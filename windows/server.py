from __future__ import annotations

import asyncio
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
RECEIVED_DIR = Path("received")

QUALITY_PRESETS = {
    "saver": {"width": 512, "height": 288, "jpeg": 28},
    "standard": {"width": 1280, "height": 720, "jpeg": 55},
    "hd": {"width": 1920, "height": 1080, "jpeg": 78},
}
VALID_FPS = {15, 30, 60}

# Remote key names are deliberately restricted to PyAutoGUI's known key names.
KEY_MAP = {
    "CTRL": "ctrl", "ALT": "alt", "SHIFT": "shift", "WIN": "winleft",
    "ENTER": "enter", "ESC": "esc", "TAB": "tab", "SPACE": "space",
    "BACKSPACE": "backspace", "DELETE": "delete", "HOME": "home",
    "END": "end", "PAGEUP": "pageup", "PAGEDOWN": "pagedown",
    "UP": "up", "DOWN": "down", "LEFT": "left", "RIGHT": "right",
    "F1": "f1", "F2": "f2", "F3": "f3", "F4": "f4", "F5": "f5",
    "F6": "f6", "F7": "f7", "F8": "f8", "F9": "f9", "F10": "f10",
    "F11": "f11", "F12": "f12", "PRINTSCREEN": "printscreen",
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
    """Return candidate local IPv4 addresses without contacting an external host."""
    values: set[str] = set()
    try:
        for item in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = item[4][0]
            if not ip.startswith("127.") and not ip.startswith("169.254."):
                values.add(ip)
    except OSError:
        pass
    # This selects the default interface locally; UDP connect sends no payload.
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("192.0.2.1", 9))
        ip = probe.getsockname()[0]
        probe.close()
        if ip and not ip.startswith("127."):
            values.add(ip)
    except OSError:
        pass
    return sorted(values)


def safe_filename(name: str) -> str:
    name = Path(str(name)).name
    name = re.sub(r"[\\/:*?\"<>|\x00-\x1f]", "_", name).strip(" .")
    return (name or "received_file")[:180]


