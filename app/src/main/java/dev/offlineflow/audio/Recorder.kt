package dev.offlineflow.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Captures 16 kHz mono float PCM into memory. Nothing is written to disk.
 * With a [gate], speech is tracked live: [onSpeechEnd] fires (once, on the mic thread) after
 * [endSilenceMs] of silence following speech (or [noSpeechMs] with no speech at all), and [stop] returns only the speech stretches.
 * Caller must hold RECORD_AUDIO.
 */
class Recorder(
    private val gate: SpeechGate? = null,
    private val endSilenceMs: Int = 1500,
    private val noSpeechMs: Int = 8000,
    private val onSpeechEnd: () -> Unit = {},
    private val onLevel: (Float) -> Unit = {},
) {
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    private val chunks = ArrayList<FloatArray>()
    @Volatile private var endFired = false

    /** Whether the VAD heard any speech (true when there is no VAD). Valid after [stop]. */
    var heardSpeech = true
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(minBuf, SAMPLE_RATE)
        )
        synchronized(chunks) { chunks.clear() }
        record = rec
        running = true
        rec.startRecording()
        worker = thread(name = "offlineflow-mic") {
            val buf = FloatArray(1600) // 100 ms
            while (running) {
                val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n > 0) {
                    val c = buf.copyOf(n)
                    synchronized(chunks) { chunks.add(c) }
                    onLevel(rms(c))
                    gate?.let { g ->
                        runCatching { g.accept(c) }
                        val silentTooLong = if (g.heardSpeech) g.trailingSilence >= endSilenceMs * SAMPLE_RATE / 1000L
                            else synchronized(chunks) { chunks.size } * 100 >= noSpeechMs
                        if (!endFired && silentTooLong) {
                            endFired = true
                            onSpeechEnd()
                        }
                    }
                }
            }
        }
    }

    /** Stops recording and returns everything captured since [start]. */
    fun stop(): FloatArray {
        running = false
        worker?.join(500)
        record?.run { stop(); release() }
        record = null
        val all = synchronized(chunks) { chunks.toList().also { chunks.clear() } }
        val out = FloatArray(all.sumOf { it.size })
        var i = 0
        for (c in all) { c.copyInto(out, i); i += c.size }
        val g = gate ?: return Silence.trim(out)
        val speech = runCatching { g.finish() }.getOrNull() ?: return Silence.trim(out)
        g.close()
        heardSpeech = speech.isNotEmpty()
        return if (heardSpeech) SpeechGate.cut(out, speech) else FloatArray(0)
    }

    private fun rms(x: FloatArray): Float {
        var s = 0.0
        for (v in x) s += v * v
        return sqrt(s / x.size).toFloat()
    }

    companion object { const val SAMPLE_RATE = 16_000 }
}

/** Energy-based trim of leading/trailing silence; the fallback when the VAD is unavailable. */
object Silence {
    fun trim(x: FloatArray, frame: Int = 320, threshold: Float = 0.01f): FloatArray {
        if (x.isEmpty()) return x
        fun loud(f: Int): Boolean {
            var s = 0.0
            val end = minOf(x.size, f + frame)
            for (i in f until end) s += x[i] * x[i]
            return sqrt(s / (end - f)) > threshold
        }
        var start = 0
        while (start < x.size && !loud(start)) start += frame
        var end = x.size
        while (end - frame > start && !loud(end - frame)) end -= frame
        val pad = 3200 // keep 200 ms around speech
        return x.copyOfRange(maxOf(0, start - pad), minOf(x.size, end + pad))
    }
}
