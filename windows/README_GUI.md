# LAN Touchpad Windows 桌面端

Windows 端 v2.1.0 使用独立 Tkinter 桌面窗口。`gui.py` 是界面入口，`server.py` 保留原有 WebSocket 控制核心；GUI 通过本地子进程启动服务端工作进程，不改变 Android WebSocket 协议。

## 本机开发 / 一键构建

1. Windows 10/11 x64，安装 Python 3.10–3.14 x64。
2. 双击 `build_exe.bat`。脚本会枚举 Python Launcher 可见的兼容版本；多个版本时让用户选择，并在项目目录创建 `.venv`。
3. 输出 `windows/dist/LAN-Touchpad.exe`。
4. 安装包由 GitHub Actions 使用 Inno Setup 构建；本机也可安装 Inno Setup 6 后运行 `ISCC.exe /DMyAppVersion=2.1.0 windows/installer.iss`。

## 安装行为

- 默认目录：`C:\Program Files\LAN Touchpad`
- 创建开始菜单快捷方式；桌面快捷方式默认勾选，可取消。
- 登录 Windows 自动启动默认勾选，可取消。
- 安装完成后提供立即启动选项。
- 升级/卸载不会删除 `%LOCALAPPDATA%\LAN Touchpad` 中的接收文件。

## 使用

启动 LAN Touchpad 后，控制面板显示本机局域网地址、服务状态、Android 连接状态、日志和接收文件目录。手机和平板需与电脑处于同一可信局域网，连接 `ws://电脑IPv4:8765`。

## 安全

本项目没有账号、配对码或 TLS。仅在可信专用网络使用；不要设置路由器端口转发，不要在公共 Wi-Fi 使用。Windows 防火墙只允许专用网络访问 TCP 8765。虚拟手柄需要兼容的 ViGEmBus 驱动；缺少驱动时，屏幕、鼠标、键盘和文件传输仍可用。
