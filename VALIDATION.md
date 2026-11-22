# Validation report

This project has been prepared with static checks and local protocol smoke tests. The checks below are deliberately separated from a full Windows/Android device test.

## Passed in the build workspace

- `windows/server.py` passes `python -m py_compile` under Python 3.13.
- Android XML resources parse as XML; every `R.id.*` referenced by `MainActivity.kt` exists in `activity_main.xml`.
- `.github/workflows/build.yml` parses as YAML.
- A local WebSocket loopback test (using the installed `websockets 16.0` in the build workspace and mocked screen/input platform APIs) passed: `hello`, `0x01 + JPEG` frame, `screen_ctrl` acknowledgement, and JSON `ping/pong`.
- A local WebSocket loopback test passed in both directions for file transfer: `file_start`/`file_ready`, `0x02` file header, sequential chunk indexes, `file_end`, and completion/length checks. The `file_request` → file-picker callback → PC-to-Android transfer path was also exercised with a mocked picker result.

## Not verified here

- The Android app has **not** been built into an APK in this workspace because Android SDK Platform 35 and Gradle are not installed here. Use the included GitHub Actions workflow to perform the real Android Gradle build.
- The Windows server has **not** been run on a real Windows desktop in this workspace. Actual mouse/keyboard input, screen capture, Windows file dialog, PyInstaller executable, Windows Defender Firewall behavior, and ViGEmBus/Xbox virtual controller must be tested on Windows 10/11.
- The virtual controller requires a compatible ViGEmBus driver on the target Windows PC. The driver is not embedded inside `server.exe`.

A protocol smoke test with mocked platform APIs proves the WebSocket message flow and packet layout, not that Windows drivers or Android hardware are functional.
