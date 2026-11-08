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
    /**
     * Speech so far, handed over at pauses (mic thread) so it can be transcribed while the user
     * keeps talking: at the first pause the VAD closes after at least [MIN_CHUNK_MS] of new speech.
     */
    private val onChunk: ((FloatArray) -> Unit)? = null,
) {
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    private val chunks = ArrayList<FloatArray>()
    @Volatile private var endFired = false
    /** Speech stretches already handed to [onChunk]. */
    @Volatile private var emitted = 0

    /** Chunks handed to [onChunk] that [tail] continues from; 0 means [tail] is everything. Valid after [stop]. */
    val chunksSent: Int get() = emitted

    /** The speech after the last chunk handed to [onChunk] (all of it when none was). Valid after [stop]. */
    var tail = FloatArray(0)
        private set

    /** Whether the VAD heard any speech (true when there is no VAD). Valid after [stop]. */
    var heardSpeech = true
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        demoClip()?.let { startDemo(it); return }
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
                if (n > 0) afterRead(buf.copyOf(n))
            }
        }
    }

    /** Mic thread: keeps [c], updates the level and the VAD, ends hands-free, hands over chunks. */
    private fun afterRead(c: FloatArray) {
        synchronized(chunks) { chunks.add(c) }
        onLevel(rms(c))
        val g = gate ?: return
        runCatching { g.accept(c) }
        val silentTooLong = if (g.heardSpeech) g.trailingSilence >= endSilenceMs * SAMPLE_RATE / 1000L
            else synchronized(chunks) { chunks.size } * 100 >= noSpeechMs
        if (!endFired && silentTooLong) {
            endFired = true
            onSpeechEnd()
        }
        val cb = onChunk ?: return
        if (endFired || !g.heardSpeech || g.trailingSilence < CHUNK_SILENCE_MS * SAMPLE_RATE / 1000L) return
        val n = g.rangeCount
        if (n <= emitted) return
        val ranges = g.rangesFrom(emitted)
        if (ranges.last().last - ranges.first().first < MIN_CHUNK_MS * SAMPLE_RATE / 1000L) return
        emitted = n
        runCatching { cb(SpeechGate.cut(snapshot(), ranges)) }
    }

    private fun snapshot(): FloatArray {
        val all = synchronized(chunks) { chunks.toList() }
        val out = FloatArray(all.sumOf { it.size })
        var i = 0
        for (c in all) { c.copyInto(out, i); i += c.size }
        return out
    }

    /** Debug builds only: a scripted clip (16 kHz s16le) waiting in [demoDir], consumed once. */
    private fun demoClip(): FloatArray? {
        val f = demoDir?.let { java.io.File(it, "next.pcm") }?.takeIf { it.exists() } ?: return null
        val b = f.readBytes(); f.delete()
        val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 2) { bb.getShort(it * 2) / 32768f }
    }

    /** Feeds a clip through the same path as the mic, in real time, then silence until stopped. */
    private fun startDemo(clip: FloatArray) {
        synchronized(chunks) { chunks.clear() }
        running = true
        worker = thread(name = "offlineflow-demo-mic") {
            var i = 0
            var next = android.os.SystemClock.elapsedRealtime()
            while (running) {
                val c = FloatArray(1600)
                if (i < clip.size) { val n = minOf(1600, clip.size - i); clip.copyInto(c, 0, i, i + n); i += n }
                afterRead(c)
                next += 100
                val wait = next - android.os.SystemClock.elapsedRealtime()
                if (wait > 0) Thread.sleep(wait)
            }
        }
    }

    /** Stops recording and returns everything captured since [start]. */
    fun stop(): FloatArray {
        running = false
        worker?.join(500)
        record?.run { stop(); release() }
        record = null
        val out = snapshot()
        synchronized(chunks) { chunks.clear() }
        val g = gate ?: return Silence.trim(out).also { tail = it }
        val speech = runCatching { g.finish() }.getOrNull() ?: return Silence.trim(out).also { tail = it; emitted = 0 }
        g.close()
        heardSpeech = speech.isNotEmpty()
        val all = if (heardSpeech) SpeechGate.cut(out, speech) else FloatArray(0)
        tail = if (emitted > 0) SpeechGate.cut(out, speech.drop(emitted)) else all
        return all
    }

    private fun rms(x: FloatArray): Float {
        var s = 0.0
        for (v in x) s += v * v
        return sqrt(s / x.size).toFloat()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        /**
         * Hands the speech so far to the live transcriber as soon as the VAD closes a stretch
         * (its own 0.4 s pause rule) and this much silence has followed. 700 ms on top of the
         * VAD's 0.4 s needed ~1.1 s pauses, which people rarely leave mid-dictation (Nov 8 test:
         * 45 s dictation, no piece handed over).
         */
        const val CHUNK_SILENCE_MS = 100
        /** ...when there is at least this much new speech; shorter pieces wait for the next pause. */
        const val MIN_CHUNK_MS = 2000
        /** Set only in debug builds (launch-video recording); null in release, so the mic is always real. */
        @Volatile var demoDir: java.io.File? = null
    }
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
