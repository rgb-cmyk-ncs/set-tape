package com.sergei.settape

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import com.sergei.settape.audio.Db

/**
 * A peak meter in dBFS, one bar per recorded channel, with a peak-hold tick and an optional
 * marker for the auto-record threshold. 0 dB at the right-hand end is digital full scale.
 */
class MeterView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.DIM
        textSize = Ui.sp(context, 11f)
        typeface = Typeface.MONOSPACE
    }

    private var channels = 2
    private val level = FloatArray(2) { Db.FLOOR }
    private val hold = FloatArray(2) { Db.FLOOR }
    private val holdAt = LongArray(2)
    private var lastPush = 0L

    /** Draws the trigger level when not null. */
    var thresholdDb: Float? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Feeds the newest peaks (linear, 1.0 = full scale). Call once per screen frame. */
    fun push(count: Int, peak: FloatArray) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastPush == 0L) 0f else (now - lastPush).coerceAtMost(200) / 1000f
        lastPush = now
        channels = count.coerceIn(1, 2)
        for (c in 0 until channels) {
            val db = Db.fromLinear(peak[c])
            level[c] = maxOf(db, level[c] - FALL_DB_PER_SECOND * dt)
            if (db >= hold[c]) {
                hold[c] = db
                holdAt[c] = now
            } else if (now - holdAt[c] > HOLD_MILLIS) {
                hold[c] = maxOf(db, hold[c] - FALL_DB_PER_SECOND * dt)
            }
        }
        invalidate()
    }

    /** Shows silence, for when no input is open. */
    fun clear() {
        for (c in 0..1) {
            level[c] = Db.FLOOR
            hold[c] = Db.FLOOR
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (92 * density).toInt()
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    private fun xFor(db: Float, left: Float, right: Float): Float {
        val t = ((db - MIN_DB) / -MIN_DB).coerceIn(0f, 1f)
        return left + t * (right - left)
    }

    override fun onDraw(canvas: Canvas) {
        val gutter = 18 * density
        val left = gutter
        val right = width.toFloat()
        val scaleH = 18 * density
        val gap = 6 * density
        val barsH = height - scaleH
        val barH = if (channels == 2) (barsH - gap) / 2f else minOf(barsH, 34 * density)

        val seg = 4 * density
        val segGap = 1.5f * density
        val count = ((right - left + segGap) / (seg + segGap)).toInt().coerceAtLeast(1)
        val step = (right - left + segGap) / count

        for (c in 0 until channels) {
            val top = c * (barH + gap)
            text.textAlign = Paint.Align.LEFT
            text.color = Ui.DIM
            if (channels == 2) canvas.drawText(if (c == 0) "L" else "R", 0f, top + barH / 2 + text.textSize / 3, text)

            for (i in 0 until count) {
                val x0 = left + i * step
                val dbLo = MIN_DB + (-MIN_DB) * (i.toFloat() / count)
                val dbMid = MIN_DB + (-MIN_DB) * ((i + 0.5f) / count)
                val color = when {
                    dbMid >= RED_DB -> Ui.RED
                    dbMid >= AMBER_DB -> Ui.AMBER
                    else -> Ui.GREEN
                }
                paint.color = color
                paint.alpha = if (level[c] > dbLo) 255 else 38
                canvas.drawRect(x0, top, x0 + step - segGap, top + barH, paint)
            }

            // Peak-hold tick.
            if (hold[c] > MIN_DB) {
                val hx = xFor(hold[c], left, right)
                paint.color = when {
                    hold[c] >= RED_DB -> Ui.RED
                    hold[c] >= AMBER_DB -> Ui.AMBER
                    else -> Ui.TEXT
                }
                paint.alpha = 255
                canvas.drawRect(hx - 1.5f * density, top - 1 * density, hx + 1.5f * density, top + barH + 1 * density, paint)
            }
        }

        val barsBottom = channels * barH + (channels - 1) * gap

        // Auto-record threshold.
        thresholdDb?.let { th ->
            val tx = xFor(th, left, right)
            paint.color = Ui.TEXT
            paint.alpha = 220
            canvas.drawRect(tx - density, 0f, tx + density, barsBottom + 4 * density, paint)
        }

        // Scale.
        text.color = Ui.DIM
        val y = barsBottom + scaleH - 3 * density
        for (mark in MARKS) {
            val x = xFor(mark.toFloat(), left, right)
            text.textAlign = when (mark) {
                0 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText(mark.toString(), x, y, text)
        }
    }

    companion object {
        const val MIN_DB = -60f
        const val AMBER_DB = -12f
        const val RED_DB = -3f
        private const val FALL_DB_PER_SECOND = 24f
        private const val HOLD_MILLIS = 1500L
        private val MARKS = intArrayOf(-48, -36, -24, -12, -6, 0)
    }
}
