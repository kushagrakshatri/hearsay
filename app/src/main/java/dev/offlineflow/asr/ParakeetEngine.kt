package dev.offlineflow.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

/**
 * NVIDIA Parakeet TDT 0.6B v2 (int8) through sherpa-onnx / ONNX Runtime, fully on-device.
 * Emits punctuation and capitalization natively.
 */
class ParakeetEngine private constructor(private var recognizer: OfflineRecognizer?) : SpeechEngine {

    override fun transcribe(samples: FloatArray, prompt: String?): String {
        val rec = checkNotNull(recognizer) { "ParakeetEngine is closed" }
        val stream = rec.createStream()
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

        fun load(dir: File): ParakeetEngine {
            fun path(name: String) = File(dir, name).also {
                require(it.exists()) { "Model file missing: ${it.name}" }
            }.absolutePath
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
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                ),
            )
            return ParakeetEngine(OfflineRecognizer(assetManager = null, config = config))
        }
    }
}
