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
