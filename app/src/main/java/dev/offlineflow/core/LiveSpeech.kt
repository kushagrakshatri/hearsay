package dev.offlineflow.core

import android.util.Log
import dev.offlineflow.audio.Recorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * Speech recognition while the user is still talking. The recorder hands over each finished
 * stretch of speech at a pause ([add]); it is transcribed on the speech thread right away, so
 * when the mic is released only the last stretch is left to do ([finish]).
 */
class LiveSpeech(private val scope: CoroutineScope, private val transcribe: (FloatArray) -> String) {
    private val parts = ArrayList<Deferred<String?>>()
    @Volatile private var audioMs = 0L

    val started: Boolean get() = synchronized(parts) { parts.isNotEmpty() }

    /** Mic thread. Starts transcribing [chunk] on the speech thread. */
    fun add(chunk: FloatArray) {
        audioMs += chunk.size * 1000L / Recorder.SAMPLE_RATE
        val d = scope.async(Brain.asr) {
            runCatching { transcribe(chunk) }.onFailure { Log.e(TAG, "live chunk failed", it) }.getOrNull()
        }
        synchronized(parts) { parts += d }
    }

    /**
     * The whole transcript: the stretches done while talking plus [tail], the speech after the
     * last pause. Null when a stretch failed, so the caller transcribes the recording in one go.
     */
    suspend fun finish(tail: FloatArray, t: Timing?): String? {
        val waitFrom = Timing.now()
        val done = synchronized(parts) { parts.toList() }.map { it.await() ?: return null }
        t?.apply { liveWait = Timing.now() - waitFrom; liveAudioMs = audioMs }
        val last = if (tail.size >= Recorder.SAMPLE_RATE / 4) Brain.onAsr(t) { transcribe(tail) } else ""
        return (done + last).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
    }

    private companion object { const val TAG = "HearsayLive" }
}
