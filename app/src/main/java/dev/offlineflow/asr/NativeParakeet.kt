package dev.offlineflow.asr

import java.io.File

/**
 * Hearsay's own Parakeet runner: fbank, encoder and TDT greedy decode in C++ (app/src/main/cpp/parakeet_core.cc) on
 * the ONNX Runtime the sherpa-onnx AAR already ships. Same text as sherpa's recognizer (a line-for-line port), plus
 * token frames and, when the encoder file carries it, the layer-11 states. Not thread-safe: the speech thread only.
 */
class NativeParakeet private constructor(private var handle: Long, private val tokens: ParakeetTokens) : AutoCloseable {

    /** The encoder outputs layer-11 states (the on-phone patched file). */
    val hasLayer11: Boolean get() = handle != 0L && nativeHasLayer11(handle)

    fun run(samples: FloatArray, layer11: Layer11Request? = null): SpeechResult {
        check(handle != 0L) { "NativeParakeet is closed" }
        val want = layer11 != null && (layer11.tailFrames > 0 || layer11.wordSpans)
        val r = checkNotNull(nativeRun(handle, samples, want)) { "native run returned nothing" }
        val frames = r[0]
        val n = r[1]
        val ids = r.copyOfRange(2, 2 + n)
        val tf = r.copyOfRange(2 + n, 2 + 2 * n)
        val dur = r.copyOfRange(2 + 2 * n, 2 + 3 * n)
        val has11 = r[2 + 3 * n] == 1
        val words = tokens.words(ids, tf)
        var tail: FloatArray? = null
        var tailFrom = 0
        var perWord: List<FloatArray>? = null
        if (want && has11) {
            if (layer11!!.tailFrames > 0) {
                tailFrom = maxOf(0, frames - layer11.tailFrames)
                tail = nativeLayer11Rows(handle, tailFrom, frames)
            }
            if (layer11.wordSpans) perWord = words.map { w ->
                nativeLayer11Rows(handle, w.firstFrame, w.lastFrame + 3) ?: FloatArray(0)
            }
        }
        return SpeechResult(
            text = tokens.text(ids), tokens = ids, tokenFrames = tf, durations = dur, words = words, frames = frames,
            layer11Tail = tail, layer11TailFrom = tailFrom, layer11Words = perWord,
            fbankMs = r[3 + 3 * n] / 1000f, encoderMs = r[4 + 3 * n] / 1000f, decodeMs = r[5 + 3 * n] / 1000f,
        )
    }

    override fun close() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
    }

    companion object {
        /** Loads libhearsay_parakeet.so (and with it sherpa's libonnxruntime.so); throws UnsatisfiedLinkError when it can't. */
        private val library: Unit by lazy { System.loadLibrary("hearsay_parakeet") }

        fun load(dir: File, threads: Int): NativeParakeet {
            library
            fun path(name: String) = File(dir, name).also { require(it.exists()) { "Model file missing: ${it.name}" } }.absolutePath
            val tokens = ParakeetTokens.load(File(path("tokens.txt")))
            val h = nativeInit(path("encoder.int8.onnx"), path("decoder.int8.onnx"), path("joiner.int8.onnx"), tokens.size, threads)
            check(h != 0L) { "native Parakeet didn't load" }
            return NativeParakeet(h, tokens)
        }

        @JvmStatic private external fun nativeInit(encoder: String, decoder: String, joiner: String, vocab: Int, threads: Int): Long
        @JvmStatic private external fun nativeHasLayer11(handle: Long): Boolean
        @JvmStatic private external fun nativeRun(handle: Long, samples: FloatArray, wantLayer11: Boolean): IntArray?
        @JvmStatic private external fun nativeLayer11Rows(handle: Long, from: Int, to: Int): FloatArray?
        @JvmStatic private external fun nativeFree(handle: Long)
    }
}

/** tokens.txt ("<piece> <id>" per line, blank last) and the text/word rules sherpa-onnx applies to NeMo tokens. */
class ParakeetTokens(private val pieces: List<String>) {
    val size: Int get() = pieces.size

    /** Concatenate the pieces (▁ → space) and drop the leading space; sherpa's ITN and homophone steps are off in the app. */
    fun text(ids: IntArray): String {
        val s = StringBuilder()
        for (id in ids) s.append(pieces.getOrElse(id) { "" })
        return (if (s.startsWith(" ")) s.substring(1) else s.toString()).trim()
    }

    /** A piece starting with a space starts a new word; the others extend the current one. */
    fun words(ids: IntArray, frames: IntArray): List<WordSpan> {
        val out = ArrayList<WordSpan>()
        var w = StringBuilder(); var f0 = 0; var f1 = 0; var open = false
        for (i in ids.indices) {
            val p = pieces.getOrElse(ids[i]) { "" }
            if (p.startsWith(" ") || !open) {
                if (open && w.isNotBlank()) out += WordSpan(w.toString().trim(), f0, f1)
                w = StringBuilder(p); f0 = frames[i]; f1 = frames[i]; open = true
            } else { w.append(p); f1 = frames[i] }
        }
        if (open && w.isNotBlank()) out += WordSpan(w.toString().trim(), f0, f1)
        return out
    }

    companion object {
        fun parse(lines: List<String>) = ParakeetTokens(
            lines.filter { it.isNotEmpty() }.map { it.substringBeforeLast(' ').replace('\u2581', ' ') },
        )
        fun load(f: File) = parse(f.readLines(Charsets.UTF_8))
    }
}
