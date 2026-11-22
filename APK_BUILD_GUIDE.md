# 极简 APK 生成指南

1. 把项目推送到 GitHub 仓库（保持 `android/`、`.github/workflows/build.yml` 的路径不变）。
2. 进入仓库 **Actions** 标签页，等待 `Build LAN Touchpad APK` 完成（首次约 2~5 分钟，需下载 Gradle 与 Android SDK 35）。
3. 在本次 run 底部 **Artifacts** 下载 `LAN-Touchpad-v<版本>-<commit>`，解压得到 APK 安装到平板。
4. 或者到 **Releases → latest（预发布）** 下载 `LAN-Touchpad-debug-latest.apk`（main 分支每次构建自动覆盖）。

手动打 `v*` 标签会生成一个带 APK 的正式 Release。

详细构建说明见 [APK_BUILD.md](APK_BUILD.md)。
