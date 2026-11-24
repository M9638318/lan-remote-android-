# LAN Touchpad 2.0.0 变更记录

## Android

- 移除强制横屏限制，新增竖屏布局；方向变化由 Activity 重新绑定布局，连接管理对象保留。
- 新增左右悬浮菜单与子菜单、动态全键盘、直接触控/相对触控板/手柄模式切换。
- 新增远程截图保存、画面 FIT/FILL/STRETCH 缩放、显示器选择和触控灵敏度调整。
- WebSocket 增加连接代次保护、指数退避自动重连、输入复位和分离的上传/下载执行队列。
- 文件传输增加 SHA-256、暂停/继续/取消/重试界面与下载长度检查。
- 改善屏幕 JPEG 解码、帧合并和图像尺寸/体积保护。
- 改善触控板分数移动、滚动累计、多指轻点和手势状态；双摇杆增加指针归属及独立发送节流。

## Windows 与通信协议

- 服务端协议版本协商，支持 v1 基础消息兼容并协商到 v2。
- 增加多显示器枚举/选择、直接坐标输入、adaptive 画质和屏幕统计。
- 发送队列按控制/画面/进度/文件数据分层，屏幕帧合并，文件流维持块与结束标记 FIFO。
- 连接断开或 `state_reset` 时释放键盘、鼠标和虚拟手柄状态。
- 双向文件传输校验长度与 SHA-256；乱序块或写入异常中止该传输并清理临时文件；未知大小文件仍受 20 GiB 总量上限约束。
- 增加跨平台模拟测试与 Android 资源 ID 检查，并在 GitHub Actions 中构建前执行。

## 明确未包含

跨连接断点续传、分块重传、自定义手柄布局编辑与配置档导入导出、宏录制、后台自动剪贴板同步、Windows 音频回传、远程视频录制、用户认证与 TLS 尚未包含在此版本中。请以 README 的“当前明确限制”为准。

## Windows EXE 构建补充（v2.0.0 维护更新）

- 新增 `windows/build_exe.bat`：在 Windows 电脑双击即可创建隔离构建环境并打包单文件控制台 EXE。
- 新增 `windows/start_server.bat`：启动生成的 `LAN-Touchpad-Server.exe`。
- 补齐 Windows 服务端运行依赖、可选手柄依赖和 EXE 打包依赖清单。
- GitHub Actions 新增 Windows runner 构建任务，推送后可从 Artifacts 下载服务端 EXE。
- 接收文件目录固定为服务端脚本/EXE 同级的 `received` 文件夹，避免依赖启动时的当前目录。
- 此维护更新不改变 Android APK 的版本号或协议版本。


## v2.1.0 - Windows GUI 与安装程序

- 新增 Windows 独立桌面控制中心，提供服务启停、局域网地址复制、设备连接状态、日志、文件发送和接收目录入口。
- 保留现有 WebSocket 服务端和 Android 协议。
- 新增 Inno Setup 安装程序，支持默认 Program Files 安装、桌面/开始菜单快捷方式、登录自动启动和安装后启动。
- 用户接收文件改存放在 `%LOCALAPPDATA%\LAN Touchpad\received`，避免写入 Program Files，并在卸载时保留。
- GitHub Actions 增加 Windows GUI EXE 与安装包构建。
