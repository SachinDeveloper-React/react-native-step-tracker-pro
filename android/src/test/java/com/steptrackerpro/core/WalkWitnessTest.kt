package com.steptrackerpro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * The witness answers one question, in one direction - is this, beyond
 * doubt, a person walking? - and these pin both halves of it:
 *
 *  - every way of carrying a phone on a walk reads as a walking rhythm and
 *    is confirmed half a minute in, at whatever rate the hub delivers;
 *  - nothing a vehicle does is ever confirmed: road vibration, a
 *    suspension's bounce at a walker's own frequency, an engine's tone,
 *    a slow sway, a run of jolts.
 *
 * The vehicle traces here are noise, not sums of sinusoids. A sum of
 * sinusoids repeats exactly, and "repeats" is the one thing a walk has
 * that a road does not: the classifier of 2.8 was tuned on such traces and
 * met real roads only in the field. The thresholds themselves were chosen
 * against recorded walks and rides (see docs/ARCHITECTURE.md); what is
 * pinned here is that the code keeps doing what was measured.
 */
class WalkWitnessTest {

    private val gravity = 9.81

    /** 2026-01-01T00:00:00Z. The witness is fed epoch ms, so the tests are too. */
    private val base = 1_767_225_600_000L

    // ---- feeding -----------------------------------------------------------

    /** What one feed saw: a verdict per second, and when walking was first confirmed. */
    private class Seen(val verdicts: List<MotionVerdict>, val confirmedAtMs: Long, val lastMs: Long) {
        val gaitShare: Double get() = verdicts.count { it == MotionVerdict.GAIT }.toDouble() / maxOf(1, verdicts.size)
    }

    private fun WalkWitness.feed(
        fromMs: Long,
        seconds: Double,
        rateHz: Double = 25.0,
        jitterMs: Int = 0,
        signal: (tSeconds: Double) -> Double
    ): Seen {
        val verdicts = ArrayList<MotionVerdict>()
        var confirmed = -1L
        var last = fromMs
        val count = (seconds * rateHz).toInt()
        var seed = 99L
        for (i in 0 until count) {
            val exact = i * 1000.0 / rateHz
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val jitter = if (jitterMs == 0) 0 else ((seed ushr 33) % (2 * jitterMs + 1)).toInt() - jitterMs
            last = maxOf(last, fromMs + exact.toLong() + jitter)
            onSample(0f, 0f, (gravity + signal(exact / 1000.0)).toFloat(), last)?.let {
                verdicts.add(it)
                if (confirmed < 0 && confirmedWalking(last)) confirmed = last - fromMs
            }
        }
        return Seen(verdicts, confirmed, last)
    }

    private fun seen(seconds: Double = 40.0, rateHz: Double = 25.0, signal: (Double) -> Double): Seen =
        WalkWitness().feed(base, seconds, rateHz, signal = signal)

    // ---- walks -------------------------------------------------------------

    private fun briskWalk(t: Double) = 2.0 * sin(2 * PI * 1.8 * t) + 0.6 * sin(2 * PI * 3.6 * t + 0.4)

    /** A phone held in front, being read: the gentlest a walk gets. */
    private fun heldWalk(t: Double) = 0.75 * sin(2 * PI * 1.6 * t) + 0.25 * sin(2 * PI * 3.2 * t)

    private fun stroll(t: Double) = 1.1 * sin(2 * PI * 1.1 * t) + 0.3 * sin(2 * PI * 2.2 * t)
    private fun run(t: Double) = 4.0 * sin(2 * PI * 2.8 * t) + 1.2 * sin(2 * PI * 5.6 * t)

    /**
     * A phone in a trouser pocket rides one leg, so the stride - half the
     * step rate - is its strongest line. 2.8.0 called this a vehicle.
     */
    private fun pocketWalk(t: Double) =
        1.3 * sin(2 * PI * 0.85 * t) + 1.0 * sin(2 * PI * 1.7 * t) +
            0.6 * sin(2 * PI * 3.4 * t) + 0.3 * sin(2 * PI * 5.1 * t)

