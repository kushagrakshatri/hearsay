package dev.offlineflow.cleanup

import dev.offlineflow.llm.FullPromptWhy
import dev.offlineflow.llm.RunStats
import kotlin.math.roundToInt

/**
 * The on-phone "Last cleanup" readout in Settings → Cleanup: which model ran, on which engine and
 * chip, whether its speed-up was in use, how long it took, and whether it reused the prefilled
 * prompt. Built from the engine's own numbers ([RunStats]), not from logs, so people can check it
 * without adb.
 */
object CleanupStatus {
    /**
     * e.g. "Qwen3.5 2B · llama.cpp processor · Faster cleanup on, 78% guesses accepted · 640 ms · 21 tokens/s".
     * [piece]: the run cleaned one live piece, so the prompt part reads "last piece: …".
     */
    fun line(model: String, s: RunStats, piece: Boolean = false): String = buildList {
        if (model.isNotBlank()) add(model)
        add(s.engine)
        add(s.speedup)
        add("${s.ms} ms")
        s.tokensPerSec?.let { add((if (s.approx) "~" else "") + "${it.roundToInt()} tokens/s") }
        prompt(s)?.let { add(if (piece) "last piece: $it" else it) }
    }.joinToString(" \u00b7 ")

    /**
     * Whether the call reused the prefilled prompt, short and plain: "reused prompt, 28 tokens read
     * in 0.4 s", or "re-read prompt (430 tokens) in 1.8 s, copying off: copy error". Null when the
     * engine doesn't say (llama.cpp).
     */
    fun prompt(s: RunStats): String? {
        val reused = s.reusedPrompt ?: return null
        val time = s.promptMs?.let { " in ${secs(it)}" } ?: ""
        if (reused) return "reused prompt" + (s.promptTokens?.let { ", $it tokens read" } ?: "") + time
        val why = s.fullPromptWhy
        return "re-read prompt" + (s.promptTokens?.let { " ($it tokens)" } ?: "") + time + when {
            s.retried -> ", redone after a copy error"
            why == FullPromptWhy.NAMES_NOTE -> ", names note"
            why == null -> ""
            s.copyingOff -> ", copying off: ${why.words}"
            else -> ", copying skipped: ${why.words}"
        } + if (s.retried && s.copyingOff) ", copying off" else ""
    }

    private fun secs(ms: Long) = if (ms < 1000) "$ms ms" else "%.1f s".format(java.util.Locale.US, ms / 1000f)
}
