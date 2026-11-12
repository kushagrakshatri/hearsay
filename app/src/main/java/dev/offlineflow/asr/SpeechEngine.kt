package dev.offlineflow.asr

/** Pluggable speech-to-text engine. Input is 16 kHz mono float PCM in [-1, 1]. */
interface SpeechEngine : AutoCloseable {
    /** [hints]: the user's dictionary (words and names to spell exactly); engines that can bias recognition with them do. */
    fun transcribe(samples: FloatArray, hints: List<String> = emptyList()): String
}
