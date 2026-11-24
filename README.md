# LAN Touchpad 2.0

> **工程与构建说明**
>
> - 当前 Android 构建工程在 **`android/`**（包名 `com.example.lantouchpad`；AGP 8.7.2 / Kotlin 2.1.10 / compileSdk 35 / Java 17）。Windows 服务端在 **`windows/`**。
> - 根目录 `app/`、`build.gradle`、`settings.gradle`、`gradle.properties` 是早期精简版遗留代码，**不参与当前 CI 构建**；不要把它们当作 Android 当前工程修改入口。
> - 每次推送到任意分支，GitHub Actions 会先运行 Windows 服务端自动化测试，再构建 Android debug APK。APK 位于该 Actions run 的 **Artifacts**。
> - 当前 Android 版本：`versionCode 3` / `versionName 2.0.0`。正式发布可创建 `v2.0.0` 标签；CI 会构建并发布标签对应 APK。

一个纯局域网 Android → Windows 远程桌面/触控板/虚拟手柄工具。无云服务、无账号、无数据库，不使用 WebRTC。

## 2.0 已实现功能

### 远程显示
- Windows 多显示器枚举和选择，实时 JPEG 画面推流。
- 省流、标清、高清和自适应画质；15/30/60 FPS 设置。
- FIT / FILL / STRETCH 画面缩放显示，定期显示输出尺寸、发送帧速率与队列状态。
- Android 端异步解码并合并过期帧，限制 JPEG 体积和解码尺寸，减少 UI 卡顿及内存压力。
- 远程画面截图，可保存至 Android 系统文件选择器。

### 输入控制
- 相对触控板：单指相对鼠标、点击、长按拖动、双指滚动、右键/中键手势。
- 直接触控：将 Android 屏幕内的触点映射为所选 Windows 显示器的归一化坐标；支持单指点击/拖动和双指滚动。
- 可调触控灵敏度；模式切换会复位按住状态。
- 屏幕虚拟键盘，覆盖字母、数字、常见标点、F1–F12、修饰键和导航键；支持快捷键与中文文本粘贴。
- 双向手动剪贴板：手机剪贴板文本发送至 Windows，或主动读取 Windows 纯文本剪贴板至手机。
- 双摇杆、A/B/X/Y、肩键、扳机、BACK/START、L3/R3 和方向键虚拟 Xbox 360 手柄。
- 左右浮动菜单和子菜单；手机竖屏与平板/手机横屏使用不同布局。

### 文件与连接
- Android → Windows、Windows → Android 双向文件传输，64 KiB 分块、进度、长度和 SHA-256 校验。
- 当前连接内暂停、继续、取消；失败后可重新发起传输。
- Windows 可通过 Android 请求打开系统文件选择对话框，也支持服务端命令行 `send <path>`。
- 网络中断后指数退避自动重连；用户主动断开则停止重连。
- 协议 v2 握手、能力协商、控制消息优先队列，以及断线/切换模式时的输入状态释放。

## Windows 服务端安装

1. Windows 10/11 安装 64 位 Python 3.10 或更新版本。
2. 在仓库 `windows` 目录执行：
   ```powershell
   py -3 -m pip install -r requirements.txt
   py -3 server.py
   ```
3. 首次运行时，仅允许 Windows 防火墙对**专用网络**放行 Python / `server.exe` 接收连接。
4. 服务端窗口会显示本机 LAN IPv4。Android 与电脑必须连接同一可信局域网，连接地址为 `ws://电脑IPv4:8765`；也可直接在 App 输入 IP，端口会自动补齐。
5. Android 上传到 Windows 的文件保存在服务端当前工作目录 `received/`。

也可在 Windows `windows` 目录运行 `build_exe.bat` 打包为 EXE。打包后的 exe 仍需按目标电脑情况安装虚拟手柄驱动。

### 虚拟手柄驱动

`vgamepad` 依赖兼容的 ViGEmBus 驱动。目标电脑未安装驱动或驱动不可用时，画面、鼠标、键盘和文件传输仍可使用，但 Xbox 虚拟手柄不可用。请参考 [vgamepad 项目说明](https://github.com/yannbouteiller/vgamepad)。

## Android APK

- `minSdk 24`，传统 XML 布局与自定义 View；同时支持横屏和竖屏。
- 使用 OkHttp WebSocket；当前服务端为 `ws://` 明文传输，只适合可信局域网。
- 推送到 GitHub `v2-development` 后，打开仓库 **Actions**，选择最新成功的 Build workflow，下载 **Artifacts** 中的 APK。

## 当前明确限制

- **不是真正的 Windows 多点触控注入。** 直接触控模式是单指坐标映射，双指动作转换为滚轮；要把 Android 多指触摸原样注入 Windows 触摸屏，需要后续独立 Windows 虚拟触摸驱动或专用输入 API。
- 多显示器选择已实现，但 Windows 混合 DPI、负坐标显示器、独占全屏和部分游戏的鼠标坐标行为仍需目标硬件实测。
- 文件暂停/继续只在当前连接有效；重试从头开始，暂未实现断线续传、分块重传和进程重启恢复。
- 手柄基础布局和按键已实现；自定义布局编辑器、透明度/位置保存、多个游戏配置档、宏录制/导入导出尚未完成。
- 双向手动剪贴板已实现：手机剪贴板文本可发送到 Windows；右侧菜单可主动读取 Windows 纯文本剪贴板到手机。后台自动同步仍未开启，以避免未经用户触发地读取剪贴板。
- 当前版本未实现 Windows 音频回传、远程视频录制和应用级登录/鉴权。
- 当前服务端只允许一个控制端，且没有账号、配对码和 TLS。

## 安全提示

服务端绑定 `0.0.0.0:8765`。同一网络中能访问这个端口的设备可能控制电脑并传送文件。不要做路由器端口转发，不要对 Windows 公用网络开放端口，不要在公共 Wi-Fi、公司不可信网络或互联网公开服务端。

## 目录结构

```text
lan-touchpad/
├─ android/                 # 当前 Android 工程
├─ windows/                 # Windows Python 服务端
├─ tests/                   # 跨平台服务端协议/状态测试
├─ .github/workflows/       # GitHub Actions APK 构建与测试
└─ protocol.md              # 协议 v2
```

## 文档

- [2.0.0 变更记录](CHANGELOG_V2.md)
- [更新到 v2-development 的文件清单与步骤](UPGRADE_TO_V2.md)
- [协议 v2](protocol.md)
- [验证记录与实机验收清单](VALIDATION.md)
