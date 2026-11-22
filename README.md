# LAN Touchpad

> **构建说明（重要）**
>
> - 可编译的 Android 工程在 **`android/`**（包名 `com.example.lantouchpad`，Gradle 8.9 / AGP 8.7.2 / Kotlin 2.1.10 / compileSdk 35），Windows 服务端在 **`windows/`**。
> - 仓库根目录的 `app/`、`build.gradle`、`settings.gradle`、`gradle.properties` 是早期精简版（`com.lanremote.app`）遗留，**已弃用，不参与 CI 构建**，仅为保留 v1 历史。
> - 每次 `push` 到任意分支，GitHub Actions 都会构建 APK：产物在对应 run 的 **Artifacts**（`LAN-Touchpad-v<版本>-<commit>`），推送到 `main` 时还会更新 **`latest` 预发布**（`LAN-Touchpad-debug-latest.apk`）。
> - 需要正式版本时打一个 `v*` 标签（例如 `v1.1.0`），CI 会自动创建对应 Release 并附上 APK。**不要再手动往 Release 上传旧 APK**。
> - 修改 Android 功能必须改 `android/app/src/main/` 下的 `.kt` / XML，并递增 `android/app/build.gradle` 里的 `versionCode` / `versionName`；`SOURCE_CODE.md` 只是源码归档快照，Gradle 不会读取它。
> - 当前版本：`versionCode 2` / `versionName 1.1.0`（debug 包，使用 debug 签名）。


一个纯局域网的 Android → Windows 远程触控板/虚拟手柄工具。无云服务、无账号、无数据库。

## 目录

```text
lan-touchpad/
├─ requirements.txt              # Windows 端 Python 依赖（windows/requirements.txt 同步副本）
├─ windows/
│  ├─ server.py
│  ├─ requirements.txt
│  └─ build_exe.bat
├─ android/
│  ├─ settings.gradle
│  ├─ build.gradle
│  ├─ gradle.properties
│  └─ app/src/main/...
├─ .github/workflows/build.yml
└─ protocol.md
```

## 当前功能

- WebSocket TCP 8765，文本 JSON 控制指令 + 二进制数据帧。
- Windows 截屏 JPEG 推流；默认 `saver / 15 FPS`，支持 `standard / hd` 和 15/30/60 FPS。
- 单指相对鼠标移动、轻点左键、双指滚轮、长按拖拽。
- 发送键盘按键/快捷键/中文文本。
- 双摇杆、A/B/X/Y、LB/RB、LT/RT 虚拟 Xbox 360 手柄。
- 平板 → 电脑、电脑 → 平板双向文件传输；64 KiB 分块和进度反馈。平板可直接请求 Windows 文件选择框。
- 快捷键功能夹，收纳 Alt+Tab、任务管理器、显示桌面、Esc、Enter。

## Windows 服务端

1. Windows 10/11 安装 64 位 Python（建议 3.10–3.14），并确保 `py` 启动器可用。
2. 进入 `windows` 目录，双击 `build_exe.bat`。或手动执行：
   ```powershell
   py -3 -m pip install -r requirements.txt
   py -3 server.py
   ```
3. 首次运行时，Windows 防火墙只对**专用网络**允许 Python / `server.exe` 接收连接。
4. 窗口会显示本机 LAN IPv4。平板与电脑必须连接同一局域网，且客户端填写 `ws://电脑IPv4:8765`（App 只要求 IP，会自动拼接端口）。
5. 电脑接收平板上传的文件放在 `windows/received/`（打包运行时是 `server.exe` 当前工作目录下的 `received/`）。平板端点“电脑发文件”会在 Windows 弹出文件选择框；也可在服务端窗口执行 `send "文件完整路径"`。

### 虚拟手柄驱动

`vgamepad` 使用 ViGEmBus 驱动。构建机上通过 `pip install vgamepad` 安装 Python 包时，安装器可能弹出驱动安装流程；**如果你把 `server.exe` 拷贝到另一台电脑，目标电脑也必须先安装兼容的 ViGEmBus 驱动**，否则鼠标、键盘、画面和文件仍可使用，但虚拟手柄不可用。安装驱动后重新启动服务端。参见 vgamepad 项目说明：<https://github.com/yannbouteiller/vgamepad>。

## Android APK

- `minSdk 24`，横屏，传统 XML 布局 + 自定义 View；不使用 Compose / WebRTC。
- 网络使用 OkHttp WebSocket；允许 `ws://` 明文连接，故只能在可信 LAN 中使用。
- 详见下文的 APK 生成方式，或在 GitHub Actions 页面下载自动编译的 APK。

## 重要安全提示

无账号、无鉴权和无 TLS 是本项目的明确设计约束，不代表适合不可信网络。服务端绑定 `0.0.0.0:8765`，同一网络内能访问该端口的主机可能控制电脑。不要在路由器做端口转发；防火墙只放行专用网络；不要在公共 Wi-Fi 使用。

## 当前实现边界

- v1 只允许一个 Android 控制端同时连接。
- 屏幕流捕获 Windows 主显示器；锁屏/UAC 安全桌面/受保护视频可能无法正常捕获或操作。
- 文件传输有块序号与长度校验，但没有断点续传和丢包重传。
- JPEG 档位限制的是最大输出尺寸，较低分辨率的显示器不会被放大到档位尺寸。
- Windows 屏幕尺寸、缩放比例和部分游戏独占全屏模式可能影响鼠标相对移动精度。
