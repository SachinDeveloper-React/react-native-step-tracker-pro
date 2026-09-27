package com.steptrackerpro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The contract: features from a walk and features from a shake are far
 * enough apart that a server can tell them apart without ever seeing the
 * trace, and a still phone produces nothing to misread.
 */
class MotionSignatureTest {

    private val gravity = 9.81f

    /**
     * Feeds [seconds] of a magnitude signal at [rateHz]. The phone is upright
     * with the motion along z, so magnitude is gravity plus the motion; the
     * sampler removes the mean itself.
     */
    private fun run(
        rateHz: Double,
        seconds: Double,
        steps: Int = 0,
        signal: (tSeconds: Double) -> Double
    ): MotionFeatures {
        val sampler = MotionWindowSampler(startedAt = 1_000L, windowMs = (seconds * 1000).toLong())
        val dtMs = 1000.0 / rateHz
        var t = 0.0
        var closed = false
        while (!closed) {
            val z = gravity + signal(t / 1000.0).toFloat()
            closed = sampler.add(0f, 0f, z, 1_000L + t.toLong())
            t += dtMs
        }
        return sampler.features(steps)
    }

    /** A walk: stride at 1.8 Hz, a smaller second harmonic, ~2 m/s² peaks. */
    private fun walking(t: Double) =
        2.0 * sin(2 * PI * 1.8 * t) + 0.6 * sin(2 * PI * 3.6 * t + 0.4)

    /** A hand shake: 4 Hz, hard, nearly pure. */
    private fun shaking(t: Double) = 8.0 * sin(2 * PI * 4.0 * t)

    @Test
    fun `a walk reads at its stride frequency`() {
        val walk = run(25.0, 10.0, steps = 18, ::walking)
        assertEquals(1.8, walk.dominantFrequencyHz, 0.1)
        // Twice the frequency for a clean oscillation, give or take the harmonic.
        assertEquals(3.6, walk.zeroCrossingRate, 0.6)
        assertEquals(18, walk.stepsDuringWindow)
        assertTrue(walk.variance in 1.0..4.0)
        assertTrue(walk.sampleCount > 200)
        assertTrue(walk.durationMs >= 10_000L)
    }

    @Test
    fun `a shake reads at its own frequency, harder and purer`() {
        val shake = run(25.0, 10.0, signal = ::shaking)
        assertEquals(4.0, shake.dominantFrequencyHz, 0.1)
        assertEquals(8.0, shake.zeroCrossingRate, 1.0)
        assertTrue(shake.variance > 20.0)
        assertTrue(shake.peakRatio > 0.5)
    }

    @Test
    fun `walking and shaking separate on every axis a server would use`() {
        val walk = run(25.0, 10.0, steps = 18, ::walking)
        val shake = run(25.0, 10.0, steps = 20, ::shaking)
        // Frequency is the headline: more than 2 Hz apart.
        assertTrue(shake.dominantFrequencyHz - walk.dominantFrequencyHz > 2.0)
        // Amplitude: an order of magnitude in variance.
        assertTrue(shake.variance > 5 * walk.variance)
        // Cadence: crossings track the frequency.
        assertTrue(shake.zeroCrossingRate > 1.8 * walk.zeroCrossingRate)
        // Purity: a shake is more tonal than a walk with its harmonic.
        assertTrue(shake.peakRatio > walk.peakRatio)
    }

    @Test
    fun `the sample rate does not move the answer`() {
        val slow = run(20.0, 10.0, signal = ::walking)
        val fast = run(50.0, 10.0, signal = ::walking)
        assertEquals(slow.dominantFrequencyHz, fast.dominantFrequencyHz, 0.1)
        assertEquals(slow.variance, fast.variance, 0.3)
        assertEquals(slow.zeroCrossingRate, fast.zeroCrossingRate, 0.6)
    }

    @Test
    fun `a still phone with sensor noise reports no frequency`() {
        var seed = 7L
        val still = run(25.0, 10.0) {
            // Deterministic noise, ±0.03 m/s²: a phone on a table.
            seed = seed * 6364136223846793005L + 1442695040888963407L
            ((seed ushr 33) % 1000 - 500) / 500.0 * 0.03
        }
        assertEquals(0.0, still.dominantFrequencyHz, 0.0)
        assertEquals(0.0, still.peakRatio, 0.0)
        assertTrue(still.variance < MotionWindowSampler.STILL_VARIANCE)
        // Noise crosses the mean constantly; that is reported as it is.
        assertTrue(still.zeroCrossingRate > 5.0)
    }

    @Test
    fun `a window closes at its length and keeps nothing afterwards`() {
        val sampler = MotionWindowSampler(startedAt = 0L, windowMs = 2_000L)
        var i = 0L
        var closed = false
        while (!closed) {
            closed = sampler.add(0f, 0f, gravity + sin(i / 40.0 * 2 * PI * 1.8).toFloat(), i * 40L)
            i++
        }
        assertTrue(sampler.isComplete)
        // Late samples are refused.
        assertTrue(!sampler.add(0f, 0f, gravity, 10_000L))
        val features = sampler.features(3)
        assertEquals(2_000L, features.durationMs)
        assertEquals(51, features.sampleCount)
        assertEquals(3, features.stepsDuringWindow)
    }

    @Test
    fun `too few samples is an honest empty answer`() {
        val sampler = MotionWindowSampler(startedAt = 0L, windowMs = 10_000L)
        repeat(5) { sampler.add(0f, 0f, gravity + it, it * 40L) }
        val features = sampler.features(0)
        assertEquals(5, features.sampleCount)
        assertEquals(0.0, features.dominantFrequencyHz, 0.0)
        assertEquals(0.0, features.variance, 0.0)
    }
}