def set_windows_clipboard_text(text: str) -> None:
    """Set Unicode clipboard text using Win32 APIs, avoiding an extra dependency."""
    import ctypes
    from ctypes import wintypes

    user32 = ctypes.WinDLL("user32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    GMEM_MOVEABLE = 0x0002
    CF_UNICODETEXT = 13
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
        handle = None  # Ownership transferred to the system.
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
    next_index: int = 0
    received: int = 0


@dataclass
class OutgoingFile:
    file_id: int
    path: Path
    name: str
    size: int
    ready: asyncio.Event = field(default_factory=asyncio.Event)
    cancelled: bool = False


@dataclass
class ClientSession:
    websocket: Any
    send_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    quality: str = "saver"
    fps: int = 15
    incoming: dict[int, IncomingFile] = field(default_factory=dict)
    outgoing: dict[int, OutgoingFile] = field(default_factory=dict)
    gamepad: Any = None
    capture: Any = None
    file_picker_active: bool = False

    async def send_json(self, payload: dict[str, Any]) -> None:
        data = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
        async with self.send_lock:
            await self.websocket.send(data)

    async def send_binary(self, payload: bytes) -> None:
        async with self.send_lock:
            await self.websocket.send(payload)

    async def close_files(self) -> None:
        for transfer in self.incoming.values():
            try:
                transfer.stream.close()
            except Exception:
                pass
        self.incoming.clear()


_ACTIVE: ClientSession | None = None
_CAPTURE_EXECUTOR = ThreadPoolExecutor(max_workers=1, thread_name_prefix="mss-capture")
_INPUT_EXECUTOR = ThreadPoolExecutor(max_workers=1, thread_name_prefix="remote-input")


def capture_jpeg(session: ClientSession) -> bytes | None:
    """Capture and JPEG-encode one frame. Called in asyncio's worker thread."""
    try:
        if session.capture is None:
            session.capture = mss.mss()
        monitor = session.capture.monitors[1]  # Primary monitor.
        shot = session.capture.grab(monitor)
        image = Image.frombytes("RGB", shot.size, shot.rgb)
        preset = QUALITY_PRESETS[session.quality]
        image.thumbnail((preset["width"], preset["height"]), Image.Resampling.LANCZOS)
        buffer = io.BytesIO()
        image.save(buffer, format="JPEG", quality=preset["jpeg"], optimize=False)
        return buffer.getvalue()
    except Exception as exc:
        print(f"[屏幕] 截图失败：{exc}")
        return None


async def screen_stream(session: ClientSession) -> None:
    while True:
        started = time.monotonic()
        loop = asyncio.get_running_loop()
        jpeg = await loop.run_in_executor(_CAPTURE_EXECUTOR, capture_jpeg, session)
        if jpeg:
            try:
                await session.send_binary(bytes([MAGIC_SCREEN]) + jpeg)
            except websockets.ConnectionClosed:
                return
        interval = 1.0 / max(1, min(session.fps, 60))
        remaining = interval - (time.monotonic() - started)
        if remaining > 0:
            await asyncio.sleep(remaining)


def do_mouse(data: dict[str, Any]) -> None:
    action = str(data.get("action", ""))
    if action == "move":
        dx = max(-2000, min(2000, int(float(data.get("dx", 0)))))
        dy = max(-2000, min(2000, int(float(data.get("dy", 0)))))
        pyautogui.moveRel(dx, dy, duration=0)
    elif action == "click":
        button = data.get("button", "left")
        if button in ("left", "right", "middle"):
            pyautogui.click(button=button)
    elif action in ("down", "up"):
        button = data.get("button", "left")
        if button in ("left", "right", "middle"):
            (pyautogui.mouseDown if action == "down" else pyautogui.mouseUp)(button=button)
    elif action == "scroll":
        amount = max(-20, min(20, int(float(data.get("amount", 0)))))
        if amount:
            pyautogui.scroll(amount)


def do_key(data: dict[str, Any]) -> None:
    action = str(data.get("action", ""))
    if action == "text":
        value = str(data.get("text", ""))[:4000]
        if value:
            set_windows_clipboard_text(value)
            pyautogui.hotkey("ctrl", "v")
        return

    if action == "hotkey":
        keys = data.get("keys", [])
        if not isinstance(keys, list) or not 1 <= len(keys) <= 4:
            return
        mapped = [KEY_MAP.get(str(k).upper()) for k in keys]
        if all(mapped):
            pyautogui.hotkey(*mapped)
        return

    raw_key = str(data.get("key", "")).upper()
    key = KEY_MAP.get(raw_key)
    if key is None and len(raw_key) == 1 and raw_key.isalnum():
        key = raw_key.lower()
    if key is None:
        return
    if action == "down":
        pyautogui.keyDown(key)
    elif action == "up":
        pyautogui.keyUp(key)
    elif action == "press":
        pyautogui.press(key)


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
        value = max(-1.0, min(1.0, float(data.get("value", 0.0))))
        if axis in ("LX", "LY"):
            current_x = float(data.get("lx", value if axis == "LX" else 0.0))
            current_y = float(data.get("ly", value if axis == "LY" else 0.0))
            session.gamepad.left_joystick_float(
                x_value_float=max(-1.0, min(1.0, current_x)),
                y_value_float=max(-1.0, min(1.0, current_y)),
            )
            session.gamepad.update()
        elif axis in ("RX", "RY"):
            current_x = float(data.get("rx", value if axis == "RX" else 0.0))
            current_y = float(data.get("ry", value if axis == "RY" else 0.0))
            session.gamepad.right_joystick_float(
                x_value_float=max(-1.0, min(1.0, current_x)),
                y_value_float=max(-1.0, min(1.0, current_y)),
            )
            session.gamepad.update()
    elif action == "trigger":
        name = str(data.get("trigger", "")).upper()
        value = max(0.0, min(1.0, float(data.get("value", 0.0))))
        raw = int(round(value * 255))
        if name == "LT":
            session.gamepad.left_trigger(value=raw)
        elif name == "RT":
            session.gamepad.right_trigger(value=raw)
        else:
            return
        session.gamepad.update()


async def handle_binary(session: ClientSession, data: bytes) -> None:
    if not data:
        return
    packet_type = data[0]
    if packet_type != MAGIC_FILE:
        return
    if len(data) < 9:
        await session.send_json({"type": "file_error", "message": "文件数据包长度不足"})
        return
    file_id, chunk_index = struct.unpack("!II", data[1:9])
    transfer = session.incoming.get(file_id)
    if transfer is None:
        await session.send_json({"type": "file_error", "file_id": file_id, "message": "未知文件传输 ID"})
        return
    if chunk_index != transfer.next_index:
        await session.send_json({"type": "file_error", "file_id": file_id, "message": f"块序号错误，期望 {transfer.next_index}，收到 {chunk_index}"})
        return
    chunk = data[9:]
    if len(chunk) > CHUNK_SIZE or transfer.received + len(chunk) > max(transfer.expected_size, 0) and transfer.expected_size >= 0:
        await session.send_json({"type": "file_error", "file_id": file_id, "message": "文件块大小或总长度超限"})
        return
    await asyncio.to_thread(transfer.stream.write, chunk)
    transfer.received += len(chunk)
    transfer.next_index += 1
    if transfer.received and transfer.received % (1024 * 1024) < CHUNK_SIZE:
        await session.send_json({"type": "file_progress", "file_id": file_id, "done": transfer.received, "total": transfer.expected_size})


async def begin_incoming_file(session: ClientSession, data: dict[str, Any]) -> None:
    file_id = int(data.get("file_id", -1))
    name = safe_filename(str(data.get("name", "received_file")))
    size = int(data.get("size", -1))
    direction = str(data.get("direction", ""))
    if direction != "to_server" or not 0 <= file_id <= 0xFFFFFFFF or size < -1:
        await session.send_json({"type": "file_error", "file_id": file_id, "message": "文件开始参数无效"})
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
    await session.send_json({"type": "file_ready", "file_id": file_id, "direction": "to_server", "chunk_size": CHUNK_SIZE})
    print(f"[文件] 平板开始上传：{name} ({size} bytes)")


async def finish_incoming_file(session: ClientSession, data: dict[str, Any]) -> None:
    file_id = int(data.get("file_id", -1))
    transfer = session.incoming.pop(file_id, None)
    if transfer is None:
        await session.send_json({"type": "file_error", "file_id": file_id, "message": "找不到待完成的文件"})
        return
    await asyncio.to_thread(transfer.stream.flush)
    await asyncio.to_thread(transfer.stream.close)
    if transfer.expected_size >= 0 and transfer.received != transfer.expected_size:
        try:
            transfer.temp_path.unlink(missing_ok=True)
        except OSError:
            pass
        await session.send_json({"type": "file_error", "file_id": file_id, "message": f"文件长度不一致：收到 {transfer.received} / {transfer.expected_size}"})
        return
    await asyncio.to_thread(os.replace, transfer.temp_path, transfer.final_path)
    await session.send_json({"type": "file_complete", "file_id": file_id, "name": transfer.name, "size": transfer.received, "path": str(transfer.final_path.resolve())})
    print(f"[文件] 已接收：{transfer.final_path.resolve()}")


def choose_file_dialog() -> Path | None:
    """Open a native Windows file picker. Tk objects are created and destroyed in this worker thread."""
    import tkinter as tk
    from tkinter import filedialog

    root = tk.Tk()
    root.withdraw()
    root.attributes("-topmost", True)
    try:
        selected = filedialog.askopenfilename(parent=root, title="选择要发送到 Android 平板的文件")
        return Path(selected) if selected else None
    finally:
        root.destroy()


async def choose_and_send_file(session: ClientSession) -> None:
    try:
        selected = await asyncio.to_thread(choose_file_dialog)
        if selected is not None:
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
    file_id = int.from_bytes(os.urandom(4), "big")
    while file_id in session.outgoing or file_id in session.incoming:
        file_id = int.from_bytes(os.urandom(4), "big")
    transfer = OutgoingFile(file_id, path, path.name, path.stat().st_size)
    session.outgoing[file_id] = transfer
    try:
        await session.send_json({
            "type": "file_start", "file_id": file_id, "name": path.name,
            "size": transfer.size, "direction": "to_client", "chunk_size": CHUNK_SIZE,
        })
        print(f"[文件] 已发送文件信息，等待平板选择保存位置：{path.name}")
        await asyncio.wait_for(transfer.ready.wait(), timeout=90)
        if transfer.cancelled:
            print(f"[文件] 平板取消接收：{path.name}")
            return
        sent = 0
        index = 0
        with path.open("rb") as stream:
            while True:
                chunk = await asyncio.to_thread(stream.read, CHUNK_SIZE)
                if not chunk:
                    break
                if transfer.cancelled:
                    await session.send_json({"type": "file_error", "file_id": file_id, "message": "接收端取消了传输"})
                    return
                packet = bytes([MAGIC_FILE]) + struct.pack("!II", file_id, index) + chunk
                await session.send_binary(packet)
                sent += len(chunk)
                index += 1
                if sent == transfer.size or sent % (1024 * 1024) < CHUNK_SIZE:
                    await session.send_json({"type": "file_progress", "file_id": file_id, "done": sent, "total": transfer.size})
        await session.send_json({"type": "file_end", "file_id": file_id, "direction": "to_client", "size": sent})
        print(f"[文件] 发送完成：{path.name} ({sent} bytes)")
    except asyncio.TimeoutError:
        await session.send_json({"type": "file_error", "file_id": file_id, "message": "等待平板确认保存超时"})
    except Exception as exc:
        print(f"[文件] 发送失败：{exc}")
        try:
            await session.send_json({"type": "file_error", "file_id": file_id, "message": str(exc)})
        except Exception:
            pass
    finally:
        session.outgoing.pop(file_id, None)


async def handle_text(session: ClientSession, raw: str) -> None:
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        await session.send_json({"type": "error", "message": "无效 JSON"})
        return
    if not isinstance(data, dict):
        return
    kind = str(data.get("type", ""))

    if kind == "screen_ctrl":
        quality = str(data.get("quality", "saver"))
        fps = int(data.get("fps", 15))
        if quality not in QUALITY_PRESETS or fps not in VALID_FPS:
            await session.send_json({"type": "error", "message": "不支持的画面档位"})
            return
        session.quality = quality
        session.fps = fps
        await session.send_json({"type": "screen_ctrl_ack", "quality": quality, "fps": fps})
    elif kind == "mouse":
        try:
            loop = asyncio.get_running_loop()
            await loop.run_in_executor(_INPUT_EXECUTOR, do_mouse, data)
        except Exception as exc:
            print(f"[鼠标] {exc}")
    elif kind == "key":
        try:
            loop = asyncio.get_running_loop()
            await loop.run_in_executor(_INPUT_EXECUTOR, do_key, data)
        except Exception as exc:
            print(f"[键盘] {exc}")
    elif kind == "gamepad":
        try:
            loop = asyncio.get_running_loop()
            await loop.run_in_executor(_INPUT_EXECUTOR, do_gamepad, session, data)
        except Exception as exc:
            print(f"[手柄] {exc}")
    elif kind == "file_request":
        if not session.file_picker_active:
            session.file_picker_active = True
            asyncio.create_task(choose_and_send_file(session), name="choose-file-to-client")
    elif kind == "file_start":
        await begin_incoming_file(session, data)
    elif kind == "file_ready":
        file_id = int(data.get("file_id", -1))
        transfer = session.outgoing.get(file_id)
        if transfer and data.get("direction") == "to_client":
            transfer.ready.set()
    elif kind == "file_end":
        if data.get("direction") == "to_server":
            await finish_incoming_file(session, data)
    elif kind == "file_cancel":
        file_id = int(data.get("file_id", -1))
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
    elif kind == "ping":
        await session.send_json({"type": "pong", "time": data.get("time")})


async def client_handler(websocket: Any) -> None:
    global _ACTIVE
    if _ACTIVE is not None:
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
    print(f"[连接] 平板已连接：{websocket.remote_address}")
    await session.send_json({"type": "hello", "server": "LAN Touchpad Server", "chunk_size": CHUNK_SIZE,
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
        streamer.cancel()
        try:
            await streamer
        except (asyncio.CancelledError, websockets.ConnectionClosed):
            pass
        await session.close_files()
        if session.capture is not None:
            try:
                loop = asyncio.get_running_loop()
                await loop.run_in_executor(_CAPTURE_EXECUTOR, session.capture.close)
            except Exception:
                pass
        if session.gamepad is not None:
            try:
                session.gamepad.reset()
                session.gamepad.update()
            except Exception:
                pass
        _ACTIVE = None
        print("[断开] 平板已断开")


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
            print("send <path>：发送电脑文件到平板；status：连接状态；quit：退出")
            continue
        if command.lower() == "status":
            print("已连接" if _ACTIVE else "等待平板连接")
            continue
        if command.lower().startswith("send "):
            if _ACTIVE is None:
                print("[提示] 请先在平板连接本机")
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
    print("=" * 60)
    print("LAN Touchpad Server — 局域网远程控制")
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
    print("=" * 60)

    async with websockets.serve(
        client_handler,
        HOST,
        PORT,
        max_size=16 * 1024 * 1024,
        max_queue=16,
        compression=None,
        ping_interval=20,
        ping_timeout=20,
        close_timeout=5,
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
