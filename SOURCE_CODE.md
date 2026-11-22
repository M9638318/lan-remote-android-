# LAN Touchpad — complete source listing

Every project text file is reproduced below in its own independent fenced code block. The project ZIP contains the same directory structure.

## `.github/workflows/build.yml`

````yaml
name: Build Android APK

on:
  push:
    branches: [ "main", "master" ]
  pull_request:
  workflow_dispatch:

permissions:
  contents: read

jobs:
  build-apk:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Install Android SDK 35
        run: sdkmanager "platforms;android-35" "build-tools;35.0.0"

      - name: Set up Gradle 8.9
        uses: gradle/actions/setup-gradle@v6
        with:
          gradle-version: '8.9'

      - name: Build debug APK
        run: gradle -p android --no-daemon :app:assembleDebug

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: LAN-Touchpad-debug-apk
          path: android/app/build/outputs/apk/debug/app-debug.apk
          if-no-files-found: error
          retention-days: 14
````

## `.gitignore`

````text
__pycache__/
*.py[cod]
.venv/
venv/
build/
dist/
*.spec
windows/received/
android/.gradle/
android/local.properties
android/**/build/
````

## `APK_BUILD.md`

````markdown
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
````

## `LICENSE`

````text
MIT License

Copyright (c) 2026 LAN Touchpad contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
````

## `README.md`

````markdown
# LAN Touchpad

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
````

## `VALIDATION.md`

````markdown
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
````

## `android/app/build.gradle`

````groovy
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace 'com.example.lantouchpad'
    compileSdk 35

    defaultConfig {
        applicationId 'com.example.lantouchpad'
        minSdk 24
        targetSdk 35
        versionCode 1
        versionName '1.0.0'
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = '17'
    }
}

dependencies {
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
}
````

## `android/app/proguard-rules.pro`

````text
# No custom shrinker rules are required for the debug-first LAN client.
````

## `android/app/src/main/AndroidManifest.xml`

````xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:allowBackup="false"
        android:label="LAN Touchpad"
        android:theme="@style/AppTheme"
        android:usesCleartextTraffic="true">
        <activity
            android:name=".MainActivity"
            android:configChanges="orientation|screenSize|keyboardHidden"
            android:exported="true"
            android:screenOrientation="landscape">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
````

## `android/app/src/main/java/com/example/lantouchpad/JoystickView.kt`

````kotlin
package com.example.lantouchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/** Dual virtual analog sticks. Two fingers can operate left and right sticks simultaneously. */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var onAxisChange: ((axis: String, x: Float, y: Float) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val pointerSticks = HashMap<Int, Int>()
    private var leftX = 0f
    private var leftY = 0f
    private var rightX = 0f
    private var rightY = 0f
    private var lastSend = 0L

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val midY = height * 0.46f
        val radius = minOf(width * 0.19f, height * 0.31f).coerceAtLeast(35f * density)
        drawStick(canvas, width * 0.27f, midY, radius, leftX, leftY, "L")
        drawStick(canvas, width * 0.73f, midY, radius, rightX, rightY, "R")
    }

    private fun drawStick(canvas: Canvas, cx: Float, cy: Float, radius: Float, x: Float, y: Float, label: String) {
        paint.style = Paint.Style.FILL
        paint.color = 0xFF202D3E.toInt()
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = 0xFF5A718F.toInt()
        canvas.drawCircle(cx, cy, radius, paint)
        paint.color = 0xFF607D9E.toInt()
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx + x * radius * 0.62f, cy + y * radius * 0.62f, radius * 0.34f, paint)
        paint.color = 0xFFCAD6E4.toInt()
        paint.textSize = 11f * density
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("${label} STICK", cx, cy + radius + 18f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                val stick = if (event.getX(index) < width / 2f) 0 else 1
                pointerSticks[pointerId] = stick
                updateStick(event.getX(index), event.getY(index), stick, true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val stick = pointerSticks[event.getPointerId(index)] ?: continue
                    updateStick(event.getX(index), event.getY(index), stick, false)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val index = event.actionIndex.coerceIn(0, event.pointerCount - 1)
                val pointerId = event.getPointerId(index)
                val stick = pointerSticks.remove(pointerId)
                if (stick != null) {
                    if (stick == 0) { leftX = 0f; leftY = 0f } else { rightX = 0f; rightY = 0f }
                    emitAxis(stick)
                }
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    pointerSticks.clear()
                    leftX = 0f; leftY = 0f; rightX = 0f; rightY = 0f
                    emitAxis(0); emitAxis(1)
                }
                invalidate()
                return true
            }
        }
        return true
    }

    private fun updateStick(x: Float, y: Float, stick: Int, force: Boolean) {
        val cx = if (stick == 0) width * 0.27f else width * 0.73f
        val cy = height * 0.46f
        val radius = minOf(width * 0.19f, height * 0.31f).coerceAtLeast(35f * density)
        var nx = (x - cx) / radius
        var ny = (y - cy) / radius
        val length = hypot(nx, ny)
        if (length > 1f) { nx /= length; ny /= length }
        if (abs(nx) < 0.08f) nx = 0f
        if (abs(ny) < 0.08f) ny = 0f
        if (stick == 0) { leftX = nx; leftY = ny } else { rightX = nx; rightY = ny }
        val now = SystemClock.uptimeMillis()
        if (force || now - lastSend >= 33L) {
            lastSend = now
            emitAxis(stick)
        }
        invalidate()
    }

    private fun emitAxis(stick: Int) {
        if (stick == 0) onAxisChange?.invoke("L", leftX, -leftY)
        else onAxisChange?.invoke("R", rightX, -rightY)
    }
}
````

## `android/app/src/main/java/com/example/lantouchpad/MainActivity.kt`

````kotlin
package com.example.lantouchpad

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.database.Cursor
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ThreadLocalRandom

class MainActivity : Activity(), WebSocketManager.Listener {
    private lateinit var ws: WebSocketManager
    private lateinit var screenView: ScreenView
    private lateinit var tvConnection: TextView
    private lateinit var tvScreenStatus: TextView
    private lateinit var tvFileStatus: TextView
    private lateinit var fileProgress: ProgressBar
    private lateinit var etHost: EditText
    private lateinit var etText: EditText
    private lateinit var spQuality: Spinner
    private lateinit var spFps: Spinner

    private var pendingUploadUri: Uri? = null
    private var pendingUploadId = -1L
    private var pendingUploadSize = -1L
    private var pendingDownloadId = -1L
    private var pendingDownloadName = "received_file"
    private var pendingDownloadSize = -1L
    private var qualityValue = "saver"
    private var fpsValue = 15
    private var seenFirstFrame = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        setContentView(R.layout.activity_main)

        screenView = findViewById(R.id.screenView)
        tvConnection = findViewById(R.id.tvConnection)
        tvScreenStatus = findViewById(R.id.tvScreenStatus)
        tvFileStatus = findViewById(R.id.tvFileStatus)
        fileProgress = findViewById(R.id.fileProgress)
        etHost = findViewById(R.id.etHost)
        etText = findViewById(R.id.etText)
        spQuality = findViewById(R.id.spQuality)
        spFps = findViewById(R.id.spFps)

