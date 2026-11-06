package com.skintclub.odyssey.mabel

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Makes speech more monotone by pulling its pitch toward the speaker's middle pitch, keeping
 * its timing. Piper's intonation is learned by the voice model (its noise settings don't
 * flatten it), so it's done on the audio afterwards.
 *
 * The pitch is found every [HOP_SECONDS] by autocorrelation; then TD-PSOLA: two-period
 * slices around each pitch pulse are laid back down a target period apart, so the pitch
 * changes but the voice's timbre and timing don't. Unvoiced sounds are copied as they are.
 */
object PitchFlattener {
    private const val MIN_HZ = 70
    private const val MAX_HZ = 400
    private const val HOP_SECONDS = 0.01
    private const val WINDOW_SECONDS = 0.03

    /** How alike a stretch has to be to itself a period later to count as voiced. */
    private const val VOICED_CORRELATION = 0.6f
    private const val UNVOICED_STEP_SECONDS = 0.005

    /** [strength] 0 leaves the pitch alone; 1 flattens it to one note. */
    fun flatten(samples: FloatArray, sampleRate: Int, strength: Float): FloatArray {
        if (strength <= 0f || samples.isEmpty()) return samples
        val hop = (sampleRate * HOP_SECONDS).toInt()
        val window = (sampleRate * WINDOW_SECONDS).toInt()
        val periods = track(samples, sampleRate, hop, window)
        val voiced = periods.filter { it > 0 }
        if (voiced.size < 10) return samples
        val middleHz = voiced.map { sampleRate.toDouble() / it }.sorted()[voiced.size / 2]

        fun periodAt(t: Int): Int = periods[((t - window / 2) / hop).coerceIn(0, periods.lastIndex)]

        // Analysis marks: a period apart in voiced stretches, each snapped to the strongest
        // peak near where it's expected, so they sit on the pitch pulses.
        val unvoicedStep = (sampleRate * UNVOICED_STEP_SECONDS).toInt()
        val marks = ArrayList<Int>()
        var t = 0
        while (t < samples.size) {
            val p = periodAt(t)
            if (p > 0) {
                var lo = t
                var hi = min(samples.size, t + p)
                val last = marks.lastOrNull()
                if (last != null && periodAt(last) > 0) {
                    lo = last + (0.8 * p).toInt()
                    hi = min(samples.size, last + (1.2 * p).toInt() + 1)
                }
                if (lo >= hi) break
                var m = lo
                for (i in lo until hi) if (samples[i] > samples[m]) m = i
                if (last != null && m <= last) {
                    t++
                    continue
                }
                marks.add(m)
                t = if (m >= t) m + 1 else t + 1
            } else {
                if (marks.lastOrNull() != t) marks.add(t)
                t += unvoicedStep
            }
        }
        if (marks.isEmpty()) return samples

        // Synthesis: walk the output a target period at a time, laying down the nearest
        // analysis mark's slice.
        val out = FloatArray(samples.size + sampleRate / 10)
        val weight = FloatArray(out.size)
        var position = marks[0].toDouble()
        var k = 0
        while (position < samples.size) {
            while (k + 1 < marks.size && abs(marks[k + 1] - position) <= abs(marks[k] - position)) k++
            val mark = marks[k]
            val p = periodAt(mark)
            val step: Double
            val half: Int
            if (p > 0) {
                val hz = sampleRate.toDouble() / p
                val target = exp(ln(hz) + (ln(middleHz) - ln(hz)) * strength)
                step = sampleRate / target
                half = p
            } else {
                step = unvoicedStep.toDouble()
                half = unvoicedStep
            }
            val start = position.roundToInt() - half
            val length = 2 * half + 1
            for (j in 0 until length) {
                val o = start + j
                val s = mark - half + j
                if (o < 0 || o >= out.size) continue
                val w = (0.5 - 0.5 * cos(2 * PI * j / (length - 1))).toFloat()
                if (s in samples.indices) out[o] += samples[s] * w
                weight[o] += w
            }
            position += step
        }
        return FloatArray(samples.size) { i -> out[i] / max(weight[i], 0.1f) }
    }

    /** The pitch period in samples at each hop, 0 where it isn't voiced. */
    private fun track(x: FloatArray, sampleRate: Int, hop: Int, window: Int): IntArray {
        val minLag = sampleRate / MAX_HZ
        val maxLag = sampleRate / MIN_HZ
        val frames = max(1, (x.size - window - maxLag) / hop)
        val periods = IntArray(frames)
        for (f in 0 until frames) {
            val s = f * hop
            var energy = 0.0
            for (i in 0 until window) energy += x[s + i] * x[s + i]
            if (energy / window < 1e-5) continue
            // The lagged window's energy, slid along instead of summed again for each lag.
            var lagEnergy = 0.0
            for (i in 0 until window) lagEnergy += x[s + minLag + i] * x[s + minLag + i]
            var best = 0.0
            var bestLag = 0
            for (lag in minLag..maxLag) {
                var dot = 0.0
                val b = s + lag
                for (i in 0 until window) dot += x[s + i] * x[b + i]
                val c = dot / sqrt(energy * lagEnergy + 1e-12)
                if (c > best) {
                    best = c
                    bestLag = lag
                }
                val next = b + window
                if (next < x.size) lagEnergy += x[next] * x[next] - x[b] * x[b]
            }
            if (best > VOICED_CORRELATION) periods[f] = bestLag
        }
        // A running median of five removes one-off octave jumps.
        return IntArray(frames) { i ->
            if (periods[i] == 0) return@IntArray 0
            val near = (max(0, i - 2)..min(frames - 1, i + 2)).map { periods[it] }.filter { it > 0 }.sorted()
            if (near.size >= 3) near[near.size / 2] else periods[i]
        }
    }
}
