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
    /** Prompt tokens the call read before writing (just the new turn when [reusedPrompt]); null when unknown. */
    val promptTokens: Int? = null,
    /** Time spent reading them, in ms; null when unknown. */
    val promptMs: Long? = null,
    /** The call copied the prefilled prompt (true) or read it all again (false); null when the engine doesn't say. */
    val reusedPrompt: Boolean? = null,
    /** Why it read it all again, when [reusedPrompt] is false. */
    val fullPromptWhy: FullPromptWhy? = null,
    /** Copying the prefilled prompt is off for good (after repeated failures). */
    val copyingOff: Boolean = false,
    /** The first try failed and the call was redone on a fresh conversation. */
    val retried: Boolean = false,
    /** Time to copy the prefilled prompt for this call (outside [ms]); null when it wasn't copied or measured. */
    val copyMs: Long? = null,
    /** Runs of the model's fixed-size prefill graph that reading [promptTokens] took; null when unknown. */
    val promptPasses: Int? = null,
    /**
     * What one such run took when the prefilled prompt was first read ([firstReadMs] over its
     * passes): the price of a run with no copying or switching around it. Null when unknown.
     */
    val passMs: Long? = null,
    /** Time spent writing the [tokens] (decode), from the runtime's own clock; null when unknown. */
    val decodeMs: Long? = null,
    /** Part of [ms] outside reading and writing: queueing, switching to the copy, bookkeeping. Null when unknown. */
    val aroundMs: Long? = null,
    /** The prefilled prompt's own first read: tokens and time (no copy then). Null when unknown. */
    val firstReadTokens: Int? = null,
    val firstReadMs: Long? = null,
)
