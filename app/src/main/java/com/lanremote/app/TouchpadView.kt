package com.lanremote.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onMove(dx: Float, dy: Float)
        fun onClick()
        fun onDoubleClick()
        fun onRightClick()
        fun onDragStart()
        fun onDragEnd()
        fun onScroll(dy: Int)
    }

    var listener: Listener? = null
    var sensitivity: Float = 1.6f

    private val mainHandler = Handler(Looper.getMainLooper())
    private val dragBorderPaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 10f
    }

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastTapTime = 0L

    private var isDragging = false
    private var isTwoFinger = false
    private var hasMoved = false
    private var twoFingerLastY = 0f

    private var pendingDx = 0f
    private var pendingDy = 0f
    private var lastMoveSendTime = 0L
    private var lastScrollSendTime = 0L

    private val longPressRunnable = Runnable {
        if (!hasMoved && !isTwoFinger) {
            isDragging = true
            listener?.onDragStart()
            invalidate()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val now = System.currentTimeMillis()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                downTime = now
                hasMoved = false
                isTwoFinger = false
                pendingDx = 0f
                pendingDy = 0f
                mainHandler.postDelayed(longPressRunnable, 350L)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                mainHandler.removeCallbacks(longPressRunnable)
                if (isDragging) {
                    isDragging = false
                    listener?.onDragEnd()
                    invalidate()
                }
                if (event.pointerCount == 2) {
                    isTwoFinger = true
                    hasMoved = false
                    twoFingerLastY = (event.getY(0) + event.getY(1)) / 2f
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isTwoFinger && event.pointerCount >= 2) {
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    val dy = midY - twoFingerLastY
                    if (abs(dy) > 8f) {
                        hasMoved = true
                        if (now - lastScrollSendTime >= 40L) {
                            val scrollStep = (dy * 1.8f).toInt()
                            if (scrollStep != 0) {
                                listener?.onScroll(scrollStep)
                                lastScrollSendTime = now
                            }
                            twoFingerLastY = midY
                        }
                    }
                } else if (!isTwoFinger) {
                    val dx = (event.x - lastX) * sensitivity
                    val dy = (event.y - lastY) * sensitivity
                    lastX = event.x
                    lastY = event.y

                    if (!hasMoved && hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > 16.0) {
                        hasMoved = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }

                    pendingDx += dx
                    pendingDy += dy
                    if (now - lastMoveSendTime >= 33L) {
                        flushMove(now)
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (isTwoFinger && !hasMoved && (now - downTime) < 280L) {
                    listener?.onRightClick()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                flushMove(now)

                if (isDragging) {
                    isDragging = false
                    listener?.onDragEnd()
                    invalidate()
                } else if (!isTwoFinger && !hasMoved && (now - downTime) < 260L) {
                    if (now - lastTapTime < 280L) {
                        listener?.onDoubleClick()
                        lastTapTime = 0L
                    } else {
                        listener?.onClick()
                        lastTapTime = now
                    }
                }
                isTwoFinger = false
            }
        }
        return true
    }

    private fun flushMove(now: Long) {
        if (abs(pendingDx) >= 0.5f || abs(pendingDy) >= 0.5f) {
            listener?.onMove(pendingDx, pendingDy)
            pendingDx = 0f
            pendingDy = 0f
            lastMoveSendTime = now
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isDragging) {
            canvas.drawRect(5f, 5f, width - 5f, height - 5f, dragBorderPaint)
        }
    }
}
