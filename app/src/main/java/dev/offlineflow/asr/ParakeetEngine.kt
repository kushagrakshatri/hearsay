package dev.offlineflow.asr

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import dev.offlineflow.model.EncoderPatch
import java.io.File

/**
 * NVIDIA Parakeet TDT 0.6B v2 (int8), fully on-device. Emits punctuation and capitalization natively.
 *
 * Runs on Hearsay's own engine ([NativeParakeet]: our C++ on the ONNX Runtime from the sherpa-onnx AAR), which
 * gives the same text as sherpa-onnx's recognizer plus token frames and the encoder's layer-11 states. sherpa-onnx's
 * `OfflineRecognizer` stays for one release: picked in Settings for an A/B, and as the automatic fallback when the
 * own engine fails to load or to run (it then stays on sherpa until the engine is reloaded).
 */
class ParakeetEngine private constructor(
    private val dir: File,
    private val threads: Int,
    private var native: NativeParakeet?,
    private var recognizer: OfflineRecognizer?,
    reason: String,
) : SpeechEngine {

    @Volatile private var label: String = reason
    override val backend: String get() = label

    override fun transcribe(samples: FloatArray, prompt: String?): String =
        transcribeDetailed(samples)?.text ?: sherpaTranscribe(samples)

    override fun transcribeDetailed(samples: FloatArray, layer11: Layer11Request?): SpeechResult? {
        val n = native ?: return null
        return try {
            n.run(samples, layer11).also { r ->
                Log.d(TAG, "own engine: %.2f s audio, fbank %.0f ms, encoder %.0f ms, decode %.0f ms, layer-11 rows %d".format(
                    samples.size / SAMPLE_RATE.toFloat(), r.fbankMs, r.encoderMs, r.decodeMs,
                    (r.layer11Tail?.size ?: 0) / SpeechResult.LAYER11_DIM + (r.layer11Words?.sumOf { it.size } ?: 0) / SpeechResult.LAYER11_DIM))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "own engine failed, switching to sherpa", t)
            runCatching { n.close() }
            native = null
            label = "sherpa (own engine failed: ${short(t)})"
            null
        }
    }

    private fun sherpaTranscribe(samples: FloatArray): String {
        val rec = recognizer ?: sherpa(dir, threads).also { recognizer = it }
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
        native?.close(); native = null
        recognizer?.release(); recognizer = null
    }

    companion object {
        private const val TAG = "HearsayParakeet"
        private const val SAMPLE_RATE = 16_000

        /** [useSherpa]: the Settings switch for the old recognizer. */
        fun load(dir: File, useSherpa: Boolean = false): ParakeetEngine {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val encoder = File(dir, "encoder.int8.onnx")
            val engine = if (useSherpa) {
                ParakeetEngine(dir, threads, null, sherpa(dir, threads), "sherpa (picked in Settings)")
            } else try {
                val n = NativeParakeet.load(dir, threads)
                ParakeetEngine(dir, threads, n, null, if (n.hasLayer11) "own engine" else "own engine, no layer-11 yet")
            } catch (t: Throwable) {
                Log.e(TAG, "own engine didn't load, using sherpa", t)
                ParakeetEngine(dir, threads, null, sherpa(dir, threads), "sherpa (own engine didn't load: ${short(t)})")
            }
            // One-time, in the background: add the layer-11 output to the pinned encoder (used from the next load).
            EncoderPatch.startIfNeeded(encoder)
            return engine
        }

        private fun short(t: Throwable) = (t.message ?: t.javaClass.simpleName).lineSequence().first().take(80)

        private fun sherpa(dir: File, threads: Int): OfflineRecognizer {
            fun path(name: String) = File(dir, name).also {
                require(it.exists()) { "Model file missing: ${it.name}" }
            }.absolutePath
            // Feature size comes from the model's metadata (128 for Parakeet); sherpa overrides any value set here.
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = path("encoder.int8.onnx"),
                        decoder = path("decoder.int8.onnx"),
                        joiner = path("joiner.int8.onnx"),
                    ),
                    tokens = path("tokens.txt"),
                    modelType = "nemo_transducer",
                    numThreads = threads,
                ),
            )
            return OfflineRecognizer(assetManager = null, config = config)
        }
    }
}
