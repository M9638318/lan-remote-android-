import asyncio
import hashlib
import importlib.util
import json
import struct
import sys
import tempfile
import types
import unittest
from pathlib import Path

import websockets

ROOT = Path(__file__).resolve().parents[1]
SERVER_PATH = ROOT / "windows" / "server.py"

# The server is intended for Windows. These lightweight substitutes allow protocol,
# file-integrity and state-management tests to run on the cross-platform CI runner.
PY_AUTOGUI_EVENTS = []
pyautogui = types.ModuleType("pyautogui")
pyautogui.FAILSAFE = True
pyautogui.PAUSE = 0
for _name in ("keyDown", "keyUp", "mouseDown", "mouseUp", "moveRel", "moveTo", "click", "scroll", "doubleClick", "press", "hotkey"):
    setattr(pyautogui, _name, lambda *args, _n=_name, **kwargs: PY_AUTOGUI_EVENTS.append((_n, args, kwargs)))

class FakeShot:
    size = (4, 2)
    rgb = bytes([40, 80, 120] * 8)

class FakeMSS:
    def __init__(self):
        self.monitors = [
            {"left": 0, "top": 0, "width": 8, "height": 4},
            {"left": 0, "top": 0, "width": 4, "height": 2},
            {"left": 4, "top": 0, "width": 4, "height": 2},
        ]
        self.closed = False

    def grab(self, monitor):
        return FakeShot()

    def close(self):
        self.closed = True

mss = types.ModuleType("mss")
mss.mss = FakeMSS
sys.modules["pyautogui"] = pyautogui
sys.modules["mss"] = mss

spec = importlib.util.spec_from_file_location("lan_touchpad_server_under_test", SERVER_PATH)
server = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = server
assert spec.loader is not None
spec.loader.exec_module(server)


class FakeWebSocket:
    def __init__(self):
        self.sent = []

    async def send(self, payload):
        self.sent.append(payload)

    async def close(self, **kwargs):
        return None


class ServerTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        PY_AUTOGUI_EVENTS.clear()
        self.session = server.ClientSession(FakeWebSocket())

    def test_filename_sanitization(self):
        self.assertEqual(server.safe_filename("../../bad:name?.txt"), "bad_name_.txt")
        self.assertEqual(server.safe_filename("   "), "received_file")

    def test_left_and_right_modifier_keys_are_distinct(self):
        server.do_key(self.session, {"action": "down", "key": "LSHIFT"})
        server.do_key(self.session, {"action": "down", "key": "RSHIFT"})
        self.assertEqual(self.session.held_keys, {"shiftleft", "shiftright"})
        server.do_key(self.session, {"action": "up", "key": "LSHIFT"})
        self.assertEqual(self.session.held_keys, {"shiftright"})
        server.release_all_inputs(self.session)

    def test_held_input_states_are_released(self):
        server.do_key(self.session, {"action": "down", "key": "CTRL"})
        server.do_key(self.session, {"action": "down", "key": "A"})
        server.do_mouse(self.session, {"action": "down", "button": "left"})
        self.assertEqual(self.session.held_keys, {"ctrl", "a"})
        self.assertEqual(self.session.held_mouse, {"left"})

        server.release_all_inputs(self.session)

        self.assertFalse(self.session.held_keys)
        self.assertFalse(self.session.held_mouse)
        released = [(name, args, kwargs) for name, args, kwargs in PY_AUTOGUI_EVENTS]
        self.assertTrue(any(name == "keyUp" and args == ("ctrl",) for name, args, _ in released))
        self.assertTrue(any(name == "keyUp" and args == ("a",) for name, args, _ in released))
        self.assertTrue(any(name == "mouseUp" and kwargs.get("button") == "left" for name, _, kwargs in released))

    def test_monitor_enumeration_and_jpeg_capture(self):
        monitors, selected = server.monitor_snapshot(self.session)
        self.assertEqual(len(monitors), 2)
        self.assertEqual(monitors[1]["left"], 4)
        self.assertEqual(selected["width"], 4)
        jpeg, width, height = server.capture_jpeg(self.session, "saver")
        self.assertTrue(jpeg.startswith(b"\xff\xd8"))
        self.assertEqual((width, height), (4, 2))

    async def test_screen_frames_are_coalesced(self):
        first = await self.session.send_binary(b"\x01old-frame", priority=1, drop_if_full=True)
        second = await self.session.send_binary(b"\x01new-frame", priority=1, drop_if_full=True)
        self.assertTrue(first)
        self.assertFalse(second)
        self.assertEqual(self.session.send_queue.qsize(), 1)

    async def test_priority_order_preserves_file_fifo(self):
        await self.session.send_json({"type": "control"})
        await self.session.send_binary(b"\x01screen", priority=1)
        await self.session.send_json({"type": "progress"}, priority=4)
        await self.session.send_binary(b"\x02chunk", priority=5)
        await self.session.send_json({"type": "file_end"}, priority=5)
        items = [await self.session.send_queue.get() for _ in range(5)]
        priorities = [item[0] for item in items]
        payloads = [item[2] for item in items]
        self.assertEqual(priorities, [0, 1, 4, 5, 5])
        self.assertEqual(payloads[-2:], [b"\x02chunk", json.dumps({"type": "file_end"}, separators=(",", ":"))])

    async def test_incoming_file_sha256_success(self):
        payload = b"LAN Touchpad v2 file transfer test" * 500
        with tempfile.TemporaryDirectory() as temp:
            old_dir = server.RECEIVED_DIR
            server.RECEIVED_DIR = Path(temp)
            try:
                await server.begin_incoming_file(self.session, {
                    "type": "file_start", "file_id": 101, "name": "sample.bin",
                    "size": len(payload), "direction": "to_server"
                })
                packet = b"\x02" + struct.pack("!II", 101, 0) + payload
                await server.handle_binary(self.session, packet)
                await server.finish_incoming_file(self.session, {
                    "file_id": 101, "direction": "to_server", "size": len(payload),
                    "sha256": hashlib.sha256(payload).hexdigest()
                })
                files = list(Path(temp).glob("*.bin"))
                self.assertEqual(len(files), 1)
                self.assertEqual(files[0].read_bytes(), payload)
                messages = [item[2] for item in list(self.session.send_queue._queue) if isinstance(item[2], str)]
                self.assertTrue(any('"type":"file_complete"' in message for message in messages))
            finally:
                await self.session.close_files()
                server.RECEIVED_DIR = old_dir

    async def test_out_of_order_chunk_aborts_and_removes_partial_file(self):
        payload = b"first chunk"
        with tempfile.TemporaryDirectory() as temp:
            old_dir = server.RECEIVED_DIR
            server.RECEIVED_DIR = Path(temp)
            try:
                await server.begin_incoming_file(self.session, {
                    "file_id": 103, "name": "order.bin", "size": len(payload) * 2,
                    "direction": "to_server"
                })
                transfer = self.session.incoming[103]
                await server.handle_binary(self.session, b"\x02" + struct.pack("!II", 103, 1) + payload)
                self.assertNotIn(103, self.session.incoming)
                self.assertFalse(transfer.temp_path.exists())
                messages = [item[2] for item in list(self.session.send_queue._queue) if isinstance(item[2], str)]
                self.assertTrue(any('"type":"file_error"' in message for message in messages))
            finally:
                await self.session.close_files()
                server.RECEIVED_DIR = old_dir

    async def test_unknown_size_upload_still_obeys_global_size_limit(self):
        with tempfile.TemporaryDirectory() as temp:
            old_dir = server.RECEIVED_DIR
            old_limit = server.MAX_FILE_SIZE
            server.RECEIVED_DIR = Path(temp)
            server.MAX_FILE_SIZE = 4
            try:
                await server.begin_incoming_file(self.session, {
                    "file_id": 104, "name": "unknown.bin", "size": -1,
                    "direction": "to_server"
                })
                transfer = self.session.incoming[104]
                packet = b"\x02" + struct.pack("!II", 104, 0) + b"12345"
                await server.handle_binary(self.session, packet)
                self.assertNotIn(104, self.session.incoming)
                self.assertFalse(transfer.temp_path.exists())
            finally:
                await self.session.close_files()
                server.RECEIVED_DIR = old_dir
                server.MAX_FILE_SIZE = old_limit

    async def test_disconnect_cleanup_removes_partial_files(self):
        with tempfile.TemporaryDirectory() as temp:
            old_dir = server.RECEIVED_DIR
            server.RECEIVED_DIR = Path(temp)
            try:
                await server.begin_incoming_file(self.session, {
                    "file_id": 105, "name": "partial.bin", "size": 20,
                    "direction": "to_server"
                })
                transfer = self.session.incoming[105]
                self.assertTrue(transfer.temp_path.exists())
                await self.session.close_files()
                self.assertFalse(transfer.temp_path.exists())
                self.assertFalse(self.session.incoming)
            finally:
                server.RECEIVED_DIR = old_dir

    async def test_incoming_file_rejects_bad_hash(self):
        payload = b"integrity must fail"
        with tempfile.TemporaryDirectory() as temp:
            old_dir = server.RECEIVED_DIR
            server.RECEIVED_DIR = Path(temp)
            try:
                await server.begin_incoming_file(self.session, {
                    "type": "file_start", "file_id": 102, "name": "bad.bin",
                    "size": len(payload), "direction": "to_server"
                })
                await server.handle_binary(self.session, b"\x02" + struct.pack("!II", 102, 0) + payload)
                await server.finish_incoming_file(self.session, {
                    "file_id": 102, "direction": "to_server", "size": len(payload), "sha256": "0" * 64
                })
                self.assertEqual(list(Path(temp).glob("*.bin")), [])
                messages = [item[2] for item in list(self.session.send_queue._queue) if isinstance(item[2], str)]
                self.assertTrue(any('"type":"file_error"' in message for message in messages))
            finally:
                await self.session.close_files()
                server.RECEIVED_DIR = old_dir

    async def test_clipboard_get_returns_plain_text_response(self):
        original = server.get_windows_clipboard_text
        server.get_windows_clipboard_text = lambda: "clipboard test"
        try:
            await server.handle_text(self.session, '{"type":"clipboard_get"}')
            payloads = [item[2] for item in list(self.session.send_queue._queue)]
            messages = [json.loads(item) for item in payloads if isinstance(item, str)]
            clipboard = next(item for item in messages if item.get("type") == "clipboard_text")
            self.assertEqual(clipboard.get("text"), "clipboard test")
        finally:
            server.get_windows_clipboard_text = original

    async def test_invalid_hello_version_uses_safe_default(self):
        await server.handle_text(self.session, '{"type":"client_hello","protocol_version":"invalid"}')
        self.assertEqual(self.session.protocol_version, 1)
        payloads = [item[2] for item in list(self.session.send_queue._queue)]
        self.assertTrue(any(isinstance(item, str) and '"hello_ack"' in item for item in payloads))


    async def test_real_websocket_v2_handshake_and_controls(self):
        async with websockets.serve(server.client_handler, "127.0.0.1", 0, compression=None) as listener:
            port = listener.sockets[0].getsockname()[1]
            async with websockets.connect(f"ws://127.0.0.1:{port}", ping_interval=None) as client:
                greeting = json.loads(await asyncio.wait_for(client.recv(), timeout=3))
                self.assertEqual(greeting.get("type"), "hello")
                await client.send(json.dumps({"type": "client_hello", "protocol_version": 2}))
                hello_ack = None
                while hello_ack is None:
                    message = await asyncio.wait_for(client.recv(), timeout=3)
                    if isinstance(message, str):
                        payload = json.loads(message)
                        if payload.get("type") == "hello_ack":
                            hello_ack = payload
                self.assertEqual(hello_ack.get("protocol_version"), 2)
                self.assertEqual(len(hello_ack.get("monitors", [])), 2)

                await client.send(json.dumps({"type": "screen_ctrl", "quality": "adaptive", "fps": 30, "monitor": 2}))
                ack = None
                while ack is None:
                    message = await asyncio.wait_for(client.recv(), timeout=3)
                    if isinstance(message, str):
                        payload = json.loads(message)
                        if payload.get("type") == "screen_ctrl_ack":
                            ack = payload
                self.assertEqual(ack.get("monitor"), 2)

                await client.send(json.dumps({"type": "state_reset"}))
                reset_ack = None
                while reset_ack is None:
                    message = await asyncio.wait_for(client.recv(), timeout=3)
                    if isinstance(message, str):
                        payload = json.loads(message)
                        if payload.get("type") == "state_reset_ack":
                            reset_ack = payload
                self.assertIsNotNone(reset_ack)
        await asyncio.sleep(0.05)
        self.assertIsNone(server._ACTIVE)


if __name__ == "__main__":
    unittest.main(verbosity=2)
