package dev.offlineflow.llm

import java.io.File

/**
 * A small instruction-tuned LLM (GGUF) running on-device through llama.cpp.
 * [prefix] (already chat-formatted: system prompt + few-shot turns) is evaluated once at
 * load and snapshotted, so each [complete] call only pays for its own suffix.
 * Not thread-safe.
 */
class LlamaEngine private constructor(private var handle: Long) : AutoCloseable {

    /** Greedy completion after restoring the cached prefix. Blocking; off the main thread. */
    fun complete(suffix: String, maxTokens: Int): String {
        check(handle != 0L) { "LlamaEngine is closed" }
        return nativeComplete(handle, suffix, maxTokens)
    }

    override fun close() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
    }

    private external fun nativeComplete(handle: Long, suffix: String, maxTokens: Int): String
    private external fun nativeFree(handle: Long)

    companion object {
        init { System.loadLibrary("hearsayllm") }

        fun load(model: File, prefix: String, threads: Int): LlamaEngine {
            require(model.exists()) { "Model not found: ${model.path}" }
            val h = nativeInit(model.absolutePath, threads, prefix)
            check(h != 0L) { "Failed to load ${model.name}" }
            return LlamaEngine(h)
        }

        @JvmStatic private external fun nativeInit(path: String, threads: Int, prefix: String): Long
    }
}
