package com.steptrackerpro.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A few numbers that describe how the phone was moving over a short window,
 * for a server that wants to tell a walk from a phone being shaken - and
 * nothing that could reconstruct the movement itself.
 *
 * The signal is the accelerometer magnitude with its mean removed, so
 * orientation and gravity drop out. From it:
 *
 *  - **dominantFrequencyHz** - where the energy sits. Gait is 1.2-2.5 Hz at
 *    the stride; a hand shake is 3-6 Hz; a car is a broad smear. Found by a
 *    direct Fourier evaluation at the sample's own timestamps between
 *    [MIN_HZ] and [MAX_HZ], which needs no resampling and so does not care
 *    whether the hub delivered 20 Hz or 50 Hz or a batch.
 *  - **variance** - how hard. A walk with the phone in a pocket is a few
 *    (m/s²)²; a shake is tens.
 *  - **zeroCrossingRate** - crossings of the mean per second, roughly twice
 *    the dominant frequency for a clean oscillation, and much higher when
 *    the signal is noise.
 *  - **peakRatio** - the share of in-band energy at the dominant frequency:
 *    near 1 for a metronomic shake, lower for a walk with its harmonics,
 *    near 0 for noise or a still phone.
 *
 * Raw samples never leave [MotionWindowSampler]; only [MotionFeatures] does.
 * Pure Kotlin, tested on the JVM with synthetic traces.
 */
data class MotionFeatures(
    /** Epoch ms the window opened. */
    val startedAt: Long,
    /** How long it actually ran, ms. */
    val durationMs: Long,
    val sampleCount: Int,
    /** 0 when the phone was effectively still. */
    val dominantFrequencyHz: Double,
    /** Of the mean-removed magnitude, (m/s²)². */
    val variance: Double,
    /** Mean crossings per second. */
    val zeroCrossingRate: Double,
    /** Share of in-band energy at [dominantFrequencyHz], 0..1. */
    val peakRatio: Double,
    /** Steps this device counted while the window was open. */
    val stepsDuringWindow: Int
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "startedAt" to startedAt,
        "durationMs" to durationMs,
        "sampleCount" to sampleCount,
        "dominantFrequencyHz" to dominantFrequencyHz,
        "variance" to variance,
        "zeroCrossingRate" to zeroCrossingRate,
        "peakRatio" to peakRatio,
        "stepsDuringWindow" to stepsDuringWindow
    )
}

/**
 * Collects accelerometer samples for one window and reduces them to
 * [MotionFeatures]. Holds at most [MAX_SAMPLES] magnitudes, in memory, and
 * discards them once [features] has been taken.
 */
class MotionWindowSampler(
    val startedAt: Long,
    private val windowMs: Long
) {
    private val magnitudes = ArrayList<Float>(MAX_SAMPLES)
    private val times = ArrayList<Long>(MAX_SAMPLES)

    /** True once a sample at or past the window's end has been seen. */
    @Volatile
    var isComplete: Boolean = false
        private set

    /**
     * @param timestampMs any monotonic millisecond base; only differences
     *   within the window are used.
     * @return true when this sample closed the window.
     */
    fun add(x: Float, y: Float, z: Float, timestampMs: Long): Boolean {
        if (isComplete) return false
        if (magnitudes.size < MAX_SAMPLES) {
            magnitudes.add(sqrt(x * x + y * y + z * z))
            times.add(timestampMs)
        }
        val first = times.firstOrNull() ?: return false
        if (timestampMs - first >= windowMs) isComplete = true
        return isComplete
    }

    /**
     * Reduces what was collected. Safe to call on a short window (a sensor
     * that stopped delivering); the numbers describe what there was.
     */
    fun features(stepsDuringWindow: Int): MotionFeatures {
        val n = magnitudes.size
        val duration = if (n >= 2) times[n - 1] - times[0] else 0L
        if (n < MIN_SAMPLES || duration <= 0L) {
            return MotionFeatures(startedAt, duration, n, 0.0, 0.0, 0.0, 0.0, stepsDuringWindow)
        }
        val mean = magnitudes.sumOf { it.toDouble() } / n
        val x = DoubleArray(n) { magnitudes[it] - mean }
        val variance = x.sumOf { it * it } / n

        var crossings = 0
        for (i in 1 until n) if ((x[i - 1] < 0.0) != (x[i] < 0.0)) crossings++
        val seconds = duration / 1000.0
        val zeroCrossingRate = crossings / seconds

        // A still phone has nothing to find a frequency in: the noise floor
        // would otherwise pick an arbitrary bin and report it with a straight
        // face. Below the floor every spectral figure is 0.
        if (variance < STILL_VARIANCE) {
            return MotionFeatures(startedAt, duration, n, 0.0, variance, zeroCrossingRate, 0.0, stepsDuringWindow)
        }

        // One bin every BIN_HZ across the band: a walk and a shake sit far
        // enough apart that finer resolution buys nothing.
        val peak = MotionBand.peak(x, LongArray(n) { times[it] }, n, MIN_HZ, MAX_HZ, BIN_HZ)
        magnitudes.clear()
        times.clear()
        return MotionFeatures(
            startedAt = startedAt,
            durationMs = duration,
            sampleCount = n,
            dominantFrequencyHz = peak.hz,
            variance = variance,
            zeroCrossingRate = zeroCrossingRate,
            peakRatio = peak.ratio,
            stepsDuringWindow = stepsDuringWindow
        )
    }

    companion object {
        /** Sixty seconds at 50 Hz; anything past it is dropped, not resampled. */
        const val MAX_SAMPLES = 3_000

        /** Fewer than this and there is no spectrum worth reporting. */
        const val MIN_SAMPLES = 16

        /** Below this variance the phone is still: 0.1 m/s² RMS, under sensor noise on a table. */
        const val STILL_VARIANCE = 0.01

        /** Slowest gait worth a frequency. */
        const val MIN_HZ = 0.5

        /** Above a sprint's cadence and its first harmonic; a shake sits inside. */
        const val MAX_HZ = 6.0

        const val BIN_HZ = 0.05
    }
}

