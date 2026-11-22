package com.lanremote.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WebSocketManager(private val callback: Callback) {

    interface Callback {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onLatency(rttMs: Long)
        fun onScreenFrame(jpegBytes: ByteArray)
        fun onFileStart(fileId: Int, fileName: String, fileSize: Long, totalChunks: Int)
        fun onFileChunk(fileId: Int, chunkIndex: Int, payload: ByteArray)
        fun onFileEnd(fileId: Int, fileName: String)
        fun onFileListReceived(files: List<Pair<String, Long>>)
        fun onFileProgress(label: String, percent: Int)
        fun onFileTransferFinished(message: String)
    }

    companion object {
        const val HEADER_SCREEN: Byte = 0x01
        const val HEADER_FILE: Byte = 0x02
        const val CHUNK_SIZE = 65536
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val fileExecutor = Executors.newSingleThreadExecutor()
    private val pingScheduler = Executors.newSingleThreadScheduledExecutor()
    private val fileIdSeq = AtomicInteger(1000)

    @Volatile
    private var webSocket: WebSocket? = null
    private var pingFuture: ScheduledFuture<*>? = null

    val isConnected: Boolean
        get() = webSocket != null

    fun connect(ip: String, port: Int = 8765) {
        disconnect()
        val url = "ws://$ip:$port"
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                startPingLoop()
                callback.onConnected()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleTextMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                val arr = bytes.toByteArray()
                if (arr.isEmpty()) return
                when (arr[0]) {
                    HEADER_SCREEN -> {
                        if (arr.size > 1) {
                            callback.onScreenFrame(arr.copyOfRange(1, arr.size))
                        }
                    }
                    HEADER_FILE -> {
                        if (arr.size >= 9) {
                            val bb = ByteBuffer.wrap(arr, 1, 8).order(ByteOrder.BIG_ENDIAN)
                            val fileId = bb.int
                            val chunkIdx = bb.int
                            val payload = arr.copyOfRange(9, arr.size)
                            callback.onFileChunk(fileId, chunkIdx, payload)
                        }
                    }
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                cleanupSocket(reason.ifEmpty { "连接已关闭" })
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                cleanupSocket(t.message ?: "连接失败")
            }
        })
    }

    fun disconnect() {
        pingFuture?.cancel(true)
        pingFuture = null
        webSocket?.close(1000, "User disconnect")
        webSocket = null
    }

    private fun cleanupSocket(reason: String) {
        pingFuture?.cancel(true)
        pingFuture = null
        webSocket = null
        callback.onDisconnected(reason)
    }

    private fun startPingLoop() {
        pingFuture?.cancel(true)
        pingFuture = pingScheduler.scheduleAtFixedRate({
            val json = JSONObject()
                .put("type", "ping")
                .put("ts", System.currentTimeMillis())
            sendJson(json)
        }, 1, 3, TimeUnit.SECONDS)
    }

    private fun handleTextMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "pong" -> {
                    val ts = json.optLong("ts", 0L)
                    if (ts > 0) {
                        callback.onLatency(System.currentTimeMillis() - ts)
                    }
                }
                "file_start" -> {
                    callback.onFileStart(
                        json.optInt("file_id"),
                        json.optString("name", "unknown.bin"),
                        json.optLong("size", 0L),
                        json.optInt("total_chunks", 1)
                    )
                }
                "file_end" -> {
                    callback.onFileEnd(
                        json.optInt("file_id"),
                        json.optString("name", "unknown.bin")
                    )
                }
                "file_list" -> {
                    val arr = json.optJSONArray("files") ?: JSONArray()
                    val list = mutableListOf<Pair<String, Long>>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        list.add(obj.optString("name") to obj.optLong("size"))
                    }
                    callback.onFileListReceived(list)
                }
                "file_ack" -> {
                    val status = json.optString("status")
                    val path = json.optString("saved_path", "")
                    val msg = if (status == "ok") "电脑端已保存: $path" else "传输失败: ${json.optString("message")}"
                    callback.onFileTransferFinished(msg)
                }
            }
        } catch (_: Exception) {
        }
    }

    fun sendJson(json: JSONObject) {
        webSocket?.send(json.toString())
    }

    fun sendScreenCtrl(quality: String, fps: Int) {
        sendJson(
            JSONObject()
                .put("type", "screen_ctrl")
                .put("quality", quality)
                .put("fps", fps)
        )
    }

    fun sendMouse(action: String, dx: Float = 0f, dy: Float = 0f) {
        val obj = JSONObject()
            .put("type", "mouse")
            .put("action", action)
        if (action == "move") {
            obj.put("dx", dx.toDouble()).put("dy", dy.toDouble())
        } else if (action == "scroll") {
            obj.put("dy", dy.toInt())
        }
        sendJson(obj)
    }

    fun sendText(text: String) {
        sendJson(JSONObject().put("type", "key").put("action", "text").put("text", text))
    }

    fun sendKeyPress(key: String) {
        sendJson(JSONObject().put("type", "key").put("action", "press").put("key", key))
    }

    fun sendHotkey(vararg keys: String) {
        val arr = JSONArray()
        keys.forEach { arr.put(it) }
        sendJson(JSONObject().put("type", "key").put("action", "hotkey").put("keys", arr))
    }

    fun sendGamepadStick(side: String, x: Float, y: Float) {
        sendJson(
            JSONObject()
                .put("type", "gamepad")
                .put("action", "stick")
                .put("side", side)
                .put("x", x.toDouble())
                .put("y", y.toDouble())
        )
    }

    fun sendGamepadButton(button: String, pressed: Boolean) {
        sendJson(
            JSONObject()
                .put("type", "gamepad")
                .put("action", "button")
                .put("button", button)
                .put("pressed", pressed)
        )
    }

    fun sendGamepadTrigger(side: String, value: Float) {
        sendJson(
            JSONObject()
                .put("type", "gamepad")
                .put("action", "trigger")
                .put("side", side)
                .put("value", value.toDouble())
        )
    }

    fun requestPcFileList() {
        sendJson(JSONObject().put("type", "file_list_req"))
    }

    fun requestPcFileDownload(fileName: String) {
        sendJson(JSONObject().put("type", "file_download_req").put("name", fileName))
    }

    fun sendFileToPc(context: Context, uri: Uri) {
        val ws = webSocket ?: return
        fileExecutor.execute {
            try {
                var fileName = "upload_${System.currentTimeMillis()}.bin"
                var fileSize = 0L
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
                        if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
                    }
                }

                val fileId = fileIdSeq.incrementAndGet()
                val totalChunks = maxOf(1, ((fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt())

                sendJson(
                    JSONObject()
                        .put("type", "file_start")
                        .put("file_id", fileId)
                        .put("name", fileName)
                        .put("size", fileSize)
                        .put("total_chunks", totalChunks)
                )

                val buffer = ByteArray(CHUNK_SIZE)
                var chunkIndex = 0
                var sentBytes = 0L

                context.contentResolver.openInputStream(uri)?.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break

                        while (ws.queueSize() > 1024 * 1024) {
                            Thread.sleep(15)
                        }

                        val packet = ByteBuffer.allocate(9 + read)
                            .order(ByteOrder.BIG_ENDIAN)
                            .put(HEADER_FILE)
                            .putInt(fileId)
                            .putInt(chunkIndex)
                            .put(buffer, 0, read)
                            .array()

                        ws.send(ByteString.of(packet, 0, packet.size))
                        chunkIndex++
                        sentBytes += read

                        val pct = if (fileSize > 0) ((sentBytes * 100) / fileSize).toInt().coerceIn(0, 100) else 100
                        callback.onFileProgress("上传中: $fileName ($pct%)", pct)
                    }
                }

                sendJson(
                    JSONObject()
                        .put("type", "file_end")
                        .put("file_id", fileId)
                        .put("name", fileName)
                )
            } catch (e: Exception) {
                callback.onFileTransferFinished("上传失败: ${e.message}")
            }
        }
    }

    fun release() {
        disconnect()
        pingScheduler.shutdownNow()
        fileExecutor.shutdownNow()
    }
}
