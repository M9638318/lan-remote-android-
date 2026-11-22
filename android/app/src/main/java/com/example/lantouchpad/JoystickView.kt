package com.example.lantouchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/** Dual virtual analog sticks. Two fingers can operate left and right sticks simultaneously. */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var onAxisChange: ((axis: String, x: Float, y: Float) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val pointerSticks = HashMap<Int, Int>()
    private var leftX = 0f
    private var leftY = 0f
    private var rightX = 0f
    private var rightY = 0f
    private var lastSend = 0L

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val midY = height * 0.46f
        val radius = minOf(width * 0.19f, height * 0.31f).coerceAtLeast(35f * density)
        drawStick(canvas, width * 0.27f, midY, radius, leftX, leftY, "L")
        drawStick(canvas, width * 0.73f, midY, radius, rightX, rightY, "R")
    }

    private fun drawStick(canvas: Canvas, cx: Float, cy: Float, radius: Float, x: Float, y: Float, label: String) {
        paint.style = Paint.Style.FILL
        paint.color = 0xFF202D3E.toInt()
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = 0xFF5A718F.toInt()
        canvas.drawCircle(cx, cy, radius, paint)
        paint.color = 0xFF607D9E.toInt()
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx + x * radius * 0.62f, cy + y * radius * 0.62f, radius * 0.34f, paint)
        paint.color = 0xFFCAD6E4.toInt()
        paint.textSize = 11f * density
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("${label} STICK", cx, cy + radius + 18f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                val stick = if (event.getX(index) < width / 2f) 0 else 1
                pointerSticks[pointerId] = stick
                updateStick(event.getX(index), event.getY(index), stick, true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val stick = pointerSticks[event.getPointerId(index)] ?: continue
                    updateStick(event.getX(index), event.getY(index), stick, false)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val index = event.actionIndex.coerceIn(0, event.pointerCount - 1)
                val pointerId = event.getPointerId(index)
                val stick = pointerSticks.remove(pointerId)
                if (stick != null) {
                    if (stick == 0) { leftX = 0f; leftY = 0f } else { rightX = 0f; rightY = 0f }
                    emitAxis(stick)
                }
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    pointerSticks.clear()
                    leftX = 0f; leftY = 0f; rightX = 0f; rightY = 0f
                    emitAxis(0); emitAxis(1)
                }
                invalidate()
                return true
            }
        }
        return true
    }

    private fun updateStick(x: Float, y: Float, stick: Int, force: Boolean) {
        val cx = if (stick == 0) width * 0.27f else width * 0.73f
        val cy = height * 0.46f
        val radius = minOf(width * 0.19f, height * 0.31f).coerceAtLeast(35f * density)
        var nx = (x - cx) / radius
        var ny = (y - cy) / radius
        val length = hypot(nx, ny)
        if (length > 1f) { nx /= length; ny /= length }
        if (abs(nx) < 0.08f) nx = 0f
        if (abs(ny) < 0.08f) ny = 0f
        if (stick == 0) { leftX = nx; leftY = ny } else { rightX = nx; rightY = ny }
        val now = SystemClock.uptimeMillis()
        if (force || now - lastSend >= 33L) {
            lastSend = now
            emitAxis(stick)
        }
        invalidate()
    }

    private fun emitAxis(stick: Int) {
        if (stick == 0) onAxisChange?.invoke("L", leftX, -leftY)
        else onAxisChange?.invoke("R", rightX, -rightY)
    }
}
