package com.example.lantouchpad

import android.app.Activity
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
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
    private lateinit var touchpadView: TouchpadView
    private lateinit var joystickView: JoystickView
    private lateinit var tvConnection: TextView
    private lateinit var tvScreenStatus: TextView
    private lateinit var tvFileStatus: TextView
    private lateinit var fileProgress: ProgressBar
    private lateinit var etHost: EditText
    private lateinit var etText: EditText
    private lateinit var spQuality: Spinner
    private lateinit var spFps: Spinner
    private lateinit var spMonitor: Spinner
    private lateinit var keyboardPanel: View
    private lateinit var keyboardContainer: LinearLayout

    private var pendingUploadUri: Uri? = null
    private var pendingUploadId = -1L
    private var pendingUploadSize = -1L
    private var pendingDownloadId = -1L
    private var pendingDownloadName = "received_file"
    private var pendingDownloadSize = -1L
    private var pendingDownloadHash = ""
    private var pendingScreenshot: Bitmap? = null
    private var lastUploadUri: Uri? = null
    private var lastUploadName = "upload_file"
    private var lastUploadSize = -1L
    private var activeFileId = -1L
    private var activeFileDirection = ""
    private var filePaused = false
    private var qualityValue = "saver"
    private var fpsValue = 15
    private var monitorValue = 1
    private var monitorIndices = mutableListOf(1)
    private var seenFirstFrame = false
    private var currentScaleMode = ScreenView.ScaleMode.FIT
    private var inputMode = InputMode.RELATIVE
    private var directTouchDown = false
    private var directScrollMode = false
    private var directLastCentroidY = 0f
    private var directPendingScrollPx = 0f

    private enum class InputMode { RELATIVE, DIRECT, GAMEPAD }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        ws = WebSocketManager(this)
        inputMode = when (savedInstanceState?.getString("inputMode")) {
            "DIRECT" -> InputMode.DIRECT
            "GAMEPAD" -> InputMode.GAMEPAD
            else -> InputMode.RELATIVE
        }
        bindLayout(savedInstanceState?.getString("host") ?: getPreferences(Context.MODE_PRIVATE).getString("last_host", "") ?: "")
    }

    private fun bindLayout(hostValue: String = "") {
        setContentView(R.layout.activity_main)
        screenView = findViewById(R.id.screenView)
        screenView.setScaleMode(currentScaleMode)
        touchpadView = findViewById(R.id.touchpadView)
        joystickView = findViewById(R.id.joystickView)
        tvConnection = findViewById(R.id.tvConnection)
        tvScreenStatus = findViewById(R.id.tvScreenStatus)
        tvFileStatus = findViewById(R.id.tvFileStatus)
        fileProgress = findViewById(R.id.fileProgress)
        etHost = findViewById(R.id.etHost)
        etText = findViewById(R.id.etText)
        spQuality = findViewById(R.id.spQuality)
        spFps = findViewById(R.id.spFps)
        spMonitor = findViewById(R.id.spMonitor)
        keyboardPanel = findViewById(R.id.keyboardPanel)
        keyboardContainer = findViewById(R.id.keyboardContainer)
        etHost.setText(hostValue)
        setupSpinners()
        setupControls()
        buildKeyboard()
        applyInputMode(sendReset = false)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A finger or key may still be held when Android swaps the qualified layout.
        if (::ws.isInitialized && ws.isConnected()) sendStateReset()
        val host = if (::etHost.isInitialized) etHost.text.toString() else ""
        bindLayout(host)
        if (ws.isConnected()) ws.sendScreenControl(qualityValue, fpsValue, monitorValue)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::etHost.isInitialized) outState.putString("host", etHost.text.toString())
        outState.putString("inputMode", inputMode.name)
        super.onSaveInstanceState(outState)
    }

    private fun setupSpinners() {
        val qualityLabels = listOf("省流", "标清", "高清", "自适应")
        spQuality.adapter = spinnerAdapter(qualityLabels)
        spQuality.setSelection(listOf("saver", "standard", "hd", "adaptive").indexOf(qualityValue).coerceAtLeast(0))
        spQuality.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                qualityValue = listOf("saver", "standard", "hd", "adaptive").getOrElse(position) { "saver" }
                sendScreenSettings()
            }
        }
        spFps.adapter = spinnerAdapter(listOf("15 FPS", "30 FPS", "60 FPS"))
        spFps.setSelection(listOf(15, 30, 60).indexOf(fpsValue).coerceAtLeast(0))
        spFps.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                fpsValue = listOf(15, 30, 60).getOrElse(position) { 15 }
                sendScreenSettings()
            }
        }
        spMonitor.adapter = spinnerAdapter(listOf("主显示器"))
        spMonitor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                monitorValue = monitorIndices.getOrElse(position) { 1 }
                sendScreenSettings()
            }
        }
    }

    private fun spinnerAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun sendScreenSettings() {
        if (ws.isConnected()) ws.sendScreenControl(qualityValue, fpsValue, monitorValue)
        if (::tvScreenStatus.isInitialized) {
            tvScreenStatus.text = "画面：${qualityLabel()} · $fpsValue FPS · 显示器 $monitorValue"
        }
    }

    private fun setupControls() {
        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            if (ws.isConnected()) {
                sendStateReset()
                ws.disconnect()
                return@setOnClickListener
            }
            val raw = etHost.text.toString().trim()
            val url = normalizeWebSocketUrl(raw)
            if (url == null) {
                toast("请输入有效的 Windows 局域网 IP 或 ws:// 地址")
            } else {
                getPreferences(Context.MODE_PRIVATE).edit().putString("last_host", raw).apply()
                tvConnection.text = "● 连接中"
                tvConnection.setTextColor(0xFFFFC66D.toInt())
                ws.connect(url)
            }
        }

        findViewById<Button>(R.id.btnTouchMode).setOnClickListener { setInputMode(InputMode.RELATIVE) }
        findViewById<Button>(R.id.btnDirectMode).setOnClickListener { setInputMode(InputMode.DIRECT) }
        findViewById<Button>(R.id.btnGamepadMode).setOnClickListener { setInputMode(InputMode.GAMEPAD) }
        findViewById<Button>(R.id.menuTouch).setOnClickListener { setInputMode(InputMode.RELATIVE); hideMenus() }
        findViewById<Button>(R.id.menuDirect).setOnClickListener { setInputMode(InputMode.DIRECT); hideMenus() }
        findViewById<Button>(R.id.menuGamepad).setOnClickListener { setInputMode(InputMode.GAMEPAD); hideMenus() }

        touchpadView.onMouseEvent = { sendJson(it) }
        joystickView.onAxisChange = { stick, x, y ->
            val json = JSONObject().put("type", "gamepad").put("action", "axis")
            if (stick == "L") json.put("axis", "LX").put("lx", x).put("ly", y)
            else json.put("axis", "RX").put("rx", x).put("ry", y)
            sendJson(json)
        }
        screenView.setOnTouchListener { _, event ->
            if (inputMode == InputMode.DIRECT) handleDirectTouch(event) else false
        }

        bindGamepadButton(R.id.gpA, "A")
        bindGamepadButton(R.id.gpB, "B")
        bindGamepadButton(R.id.gpX, "X")
        bindGamepadButton(R.id.gpY, "Y")
        bindGamepadButton(R.id.gpLB, "LB")
        bindGamepadButton(R.id.gpRB, "RB")
        bindGamepadButton(R.id.gpBACK, "BACK")
        bindGamepadButton(R.id.gpSTART, "START")
        bindGamepadButton(R.id.gpL3, "L3")
        bindGamepadButton(R.id.gpR3, "R3")
        bindGamepadButtonIfPresent(R.id.gpDpadUp, "DPAD_UP")
        bindGamepadButtonIfPresent(R.id.gpDpadDown, "DPAD_DOWN")
        bindGamepadButtonIfPresent(R.id.gpDpadLeft, "DPAD_LEFT")
        bindGamepadButtonIfPresent(R.id.gpDpadRight, "DPAD_RIGHT")
        bindTrigger(R.id.gpLT, "LT")
        bindTrigger(R.id.gpRT, "RT")

        findViewById<Button>(R.id.btnQuick).setOnClickListener { toggleVisibility(R.id.quickPanel) }
        findViewById<Button>(R.id.quickAltTab).setOnClickListener { sendHotkey("ALT", "TAB") }
        findViewById<Button>(R.id.quickCtrlShiftEsc).setOnClickListener { sendHotkey("CTRL", "SHIFT", "ESC") }
        findViewById<Button>(R.id.quickWinD).setOnClickListener { sendHotkey("WIN", "D") }
        findViewById<Button>(R.id.quickEsc).setOnClickListener { sendKey("ESC") }
        findViewById<Button>(R.id.quickEnter).setOnClickListener { sendKey("ENTER") }

        findViewById<Button>(R.id.btnSendText).setOnClickListener { sendText() }
        etText.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                sendText(); true
            } else false
        }
        findViewById<Button>(R.id.btnSendFile).setOnClickListener { chooseUploadFile() }
        findViewById<Button>(R.id.btnPcFileHint).setOnClickListener { requestPcFilePicker() }
        findViewById<Button>(R.id.btnPauseFile).setOnClickListener { toggleFilePause() }
        findViewById<Button>(R.id.btnRetryFile).setOnClickListener { retryLastTransfer() }
        findViewById<Button>(R.id.btnCancelFile).setOnClickListener { cancelCurrentTransfer() }
        findViewById<Button>(R.id.btnKeyboard).setOnClickListener { showKeyboard(true) }
        findViewById<Button>(R.id.btnKeyboardClose).setOnClickListener { showKeyboard(false) }
        findViewById<Button>(R.id.menuKeyboard).setOnClickListener { showKeyboard(true); hideMenus() }
        findViewById<Button>(R.id.btnScreenshot).setOnClickListener { createScreenshot() }
        findViewById<Button>(R.id.btnScale).setOnClickListener { cycleScale() }
        findViewById<Button>(R.id.menuQuick).setOnClickListener { toggleVisibility(R.id.quickPanel); hideMenus() }
        findViewById<Button>(R.id.menuScreenshot).setOnClickListener { createScreenshot(); hideMenus() }
        findViewById<Button>(R.id.menuScale).setOnClickListener { cycleScale(); hideMenus() }
        findViewById<Button>(R.id.menuUpload).setOnClickListener { chooseUploadFile(); hideMenus() }
        findViewById<Button>(R.id.menuDownload).setOnClickListener { requestPcFilePicker(); hideMenus() }
        findViewById<Button>(R.id.menuGetPcClipboard).setOnClickListener {
            sendJson(JSONObject().put("type", "clipboard_get")); hideMenus()
        }
        findViewById<Button>(R.id.menuSendPhoneClipboard).setOnClickListener {
            sendPhoneClipboard(); hideMenus()
        }

        findViewById<Button>(R.id.btnLeftMenu).setOnClickListener {
            val panel = findViewById<View>(R.id.leftMenuPanel)
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            findViewById<View>(R.id.rightMenuPanel).visibility = View.GONE
        }
        findViewById<Button>(R.id.btnRightMenu).setOnClickListener {
            val panel = findViewById<View>(R.id.rightMenuPanel)
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            findViewById<View>(R.id.leftMenuPanel).visibility = View.GONE
        }

        val sensitivity = findViewById<SeekBar>(R.id.seekSensitivity)
        touchpadView.sensitivity = 0.35f + (sensitivity.progress / 100f) * 3.65f
        sensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                touchpadView.sensitivity = 0.35f + (progress / 100f) * 3.65f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun normalizeWebSocketUrl(raw: String): String? {
        if (raw.isBlank()) return null
        var value = raw.trim().removeSuffix("/")
        if (!value.startsWith("ws://", true) && !value.startsWith("wss://", true)) {
            value = "ws://$value"
        }
        val schemeEnd = value.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = value.substring(0, schemeEnd + 3)
        val remainder = value.substring(schemeEnd + 3)
        val authorityEnd = listOf(remainder.indexOf('/'), remainder.indexOf('?'), remainder.indexOf('#'))
            .filter { it >= 0 }.minOrNull() ?: remainder.length
        val authority = remainder.substring(0, authorityEnd)
        val suffix = remainder.substring(authorityEnd)
        if (authority.isBlank() || authority.contains(' ')) return null

        val hasPort = if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            val tail = authority.substring(close + 1)
            when {
                tail.isEmpty() -> false
                tail.startsWith(":") -> {
                    val port = tail.drop(1).toIntOrNull() ?: return null
                    if (port in 1..65535) true else return null
                }
                else -> return null
            }
        } else {
            val colonCount = authority.count { it == ':' }
            when (colonCount) {
                0 -> false
                1 -> {
                    val port = authority.substringAfterLast(':').toIntOrNull() ?: return null
                    if (port in 1..65535) true else return null
                }
                else -> return null // IPv6 literals must use [address] notation.
            }
        }
        return if (hasPort) value else "$scheme$authority:8765$suffix"
    }

    private fun applyInputMode(sendReset: Boolean) {
        findViewById<View>(R.id.touchpadView).visibility = if (inputMode == InputMode.RELATIVE) View.VISIBLE else View.GONE
        findViewById<View>(R.id.gamepadPanel).visibility = if (inputMode == InputMode.GAMEPAD) View.VISIBLE else View.GONE
        if (sendReset) {
            sendStateReset()
            joystickView.resetAll()
            directTouchDown = false
            directScrollMode = false
            directPendingScrollPx = 0f
        }
        findViewById<Button>(R.id.btnTouchMode).isEnabled = inputMode != InputMode.RELATIVE
        findViewById<Button>(R.id.btnDirectMode).isEnabled = inputMode != InputMode.DIRECT
        findViewById<Button>(R.id.btnGamepadMode).isEnabled = inputMode != InputMode.GAMEPAD
        tvScreenStatus.text = when (inputMode) {
            InputMode.RELATIVE -> "相对触控板模式"
            InputMode.DIRECT -> "直接触控模式 · 点击/拖动映射到电脑屏幕"
            InputMode.GAMEPAD -> "虚拟手柄模式"
        }
    }

    private fun setInputMode(mode: InputMode) {
        if (inputMode == mode) return
        inputMode = mode
        applyInputMode(sendReset = true)
    }

    private fun handleDirectTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val point = screenView.mapToNormalized(event.x, event.y) ?: return true
                directScrollMode = false
                directPendingScrollPx = 0f
                directTouchDown = true
                sendAbsoluteMove(point.first, point.second)
                sendJson(JSONObject().put("type", "mouse").put("action", "down").put("button", "left"))
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (directTouchDown) sendJson(JSONObject().put("type", "mouse").put("action", "up").put("button", "left"))
                directTouchDown = false
                directScrollMode = true
                directPendingScrollPx = 0f
                if (event.pointerCount >= 2) directLastCentroidY = (event.getY(0) + event.getY(1)) / 2f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (directScrollMode && event.pointerCount >= 2) {
                    val center = (event.getY(0) + event.getY(1)) / 2f
                    val delta = directLastCentroidY - center
                    directLastCentroidY = center
                    directPendingScrollPx += delta
                    val unit = 42f * resources.displayMetrics.density
                    val steps = (directPendingScrollPx / unit).toInt()
                    if (steps != 0) {
                        val sent = steps.coerceIn(-12, 12)
                        sendJson(JSONObject().put("type", "mouse").put("action", "scroll").put("amount", sent))
                        directPendingScrollPx -= sent * unit
                    }
                } else if (directTouchDown) {
                    screenView.mapToNormalized(event.x, event.y)?.let { sendAbsoluteMove(it.first, it.second) }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> return true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (directTouchDown) sendJson(JSONObject().put("type", "mouse").put("action", "up").put("button", "left"))
                directTouchDown = false
                directScrollMode = false
                directPendingScrollPx = 0f
                return true
            }
        }
        return true
    }

    private fun sendAbsoluteMove(x: Float, y: Float) {
        sendJson(JSONObject().put("type", "mouse").put("action", "absolute_move").put("x", x).put("y", y))
    }

    private fun bindGamepadButton(viewId: Int, buttonName: String) {
        bindGamepadButtonIfPresent(viewId, buttonName)
    }

    private fun bindGamepadButtonIfPresent(viewId: Int, buttonName: String) {
        val button = findViewById<Button>(viewId) ?: return
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sendGamepadButton(buttonName, true); view.isPressed = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    sendGamepadButton(buttonName, false); view.isPressed = false; view.performClick()
                }
            }
            true
        }
    }

    private fun bindTrigger(viewId: Int, triggerName: String) {
        val button = findViewById<Button>(viewId) ?: return
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sendTrigger(triggerName, 1f); view.isPressed = true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { sendTrigger(triggerName, 0f); view.isPressed = false; view.performClick() }
            }
            true
        }
    }

    private fun bindKeyboardKey(button: Button, key: String) {
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sendKeyState(key, true)
                    view.isPressed = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    sendKeyState(key, false)
                    view.isPressed = false
                    view.performClick()
                }
            }
            true
        }
    }

    private fun buildKeyboard() {
        keyboardContainer.removeAllViews()
        val rows = listOf(
            listOf("ESC", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12", "PRINTSCREEN"),
            listOf("`", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "=", "BACKSPACE"),
            listOf("TAB", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "[", "]", "\\"),
            listOf("CAPSLOCK", "A", "S", "D", "F", "G", "H", "J", "K", "L", ";", "'", "ENTER"),
            listOf("LSHIFT", "Z", "X", "C", "V", "B", "N", "M", ",", ".", "/", "RSHIFT"),
            listOf("LCTRL", "WIN", "LALT", "SPACE", "RALT", "RCTRL", "LEFT", "DOWN", "UP", "RIGHT", "HOME", "END", "PAGEUP", "PAGEDOWN", "INSERT", "DELETE")
        )
        rows.forEach { keys ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            keys.forEach { key ->
                val button = Button(this).apply {
                    text = when (key) {
                        "BACKSPACE" -> "⌫"; "SPACE" -> "空格"; "PRINTSCREEN" -> "PrtSc"
                        "PAGEUP" -> "PgUp"; "PAGEDOWN" -> "PgDn"; "CAPSLOCK" -> "Caps"
                        "LSHIFT", "RSHIFT" -> "Shift"
                        "LCTRL", "RCTRL" -> "Ctrl"
                        "LALT", "RALT" -> "Alt"
                        else -> key
                    }
                    setAllCaps(false)
                    textSize = 9f
                    minWidth = dp(if (key in listOf("SPACE", "BACKSPACE", "ENTER", "SHIFT", "LSHIFT", "RSHIFT", "CTRL", "LCTRL", "RCTRL", "PRINTSCREEN")) 56 else 38)
                    minHeight = dp(38)
                    setPadding(dp(3), 0, dp(3), 0)
                }
                bindKeyboardKey(button, key)
                row.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply {
                    marginEnd = dp(2); bottomMargin = dp(2)
                })
            }
            keyboardContainer.addView(row)
        }
    }

    private fun sendKeyState(key: String, down: Boolean) {
        sendJson(JSONObject().put("type", "key").put("action", if (down) "down" else "up").put("key", key))
    }

    private fun sendGamepadButton(name: String, pressed: Boolean) {
        sendJson(JSONObject().put("type", "gamepad").put("action", "button").put("button", name).put("pressed", pressed))
    }

    private fun sendTrigger(name: String, value: Float) {
        sendJson(JSONObject().put("type", "gamepad").put("action", "trigger").put("trigger", name).put("value", value))
    }

    private fun sendHotkey(vararg keys: String) {
        val array = JSONArray(); keys.forEach { array.put(it) }
        sendJson(JSONObject().put("type", "key").put("action", "hotkey").put("keys", array))
    }

    private fun sendKey(key: String) {
        sendJson(JSONObject().put("type", "key").put("action", "press").put("key", key))
    }

    private fun sendPhoneClipboard() {
        if (!ws.isConnected()) { toast("请先连接电脑"); return }
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = manager.primaryClip
        if (clip == null || clip.itemCount == 0) { toast("手机剪贴板为空"); return }
        val text = clip.getItemAt(0).coerceToText(this).toString()
        if (text.isBlank()) { toast("手机剪贴板没有文本内容"); return }
        sendJson(JSONObject().put("type", "key").put("action", "text").put("text", text.take(16000)))
        toast("手机剪贴板文本已发送到电脑")
    }

    private fun sendText() {
        val value = etText.text.toString()
        if (value.isBlank()) return
        if (!ws.isConnected()) { toast("请先连接电脑"); return }
        sendJson(JSONObject().put("type", "key").put("action", "text").put("text", value))
        etText.text.clear()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etText.windowToken, 0)
    }

    private fun sendJson(json: JSONObject) { ws.sendJson(json) }

    private fun sendStateReset() {
        sendJson(JSONObject().put("type", "state_reset"))
        if (::joystickView.isInitialized) joystickView.resetAll()
        directTouchDown = false
        directScrollMode = false
        directPendingScrollPx = 0f
    }

    private fun updateFileButtons() {
        if (!::tvFileStatus.isInitialized) return
        findViewById<Button>(R.id.btnPauseFile).isEnabled = activeFileId >= 0
        findViewById<Button>(R.id.btnPauseFile).text = if (filePaused) "继续传输" else "暂停传输"
        findViewById<Button>(R.id.btnCancelFile).isEnabled = activeFileId >= 0
        findViewById<Button>(R.id.btnRetryFile).isEnabled = activeFileId < 0 && (lastUploadUri != null || ws.isConnected())
    }

    private fun toggleFilePause() {
        if (activeFileId < 0) return
        filePaused = !filePaused
        ws.pauseFile(activeFileId, filePaused, activeFileDirection)
        updateFileButtons()
        tvFileStatus.text = if (filePaused) "传输已暂停" else "继续传输中…"
    }

    private fun cancelCurrentTransfer() {
        if (activeFileId < 0) return
        ws.cancelFile(activeFileId)
        activeFileId = -1L; activeFileDirection = ""; filePaused = false
        pendingUploadId = -1L; pendingUploadUri = null
        updateFileButtons()
        fileProgress.visibility = View.GONE
        tvFileStatus.text = "传输已取消"
    }

    private fun retryLastTransfer() {
        val uri = lastUploadUri
        if (uri == null) {
            requestPcFilePicker()
            return
        }
        if (!ws.isConnected()) { toast("等待重新连接电脑后再重试"); return }
        val id = ThreadLocalRandom.current().nextInt(1, Int.MAX_VALUE).toLong()
        pendingUploadUri = uri; pendingUploadId = id; pendingUploadSize = lastUploadSize
        activeFileId = id; activeFileDirection = "to_server"; filePaused = false
        updateFileButtons()
        tvFileStatus.text = "重新发送：$lastUploadName"
        fileProgress.visibility = View.VISIBLE; fileProgress.progress = 0
        ws.beginUpload(id, lastUploadName, lastUploadSize)
    }

    private fun chooseUploadFile() {
        if (!ws.isConnected()) { toast("请先连接电脑"); return }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }
        startActivityForResult(intent, REQUEST_UPLOAD)
    }

    private fun requestPcFilePicker() {
        if (!ws.isConnected()) toast("请先连接电脑") else {
            ws.requestPcFilePicker()
            toast("请在 Windows 电脑弹出的文件选择框中选择文件")
        }
    }

    private fun chooseDownloadTarget(fileId: Long, name: String, size: Long, sha256: String) {
        pendingDownloadId = fileId
        pendingDownloadName = name
        pendingDownloadSize = size
        pendingDownloadHash = sha256
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(intent, REQUEST_DOWNLOAD)
    }

    private fun createScreenshot() {
        val snapshot = screenView.snapshotBitmap()
        if (snapshot == null) { toast("目前还没有可保存的远程画面"); return }
        pendingScreenshot = snapshot
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "image/png"
            putExtra(Intent.EXTRA_TITLE, "LAN-Touchpad-${System.currentTimeMillis()}.png")
        }
        startActivityForResult(intent, REQUEST_SCREENSHOT)
    }

    private fun saveScreenshot(uri: Uri) {
        val image = pendingScreenshot ?: return
        try {
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                if (!image.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IllegalStateException("PNG 编码失败")
            } ?: throw IllegalStateException("无法创建截图文件")
            toast("截图已保存")
        } catch (e: Exception) {
            toast("截图保存失败：${e.message}")
        } finally {
            image.recycle(); pendingScreenshot = null
        }
    }

    private fun cycleScale() {
        currentScaleMode = when (currentScaleMode) {
            ScreenView.ScaleMode.FIT -> ScreenView.ScaleMode.FILL
            ScreenView.ScaleMode.FILL -> ScreenView.ScaleMode.STRETCH
            ScreenView.ScaleMode.STRETCH -> ScreenView.ScaleMode.FIT
        }
        screenView.setScaleMode(currentScaleMode)
        toast("画面缩放：$currentScaleMode")
    }

    private fun showKeyboard(show: Boolean) {
        keyboardPanel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            findViewById<View>(R.id.leftMenuPanel).visibility = View.GONE
            findViewById<View>(R.id.rightMenuPanel).visibility = View.GONE
        }
    }

    private fun hideMenus() {
        findViewById<View>(R.id.leftMenuPanel).visibility = View.GONE
        findViewById<View>(R.id.rightMenuPanel).visibility = View.GONE
    }

    private fun toggleVisibility(viewId: Int) {
        val target = findViewById<View>(viewId)
        target.visibility = if (target.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    @Deprecated("Retained for minSdk 24 compatibility and SAF integration")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SCREENSHOT) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) saveScreenshot(data.data!!)
            else { pendingScreenshot?.recycle(); pendingScreenshot = null }
            return
        }
        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            if (requestCode == REQUEST_DOWNLOAD && pendingDownloadId >= 0) {
                ws.cancelFile(pendingDownloadId); pendingDownloadId = -1L; pendingDownloadHash = ""
                activeFileId = -1L; activeFileDirection = ""; filePaused = false; updateFileButtons()
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
                pendingUploadUri = uri; pendingUploadId = id; pendingUploadSize = size
                lastUploadUri = uri; lastUploadName = name; lastUploadSize = size
                activeFileId = id; activeFileDirection = "to_server"; filePaused = false
                updateFileButtons()
                tvFileStatus.text = "等待电脑接收：$name"
                fileProgress.visibility = View.VISIBLE; fileProgress.progress = 0
                ws.beginUpload(id, name, size)
            }
            REQUEST_DOWNLOAD -> {
                val output = try { contentResolver.openOutputStream(uri, "w") } catch (_: Exception) { null }
                if (output == null) {
                    if (pendingDownloadId >= 0) ws.cancelFile(pendingDownloadId)
                    toast("无法创建目标文件")
                    activeFileId = -1L; activeFileDirection = ""; updateFileButtons()
                } else if (pendingDownloadId < 0 || !ws.isConnected()) {
                    try { output.close() } catch (_: Exception) { }
                    toast("连接已中断，本次接收已取消")
                    activeFileId = -1L; activeFileDirection = ""; updateFileButtons()
                } else {
                    ws.attachDownloadSink(pendingDownloadId, output, pendingDownloadSize, pendingDownloadHash)
                    tvFileStatus.text = "正在接收：$pendingDownloadName"
                    fileProgress.visibility = View.VISIBLE; fileProgress.progress = 0
                }
                pendingDownloadId = -1L; pendingDownloadHash = ""
            }
        }
    }

    private fun queryFileInfo(uri: Uri): Pair<String, Long> {
        var name = "upload_file"; var size = -1L; var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0) name = cursor.getString(nameColumn) ?: name
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
            }
        } catch (_: Exception) { } finally { cursor?.close() }
        return name to size
    }

    override fun onConnectionChanged(connected: Boolean, detail: String) {
        runOnUiThread {
            tvConnection.text = if (connected) "● 在线" else "● 离线"
            tvConnection.setTextColor(if (connected) 0xFF55D6BE.toInt() else 0xFFF0B45A.toInt())
            findViewById<Button>(R.id.btnConnect).text = if (connected) "断开" else "连接/重连"
            if (connected) {
                seenFirstFrame = false
                tvScreenStatus.text = "已连接 · 协议协商中"
                updateFileButtons()
                ws.sendScreenControl(qualityValue, fpsValue, monitorValue)
            } else {
                seenFirstFrame = false
                tvScreenStatus.text = detail
                fileProgress.visibility = View.GONE
                if (pendingUploadId >= 0) {
                    pendingUploadId = -1L; pendingUploadUri = null
                }
                pendingDownloadId = -1L; pendingDownloadHash = ""
                activeFileId = -1L; activeFileDirection = ""; filePaused = false; updateFileButtons()
            }
        }
    }

    override fun onScreenFrame(jpeg: ByteArray) {
        if (::screenView.isInitialized) screenView.submitFrame(jpeg)
        if (!seenFirstFrame) {
            seenFirstFrame = true
            runOnUiThread { tvScreenStatus.text = "实时画面 · ${qualityLabel()} · $fpsValue FPS" }
        }
    }

    override fun onFileStart(fileId: Long, name: String, size: Long, direction: String, sha256: String) {
        if (direction == "to_client") runOnUiThread {
            activeFileId = fileId; activeFileDirection = direction; filePaused = false; updateFileButtons()
            tvFileStatus.text = "电脑发送：$name (${formatBytes(size)})"
            chooseDownloadTarget(fileId, name, size, sha256)
        }
    }

    override fun onFileReady(fileId: Long, direction: String) {
        if (direction == "to_server") runOnUiThread {
            if (fileId == pendingUploadId) {
                val uri = pendingUploadUri
                if (uri != null) {
                    tvFileStatus.text = "正在发送到电脑…"
                    ws.sendDocument(contentResolver, uri, fileId, pendingUploadSize)
                    pendingUploadUri = null; pendingUploadId = -1L
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
            activeFileId = -1L; activeFileDirection = ""; filePaused = false; updateFileButtons()
            tvFileStatus.text = "电脑文件已保存并通过完整性校验"
            toast("文件接收完成")
        }
    }

    override fun onFileComplete(fileId: Long, name: String, size: Long) {
        runOnUiThread {
            fileProgress.progress = 100
            activeFileId = -1L; activeFileDirection = ""; filePaused = false; updateFileButtons()
            tvFileStatus.text = "已发送到电脑：$name (${formatBytes(size)}) · 校验成功"
            toast("文件已发送到电脑")
        }
    }

    override fun onFileError(fileId: Long, message: String) {
        runOnUiThread {
            activeFileId = -1L; activeFileDirection = ""; filePaused = false; updateFileButtons()
            tvFileStatus.text = "文件错误：$message"
            fileProgress.visibility = View.GONE
            toast(message)
        }
    }

    override fun onServerMessage(type: String, payload: JSONObject) {
        when (type) {
            "hello" -> runOnUiThread { tvScreenStatus.text = "服务端可用 · 正在协商协议" }
            "hello_ack" -> {
                val items = payload.optJSONArray("monitors") ?: JSONArray()
                val labels = mutableListOf<String>()
                val indices = mutableListOf<Int>()
                for (i in 0 until items.length()) {
                    val monitor = items.optJSONObject(i) ?: continue
                    val index = monitor.optInt("index", i + 1)
                    val label = monitor.optString("name", "显示器 $index") +
                        " ${monitor.optInt("width")}×${monitor.optInt("height")}"
                    indices.add(index); labels.add(label)
                }
                runOnUiThread {
                    monitorIndices = if (indices.isEmpty()) mutableListOf(1) else indices
                    spMonitor.adapter = spinnerAdapter(if (labels.isEmpty()) listOf("主显示器") else labels)
                    val selected = monitorIndices.indexOf(payload.optInt("selected_monitor", 1)).coerceAtLeast(0)
                    spMonitor.setSelection(selected)
                    tvScreenStatus.text = "协议 v${payload.optInt("protocol_version", 1)} · 服务端已就绪"
                }
            }
            "monitors" -> {
                val items = payload.optJSONArray("items") ?: JSONArray()
                val labels = mutableListOf<String>(); val indices = mutableListOf<Int>()
                for (i in 0 until items.length()) {
                    val monitor = items.optJSONObject(i) ?: continue
                    val index = monitor.optInt("index", i + 1)
                    labels.add("显示器 $index · ${monitor.optInt("width")}×${monitor.optInt("height")}")
                    indices.add(index)
                }
                runOnUiThread {
                    monitorIndices = if (indices.isEmpty()) mutableListOf(1) else indices
                    spMonitor.adapter = spinnerAdapter(if (labels.isEmpty()) listOf("主显示器") else labels)
                }
            }
            "screen_stats" -> runOnUiThread {
                tvScreenStatus.text = "${payload.optInt("width")}×${payload.optInt("height")} · " +
                    "${payload.optDouble("fps_actual", 0.0)} FPS · 队列 ${payload.optInt("queue_depth")}"
            }
            "clipboard_text" -> {
                val text = payload.optString("text", "")
                runOnUiThread {
                    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    manager.setPrimaryClip(ClipData.newPlainText("Windows 剪贴板", text))
                    toast(if (text.isEmpty()) "电脑剪贴板没有文本" else "已复制电脑剪贴板文本到手机")
                }
            }
            "screen_ctrl_ack" -> runOnUiThread { tvScreenStatus.text = "画面设置已应用 · ${qualityLabel()} · $fpsValue FPS" }
            "error" -> runOnUiThread { toast(payload.optString("message", "服务端错误")) }
        }
    }

    private fun qualityLabel(): String = when (qualityValue) {
        "standard" -> "标清"; "hd" -> "高清"; "adaptive" -> "自适应"; else -> "省流"
    }

    private fun formatBytes(value: Long): String {
        if (value < 0) return "未知大小"
        if (value < 1024) return "$value B"
        if (value < 1024 * 1024) return "%.1f KB".format(value / 1024.0)
        if (value < 1024L * 1024L * 1024L) return "%.1f MB".format(value / (1024.0 * 1024.0))
        return "%.2f GB".format(value / (1024.0 * 1024.0 * 1024.0))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        if (::ws.isInitialized && ws.isConnected()) sendStateReset()
        super.onPause()
    }

    override fun onDestroy() {
        if (::ws.isInitialized) ws.close()
        pendingScreenshot?.recycle()
        pendingScreenshot = null
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_UPLOAD = 1001
        private const val REQUEST_DOWNLOAD = 1002
        private const val REQUEST_SCREENSHOT = 1003
    }
}
