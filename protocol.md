# LAN Touchpad 通信协议（v1）

## 1. 连接与传输规则

- 传输层：WebSocket，地址 `ws://<Windows-LAN-IP>:8765`。
- 连接方向：Android 平板作为客户端；Windows 作为服务端。
- **文本 WebSocket 帧只承载 JSON 控制/状态消息。**
- **二进制 WebSocket 帧只承载屏幕 JPEG 或文件分块。**
- 多字节整数使用网络字节序（Big Endian）。
- 文件块大小：64 KiB（最后一块可以小于 64 KiB）。
- v1 同时只接受一个 Android 控制端；同一个连接允许同时收发画面与文件。

## 2. 二进制帧格式

### 2.1 屏幕画面：`0x01`

| 偏移 | 长度 | 内容 |
|---:|---:|---|
| 0 | 1 byte | `0x01`，屏幕帧类型 |
| 1 | 剩余全部 | JPEG 图片字节 |

示例：`01 FF D8 FF ... FF D9`。Android 收到后去掉首字节，将剩余数据交给 JPEG 解码器。

### 2.2 文件数据：`0x02`

| 偏移 | 长度 | 内容 |
|---:|---:|---|
| 0 | 1 byte | `0x02`，文件数据类型 |
| 1 | 4 bytes | `file_id`，无符号 32 位大端整数 |
| 5 | 4 bytes | `chunk_index`，无符号 32 位大端整数，从 0 连续递增 |
| 9 | 0–65536 bytes | 文件块原始数据 |

固定包头为 **9 bytes（1 字节类型 + 4 字节文件 ID + 4 字节块序号）**。文件数据不再额外编码或压缩。

## 3. JSON 文本消息

所有消息都必须是 JSON 对象，并包含 `type`。

### 3.1 鼠标

```json
{"type":"mouse","action":"move","dx":12,"dy":-5}
{"type":"mouse","action":"click","button":"left"}
{"type":"mouse","action":"down","button":"left"}
{"type":"mouse","action":"up","button":"left"}
{"type":"mouse","action":"scroll","amount":-2}
```

`action`：`move | click | down | up | scroll`；`button`：`left | right | middle`；`dx/dy` 为相对移动量。

### 3.2 键盘

```json
{"type":"key","action":"press","key":"ENTER"}
{"type":"key","action":"down","key":"CTRL"}
{"type":"key","action":"up","key":"CTRL"}
{"type":"key","action":"hotkey","keys":["CTRL","SHIFT","ESC"]}
{"type":"key","action":"text","text":"Hello world"}
```

`text` 在 Windows 端通过 Unicode 剪贴板粘贴，以支持中文输入。远端按键经过白名单映射，避免执行任意系统命令。

### 3.3 虚拟手柄

```json
{"type":"gamepad","action":"button","button":"A","pressed":true}
{"type":"gamepad","action":"axis","axis":"LX","lx":-0.4,"ly":0.7}
{"type":"gamepad","action":"axis","axis":"RX","rx":0.2,"ry":-0.3}
{"type":"gamepad","action":"trigger","trigger":"LT","value":0.75}
```

按钮：`A/B/X/Y/LB/RB/BACK/START/L3/R3/DPAD_UP/DPAD_DOWN/DPAD_LEFT/DPAD_RIGHT`。摇杆范围 `[-1, 1]`，扳机范围 `[0, 1]`。

### 3.4 屏幕档位

```json
{"type":"screen_ctrl","quality":"saver","fps":15}
{"type":"screen_ctrl","quality":"standard","fps":30}
{"type":"screen_ctrl","quality":"hd","fps":60}
```

| quality | 最大输出尺寸（保持原始宽高比，不放大） | JPEG quality | 用途 |
|---|---:|---:|---|
| `saver` | 512 × 288 | 28 | 默认省流 |
| `standard` | 1280 × 720 | 55 | 日常操作 |
| `hd` | 1920 × 1080 | 78 | 看细节 |

`fps` 只允许 `15 | 30 | 60`。服务端按当前配置实时调整截屏周期。

### 3.5 文件传输握手

文件传输除题目要求的 `file_start / file_end` 外，补充 `file_ready` 握手，避免发送方先于接收方创建文件目标。Android 点击“电脑发文件”时，会额外发送 `file_request`，Windows 打开本机文件选择框；命令行 `send <path>` 也可作为替代方式。

```json
{"type":"file_request"}
```

**平板 → 电脑：**

1. 平板发送 `file_start`：
   ```json
   {"type":"file_start","file_id":123,"name":"notes.txt","size":2048,"direction":"to_server","chunk_size":65536}
   ```
2. 电脑创建临时文件后回复：
   ```json
   {"type":"file_ready","file_id":123,"direction":"to_server","chunk_size":65536}
   ```
3. 平板按 `0x02` 二进制格式发送块，`chunk_index` 从 0 开始。
4. 平板发送：
   ```json
   {"type":"file_end","file_id":123,"direction":"to_server","size":2048}
   ```
5. 电脑校验长度并将 `.part` 文件原子改名，回复 `file_complete`。

**电脑 → 平板：**

1. 电脑发送 `file_start`，其中 `direction` 为 `to_client`。
2. 平板弹出系统“创建/保存文件”选择器。用户选定目标后，平板打开输出流并发送 `file_ready`（`direction: to_client`）。
3. 电脑收到 ready 后发送 `0x02` 文件块，最后发送 `file_end`。
4. 平板按序写入并校验声明长度，完成后通知 UI。

`size` 为字节数；若 Android 内容提供器无法返回文件大小，可传 `-1`，表示未知长度，接收端仍会统计实际长度。

### 3.6 进度、结束与错误

```json
{"type":"file_progress","file_id":123,"done":1048576,"total":5242880}
{"type":"file_complete","file_id":123,"name":"notes.txt","size":2048,"path":"received/notes.txt"}
{"type":"file_error","file_id":123,"message":"chunk index mismatch"}
{"type":"file_cancel","file_id":123}
```

`file_progress` 的 `total` 为 `-1` 表示总大小未知。

### 3.7 心跳与服务端问候

```json
{"type":"ping","time":1730000000}
{"type":"pong","time":1730000000}
{"type":"hello","server":"LAN Touchpad Server","chunk_size":65536,"quality":"saver","fps":15}
```

WebSocket 协议自身也启用了 ping/pong 控制帧；JSON ping/pong 用于应用层状态检测。

## 4. 错误处理原则

- 未知二进制类型直接忽略；文件帧包头不足 9 字节时拒绝。
- 文件块必须按序到达；不接受跳号、超长块或超过声明总长度的数据。
- 接收端先写入 `.part` 临时文件，长度验证通过后才改为正式文件名。
- 连接断开时关闭未完成的文件句柄；未完成 `.part` 文件不会被当作已完成文件。
- v1 不实现自动重传；出现丢块或写入错误时结束该文件传输并提示错误。

## 5. 安全边界

这是一个无账号、无配对码、无 TLS 的可信局域网工具。任何能访问 TCP 8765 的设备都可能控制鼠标键盘、操作虚拟手柄并向电脑发送文件。仅允许在自己控制的家庭/实验室 LAN 中使用，不要配置端口映射，不要让 Windows 防火墙对“公用网络”开放该端口。服务端接收文件只写入程序目录下的 `received/`。
