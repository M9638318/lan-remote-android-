# 将当前仓库更新到 LAN Touchpad 2.0.0

## 操作原则

- 只更新 GitHub 的 `v2-development` 分支；不要把这些文件推到 `main`。
- 更新前可以在 GitHub 分支页面基于 `v2-development` 再建立一个备份分支。
- 本次更新保留仓库根目录的旧版 `app/`、`build.gradle`、`settings.gradle` 和 `gradle.properties`；当前 Android 构建工程仍是 `android/`。
- 不要把 ZIP 文件本身上传为项目源码文件；必须解压后按相对路径更新其内部文件。

## 本次需要新增或替换的文件

本次在 v2.0.0 安卓端源码基础上增加 Windows 单文件 EXE 打包能力。上传时仍然只更新 `v2-development`，并保留仓库原有的旧版根目录文件。

```text
.github/workflows/build.yml
APK_BUILD.md
CHANGELOG_V2.md
README.md
UPGRADE_TO_V2.md
VALIDATION.md
android/app/build.gradle
android/app/src/main/AndroidManifest.xml
android/app/src/main/java/com/example/lantouchpad/JoystickView.kt
android/app/src/main/java/com/example/lantouchpad/MainActivity.kt
android/app/src/main/java/com/example/lantouchpad/ScreenView.kt
android/app/src/main/java/com/example/lantouchpad/TouchpadView.kt
android/app/src/main/java/com/example/lantouchpad/WebSocketManager.kt
android/app/src/main/res/layout/activity_main.xml
android/app/src/main/res/layout-land/activity_main.xml   # 新增横屏布局
protocol.md
windows/server.py
windows/build_exe.bat                 # 新增：双击一键生成 Windows EXE
windows/start_server.bat              # 新增：启动已打包 EXE
windows/requirements.txt              # 新增：Windows 服务端运行依赖
windows/requirements-gamepad.txt      # 新增：可选虚拟手柄依赖
windows/requirements-build.txt        # 新增：EXE 打包依赖

tests/test_android_resources.py
tests/test_server.py
tests/test_windows_packaging.py       # 新增：Windows EXE 打包配置静态测试
```

`tests/` 目录是新增目录。其余未列出的项目文件沿用原版。

## 上传后检查

1. 确认页面顶部选择的是 `v2-development`。
2. 上传/替换上述文件并提交到该分支。
3. 打开 **Actions**，查看 `Build LAN Touchpad APK` 工作流；同一次运行会出现 Android 和 Windows 两个构建 job。
4. 确认 Python 自动化测试、`Build debug APK` 和 `Build Windows server EXE` 均成功。
5. 下载 Android APK Artifact 和 Windows EXE Artifact，分别在真实设备上验证；不要提前给 `main` 打版本标签。

如果 Actions 构建失败，先保留失败页面和日志，不要把失败代码合并到 `main`。`VALIDATION.md` 说明了本地已执行的检查和仍需真机验证的项目。
