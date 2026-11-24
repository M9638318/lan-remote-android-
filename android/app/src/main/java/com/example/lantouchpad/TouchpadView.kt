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
import org.json.JSONObject
import kotlin.math.abs

/** Trackpad: one-finger relative motion, tap click, two-finger scroll, long-press drag. */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var onMouseEvent: ((JSONObject) -> Unit)? = null
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
    private var dragging = false
    private var moved = false
    private var twoFinger = false
    private var lastCentroidY = 0f
    private var pressRunnable: Runnable? = null

    init { isClickable = true }

    private fun emit(action: String, vararg values: Pair<String, Any>) {
        val json = JSONObject().put("type", "mouse").put("action", action)
        values.forEach { (key, value) -> json.put(key, value) }
        onMouseEvent?.invoke(json)
    }

    private fun cancelPressTimer() {
        pressRunnable?.let(handler::removeCallbacks)
        pressRunnable = null
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
        canvas.drawText("单指移动  ·  轻点左键", width / 2f, height / 2f + 18f * density, paint)
        canvas.drawText("双指滚动  ·  长按拖拽", width / 2f, height / 2f + 38f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                downAt = SystemClock.uptimeMillis()
                pendingDx = 0f
                pendingDy = 0f
                pendingScrollPx = 0f
                moved = false
                dragging = false
                twoFinger = false
                cancelPressTimer()
                pressRunnable = Runnable {
                    if (!twoFinger && !moved && isPressed) {
                        dragging = true
                        emit("down", "button" to "left")
                        invalidate()
                    }
                }.also { handler.postDelayed(it, 420L) }
                isPressed = true
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelPressTimer()
                twoFinger = true
                if (event.pointerCount >= 2) lastCentroidY = (event.getY(0) + event.getY(1)) / 2f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 || twoFinger) {
                    cancelPressTimer()
                    twoFinger = true
                    val centroid = (event.getY(0) + event.getY(1)) / 2f
                    val dy = lastCentroidY - centroid
                    lastCentroidY = centroid
                    pendingScrollPx += dy
                    val now = SystemClock.uptimeMillis()
                    val scrollUnit = 42f * density
                    val steps = (pendingScrollPx / scrollUnit).toInt()
                    if (steps != 0 && now - lastScrollAt >= 16L) {
                        lastScrollAt = now
                        pendingScrollPx -= steps * scrollUnit
                        emit("scroll", "amount" to steps.coerceIn(-8, 8))
                    }
                    return true
                }
                val dxFromStart = event.x - downX
                val dyFromStart = event.y - downY
                if (abs(dxFromStart) > slop || abs(dyFromStart) > slop) {
                    moved = true
                    if (!dragging) cancelPressTimer()
                }
                val dx = (event.x - lastX) * 1.65f
                val dy = (event.y - lastY) * 1.65f
                lastX = event.x
                lastY = event.y
                pendingDx += dx
                pendingDy += dy
                val now = SystemClock.uptimeMillis()
                if ((abs(pendingDx) + abs(pendingDy)) > 0.5f && now - lastSentAt >= 16L) {
                    lastSentAt = now
                    emit("move", "dx" to pendingDx.toInt(), "dy" to pendingDy.toInt())
                    pendingDx = 0f
                    pendingDy = 0f
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remainingIndex = if (event.actionIndex == 0) 1 else 0
                if (remainingIndex < event.pointerCount) {
                    lastX = event.getX(remainingIndex)
                    lastY = event.getY(remainingIndex)
                }
                moved = true
                twoFinger = false
                pendingScrollPx = 0f
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelPressTimer()
                val elapsed = SystemClock.uptimeMillis() - downAt
                if (dragging) {
                    if (abs(pendingDx) + abs(pendingDy) > 0.5f) {
                        emit("move", "dx" to pendingDx.toInt(), "dy" to pendingDy.toInt())
                    }
                    emit("up", "button" to "left")
                } else if (!twoFinger && !moved && elapsed < 420L && event.actionMasked == MotionEvent.ACTION_UP) {
                    emit("click", "button" to "left")
                }
                dragging = false
                twoFinger = false
                isPressed = false
                performClick()
                invalidate()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
