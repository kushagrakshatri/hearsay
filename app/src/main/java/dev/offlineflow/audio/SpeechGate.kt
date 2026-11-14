package dev.offlineflow.audio

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Silero VAD (bundled asset, ~0.6 MB, MIT) run on the mic thread as audio arrives.
 * It tells the recorder when speech has ended (for hands-free auto-stop) and, at the end,
 * which stretches of the recording contain speech, so silence and long pauses are cut
 * before the audio reaches the speech model. Not thread-safe: feed from one thread.
 */
class SpeechGate private constructor(private val vad: Vad) : AutoCloseable {
    private val ranges = ArrayList<LongRange>()
    private var samplesSeen = 0L
    private var lastSpeechAt = -1L

    /** True once any speech has been heard. */
    val heardSpeech: Boolean get() = lastSpeechAt >= 0 || ranges.isNotEmpty()

    /** Samples of silence since speech was last heard, or 0 before any speech. */
    val trailingSilence: Long get() = if (lastSpeechAt < 0) 0 else samplesSeen - lastSpeechAt

    /**
     * Roughly where the stretch the VAD is still in started (sample offset), or -1 when it isn't
     * in one. The Kotlin Vad doesn't expose it; this mirrors sherpa's start (newest window − 2
     * windows − min speech) at the read where speech was first detected.
     */
    var openStart = -1L
        private set

    fun accept(chunk: FloatArray) {
        vad.acceptWaveform(chunk)
        samplesSeen += chunk.size
        if (vad.isSpeechDetected()) {
            lastSpeechAt = samplesSeen
            if (openStart < 0) openStart = maxOf(0L, samplesSeen / WINDOW * WINDOW - 2 * WINDOW - MIN_SPEECH_SAMPLES)
        } else openStart = -1
        drain()
        // A stretch closed and a new one opened within this read: the open one starts after it.
        if (openStart >= 0 && ranges.isNotEmpty()) openStart = maxOf(openStart, ranges.last().last + 1)
    }

    /** Speech stretches finished so far. */
    val rangeCount: Int get() = ranges.size

    /** Finished speech stretches from index [from] on (sample offsets). */
    fun rangesFrom(from: Int): List<LongRange> = ranges.subList(from, ranges.size).toList()

    /** Speech stretches of the whole recording (sample offsets), after flushing the VAD. */
    fun finish(): List<LongRange> {
        vad.flush()
        drain()
        return ranges.toList()
    }

    private fun drain() {
        while (!vad.empty()) {
            val seg = vad.front()
            val start = seg.start.toLong()
            ranges += start until start + seg.samples.size
            vad.pop()
        }
    }

    override fun close() = vad.release()

    companion object {
        private const val TAG = "SpeechGate"
        private const val WINDOW = 512L
        private const val MIN_SPEECH_SAMPLES = 2400L // minSpeechDuration 0.15 s

        /** Returns null if the VAD can't load; callers fall back to energy trimming. */
        fun create(assets: AssetManager): SpeechGate? = runCatching {
            val cfg = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "silero_vad.onnx",
                    threshold = 0.4f,
                    minSilenceDuration = 0.4f,
                    minSpeechDuration = 0.15f,
                    windowSize = 512,
                    maxSpeechDuration = 30f,
                ),
                sampleRate = Recorder.SAMPLE_RATE,
                numThreads = 1,
            )
            SpeechGate(Vad(assets, cfg))
        }.onFailure { Log.e(TAG, "VAD unavailable, using energy trim", it) }.getOrNull()

        /**
         * Keeps only the speech stretches (each padded by 250 ms) and drops the silence between
         * them, so a long pause costs the speech model nothing.
         */
        fun cut(audio: FloatArray, speech: List<LongRange>, pad: Int = 4000): FloatArray {
            if (speech.isEmpty()) return FloatArray(0)
            val merged = ArrayList<IntRange>()
            for (r in speech.sortedBy { it.first }) {
                val a = maxOf(0, r.first.toInt() - pad)
                val b = minOf(audio.size, r.last.toInt() + 1 + pad)
                if (a >= b) continue
                val last = merged.lastOrNull()
                if (last != null && a <= last.last) merged[merged.size - 1] = last.first..maxOf(last.last, b)
                else merged += a..b
            }
            val out = FloatArray(merged.sumOf { it.last - it.first })
            var i = 0
            for (r in merged) { audio.copyInto(out, i, r.first, r.last); i += r.last - r.first }
            return out
        }
    }
}
