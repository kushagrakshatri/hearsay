package dev.offlineflow.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import dev.offlineflow.R
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The round mic key. Idle: a filled circle with a mic. Listening: a halo that breathes with your
 * voice. Hands-free: a stop square. Busy: a spinning arc while speech and cleanup run.
 */
class MicButton(context: Context) : View(context) {
    enum class State { IDLE, LISTENING, HANDS_FREE, BUSY }

    var state = State.IDLE
        set(v) { field = v; contentDescription = describe(v); invalidate() }

    var idleColor = 0
    var activeColor = 0
    var iconColor = 0
    var busyColor = 0

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 3.5f * resources.displayMetrics.density
    }
    private val mic: Drawable = ContextCompat.getDrawable(context, R.drawable.ic_mic)!!.mutate()
    private val stop: Drawable = ContextCompat.getDrawable(context, R.drawable.ic_stop)!!.mutate()
    private var level = 0f
    private var shown = 0f
    private val oval = RectF()

    init { contentDescription = describe(state); isClickable = true }

    /** One mic RMS reading (0..1). */
    fun push(rms: Float) { level = sqrt(min(1f, rms * 10f)); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f * 0.72f
        val listening = state == State.LISTENING || state == State.HANDS_FREE

        if (listening) {
            shown += (level - shown) * 0.35f
            halo.color = ColorUtils.setAlphaComponent(activeColor, 60)
            canvas.drawCircle(cx, cy, r * (1.08f + 0.30f * shown), halo)
            halo.color = ColorUtils.setAlphaComponent(activeColor, 36)
            canvas.drawCircle(cx, cy, r * (1.16f + 0.42f * shown), halo)
            if (shown > 0.01f || level > 0f) postInvalidateOnAnimation()
        } else shown = 0f

        fill.color = when (state) {
            State.IDLE -> idleColor
            State.BUSY -> ColorUtils.setAlphaComponent(idleColor, 150)
            else -> activeColor
        }
        canvas.drawCircle(cx, cy, r, fill)

        if (state == State.BUSY) {
            arc.color = busyColor
            val a = (SystemClock.uptimeMillis() % 1000L) / 1000f * 360f
            val pad = arc.strokeWidth * 2
            oval.set(cx - r - pad, cy - r - pad, cx + r + pad, cy + r + pad)
            canvas.drawArc(oval, a, 90f, false, arc)
            postInvalidateOnAnimation()
        }

        val icon = if (state == State.HANDS_FREE) stop else mic
        val s = (r * 0.85f).toInt()
        icon.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
        icon.setTint(iconColor)
        icon.draw(canvas)
    }

    private fun describe(s: State) = when (s) {
        State.IDLE -> "Microphone. Hold to talk, tap for hands-free"
        State.LISTENING -> "Listening"
        State.HANDS_FREE -> "Listening hands-free. Tap to stop"
        State.BUSY -> "Working"
    }
}
