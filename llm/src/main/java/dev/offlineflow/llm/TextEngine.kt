package dev.offlineflow.llm

/**
 * A loaded on-device language model that answers chat-style prompts: a fixed system prompt
 * with few-shot pairs (prepared once and cached), then one short user turn per call.
 * Implemented by llama.cpp (CPU, GGUF) and LiteRT-LM (GPU or CPU, .litertlm). Not thread-safe.
 */
interface TextEngine : AutoCloseable {
    /** Runtime and processor in use, e.g. "LiteRT-LM GPU" or "llama.cpp CPU". */
    val backend: String

    /** How many prompts have been prepared; each holds cached model state in memory. */
    val preparedCount: Int

    /** Evaluates [system] plus [examples] (user, assistant, user, assistant, …) once; returns a handle. */
    fun prepare(system: String, examples: List<String>): Int

    /**
     * Greedy completion of [user] after prepared prompt [handle]. [note] is an extra system note
     * for this call only (e.g. names to spell exactly). Blocking; call off the main thread.
     */
    fun complete(handle: Int, note: String?, user: String, maxTokens: Int): String

    /** What the last [complete] did (chip, speed-up, timing); null before the first call. */
    val lastRun: RunStats? get() = null
}
