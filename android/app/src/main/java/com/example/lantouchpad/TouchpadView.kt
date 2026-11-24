package com.example.lantouchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** Multi-gesture trackpad: relative movement, tap, right/middle click, scroll and drag. */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var onMouseEvent: ((org.json.JSONObject) -> Unit)? = null
    var sensitivity: Float = 1.65f
        set(value) { field = value.coerceIn(0.35f, 4.0f) }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density
    private val slop = 10f * density
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var lastSentAt = 0L
    private var lastScrollAt = 0L
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var pendingScrollPx = 0f
    private var moved = false
    private var dragging = false
    private var gestureMode = 0 // 0=one finger, 2=two finger scroll/right click, 3=middle click
    private var pointerCountMax = 1
    private var lastCentroidY = 0f
    private var pressRunnable: Runnable? = null

    init { isClickable = true }

    private fun emit(action: String, vararg values: Pair<String, Any>) {
        val json = org.json.JSONObject().put("type", "mouse").put("action", action)
        values.forEach { (key, value) -> json.put(key, value) }
        onMouseEvent?.invoke(json)
    }

    private fun cancelPressTimer() {
        pressRunnable?.let(handler::removeCallbacks)
        pressRunnable = null
    }

    private fun flushMovement() {
        val dx = pendingDx.toInt()
        val dy = pendingDy.toInt()
        if (dx != 0 || dy != 0) emit("move", "dx" to dx, "dy" to dy)
        pendingDx -= dx
        pendingDy -= dy
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = 14f * density
        paint.color = 0xFF202D3E.toInt()
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, paint)
        paint.color = 0xFF34465E.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        canvas.drawRoundRect(RectF(2f, 2f, width - 2f, height - 2f), radius, radius, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xFFE0E8F2.toInt()
        paint.textSize = 15f * density
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("触 控 板", width / 2f, height / 2f - 8f * density, paint)
        paint.color = 0xFF9FB1C7.toInt()
        paint.textSize = 11f * density
        canvas.drawText("单指移动 · 轻点左键 · 长按拖拽", width / 2f, height / 2f + 16f * density, paint)
        canvas.drawText("双指滚动/轻点右键 · 三指轻点中键", width / 2f, height / 2f + 36f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                lastX = event.x; lastY = event.y
                downAt = SystemClock.uptimeMillis()
                pendingDx = 0f; pendingDy = 0f; pendingScrollPx = 0f
                moved = false; dragging = false; gestureMode = 0; pointerCountMax = 1
                cancelPressTimer()
                pressRunnable = Runnable {
                    if (gestureMode == 0 && !moved && isPressed) {
                        dragging = true
                        emit("down", "button" to "left")
                        invalidate()
                    }
                }.also { handler.postDelayed(it, LONG_PRESS_MS) }
                isPressed = true
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelPressTimer()
                pointerCountMax = maxOf(pointerCountMax, event.pointerCount)
                gestureMode = if (event.pointerCount >= 3) 3 else 2
                if (event.pointerCount >= 2) lastCentroidY = (0 until event.pointerCount).map { event.getY(it) }.average().toFloat()
                // A second/third finger alone is not movement: this preserves multi-finger tap clicks.
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 && gestureMode == 2) {
                    val centroid = (event.getY(0) + event.getY(1)) / 2f
                    val dy = lastCentroidY - centroid
                    lastCentroidY = centroid
                    pendingScrollPx += dy
                    val now = SystemClock.uptimeMillis()
                    val scrollUnit = 42f * density
                    val steps = (pendingScrollPx / scrollUnit).toInt()
                    if (steps != 0 && now - lastScrollAt >= 16L) {
                        lastScrollAt = now
                        val sent = steps.coerceIn(-12, 12)
                        emit("scroll", "amount" to sent)
                        pendingScrollPx -= sent * scrollUnit
                    }
                    if (abs(dy) > slop * 0.2f) moved = true
                    return true
                }
                if (gestureMode == 3) {
                    val centroid = (0 until event.pointerCount).map { event.getY(it) }.average().toFloat()
                    if (abs(centroid - lastCentroidY) > slop) moved = true
                    lastCentroidY = centroid
                    return true
                }
                if (gestureMode != 0) return true
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                    moved = true
                    if (!dragging) cancelPressTimer()
                }
                pendingDx += (event.x - lastX) * sensitivity
                pendingDy += (event.y - lastY) * sensitivity
                lastX = event.x; lastY = event.y
                val now = SystemClock.uptimeMillis()
                if ((abs(pendingDx) >= 1f || abs(pendingDy) >= 1f) && now - lastSentAt >= 16L) {
                    lastSentAt = now
                    flushMovement()
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Do not silently return a remaining finger to relative-mouse mode mid-gesture.
                gestureMode = if (event.pointerCount > 2) 3 else 2
                pendingScrollPx = 0f
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelPressTimer()
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                val elapsed = SystemClock.uptimeMillis() - downAt
                if (dragging) {
                    flushMovement()
                    emit("up", "button" to "left")
                } else if (!cancelled && !moved && elapsed < LONG_PRESS_MS) {
                    when (pointerCountMax) {
                        1 -> emit("click", "button" to "left")
                        2 -> emit("click", "button" to "right")
                        else -> emit("click", "button" to "middle")
                    }
                } else if (!cancelled && gestureMode == 0) {
                    flushMovement()
                }
                dragging = false; gestureMode = 0; pointerCountMax = 1
                pendingDx = 0f; pendingDy = 0f; pendingScrollPx = 0f
                isPressed = false
                performClick()
                invalidate()
                return true
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        cancelPressTimer()
        if (dragging) emit("up", "button" to "left")
        dragging = false
        isPressed = false
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object { private const val LONG_PRESS_MS = 420L }
}
