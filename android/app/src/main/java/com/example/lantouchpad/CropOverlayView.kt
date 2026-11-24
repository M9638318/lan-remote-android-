package com.example.lantouchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Drag-to-select overlay used for snipping a region of the remote desktop frame. */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99040A12.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2238D9C0 }
    private val start = android.graphics.PointF()
    private val end = android.graphics.PointF()
    private var selecting = false
    var onSelectionFinished: ((RectF) -> Unit)? = null

    fun reset() {
        selecting = false
        start.set(0f, 0f)
        end.set(0f, 0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
        if (selecting || (end.x > start.x && end.y > start.y)) {
            val rect = selection()
            canvas.drawRect(rect, fill)
            canvas.drawRect(rect, border)
            val handle = resources.displayMetrics.density * 5f
            listOf(rect.left to rect.top, rect.right to rect.top, rect.left to rect.bottom, rect.right to rect.bottom)
                .forEach { (x, y) -> canvas.drawCircle(x, y, handle, border) }
        } else {
            val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = resources.displayMetrics.scaledDensity * 14f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("拖动选择截图区域 · 松开完成", width / 2f, height / 2f, hint)
        }
    }

    private fun selection() = RectF(
        minOf(start.x, end.x), minOf(start.y, end.y),
        maxOf(start.x, end.x), maxOf(start.y, end.y)
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                start.set(event.x, event.y)
                end.set(event.x, event.y)
                selecting = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                end.set(event.x.coerceIn(0f, width.toFloat()), event.y.coerceIn(0f, height.toFloat()))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                end.set(event.x.coerceIn(0f, width.toFloat()), event.y.coerceIn(0f, height.toFloat()))
                selecting = false
                val rect = selection()
                invalidate()
                if (rect.width() >= 24f && rect.height() >= 24f) onSelectionFinished?.invoke(rect)
                else reset()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { reset(); return true }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
