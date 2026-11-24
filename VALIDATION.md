# LAN Touchpad 2.0 验证记录

本报告区分跨平台自动化检查、Android 构建检查和真实设备检查。自动化测试通过不等于已完成 Windows/Android 真机验收。

## 当前工作区已通过

- `python -m py_compile windows/server.py`：通过。
- `python -m unittest discover -s tests -v`：17 项通过。
  - 文件名清理；
  - 未知大小文件仍受总文件大小上限约束；
  - 会话断开时关闭并删除未完成的临时文件；
  - 按键/鼠标按住状态释放；
  - 左右 Ctrl/Alt/Shift 独立按住与释放；
  - 双向剪贴板协议消息由用户操作触发；
  - 多显示器枚举与 JPEG 生成；
  - 文件 SHA-256 正确接收；
  - 错误 SHA-256 拒收；
  - 文件块乱序时中止传输并删除不完整临时文件；
  - 无效协议版本安全降级；
  - 本地 WebSocket 会话完成 v2 握手、显示器切换、输入复位确认和关闭清理（平台接口使用模拟对象）；
  - 控制/画面/进度/文件块优先级顺序；
  - 屏幕待发送帧合并，防止积压；
  - Android Manifest / XML 布局解析；
  - 横竖屏布局 ID 唯一且 Kotlin 引用的 ID 都存在。
- `.github/workflows/build.yml`：由本地 YAML 解析器成功解析。
- Android Manifest 不再强制横屏，并声明方向/屏幕尺寸变化由 Activity 处理。

## 当前环境未能验证

- 本工作区未安装 Android SDK Platform 35、`android.jar` 或 Gradle 8.9，因此**没有在本地完成 Android Gradle 编译，也没有生成 APK**。仅用 Kotlin 编译器检查源码会因缺少 Android/AndroidX SDK 类型而报大量 unresolved reference，不能视为 Android 编译结果。请以 GitHub Actions 的真实 `assembleDebug` 结果为准。
- 没有在真实 Windows 10/11 桌面运行服务端。实际屏幕捕获、鼠标/键盘注入、混合 DPI/多显示器、Windows 文件选择框、剪贴板、PyInstaller EXE 和防火墙行为均待 Windows 实机验证。
- 没有在实体 Android 手机/平板验证触摸、多点手势、横竖屏切换、不同屏幕比例、SAF 文件提供器、自动重连及长时间稳定性。
- Xbox 虚拟手柄需要目标 Windows 电脑安装并正常加载 `vgamepad` 和兼容的 ViGEmBus 驱动；当前自动化测试没有模拟真实驱动。

## 验收建议

1. 将源码更新到 GitHub `v2-development`，先检查 Actions 中的 Python tests 和 Android `assembleDebug` 都通过。
2. 在 Windows 电脑启动服务端，Android 手机先横屏、再竖屏连接，检查画面、触控板、直接触控、键盘和重连。
3. 用小文件与大文件分别测试双向传输、暂停、继续、取消和重试；对照文件 SHA-256。
4. 若使用多显示器和虚拟手柄，分别验证各显示器坐标与 ViGEmBus 驱动状态。

## 已知未交付功能

当前版本尚未实现跨连接/跨进程断点续传、自定义手柄布局编辑器和配置档导入导出、宏录制、后台自动剪贴板同步、Windows 音频回传与远程视频录制、账号/鉴权/TLS。相关限制也在 README 中列出。
