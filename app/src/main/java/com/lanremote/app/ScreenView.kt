package com.lanremote.app

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

class ScreenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val decodeExecutor = Executors.newSingleThreadExecutor()
    private val isDecoding = AtomicBoolean(false)
    private val currentBitmap = AtomicReference<Bitmap?>(null)

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5C667A")
        textSize = 36f
        textAlign = Paint.Align.CENTER
    }
    private val dstRect = RectF()

    fun updateFrame(jpegBytes: ByteArray) {
        if (!isDecoding.compareAndSet(false, true)) return

        decodeExecutor.execute {
            try {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts)
                if (decoded != null) {
                    val old = currentBitmap.getAndSet(decoded)
                    postInvalidate()
                    if (old != null && old != decoded && !old.isRecycled) {
                        old.recycle()
                    }
                }
            } catch (_: Throwable) {
            } finally {
                isDecoding.set(false)
            }
        }
    }

    fun clearScreen() {
        val old = currentBitmap.getAndSet(null)
        if (old != null && !old.isRecycled) {
            old.recycle()
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#0D0F14"))

        val bmp = currentBitmap.get()
        if (bmp == null || bmp.isRecycled) {
            canvas.drawText(
                "点击顶部【连接】接入局域网电脑",
                width / 2f,
                height / 2f,
                hintPaint
            )
            return
        }

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val bmpW = bmp.width.toFloat()
        val bmpH = bmp.height.toFloat()
        if (viewW <= 0f || viewH <= 0f || bmpW <= 0f || bmpH <= 0f) return

        val scale = minOf(viewW / bmpW, viewH / bmpH)
        val drawW = bmpW * scale
        val drawH = bmpH * scale
        val left = (viewW - drawW) / 2f
        val top = (viewH - drawH) / 2f
        dstRect.set(left, top, left + drawW, top + drawH)

        canvas.drawBitmap(bmp, null, dstRect, bitmapPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        decodeExecutor.shutdownNow()
        clearScreen()
    }
}
