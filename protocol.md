# LAN Touchpad 通信协议（v2，兼容 v1 基础消息）

## 1. 连接与传输规则

- 传输层：WebSocket，默认地址 `ws://<Windows-LAN-IP>:8765`。
- Android 是客户端，Windows 是服务端；同一时刻只接受一个控制端。
- 文本帧承载 JSON 控制/状态消息；二进制帧承载屏幕 JPEG 或文件分块。
- 多字节整数使用网络字节序（Big Endian）。
- 文件块大小为 64 KiB；最后一块可更短。
- v2 客户端连接后发送 `client_hello`；旧 v1 客户端不发送该消息时，服务端保持基础 v1 行为。
- v2 新增能力按服务端 `hello_ack.capabilities` 声明为准；协商版本不得高于服务端支持版本。

## 2. 版本协商与能力

Android 连接后发送：

```json
{"type":"client_hello","client":"LAN Touchpad Android","client_version":"2.0.0","protocol_version":2,"capabilities":["absolute_touch","relative_mouse","gamepad","files_sha256","clipboard_manual"]}
```

服务端回复：

```json
{"type":"hello_ack","protocol_version":2,"server_version":"2.0.0","chunk_size":65536,"quality":"saver","fps":15,"selected_monitor":1,"monitors":[{"index":1,"name":"显示器 1","left":0,"top":0,"width":1920,"height":1080}],"capabilities":["absolute_touch","relative_mouse","gamepad","files_sha256","multi_display","screen_stats","priority_send","clipboard_manual"]}
```

`protocol_version` 是协商后的版本。若客户端请求的版本高于服务端支持值，服务端会按自身最高支持版本应答。v1 客户端仍可使用原有的鼠标、键盘、手柄、画面和文件握手消息；SHA-256 在 v1 中为可选，在 v2 中必须提供。

## 3. 二进制帧

### 3.1 屏幕 JPEG：`0x01`

| 偏移 | 长度 | 内容 |
|---:|---:|---|
| 0 | 1 byte | `0x01` |
| 1 | 剩余全部 | JPEG 图片字节 |

Android 解码 JPEG 并显示。客户端合并待解码画面，避免网络较快时在 UI 队列中堆积大量过期帧。

### 3.2 文件块：`0x02`

| 偏移 | 长度 | 内容 |
|---:|---:|---|
| 0 | 1 byte | `0x02` |
| 1 | 4 bytes | `file_id`，无符号 32 位大端整数 |
| 5 | 4 bytes | `chunk_index`，无符号 32 位大端整数，从 0 连续递增 |
| 9 | 0–65536 bytes | 文件内容 |

包头固定为 9 字节。超长块、乱序块、总长度超限和 SHA-256 不匹配均会使本次传输失败。

## 4. JSON 控制消息

### 4.1 鼠标与直接触控

```json
{"type":"mouse","action":"move","dx":12,"dy":-5}
{"type":"mouse","action":"absolute_move","x":0.5,"y":0.5}
{"type":"mouse","action":"click","button":"left"}
{"type":"mouse","action":"down","button":"left"}
{"type":"mouse","action":"up","button":"left"}
{"type":"mouse","action":"scroll","amount":-2}
{"type":"mouse","action":"double_click","button":"left"}
```

- `move` 为相对像素偏移；`absolute_move` 的 `x/y` 为当前显示器画面内 0.0–1.0 的归一化坐标。
- `button` 允许 `left | right | middle`。
- 相对触控板、直接触控和游戏手柄模式之间切换时，客户端发送 `state_reset`，服务端释放已按住的输入。

### 4.2 键盘

```json
{"type":"key","action":"press","key":"ENTER"}
{"type":"key","action":"down","key":"CTRL"}
{"type":"key","action":"up","key":"CTRL"}
{"type":"key","action":"hotkey","keys":["CTRL","SHIFT","ESC"]}
{"type":"key","action":"text","text":"中文文本"}
```

`text` 使用 Windows Unicode 剪贴板再粘贴；普通按键经过白名单映射。支持 F1–F12、常用修饰键、导航键和常见标点。剪贴板粘贴依赖当前 Windows 桌面会话。

### 4.3 手动剪贴板交换

```json
{"type":"clipboard_get"}
{"type":"clipboard_text","text":"来自 Windows 的纯文本"}
```

`clipboard_get` 由 Android 用户主动点击菜单触发；服务端只读取 Windows 当前纯文本剪贴板，最多 16,000 字符，并返回 `clipboard_text`。反方向可使用 `key` / `text` 将 Android 剪贴板文本写入 Windows 剪贴板并粘贴。协议不在后台自动轮询剪贴板。

### 4.4 虚拟 Xbox 手柄

