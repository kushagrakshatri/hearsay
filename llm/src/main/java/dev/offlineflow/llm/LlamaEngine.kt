package dev.offlineflow.llm

import java.io.File

/**
 * A small instruction-tuned LLM (GGUF) running on-device through llama.cpp.
 * The system prompt and few-shot [examples] are evaluated once at load and cached,
 * so each [complete] call only pays for the new user turn. Not thread-safe.
 */
class LlamaEngine private constructor(private var handle: Long) : AutoCloseable {

    /** Greedy completion of one user turn. Blocking; call off the main thread. */
    fun complete(user: String, maxTokens: Int): String {
        check(handle != 0L) { "LlamaEngine is closed" }
        return nativeComplete(handle, user, maxTokens).trim()
    }

    override fun close() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
    }

    private external fun nativeComplete(handle: Long, user: String, maxTokens: Int): String
    private external fun nativeFree(handle: Long)

    companion object {
        init { System.loadLibrary("hearsayllm") }

        /** [examples] alternate user, assistant, user, assistant… */
        fun load(model: File, system: String, examples: List<String>, threads: Int): LlamaEngine {
            require(model.exists()) { "Model not found: ${model.path}" }
            require(examples.size % 2 == 0) { "examples must be user/assistant pairs" }
            val h = nativeInit(model.absolutePath, threads, system, examples.toTypedArray())
            check(h != 0L) { "Failed to load ${model.name}" }
            return LlamaEngine(h)
        }

        @JvmStatic private external fun nativeInit(
            path: String, threads: Int, system: String, examples: Array<String>,
        ): Long
    }
}
