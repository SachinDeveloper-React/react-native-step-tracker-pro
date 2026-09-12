package com.steptrackerpro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic gait: gravity plus a sinusoid at walking cadence, with sensor
 * noise on top. Real accelerometer traces are messier - a step is a sharp
 * heel-strike impact rather than a sine - but sharper peaks are easier for
 * a threshold detector, not harder, so the sine is the conservative case.
 */
class AccelerometerStepDetectorTest {

    private val gravity = 9.81f

    /**
     * Feeds [seconds] of signal at [hz] samples per second and returns the
     * counted steps. [amplitude] is the peak linear acceleration in m/s²,
     * [cadenceHz] the steps per second, [noise] the peak jitter.
     */
    private fun run(
        detector: AccelerometerStepDetector,
        seconds: Double,
        hz: Int,
        cadenceHz: Double,
        amplitude: Float,
        noise: Float = 0.15f,
        startMs: Long = 0L,
        axis: Int = 2,
        seed: Int = 1
    ): Int {
        val random = Random(seed)
        var total = 0
        val samples = (seconds * hz).toInt()
        for (i in 0 until samples) {
            val t = i.toDouble() / hz
            val gait = (amplitude * sin(2 * PI * cadenceHz * t)).toFloat()
            val jitter = { (random.nextFloat() * 2f - 1f) * noise }
            // Gravity sits on one axis; the gait impact is along it too, the
            // way a phone in a trouser pocket sees a heel strike.
            val v = floatArrayOf(jitter(), jitter(), jitter())
            v[axis] += gravity + gait
            total += detector.onSample(v[0], v[1], v[2], startMs + (t * 1000).toLong())
        }
        return total
    }

    /**
     * Heel-strike model, closer to a pocketed phone than a sine: a sharp
     * positive impulse about 50 ms wide, a smaller negative rebound, then
     * near-silence until the next strike. Gravity is split across two axes
     * the way a phone sits in a trouser pocket.
     */
    private fun strikes(
        detector: AccelerometerStepDetector,
        seconds: Double,
        hz: Int,
        cadenceHz: Double,
        peak: Float,
        noise: Float = 0.3f
    ): Int {
        val random = Random(7)
        var total = 0
        val period = 1.0 / cadenceHz
        for (i in 0 until (seconds * hz).toInt()) {
            val t = i.toDouble() / hz
            val phase = t % period
            val strike = (peak * exp(-((phase - 0.04) * (phase - 0.04)) / (2 * 0.02 * 0.02))).toFloat()
            val rebound = (-0.4f * peak * exp(-((phase - 0.16) * (phase - 0.16)) / (2 * 0.04 * 0.04))).toFloat()
            val jitter = { (random.nextFloat() * 2f - 1f) * noise }
            total += detector.onSample(
                jitter(),
                gravity * 0.3f + jitter(),
                gravity * 0.95f + strike + rebound + jitter(),
                (t * 1000).toLong()
            )
        }
        return total
    }

    @Test
    fun `impulse-shaped heel strikes count one per stride at any sample rate`() {
        for (hz in listOf(20, 25, 50)) {
            val steps = strikes(AccelerometerStepDetector(), 20.0, hz, 1.8, 4f)
            assertTrue("$hz Hz counted $steps", steps in 34..37)
        }
    }

    @Test
    fun `gentle impulse strikes in a bag still count`() {
        val steps = strikes(AccelerometerStepDetector(), 20.0, 25, 1.6, 2.5f)
        assertTrue("counted $steps", steps in 29..33)
    }

    @Test
    fun `hard running strikes count one per stride`() {
        val steps = strikes(AccelerometerStepDetector(), 10.0, 50, 2.8, 12f)
        assertTrue("counted $steps", steps in 26..29)
    }

    @Test
    fun `a noisy sensor does not change the strike count`() {
        val clean = strikes(AccelerometerStepDetector(), 20.0, 25, 1.8, 4f, noise = 0.3f)
        val noisy = strikes(AccelerometerStepDetector(), 20.0, 25, 1.8, 4f, noise = 0.8f)
        assertEquals(clean, noisy)
    }

    @Test
    fun `impulse shuffle at 0_9 Hz counts`() {
        val steps = strikes(AccelerometerStepDetector(), 30.0, 25, 0.9, 2.5f)
        assertTrue("counted $steps", steps in 25..28)
    }