```json
{"type":"gamepad","action":"button","button":"A","pressed":true}
{"type":"gamepad","action":"axis","axis":"LX","lx":-0.4,"ly":0.7}
{"type":"gamepad","action":"axis","axis":"RX","rx":0.2,"ry":-0.3}
{"type":"gamepad","action":"trigger","trigger":"LT","value":0.75}
```

支持 `A/B/X/Y/LB/RB/BACK/START/L3/R3/DPAD_UP/DPAD_DOWN/DPAD_LEFT/DPAD_RIGHT`。摇杆范围 `[-1,1]`，扳机范围 `[0,1]`。Windows 端需要安装 `vgamepad` 和兼容的 ViGEmBus 驱动；驱动不可用时，屏幕、鼠标、键盘和文件功能仍可使用。

### 4.5 画面控制、显示器和统计

```json
{"type":"screen_ctrl","quality":"adaptive","fps":30,"monitor":1}
```

`quality` 支持 `saver | standard | hd | adaptive`；`fps` 支持 `15 | 30 | 60`；`monitor` 使用 `hello_ack.monitors` 中的 `index`。

| 档位 | 最大输出尺寸 | JPEG quality |
|---|---:|---:|
| saver | 512 × 288 | 28 |
| standard | 1280 × 720 | 55 |
| hd | 1920 × 1080 | 78 |
| adaptive | 根据发送队列压力在上述档位间调整 | 随档位变化 |

服务端定期发送 `screen_stats`，包含最近统计窗口内成功写入 WebSocket 的画面帧速率、输出尺寸、队列深度、档位和显示器编号。它不是显示器刷新率，也不保证客户端实际渲染 FPS。

### 4.6 输入状态复位

```json
{"type":"state_reset"}
```

服务端释放所有由此连接按下的键盘键和鼠标按钮，并重置虚拟手柄。客户端断开时服务端也执行相同清理。它不能保证 Windows 安全桌面、失去焦点的应用或外部驱动中的状态都能被第三方 API 完全控制。

## 5. 双向文件传输

### 5.1 Android → Windows

1. Android 发送 `file_start`，包含 `file_id/name/size/direction=to_server/chunk_size`。
2. Windows 建立 `.part` 临时文件并回复 `file_ready`。
3. Android 依序发送 `0x02` 文件块；界面显示进度。
4. Android 发送 `file_end`，包含最终 `size` 和完整文件 `sha256`。
5. Windows 验证长度和 SHA-256，成功后原子改名并回复 `file_complete`；失败则返回 `file_error`。

### 5.2 Windows → Android

1. Android 发送 `file_request`，Windows 打开本机文件选择框；也可在服务端命令行输入 `send <path>`。
2. Windows 发送 `file_start`，包含 `direction=to_client`、`size` 和 `sha256`。
3. Android 请求用户选择保存位置，打开 SAF 输出流后发送 `file_ready`。
4. Windows 按序发送文件块，再发送 `file_end`。
5. Android 校验长度和 SHA-256；成功后通知界面完成。

### 5.3 暂停、继续、取消和重试

```json
{"type":"file_pause","file_id":123}
{"type":"file_resume","file_id":123}
{"type":"file_cancel","file_id":123}
```

暂停/继续适用于当前仍存活的连接和传输会话。Android 端上传由本地发送循环暂停；Windows 向 Android 发送时由服务端暂停后续文件块。取消会清理当前临时接收状态。界面“重试”会重新发起整个文件传输；**当前版本尚未实现跨断线/进程重启的断点续传**。

### 5.4 安全与限制

- 单次文件上限 20 GiB；分块 64 KiB。
- Android 文件由系统 Storage Access Framework 选择；Windows 接收内容写入服务端工作目录 `received/`。
- 连接中断留下的 `.part` 文件不是完整文件，不能作为成功传输使用；可人工检查和清理。
- 传输队列优先让控制消息和画面帧插队，但同一个文件流中的文件块与结束标记保持 FIFO，避免结束标记提前到达。

## 6. 心跳与错误处理

```json
{"type":"ping","time":1730000000}
{"type":"pong","time":1730000000}
```

WebSocket 自身也启用 ping/pong。无效 JSON、超长控制消息、未知文件 ID、文件块乱序和校验失败会返回错误或终止对应传输；连接断开时关闭未完成文件句柄并释放远程输入状态。

## 7. 安全边界

这是无账号、无配对码、无 TLS 的可信局域网工具。服务端绑定 `0.0.0.0:8765`，任何能访问该端口的设备都可能控制鼠标键盘、虚拟手柄并向电脑发送文件。仅在自己控制的家庭/实验室 LAN 使用；不要配置路由器端口映射，不要对公用网络开放 Windows 防火墙端口，不要在公共 Wi-Fi 或互联网暴露服务。
