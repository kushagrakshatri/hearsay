package dev.offlineflow.core

import android.os.SystemClock

/**
 * Where one dictation's time went, from mic release to text in the field: waiting for a model
 * that was still busy (usually a warm-up still loading it), loading a model, speech recognition,
 * the first-use prompt setup, and the cleanup itself. Shown in Settings as "Last dictation" so
 * people can see the slow part without adb.
 */
class Timing {
    val start = now()
    var audioMs = 0L
    var asrWait = 0L; var asrLoad = 0L; var asr = 0L
    var llmWait = 0L; var llmLoad = 0L; var prompt = 0L; var llm = 0L
    /** Speech transcribed while the user was still talking, and the wait for its last piece. */
    var liveAudioMs = 0L; var liveWait = 0L
    var ranLlm = false
    var cloud = false

    /** e.g. "2.4 s total · speech 0.3 s for 4.2 s of audio · loading cleanup model 1.1 s · cleanup 0.8 s" */
    fun line(totalMs: Long = now() - start): String = buildList {
        add("${secs(totalMs)} total")
        if (asrWait >= MIN) add("waiting for speech model ${secs(asrWait)}")
        if (asrLoad >= MIN) add("loading speech model ${secs(asrLoad)}")
        if (liveWait >= MIN) add("finishing speech from before the last pause ${secs(liveWait)}")
        add("speech ${secs(asr)}" + (if (audioMs > 0) " for ${secs(audioMs)} of audio" else "") +
            if (liveAudioMs > 0) " (${secs(liveAudioMs)} done while you talked)" else "")
        if (llmWait >= MIN) add("waiting for cleanup model ${secs(llmWait)}")
        if (llmLoad >= MIN) add("loading cleanup model ${secs(llmLoad)}")
        if (prompt >= MIN) add("first-use prompt setup ${secs(prompt)}")
        add(when {
            cloud -> "cleanup ${secs(llm)} (ChatGPT)"
            ranLlm -> "cleanup ${secs(llm)}"
            else -> "no cleanup model ran"
        })
        val other = totalMs - (liveWait + asrWait + asrLoad + asr + llmWait + llmLoad + prompt + llm)
        if (other >= MIN) add("other ${secs(other)}")
    }.joinToString(" \u00b7 ")

    companion object {
        private const val MIN = 50L
        fun now() = SystemClock.elapsedRealtime()
        fun secs(ms: Long) = if (ms < 1000) "$ms ms" else "%.1f s".format(ms / 1000f)
    }
}
