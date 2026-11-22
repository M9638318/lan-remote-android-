# LanRemote 局域网远程控制通信协议 (v1.0)

- **传输层**：WebSocket (`ws://<PC_LAN_IP>:8765`)
- **字节序**：所有二进制多字节整数均采用 **大端序 (Big-Endian)**
- **分块大小**：文件传输固定 `65536` 字节 (64KB) / 块

---

## 一、二进制帧协议（Binary Frames）

所有二进制帧的第 `0` 字节为 **包头类型标识（1 Byte）**：

| 包头字节 | 名称 | 载荷结构 | 方向 |
| :--- | :--- | :--- | :--- |
| `0x01` | 屏幕画面帧 | `[1B: 0x01]` + `[N Bytes: JPEG 图像原始字节]` | PC -> Android |
| `0x02` | 文件分块帧 | `[1B: 0x02]` + `[4B uint32: file_id]` + `[4B uint32: chunk_index]` + `[N Bytes: 文件块数据]` | 双向 (PC <-> Android) |

### 二进制帧布局示意图

1. **屏幕画面帧 (`0x01`)**
   ```text
   +--------+-----------------------------+
   |  0x01  |      JPEG Binary Data       |
   +--------+-----------------------------+
     1 Byte            Variable
   ```

2. **文件分块帧 (`0x02`)**
   ```text
   +--------+-------------+-------------+------------------------+
   |  0x02  |   file_id   | chunk_index |     Chunk Payload      |
   +--------+-------------+-------------+------------------------+
     1 Byte     4 Bytes       4 Bytes       <= 65536 Bytes
                (uint32 BE)   (uint32 BE)
   ```

---

## 二、文本帧协议（Text Frames - UTF-8 JSON）

所有文本帧均为 JSON 对象，必须包含 `type` 字段。

### 1. 心跳检测 (`ping` / `pong`)
- **请求 (Android -> PC)**：
  ```json
  { "type": "ping", "ts": 1700000000000 }
  ```
- **响应 (PC -> Android)**：
  ```json
  { "type": "pong", "ts": 1700000000000 }
  ```

### 2. 画面档位控制 (`screen_ctrl`)
- **方向**：Android -> PC
- **参数**：
  - `quality`: `"saver"` (512x288, Q=35) | `"standard"` (1280x720, Q=60) | `"hd"` (1920x1080, Q=80)
  - `fps`: `15` | `30` | `60`
  ```json
  { "type": "screen_ctrl", "quality": "standard", "fps": 15 }
  ```

### 3. 鼠标控制 (`mouse`)
- **方向**：Android -> PC
- **动作 `action`**：
  - `"move"`: 相对移动，附带 `dx`, `dy` (float/int)
  - `"click"`: 左键单击
  - `"double_click"`: 左键双击
  - `"right_click"`: 右键单击
  - `"left_down"` / `"left_up"`: 左键按下/抬起（用于长按拖拽）
  - `"scroll"`: 滚轮滚动，附带 `dy` (int，正数向上，负数向下)
  ```json
  { "type": "mouse", "action": "move", "dx": 12, "dy": -5 }
  ```

### 4. 键盘控制 (`key`)
- **方向**：Android -> PC
- **动作 `action`**：
  - `"text"`: 输入任意中英文字符串（PC 端通过 Win32 Unicode SendInput 注入），附带 `text`
  - `"press"`: 单键敲击（如 `"enter"`, `"backspace"`, `"esc"`, `"win"`），附带 `key`
  - `"hotkey"`: 组合键，附带 `keys` 字符串数组（如 `["ctrl", "c"]`, `["alt", "tab"]`）
  ```json
  { "type": "key", "action": "hotkey", "keys": ["win", "d"] }
  ```

### 5. 虚拟 Xbox 360 手柄 (`gamepad`)
- **方向**：Android -> PC
- **动作 `action`**：
  - `"stick"`: 摇杆，`side`: `"left"` | `"right"`，`x`, `y` 范围 `[-1.0, 1.0]`
  - `"button"`: 按键，`button`: `"A"|"B"|"X"|"Y"|"LB"|"RB"|"BACK"|"START"|"UP"|"DOWN"|"LEFT"|"RIGHT"`，`pressed`: `true|false`
  - `"trigger"`: 扳机，`side`: `"LT"` | `"RT"`，`value`: `0.0 ~ 1.0`
  ```json
  { "type": "gamepad", "action": "stick", "side": "left", "x": 0.75, "y": -0.40 }
  ```

### 6. 双向文件传输 (`file_start` / `file_end` / `file_list_req` / `file_list` / `file_download_req` / `file_ack`)
- **开始传输 (`file_start`)**：
  ```json
  { "type": "file_start", "file_id": 1001, "name": "demo.pdf", "size": 245760, "total_chunks": 4 }
  ```
- **结束传输 (`file_end`)**：
  ```json
  { "type": "file_end", "file_id": 1001, "name": "demo.pdf" }
  ```
- **接收确认 (`file_ack`)**：
  ```json
  { "type": "file_ack", "file_id": 1001, "status": "ok", "saved_path": "received_files/demo.pdf" }
  ```
- **请求电脑端共享目录列表 (`file_list_req` -> `file_list`)**：
  ```json
  { "type": "file_list_req" }
  ```
  ```json
  { "type": "file_list", "files": [{ "name": "report.txt", "size": 1024 }] }
  ```
- **请求下载电脑端文件 (`file_download_req`)**：
  ```json
  { "type": "file_download_req", "name": "report.txt" }
  ```
