package dev.offlineflow.core

import android.util.Log
import dev.offlineflow.audio.Recorder
import dev.offlineflow.cleanup.Joins
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * Speech recognition while the user is still talking. The recorder hands over each finished
 * stretch of speech at a pause ([add]); it is transcribed on the speech thread right away, so
 * when the mic is released only the last stretch is left to do ([finish]).
 */
class LiveSpeech(
    private val scope: CoroutineScope,
    private val transcribe: (FloatArray) -> String,
    /** Speech thread: the transcript so far, one entry per piece, after each piece is done. */
    private val onText: (List<String>) -> Unit = {},
) {
    private val parts = ArrayList<Deferred<String?>>()
    private val chunks = ArrayList<FloatArray>()
    private val texts = ArrayList<String>()

    /** The transcript in pieces (one per pause, then the tail), as transcribed; [finish] joins them with [Joins]. */
    var pieces: List<String> = emptyList()
        private set
    @Volatile private var audioMs = 0L

    val started: Boolean get() = synchronized(parts) { parts.isNotEmpty() }

    /** Mic thread. Starts transcribing [chunk] on the speech thread. */
    fun add(chunk: FloatArray) {
        audioMs += chunk.size * 1000L / Recorder.SAMPLE_RATE
        val d = scope.async(Brain.asr) {
            runCatching { transcribe(chunk) }.onFailure { Log.e(TAG, "live chunk failed", it) }.getOrNull()
                ?.also { text ->
                    val soFar = synchronized(texts) { texts += text.trim(); texts.filter { it.isNotEmpty() } }
                    runCatching { onText(soFar) }
                }
        }
        synchronized(parts) { parts += d; chunks += chunk }
    }

    /**
     * The whole transcript: the stretches done while talking plus [tail], the speech after the
     * last pause. Null when a stretch failed, so the caller transcribes the recording in one go.
     */
    suspend fun finish(tail: FloatArray, t: Timing?): String? {
        val waitFrom = Timing.now()
        val (ds, cs) = synchronized(parts) { parts.toList() to chunks.toList() }
        var redone = 0
        // A piece that failed while talking gets one more try on its own before giving up on all of it.
        val done = ds.mapIndexed { i, d ->
            d.await() ?: withContext(Brain.asr) { runCatching { transcribe(cs[i]) }.getOrNull() }
                ?.also { redone++ } ?: run { t?.liveFailed = true; return null }
        }
        t?.apply { liveWait = Timing.now() - waitFrom; liveAudioMs = audioMs; liveRedone = redone }
        val last = if (tail.size >= Recorder.SAMPLE_RATE / 4) Brain.onAsr(t) { transcribe(tail) } else ""
        pieces = (done + last).map { it.trim() }.filter { it.isNotEmpty() }
        // Each piece was transcribed alone and ends like a sentence; mend the seams that don't end one.
        return Joins.join(pieces)
    }

    private companion object { const val TAG = "HearsayLive" }
}