        ws = WebSocketManager(this)
        setupSpinners()
        setupControls()
    }

    private fun setupSpinners() {
        val qualityAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            listOf("省流 · 512×288", "标清 · 1280×720", "高清 · 1920×1080"))
        qualityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spQuality.adapter = qualityAdapter
        spQuality.setSelection(0)
        spQuality.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                qualityValue = listOf("saver", "standard", "hd").getOrElse(position) { "saver" }
                if (ws.isConnected()) ws.sendScreenControl(qualityValue, fpsValue)
                tvScreenStatus.text = "画面：${listOf("省流", "标清", "高清").getOrElse(position) { "省流" }} · ${fpsValue} FPS"
            }
        }

        val fpsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            listOf("15 FPS · 省资源", "30 FPS · 流畅", "60 FPS · 高配"))
        fpsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spFps.adapter = fpsAdapter
        spFps.setSelection(0)
        spFps.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                fpsValue = listOf(15, 30, 60).getOrElse(position) { 15 }
                if (ws.isConnected()) ws.sendScreenControl(qualityValue, fpsValue)
                tvScreenStatus.text = "画面：${qualityLabel()} · ${fpsValue} FPS"
            }
        }
    }

    private fun setupControls() {
        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            val host = etHost.text.toString().trim()
                .removePrefix("ws://").removeSuffix("/").removeSuffix(":8765")
            if (host.isBlank()) {
                toast("先填写 Windows 电脑的局域网 IPv4 地址")
            } else {
                tvConnection.text = "● 连接中"
                tvConnection.setTextColor(0xFFFFC66D.toInt())
                ws.connect("ws://$host:8765")
            }
        }

        findViewById<Button>(R.id.btnTouchMode).setOnClickListener {
            findViewById<View>(R.id.touchpadView).visibility = View.VISIBLE
            findViewById<View>(R.id.gamepadPanel).visibility = View.GONE
        }
        findViewById<Button>(R.id.btnGamepadMode).setOnClickListener {
            findViewById<View>(R.id.touchpadView).visibility = View.GONE
            findViewById<View>(R.id.gamepadPanel).visibility = View.VISIBLE
        }

        findViewById<TouchpadView>(R.id.touchpadView).onMouseEvent = { sendJson(it) }
        findViewById<JoystickView>(R.id.joystickView).onAxisChange = { stick, x, y ->
            val json = JSONObject().put("type", "gamepad").put("action", "axis")
            if (stick == "L") json.put("axis", "LX").put("lx", x).put("ly", y)
            else json.put("axis", "RX").put("rx", x).put("ry", y)
            sendJson(json)
        }

        bindGamepadButton(R.id.gpA, "A")
        bindGamepadButton(R.id.gpB, "B")
        bindGamepadButton(R.id.gpX, "X")
        bindGamepadButton(R.id.gpY, "Y")
        bindGamepadButton(R.id.gpLB, "LB")
        bindGamepadButton(R.id.gpRB, "RB")
        bindTrigger(R.id.gpLT, "LT")
        bindTrigger(R.id.gpRT, "RT")

        findViewById<Button>(R.id.btnQuick).setOnClickListener {
            val panel = findViewById<View>(R.id.quickPanel)
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.quickAltTab).setOnClickListener { sendHotkey("ALT", "TAB") }
        findViewById<Button>(R.id.quickCtrlShiftEsc).setOnClickListener { sendHotkey("CTRL", "SHIFT", "ESC") }
        findViewById<Button>(R.id.quickWinD).setOnClickListener { sendHotkey("WIN", "D") }
        findViewById<Button>(R.id.quickEsc).setOnClickListener { sendKey("ESC") }
        findViewById<Button>(R.id.quickEnter).setOnClickListener { sendKey("ENTER") }

        findViewById<Button>(R.id.btnSendText).setOnClickListener { sendText() }
        etText.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                sendText()
                true
            } else false
        }

        findViewById<Button>(R.id.btnSendFile).setOnClickListener { chooseUploadFile() }
        findViewById<Button>(R.id.btnPcFileHint).setOnClickListener {
            if (!ws.isConnected()) toast("请先连接电脑") else {
                ws.requestPcFilePicker()
                toast("请在 Windows 电脑弹出的文件选择框中选择文件")
            }
        }
    }

    private fun bindGamepadButton(viewId: Int, buttonName: String) {
        findViewById<Button>(viewId).setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    sendGamepadButton(buttonName, true)
                    view.isPressed = true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    sendGamepadButton(buttonName, false)
                    view.isPressed = false
                    view.performClick()
                }
            }
            true
        }
    }

    private fun bindTrigger(viewId: Int, triggerName: String) {
        findViewById<Button>(viewId).setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    sendTrigger(triggerName, 1f)
                    view.isPressed = true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    sendTrigger(triggerName, 0f)
                    view.isPressed = false
                    view.performClick()
                }
            }
            true
        }
    }

    private fun sendGamepadButton(name: String, pressed: Boolean) {
        sendJson(JSONObject().put("type", "gamepad").put("action", "button")
            .put("button", name).put("pressed", pressed))
    }

    private fun sendTrigger(name: String, value: Float) {
        sendJson(JSONObject().put("type", "gamepad").put("action", "trigger")
            .put("trigger", name).put("value", value))
    }

    private fun sendHotkey(vararg keys: String) {
        val array = JSONArray()
        keys.forEach { key -> array.put(key) }
        sendJson(JSONObject().put("type", "key").put("action", "hotkey").put("keys", array))
    }

    private fun sendKey(key: String) {
        sendJson(JSONObject().put("type", "key").put("action", "press").put("key", key))
    }

    private fun sendText() {
        val value = etText.text.toString()
        if (value.isBlank()) return
        if (!ws.isConnected()) {
            toast("请先连接电脑")
            return
        }
        sendJson(JSONObject().put("type", "key").put("action", "text").put("text", value))
        etText.text.clear()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etText.windowToken, 0)
    }

    private fun sendJson(json: JSONObject) {
        // 高频触控/摇杆事件必须静默丢弃，避免离线时连续弹 Toast。
        ws.sendJson(json)
    }

    private fun chooseUploadFile() {
        if (!ws.isConnected()) {
            toast("请先连接电脑")
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_UPLOAD)
    }

    private fun chooseDownloadTarget(fileId: Long, name: String, size: Long) {
        pendingDownloadId = fileId
        pendingDownloadName = name
        pendingDownloadSize = size
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(intent, REQUEST_DOWNLOAD)
    }

    @Deprecated("Activity result API is retained to keep this sample dependency-light and minSdk 24 compatible")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            if (requestCode == REQUEST_DOWNLOAD && pendingDownloadId >= 0) {
                ws.cancelFile(pendingDownloadId)
                pendingDownloadId = -1L
                pendingDownloadSize = -1L
            }
            if (requestCode == REQUEST_UPLOAD) toast("未选择文件")
            return
        }
        val uri = data.data!!
        when (requestCode) {
            REQUEST_UPLOAD -> {
                try {
                    val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    if (flags != 0) contentResolver.takePersistableUriPermission(uri, flags)
                } catch (_: Exception) { }
                val (name, size) = queryFileInfo(uri)
                val id = ThreadLocalRandom.current().nextInt(1, Int.MAX_VALUE).toLong()
                pendingUploadUri = uri
                pendingUploadId = id
                pendingUploadSize = size
                tvFileStatus.text = "等待电脑接收：$name"
                fileProgress.visibility = View.VISIBLE
                fileProgress.progress = 0
                ws.beginUpload(id, name, size)
            }
            REQUEST_DOWNLOAD -> {
                val output = try { contentResolver.openOutputStream(uri, "w") } catch (e: Exception) { null }
                if (output == null) {
                    ws.cancelFile(pendingDownloadId)
                    toast("无法创建目标文件")
                } else {
                    ws.attachDownloadSink(pendingDownloadId, output)
                    tvFileStatus.text = "正在接收：$pendingDownloadName"
                    fileProgress.visibility = View.VISIBLE
                    fileProgress.progress = 0
                }
                pendingDownloadId = -1L
            }
        }
    }

    private fun queryFileInfo(uri: Uri): Pair<String, Long> {
        var name = "upload_file"
        var size = -1L
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0) name = cursor.getString(nameColumn) ?: name
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }
        return name to size
    }

    override fun onConnectionChanged(connected: Boolean, detail: String) {
        runOnUiThread {
            tvConnection.text = if (connected) "● 在线" else "● 离线"
            tvConnection.setTextColor(if (connected) 0xFF55D6BE.toInt() else 0xFFF0B45A.toInt())
            tvScreenStatus.text = if (connected) "已连接 · 等待首帧" else detail
            findViewById<Button>(R.id.btnConnect).text = if (connected) "重连" else "连接"
            if (connected) {
                seenFirstFrame = false
                ws.sendScreenControl(qualityValue, fpsValue)
            } else {
                seenFirstFrame = false
                fileProgress.visibility = View.GONE
            }
        }
    }

    override fun onScreenFrame(jpeg: ByteArray) {
        screenView.submitFrame(jpeg)
        if (!seenFirstFrame) {
            seenFirstFrame = true
            runOnUiThread { tvScreenStatus.text = "实时画面 · ${qualityLabel()} · ${fpsValue} FPS" }
        }
    }

    override fun onFileStart(fileId: Long, name: String, size: Long, direction: String) {
        runOnUiThread {
            if (direction == "to_client") {
                tvFileStatus.text = "电脑发送：$name (${formatBytes(size)})"
                chooseDownloadTarget(fileId, name, size)
            }
        }
    }

    override fun onFileReady(fileId: Long, direction: String) {
        if (direction == "to_server") {
            runOnUiThread {
                if (fileId == pendingUploadId) {
                    val uri = pendingUploadUri
                    if (uri != null) {
                        tvFileStatus.text = "正在发送到电脑…"
                        ws.sendDocument(contentResolver, uri, fileId, pendingUploadSize)
                        pendingUploadUri = null
                        pendingUploadId = -1L
                    }
                }
            }
        }
    }

    override fun onFileProgress(fileId: Long, done: Long, total: Long) {
        runOnUiThread {
            fileProgress.visibility = View.VISIBLE
            fileProgress.progress = if (total > 0) ((done * 100L / total).coerceIn(0, 100)).toInt() else 0
            tvFileStatus.text = "文件传输：${formatBytes(done)} / ${if (total >= 0) formatBytes(total) else "未知"}"
        }
    }

    override fun onFileEnd(fileId: Long, direction: String) {
        runOnUiThread {
            fileProgress.progress = 100
            tvFileStatus.text = "电脑文件已保存到所选位置"
            toast("文件接收完成")
        }
    }

    override fun onFileComplete(fileId: Long, name: String, size: Long) {
        runOnUiThread {
            fileProgress.progress = 100
            tvFileStatus.text = "已发送到电脑：$name (${formatBytes(size)})"
            toast("文件已发送到电脑")
        }
    }

    override fun onFileError(fileId: Long, message: String) {
        runOnUiThread {
            tvFileStatus.text = "文件错误：$message"
            fileProgress.visibility = View.GONE
            toast(message)
        }
    }

    override fun onServerMessage(type: String, payload: JSONObject) {
        if (type == "screen_ctrl_ack") {
            runOnUiThread { tvScreenStatus.text = "画面：${qualityLabel()} · ${fpsValue} FPS" }
        } else if (type == "hello") {
            runOnUiThread { tvScreenStatus.text = "服务端已就绪 · 等待画面" }
        } else if (type == "error") {
            runOnUiThread { toast(payload.optString("message", "服务端错误")) }
        }
    }

    private fun qualityLabel(): String = when (qualityValue) {
        "standard" -> "标清"
        "hd" -> "高清"
        else -> "省流"
    }

    private fun formatBytes(value: Long): String {
        if (value < 0) return "未知大小"
        if (value < 1024) return "$value B"
        if (value < 1024 * 1024) return "%.1f KB".format(value / 1024.0)
        return "%.1f MB".format(value / (1024.0 * 1024.0))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        ws.close()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_UPLOAD = 1001
        private const val REQUEST_DOWNLOAD = 1002
    }
}
````

## `android/app/src/main/java/com/example/lantouchpad/ScreenView.kt`

````kotlin
package com.example.lantouchpad

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Decodes JPEG off the UI thread. Both the input and UI queues are coalesced:
 * slow tablets display the newest frame rather than accumulating stale frames.
 */
class ScreenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val decoder = Executors.newSingleThreadExecutor { r ->
        Thread(r, "screen-jpeg-decoder").apply { isDaemon = true }
    }
    private val latestBytes = AtomicReference<ByteArray?>(null)
    private val latestBitmap = AtomicReference<Bitmap?>(null)
    private val decoding = AtomicBoolean(false)
    private val uiUpdatePosted = AtomicBoolean(false)
    @Volatile private var bitmap: Bitmap? = null

    init {
        setBackgroundColor(Color.rgb(5, 8, 14))
    }

    fun submitFrame(jpeg: ByteArray) {
        latestBytes.set(jpeg)
        startDecodeLoop()
    }

    private fun startDecodeLoop() {
        if (!decoding.compareAndSet(false, true)) return
        decoder.execute {
            try {
                while (true) {
                    val data = latestBytes.getAndSet(null) ?: break
                    val decoded = BitmapFactory.decodeByteArray(data, 0, data.size) ?: continue
                    latestBitmap.getAndSet(decoded)?.recycle()
                    scheduleUiUpdate()
                }
            } finally {
                decoding.set(false)
                if (latestBytes.get() != null) startDecodeLoop()
            }
        }
    }

    private fun scheduleUiUpdate() {
        if (!uiUpdatePosted.compareAndSet(false, true)) return
        val accepted = post {
            val newest = latestBitmap.getAndSet(null)
            if (newest != null && !newest.isRecycled) {
                val old = bitmap
                bitmap = newest
                old?.recycle()
                invalidate()
            }
            uiUpdatePosted.set(false)
            if (latestBitmap.get() != null) scheduleUiUpdate()
        }
        if (!accepted) uiUpdatePosted.set(false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = bitmap ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return
        val scale = minOf(viewW / frame.width, viewH / frame.height)
        val drawW = frame.width * scale
        val drawH = frame.height * scale
        val left = (viewW - drawW) / 2f
        val top = (viewH - drawH) / 2f
        canvas.drawBitmap(frame, null, RectF(left, top, left + drawW, top + drawH), paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        decoder.shutdownNow()
        latestBytes.set(null)
        latestBitmap.getAndSet(null)?.recycle()
        bitmap?.recycle()
        bitmap = null
    }
}
````

## `android/app/src/main/java/com/example/lantouchpad/TouchpadView.kt`

````kotlin
package com.example.lantouchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import kotlin.math.abs

/** Trackpad: one-finger relative motion, tap click, two-finger scroll, long-press drag. */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var onMouseEvent: ((JSONObject) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density
    private val slop = 10f * density
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var lastSentAt = 0L
    private var lastScrollAt = 0L
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var pendingScrollPx = 0f
    private var dragging = false
    private var moved = false
    private var twoFinger = false
    private var lastCentroidY = 0f
    private var pressRunnable: Runnable? = null

    init { isClickable = true }

    private fun emit(action: String, vararg values: Pair<String, Any>) {
        val json = JSONObject().put("type", "mouse").put("action", action)
        values.forEach { (key, value) -> json.put(key, value) }
        onMouseEvent?.invoke(json)
    }

    private fun cancelLongPress() {
        pressRunnable?.let(handler::removeCallbacks)
        pressRunnable = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = 14f * density
        paint.color = 0xFF202D3E.toInt()
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, paint)
        paint.color = 0xFF34465E.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        canvas.drawRoundRect(RectF(2f, 2f, width - 2f, height - 2f), radius, radius, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xFFE0E8F2.toInt()
        paint.textSize = 15f * density
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("触 控 板", width / 2f, height / 2f - 8f * density, paint)
        paint.color = 0xFF9FB1C7.toInt()
        paint.textSize = 11f * density
        canvas.drawText("单指移动  ·  轻点左键", width / 2f, height / 2f + 18f * density, paint)
        canvas.drawText("双指滚动  ·  长按拖拽", width / 2f, height / 2f + 38f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                downAt = SystemClock.uptimeMillis()
                pendingDx = 0f
                pendingDy = 0f
                pendingScrollPx = 0f
                moved = false
                dragging = false
                twoFinger = false
                cancelLongPress()
                pressRunnable = Runnable {
                    if (!twoFinger && !moved && isPressed) {
                        dragging = true
                        emit("down", "button" to "left")
                        invalidate()
                    }
                }.also { handler.postDelayed(it, 420L) }
                isPressed = true
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelLongPress()
                twoFinger = true
                if (event.pointerCount >= 2) lastCentroidY = (event.getY(0) + event.getY(1)) / 2f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 || twoFinger) {
                    cancelLongPress()
                    twoFinger = true
                    val centroid = (event.getY(0) + event.getY(1)) / 2f
                    val dy = lastCentroidY - centroid
                    lastCentroidY = centroid
                    pendingScrollPx += dy
                    val now = SystemClock.uptimeMillis()
                    val scrollUnit = 42f * density
                    val steps = (pendingScrollPx / scrollUnit).toInt()
                    if (steps != 0 && now - lastScrollAt >= 16L) {
                        lastScrollAt = now
                        pendingScrollPx -= steps * scrollUnit
                        emit("scroll", "amount" to steps.coerceIn(-8, 8))
                    }
                    return true
                }
                val dxFromStart = event.x - downX
                val dyFromStart = event.y - downY
                if (abs(dxFromStart) > slop || abs(dyFromStart) > slop) {
                    moved = true
                    if (!dragging) cancelLongPress()
                }
                val dx = (event.x - lastX) * 1.65f
                val dy = (event.y - lastY) * 1.65f
                lastX = event.x
                lastY = event.y
                pendingDx += dx
                pendingDy += dy
                val now = SystemClock.uptimeMillis()
                if ((abs(pendingDx) + abs(pendingDy)) > 0.5f && now - lastSentAt >= 16L) {
                    lastSentAt = now
                    emit("move", "dx" to pendingDx.toInt(), "dy" to pendingDy.toInt())
                    pendingDx = 0f
                    pendingDy = 0f
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remainingIndex = if (event.actionIndex == 0) 1 else 0
                if (remainingIndex < event.pointerCount) {
                    lastX = event.getX(remainingIndex)
                    lastY = event.getY(remainingIndex)
                }
                moved = true
                twoFinger = false
                pendingScrollPx = 0f
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                val elapsed = SystemClock.uptimeMillis() - downAt
                if (dragging) {
                    if (abs(pendingDx) + abs(pendingDy) > 0.5f) {
                        emit("move", "dx" to pendingDx.toInt(), "dy" to pendingDy.toInt())
                    }
                    emit("up", "button" to "left")
                } else if (!twoFinger && !moved && elapsed < 420L && event.actionMasked == MotionEvent.ACTION_UP) {
                    emit("click", "button" to "left")
                }
                dragging = false
                twoFinger = false
                isPressed = false
                performClick()
                invalidate()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
````

## `android/app/src/main/java/com/example/lantouchpad/WebSocketManager.kt`

````kotlin
package com.example.lantouchpad

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** JSON text messages + typed binary frames. See protocol.md. */
class WebSocketManager(private val listener: Listener) : Closeable {
    interface Listener {
        fun onConnectionChanged(connected: Boolean, detail: String)
        fun onScreenFrame(jpeg: ByteArray)
        fun onFileStart(fileId: Long, name: String, size: Long, direction: String)
        fun onFileReady(fileId: Long, direction: String)
        fun onFileProgress(fileId: Long, done: Long, total: Long)
        fun onFileEnd(fileId: Long, direction: String)
        fun onFileComplete(fileId: Long, name: String, size: Long)
        fun onFileError(fileId: Long, message: String)
        fun onServerMessage(type: String, payload: JSONObject)
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val fileExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lan-touchpad-file-io").apply { isDaemon = true }
    }
    private val sinks = ConcurrentHashMap<Long, OutputStream>()
    private val receivedBytes = ConcurrentHashMap<Long, Long>()
    private val fileTotals = ConcurrentHashMap<Long, Long>()
    private val closed = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var connected = false

    fun connect(url: String) {
        if (closed.get()) return
        val request = Request.Builder().url(url).build()
        socket?.cancel()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                listener.onConnectionChanged(true, "已连接")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type", "")
                    when (type) {
                        "file_start" -> {
                            val id = json.optLong("file_id", -1L)
                            fileTotals[id] = json.optLong("size", -1L)
                            receivedBytes[id] = 0L
                            listener.onFileStart(id, json.optString("name", "received_file"),
                                json.optLong("size", -1L), json.optString("direction", ""))
                        }
                        "file_ready" -> listener.onFileReady(
                            json.optLong("file_id", -1L), json.optString("direction", ""))
                        "file_progress" -> listener.onFileProgress(
                            json.optLong("file_id", -1L), json.optLong("done", 0L), json.optLong("total", -1L))
                        "file_end" -> finishDownload(json.optLong("file_id", -1L), json.optString("direction", ""))
                        "file_complete" -> listener.onFileComplete(
                            json.optLong("file_id", -1L), json.optString("name", ""), json.optLong("size", 0L))
                        "file_error" -> listener.onFileError(
                            json.optLong("file_id", -1L), json.optString("message", "文件传输错误"))
                        else -> listener.onServerMessage(type, json)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid server JSON", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleBinary(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                listener.onConnectionChanged(false, "连接已关闭：$reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                listener.onConnectionChanged(false, "连接失败：${t.message ?: "网络错误"}")
            }
        })
    }

    fun isConnected(): Boolean = connected

    fun sendJson(json: JSONObject): Boolean {
        if (!connected || closed.get()) return false
        return socket?.send(json.toString()) == true
    }

    fun sendScreenControl(quality: String, fps: Int) {
        sendJson(JSONObject().put("type", "screen_ctrl").put("quality", quality).put("fps", fps))
    }

    fun requestPcFilePicker() {
        sendJson(JSONObject().put("type", "file_request"))
    }

    fun beginUpload(fileId: Long, name: String, size: Long) {
        sendJson(JSONObject()
            .put("type", "file_start")
            .put("file_id", fileId)
            .put("name", name)
            .put("size", size)
            .put("direction", "to_server")
            .put("chunk_size", CHUNK_SIZE))
    }

    /** Called only after the server acknowledges file_ready for a to_server upload. */
    fun sendDocument(resolver: ContentResolver, uri: Uri, fileId: Long, expectedSize: Long) {
        fileExecutor.execute {
            var sent = 0L
            var index = 0L
            try {
                val input: InputStream = resolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法打开所选文件")
                input.use { stream ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        val chunk = buffer.copyOf(count)
                        val packet = ByteBuffer.allocate(9 + count).order(ByteOrder.BIG_ENDIAN)
                            .put(MAGIC_FILE.toByte()).putInt(fileId.toInt()).putInt(index.toInt()).put(chunk).array()
                        sendBinaryWithBackpressure(packet)
                        sent += count
                        index++
                        listener.onFileProgress(fileId, sent, expectedSize)
                    }
                }
                sendJson(JSONObject().put("type", "file_end").put("file_id", fileId)
                    .put("direction", "to_server").put("size", sent))
            } catch (e: Exception) {
                sendJson(JSONObject().put("type", "file_cancel").put("file_id", fileId))
                listener.onFileError(fileId, "发送失败：${e.message}")
            }
        }
    }

    /** Attach a SAF output stream, then allow the PC to start sending chunks. */
    fun attachDownloadSink(fileId: Long, output: OutputStream) {
        sinks[fileId] = output
        receivedBytes[fileId] = 0L
        sendJson(JSONObject().put("type", "file_ready").put("file_id", fileId).put("direction", "to_client"))
    }

    fun cancelFile(fileId: Long) {
        sendJson(JSONObject().put("type", "file_cancel").put("file_id", fileId))
        fileExecutor.execute {
            try { sinks.remove(fileId)?.close() } catch (_: Exception) { }
            receivedBytes.remove(fileId)
            fileTotals.remove(fileId)
        }
    }

    private fun sendBinaryWithBackpressure(packet: ByteArray) {
        val ws = socket ?: throw IllegalStateException("WebSocket 未连接")
        while (!closed.get() && ws.queueSize() > 1024L * 1024L) {
            Thread.sleep(10)
        }
        if (closed.get() || !connected || !ws.send(ByteString.of(*packet))) {
            throw IllegalStateException("WebSocket 发送队列已关闭")
        }
    }

    private fun handleBinary(packet: ByteArray) {
        if (packet.isEmpty()) return
        when (packet[0].toInt() and 0xFF) {
            MAGIC_SCREEN -> {
                if (packet.size > 1) listener.onScreenFrame(packet.copyOfRange(1, packet.size))
            }
            MAGIC_FILE -> {
                if (packet.size < 9) return
                if (packet.size > 9 + CHUNK_SIZE) {
                    listener.onFileError(-1L, "文件块超过 64 KiB 上限")
                    return
                }
                val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
                buffer.get()
                val fileId = buffer.int.toLong() and 0xFFFFFFFFL
                val index = buffer.int.toLong() and 0xFFFFFFFFL
                val chunk = ByteArray(buffer.remaining())
                buffer.get(chunk)
                fileExecutor.execute {
                    try {
                        val output = sinks[fileId] ?: throw IllegalStateException("未准备文件保存目标")
                        // WebSocket preserves message order. The receiver rejects missing/non-sequential chunks.
                        val expectedIndex = receivedBytes[fileId + INDEX_OFFSET] ?: 0L
                        if (index != expectedIndex) throw IllegalStateException("文件块序号错误：$index / $expectedIndex")
                        output.write(chunk)
                        val totalDone = (receivedBytes[fileId] ?: 0L) + chunk.size
                        receivedBytes[fileId] = totalDone
                        receivedBytes[fileId + INDEX_OFFSET] = expectedIndex + 1L
                        listener.onFileProgress(fileId, totalDone, fileTotals[fileId] ?: -1L)
                    } catch (e: Exception) {
                        listener.onFileError(fileId, e.message ?: "写入文件失败")
                        try { sinks.remove(fileId)?.close() } catch (_: Exception) { }
                    }
                }
            }
        }
    }

    private fun finishDownload(fileId: Long, direction: String) {
        if (direction != "to_client") {
            listener.onFileEnd(fileId, direction)
            return
        }
        fileExecutor.execute {
            try {
                sinks.remove(fileId)?.use { it.flush() }
                val done = receivedBytes[fileId] ?: 0L
                val total = fileTotals[fileId] ?: -1L
                if (total >= 0 && done != total) {
                    listener.onFileError(fileId, "文件长度不一致：收到 $done / $total")
                } else {
                    listener.onFileEnd(fileId, direction)
                }
            } catch (e: Exception) {
                listener.onFileError(fileId, "保存文件失败：${e.message}")
            } finally {
                receivedBytes.remove(fileId)
                receivedBytes.remove(fileId + INDEX_OFFSET)
                fileTotals.remove(fileId)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        connected = false
        socket?.close(1000, "app closed")
        socket = null
        fileExecutor.execute {
            sinks.values.forEach { try { it.close() } catch (_: Exception) { } }
            sinks.clear()
        }
        fileExecutor.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        private const val TAG = "LanTouchpadWS"
        const val CHUNK_SIZE = 64 * 1024
        private const val MAGIC_SCREEN = 0x01
        private const val MAGIC_FILE = 0x02
        // Store chunk index alongside byte count without another map.
        private const val INDEX_OFFSET = 0x1_0000_0000L
    }
}
````

## `android/app/src/main/res/layout/activity_main.xml`

````xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#0C111B">

    <LinearLayout
        android:id="@+id/mainRow"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="horizontal"
        android:padding="8dp">

        <FrameLayout
            android:id="@+id/screenFrame"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1.65"
            android:background="#05080E">

            <com.example.lantouchpad.ScreenView
                android:id="@+id/screenView"
                android:layout_width="match_parent"
                android:layout_height="match_parent" />

            <TextView
                android:id="@+id/tvScreenStatus"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_gravity="top|start"
                android:layout_margin="10dp"
                android:background="#B31B2533"
                android:paddingHorizontal="10dp"
                android:paddingVertical="6dp"
                android:text="未连接 · 等待画面"
                android:textColor="#E8F0FA"
                android:textSize="12sp" />

            <LinearLayout
                android:id="@+id/quickPanel"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_gravity="bottom|end"
                android:layout_marginEnd="12dp"
                android:layout_marginBottom="64dp"
                android:background="#EE182333"
                android:elevation="8dp"
                android:orientation="vertical"
                android:padding="6dp"
                android:visibility="gone">

                <Button
                    android:id="@+id/quickAltTab"
                    android:layout_width="wrap_content"
                    android:layout_height="42dp"
                    android:text="Alt + Tab"
                    android:textAllCaps="false"
                    android:textSize="12sp" />
                <Button
                    android:id="@+id/quickCtrlShiftEsc"
                    android:layout_width="wrap_content"
                    android:layout_height="42dp"
                    android:text="任务管理器"
                    android:textAllCaps="false"
                    android:textSize="12sp" />
                <Button
                    android:id="@+id/quickWinD"
                    android:layout_width="wrap_content"
                    android:layout_height="42dp"
                    android:text="显示桌面"
                    android:textAllCaps="false"
                    android:textSize="12sp" />
                <Button
                    android:id="@+id/quickEsc"
                    android:layout_width="wrap_content"
                    android:layout_height="42dp"
                    android:text="Esc"
                    android:textAllCaps="false"
                    android:textSize="12sp" />
                <Button
                    android:id="@+id/quickEnter"
                    android:layout_width="wrap_content"
                    android:layout_height="42dp"
                    android:text="Enter"
                    android:textAllCaps="false"
                    android:textSize="12sp" />
            </LinearLayout>

            <Button
                android:id="@+id/btnQuick"
                android:layout_width="52dp"
                android:layout_height="52dp"
                android:layout_gravity="bottom|end"
                android:layout_margin="10dp"
                android:backgroundTint="#1B9E8C"
                android:elevation="8dp"
                android:text="⌘"
                android:textColor="#FFFFFF"
                android:textSize="22sp" />
        </FrameLayout>

        <LinearLayout
            android:id="@+id/sidePanel"
            android:layout_width="350dp"
            android:layout_height="match_parent"
            android:layout_marginStart="8dp"
            android:background="#151E2C"
            android:orientation="vertical"
            android:padding="10dp">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="42dp"
                android:gravity="center_vertical"
                android:orientation="horizontal">
                <TextView
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="LAN TOUCHPAD"
                    android:textColor="#ECF4FF"
                    android:textSize="15sp"
                    android:textStyle="bold" />
                <TextView
                    android:id="@+id/tvConnection"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="● 离线"
                    android:textColor="#F0B45A"
                    android:textSize="12sp" />
            </LinearLayout>

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="42dp"
                android:gravity="center_vertical"
                android:orientation="horizontal">
                <EditText
                    android:id="@+id/etHost"
                    android:layout_width="0dp"
                    android:layout_height="match_parent"
                    android:layout_weight="1"
                    android:hint="电脑 IP，例如 192.168.1.10"
                    android:inputType="textUri"
                    android:singleLine="true"
                    android:textColor="#FFFFFF"
                    android:textColorHint="#8795A8"
                    android:textSize="12sp" />
                <Button
                    android:id="@+id/btnConnect"
                    android:layout_width="78dp"
                    android:layout_height="match_parent"
                    android:layout_marginStart="6dp"
                    android:text="连接"
                    android:textAllCaps="false"
                    android:textSize="12sp" />
            </LinearLayout>

            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:text="画面控制中心"
                android:textColor="#9FB1C7"
                android:textSize="11sp" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="44dp"
                android:gravity="center_vertical"
                android:orientation="horizontal">
                <Spinner
                    android:id="@+id/spQuality"
                    android:layout_width="0dp"
                    android:layout_height="match_parent"
                    android:layout_weight="1.15"
                    android:spinnerMode="dropdown" />
                <Spinner
                    android:id="@+id/spFps"
                    android:layout_width="0dp"
                    android:layout_height="match_parent"
                    android:layout_marginStart="6dp"
                    android:layout_weight="0.85"
                    android:spinnerMode="dropdown" />
            </LinearLayout>

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="42dp"
                android:orientation="horizontal">
                <Button
                    android:id="@+id/btnTouchMode"
                    android:layout_width="0dp"
                    android:layout_height="match_parent"
                    android:layout_weight="1"
                    android:text="触控板"
                    android:textAllCaps="false"
                    android:textSize="12sp" />
                <Button
                    android:id="@+id/btnGamepadMode"
                    android:layout_width="0dp"
                    android:layout_height="match_parent"
                    android:layout_marginStart="6dp"
                    android:layout_weight="1"
                    android:text="虚拟手柄"
                    android:textAllCaps="false"
                    android:textSize="12sp" />
            </LinearLayout>

            <FrameLayout
                android:id="@+id/controlContainer"
                android:layout_width="match_parent"
                android:layout_height="0dp"
                android:layout_marginTop="6dp"
                android:layout_weight="1">

                <com.example.lantouchpad.TouchpadView
                    android:id="@+id/touchpadView"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent" />

                <LinearLayout
                    android:id="@+id/gamepadPanel"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:orientation="vertical"
                    android:visibility="gone">
                    <com.example.lantouchpad.JoystickView
                        android:id="@+id/joystickView"
                        android:layout_width="match_parent"
                        android:layout_height="0dp"
                        android:layout_weight="1" />
                    <GridLayout
                        android:id="@+id/gamepadButtons"
                        android:layout_width="match_parent"
                        android:layout_height="118dp"
                        android:columnCount="4"
                        android:rowCount="2">
                        <Button android:id="@+id/gpLB" android:layout_width="0dp" android:layout_height="52dp" android:layout_columnWeight="1" android:text="LB" android:textSize="11sp" />
                        <Button android:id="@+id/gpLT" android:layout_width="0dp" android:layout_height="52dp" android:layout_columnWeight="1" android:text="LT" android:textSize="11sp" />
                        <Button android:id="@+id/gpRB" android:layout_width="0dp" android:layout_height="52dp" android:layout_columnWeight="1" android:text="RB" android:textSize="11sp" />
                        <Button android:id="@+id/gpRT" android:layout_width="0dp" android:layout_height="52dp" android:layout_columnWeight="1" android:text="RT" android:textSize="11sp" />
                        <Button android:id="@+id/gpX" android:layout_width="0dp" android:layout_height="52dp" android:layout_columnWeight="1" android:text="X" android:textSize="11sp" />
                        <Button android:id="@+id/gpY" android:layout_width="0dp" android:layout_height="52dp" android:layout_columnWeight="1" android:text="Y" android:textSize="11sp" />
                        <Button android:id="@+id/gpA" android:layout_width="0dp" android:layout_height="52dp" android:layout_columnWeight="1" android:text="A" android:textSize="11sp" />
                        <Button android:id="@+id/gpB" android:layout_width="0dp" android:layout_height="52dp" android:layout_columnWeight="1" android:text="B" android:textSize="11sp" />
                    </GridLayout>
                </LinearLayout>
            </FrameLayout>

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="42dp"
                android:gravity="center_vertical"
                android:orientation="horizontal">
                <Button
                    android:id="@+id/btnSendFile"
                    android:layout_width="0dp"
                    android:layout_height="match_parent"
                    android:layout_weight="1"
                    android:text="发文件到电脑"
                    android:textAllCaps="false"
                    android:textSize="11sp" />
                <Button
                    android:id="@+id/btnPcFileHint"
                    android:layout_width="0dp"
                    android:layout_height="match_parent"
                    android:layout_marginStart="6dp"
                    android:layout_weight="1"
                    android:text="电脑发文件"
                    android:textAllCaps="false"
                    android:textSize="11sp" />
            </LinearLayout>

            <ProgressBar
                android:id="@+id/fileProgress"
                style="?android:attr/progressBarStyleHorizontal"
                android:layout_width="match_parent"
                android:layout_height="5dp"
                android:max="100"
                android:progress="0"
                android:visibility="gone" />
            <TextView
                android:id="@+id/tvFileStatus"
                android:layout_width="match_parent"
                android:layout_height="18dp"
                android:gravity="center_vertical"
                android:text="文件传输空闲"
                android:textColor="#8F9EB2"
                android:textSize="10sp" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="46dp"
                android:gravity="center_vertical"
                android:orientation="horizontal">
                <EditText
                    android:id="@+id/etText"
                    android:layout_width="0dp"
                    android:layout_height="match_parent"
                    android:layout_weight="1"
                    android:hint="输入文字后发送到电脑"
                    android:imeOptions="actionSend"
                    android:inputType="textCapSentences|textMultiLine"
                    android:maxLines="2"
                    android:textColor="#FFFFFF"
                    android:textColorHint="#8795A8"
                    android:textSize="12sp" />
                <Button
                    android:id="@+id/btnSendText"
                    android:layout_width="62dp"
                    android:layout_height="match_parent"
                    android:layout_marginStart="6dp"
                    android:text="发送"
                    android:textAllCaps="false"
                    android:textSize="12sp" />
            </LinearLayout>
        </LinearLayout>
    </LinearLayout>
</FrameLayout>
````

## `android/app/src/main/res/values/styles.xml`

````xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="AppTheme" parent="android:style/Theme.Material.Light.NoActionBar">
        <item name="android:fontFamily">sans</item>
        <item name="android:colorAccent">#55D6BE</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:statusBarColor">#0C111B</item>
        <item name="android:navigationBarColor">#0C111B</item>
        <item name="android:windowActionModeOverlay">true</item>
        <item name="android:windowBackground">#0C111B</item>
    </style>
</resources>
````

## `android/build.gradle`

````groovy
plugins {
    id 'com.android.application' version '8.7.2' apply false
    id 'org.jetbrains.kotlin.android' version '2.1.10' apply false
}
````

## `android/gradle.properties`

````properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
kotlin.code.style=official
````

## `android/settings.gradle`

````groovy
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "LanTouchpad"
include(":app")
````

## `protocol.md`

````markdown
# LAN Touchpad 通信协议（v1）

## 1. 连接与传输规则

- 传输层：WebSocket，地址 `ws://<Windows-LAN-IP>:8765`。
- 连接方向：Android 平板作为客户端；Windows 作为服务端。
- **文本 WebSocket 帧只承载 JSON 控制/状态消息。**
- **二进制 WebSocket 帧只承载屏幕 JPEG 或文件分块。**
- 多字节整数使用网络字节序（Big Endian）。
- 文件块大小：64 KiB（最后一块可以小于 64 KiB）。
- v1 同时只接受一个 Android 控制端；同一个连接允许同时收发画面与文件。

## 2. 二进制帧格式

### 2.1 屏幕画面：`0x01`

| 偏移 | 长度 | 内容 |
|---:|---:|---|
| 0 | 1 byte | `0x01`，屏幕帧类型 |
| 1 | 剩余全部 | JPEG 图片字节 |

示例：`01 FF D8 FF ... FF D9`。Android 收到后去掉首字节，将剩余数据交给 JPEG 解码器。

### 2.2 文件数据：`0x02`

| 偏移 | 长度 | 内容 |
|---:|---:|---|
| 0 | 1 byte | `0x02`，文件数据类型 |
| 1 | 4 bytes | `file_id`，无符号 32 位大端整数 |
| 5 | 4 bytes | `chunk_index`，无符号 32 位大端整数，从 0 连续递增 |
| 9 | 0–65536 bytes | 文件块原始数据 |

固定包头为 **9 bytes（1 字节类型 + 4 字节文件 ID + 4 字节块序号）**。文件数据不再额外编码或压缩。

## 3. JSON 文本消息

所有消息都必须是 JSON 对象，并包含 `type`。

### 3.1 鼠标

```json
{"type":"mouse","action":"move","dx":12,"dy":-5}
{"type":"mouse","action":"click","button":"left"}
{"type":"mouse","action":"down","button":"left"}
{"type":"mouse","action":"up","button":"left"}
{"type":"mouse","action":"scroll","amount":-2}
```

`action`：`move | click | down | up | scroll`；`button`：`left | right | middle`；`dx/dy` 为相对移动量。

### 3.2 键盘

```json
{"type":"key","action":"press","key":"ENTER"}
{"type":"key","action":"down","key":"CTRL"}
{"type":"key","action":"up","key":"CTRL"}
{"type":"key","action":"hotkey","keys":["CTRL","SHIFT","ESC"]}
{"type":"key","action":"text","text":"Hello world"}
```

`text` 在 Windows 端通过 Unicode 剪贴板粘贴，以支持中文输入。远端按键经过白名单映射，避免执行任意系统命令。

### 3.3 虚拟手柄

```json
{"type":"gamepad","action":"button","button":"A","pressed":true}
{"type":"gamepad","action":"axis","axis":"LX","lx":-0.4,"ly":0.7}
{"type":"gamepad","action":"axis","axis":"RX","rx":0.2,"ry":-0.3}
{"type":"gamepad","action":"trigger","trigger":"LT","value":0.75}
```

按钮：`A/B/X/Y/LB/RB/BACK/START/L3/R3/DPAD_UP/DPAD_DOWN/DPAD_LEFT/DPAD_RIGHT`。摇杆范围 `[-1, 1]`，扳机范围 `[0, 1]`。

### 3.4 屏幕档位

```json
{"type":"screen_ctrl","quality":"saver","fps":15}
{"type":"screen_ctrl","quality":"standard","fps":30}
{"type":"screen_ctrl","quality":"hd","fps":60}
```

| quality | 最大输出尺寸（保持原始宽高比，不放大） | JPEG quality | 用途 |
|---|---:|---:|---|
| `saver` | 512 × 288 | 28 | 默认省流 |
| `standard` | 1280 × 720 | 55 | 日常操作 |
| `hd` | 1920 × 1080 | 78 | 看细节 |

`fps` 只允许 `15 | 30 | 60`。服务端按当前配置实时调整截屏周期。

### 3.5 文件传输握手

文件传输除题目要求的 `file_start / file_end` 外，补充 `file_ready` 握手，避免发送方先于接收方创建文件目标。Android 点击“电脑发文件”时，会额外发送 `file_request`，Windows 打开本机文件选择框；命令行 `send <path>` 也可作为替代方式。

```json
{"type":"file_request"}
```

**平板 → 电脑：**

1. 平板发送 `file_start`：
   ```json
   {"type":"file_start","file_id":123,"name":"notes.txt","size":2048,"direction":"to_server","chunk_size":65536}
   ```
2. 电脑创建临时文件后回复：
   ```json
   {"type":"file_ready","file_id":123,"direction":"to_server","chunk_size":65536}
   ```
3. 平板按 `0x02` 二进制格式发送块，`chunk_index` 从 0 开始。
4. 平板发送：
   ```json
   {"type":"file_end","file_id":123,"direction":"to_server","size":2048}
   ```
5. 电脑校验长度并将 `.part` 文件原子改名，回复 `file_complete`。

**电脑 → 平板：**

1. 电脑发送 `file_start`，其中 `direction` 为 `to_client`。
2. 平板弹出系统“创建/保存文件”选择器。用户选定目标后，平板打开输出流并发送 `file_ready`（`direction: to_client`）。
3. 电脑收到 ready 后发送 `0x02` 文件块，最后发送 `file_end`。
4. 平板按序写入并校验声明长度，完成后通知 UI。

`size` 为字节数；若 Android 内容提供器无法返回文件大小，可传 `-1`，表示未知长度，接收端仍会统计实际长度。

### 3.6 进度、结束与错误

```json
{"type":"file_progress","file_id":123,"done":1048576,"total":5242880}
{"type":"file_complete","file_id":123,"name":"notes.txt","size":2048,"path":"received/notes.txt"}
{"type":"file_error","file_id":123,"message":"chunk index mismatch"}
{"type":"file_cancel","file_id":123}
```

`file_progress` 的 `total` 为 `-1` 表示总大小未知。

### 3.7 心跳与服务端问候

```json
{"type":"ping","time":1730000000}
{"type":"pong","time":1730000000}
{"type":"hello","server":"LAN Touchpad Server","chunk_size":65536,"quality":"saver","fps":15}
```

WebSocket 协议自身也启用了 ping/pong 控制帧；JSON ping/pong 用于应用层状态检测。

## 4. 错误处理原则

- 未知二进制类型直接忽略；文件帧包头不足 9 字节时拒绝。
- 文件块必须按序到达；不接受跳号、超长块或超过声明总长度的数据。
- 接收端先写入 `.part` 临时文件，长度验证通过后才改为正式文件名。
- 连接断开时关闭未完成的文件句柄；未完成 `.part` 文件不会被当作已完成文件。
- v1 不实现自动重传；出现丢块或写入错误时结束该文件传输并提示错误。

## 5. 安全边界

这是一个无账号、无配对码、无 TLS 的可信局域网工具。任何能访问 TCP 8765 的设备都可能控制鼠标键盘、操作虚拟手柄并向电脑发送文件。仅允许在自己控制的家庭/实验室 LAN 中使用，不要配置端口映射，不要让 Windows 防火墙对“公用网络”开放该端口。服务端接收文件只写入程序目录下的 `received/`。
````

## `requirements.txt`

````text
# 推荐 Python 3.10–3.14 / Windows 10–11
websockets==16.1
pyautogui==0.9.54
vgamepad==0.1.0
Pillow==12.2.0
mss==10.2.0
pyinstaller==6.22.3
````

## `windows/build_exe.bat`

````bat
@echo off
setlocal
cd /d "%~dp0"

echo [1/3] Checking Python...
py -3 --version
if errorlevel 1 (
    echo ERROR: Python 3.10+ was not found. Install Python and enable the py launcher.
    pause
    exit /b 1
)
py -3 -c "import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)"
if errorlevel 1 (
    echo ERROR: Python 3.10 or newer is required. Select a supported Python with the py launcher.
    pause
    exit /b 1
)

echo [2/3] Installing dependencies...
py -3 -m pip install --upgrade pip
if errorlevel 1 goto :error
py -3 -m pip install -r requirements.txt
if errorlevel 1 goto :error

echo [3/3] Building single-file server.exe...
py -3 -m PyInstaller --noconfirm --clean --onefile --name server --console ^
  --collect-all vgamepad ^
  --collect-all mss ^
  --collect-all pyautogui ^
  server.py
if errorlevel 1 goto :error

echo.
echo BUILD SUCCESS: %CD%\dist\server.exe
echo Run server.exe, then allow it through Windows Defender Firewall on Private networks.
pause
exit /b 0

:error
echo.
echo BUILD FAILED. Read the error above.
pause
exit /b 1
````

## `windows/requirements.txt`

````text
# 推荐 Python 3.10–3.14 / Windows 10–11
websockets==16.1
pyautogui==0.9.54
vgamepad==0.1.0
Pillow==12.2.0
mss==10.2.0
pyinstaller==6.22.3
````

## `windows/server.py`

````python
from __future__ import annotations

import asyncio
import io
import json
import os
import re
import socket
import struct
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import mss
import pyautogui
import websockets
from PIL import Image

try:
    import vgamepad as vg
except Exception:
    vg = None

HOST = "0.0.0.0"
PORT = 8765
CHUNK_SIZE = 64 * 1024
MAGIC_SCREEN = 0x01
MAGIC_FILE = 0x02
RECEIVED_DIR = Path("received")

QUALITY_PRESETS = {
    "saver": {"width": 512, "height": 288, "jpeg": 28},
    "standard": {"width": 1280, "height": 720, "jpeg": 55},
    "hd": {"width": 1920, "height": 1080, "jpeg": 78},
}
VALID_FPS = {15, 30, 60}

# Remote key names are deliberately restricted to PyAutoGUI's known key names.
KEY_MAP = {
    "CTRL": "ctrl", "ALT": "alt", "SHIFT": "shift", "WIN": "winleft",
    "ENTER": "enter", "ESC": "esc", "TAB": "tab", "SPACE": "space",
    "BACKSPACE": "backspace", "DELETE": "delete", "HOME": "home",
    "END": "end", "PAGEUP": "pageup", "PAGEDOWN": "pagedown",
    "UP": "up", "DOWN": "down", "LEFT": "left", "RIGHT": "right",
    "F1": "f1", "F2": "f2", "F3": "f3", "F4": "f4", "F5": "f5",
    "F6": "f6", "F7": "f7", "F8": "f8", "F9": "f9", "F10": "f10",
    "F11": "f11", "F12": "f12", "PRINTSCREEN": "printscreen",
}

GAMEPAD_BUTTONS = {}
if vg is not None:
    GAMEPAD_BUTTONS = {
        "A": vg.XUSB_BUTTON.XUSB_GAMEPAD_A,
        "B": vg.XUSB_BUTTON.XUSB_GAMEPAD_B,
        "X": vg.XUSB_BUTTON.XUSB_GAMEPAD_X,
        "Y": vg.XUSB_BUTTON.XUSB_GAMEPAD_Y,
        "LB": vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER,
        "RB": vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER,
        "BACK": vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK,
        "START": vg.XUSB_BUTTON.XUSB_GAMEPAD_START,
        "L3": vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB,
        "R3": vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_THUMB,
        "DPAD_UP": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP,
        "DPAD_DOWN": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN,
        "DPAD_LEFT": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT,
        "DPAD_RIGHT": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT,
    }


def private_ipv4_addresses() -> list[str]:
    """Return candidate local IPv4 addresses without contacting an external host."""
    values: set[str] = set()
    try:
        for item in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = item[4][0]
            if not ip.startswith("127.") and not ip.startswith("169.254."):
                values.add(ip)
    except OSError:
        pass
    # This selects the default interface locally; UDP connect sends no payload.
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("192.0.2.1", 9))
        ip = probe.getsockname()[0]
        probe.close()
        if ip and not ip.startswith("127."):
            values.add(ip)
    except OSError:
        pass
    return sorted(values)


def safe_filename(name: str) -> str:
    name = Path(str(name)).name
    name = re.sub(r"[\\/:*?\"<>|\x00-\x1f]", "_", name).strip(" .")
    return (name or "received_file")[:180]


def set_windows_clipboard_text(text: str) -> None:
    """Set Unicode clipboard text using Win32 APIs, avoiding an extra dependency."""
    import ctypes
    from ctypes import wintypes

    user32 = ctypes.WinDLL("user32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    GMEM_MOVEABLE = 0x0002
    CF_UNICODETEXT = 13
    payload = (text + "\0").encode("utf-16-le")

    user32.OpenClipboard.argtypes = [wintypes.HWND]
    user32.OpenClipboard.restype = wintypes.BOOL
    user32.EmptyClipboard.restype = wintypes.BOOL
    user32.SetClipboardData.argtypes = [wintypes.UINT, wintypes.HANDLE]
    user32.SetClipboardData.restype = wintypes.HANDLE
    user32.CloseClipboard.restype = wintypes.BOOL
    kernel32.GlobalAlloc.argtypes = [wintypes.UINT, ctypes.c_size_t]
    kernel32.GlobalAlloc.restype = wintypes.HGLOBAL
    kernel32.GlobalLock.argtypes = [wintypes.HGLOBAL]
    kernel32.GlobalLock.restype = wintypes.LPVOID
    kernel32.GlobalUnlock.argtypes = [wintypes.HGLOBAL]
    kernel32.GlobalUnlock.restype = wintypes.BOOL

    if not user32.OpenClipboard(None):
        raise ctypes.WinError(ctypes.get_last_error())
    handle = None
    try:
        if not user32.EmptyClipboard():
            raise ctypes.WinError(ctypes.get_last_error())
        handle = kernel32.GlobalAlloc(GMEM_MOVEABLE, len(payload))
        if not handle:
            raise ctypes.WinError(ctypes.get_last_error())
        pointer = kernel32.GlobalLock(handle)
        if not pointer:
            raise ctypes.WinError(ctypes.get_last_error())
        ctypes.memmove(pointer, payload, len(payload))
        kernel32.GlobalUnlock(handle)
        if not user32.SetClipboardData(CF_UNICODETEXT, handle):
            raise ctypes.WinError(ctypes.get_last_error())
        handle = None  # Ownership transferred to the system.
    finally:
        user32.CloseClipboard()
        if handle:
            kernel32.GlobalFree(handle)


@dataclass
class IncomingFile:
    file_id: int
    name: str
    expected_size: int
    temp_path: Path
    final_path: Path
    stream: Any
    next_index: int = 0
    received: int = 0


@dataclass
class OutgoingFile:
    file_id: int
    path: Path
    name: str
    size: int
    ready: asyncio.Event = field(default_factory=asyncio.Event)
    cancelled: bool = False


@dataclass
class ClientSession:
    websocket: Any
    send_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    quality: str = "saver"
    fps: int = 15
    incoming: dict[int, IncomingFile] = field(default_factory=dict)
    outgoing: dict[int, OutgoingFile] = field(default_factory=dict)
    gamepad: Any = None
    capture: Any = None
    file_picker_active: bool = False

    async def send_json(self, payload: dict[str, Any]) -> None:
        data = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
        async with self.send_lock:
            await self.websocket.send(data)

    async def send_binary(self, payload: bytes) -> None:
        async with self.send_lock:
            await self.websocket.send(payload)

    async def close_files(self) -> None:
        for transfer in self.incoming.values():
            try:
                transfer.stream.close()
            except Exception:
                pass
        self.incoming.clear()


_ACTIVE: ClientSession | None = None
_CAPTURE_EXECUTOR = ThreadPoolExecutor(max_workers=1, thread_name_prefix="mss-capture")
_INPUT_EXECUTOR = ThreadPoolExecutor(max_workers=1, thread_name_prefix="remote-input")


def capture_jpeg(session: ClientSession) -> bytes | None:
    """Capture and JPEG-encode one frame. Called in asyncio's worker thread."""
    try:
        if session.capture is None:
            session.capture = mss.mss()
        monitor = session.capture.monitors[1]  # Primary monitor.
        shot = session.capture.grab(monitor)
        image = Image.frombytes("RGB", shot.size, shot.rgb)
        preset = QUALITY_PRESETS[session.quality]
        image.thumbnail((preset["width"], preset["height"]), Image.Resampling.LANCZOS)
        buffer = io.BytesIO()
        image.save(buffer, format="JPEG", quality=preset["jpeg"], optimize=False)
        return buffer.getvalue()
    except Exception as exc:
        print(f"[屏幕] 截图失败：{exc}")
        return None


async def screen_stream(session: ClientSession) -> None:
    while True:
        started = time.monotonic()
        loop = asyncio.get_running_loop()
        jpeg = await loop.run_in_executor(_CAPTURE_EXECUTOR, capture_jpeg, session)
        if jpeg:
            try:
                await session.send_binary(bytes([MAGIC_SCREEN]) + jpeg)
            except websockets.ConnectionClosed:
                return
        interval = 1.0 / max(1, min(session.fps, 60))
        remaining = interval - (time.monotonic() - started)
        if remaining > 0:
            await asyncio.sleep(remaining)


def do_mouse(data: dict[str, Any]) -> None:
    action = str(data.get("action", ""))
    if action == "move":
        dx = max(-2000, min(2000, int(float(data.get("dx", 0)))))
        dy = max(-2000, min(2000, int(float(data.get("dy", 0)))))
        pyautogui.moveRel(dx, dy, duration=0)
    elif action == "click":
        button = data.get("button", "left")
        if button in ("left", "right", "middle"):
            pyautogui.click(button=button)
    elif action in ("down", "up"):
        button = data.get("button", "left")
        if button in ("left", "right", "middle"):
            (pyautogui.mouseDown if action == "down" else pyautogui.mouseUp)(button=button)
    elif action == "scroll":
        amount = max(-20, min(20, int(float(data.get("amount", 0)))))
        if amount:
            pyautogui.scroll(amount)


def do_key(data: dict[str, Any]) -> None:
    action = str(data.get("action", ""))
    if action == "text":
        value = str(data.get("text", ""))[:4000]
        if value:
            set_windows_clipboard_text(value)
            pyautogui.hotkey("ctrl", "v")
        return

    if action == "hotkey":
        keys = data.get("keys", [])
        if not isinstance(keys, list) or not 1 <= len(keys) <= 4:
            return
        mapped = [KEY_MAP.get(str(k).upper()) for k in keys]
        if all(mapped):
            pyautogui.hotkey(*mapped)
        return

    raw_key = str(data.get("key", "")).upper()
    key = KEY_MAP.get(raw_key)
    if key is None and len(raw_key) == 1 and raw_key.isalnum():
        key = raw_key.lower()
    if key is None:
        return
    if action == "down":
        pyautogui.keyDown(key)
    elif action == "up":
        pyautogui.keyUp(key)
    elif action == "press":
        pyautogui.press(key)


def do_gamepad(session: ClientSession, data: dict[str, Any]) -> None:
    if session.gamepad is None:
        return
    action = str(data.get("action", ""))
    if action == "button":
        name = str(data.get("button", "")).upper()
        button = GAMEPAD_BUTTONS.get(name)
        if button is not None:
            if bool(data.get("pressed", False)):
                session.gamepad.press_button(button=button)
            else:
                session.gamepad.release_button(button=button)
            session.gamepad.update()
    elif action == "axis":
        axis = str(data.get("axis", "")).upper()
        value = max(-1.0, min(1.0, float(data.get("value", 0.0))))
        if axis in ("LX", "LY"):
            current_x = float(data.get("lx", value if axis == "LX" else 0.0))
            current_y = float(data.get("ly", value if axis == "LY" else 0.0))
            session.gamepad.left_joystick_float(
                x_value_float=max(-1.0, min(1.0, current_x)),
                y_value_float=max(-1.0, min(1.0, current_y)),
            )
            session.gamepad.update()
        elif axis in ("RX", "RY"):
            current_x = float(data.get("rx", value if axis == "RX" else 0.0))
            current_y = float(data.get("ry", value if axis == "RY" else 0.0))
            session.gamepad.right_joystick_float(
                x_value_float=max(-1.0, min(1.0, current_x)),
                y_value_float=max(-1.0, min(1.0, current_y)),
            )
            session.gamepad.update()
    elif action == "trigger":
        name = str(data.get("trigger", "")).upper()
        value = max(0.0, min(1.0, float(data.get("value", 0.0))))
        raw = int(round(value * 255))
        if name == "LT":
            session.gamepad.left_trigger(value=raw)
        elif name == "RT":
            session.gamepad.right_trigger(value=raw)
        else:
            return
        session.gamepad.update()


async def handle_binary(session: ClientSession, data: bytes) -> None:
    if not data:
        return
    packet_type = data[0]
    if packet_type != MAGIC_FILE:
        return
    if len(data) < 9:
        await session.send_json({"type": "file_error", "message": "文件数据包长度不足"})
        return
    file_id, chunk_index = struct.unpack("!II", data[1:9])
    transfer = session.incoming.get(file_id)
    if transfer is None:
        await session.send_json({"type": "file_error", "file_id": file_id, "message": "未知文件传输 ID"})
        return
    if chunk_index != transfer.next_index:
        await session.send_json({"type": "file_error", "file_id": file_id, "message": f"块序号错误，期望 {transfer.next_index}，收到 {chunk_index}"})
        return
    chunk = data[9:]
    if len(chunk) > CHUNK_SIZE or transfer.received + len(chunk) > max(transfer.expected_size, 0) and transfer.expected_size >= 0:
        await session.send_json({"type": "file_error", "file_id": file_id, "message": "文件块大小或总长度超限"})
        return
    await asyncio.to_thread(transfer.stream.write, chunk)
    transfer.received += len(chunk)
    transfer.next_index += 1
    if transfer.received and transfer.received % (1024 * 1024) < CHUNK_SIZE:
        await session.send_json({"type": "file_progress", "file_id": file_id, "done": transfer.received, "total": transfer.expected_size})


async def begin_incoming_file(session: ClientSession, data: dict[str, Any]) -> None:
    file_id = int(data.get("file_id", -1))
    name = safe_filename(str(data.get("name", "received_file")))
    size = int(data.get("size", -1))
    direction = str(data.get("direction", ""))
    if direction != "to_server" or not 0 <= file_id <= 0xFFFFFFFF or size < -1:
        await session.send_json({"type": "file_error", "file_id": file_id, "message": "文件开始参数无效"})
        return
    if file_id in session.incoming or file_id in session.outgoing:
        await session.send_json({"type": "file_error", "file_id": file_id, "message": "文件 ID 冲突"})
        return
    RECEIVED_DIR.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d_%H%M%S")
    final_path = RECEIVED_DIR / f"{stamp}_{name}"
    suffix = 1
    while final_path.exists():
        final_path = RECEIVED_DIR / f"{stamp}_{suffix}_{name}"
        suffix += 1
    temp_path = final_path.with_name(final_path.name + ".part")
    stream = await asyncio.to_thread(open, temp_path, "wb")
    session.incoming[file_id] = IncomingFile(file_id, name, size, temp_path, final_path, stream)
    await session.send_json({"type": "file_ready", "file_id": file_id, "direction": "to_server", "chunk_size": CHUNK_SIZE})
    print(f"[文件] 平板开始上传：{name} ({size} bytes)")


async def finish_incoming_file(session: ClientSession, data: dict[str, Any]) -> None:
    file_id = int(data.get("file_id", -1))
    transfer = session.incoming.pop(file_id, None)
    if transfer is None:
        await session.send_json({"type": "file_error", "file_id": file_id, "message": "找不到待完成的文件"})
        return
    await asyncio.to_thread(transfer.stream.flush)
    await asyncio.to_thread(transfer.stream.close)
    if transfer.expected_size >= 0 and transfer.received != transfer.expected_size:
        try:
            transfer.temp_path.unlink(missing_ok=True)
        except OSError:
            pass
        await session.send_json({"type": "file_error", "file_id": file_id, "message": f"文件长度不一致：收到 {transfer.received} / {transfer.expected_size}"})
        return
    await asyncio.to_thread(os.replace, transfer.temp_path, transfer.final_path)
    await session.send_json({"type": "file_complete", "file_id": file_id, "name": transfer.name, "size": transfer.received, "path": str(transfer.final_path.resolve())})
    print(f"[文件] 已接收：{transfer.final_path.resolve()}")


def choose_file_dialog() -> Path | None:
    """Open a native Windows file picker. Tk objects are created and destroyed in this worker thread."""
    import tkinter as tk
    from tkinter import filedialog

    root = tk.Tk()
    root.withdraw()
    root.attributes("-topmost", True)
    try:
        selected = filedialog.askopenfilename(parent=root, title="选择要发送到 Android 平板的文件")
        return Path(selected) if selected else None
    finally:
        root.destroy()


async def choose_and_send_file(session: ClientSession) -> None:
    try:
        selected = await asyncio.to_thread(choose_file_dialog)
        if selected is not None:
            await send_file_to_client(session, selected)
    except Exception as exc:
        print(f"[文件] 打开选择框或发送失败：{exc}")
        try:
            await session.send_json({"type": "file_error", "file_id": -1, "message": str(exc)})
        except Exception:
            pass
    finally:
        session.file_picker_active = False


async def send_file_to_client(session: ClientSession, path: Path) -> None:
    if not path.is_file():
        print(f"[文件] 文件不存在：{path}")
        return
    file_id = int.from_bytes(os.urandom(4), "big")
    while file_id in session.outgoing or file_id in session.incoming:
        file_id = int.from_bytes(os.urandom(4), "big")
    transfer = OutgoingFile(file_id, path, path.name, path.stat().st_size)
    session.outgoing[file_id] = transfer
    try:
        await session.send_json({
            "type": "file_start", "file_id": file_id, "name": path.name,
            "size": transfer.size, "direction": "to_client", "chunk_size": CHUNK_SIZE,
        })
        print(f"[文件] 已发送文件信息，等待平板选择保存位置：{path.name}")
        await asyncio.wait_for(transfer.ready.wait(), timeout=90)
        if transfer.cancelled:
            print(f"[文件] 平板取消接收：{path.name}")
            return
        sent = 0
        index = 0
        with path.open("rb") as stream:
            while True:
                chunk = await asyncio.to_thread(stream.read, CHUNK_SIZE)
                if not chunk:
                    break
                if transfer.cancelled:
                    await session.send_json({"type": "file_error", "file_id": file_id, "message": "接收端取消了传输"})
                    return
                packet = bytes([MAGIC_FILE]) + struct.pack("!II", file_id, index) + chunk
                await session.send_binary(packet)
                sent += len(chunk)
                index += 1
                if sent == transfer.size or sent % (1024 * 1024) < CHUNK_SIZE:
                    await session.send_json({"type": "file_progress", "file_id": file_id, "done": sent, "total": transfer.size})
        await session.send_json({"type": "file_end", "file_id": file_id, "direction": "to_client", "size": sent})
        print(f"[文件] 发送完成：{path.name} ({sent} bytes)")
    except asyncio.TimeoutError:
        await session.send_json({"type": "file_error", "file_id": file_id, "message": "等待平板确认保存超时"})
    except Exception as exc:
        print(f"[文件] 发送失败：{exc}")
        try:
            await session.send_json({"type": "file_error", "file_id": file_id, "message": str(exc)})
        except Exception:
            pass
    finally:
        session.outgoing.pop(file_id, None)


async def handle_text(session: ClientSession, raw: str) -> None:
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        await session.send_json({"type": "error", "message": "无效 JSON"})
        return
    if not isinstance(data, dict):
        return
    kind = str(data.get("type", ""))

    if kind == "screen_ctrl":
        quality = str(data.get("quality", "saver"))
        fps = int(data.get("fps", 15))
        if quality not in QUALITY_PRESETS or fps not in VALID_FPS:
            await session.send_json({"type": "error", "message": "不支持的画面档位"})
            return
        session.quality = quality
        session.fps = fps
        await session.send_json({"type": "screen_ctrl_ack", "quality": quality, "fps": fps})
    elif kind == "mouse":
        try:
            loop = asyncio.get_running_loop()
            await loop.run_in_executor(_INPUT_EXECUTOR, do_mouse, data)
        except Exception as exc:
            print(f"[鼠标] {exc}")
    elif kind == "key":
        try:
            loop = asyncio.get_running_loop()
            await loop.run_in_executor(_INPUT_EXECUTOR, do_key, data)
        except Exception as exc:
            print(f"[键盘] {exc}")
    elif kind == "gamepad":
        try:
            loop = asyncio.get_running_loop()
            await loop.run_in_executor(_INPUT_EXECUTOR, do_gamepad, session, data)
        except Exception as exc:
            print(f"[手柄] {exc}")
    elif kind == "file_request":
        if not session.file_picker_active:
            session.file_picker_active = True
            asyncio.create_task(choose_and_send_file(session), name="choose-file-to-client")
    elif kind == "file_start":
        await begin_incoming_file(session, data)
    elif kind == "file_ready":
        file_id = int(data.get("file_id", -1))
        transfer = session.outgoing.get(file_id)
        if transfer and data.get("direction") == "to_client":
            transfer.ready.set()
    elif kind == "file_end":
        if data.get("direction") == "to_server":
            await finish_incoming_file(session, data)
    elif kind == "file_cancel":
        file_id = int(data.get("file_id", -1))
        transfer = session.incoming.pop(file_id, None)
        if transfer:
            try:
                transfer.stream.close()
                transfer.temp_path.unlink(missing_ok=True)
            except OSError:
                pass
        outgoing = session.outgoing.get(file_id)
        if outgoing:
            outgoing.cancelled = True
            outgoing.ready.set()
    elif kind == "ping":
        await session.send_json({"type": "pong", "time": data.get("time")})


async def client_handler(websocket: Any) -> None:
    global _ACTIVE
    if _ACTIVE is not None:
        await websocket.close(code=1013, reason="Only one controller is supported")
        return

    session = ClientSession(websocket)
    if vg is not None:
        try:
            session.gamepad = vg.VX360Gamepad()
            print("[手柄] Xbox 360 虚拟手柄已就绪")
        except Exception as exc:
            print(f"[手柄] 初始化失败（请确认 ViGEmBus 驱动）：{exc}")
    else:
        print("[手柄] vgamepad 未安装，手柄功能不可用")

    _ACTIVE = session
    print(f"[连接] 平板已连接：{websocket.remote_address}")
    await session.send_json({"type": "hello", "server": "LAN Touchpad Server", "chunk_size": CHUNK_SIZE,
                             "quality": session.quality, "fps": session.fps})
    streamer = asyncio.create_task(screen_stream(session), name="screen-stream")
    try:
        async for message in websocket:
            if isinstance(message, bytes):
                await handle_binary(session, message)
            else:
                await handle_text(session, message)
    except websockets.ConnectionClosed:
        pass
    finally:
        streamer.cancel()
        try:
            await streamer
        except (asyncio.CancelledError, websockets.ConnectionClosed):
            pass
        await session.close_files()
        if session.capture is not None:
            try:
                loop = asyncio.get_running_loop()
                await loop.run_in_executor(_CAPTURE_EXECUTOR, session.capture.close)
            except Exception:
                pass
        if session.gamepad is not None:
            try:
                session.gamepad.reset()
                session.gamepad.update()
            except Exception:
                pass
        _ACTIVE = None
        print("[断开] 平板已断开")


async def console_loop(stop_event: asyncio.Event) -> None:
    print("\n命令：send <文件完整路径>  |  status  |  help  |  quit")
    while not stop_event.is_set():
        line = await asyncio.to_thread(sys.stdin.readline)
        if not line:
            await asyncio.sleep(0.2)
            continue
        command = line.strip()
        if command.lower() in ("quit", "exit"):
            stop_event.set()
            return
        if command.lower() == "help":
            print("send <path>：发送电脑文件到平板；status：连接状态；quit：退出")
            continue
        if command.lower() == "status":
            print("已连接" if _ACTIVE else "等待平板连接")
            continue
        if command.lower().startswith("send "):
            if _ACTIVE is None:
                print("[提示] 请先在平板连接本机")
                continue
            raw_path = command[5:].strip().strip('"')
            try:
                await send_file_to_client(_ACTIVE, Path(raw_path).expanduser())
            except Exception as exc:
                print(f"[命令] {exc}")
            continue
        if command:
            print("未知命令。输入 help 查看帮助。")


async def main() -> None:
    pyautogui.FAILSAFE = True
    pyautogui.PAUSE = 0
    RECEIVED_DIR.mkdir(parents=True, exist_ok=True)
    stop_event = asyncio.Event()
    print("=" * 60)
    print("LAN Touchpad Server — 局域网远程控制")
    print(f"监听：ws://0.0.0.0:{PORT}")
    print("平板连接地址：")
    addresses = private_ipv4_addresses()
    if addresses:
        for address in addresses:
            print(f"  ws://{address}:{PORT}")
    else:
        print("  未能自动识别，请在 Windows 网络设置中查看本机 IPv4")
    print("接收文件目录：", RECEIVED_DIR.resolve())
    print("安全提示：无账号/无加密，仅在可信局域网使用；不要将 8765 端口暴露到互联网。")
    print("=" * 60)

    async with websockets.serve(
        client_handler,
        HOST,
        PORT,
        max_size=16 * 1024 * 1024,
        max_queue=16,
        compression=None,
        ping_interval=20,
        ping_timeout=20,
        close_timeout=5,
    ):
        console_task = asyncio.create_task(console_loop(stop_event))
        await stop_event.wait()
        console_task.cancel()
        try:
            await console_task
        except asyncio.CancelledError:
            pass


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n服务器已停止")
````
