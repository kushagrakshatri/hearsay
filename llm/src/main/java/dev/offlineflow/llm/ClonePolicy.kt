package dev.offlineflow.llm

/**
 * Why a call read its whole prompt instead of copying the prefilled one, in plain words for the
 * "Last cleanup" readout.
 */
enum class FullPromptWhy(val words: String) {
    /** A per-call note (names to spell) changes the system prompt, so the prefilled copy doesn't fit. */
    NAMES_NOTE("names note"),
    /** Copying the prefilled conversation, or writing on the copy, failed. */
    CLONE_ERROR("copy error"),
    /** A new conversation couldn't open beside the prefilled one (some GPU builds allow one at a time). */
    OPEN_FAILED("open failed"),
    /** The prompt couldn't be prefilled. */
    NO_BASE("no prefilled prompt"),
}

/**
 * When to stop copying the prefilled prompt (LiteRT-LM [Conversation] clones). One failure only
 * means the prompt is prepared again on the next call; copying stays off for the engine's life
 * only after [maxFailures] failures in a row. Pure logic, so it's unit tested.
 */
class ClonePolicy(private val maxFailures: Int = 3) {
    /** Copying is still in use. False for good once [maxFailures] failures came in a row. */
    var on = true; private set
    /** Failures since the last call that copied the prompt and finished. */
    var failuresInARow = 0; private set
    /** What the last failure was; kept to explain why copying is off. */
    var lastFailure: FullPromptWhy? = null; private set

    /** A call copied the prompt and finished. */
    fun succeeded() { failuresInARow = 0 }

    /** Records a failure; true when that turned copying off for good. */
    fun failed(why: FullPromptWhy): Boolean {
        lastFailure = why
        failuresInARow++
        if (on && failuresInARow >= maxFailures) { on = false; return true }
        return false
    }
}
