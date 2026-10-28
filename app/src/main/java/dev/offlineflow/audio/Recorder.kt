package dev.offlineflow.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Captures 16 kHz mono float PCM into memory. Nothing is written to disk.
 * Caller must hold RECORD_AUDIO.
 */
class Recorder(private val onLevel: (Float) -> Unit = {}) {
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    private val chunks = ArrayList<FloatArray>()

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
        return Silence.trim(out)
    }

    private fun rms(x: FloatArray): Float {
        var s = 0.0
        for (v in x) s += v * v
        return sqrt(s / x.size).toFloat()
    }

    companion object { const val SAMPLE_RATE = 16_000 }
}

/** Energy-based trim of leading/trailing silence. M1 swaps this for Silero VAD. */
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