    private fun slowPocketWalk(t: Double) =
        1.0 * sin(2 * PI * 0.75 * t) + 0.7 * sin(2 * PI * 1.5 * t) + 0.4 * sin(2 * PI * 3.0 * t)

    /** Heel strikes as the impulses they are, one per step at 1.9 Hz. */
    private fun heelStrikes(t: Double): Double {
        val period = 1.0 / 1.9
        val d = t % period - period / 2
        return 4.0 * exp(-(d * d) / (2 * 0.04 * 0.04))
    }

    // ---- everything that is not a walk --------------------------------------

    /** Deterministic white noise in -1..1, one value per call. */
    private class Noise(private var seed: Long) {
        fun next(): Double {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 11).toDouble() / (1L shl 53).toDouble() - 0.5) * 2.0
        }
    }

    /**
     * [seconds] of a signal at 100 Hz built by [build], read back at any
     * time: the traces below are made sample by sample, because a filter
     * has a memory and a function of time does not.
     */
    private fun recorded(seconds: Int, build: (n: Int) -> DoubleArray): (Double) -> Double {
        val trace = build(seconds * 100)
        return { t -> trace[(t * 100).toInt().coerceIn(0, trace.size - 1)] }
    }

    /** Road and body vibration: noise across the whole band, nothing in it that repeats. */
    private fun roadNoise(rms: Double, seed: Long = 7L) = recorded(600) { n ->
        val noise = Noise(seed)
        var low = 0.0
        DoubleArray(n) {
            // One pole at about 8 Hz: a seat and a hand pass less above it.
            low += 0.4 * (noise.next() - low)
            low * rms / 0.29
        }
    }

    /**
     * A suspension's bounce: noise through a resonance at [hz], a walker's
     * own frequency. This is what a hardware step counter mistakes for
     * steps, and what the witness has to refuse - it is a rhythm for a
     * second or two and never the same one for thirty.
     */
    private fun bounce(hz: Double, rms: Double, q: Double = 2.5, seed: Long = 11L) = recorded(600) { n ->
        val noise = Noise(seed)
        val r = exp(-PI * hz / (q * 100.0))
        val a1 = 2 * r * cos(2 * PI * hz / 100.0)
        val a2 = -r * r
        var y1 = 0.0
        var y2 = 0.0
        val raw = DoubleArray(n) {
            val y = a1 * y1 + a2 * y2 + noise.next()
            y2 = y1
            y1 = y
            y
        }
        var energy = 0.0
        for (v in raw) energy += v * v
        val scale = rms / kotlin.math.sqrt(energy / n)
        DoubleArray(n) { raw[it] * scale }
    }

    /** A bus on a bad road: the bounce, the road under it, and a jolt every so often. */
    private fun roughBus(seed: Long = 23L): (Double) -> Double {
        val road = roadNoise(0.5, seed)
        val body = bounce(1.6, 0.9, seed = seed + 1)
        val jolts = recorded(600) { n ->
            val noise = Noise(seed + 2)
            val out = DoubleArray(n)
            var at = 150
            while (at < n - 30) {
                val size = 2.0 + 2.0 * kotlin.math.abs(noise.next())
                for (k in 0 until 25) out[at + k] += size * exp(-k / 6.0) * cos(2 * PI * 4.0 * k / 100.0)
                at += 120 + (kotlin.math.abs(noise.next()) * 500).toInt()
            }
            out
        }
        return { t -> road(t) + body(t) + jolts(t) }
    }

    private fun engineTone(hz: Double, amplitude: Double): (Double) -> Double = { t -> amplitude * sin(2 * PI * hz * t) }

    // ---- walking is recognised ---------------------------------------------

    @Test
    fun `every way of carrying a phone on a walk reads as a walking rhythm`() {
        val walks = mapOf<String, (Double) -> Double>(
            "brisk" to ::briskWalk, "held" to ::heldWalk, "stroll" to ::stroll, "run" to ::run,
            "pocket" to ::pocketWalk, "slow pocket" to ::slowPocketWalk, "heel strikes" to ::heelStrikes
        )
        for ((name, walk) in walks) {
            val result = seen(signal = walk)
            assertTrue("$name: ${result.verdicts}", result.verdicts.all { it == MotionVerdict.GAIT })
            // Thirty seconds of rhythm, counted from the first second the
            // witness could judge: it cannot be confirmed sooner, and a
            // clean walk is not confirmed later.
            assertTrue("$name confirmed at ${result.confirmedAtMs}", result.confirmedAtMs in 26_000..31_000)
        }
    }

    @Test
    fun `sensor noise on top of a walk does not hide it`() {
        val gentle = roadNoise(0.15, seed = 3L)
        val rough = roadNoise(0.4, seed = 4L)
        assertTrue(seen { heldWalk(it) + gentle(it) }.gaitShare > 0.9)
        assertTrue(seen { pocketWalk(it) + rough(it) }.gaitShare > 0.9)
        assertTrue(seen { heldWalk(it) + gentle(it) }.confirmedAtMs > 0)
    }

    @Test
    fun `a walk is recognised at whatever rate the hub delivers`() {
        // Android delivers at the fastest rate any app asked for, and a
        // slow hub at less than was asked. 2.8.2's window never filled at
        // 200 Hz, which left the gate blind on exactly the phones with a
        // game open.
        for (rate in listOf(10.0, 20.0, 25.0, 50.0, 100.0, 200.0, 400.0)) {
            val result = seen(rateHz = rate, signal = ::pocketWalk)
            assertTrue("at $rate Hz: ${result.gaitShare}", result.gaitShare > 0.95)
            assertTrue("at $rate Hz", result.confirmedAtMs in 26_000..32_000)
        }
    }

    @Test
    fun `timestamps that jitter do not break the rhythm`() {
        val result = WalkWitness().feed(base, 40.0, 25.0, jitterMs = 12, signal = ::briskWalk)
        assertTrue("${result.gaitShare}", result.gaitShare > 0.95)
        assertTrue(result.confirmedAtMs > 0)
    }

    // ---- nothing else is -----------------------------------------------------

    @Test
    fun `road vibration is never a walking rhythm`() {
        for (rms in listOf(0.3, 0.7, 1.5)) {
            val result = seen(seconds = 300.0, signal = roadNoise(rms))
            assertTrue("rms $rms: ${result.gaitShare}", result.gaitShare < 0.05)
            assertEquals(-1L, result.confirmedAtMs)
        }
    }

    @Test
    fun `a suspension bouncing at a walker's own frequency is not confirmed as a walk`() {
        // The hard one: for a second or two this is a rhythm, and seconds
        // of it pass - as they do on a real bus, about one in sixteen. What
        // it cannot do is keep the same rhythm for half a minute.
        for (hz in listOf(1.2, 1.6, 2.0)) {
            for (seed in listOf(11L, 12L, 13L)) {
                val result = seen(seconds = 600.0, signal = bounce(hz, 1.0, seed = seed))
                assertTrue("$hz Hz seed $seed: ${result.gaitShare}", result.gaitShare < 0.5)
                assertEquals("$hz Hz seed $seed", -1L, result.confirmedAtMs)
            }
        }
    }

    @Test
    fun `a rough bus ride is not confirmed as a walk`() {
        for (seed in listOf(23L, 31L, 47L)) {
            val result = seen(seconds = 600.0, rateHz = 50.0, signal = roughBus(seed))
            assertEquals("seed $seed, gait share ${result.gaitShare}", -1L, result.confirmedAtMs)
        }
    }

    @Test
    fun `an engine's tone repeats perfectly and is still not a step`() {
        // As periodic as anything can be, at 8 to 15 Hz: found on a real
        // bus, where the unsmoothed signal read as "walking" for most of a
        // minute. Smoothing to a step's frequencies is what removes it.
        for (hz in listOf(8.0, 11.5, 13.0, 15.0)) {
            for (rate in listOf(25.0, 100.0)) {
                if (hz > rate / 2) continue
                val result = seen(seconds = 60.0, rateHz = rate, signal = engineTone(hz, 1.5))
                assertEquals("$hz Hz at $rate Hz", 0.0, result.gaitShare, 0.0)
            }
        }
        // And under a walk it changes nothing.
        val both = seen { briskWalk(it) + engineTone(11.5, 1.0)(it) }
        assertTrue(both.gaitShare > 0.95)
    }

    @Test
    fun `a slow sway is not a stride`() {
        // A coach leaning through bends comes round every 2.5 to 5 s:
        // slower than the slowest stride the witness looks for. It also
        // resembles itself a third of a second later, as anything slow
        // does, and that resemblance is not a rhythm.
        for (hz in listOf(0.2, 0.3, 0.4, 0.5)) {
            val result = seen(seconds = 120.0) { 1.2 * sin(2 * PI * hz * it) }
            assertEquals("$hz Hz", 0.0, result.gaitShare, 0.0)
        }
        // Nor is a slow drift with road vibration on top of it.
        val road = roadNoise(0.4)
        val drifting = seen(seconds = 300.0) { 1.5 * sin(2 * PI * 0.25 * it) + road(it) }
        assertTrue("${drifting.gaitShare}", drifting.gaitShare < 0.05)
    }

    @Test
    fun `a phone lying still is still, and one barely moving is not walking`() {
        val still = seen(seconds = 20.0) { 0.01 * sin(2 * PI * 3.0 * it) }
        assertTrue(still.verdicts.all { it == MotionVerdict.STILL })
        // Regular, in a walk's range, and far too faint to be one: an
        // engine idling under a seat.
        val faint = seen(seconds = 60.0) { 0.3 * sin(2 * PI * 1.7 * it) }
        assertTrue(faint.verdicts.all { it == MotionVerdict.VIBRATION })
    }

    // ---- confirmation --------------------------------------------------------

    @Test
    fun `walking is confirmed by most of half a minute, not by all of it and not by a few seconds`() {
        // Ten seconds of walk in every fifteen is two thirds: a rhythm that
        // keeps coming back, and short of the four fifths asked for.
        val road = roadNoise(0.7)
        val patchy = seen(seconds = 180.0) { t -> if (t % 15.0 < 10.0) briskWalk(t) else road(t) }
        assertEquals(-1L, patchy.confirmedAtMs)
        // A walk with a two-second stumble in every fifteen is a walk.
        val stumbling = seen(seconds = 90.0) { t -> if (t % 15.0 < 13.0) briskWalk(t) else road(t) }
        assertTrue(stumbling.confirmedAtMs > 0)
        // Ten seconds of perfect walking, once, confirm nothing.
        val brief = seen(seconds = 120.0) { t -> if (t < 10.0) briskWalk(t) else road(t) }
        assertEquals(-1L, brief.confirmedAtMs)
    }

    @Test
    fun `a confirmed walk goes on at half the seconds, and is over below that`() {
        val road = roadNoise(0.7)
        val witness = WalkWitness()
        val walked = witness.feed(base, 40.0, signal = ::briskWalk)
        assertTrue(witness.confirmedWalking(walked.lastMs))
        assertTrue(witness.stillWalking(walked.lastMs))
        // Ten seconds nothing can be read in: too few seconds of rhythm left
        // to confirm a walk, enough for one already confirmed to go on.
        val faltered = witness.feed(walked.lastMs + 40, 10.0, signal = road)
        assertFalse(witness.confirmedWalking(faltered.lastMs))
        assertTrue(witness.stillWalking(faltered.lastMs))
        assertTrue(witness.gaitSeconds(faltered.lastMs) in WalkWitness.KEEP_GAIT_SECONDS until WalkWitness.CONFIRM_GAIT_SECONDS)
        // Twenty-five seconds of it: the walk has stopped.
        val stopped = witness.feed(faltered.lastMs + 40, 15.0, signal = road)
        assertFalse(witness.stillWalking(stopped.lastMs))
        // And a witness that is not watching says nothing goes on.
        assertEquals(0, witness.gaitSeconds(stopped.lastMs + 60_000))
    }

    @Test
    fun `a witness that stopped watching confirms nothing`() {
        val witness = WalkWitness()
        val result = witness.feed(base, 40.0, signal = ::briskWalk)
        assertTrue(witness.confirmedWalking(result.lastMs))
        // Five seconds on with no samples: it does not know what happened.
        assertFalse(witness.confirmedWalking(result.lastMs + 5_000))
        assertEquals(MotionVerdict.UNKNOWN, witness.verdictFor(result.lastMs + 5_000))
        assertEquals(MotionVerdict.GAIT, witness.verdictFor(result.lastMs))
    }

    @Test
    fun `reset forgets the walk`() {
        val witness = WalkWitness()
        val result = witness.feed(base, 40.0, signal = ::briskWalk)
        witness.reset()
        assertFalse(witness.confirmedWalking(result.lastMs))
        assertEquals(MotionVerdict.UNKNOWN, witness.verdict)
        // And needs a fresh thirty seconds to confirm again.
        val again = witness.feed(result.lastMs + 40, 20.0, signal = ::briskWalk)
        assertEquals(-1L, again.confirmedAtMs)
    }

    // ---- which second was which ----------------------------------------------

    @Test
    fun `a step is matched to the second it was taken in`() {
        val road = roadNoise(0.7)
        val witness = WalkWitness()
        // A minute of road, then a walk.
        witness.feed(base, 60.0, signal = road)
        witness.feed(base + 60_000, 30.0) { briskWalk(it) }
        assertFalse(witness.gaitAround(base + 30_000))
        assertFalse(witness.gaitAround(base + 55_000))
        assertTrue(witness.gaitAround(base + 70_000))
        assertTrue(witness.gaitAround(base + 85_000))
        // Before the witness was watching, and after it stopped: no verdict, no vouching.
        assertFalse(witness.gaitAround(base - 20_000))
        assertFalse(witness.gaitAround(base + 200_000))
    }

    // ---- stillness -----------------------------------------------------------

    @Test
    fun `stillness is timed past the two minutes of verdicts that are kept`() {
        val witness = WalkWitness()
        val moving = witness.feed(base, 20.0, signal = ::briskWalk)
        assertEquals(0L, witness.stillFor(moving.lastMs))
        val resting = witness.feed(moving.lastMs + 40, 400.0) { 0.0 }
        val still = witness.stillFor(resting.lastMs)
        // 400 s less the few seconds the walk stays in the window.
        assertTrue("$still", still in 390_000..400_000)
        // One movement ends the run.
        val stirred = witness.feed(resting.lastMs + 40, 6.0, signal = ::briskWalk)
        assertEquals(0L, witness.stillFor(stirred.lastMs))
    }

    // ---- the grid --------------------------------------------------------------

    @Test
    fun `a sample out of order is dropped`() {
        val witness = WalkWitness()
        val result = witness.feed(base, 10.0, signal = ::briskWalk)
        assertNull(witness.onSample(0f, 0f, 30f, result.lastMs - 2_000))
        assertEquals(MotionVerdict.GAIT, witness.verdictFor(result.lastMs))
    }

    @Test
    fun `nothing is judged across a hole in the samples`() {
        val witness = WalkWitness()
        val first = witness.feed(base, 10.0, signal = ::briskWalk)
        assertEquals(MotionVerdict.GAIT, witness.verdict)
        // The hub goes quiet for two seconds, then delivers again: the
        // seconds either side cannot be laid end to end, so the next verdict
        // waits for a full window of its own.
        val second = witness.feed(first.lastMs + 2_000, 2.5, signal = ::briskWalk)
        assertTrue(second.verdicts.isEmpty())
        // The same walk carried on, so the window is one walk and not two
        // joined out of step.
        val third = witness.feed(second.lastMs + 40, 2.0) { briskWalk(it + 2.5) }
        assertEquals(listOf(MotionVerdict.GAIT), third.verdicts.take(1))
    }
}
