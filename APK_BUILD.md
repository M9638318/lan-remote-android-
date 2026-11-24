# APK 生成指南

## 方式 A：GitHub Actions（推荐，免装 Android Studio）

1. 把代码推送到仓库任意分支，工作流 `Build LAN Touchpad APK` 会自动运行（JDK 17 + Android SDK 35 + Gradle 8.9）。
2. 构建完成后，进入该次 workflow run 页面，在 **Artifacts** 下载 `LAN-Touchpad-v<版本>-<commit>`，解压得到带版本号与 commit 短哈希的 APK。
3. 如果推送的是 `main` 分支，还可以直接到 **Releases → `latest`（预发布）** 下载 `LAN-Touchpad-debug-latest.apk`，每次构建都会覆盖更新。
4. 需要正式版本时打标签：`git tag v2.0.0 && git push origin v2.0.0`，CI 会自动创建同名 Release 并附上 APK。

> 产物文件名包含版本号和提交哈希，因此不同版本不会再是「同一份」。

## 方式 B：本机命令行

需要 JDK 17、Android SDK Platform 35 / Build Tools 35、Gradle 8.9。在**仓库根目录**执行：

```bash
gradle -p android --no-daemon :app:assembleDebug
```

输出：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

注意：命令里的 `-p android` 指向 `android/` 子工程；仓库根目录的 `app/` 是已弃用的早期版本，不要用 `gradle assembleDebug` 构建它。

## 版本与签名

- 版本号在 `android/app/build.gradle` 的 `versionCode` / `versionName`，**每次发版都要递增**。
- 当前构建的是 debug 包（debug 签名），适合个人局域网测试。正式分发需配置 release 签名并改用 `assembleRelease`。
- 平板需 Android 7.0（API 24）及以上，首次安装时允许「未知来源」。

## 手机/平板首次连接

1. Android 手机/平板和 Windows 电脑连接同一个 Wi-Fi / LAN。
2. Windows 安装并启动 LAN Touchpad 桌面程序，在控制面板查看局域网 IPv4 地址。
3. Android 设备打开 LAN Touchpad，输入电脑的 IPv4，点「连接」（端口 8765）。

> Android APK 和 Windows 服务端 EXE 由两个独立的 GitHub Actions job 构建。应用运行时只连接你填写的局域网 Windows 地址，不连接任何云服务。

## Windows GUI EXE 与安装程序

GitHub Actions 的 Windows job 会构建 `LAN-Touchpad.exe` 和 Inno Setup 安装程序 `LAN-Touchpad-Setup-v2.1.0.exe`。在该次 Actions run 的 Artifacts 下载 `LAN-Touchpad-Windows-Installer-...`。

本机只构建 GUI EXE：Windows 10/11 x64，安装 Python 3.10–3.14 x64 后双击 `windows/build_exe.bat`。脚本扫描可用 Python 版本，存在多个兼容版本时允许选择，输出 `windows/dist/LAN-Touchpad.exe`。完整安装向导由 GitHub Actions 构建。

安装程序默认目录为 `C:\Program Files\LAN Touchpad`，提供桌面快捷方式、开始菜单快捷方式、登录自动启动和安装后启动选项。用户接收文件保存在 `%LOCALAPPDATA%\LAN Touchpad\received`，卸载时保留。

