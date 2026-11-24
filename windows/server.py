from __future__ import annotations

import asyncio
import hashlib
import io
import json
import os
import re
import socket
import struct
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from itertools import count
from pathlib import Path
from typing import Any

import mss
import pyautogui
import websockets
from PIL import Image

try:
    import vgamepad as vg
except Exception:
    vg = None

HOST = "0.0.0.0"
PORT = 8765
CHUNK_SIZE = 64 * 1024
MAGIC_SCREEN = 0x01
MAGIC_FILE = 0x02
PROTOCOL_VERSION = 2
RECEIVED_DIR = Path("received")
MAX_FILE_SIZE = 20 * 1024 * 1024 * 1024  # 20 GiB safety ceiling per transfer.
MAX_TEXT_LENGTH = 16_000
MAX_SEND_QUEUE = 32

QUALITY_PRESETS = {
    "saver": {"width": 512, "height": 288, "jpeg": 28},
    "standard": {"width": 1280, "height": 720, "jpeg": 55},
    "hd": {"width": 1920, "height": 1080, "jpeg": 78},
}
VALID_FPS = {15, 30, 60}

KEY_MAP = {
    "CTRL": "ctrl", "ALT": "alt", "SHIFT": "shift", "WIN": "winleft",
    "LCTRL": "ctrlleft", "RCTRL": "ctrlright", "LALT": "altleft", "RALT": "altright",
    "LSHIFT": "shiftleft", "RSHIFT": "shiftright",
    "ENTER": "enter", "ESC": "esc", "TAB": "tab", "SPACE": "space",
    "BACKSPACE": "backspace", "DELETE": "delete", "HOME": "home",
    "END": "end", "PAGEUP": "pageup", "PAGEDOWN": "pagedown",
    "UP": "up", "DOWN": "down", "LEFT": "left", "RIGHT": "right",
    "INSERT": "insert", "CAPSLOCK": "capslock", "PRINTSCREEN": "printscreen",
    "`": "`", "-": "-", "=": "=", "[": "[", "]": "]", "\\": "\\",
    ";": ";", "'": "'", ",": ",", ".": ".", "/": "/",
    **{f"F{i}": f"f{i}" for i in range(1, 13)},
}

GAMEPAD_BUTTONS = {}
if vg is not None:
    GAMEPAD_BUTTONS = {
        "A": vg.XUSB_BUTTON.XUSB_GAMEPAD_A,
        "B": vg.XUSB_BUTTON.XUSB_GAMEPAD_B,
        "X": vg.XUSB_BUTTON.XUSB_GAMEPAD_X,
        "Y": vg.XUSB_BUTTON.XUSB_GAMEPAD_Y,
        "LB": vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER,
        "RB": vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER,
        "BACK": vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK,
        "START": vg.XUSB_BUTTON.XUSB_GAMEPAD_START,
        "L3": vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB,
        "R3": vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_THUMB,
        "DPAD_UP": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP,
        "DPAD_DOWN": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN,
        "DPAD_LEFT": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT,
        "DPAD_RIGHT": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT,
    }


def private_ipv4_addresses() -> list[str]:
    values: set[str] = set()
    try:
        for item in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = item[4][0]
            if not ip.startswith(("127.", "169.254.")):
                values.add(ip)
    except OSError:
        pass
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("192.0.2.1", 9))
            ip = probe.getsockname()[0]
            if ip and not ip.startswith("127."):
                values.add(ip)
    except OSError:
        pass
    return sorted(values)


def safe_filename(name: str) -> str:
    name = Path(str(name)).name
    name = re.sub(r"[\\/:*?\"<>|\x00-\x1f]", "_", name).strip(" .")
    return (name or "received_file")[:180]


def safe_int(value: Any, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError, OverflowError):
        return default


def safe_float(value: Any, default: float = 0.0) -> float:
    try:
        result = float(value)
        return result if result == result and abs(result) != float("inf") else default
    except (TypeError, ValueError, OverflowError):
        return default


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            block = stream.read(1024 * 1024)
            if not block:
                break
            digest.update(block)
    return digest.hexdigest()


