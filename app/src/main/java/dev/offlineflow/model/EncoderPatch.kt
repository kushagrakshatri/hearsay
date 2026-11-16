package dev.offlineflow.model

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Adds one graph output, the encoder's layer-11 states (`/layers.11/norm_out/LayerNormalization_output_0`), to the
 * pinned Parakeet encoder already on the phone, so nobody downloads 650 MB again. The weights and the normal outputs
 * stay bit-identical; only the ONNX graph gains a 73-byte output entry and its length prefix grows.
 *
 * The patch is two fixed edits on the pinned file (both measured on desktop against `onnx.save` of the same edit,
 * byte-identical): the graph field's length varint at byte 25, and the new ValueInfoProto inserted right after the
 * graph's last output. The original's SHA-256 is checked while reading and the result's while writing; the result
 * goes to a temp file and replaces the original with one rename only when both match. Anything else leaves the
 * original in place, and the engine just runs without layer-11 states.
 */
object EncoderPatch {
    const val ORIGINAL_BYTES = 652_184_296L
    const val ORIGINAL_SHA = "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab"
    const val PATCHED_BYTES = 652_184_369L
    const val PATCHED_SHA = "081b59faed2f708697678cafa990ba2fba247daf3a9b7edc5a3ea003c50ae60c"

    /** A byte range [offset, offset + old.size) of the original replaced by [new] (old empty = insert). */
    class Edit(val offset: Long, val old: ByteArray, val new: ByteArray)

    val EDITS = listOf(
        // ModelProto.graph (field 7) length: 652,183,953 -> 652,184,026 (same 5-byte varint length).
        Edit(25, hex("8b93feb602"), hex("d493feb602")),
        // GraphProto.output (field 12) #3, after "outputs" and "encoded_lengths": name, float, shape [1, "T", 1024].
        Edit(
            651_898_470, ByteArray(0),
            hex(
                "62470a2f2f6c61796572732e31312f6e6f726d5f6f75742f4c617965724e6f726d616c697a6174696f6e5f6f75747075745f30" +
                    "12140a120801120e0a0208010a031201540a03088008",
            ),
        ),
    )

    const val TMP_SUFFIX = ".l11.tmp"
    /** Written when the patch can never work on this file (it isn't the pinned original), so it isn't retried. */
    const val FAILED_SUFFIX = ".l11-failed"
    /** Room kept free beyond the temp copy. */
    private const val SPARE_BYTES = 200_000_000L

    fun isPatched(f: File) = f.length() == PATCHED_BYTES

    /** What the last attempt in this process did, for logs and the debug line. */
    @Volatile var lastOutcome: String = "not tried"
        private set

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Starts the one-time patch on a low-priority background thread when [encoder] is the pinned original. */
    fun startIfNeeded(encoder: File) {
        if (encoder.length() != ORIGINAL_BYTES || File(encoder.path + FAILED_SUFFIX).exists()) return
        if (!running.compareAndSet(false, true)) return
        Thread({
            var threw = false
            try { lastOutcome = apply(encoder) } catch (t: Throwable) {
                lastOutcome = "failed: ${t.message}"; threw = true
                dev.offlineflow.telemetry.Telemetry.problem("encoder patch failed", t)
            } finally { running.set(false) }
            android.util.Log.i("HearsayEncoderPatch", lastOutcome)
            val outcome = lastOutcome
            if (!threw && outcome.startsWith("failed")) dev.offlineflow.telemetry.Telemetry.problem("encoder patch failed", IllegalStateException(outcome))
            else if (!threw) dev.offlineflow.telemetry.Telemetry.step("speech", "encoder patch: $outcome")
        }, "encoder-patch").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    /** Blocking. Returns a short outcome; the original is untouched unless the outcome is "patched". */
    fun apply(encoder: File): String {
        if (isPatched(encoder)) return "already patched"
        if (encoder.length() != ORIGINAL_BYTES) return "skipped: not the pinned encoder"
        val tmp = File(encoder.path + TMP_SUFFIX)
        tmp.delete()
        val dir = encoder.parentFile ?: return "skipped: no directory"
        if (dir.usableSpace < PATCHED_BYTES + SPARE_BYTES) return "skipped: not enough free storage"
        val inSha = MessageDigest.getInstance("SHA-256")
        val outSha = MessageDigest.getInstance("SHA-256")
        try {
            encoder.inputStream().use { input ->
                FileOutputStream(tmp).use { fos ->
                    patch(input, Hashing(fos, outSha), EDITS, inSha)
                    fos.fd.sync()
                }
            }
            val gotIn = inSha.digest().hexString()
            if (gotIn != ORIGINAL_SHA) {
                File(encoder.path + FAILED_SUFFIX).writeText("original sha $gotIn")
                return "failed: the encoder isn't the pinned file"
            }
            val gotOut = outSha.digest().hexString()
            if (tmp.length() != PATCHED_BYTES || gotOut != PATCHED_SHA) {
                File(encoder.path + FAILED_SUFFIX).writeText("patched sha $gotOut")
                return "failed: patched file didn't verify"
            }
            // Still the same original (not deleted or replaced while we copied)? Then swap in one rename.
            if (encoder.length() != ORIGINAL_BYTES) return "skipped: encoder changed while patching"
            check(tmp.renameTo(encoder)) { "rename failed" }
            return "patched"
        } finally {
            tmp.delete()
        }
    }

    /**
     * Streams [input] to [output] with [edits] (sorted by offset) applied, feeding every input byte to [inDigest].
     * Throws when an edit's expected old bytes don't match or the input ends early.
     */
    fun patch(input: InputStream, output: OutputStream, edits: List<Edit>, inDigest: MessageDigest? = null) {
        val src = BufferedInputStream(input, 1 shl 20)
        val buf = ByteArray(1 shl 20)
        var pos = 0L
        fun copy(n: Long) {
            var left = n
            while (left > 0) {
                val r = src.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                if (r < 0) error("input ended at $pos")
                inDigest?.update(buf, 0, r)
                output.write(buf, 0, r); pos += r; left -= r
            }
        }
        for (e in edits) {
            require(e.offset >= pos) { "edits out of order" }
            copy(e.offset - pos)
            if (e.old.isNotEmpty()) {
                val got = ByteArray(e.old.size)
                var k = 0
                while (k < got.size) { val r = src.read(got, k, got.size - k); if (r < 0) error("input ended at $pos"); k += r }
                inDigest?.update(got)
                check(got.contentEquals(e.old)) { "unexpected bytes at $pos" }
                pos += got.size
            }
            output.write(e.new)
        }
        while (true) {
            val r = src.read(buf); if (r < 0) break
            inDigest?.update(buf, 0, r); output.write(buf, 0, r); pos += r
        }
        output.flush()
    }

    private class Hashing(private val out: OutputStream, private val md: MessageDigest) : OutputStream() {
        override fun write(b: Int) { out.write(b); md.update(b.toByte()) }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); md.update(b, off, len) }
        override fun flush() = out.flush()
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun ByteArray.hexString() = joinToString("") { "%02x".format(it) }
}
