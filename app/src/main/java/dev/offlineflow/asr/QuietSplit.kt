package dev.offlineflow.asr

import kotlin.math.abs

/** Where to cut a long recording in two: the quietest 50 ms within the middle third. */
object QuietSplit {
    fun point(samples: FloatArray, window: Int = 800): Int {
        val lo = samples.size / 3
        val hi = samples.size * 2 / 3 - window
        if (hi <= lo) return samples.size / 2
        var best = lo
        var bestEnergy = Float.MAX_VALUE
        var i = lo
        while (i <= hi) {
            var e = 0f
            for (k in i until i + window step 8) e += abs(samples[k])
            if (e < bestEnergy) { bestEnergy = e; best = i }
            i += window / 2
        }
        return best + window / 2
    }
}
