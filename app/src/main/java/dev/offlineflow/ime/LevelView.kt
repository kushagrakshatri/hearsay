package dev.offlineflow.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

/** A scrolling bar waveform of the mic level while listening. */
class LevelView(context: Context) : View(context) {
    private val levels = FloatArray(48)
    private var head = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4F46E5") }
    var color: Int
        get() = paint.color
        set(v) { paint.color = v; invalidate() }
    private val minBar = 2 * resources.displayMetrics.density

    /** Adds one RMS reading (0..1). Main thread. */
    fun push(rms: Float) {
        levels[head] = rms
        head = (head + 1) % levels.size
        invalidate()
    }

    fun clear() { levels.fill(0f); head = 0; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = levels.size
        val slot = width / n.toFloat()
        val barW = slot * 0.6f
        val mid = height / 2f
        for (i in 0 until n) {
            val v = levels[(head + i) % n]
            val h = maxOf(minBar, sqrt(min(1f, v * 10f)) * height)
            val x = i * slot + (slot - barW) / 2
            canvas.drawRoundRect(x, mid - h / 2, x + barW, mid + h / 2, barW / 2, barW / 2, paint)
        }
    }
}
