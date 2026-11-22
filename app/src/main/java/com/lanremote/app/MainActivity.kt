package com.lanremote.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity(), WebSocketManager.Callback {

    private lateinit var wsManager: WebSocketManager
    private lateinit var screenView: ScreenView
    private lateinit var touchpadView: TouchpadView
    private lateinit var gamepadOverlay: RelativeLayout

    private lateinit var topControlBar: LinearLayout
    private lateinit var btnExpandBar: TextView
    private lateinit var streamPresetPanel: LinearLayout
    private lateinit var shortcutFolderPanel: LinearLayout
    private lateinit var keyboardInputBar: LinearLayout
    private lateinit var fileProgressBox: LinearLayout

    private lateinit var tvStatus: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnQuality: Button
    private lateinit var btnFps: Button
    private lateinit var btnMode: Button
    private lateinit var etTextInput: EditText
    private lateinit var tvFileProgress: TextView
    private lateinit var pbFileProgress: ProgressBar

    private lateinit var btnQualitySaver: Button
    private lateinit var btnQualityStandard: Button
    private lateinit var btnQualityHd: Button
    private lateinit var btnFps15: Button
    private lateinit var btnFps30: Button
    private lateinit var btnFps60: Button

    private var currentQuality = "standard"
    private var currentFps = 15
    private var isGamepadMode = false

    private data class IncomingFile(
        val file: File,
        val raf: RandomAccessFile,
        val totalSize: Long,
        var receivedBytes: Long = 0L
    )

    private val incomingFiles = ConcurrentHashMap<Int, IncomingFile>()

    private val pickFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                wsManager.sendFileToPc(this, uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        setContentView(R.layout.activity_main)

        wsManager = WebSocketManager(this)
        bindViews()
        setupTopControlBar()
        setupStreamPresetPanel()
        setupTouchpad()
        setupGamepad()
        setupShortcutFolder()
        setupKeyboardBar()
    }

    @Suppress("DEPRECATION")
    private fun enableImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }

    private fun bindViews() {
        screenView = findViewById(R.id.screenView)
        touchpadView = findViewById(R.id.touchpadView)
        gamepadOverlay = findViewById(R.id.gamepadOverlay)

        topControlBar = findViewById(R.id.topControlBar)
        btnExpandBar = findViewById(R.id.btnExpandBar)
        streamPresetPanel = findViewById(R.id.streamPresetPanel)
        shortcutFolderPanel = findViewById(R.id.shortcutFolderPanel)
        keyboardInputBar = findViewById(R.id.keyboardInputBar)
        fileProgressBox = findViewById(R.id.fileProgressBox)

        tvStatus = findViewById(R.id.tvStatus)
        btnConnect = findViewById(R.id.btnConnect)
        btnQuality = findViewById(R.id.btnQuality)
        btnFps = findViewById(R.id.btnFps)
        btnMode = findViewById(R.id.btnMode)
        etTextInput = findViewById(R.id.etTextInput)
        tvFileProgress = findViewById(R.id.tvFileProgress)
        pbFileProgress = findViewById(R.id.pbFileProgress)

        btnQualitySaver = findViewById(R.id.btnQualitySaver)
        btnQualityStandard = findViewById(R.id.btnQualityStandard)
        btnQualityHd = findViewById(R.id.btnQualityHd)
        btnFps15 = findViewById(R.id.btnFps15)
        btnFps30 = findViewById(R.id.btnFps30)
        btnFps60 = findViewById(R.id.btnFps60)
    }

    private fun setupTopControlBar() {
        btnConnect.setOnClickListener {
            if (wsManager.isConnected) {
                wsManager.disconnect()
            } else {
                showConnectDialog()
            }
        }

        val togglePresetPanel = View.OnClickListener {
            streamPresetPanel.visibility =
                if (streamPresetPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        btnQuality.setOnClickListener(togglePresetPanel)
        btnFps.setOnClickListener(togglePresetPanel)

        btnMode.setOnClickListener {
            isGamepadMode = !isGamepadMode
            gamepadOverlay.visibility = if (isGamepadMode) View.VISIBLE else View.GONE
            touchpadView.visibility = if (isGamepadMode) View.GONE else View.VISIBLE
            btnMode.text = if (isGamepadMode) "手柄中" else "触控板"
            btnMode.setTextColor(if (isGamepadMode) Color.parseColor("#00E5FF") else Color.WHITE)
        }

        findViewById<Button>(R.id.btnKeyboard).setOnClickListener {
            val showing = keyboardInputBar.visibility == View.VISIBLE
            keyboardInputBar.visibility = if (showing) View.GONE else View.VISIBLE
            if (!showing) {
                etTextInput.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(etTextInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        findViewById<Button>(R.id.btnFile).setOnClickListener {
            showFileActionDialog()
        }

        findViewById<Button>(R.id.btnCollapseBar).setOnClickListener {
            topControlBar.visibility = View.GONE
            streamPresetPanel.visibility = View.GONE
            btnExpandBar.visibility = View.VISIBLE
        }

        btnExpandBar.setOnClickListener {
            btnExpandBar.visibility = View.GONE
            topControlBar.visibility = View.VISIBLE
        }
    }

    private fun setupStreamPresetPanel() {
        btnQualitySaver.setOnClickListener { applyStreamCtrl("saver", currentFps) }
        btnQualityStandard.setOnClickListener { applyStreamCtrl("standard", currentFps) }
        btnQualityHd.setOnClickListener { applyStreamCtrl("hd", currentFps) }

        btnFps15.setOnClickListener { applyStreamCtrl(currentQuality, 15) }
        btnFps30.setOnClickListener { applyStreamCtrl(currentQuality, 30) }
        btnFps60.setOnClickListener { applyStreamCtrl(currentQuality, 60) }
        refreshPresetHighlights()
    }

    private fun applyStreamCtrl(quality: String, fps: Int) {
        currentQuality = quality
        currentFps = fps
        refreshPresetHighlights()
        wsManager.sendScreenCtrl(quality, fps)
        streamPresetPanel.visibility = View.GONE
    }

    private fun refreshPresetHighlights() {
        val activeColor = Color.parseColor("#007A8C")
        val normalColor = Color.parseColor("#262C3A")

        btnQualitySaver.setBackgroundColor(if (currentQuality == "saver") activeColor else normalColor)
        btnQualityStandard.setBackgroundColor(if (currentQuality == "standard") activeColor else normalColor)
        btnQualityHd.setBackgroundColor(if (currentQuality == "hd") activeColor else normalColor)

        btnFps15.setBackgroundColor(if (currentFps == 15) activeColor else normalColor)
        btnFps30.setBackgroundColor(if (currentFps == 30) activeColor else normalColor)
        btnFps60.setBackgroundColor(if (currentFps == 60) activeColor else normalColor)

        val qLabel = when (currentQuality) {
            "saver" -> "省流"
            "hd" -> "高清"
            else -> "标清"
        }
        btnQuality.text = "画质:$qLabel"
        btnFps.text = "$currentFps FPS"
    }

    private fun setupTouchpad() {
        touchpadView.listener = object : TouchpadView.Listener {
            override fun onMove(dx: Float, dy: Float) = wsManager.sendMouse("move", dx, dy)
            override fun onClick() = wsManager.sendMouse("click")
            override fun onDoubleClick() = wsManager.sendMouse("double_click")
            override fun onRightClick() = wsManager.sendMouse("right_click")
            override fun onDragStart() = wsManager.sendMouse("left_down")
            override fun onDragEnd() = wsManager.sendMouse("left_up")
            override fun onScroll(dy: Int) = wsManager.sendMouse("scroll", 0f, dy.toFloat())
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGamepad() {
        findViewById<JoystickView>(R.id.leftJoystick).onMoveListener = { x, y ->
            wsManager.sendGamepadStick("left", x, y)
        }
        findViewById<JoystickView>(R.id.rightJoystick).onMoveListener = { x, y ->
            wsManager.sendGamepadStick("right", x, y)
        }

        val buttonIds = listOf(
            R.id.btnA to "A",
            R.id.btnB to "B",
            R.id.btnX to "X",
            R.id.btnY to "Y",
            R.id.btnLB to "LB",
            R.id.btnRB to "RB",
            R.id.btnBack to "BACK",
            R.id.btnStart to "START"
        )
        for ((id, code) in buttonIds) {
            val btn = findViewById<Button>(id)
            btn.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.alpha = 0.55f
                        wsManager.sendGamepadButton(code, true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 1.0f
                        wsManager.sendGamepadButton(code, false)
                    }
                }
                true
            }
        }

        val triggerIds = listOf(R.id.btnLT to "LT", R.id.btnRT to "RT")
        for ((id, side) in triggerIds) {
            val btn = findViewById<Button>(id)
            btn.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.alpha = 0.55f
                        wsManager.sendGamepadTrigger(side, 1.0f)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 1.0f
                        wsManager.sendGamepadTrigger(side, 0.0f)
                    }
                }
                true
            }
        }
    }

    private fun setupShortcutFolder() {
        findViewById<Button>(R.id.btnShortcutToggle).setOnClickListener {
            shortcutFolderPanel.visibility =
                if (shortcutFolderPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.scWinD).setOnClickListener { wsManager.sendHotkey("win", "d") }
        findViewById<Button>(R.id.scAltTab).setOnClickListener { wsManager.sendHotkey("alt", "tab") }
        findViewById<Button>(R.id.scCopy).setOnClickListener { wsManager.sendHotkey("ctrl", "c") }
        findViewById<Button>(R.id.scPaste).setOnClickListener { wsManager.sendHotkey("ctrl", "v") }
        findViewById<Button>(R.id.scUndo).setOnClickListener { wsManager.sendHotkey("ctrl", "z") }
        findViewById<Button>(R.id.scEsc).setOnClickListener { wsManager.sendKeyPress("esc") }
        findViewById<Button>(R.id.scTaskMgr).setOnClickListener { wsManager.sendHotkey("ctrl", "shift", "esc") }
        findViewById<Button>(R.id.scWinKey).setOnClickListener { wsManager.sendKeyPress("win") }
    }

    private fun setupKeyboardBar() {
        val sendCurrentText = {
            val txt = etTextInput.text.toString()
            if (txt.isNotEmpty()) {
                wsManager.sendText(txt)
                etTextInput.setText("")
            }
        }
        findViewById<Button>(R.id.btnSendText).setOnClickListener { sendCurrentText() }
        etTextInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentText()
                true
            } else {
                false
            }
        }
        findViewById<Button>(R.id.btnSendBackspace).setOnClickListener { wsManager.sendKeyPress("backspace") }
        findViewById<Button>(R.id.btnSendEnter).setOnClickListener { wsManager.sendKeyPress("enter") }
        findViewById<Button>(R.id.btnCloseKeyboardBar).setOnClickListener {
            keyboardInputBar.visibility = View.GONE
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etTextInput.windowToken, 0)
            enableImmersiveMode()
        }
    }

    private fun showConnectDialog() {
        val prefs = getSharedPreferences("lan_remote", Context.MODE_PRIVATE)
        val savedIp = prefs.getString("last_ip", "192.168.1.100") ?: "192.168.1.100"

        val input = EditText(this).apply {
            setText(savedIp)
            hint = "例如 192.168.1.100"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("连接局域网电脑 (端口 8765)")
            .setView(input)
            .setPositiveButton("连接") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) {
                    prefs.edit().putString("last_ip", ip).apply()
                    tvStatus.text = "● 连接中..."
                    tvStatus.setTextColor(Color.parseColor("#FFD54F"))
                    wsManager.connect(ip, 8765)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showFileActionDialog() {
        if (!wsManager.isConnected) {
            Toast.makeText(this, "请先连接电脑", Toast.LENGTH_SHORT).show()
            return
        }
        val items = arrayOf("发送文件到电脑 (上传)", "从电脑 shared_files 目录下载")
        AlertDialog.Builder(this)
            .setTitle("局域网双向文件传输")
            .setItems(items) { _, which ->
                if (which == 0) {
                    pickFileLauncher.launch(arrayOf("*/*"))
                } else {
                    wsManager.requestPcFileList()
                }
            }
            .show()
    }

    override fun onConnected() {
        runOnUiThread {
            tvStatus.text = "● 已连接"
            tvStatus.setTextColor(Color.parseColor("#66BB6A"))
            btnConnect.text = "断开"
            wsManager.sendScreenCtrl(currentQuality, currentFps)
        }
    }

    override fun onDisconnected(reason: String) {
        runOnUiThread {
            tvStatus.text = "● 未连接"
            tvStatus.setTextColor(Color.parseColor("#FF6B6B"))
            btnConnect.text = "连接"
            screenView.clearScreen()
            Toast.makeText(this, "已断开: $reason", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onLatency(rttMs: Long) {
        runOnUiThread {
            tvStatus.text = "● ${rttMs}ms"
            tvStatus.setTextColor(
                if (rttMs < 60) Color.parseColor("#66BB6A") else Color.parseColor("#FFD54F")
            )
        }
    }

    override fun onScreenFrame(jpegBytes: ByteArray) {
        screenView.updateFrame(jpegBytes)
    }

    override fun onFileStart(fileId: Int, fileName: String, fileSize: Long, totalChunks: Int) {
        try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, File(fileName).name)
            val raf = RandomAccessFile(outFile, "rw")
            raf.setLength(0)
            incomingFiles[fileId] = IncomingFile(outFile, raf, fileSize)
            onFileProgress("下载中: $fileName (0%)", 0)
        } catch (e: Exception) {
            onFileTransferFinished("创建文件失败: ${e.message}")
        }
    }

    override fun onFileChunk(fileId: Int, chunkIndex: Int, payload: ByteArray) {
        val session = incomingFiles[fileId] ?: return
        try {
            session.raf.seek(chunkIndex.toLong() * WebSocketManager.CHUNK_SIZE)
            session.raf.write(payload)
            session.receivedBytes += payload.size
            val pct = if (session.totalSize > 0) {
                ((session.receivedBytes * 100) / session.totalSize).toInt().coerceIn(0, 100)
            } else 100
            onFileProgress("下载中: ${session.file.name} ($pct%)", pct)
        } catch (_: Exception) {
        }
    }

    override fun onFileEnd(fileId: Int, fileName: String) {
        val session = incomingFiles.remove(fileId) ?: return
        try {
            session.raf.close()
            onFileTransferFinished("已下载至: ${session.file.absolutePath}")
        } catch (_: Exception) {
        }
    }

    override fun onFileListReceived(files: List<Pair<String, Long>>) {
        runOnUiThread {
            if (files.isEmpty()) {
                Toast.makeText(this, "电脑 shared_files 目录为空", Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            val labels = files.map { (name, size) ->
                val kb = maxOf(1L, size / 1024L)
                "$name (${kb} KB)"
            }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("选择要下载的电脑文件")
                .setItems(labels) { _, which ->
                    wsManager.requestPcFileDownload(files[which].first)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    override fun onFileProgress(label: String, percent: Int) {
        runOnUiThread {
            fileProgressBox.visibility = View.VISIBLE
            tvFileProgress.text = label
            pbFileProgress.progress = percent
        }
    }

    override fun onFileTransferFinished(message: String) {
        runOnUiThread {
            fileProgressBox.visibility = View.GONE
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        incomingFiles.values.forEach { runCatching { it.raf.close() } }
        incomingFiles.clear()
        wsManager.release()
    }
}
