package com.sergei.settape

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/** The one big button: a red dot to start, a square to stop, a pulsing ring while armed. */
class RecordButton(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    var mode = Engine.State.MONITOR
        set(value) {
            if (field != value) {
                field = value
                contentDescription = when (value) {
                    Engine.State.RECORDING -> "Stop recording"
                    Engine.State.ARMED -> "Disarm"
                    else -> "Record"
                }
                invalidate()
            }
        }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Record"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (132 * density).toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 4 * density
        val pressedScale = if (isPressed) 0.94f else 1f

        // Outer ring.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4 * density
        paint.color = when (mode) {
            Engine.State.ARMED -> Ui.AMBER
            Engine.State.RECORDING -> Ui.RED
            else -> Ui.TEXT
        }
        paint.alpha = 255
        if (mode == Engine.State.ARMED) {
            val phase = (SystemClock.uptimeMillis() % 1600) / 1600.0
            paint.alpha = (150 + 105 * sin(2 * PI * phase)).toInt().coerceIn(0, 255)
            postInvalidateOnAnimation()
        }
        canvas.drawCircle(cx, cy, r, paint)

        // Centre.
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        when (mode) {
            Engine.State.RECORDING -> {
                val s = r * 0.42f * pressedScale
                paint.color = Ui.RED
                rect.set(cx - s, cy - s, cx + s, cy + s)
                canvas.drawRoundRect(rect, 8 * density, 8 * density, paint)
            }
            Engine.State.ARMED -> {
                paint.color = Ui.AMBER
                canvas.drawCircle(cx, cy, r * 0.30f * pressedScale, paint)
            }
            else -> {
                paint.color = if (isEnabled) Ui.RED else Ui.DIM
                canvas.drawCircle(cx, cy, r * 0.78f * pressedScale, paint)
            }
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}
