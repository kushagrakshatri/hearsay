package dev.offlineflow.audio

import kotlin.math.floor
import kotlin.math.log10

/**
 * Finds where to cut live speech when the VAD doesn't close a pause. In any noise or reverb
 * Silero's probability stays at 0.3–0.7 through short pauses, so long readings got no live piece
 * at all, but the frame energy still drops 15–30 dB there (tools/eval/turn_detect, Nov 12, 2026).
 *
 * Keeps 32 ms frame energies (dB) for the whole recording. Speech level = 90th percentile of the
 * last 4 s, noise floor = 5th percentile of the last 8 s. A frame is a pause frame when it is under
 * min(level − 12, max(floor + 8, level − 40)) dB.
 * - Volume cut: once ≥ [VOLUME_AFTER_S] of speech is unhanded, a run of ≥ 200 ms of pause frames
 *   ending at the newest frame is cut at its second-newest frame (inside the pause, not in a weak
 *   word ending).
 * - Forced cut: once ≥ [FORCED_AFTER_S] is unhanded, cut at the quietest 96 ms in the unhanded
 *   speech, skipping its first 2 s and the newest 100 ms.
 * Pure Kotlin so it runs in JVM tests; fed from the mic thread only.
 */
class PauseCutter {
    enum class Kind { PAUSE, VOLUME, FORCED }

    private var db = DoubleArray(1024)
    private var frames = 0
    private val partial = FloatArray(FRAME)
    private var partialN = 0

    /** Samples added so far. */
    var seen = 0L
        private set

    fun add(x: FloatArray) {
        seen += x.size
        var i = 0
        while (i < x.size) {
            val n = minOf(FRAME - partialN, x.size - i)
            x.copyInto(partial, partialN, i, i + n)
            partialN += n; i += n
            if (partialN == FRAME) {
                var s = 0.0
                for (v in partial) s += v.toDouble() * v
                if (frames == db.size) db = db.copyOf(db.size * 2)
                db[frames++] = 10 * log10(s / FRAME + 1e-10)
                partialN = 0
            }
        }
    }

    /**
     * Where to cut (a sample offset) when the unhanded speech starts at sample [unhandedStart],
     * or null to keep listening.
     */
    fun cutAt(unhandedStart: Long): Pair<Long, Kind>? {
        val span = (seen - unhandedStart).toDouble() / SR
        if (span < VOLUME_AFTER_S) return null
        val now = frames
        if (now >= 2) {
            val level = percentile(maxOf(0, now - 4 * SR / FRAME), now, 90.0)
            val floor = percentile(maxOf(0, now - 8 * SR / FRAME), now, 5.0)
            val thr = minOf(level - 12, maxOf(floor + 8, level - 40))
            var k = now - 1; var n = 0
            val first = floorDiv(unhandedStart, FRAME.toLong())
            while (k >= first && k >= 0 && db[k] < thr) { k--; n++ }
            if (n > 0 && n.toDouble() * FRAME / SR >= PAUSE_S) {
                val f = maxOf(k + 1, now - 2)
                return f.toLong() * FRAME + FRAME / 2 to Kind.VOLUME
            }
        }
        if (span < FORCED_AFTER_S) return null
        val a = ((unhandedStart + 2L * SR) / FRAME).toInt()
        val b = minOf(((seen - SR / 10) / FRAME).toInt(), now)
        if (b - a < 3) return null
        // 3-frame mean, zero-padded at the edges (numpy convolve "same"), first minimum.
        var best = a; var bestV = Double.MAX_VALUE
        for (j in a until b) {
            var s = db[j]
            if (j - 1 >= a) s += db[j - 1]
            if (j + 1 < b) s += db[j + 1]
            val v = s / 3
            if (v < bestV) { bestV = v; best = j }
        }
        return best.toLong() * FRAME + FRAME / 2 to Kind.FORCED
    }

    /** numpy.percentile (linear) over frames [from, to). */
    private fun percentile(from: Int, to: Int, p: Double): Double {
        val a = db.copyOfRange(from, to).also { it.sort() }
        val pos = p / 100 * (a.size - 1)
        val lo = floor(pos).toInt(); val hi = minOf(lo + 1, a.size - 1)
        return a[lo] + (a[hi] - a[lo]) * (pos - lo)
    }

    private fun floorDiv(a: Long, b: Long) = Math.floorDiv(a, b).toInt()

    companion object {
        const val SR = 16_000
        /** 32 ms, aligned with the VAD's 512-sample windows. */
        const val FRAME = 512
        const val VOLUME_AFTER_S = 4.0
        const val PAUSE_S = 0.2
        const val FORCED_AFTER_S = 8.0

        /**
         * The audio of speech [ranges] (each padded by [pad], merged, like SpeechGate.cut) that
         * falls in [from, to). Consecutive cuts at the same offset split it with nothing lost or
         * repeated.
         */
        fun slice(audio: FloatArray, ranges: List<LongRange>, from: Long = 0, to: Long = audio.size.toLong(), pad: Int = 4000): FloatArray {
            val lo = maxOf(0L, from); val hi = minOf(audio.size.toLong(), to)
            if (lo >= hi || ranges.isEmpty()) return FloatArray(0)
            val merged = ArrayList<LongArray>()
            for (r in ranges.sortedBy { it.first }) {
                val a = maxOf(lo, r.first - pad); val b = minOf(hi, r.last + 1 + pad)
                if (a >= b) continue
                val last = merged.lastOrNull()
                if (last != null && a <= last[1]) last[1] = maxOf(last[1], b) else merged += longArrayOf(a, b)
            }
            val out = FloatArray(merged.sumOf { (it[1] - it[0]).toInt() })
            var i = 0
            for (m in merged) { audio.copyInto(out, i, m[0].toInt(), m[1].toInt()); i += (m[1] - m[0]).toInt() }
            return out
        }

        /** "3 pause, 2 volume, 1 forced", leaving out kinds that didn't happen. */
        fun describe(counts: IntArray): String =
            Kind.entries.mapIndexedNotNull { i, k -> counts.getOrElse(i) { 0 }.takeIf { it > 0 }?.let { "$it ${k.name.lowercase()}" } }
                .joinToString(", ")
    }
}