    @Test
    fun `steady walk at 1_8 Hz counts one step per cycle`() {
        val steps = run(AccelerometerStepDetector(), seconds = 20.0, hz = 25, cadenceHz = 1.8, amplitude = 3f)
        // 36 cycles; the first is spent settling the gravity filter.
        assertTrue("counted $steps", steps in 33..37)
    }

    @Test
    fun `brisk walk at 50 Hz counts the same as at 20 Hz`() {
        val at20 = run(AccelerometerStepDetector(), 20.0, 20, 2.0, 3.5f)
        val at50 = run(AccelerometerStepDetector(), 20.0, 50, 2.0, 3.5f)
        assertTrue("20 Hz: $at20", at20 in 37..41)
        assertTrue("50 Hz: $at50", at50 in 37..41)
    }

    @Test
    fun `running at 2_8 Hz with big impacts counts one step per cycle`() {
        val steps = run(AccelerometerStepDetector(), 10.0, 50, 2.8, 8f)
        assertTrue("counted $steps", steps in 25..29)
    }

    @Test
    fun `lying still with sensor noise counts nothing`() {
        val steps = run(AccelerometerStepDetector(), 60.0, 25, 0.0, 0f, noise = 0.4f)
        assertEquals(0, steps)
    }

    @Test
    fun `picking the phone up is not a walk`() {
        val detector = AccelerometerStepDetector()
        // Two jolts a second apart, then nothing: below the warm-up count.
        var total = run(detector, 1.0, 25, 1.0, 4f)
        total += run(detector, 5.0, 25, 0.0, 0f, startMs = 1_000)
        assertEquals(0, total)
    }

    @Test
    fun `orientation does not matter`() {
        val onX = run(AccelerometerStepDetector(), 20.0, 25, 1.8, 3f, axis = 0)
        val onZ = run(AccelerometerStepDetector(), 20.0, 25, 1.8, 3f, axis = 2)
        assertEquals(onZ, onX)
    }

    @Test
    fun `a pause between two walks ends the first and warms up the second`() {
        val detector = AccelerometerStepDetector()
        val first = run(detector, 10.0, 25, 1.8, 3f)
        val still = run(detector, 5.0, 25, 0.0, 0f, startMs = 10_000)
        val second = run(detector, 10.0, 25, 1.8, 3f, startMs = 15_000)
        assertEquals(0, still)
        assertTrue("first $first", first in 15..19)
        // The second walk loses nothing to the warm-up beyond the settling
        // cycle: held-back candidates are released once it is confirmed.
        assertTrue("second $second", second in 15..19)
    }

    @Test
    fun `a batch delivered at once counts the same as live samples`() {
        // Same signal, but timestamps say 25 Hz while it arrives in one go -
        // the detector only looks at the timestamps.
        val live = run(AccelerometerStepDetector(), 20.0, 25, 1.8, 3f)
        val batched = run(AccelerometerStepDetector(), 20.0, 25, 1.8, 3f)
        assertEquals(live, batched)
    }

    @Test
    fun `vehicle vibration counts nothing`() {
        assertEquals(0, run(AccelerometerStepDetector(), 60.0, 25, 12.0, 1.5f))
        assertEquals(0, run(AccelerometerStepDetector(), 60.0, 25, 5.0, 1.0f))
        assertEquals(0, run(AccelerometerStepDetector(), 60.0, 25, 5.0, 1.5f))
        assertEquals(0, run(AccelerometerStepDetector(), 60.0, 25, 3.0, 1.0f))
    }

    @Test
    fun `heavy sensor noise on a still phone counts nothing`() {
        assertEquals(0, run(AccelerometerStepDetector(), 60.0, 25, 0.0, 0f, noise = 1.2f))
    }

    @Test
    fun `slow rocking such as a bus or a boat counts nothing`() {
        // Half a hertz is one peak every two seconds - no gait is that slow.
        assertEquals(0, run(AccelerometerStepDetector(), 60.0, 25, 0.5, 1.5f))
        assertEquals(0, run(AccelerometerStepDetector(), 60.0, 25, 0.7, 1.5f))
    }

    @Test
    fun `a gentle walk with the phone in a bag still counts`() {
        val steps = run(AccelerometerStepDetector(), 20.0, 25, 1.8, 1.5f)
        assertTrue("counted $steps", steps in 33..37)
    }

    @Test
    fun `an elderly shuffle at 0_9 Hz counts`() {
        val steps = run(AccelerometerStepDetector(), 30.0, 25, 0.9, 2f)
        assertTrue("counted $steps", steps in 24..28)
    }
}
