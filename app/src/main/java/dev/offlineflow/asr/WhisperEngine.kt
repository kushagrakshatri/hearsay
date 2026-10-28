package dev.offlineflow.asr

import java.io.File

/** whisper.cpp running fully on-device through JNI. */
class WhisperEngine private constructor(private var handle: Long) : SpeechEngine {

    override fun transcribe(samples: FloatArray, prompt: String?): String {
        check(handle != 0L) { "WhisperEngine is closed" }
        return nativeTranscribe(handle, samples, prompt).trim()
    }

    override fun close() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
    }

    private external fun nativeTranscribe(handle: Long, samples: FloatArray, prompt: String?): String
    private external fun nativeFree(handle: Long)

    companion object {
        init { System.loadLibrary("offlineflow") }

        fun load(model: File): WhisperEngine {
            require(model.exists()) { "Model not found: ${model.path}" }
            val h = nativeInit(model.absolutePath)
            check(h != 0L) { "Failed to load model ${model.name}" }
            return WhisperEngine(h)
        }

        @JvmStatic private external fun nativeInit(path: String): Long
    }
}
