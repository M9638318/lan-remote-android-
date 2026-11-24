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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** WebSocket transport for protocol v2, with v1-compatible server negotiation. */
class WebSocketManager(private val listener: Listener) : Closeable {
    interface Listener {
        fun onConnectionChanged(connected: Boolean, detail: String)
        fun onScreenFrame(jpeg: ByteArray)
        fun onFileStart(fileId: Long, name: String, size: Long, direction: String, sha256: String)
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
        .retryOnConnectionFailure(true)
        .build()

    private val uploadExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lan-touchpad-upload").apply { isDaemon = true }
    }
    private val downloadExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lan-touchpad-download").apply { isDaemon = true }
    }
    private val reconnectExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "lan-touchpad-reconnect").apply { isDaemon = true }
    }

    private data class DownloadState(
        val output: OutputStream,
        val digest: MessageDigest,
        val total: Long,
        val expectedHash: String,
        var received: Long = 0L,
        var nextIndex: Long = 0L,
        var failed: Boolean = false
    )

    private val downloads = ConcurrentHashMap<Long, DownloadState>()
    private val uploadCancelled = ConcurrentHashMap<Long, AtomicBoolean>()
    private val uploadPaused = ConcurrentHashMap<Long, AtomicBoolean>()
    private val generation = AtomicLong(0L)
    private val closed = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var desiredUrl: String? = null
    @Volatile private var autoReconnect = true
    @Volatile private var reconnectAttempt = 0
    @Volatile private var negotiatedProtocol = 1

    fun connect(url: String) {
        if (closed.get()) return
        desiredUrl = url
        autoReconnect = true
        reconnectAttempt = 0
        startConnectionAttempt()
    }

    private fun startConnectionAttempt() {
        val url = desiredUrl ?: return
        if (closed.get() || !autoReconnect) return
        val attempt = generation.incrementAndGet()
        connected = false
        socket?.cancel()
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: Exception) {
            listener.onConnectionChanged(false, "地址无效：${e.message ?: "请检查 IP 和端口"}")
            return
        }
        val newSocket = client.newWebSocket(request, object : WebSocketListener() {
            private fun current(): Boolean = !closed.get() && generation.get() == attempt

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!current()) {
                    webSocket.cancel()
                    return
                }
                socket = webSocket
                connected = true
                reconnectAttempt = 0
                negotiatedProtocol = 1
                webSocket.send(JSONObject()
                    .put("type", "client_hello")
                    .put("client", "LAN Touchpad Android")
                    .put("client_version", "2.0.0")
                    .put("protocol_version", PROTOCOL_VERSION)
                    .put("capabilities", org.json.JSONArray(listOf("absolute_touch", "relative_mouse", "gamepad", "files_sha256", "clipboard_manual")))
                    .toString())
                listener.onConnectionChanged(true, "已连接，正在协商协议")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!current()) return
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type", "")
                    when (type) {
                        "file_start" -> {
                            val id = json.optLong("file_id", -1L)
                            listener.onFileStart(id, json.optString("name", "received_file"),
                                json.optLong("size", -1L), json.optString("direction", ""), json.optString("sha256", ""))
                        }
                        "file_ready" -> listener.onFileReady(
                            json.optLong("file_id", -1L), json.optString("direction", ""))
                        "file_progress" -> listener.onFileProgress(
                            json.optLong("file_id", -1L), json.optLong("done", 0L), json.optLong("total", -1L))
                        "file_end" -> finishDownload(
                            json.optLong("file_id", -1L), json.optString("direction", ""),
                            json.optString("sha256", ""))
                        "file_complete" -> {
                            val id = json.optLong("file_id", -1L)
                            uploadCancelled.remove(id)
                            uploadPaused.remove(id)
                            listener.onFileComplete(id, json.optString("name", ""), json.optLong("size", 0L))
                        }
                        "file_error" -> {
                            val id = json.optLong("file_id", -1L)
                            uploadCancelled.remove(id)
                            uploadPaused.remove(id)
                            cleanupDownload(id)
                            listener.onFileError(id, json.optString("message", "文件传输错误"))
                        }
                        "hello_ack" -> {
                            negotiatedProtocol = json.optInt("protocol_version", 1)
                            listener.onServerMessage(type, json)
                        }
                        else -> listener.onServerMessage(type, json)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid server JSON", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (current()) handleBinary(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!current()) return
                connected = false
                listener.onConnectionChanged(false, "连接已关闭：$reason")
                cancelUploads()
                closeDownloads()
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!current()) return
                connected = false
                listener.onConnectionChanged(false, "连接失败：${t.message ?: "网络错误"}")
                cancelUploads()
                closeDownloads()
                scheduleReconnect()
            }
        })
        if (generation.get() == attempt) socket = newSocket else newSocket.cancel()
    }

    private fun scheduleReconnect() {
        if (closed.get() || !autoReconnect || desiredUrl == null) return
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(6)
        val delay = (1L shl (reconnectAttempt - 1)).coerceAtMost(20L)
        reconnectExecutor.schedule({
            if (!closed.get() && autoReconnect && !connected) startConnectionAttempt()
        }, delay, TimeUnit.SECONDS)
    }

    fun disconnect() {
        autoReconnect = false
        desiredUrl = null
        generation.incrementAndGet()
        connected = false
        sendStateResetBestEffort()
        socket?.close(1000, "user disconnected")
        socket = null
        cancelUploads()
        closeDownloads()
        listener.onConnectionChanged(false, "已主动断开")
    }

    fun isConnected(): Boolean = connected && !closed.get()

    fun sendJson(json: JSONObject): Boolean {
        if (!isConnected()) return false
        return socket?.send(json.toString()) == true
    }

    fun sendScreenControl(quality: String, fps: Int, monitor: Int = 1) {
        sendJson(JSONObject().put("type", "screen_ctrl").put("quality", quality)
            .put("fps", fps).put("monitor", monitor))
    }

    fun requestPcFilePicker() {
        sendJson(JSONObject().put("type", "file_request"))
    }

    fun beginUpload(fileId: Long, name: String, size: Long) {
        uploadCancelled[fileId] = AtomicBoolean(false)
        uploadPaused[fileId] = AtomicBoolean(false)
        sendJson(JSONObject()
            .put("type", "file_start")
            .put("file_id", fileId)
            .put("name", name)
            .put("size", size)
            .put("direction", "to_server")
            .put("chunk_size", CHUNK_SIZE))
    }

    /** Stream a SAF document; cancellation is checked between chunks and while waiting for backpressure. */
    fun sendDocument(resolver: ContentResolver, uri: Uri, fileId: Long, expectedSize: Long) {
        val cancellation = uploadCancelled.computeIfAbsent(fileId) { AtomicBoolean(false) }
        uploadExecutor.execute {
            var sent = 0L
            var index = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                val input: InputStream = resolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法打开所选文件")
                input.use { stream ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    while (!cancellation.get()) {
                        while (uploadPaused[fileId]?.get() == true && !cancellation.get()) Thread.sleep(40)
                        if (cancellation.get()) break
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        digest.update(buffer, 0, count)
                        val packet = ByteBuffer.allocate(9 + count).order(ByteOrder.BIG_ENDIAN)
                            .put(MAGIC_FILE.toByte()).putInt(fileId.toInt()).putInt(index.toInt())
                            .put(buffer, 0, count).array()
                        sendBinaryWithBackpressure(packet, cancellation)
                        sent += count
                        index++
                        listener.onFileProgress(fileId, sent, expectedSize)
                    }
                }
                if (cancellation.get()) throw InterruptedException("已取消传输")
                if (expectedSize >= 0 && sent != expectedSize) {
                    throw IllegalStateException("文件读取长度变化：$sent / $expectedSize")
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                sendJson(JSONObject().put("type", "file_end").put("file_id", fileId)
                    .put("direction", "to_server").put("size", sent).put("sha256", hash))
            } catch (e: Exception) {
                if (!cancellation.get()) {
                    sendJson(JSONObject().put("type", "file_cancel").put("file_id", fileId))
                    listener.onFileError(fileId, "发送失败：${e.message ?: "文件读取或网络错误"}")
                }
            } finally {
                uploadCancelled.remove(fileId)
                uploadPaused.remove(fileId)
            }
        }
    }

    /** Attach the SAF destination before acknowledging the PC's file_start. */
    fun attachDownloadSink(fileId: Long, output: OutputStream, expectedSize: Long, expectedHash: String = "") {
        downloadExecutor.execute {
            downloads[fileId] = DownloadState(output, MessageDigest.getInstance("SHA-256"), expectedSize, expectedHash)
            sendJson(JSONObject().put("type", "file_ready").put("file_id", fileId).put("direction", "to_client"))
        }
    }

    fun pauseFile(fileId: Long, paused: Boolean, direction: String) {
        if (direction == "to_server") {
            uploadPaused.computeIfAbsent(fileId) { AtomicBoolean(false) }.set(paused)
        } else {
            sendJson(JSONObject().put("type", if (paused) "file_pause" else "file_resume").put("file_id", fileId))
        }
    }

    fun cancelFile(fileId: Long) {
        uploadCancelled[fileId]?.set(true)
        uploadPaused[fileId]?.set(false)
        sendJson(JSONObject().put("type", "file_cancel").put("file_id", fileId))
        cleanupDownload(fileId)
    }

    private fun sendBinaryWithBackpressure(packet: ByteArray, cancellation: AtomicBoolean) {
        val ws = socket ?: throw IllegalStateException("WebSocket 未连接")
        while (!closed.get() && !cancellation.get() && connected && ws.queueSize() > MAX_QUEUED_BYTES) {
            Thread.sleep(8)
        }
        if (closed.get() || cancellation.get() || !connected || !ws.send(ByteString.of(*packet))) {
            throw IllegalStateException("传输已取消或 WebSocket 发送队列已关闭")
        }
    }

    private fun handleBinary(packet: ByteArray) {
        if (packet.isEmpty()) return
        when (packet[0].toInt() and 0xFF) {
            MAGIC_SCREEN -> if (packet.size > 1) listener.onScreenFrame(packet.copyOfRange(1, packet.size))
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
                downloadExecutor.execute {
                    val state = downloads[fileId]
                    if (state == null || state.failed) return@execute
                    try {
                        if (index != state.nextIndex) throw IllegalStateException("文件块序号错误：$index / ${state.nextIndex}")
                        if (state.total >= 0 && state.received + chunk.size > state.total) {
                            throw IllegalStateException("文件数据超过声明长度")
                        }
                        state.output.write(chunk)
                        state.digest.update(chunk)
                        state.received += chunk.size
                        state.nextIndex++
                        listener.onFileProgress(fileId, state.received, state.total)
                    } catch (e: Exception) {
                        state.failed = true
                        try { state.output.close() } catch (_: Exception) { }
                        downloads.remove(fileId)
                        listener.onFileError(fileId, e.message ?: "写入文件失败")
                        sendJson(JSONObject().put("type", "file_cancel").put("file_id", fileId))
                    }
                }
            }
        }
    }

    private fun finishDownload(fileId: Long, direction: String, hashFromServer: String) {
        if (direction != "to_client") {
            listener.onFileEnd(fileId, direction)
            return
        }
        downloadExecutor.execute {
            val state = downloads.remove(fileId)
            if (state == null) {
                listener.onFileError(fileId, "找不到文件保存目标")
                return@execute
            }
            try {
                state.output.flush()
                state.output.close()
                if (state.total >= 0 && state.received != state.total) {
                    throw IllegalStateException("文件长度不一致：收到 ${state.received} / ${state.total}")
                }
                val actualHash = state.digest.digest().joinToString("") { "%02x".format(it) }
                val expectedHash = if (hashFromServer.isNotBlank()) hashFromServer else state.expectedHash
                if (expectedHash.isNotBlank() && !actualHash.equals(expectedHash, ignoreCase = true)) {
                    throw IllegalStateException("SHA-256 校验失败，文件可能损坏")
                }
                listener.onFileEnd(fileId, direction)
            } catch (e: Exception) {
                listener.onFileError(fileId, "保存或校验文件失败：${e.message}")
            }
        }
    }

    private fun cleanupDownload(fileId: Long) {
        try {
            downloadExecutor.execute {
                val state = downloads.remove(fileId)
                try { state?.output?.close() } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    private fun cancelUploads() {
        uploadCancelled.values.forEach { it.set(true) }
        uploadCancelled.clear()
        uploadPaused.values.forEach { it.set(false) }
        uploadPaused.clear()
    }

    private fun closeDownloads() {
        try {
            downloadExecutor.execute {
                downloads.values.forEach { state -> try { state.output.close() } catch (_: Exception) { } }
                downloads.clear()
            }
        } catch (_: Exception) { }
    }

    private fun sendStateResetBestEffort() {
        try {
            socket?.send(JSONObject().put("type", "state_reset").toString())
        } catch (_: Exception) { }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        autoReconnect = false
        connected = false
        generation.incrementAndGet()
        uploadCancelled.values.forEach { it.set(true) }
        uploadCancelled.clear()
        uploadPaused.clear()
        socket?.close(1000, "app closed")
        socket = null
        closeDownloads()
        uploadExecutor.shutdownNow()
        downloadExecutor.shutdown()
        reconnectExecutor.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        private const val TAG = "LanTouchpadWS"
        const val CHUNK_SIZE = 64 * 1024
        private const val MAGIC_SCREEN = 0x01
        private const val MAGIC_FILE = 0x02
        private const val PROTOCOL_VERSION = 2
        private const val MAX_QUEUED_BYTES = 512L * 1024L
    }
}
