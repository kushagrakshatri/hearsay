package dev.offlineflow.llm

/**
 * What the engine did on its last completion, for the on-phone "Last cleanup" readout
 * (people can't read logcat). Plain words, ready to show.
 */
data class RunStats(
    /** Runtime and chip in plain words: "llama.cpp processor", "LiteRT-LM graphics chip", "LiteRT-LM processor". */
    val engine: String,
    /**
     * Speed-up state: "Faster cleanup on, 78% guesses accepted", "Faster cleanup off",
     * "Faster cleanup fell back to normal", "MTP on", "MTP off (PowerVR GPU)", …
     */
    val speedup: String,
    /** Wall time of the whole completion (the new turn's prompt plus generation), in ms. */
    val ms: Long,
    /** Tokens generated; an estimate when [approx]. */
    val tokens: Int,
    /** Generation speed, or null when it couldn't be measured. */
    val tokensPerSec: Double?,
    /** [tokens] and [tokensPerSec] are estimated from the text length (~4 characters a token). */
    val approx: Boolean = false,
)
