package dev.offlineflow.cleanup

import dev.offlineflow.llm.RunStats
import kotlin.math.roundToInt

/**
 * The on-phone "Last cleanup" readout in Settings → Cleanup: which model ran, on which engine and
 * chip, whether its speed-up was in use, and how long it took. Built from the engine's own
 * numbers ([RunStats]), not from logs, so people can check it without adb.
 */
object CleanupStatus {
    /** e.g. "Qwen3.5 2B · llama.cpp processor · Faster cleanup on, 78% guesses accepted · 640 ms · 21 tokens/s" */
    fun line(model: String, s: RunStats): String = buildList {
        if (model.isNotBlank()) add(model)
        add(s.engine)
        add(s.speedup)
        add("${s.ms} ms")
        s.tokensPerSec?.let { add((if (s.approx) "~" else "") + "${it.roundToInt()} tokens/s") }
    }.joinToString(" \u00b7 ")
}
