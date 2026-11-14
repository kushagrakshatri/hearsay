package dev.offlineflow.asr

/** Pluggable speech-to-text engine. Input is 16 kHz mono float PCM in [-1, 1]. */
interface SpeechEngine : AutoCloseable {
    fun transcribe(samples: FloatArray, prompt: String? = null): String

    /**
     * The text plus what the recognizer saw: tokens with their encoder frames, words with frame spans, and the
     * encoder's layer-11 states when [layer11] asks for them and the model has them. Null when the engine can't
     * say more than the text (Whisper, Parakeet on sherpa-onnx); then use [transcribe].
     */
    fun transcribeDetailed(samples: FloatArray, layer11: Layer11Request? = null): SpeechResult? = null

    /** Which runtime ran the last transcription, in plain words for Settings ("own engine", "sherpa ..."); null = n/a. */
    val backend: String? get() = null
}

/** Which layer-11 rows to hand back: the last [tailFrames] frames, and/or each word's frames plus 2 after. */
data class Layer11Request(val tailFrames: Int = 0, val wordSpans: Boolean = false)

/** One word and the encoder frames (80 ms each) of its first and last token. */
data class WordSpan(val word: String, val firstFrame: Int, val lastFrame: Int)

class SpeechResult(
    val text: String,
    /** Token ids (blank excluded), the encoder frame each was emitted at, and its TDT duration index. */
    val tokens: IntArray,
    val tokenFrames: IntArray,
    val durations: IntArray,
    val words: List<WordSpan>,
    /** Encoder frames in the piece (80 ms each). */
    val frames: Int,
    /** Layer-11 rows for the last frames, row-major × [LAYER11_DIM], starting at frame [layer11TailFrom]; null if not asked or not available. */
    val layer11Tail: FloatArray? = null,
    val layer11TailFrom: Int = 0,
    /** Per word in [words]: rows [firstFrame, lastFrame + 2] × [LAYER11_DIM] (clipped to the piece); null if not asked or not available. */
    val layer11Words: List<FloatArray>? = null,
    /** Where the time went, in ms: features, encoder, decoder loop. */
    val fbankMs: Float = 0f,
    val encoderMs: Float = 0f,
    val decodeMs: Float = 0f,
) {
    companion object { const val LAYER11_DIM = 1024 }
}
