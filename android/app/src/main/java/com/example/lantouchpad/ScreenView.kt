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