/** Where a band's energy sits, and how much of the band's energy is at that one frequency. */
internal data class SpectralPeak(val hz: Double, val ratio: Double)

/**
 * A direct Fourier evaluation at the samples' own timestamps, so an irregular
 * or batched delivery is used exactly as it came: nothing is resampled, and it
 * does not matter whether the hub delivered 20 Hz, 50 Hz, or a second's batch
 * all at once.
 *
 * Used by [MotionWindowSampler], which scans the whole motion band for a
 * signature a server can judge. (The 2.8 motion gate scanned the gait band
 * with it too; [WalkWitness], which replaced that classifier, compares the
 * signal with itself instead.)
 */
internal object MotionBand {

    /**
     * @param values the signal with its mean already removed.
     * @param times each sample's time, any monotonic millisecond base; only
     *   differences are used.
     * @param n how many of [values] and [times] to read.
     * @return the strongest frequency between [minHz] and [maxHz], and its
     *   share of the energy found in that band: near 1 for a pure tone, lower
     *   for a rhythm with harmonics, near 0 for broadband noise.
     */
    fun peak(
        values: DoubleArray,
        times: LongArray,
        n: Int,
        minHz: Double,
        maxHz: Double,
        binHz: Double
    ): SpectralPeak {
        val power = spectrum(values, times, n, minHz, maxHz, binHz)
        if (power.isEmpty()) return SpectralPeak(0.0, 0.0)
        var best = 0
        var totalPower = 0.0
        for (i in power.indices) {
            totalPower += power[i]
            if (power[i] > power[best]) best = i
        }
        val bestHz = if (power[best] > 0.0) minHz + best * binHz else 0.0
        val ratio = if (totalPower > 0.0) (power[best] / totalPower).coerceIn(0.0, 1.0) else 0.0
        return SpectralPeak(bestHz, ratio)
    }

    /**
     * The power at every [binHz] from [minHz] to [maxHz]: index `i` is
     * `minHz + i * binHz`. Empty when there is nothing to evaluate. For a
     * caller that has to look at more than the one strongest line - the gate
     * asks whether that line is a step, a stride or a step's harmonic.
     */
    fun spectrum(
        values: DoubleArray,
        times: LongArray,
        n: Int,
        minHz: Double,
        maxHz: Double,
        binHz: Double
    ): DoubleArray {
        if (n <= 0 || binHz <= 0.0 || maxHz < minHz) return DoubleArray(0)
        val bins = ((maxHz - minHz) / binHz + 1e-9).toInt() + 1
        val out = DoubleArray(bins)
        val t0 = times[0]
        for (b in 0 until bins) {
            val hz = minHz + b * binHz
            var re = 0.0
            var im = 0.0
            val w = 2.0 * PI * hz / 1000.0
            for (i in 0 until n) {
                val phase = w * (times[i] - t0)
                re += values[i] * cos(phase)
                im -= values[i] * sin(phase)
            }
            out[b] = re * re + im * im
        }
        return out
    }
}
