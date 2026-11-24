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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Low-latency screen surface. Frame queues are coalesced and decoded off the UI thread. */
class ScreenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    enum class ScaleMode { FIT, FILL, STRETCH }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    @Volatile private var decoder: ExecutorService = newDecoder()
    private val latestBytes = AtomicReference<ByteArray?>(null)
    private val latestBitmap = AtomicReference<Bitmap?>(null)
    private val decoding = AtomicBoolean(false)
    private val uiUpdatePosted = AtomicBoolean(false)
    @Volatile private var bitmap: Bitmap? = null
    @Volatile var scaleMode: ScaleMode = ScaleMode.FIT
        private set

    init { setBackgroundColor(Color.rgb(5, 8, 14)) }

    private fun newDecoder() = Executors.newSingleThreadExecutor { r ->
        Thread(r, "screen-jpeg-decoder").apply { isDaemon = true }
    }

    fun setScaleMode(mode: ScaleMode) {
        scaleMode = mode
        invalidate()
    }

    fun cycleScaleMode() {
        scaleMode = when (scaleMode) {
            ScaleMode.FIT -> ScaleMode.FILL
            ScaleMode.FILL -> ScaleMode.STRETCH
            ScaleMode.STRETCH -> ScaleMode.FIT
        }
        invalidate()
    }

    fun submitFrame(jpeg: ByteArray) {
        if (jpeg.isEmpty() || jpeg.size > 16 * 1024 * 1024) return
        latestBytes.set(jpeg)
        startDecodeLoop()
    }

    private fun startDecodeLoop() {
        if (decoder.isShutdown) decoder = newDecoder()
        if (!decoding.compareAndSet(false, true)) return
        decoder.execute {
            try {
                while (true) {
                    val data = latestBytes.getAndSet(null) ?: break
                    try {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue
                        var sample = 1
                        while (bounds.outWidth / sample > MAX_DECODE_DIMENSION ||
                            bounds.outHeight / sample > MAX_DECODE_DIMENSION) sample *= 2
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: continue
                        latestBitmap.getAndSet(decoded)?.recycle()
                        scheduleUiUpdate()
                    } catch (_: OutOfMemoryError) {
                        latestBitmap.getAndSet(null)?.recycle()
                    }
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

    private fun imageRect(frame: Bitmap): RectF {
        val viewW = width.toFloat().coerceAtLeast(1f)
        val viewH = height.toFloat().coerceAtLeast(1f)
        val scaleX = viewW / frame.width
        val scaleY = viewH / frame.height
        val scale = when (scaleMode) {
            ScaleMode.FIT -> minOf(scaleX, scaleY)
            ScaleMode.FILL -> maxOf(scaleX, scaleY)
            ScaleMode.STRETCH -> 1f
        }
        val drawW = if (scaleMode == ScaleMode.STRETCH) viewW else frame.width * scale
        val drawH = if (scaleMode == ScaleMode.STRETCH) viewH else frame.height * scale
        val left = (viewW - drawW) / 2f
        val top = (viewH - drawH) / 2f
        return RectF(left, top, left + drawW, top + drawH)
    }

    /** Convert a view-local touch point to 0..1 coordinates within the remote monitor. */
    fun mapToNormalized(viewX: Float, viewY: Float): Pair<Float, Float>? {
        val frame = bitmap ?: return null
        val rect = imageRect(frame)
        if (!rect.contains(viewX, viewY)) return null
        return ((viewX - rect.left) / rect.width()).coerceIn(0f, 1f) to
            ((viewY - rect.top) / rect.height()).coerceIn(0f, 1f)
    }

    fun snapshotBitmap(): Bitmap? {
        val current = bitmap ?: return null
        if (current.isRecycled) return null
        return try { current.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Exception) { null }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = bitmap ?: return
        if (!frame.isRecycled && width > 0 && height > 0) {
            canvas.drawBitmap(frame, null, imageRect(frame), paint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        decoder.shutdownNow()
        latestBytes.set(null)
        latestBitmap.getAndSet(null)?.recycle()
        bitmap?.recycle()
        bitmap = null
        decoding.set(false)
        uiUpdatePosted.set(false)
    }

    companion object { private const val MAX_DECODE_DIMENSION = 2560 }
}
