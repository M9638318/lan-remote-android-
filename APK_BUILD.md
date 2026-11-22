# APK 极简生成指南

## 方式 A：GitHub Actions（不安装 Android Studio）

1. 在 GitHub 创建一个空仓库，将本项目全部文件上传，保持 `.github/workflows/build.yml` 的路径不变。
2. 打开仓库的 **Actions** 页面，选择 **Build Android APK**。
3. 点击 **Run workflow**。首次构建会下载 Gradle、Android Gradle Plugin 和 Android SDK 组件。
4. 构建成功后进入本次 workflow run 页面，在 **Artifacts** 区域下载 `LAN-Touchpad-debug-apk`。
5. 解压得到 `app-debug.apk`，传到 Android 7.0 平板安装。首次安装时允许当前文件管理器/浏览器安装未知来源应用。

自动构建使用 JDK 17、Gradle 8.9、Android Gradle Plugin 8.7.2、Kotlin 2.1.10、compileSdk 35。APK 是 debug 构建，适合个人局域网测试；正式分发前应配置自己的 release 签名。

## 方式 B：本机命令行（无需打开 Android Studio）

需要 JDK 17、Android SDK Platform 35 / Build Tools 35，以及 Gradle 8.9。进入项目根目录后执行：

```powershell
gradle -p android --no-daemon :app:assembleDebug
```

输出位置：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## 平板首次连接

1. 平板和 Windows 电脑连接同一个 Wi-Fi / LAN。
2. Windows 启动 `server.py` 或 `server.exe`，查看控制台打印的 IPv4 地址。
3. 平板打开 LAN Touchpad，输入电脑的 IPv4（例如 `192.168.1.20`），点“连接”。端口固定为 8765。
4. 初次连接建议保持“省流 / 15 FPS”。确认操作流畅后再逐步提升到标清、30 FPS。

> GitHub Actions 只负责编译 APK。应用运行时只连接你填写的局域网 Windows 地址，不连接项目云服务。