def get_windows_clipboard_text() -> str:
    """Read plain text from the Windows clipboard after an explicit client request."""
    import ctypes
    from ctypes import wintypes

    user32 = ctypes.WinDLL("user32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    CF_UNICODETEXT = 13
    user32.OpenClipboard.argtypes = [wintypes.HWND]
    user32.OpenClipboard.restype = wintypes.BOOL
    user32.IsClipboardFormatAvailable.argtypes = [wintypes.UINT]
    user32.IsClipboardFormatAvailable.restype = wintypes.BOOL
    user32.GetClipboardData.argtypes = [wintypes.UINT]
    user32.GetClipboardData.restype = wintypes.HANDLE
    user32.CloseClipboard.restype = wintypes.BOOL
    kernel32.GlobalLock.argtypes = [wintypes.HGLOBAL]
    kernel32.GlobalLock.restype = wintypes.LPVOID
    kernel32.GlobalUnlock.argtypes = [wintypes.HGLOBAL]
    kernel32.GlobalUnlock.restype = wintypes.BOOL
    kernel32.GlobalSize.argtypes = [wintypes.HGLOBAL]
    kernel32.GlobalSize.restype = ctypes.c_size_t

    if not user32.OpenClipboard(None):
        raise ctypes.WinError(ctypes.get_last_error())
    handle = None
    pointer = None
    try:
        if not user32.IsClipboardFormatAvailable(CF_UNICODETEXT):
            return ""
        handle = user32.GetClipboardData(CF_UNICODETEXT)
        if not handle:
            return ""
        pointer = kernel32.GlobalLock(handle)
        if not pointer:
            raise ctypes.WinError(ctypes.get_last_error())
        size_bytes = int(kernel32.GlobalSize(handle))
        max_chars = min(MAX_TEXT_LENGTH, max(0, size_bytes // ctypes.sizeof(ctypes.c_wchar)))
        if max_chars <= 0:
            return ""
        return ctypes.wstring_at(pointer, max_chars).split("\0", 1)[0][:MAX_TEXT_LENGTH]
    finally:
        if pointer:
            kernel32.GlobalUnlock(handle)
        user32.CloseClipboard()


def set_windows_clipboard_text(text: str) -> None:
    import ctypes
    from ctypes import wintypes

    user32 = ctypes.WinDLL("user32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    GMEM_MOVEABLE, CF_UNICODETEXT = 0x0002, 13
    payload = (text + "\0").encode("utf-16-le")
    user32.OpenClipboard.argtypes = [wintypes.HWND]
    user32.OpenClipboard.restype = wintypes.BOOL
    user32.EmptyClipboard.restype = wintypes.BOOL
    user32.SetClipboardData.argtypes = [wintypes.UINT, wintypes.HANDLE]
    user32.SetClipboardData.restype = wintypes.HANDLE
    user32.CloseClipboard.restype = wintypes.BOOL
    kernel32.GlobalAlloc.argtypes = [wintypes.UINT, ctypes.c_size_t]
    kernel32.GlobalAlloc.restype = wintypes.HGLOBAL
    kernel32.GlobalLock.argtypes = [wintypes.HGLOBAL]
    kernel32.GlobalLock.restype = wintypes.LPVOID
    kernel32.GlobalUnlock.argtypes = [wintypes.HGLOBAL]
    kernel32.GlobalUnlock.restype = wintypes.BOOL
    kernel32.GlobalFree.argtypes = [wintypes.HGLOBAL]
    kernel32.GlobalFree.restype = wintypes.HGLOBAL

    if not user32.OpenClipboard(None):
        raise ctypes.WinError(ctypes.get_last_error())
    handle = None
    try:
        if not user32.EmptyClipboard():
            raise ctypes.WinError(ctypes.get_last_error())
        handle = kernel32.GlobalAlloc(GMEM_MOVEABLE, len(payload))
        if not handle:
            raise ctypes.WinError(ctypes.get_last_error())
        pointer = kernel32.GlobalLock(handle)
        if not pointer:
            raise ctypes.WinError(ctypes.get_last_error())
        ctypes.memmove(pointer, payload, len(payload))
        kernel32.GlobalUnlock(handle)
        if not user32.SetClipboardData(CF_UNICODETEXT, handle):
            raise ctypes.WinError(ctypes.get_last_error())
        handle = None
    finally:
        user32.CloseClipboard()
        if handle:
            kernel32.GlobalFree(handle)


@dataclass
class IncomingFile:
    file_id: int
    name: str
    expected_size: int
    temp_path: Path
    final_path: Path
    stream: Any
    digest: Any = field(default_factory=hashlib.sha256)
    next_index: int = 0
    received: int = 0


@dataclass
class OutgoingFile:
    file_id: int
    path: Path
    name: str
    size: int
    sha256: str
    ready: asyncio.Event = field(default_factory=asyncio.Event)
    resume_event: asyncio.Event = field(default_factory=asyncio.Event)
    cancelled: bool = False


@dataclass
class ClientSession:
    websocket: Any
    quality: str = "saver"
    fps: int = 15
    monitor_index: int = 1
    protocol_version: int = 1
    incoming: dict[int, IncomingFile] = field(default_factory=dict)
    outgoing: dict[int, OutgoingFile] = field(default_factory=dict)
    gamepad: Any = None
    capture: Any = None
    file_picker_active: bool = False
    held_keys: set[str] = field(default_factory=set)
    held_mouse: set[str] = field(default_factory=set)
    send_queue: asyncio.PriorityQueue = field(default_factory=lambda: asyncio.PriorityQueue(MAX_SEND_QUEUE))
    send_sequence: Any = field(default_factory=count)
    screen_enqueued: bool = False
    sender_task: asyncio.Task | None = None
    last_metrics_at: float = field(default_factory=time.monotonic)
    metrics_frames: int = 0
    last_resolution: tuple[int, int] = (0, 0)
    monitor_rect: dict[str, int] = field(default_factory=lambda: {"left": 0, "top": 0, "width": 1, "height": 1})
    closed: bool = False

    async def queue_payload(self, priority: int, payload: str | bytes, *, drop_if_full: bool = False) -> bool:
        if self.closed:
            return False
        is_screen = isinstance(payload, bytes) and payload[:1] == bytes([MAGIC_SCREEN])
        # Keep at most one not-yet-sent screen frame in the queue. This avoids
        # showing seconds-old images when file traffic or a slow Wi-Fi link builds pressure.
        if is_screen and self.screen_enqueued:
            return False
        item = (priority, next(self.send_sequence), payload)
        if drop_if_full:
            try:
                self.send_queue.put_nowait(item)
                if is_screen:
                    self.screen_enqueued = True
                return True
            except asyncio.QueueFull:
                return False
        await self.send_queue.put(item)
        if is_screen:
            self.screen_enqueued = True
        return True

    async def send_json(self, payload: dict[str, Any], *, priority: int = 0) -> None:
        data = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
        await self.queue_payload(priority, data)

    async def send_binary(self, payload: bytes, *, priority: int = 5, drop_if_full: bool = False) -> bool:
        return await self.queue_payload(priority, payload, drop_if_full=drop_if_full)

    async def close_files(self) -> None:
        for transfer in list(self.incoming.values()):
            try:
                transfer.stream.close()
            except Exception:
                pass
            try:
                transfer.temp_path.unlink(missing_ok=True)
            except OSError:
                pass
        self.incoming.clear()


_ACTIVE: ClientSession | None = None
_CAPTURE_EXECUTOR = ThreadPoolExecutor(max_workers=1, thread_name_prefix="mss-capture")
_INPUT_EXECUTOR = ThreadPoolExecutor(max_workers=1, thread_name_prefix="remote-input")


async def sender_loop(session: ClientSession) -> None:
    try:
        while not session.closed:
            _priority, _sequence, payload = await session.send_queue.get()
            if isinstance(payload, bytes) and payload[:1] == bytes([MAGIC_SCREEN]):
                session.screen_enqueued = False
            try:
                await session.websocket.send(payload)
                if isinstance(payload, bytes) and payload[:1] == bytes([MAGIC_SCREEN]):
                    session.metrics_frames += 1
            finally:
                session.send_queue.task_done()
    except websockets.ConnectionClosed:
        session.closed = True
    except asyncio.CancelledError:
        pass
    except Exception as exc:
        print(f"[发送队列] {exc}")
        session.closed = True
        try:
            await session.websocket.close(code=1011, reason="send queue failure")
        except Exception:
            pass


def monitor_snapshot(session: ClientSession) -> tuple[list[dict[str, Any]], dict[str, int]]:
    if session.capture is None:
        session.capture = mss.mss()
    monitors = []
    for index, monitor in enumerate(session.capture.monitors[1:], start=1):
        monitors.append({
            "index": index,
            "name": f"显示器 {index}",
            "left": int(monitor["left"]),
            "top": int(monitor["top"]),
            "width": int(monitor["width"]),
            "height": int(monitor["height"]),
        })
    if not monitors:
        selected_rect = {"left": 0, "top": 0, "width": 1, "height": 1}
    else:
        selected = session.capture.monitors[min(max(session.monitor_index, 1), len(session.capture.monitors) - 1)]
        selected_rect = {k: int(selected[k]) for k in ("left", "top", "width", "height")}
    session.monitor_rect = selected_rect
    return monitors, selected_rect


def capture_jpeg(session: ClientSession, quality_name: str) -> tuple[bytes | None, int, int]:
    try:
        if session.capture is None:
            session.capture = mss.mss()
        monitor_index = min(max(session.monitor_index, 1), len(session.capture.monitors) - 1)
        monitor = session.capture.monitors[monitor_index]
        shot = session.capture.grab(monitor)
        image = Image.frombytes("RGB", shot.size, shot.rgb)
        preset = QUALITY_PRESETS[quality_name]
        image.thumbnail((preset["width"], preset["height"]), Image.Resampling.LANCZOS)
        width, height = image.size
        buffer = io.BytesIO()
        image.save(buffer, format="JPEG", quality=preset["jpeg"], optimize=False)
        return buffer.getvalue(), width, height
    except Exception as exc:
        print(f"[屏幕] 截图失败：{exc}")
        return None, 0, 0


def effective_quality(session: ClientSession) -> str:
    if session.quality != "adaptive":
        return session.quality
    queued = session.send_queue.qsize()
    if queued >= 6:
        return "saver"
    if queued >= 3:
        return "standard"
    return "hd"


async def screen_stream(session: ClientSession) -> None:
    while not session.closed:
        started = time.monotonic()
        quality = effective_quality(session)
        loop = asyncio.get_running_loop()
        jpeg, width, height = await loop.run_in_executor(_CAPTURE_EXECUTOR, capture_jpeg, session, quality)
        if jpeg:
            accepted = await session.send_binary(bytes([MAGIC_SCREEN]) + jpeg, priority=1, drop_if_full=True)
            if accepted:
                session.last_resolution = (width, height)
        now = time.monotonic()
        if now - session.last_metrics_at >= 3.0:
            elapsed = max(0.001, now - session.last_metrics_at)
            await session.send_json({
                "type": "screen_stats",
                "fps_actual": round(session.metrics_frames / elapsed, 1),
                "queue_depth": session.send_queue.qsize(),
                "width": session.last_resolution[0],
                "height": session.last_resolution[1],
                "quality": quality,
                "monitor": session.monitor_index,
            }, priority=2)
            session.metrics_frames = 0
            session.last_metrics_at = now
        interval = 1.0 / max(1, min(session.fps, 60))
        remaining = interval - (time.monotonic() - started)
        if remaining > 0:
            await asyncio.sleep(remaining)


def do_mouse(session: ClientSession, data: dict[str, Any]) -> None:
    action = str(data.get("action", ""))
    if action == "move":
        dx = max(-2000, min(2000, int(safe_float(data.get("dx", 0)))))
        dy = max(-2000, min(2000, int(safe_float(data.get("dy", 0)))))
        pyautogui.moveRel(dx, dy, duration=0)
    elif action == "absolute_move":
        nx = max(0.0, min(1.0, safe_float(data.get("x", 0.0))))
        ny = max(0.0, min(1.0, safe_float(data.get("y", 0.0))))
        monitor = session.monitor_rect
        x = int(monitor["left"] + nx * max(0, monitor["width"] - 1))
        y = int(monitor["top"] + ny * max(0, monitor["height"] - 1))
        pyautogui.moveTo(x, y, duration=0)
    elif action == "click":
        button = str(data.get("button", "left"))
        if button in ("left", "right", "middle"):
            pyautogui.click(button=button)
    elif action in ("down", "up"):
        button = str(data.get("button", "left"))
        if button in ("left", "right", "middle"):
            (pyautogui.mouseDown if action == "down" else pyautogui.mouseUp)(button=button)
            if action == "down":
                session.held_mouse.add(button)
            else:
                session.held_mouse.discard(button)
    elif action == "scroll":
        amount = max(-30, min(30, int(safe_float(data.get("amount", 0)))))
        if amount:
            pyautogui.scroll(amount)
    elif action == "double_click":
        button = str(data.get("button", "left"))
        if button in ("left", "right", "middle"):
            pyautogui.doubleClick(button=button)


def do_key(session: ClientSession, data: dict[str, Any]) -> None:
    action = str(data.get("action", ""))
    if action == "text":
        value = str(data.get("text", ""))[:MAX_TEXT_LENGTH]
        if value:
            set_windows_clipboard_text(value)
            pyautogui.hotkey("ctrl", "v")
        return
    if action == "hotkey":
        keys = data.get("keys", [])
        if not isinstance(keys, list) or not 1 <= len(keys) <= 6:
            return
        mapped = [KEY_MAP.get(str(k).upper()) for k in keys]
        if all(mapped):
            pyautogui.hotkey(*mapped)
        return
    raw_key = str(data.get("key", "")).upper()
    key = KEY_MAP.get(raw_key)
    if key is None and len(raw_key) == 1 and (raw_key.isalnum() or raw_key in KEY_MAP):
        key = raw_key.lower() if raw_key.isalpha() else raw_key
    if key is None:
        return
    if action == "down":
        pyautogui.keyDown(key)
        session.held_keys.add(key)
    elif action == "up":
        pyautogui.keyUp(key)
        session.held_keys.discard(key)
    elif action == "press":
        pyautogui.press(key)


def release_all_inputs(session: ClientSession) -> None:
    for key in list(session.held_keys):
        try:
            pyautogui.keyUp(key)
        except Exception:
            pass
    session.held_keys.clear()
    for button in list(session.held_mouse):
        try:
            pyautogui.mouseUp(button=button)
        except Exception:
            pass
    session.held_mouse.clear()
    if session.gamepad is not None:
        try:
            session.gamepad.reset()
            session.gamepad.update()
        except Exception:
            pass


def do_gamepad(session: ClientSession, data: dict[str, Any]) -> None:
    if session.gamepad is None:
        return
    action = str(data.get("action", ""))
    if action == "button":
        name = str(data.get("button", "")).upper()
        button = GAMEPAD_BUTTONS.get(name)
        if button is not None:
            if bool(data.get("pressed", False)):
                session.gamepad.press_button(button=button)
            else:
                session.gamepad.release_button(button=button)
            session.gamepad.update()
    elif action == "axis":
        axis = str(data.get("axis", "")).upper()
        if axis in ("LX", "LY", "RX", "RY"):
            x = max(-1.0, min(1.0, safe_float(data.get("lx" if axis[0] == "L" else "rx", 0.0))))
            y = max(-1.0, min(1.0, safe_float(data.get("ly" if axis[0] == "L" else "ry", 0.0))))
            if axis[0] == "L":
                session.gamepad.left_joystick_float(x_value_float=x, y_value_float=y)
            else:
                session.gamepad.right_joystick_float(x_value_float=x, y_value_float=y)
            session.gamepad.update()
    elif action == "trigger":
        name = str(data.get("trigger", "")).upper()
        raw = int(round(max(0.0, min(1.0, safe_float(data.get("value", 0.0)))) * 255))
        if name == "LT":
            session.gamepad.left_trigger(value=raw)
        elif name == "RT":
            session.gamepad.right_trigger(value=raw)
        else:
            return
        session.gamepad.update()


async def abort_incoming_file(session: ClientSession, file_id: int, message: str) -> None:
    transfer = session.incoming.pop(file_id, None)
    if transfer is not None:
        try:
            await asyncio.to_thread(transfer.stream.close)
        except Exception:
            pass
        try:
            await asyncio.to_thread(transfer.temp_path.unlink, missing_ok=True)
        except Exception:
            pass
    await session.send_json({"type": "file_error", "file_id": file_id, "message": message})


async def handle_binary(session: ClientSession, data: bytes) -> None:
    if not data or data[0] != MAGIC_FILE:
        return
    if len(data) < 9:
        await session.send_json({"type": "file_error", "message": "文件数据包长度不足"})
        return
    file_id, chunk_index = struct.unpack("!II", data[1:9])
    transfer = session.incoming.get(file_id)
    if transfer is None:
        await session.send_json({"type": "file_error", "file_id": file_id, "message": "未知文件传输 ID"})
        return
    chunk = data[9:]
    if chunk_index != transfer.next_index:
        await abort_incoming_file(session, file_id,
            f"块序号错误，期望 {transfer.next_index}，收到 {chunk_index}")
        return
    if (len(chunk) > CHUNK_SIZE or transfer.received + len(chunk) > MAX_FILE_SIZE or
            (transfer.expected_size >= 0 and transfer.received + len(chunk) > transfer.expected_size)):
        await abort_incoming_file(session, file_id, "文件块大小或总长度超限")
        return
    try:
        await asyncio.to_thread(transfer.stream.write, chunk)
    except Exception as exc:
        await abort_incoming_file(session, file_id, f"写入临时文件失败：{exc}")
        return
    transfer.digest.update(chunk)
    transfer.received += len(chunk)
    transfer.next_index += 1
    if transfer.received and transfer.received % (1024 * 1024) < CHUNK_SIZE:
        await session.send_json({"type": "file_progress", "file_id": file_id,
                                 "done": transfer.received, "total": transfer.expected_size}, priority=4)


async def begin_incoming_file(session: ClientSession, data: dict[str, Any]) -> None:
    try:
        file_id = int(data.get("file_id", -1))
        size = int(data.get("size", -1))
    except (TypeError, ValueError):
        file_id, size = -1, -1
    name = safe_filename(str(data.get("name", "received_file")))
    if data.get("direction") != "to_server" or not 0 <= file_id <= 0xFFFFFFFF:
        await session.send_json({"type": "file_error", "file_id": file_id, "message": "文件开始参数无效"})
        return
    if size < -1 or size > MAX_FILE_SIZE:
        await session.send_json({"type": "file_error", "file_id": file_id, "message": "文件大小不在允许范围内"})
        return
    if file_id in session.incoming or file_id in session.outgoing:
        await session.send_json({"type": "file_error", "file_id": file_id, "message": "文件 ID 冲突"})
        return
    RECEIVED_DIR.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d_%H%M%S")
    final_path = RECEIVED_DIR / f"{stamp}_{name}"
    suffix = 1
    while final_path.exists():
        final_path = RECEIVED_DIR / f"{stamp}_{suffix}_{name}"
        suffix += 1
    temp_path = final_path.with_name(final_path.name + ".part")
    stream = await asyncio.to_thread(open, temp_path, "wb")
    session.incoming[file_id] = IncomingFile(file_id, name, size, temp_path, final_path, stream)
    await session.send_json({"type": "file_ready", "file_id": file_id,
                             "direction": "to_server", "chunk_size": CHUNK_SIZE})
    print(f"[文件] Android 开始上传：{name} ({size} bytes)")


async def finish_incoming_file(session: ClientSession, data: dict[str, Any]) -> None:
    file_id = safe_int(data.get("file_id", -1), -1)
    transfer = session.incoming.pop(file_id, None)
    if transfer is None:
        await session.send_json({"type": "file_error", "file_id": file_id, "message": "找不到待完成的文件"})
        return
    try:
        await asyncio.to_thread(transfer.stream.flush)
        await asyncio.to_thread(transfer.stream.close)
        if transfer.expected_size >= 0 and transfer.received != transfer.expected_size:
            raise ValueError(f"文件长度不一致：收到 {transfer.received} / {transfer.expected_size}")
        end_size = safe_int(data.get("size", -1), -1)
        if session.protocol_version >= 2 and end_size < 0:
            raise ValueError("协议 v2 文件结束消息缺少有效长度")
        if end_size >= 0 and end_size != transfer.received:
            raise ValueError(f"结束消息长度不一致：收到 {transfer.received} / {end_size}")
        actual_hash = transfer.digest.hexdigest()
        expected_hash = str(data.get("sha256", "")).lower()
        if session.protocol_version >= 2 and not expected_hash:
            raise ValueError("协议 v2 文件结束消息缺少 SHA-256")
        if expected_hash and actual_hash != expected_hash:
            raise ValueError("SHA-256 校验失败，文件可能损坏")
        await asyncio.to_thread(os.replace, transfer.temp_path, transfer.final_path)
        await session.send_json({"type": "file_complete", "file_id": file_id, "name": transfer.name,
                                 "size": transfer.received, "sha256": actual_hash,
                                 "path": str(transfer.final_path.resolve())})
        print(f"[文件] 已接收并校验：{transfer.final_path.resolve()}")
    except Exception as exc:
        try:
            transfer.temp_path.unlink(missing_ok=True)
        except OSError:
            pass
        await session.send_json({"type": "file_error", "file_id": file_id, "message": str(exc)})


def choose_file_dialog() -> Path | None:
    import tkinter as tk
    from tkinter import filedialog
    root = tk.Tk()
    root.withdraw()
    root.attributes("-topmost", True)
    try:
        selected = filedialog.askopenfilename(parent=root, title="选择要发送到 Android 的文件")
        return Path(selected) if selected else None
    finally:
        root.destroy()


async def choose_and_send_file(session: ClientSession) -> None:
    try:
        selected = await asyncio.to_thread(choose_file_dialog)
        if selected is not None and not session.closed:
            await send_file_to_client(session, selected)
    except Exception as exc:
        print(f"[文件] 打开选择框或发送失败：{exc}")
        try:
            await session.send_json({"type": "file_error", "file_id": -1, "message": str(exc)})
        except Exception:
            pass
    finally:
        session.file_picker_active = False


async def send_file_to_client(session: ClientSession, path: Path) -> None:
    if not path.is_file():
        print(f"[文件] 文件不存在：{path}")
        return
    size = path.stat().st_size
    if size > MAX_FILE_SIZE:
        raise ValueError("文件超过 20 GiB 限制")
    file_id = int.from_bytes(os.urandom(4), "big")
    while file_id in session.outgoing or file_id in session.incoming:
        file_id = int.from_bytes(os.urandom(4), "big")
    digest = await asyncio.to_thread(sha256_file, path)
    transfer = OutgoingFile(file_id, path, path.name, size, digest)
    transfer.resume_event.set()
    session.outgoing[file_id] = transfer
    try:
        await session.send_json({"type": "file_start", "file_id": file_id, "name": path.name,
                                 "size": size, "sha256": digest, "direction": "to_client",
                                 "chunk_size": CHUNK_SIZE})
        await asyncio.wait_for(transfer.ready.wait(), timeout=120)
        if transfer.cancelled:
            return
        sent = 0
        index = 0
        with path.open("rb") as stream:
            while not transfer.cancelled:
                await transfer.resume_event.wait()
                if transfer.cancelled:
                    break
                chunk = await asyncio.to_thread(stream.read, CHUNK_SIZE)
                if not chunk:
                    break
                packet = bytes([MAGIC_FILE]) + struct.pack("!II", file_id, index) + chunk
                await session.send_binary(packet, priority=5)
                sent += len(chunk)
                index += 1
                if sent == size or sent % (1024 * 1024) < CHUNK_SIZE:
                    await session.send_json({"type": "file_progress", "file_id": file_id,
                                             "done": sent, "total": size}, priority=4)
        if transfer.cancelled:
            await session.send_json({"type": "file_error", "file_id": file_id, "message": "接收端取消了传输"})
            return
        # Keep the end marker at the same FIFO priority as file chunks. A high-priority
        # marker could overtake queued chunks and make the receiver finalize too early.
        await session.send_json({"type": "file_end", "file_id": file_id, "direction": "to_client",
                                 "size": sent, "sha256": digest}, priority=5)
        print(f"[文件] 已发送并提供 SHA-256 校验：{path.name} ({sent} bytes)")
    except asyncio.TimeoutError:
        await session.send_json({"type": "file_error", "file_id": file_id, "message": "等待 Android 确认保存超时"})
    finally:
        session.outgoing.pop(file_id, None)


async def handle_text(session: ClientSession, raw: str) -> None:
    if len(raw) > 64 * 1024:
        await session.send_json({"type": "error", "message": "控制消息超过 64 KiB 限制"})
        return
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        await session.send_json({"type": "error", "message": "无效 JSON"})
        return
    if not isinstance(data, dict):
        return
    kind = str(data.get("type", ""))
    loop = asyncio.get_running_loop()

    if kind == "client_hello":
        requested = safe_int(data.get("protocol_version", 1), 1)
        session.protocol_version = min(max(requested, 1), PROTOCOL_VERSION)
        monitors, selected = await loop.run_in_executor(_CAPTURE_EXECUTOR, monitor_snapshot, session)
        await session.send_json({"type": "hello_ack", "protocol_version": session.protocol_version,
                                 "server_version": "2.0.0", "chunk_size": CHUNK_SIZE,
                                 "quality": session.quality, "fps": session.fps,
                                 "monitors": monitors, "selected_monitor": session.monitor_index,
                                 "capabilities": ["absolute_touch", "relative_mouse", "gamepad", "files_sha256",
                                                  "multi_display", "screen_stats", "priority_send", "clipboard_manual"]})
    elif kind == "screen_ctrl":
        quality = str(data.get("quality", "saver"))
        fps = safe_int(data.get("fps", 15), 15)
        monitor = safe_int(data.get("monitor", session.monitor_index), session.monitor_index)
        if quality not in (*QUALITY_PRESETS.keys(), "adaptive") or fps not in VALID_FPS:
            await session.send_json({"type": "error", "message": "不支持的画面档位"})
            return
        try:
            # Select before reading the geometry so direct-touch coordinates use the same monitor.
            session.monitor_index = monitor
            monitors, _selected = await loop.run_in_executor(_CAPTURE_EXECUTOR, monitor_snapshot, session)
            if monitor not in {m["index"] for m in monitors}:
                monitor = 1
                session.monitor_index = monitor
                monitors, _selected = await loop.run_in_executor(_CAPTURE_EXECUTOR, monitor_snapshot, session)
        except Exception:
            monitor = 1
            session.monitor_index = monitor
        session.quality, session.fps, session.monitor_index = quality, fps, monitor
        await session.send_json({"type": "screen_ctrl_ack", "quality": quality, "fps": fps,
                                 "monitor": monitor})
        try:
            await session.send_json({"type": "monitors", "items": monitors, "selected": monitor})
        except Exception:
            pass
    elif kind == "mouse":
        try:
            await loop.run_in_executor(_INPUT_EXECUTOR, do_mouse, session, data)
        except Exception as exc:
            print(f"[鼠标] {exc}")
    elif kind == "key":
        try:
            await loop.run_in_executor(_INPUT_EXECUTOR, do_key, session, data)
        except Exception as exc:
            print(f"[键盘] {exc}")
    elif kind == "gamepad":
        try:
            await loop.run_in_executor(_INPUT_EXECUTOR, do_gamepad, session, data)
        except Exception as exc:
            print(f"[手柄] {exc}")
    elif kind == "state_reset":
        await loop.run_in_executor(_INPUT_EXECUTOR, release_all_inputs, session)
        await session.send_json({"type": "state_reset_ack"})
    elif kind == "file_request":
        if not session.file_picker_active:
            session.file_picker_active = True
            asyncio.create_task(choose_and_send_file(session), name="choose-file-to-client")
    elif kind == "file_start":
        await begin_incoming_file(session, data)
    elif kind == "file_ready":
        file_id = safe_int(data.get("file_id", -1), -1)
        transfer = session.outgoing.get(file_id)
        if transfer and data.get("direction") == "to_client":
            transfer.ready.set()
    elif kind == "file_end" and data.get("direction") == "to_server":
        await finish_incoming_file(session, data)
    elif kind in ("file_pause", "file_resume"):
        file_id = safe_int(data.get("file_id", -1), -1)
        outgoing = session.outgoing.get(file_id)
        if outgoing:
            if kind == "file_pause":
                outgoing.resume_event.clear()
            else:
                outgoing.resume_event.set()
        await session.send_json({"type": "file_pause_ack", "file_id": file_id,
                                 "paused": kind == "file_pause"})
    elif kind == "file_cancel":
        file_id = safe_int(data.get("file_id", -1), -1)
        transfer = session.incoming.pop(file_id, None)
        if transfer:
            try:
                transfer.stream.close()
                transfer.temp_path.unlink(missing_ok=True)
            except OSError:
                pass
        outgoing = session.outgoing.get(file_id)
        if outgoing:
            outgoing.cancelled = True
            outgoing.ready.set()
            outgoing.resume_event.set()
    elif kind == "clipboard_get":
        try:
            text = await loop.run_in_executor(_INPUT_EXECUTOR, get_windows_clipboard_text)
            await session.send_json({"type": "clipboard_text", "text": text[:MAX_TEXT_LENGTH]})
        except Exception as exc:
            await session.send_json({"type": "error", "message": f"读取 Windows 剪贴板失败：{exc}"})
    elif kind == "ping":
        await session.send_json({"type": "pong", "time": data.get("time")}, priority=1)


async def client_handler(websocket: Any) -> None:
    global _ACTIVE
    if _ACTIVE is not None and not _ACTIVE.closed:
        await websocket.close(code=1013, reason="Only one controller is supported")
        return

    session = ClientSession(websocket)
    if vg is not None:
        try:
            session.gamepad = vg.VX360Gamepad()
            print("[手柄] Xbox 360 虚拟手柄已就绪")
        except Exception as exc:
            print(f"[手柄] 初始化失败（请确认 ViGEmBus 驱动）：{exc}")
    else:
        print("[手柄] vgamepad 未安装，手柄功能不可用")

    _ACTIVE = session
    session.sender_task = asyncio.create_task(sender_loop(session), name="ws-sender")
    print(f"[连接] Android 已连接：{websocket.remote_address}")
    await session.send_json({"type": "hello", "server": "LAN Touchpad Server", "server_version": "2.0.0",
                             "protocol_version": PROTOCOL_VERSION, "chunk_size": CHUNK_SIZE,
                             "quality": session.quality, "fps": session.fps})
    streamer = asyncio.create_task(screen_stream(session), name="screen-stream")
    try:
        async for message in websocket:
            if isinstance(message, bytes):
                await handle_binary(session, message)
            else:
                await handle_text(session, message)
    except websockets.ConnectionClosed:
        pass
    finally:
        session.closed = True
        streamer.cancel()
        try:
            await streamer
        except asyncio.CancelledError:
            pass
        await session.close_files()
        try:
            await loop_safe_release(session)
        except Exception as exc:
            print(f"[输入] 释放按键失败：{exc}")
        if session.capture is not None:
            try:
                await asyncio.get_running_loop().run_in_executor(_CAPTURE_EXECUTOR, session.capture.close)
            except Exception:
                pass
            session.capture = None
        if session.sender_task:
            session.sender_task.cancel()
            try:
                await session.sender_task
            except asyncio.CancelledError:
                pass
        if _ACTIVE is session:
            _ACTIVE = None
        print("[断开] Android 已断开，输入状态已释放")


async def loop_safe_release(session: ClientSession) -> None:
    await asyncio.get_running_loop().run_in_executor(_INPUT_EXECUTOR, release_all_inputs, session)


async def console_loop(stop_event: asyncio.Event) -> None:
    print("\n命令：send <文件完整路径>  |  status  |  help  |  quit")
    while not stop_event.is_set():
        line = await asyncio.to_thread(sys.stdin.readline)
        if not line:
            await asyncio.sleep(0.2)
            continue
        command = line.strip()
        if command.lower() in ("quit", "exit"):
            stop_event.set()
            return
        if command.lower() == "help":
            print("send <path>：发送电脑文件到 Android；status：连接状态；quit：退出")
            continue
        if command.lower() == "status":
            print("已连接" if _ACTIVE else "等待 Android 连接")
            if _ACTIVE:
                print(f"协议 v{_ACTIVE.protocol_version} · 画质 {_ACTIVE.quality} · {_ACTIVE.fps} FPS · 队列 {_ACTIVE.send_queue.qsize()}")
            continue
        if command.lower().startswith("send "):
            if _ACTIVE is None:
                print("[提示] 请先在 Android 连接本机")
                continue
            raw_path = command[5:].strip().strip('"')
            try:
                await send_file_to_client(_ACTIVE, Path(raw_path).expanduser())
            except Exception as exc:
                print(f"[命令] {exc}")
            continue
        if command:
            print("未知命令。输入 help 查看帮助。")


async def main() -> None:
    pyautogui.FAILSAFE = True
    pyautogui.PAUSE = 0
    RECEIVED_DIR.mkdir(parents=True, exist_ok=True)
    stop_event = asyncio.Event()
    print("=" * 64)
    print("LAN Touchpad Server 2.0 — 局域网远程控制")
    print(f"监听：ws://0.0.0.0:{PORT}")
    print("平板连接地址：")
    addresses = private_ipv4_addresses()
    if addresses:
        for address in addresses:
            print(f"  ws://{address}:{PORT}")
    else:
        print("  未能自动识别，请在 Windows 网络设置中查看本机 IPv4")
    print("接收文件目录：", RECEIVED_DIR.resolve())
    print("安全提示：无账号/无加密，仅在可信局域网使用；不要将 8765 端口暴露到互联网。")
    print("=" * 64)

    async with websockets.serve(
        client_handler, HOST, PORT, max_size=16 * 1024 * 1024, max_queue=16,
        compression=None, ping_interval=20, ping_timeout=20, close_timeout=5,
    ):
        console_task = asyncio.create_task(console_loop(stop_event))
        await stop_event.wait()
        console_task.cancel()
        try:
            await console_task
        except asyncio.CancelledError:
            pass


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n服务器已停止")
