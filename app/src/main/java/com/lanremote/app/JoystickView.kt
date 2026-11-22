package com.lanremote.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onMoveListener: ((x: Float, y: Float) -> Unit)? = null

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#661E2430")
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9900E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC00E5FF")
        style = Paint.Style.FILL
    }

    private var centerX = 0f
    private var centerY = 0f
    private var baseRadius = 0f
    private var knobRadius = 0f
    private var knobX = 0f
    private var knobY = 0f

    private var lastSendTime = 0L

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        baseRadius = minOf(w, h) * 0.42f
        knobRadius = baseRadius * 0.42f
        knobX = centerX
        knobY = centerY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(centerX, centerY, baseRadius, basePaint)
        canvas.drawCircle(centerX, centerY, baseRadius, ringPaint)
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val dx = event.x - centerX
                val dy = event.y - centerY
                val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (dist <= baseRadius) {
                    knobX = event.x
                    knobY = event.y
                } else {
                    val ratio = baseRadius / dist
                    knobX = centerX + dx * ratio
                    knobY = centerY + dy * ratio
                }
                invalidate()

                val now = System.currentTimeMillis()
                if (now - lastSendTime >= 33L) {
                    var nx = ((knobX - centerX) / baseRadius).coerceIn(-1f, 1f)
                    var ny = (-(knobY - centerY) / baseRadius).coerceIn(-1f, 1f)
                    if (hypot(nx.toDouble(), ny.toDouble()) < 0.08) {
                        nx = 0f
                        ny = 0f
                    }
                    onMoveListener?.invoke(nx, ny)
                    lastSendTime = now
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                knobX = centerX
                knobY = centerY
                invalidate()
                onMoveListener?.invoke(0f, 0f)
                lastSendTime = 0L
            }
        }
        return true
    }
}
