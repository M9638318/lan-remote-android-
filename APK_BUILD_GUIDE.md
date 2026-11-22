# 极简 APK 生成指南（免装 Android Studio）

1. 在 GitHub 上新建一个仓库（Public 或 Private 均可），例如 `lan-remote-android`。
2. 解压本压缩包，将内部所有文件（包含 `.github/workflows/build.yml`）上传到仓库根目录。
3. 提交后进入仓库的 **Actions** 标签页，等待 `Build LanRemote APK` 运行完成（约 1~2 分钟）。
4. 点进构建记录，在底部 **Artifacts** 下载 `LanRemote-debug-apk`，解压即可得到 `app-debug.apk` 安装到平板。
