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
