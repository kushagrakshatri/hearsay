package dev.offlineflow.asr

/** Pluggable speech-to-text engine. Input is 16 kHz mono float PCM in [-1, 1]. */
interface SpeechEngine : AutoCloseable {
    fun transcribe(samples: FloatArray, prompt: String? = null): String
}
