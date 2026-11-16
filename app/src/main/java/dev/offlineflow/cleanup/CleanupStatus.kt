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
        firstRead(s)?.let { add(it) }
    }.joinToString(" \u00b7 ")

    /** The prefilled prompt's own first read, the yardstick for a pass: "prompt first read: 403 tokens in 1.0 s (4 passes)". */
    fun firstRead(s: RunStats): String? {
        val tokens = s.firstReadTokens ?: return null
        val ms = s.firstReadMs ?: return null
        val passes = dev.offlineflow.llm.PrefillPasses.count(tokens, greedy = !s.engine.endsWith("graphics chip"))
        return "prompt first read: $tokens tokens in ${secs(ms)} (${plural(passes, "pass", "passes")})"
    }

    /**
     * Whether the call reused the prefilled prompt, short and plain: "reused prompt, 28 tokens read
     * in 0.4 s", or "re-read prompt (430 tokens) in 1.8 s, copying off: copy error". Null when the
     * engine doesn't say (llama.cpp).
     */
    fun prompt(s: RunStats): String? {
        val reused = s.reusedPrompt ?: return null
        val time = s.promptMs?.let { " in ${secs(it)}" } ?: ""
        if (reused) return "reused prompt" + (s.copyMs?.let { " (copied in ${secs(it)})" } ?: "") +
            (s.promptTokens?.let { ", $it tokens read" } ?: "") + time + split(s) + after(s)
        val why = s.fullPromptWhy
        return "re-read prompt" + (s.promptTokens?.let { " ($it tokens)" } ?: "") + time + split(s) + when {
            s.retried -> ", redone after a copy error"
            why == FullPromptWhy.NAMES_NOTE -> ", names note"
            why == null -> ""
            s.copyingOff -> ", copying off: ${why.words}"
            else -> ", copying skipped: ${why.words}"
        } + (if (s.retried && s.copyingOff) ", copying off" else "") + after(s)
    }

    /**
     * The prompt read split into the model's fixed-size passes and the rest ("setup"): each pass
     * is priced at what one took when the prompt was first read, with no copy around it.
     * " = setup ~727 ms + 1 pass ~240 ms", or " (1 pass)" when there's no yardstick.
     */
    private fun split(s: RunStats): String {
        val passes = s.promptPasses?.takeIf { it > 0 } ?: return ""
        val read = s.promptMs ?: return ""
        val each = s.passMs ?: return " (${plural(passes, "pass", "passes")})"
        val setup = (read - passes * each).coerceAtLeast(0)
        return " = setup ~${secs(setup)} + ${plural(passes, "pass", "passes")} ~${secs(passes * each)}"
    }

    /** What came after reading: "; wrote 24 tokens in 1.1 s; 0.8 s outside reading and writing". */
    private fun after(s: RunStats): String = buildString {
        s.decodeMs?.let { append("; wrote ${s.tokens} tokens in ${secs(it)}") }
        s.aroundMs?.let { append("; ${secs(it)} outside reading and writing") }
    }

    private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

    private fun secs(ms: Long) = if (ms < 1000) "$ms ms" else "%.1f s".format(java.util.Locale.US, ms / 1000f)
}
