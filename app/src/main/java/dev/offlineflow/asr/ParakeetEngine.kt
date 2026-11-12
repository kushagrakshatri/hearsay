package dev.offlineflow.asr

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

/**
 * NVIDIA Parakeet TDT 0.6B v2 (int8) through sherpa-onnx / ONNX Runtime, fully on-device.
 * Emits punctuation and capitalization natively. Decodes with beam search so the user's dictionary can be
 * passed in as hotwords per dictation ([Hotwords]).
 */
class ParakeetEngine private constructor(private var recognizer: OfflineRecognizer?) : SpeechEngine {

    override fun transcribe(samples: FloatArray, hints: List<String>): String {
        val text = decode(samples, hints)
        if (text.isNotEmpty() || samples.size < MIN_SPLIT_SAMPLES) return text
        // Beam search sometimes writes nothing for a long recording (seen offline at 30 s and 44 s of speech that
        // greedy decoded fine). Try each half on its own before giving up.
        val cut = QuietSplit.point(samples)
        Log.w(TAG, "empty result for ${samples.size / SAMPLE_RATE} s of audio, decoding in two halves")
        return (decode(samples.copyOfRange(0, cut), hints) + " " + decode(samples.copyOfRange(cut, samples.size), hints)).trim()
    }

    private fun decode(samples: FloatArray, hints: List<String>): String {
        val rec = checkNotNull(recognizer) { "ParakeetEngine is closed" }
        val stream = Hotwords.forStream(hints)?.let { hw ->
            runCatching { rec.createStream(hw) }.onFailure { Log.w(TAG, "hotwords rejected, decoding without them", it) }.getOrNull()
        } ?: rec.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            return rec.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() {
        recognizer?.release()
        recognizer = null
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val TAG = "ParakeetEngine"
        private const val MIN_SPLIT_SAMPLES = 5 * SAMPLE_RATE

        fun load(dir: File): ParakeetEngine {
            fun path(name: String) = File(dir, name).also {
                require(it.exists()) { "Model file missing: ${it.name}" }
            }.absolutePath
            // Hotwords need beam search and a piece vocabulary to cut each word into pieces.
            val bpeVocab = File(dir, "bpe.vocab").also {
                val want = Hotwords.vocabulary(File(dir, "tokens.txt").readText())
                if (!it.exists() || it.length() != want.toByteArray().size.toLong()) it.writeText(want)
            }
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = path("encoder.int8.onnx"),
                        decoder = path("decoder.int8.onnx"),
                        joiner = path("joiner.int8.onnx"),
                    ),
                    tokens = path("tokens.txt"),
                    modelType = "nemo_transducer",
                    modelingUnit = "bpe",
                    bpeVocab = bpeVocab.absolutePath,
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                ),
                decodingMethod = "modified_beam_search",
                maxActivePaths = 4,
                hotwordsScore = Hotwords.SCORE,
            )
            return ParakeetEngine(OfflineRecognizer(assetManager = null, config = config))
        }
    }
}
