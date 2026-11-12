package com.steptrackerpro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The contract, in both directions:
 *
 *  - every way of walking the synthetic traces can express reads as gait and
 *    loses no step, including a walk down the aisle of a moving bus;
 *  - a bus seat, a car's floor, a bicycle's frame, a coach's sway and a
 *    train read as vibration, each caught by a different one of the three
 *    rules, which is why all three are there;
 *  - and every uncertainty - a still phone, a stale verdict, a batch
 *    covering minutes, an untimed sample - counts the steps.
 *
 * The traces are sums of sinusoids at amplitudes taken from what an
 * accelerometer sees in each case: metres per second squared, with gravity
 * added and removed again by the gate's own mean subtraction.
 */
class GaitGateTest {

    private val gravity = 9.81

    /** 2026-01-01T00:00:00Z. The gate is fed epoch ms, so the tests are too. */
    private val BASE = 1_767_225_600_000L

    /**
     * Feeds [seconds] of a signal at [rateHz] from [fromMs].
     *
     * @return the time of the last sample fed, to chain one stretch onto the
     *   next and to ask [GaitGate.gated] about steps taken at the end of it.
     */
    private fun GaitGate.feed(
        fromMs: Long,
        seconds: Double,
        rateHz: Double = 25.0,
        signal: (tSeconds: Double) -> Double
    ): Long {
        val dtMs = 1000.0 / rateHz
        var t = 0.0
        var last = fromMs
        while (t < seconds * 1000) {
            last = fromMs + t.toLong()
            onSample(0f, 0f, (gravity + signal(t / 1000.0)).toFloat(), last)
            t += dtMs
        }
        return last
    }

    private fun verdictOf(seconds: Double = 8.0, signal: (Double) -> Double): MotionVerdict {
        val gate = GaitGate()
        gate.feed(BASE, seconds, signal = signal)
        return gate.verdict
    }

    // ---- the traces --------------------------------------------------------

    private fun briskWalk(t: Double) = 2.0 * sin(2 * PI * 1.8 * t) + 0.6 * sin(2 * PI * 3.6 * t + 0.4)
    private fun gentleWalk(t: Double) = 0.7 * sin(2 * PI * 1.6 * t) + 0.25 * sin(2 * PI * 3.2 * t)
    private fun stroll(t: Double) = 1.1 * sin(2 * PI * 1.1 * t) + 0.3 * sin(2 * PI * 2.2 * t)
    private fun run(t: Double) = 4.0 * sin(2 * PI * 2.8 * t) + 1.2 * sin(2 * PI * 5.6 * t)

    private fun busSeat(t: Double) =
        0.35 * sin(2 * PI * 6.5 * t) + 0.3 * sin(2 * PI * 9.3 * t) +
            0.18 * sin(2 * PI * 3.7 * t) + 0.12 * sin(2 * PI * 0.8 * t)

    private fun busInHand(t: Double) =
        0.22 * sin(2 * PI * 7.1 * t) + 0.16 * sin(2 * PI * 11.4 * t) + 0.1 * sin(2 * PI * 2.6 * t)

    private fun coachSway(t: Double) = 0.5 * sin(2 * PI * 0.5 * t) + 0.1 * sin(2 * PI * 8.0 * t)

    private fun bicycle(t: Double) =
        0.35 * sin(2 * PI * 1.3 * t) + 0.5 * sin(2 * PI * 8.5 * t) + 0.3 * sin(2 * PI * 5.2 * t)

    private fun carFloor(t: Double) =
        0.6 * sin(2 * PI * 9.0 * t) + 0.4 * sin(2 * PI * 4.3 * t) + 0.2 * sin(2 * PI * 1.9 * t)

    private fun train(t: Double) =
        0.4 * sin(2 * PI * 0.7 * t) + 0.3 * sin(2 * PI * 4.9 * t) + 0.2 * sin(2 * PI * 10.1 * t)

    private fun walkingInABus(t: Double) =
        1.6 * sin(2 * PI * 1.9 * t) + 0.5 * sin(2 * PI * 3.8 * t) +
            0.3 * sin(2 * PI * 6.5 * t) + 0.25 * sin(2 * PI * 9.3 * t)

    /**
     * A phone in a front trouser pocket rides one leg, so the stride - half
     * the step rate - is the strongest line, below the gait band.
     */
    private fun pocketWalk(t: Double) =
        1.3 * sin(2 * PI * 0.85 * t) + 1.0 * sin(2 * PI * 1.7 * t) +
            0.6 * sin(2 * PI * 3.4 * t) + 0.3 * sin(2 * PI * 5.1 * t)

    private fun slowPocketWalk(t: Double) =
        1.0 * sin(2 * PI * 0.75 * t) + 0.7 * sin(2 * PI * 1.5 * t) + 0.4 * sin(2 * PI * 3.0 * t)

    /** A hard heel strike puts more into the harmonic than into the step, above the band. */
    private fun heelStrikeWalk(t: Double) = 1.0 * sin(2 * PI * 2.0 * t) + 1.4 * sin(2 * PI * 4.0 * t)

    /** Heel strikes as the impulses they are, one per step at 1.9 Hz. */
    private fun heelStrikes(t: Double): Double {
        val period = 1.0 / 1.9
        val d = t % period - period / 2
        return 4.0 * exp(-(d * d) / (2 * 0.04 * 0.04))
    }

    /** Deterministic, so a failure is reproducible. */
    private fun noise(amplitude: Double): (Double) -> Double {
        var seed = 12_345L
        val seen = HashMap<Long, Double>()
        return { t ->
            seen.getOrPut((t * 1_000_000).toLong()) {
                seed = seed * 6364136223846793005L + 1442695040888963407L
                ((seed ushr 11).toDouble() / (1L shl 53).toDouble() - 0.5) * 2.0 * amplitude
            }
        }
    }

    // ---- walking is never gated --------------------------------------------

    @Test
    fun `every way of walking reads as gait`() {
        assertEquals(MotionVerdict.GAIT, verdictOf { briskWalk(it) })
        assertEquals(MotionVerdict.GAIT, verdictOf { gentleWalk(it) })
        assertEquals(MotionVerdict.GAIT, verdictOf { stroll(it) })
        assertEquals(MotionVerdict.GAIT, verdictOf { run(it) })
    }

    @Test
    fun `sensor noise on top of a walk does not turn it into vibration`() {
        val gentle = noise(0.10)
        val brisk = noise(0.25)
        assertEquals(MotionVerdict.GAIT, verdictOf { gentleWalk(it) + gentle(it) })
        assertEquals(MotionVerdict.GAIT, verdictOf { briskWalk(it) + brisk(it) })
    }

    @Test
    fun `walking down a moving bus is still walking`() {
        assertEquals(MotionVerdict.GAIT, verdictOf { walkingInABus(it) })
    }

    /**
     * Steps every half second over [seconds] of [signal], asking the gate
     * about each as the counting path would.
     *
     * @return how many of them it threw out.
     */
    private fun gatedOver(
        seconds: Double = 20.0,
        rateHz: Double = 25.0,
        signal: (Double) -> Double
    ): Int {
        val gate = GaitGate()
        var gated = 0
        var at = BASE
        var t = 0.0
        while (t < seconds) {
            at = gate.feed(at, 0.5, rateHz) { signal(t + it) } + (1000.0 / rateHz).toLong()
            gated += gate.gated(at - 500, at, 1)
            t += 0.5
        }
        return gated
    }

    @Test
    fun `a walk whose strongest line is the stride or a harmonic is walking`() {
        // All four read as vibration before, and lost every step after the
        // first two seconds.
        assertEquals(MotionVerdict.GAIT, verdictOf { pocketWalk(it) })
        assertEquals(MotionVerdict.GAIT, verdictOf { slowPocketWalk(it) })
        assertEquals(MotionVerdict.GAIT, verdictOf { heelStrikeWalk(it) })
        assertEquals(MotionVerdict.GAIT, verdictOf { heelStrikes(it) })
        assertEquals(0, gatedOver { pocketWalk(it) })
        assertEquals(0, gatedOver { slowPocketWalk(it) })
        assertEquals(0, gatedOver { heelStrikeWalk(it) })
        assertEquals(0, gatedOver { heelStrikes(it) })
    }

    @Test
    fun `a walk sampled fast with sensor noise is walking`() {
        // Bare zero crossings counted the noise's chatter on each slow slope
        // as buzz: eight windows in nineteen read as vibration.
        val hiss = noise(0.15)
        assertEquals(0, gatedOver(rateHz = 100.0) { gentleWalk(it) + hiss(it) })
        assertEquals(0, gatedOver(rateHz = 50.0) { pocketWalk(it) })
    }

    @Test
    fun `a sensor delivering faster than the window is sized for is still judged`() {
        // Android delivers at the fastest rate any app asked for. At 200 Hz
        // the window held a second and a half and never formed a verdict.
        val gate = GaitGate()
        gate.feed(BASE, 8.0, rateHz = 200.0) { busSeat(it) }
        assertEquals(MotionVerdict.VIBRATION, gate.verdict)
        val walking = GaitGate()
        walking.feed(BASE, 8.0, rateHz = 200.0) { briskWalk(it) }
        assertEquals(MotionVerdict.GAIT, walking.verdict)
    }

    @Test
    fun `a gait verdict gates nothing`() {
        val gate = GaitGate()
        val at = gate.feed(BASE, 8.0) { briskWalk(it) }
        assertEquals(0, gate.gated(at - 800, at, 2))
    }

    // ---- machines ----------------------------------------------------------

    @Test
    fun `a bus a car and a bicycle read as vibration`() {
        // Caught by the crossing rate: buzz crosses the mean far more often
        // than any human cadence can.
        assertEquals(MotionVerdict.VIBRATION, verdictOf { busSeat(it) })
        assertEquals(MotionVerdict.VIBRATION, verdictOf { busInHand(it) })
        assertEquals(MotionVerdict.VIBRATION, verdictOf { bicycle(it) })
        assertEquals(MotionVerdict.VIBRATION, verdictOf { carFloor(it) })
    }

    @Test
    fun `a slow sway is vibration, not a slow walk`() {
        // Caught by where the peak sits: half a hertz is 30 steps a minute,
        // which nobody walks at. This is the case a scan narrowed to the
        // gait band got wrong - the sway leaked across the band's edge and
        // was reported as a strong peak at 1 Hz.
        assertEquals(MotionVerdict.VIBRATION, verdictOf { coachSway(it) })
        assertEquals(MotionVerdict.VIBRATION, verdictOf { train(it) })
    }

    @Test
    fun `broadband noise is vibration`() {
        // Caught by the peak's share of the band: noise has no peak worth
        // the name, whatever its crossing rate.
        val hiss = noise(0.60)
        assertEquals(MotionVerdict.VIBRATION, verdictOf { hiss(it) })
    }

    // ---- a still phone is never gated --------------------------------------

    @Test
    fun `a still phone is still, not vibration`() {
        // Deliberately not gated. A phone held steady gives the hardware
        // counter nothing to mistake for a step, an accelerometer that
        // stopped delivering looks exactly like this, and gating it would
        // throw away the first steps of every walk out of every pause.
        val gate = GaitGate()
        val at = gate.feed(BASE, 8.0) { 0.08 * sin(2 * PI * 1.0 * it) }
        assertEquals(MotionVerdict.STILL, gate.verdict)
        assertEquals(0, gate.gated(at - 800, at, 3))
    }

    // ---- engaging and disengaging ------------------------------------------

    @Test
    fun `vibration has to hold before anything is gated`() {
        val gate = GaitGate()
        // The first verdict needs half a window; the gate then wants two
        // more seconds of the same before it holds a step back.
        val early = gate.feed(BASE, 2.0) { busSeat(it) }
        assertEquals(MotionVerdict.VIBRATION, gate.verdict)
        assertEquals(0, gate.gated(early - 500, early, 4))

        val settled = gate.feed(early + 40, 4.0) { busSeat(it) }
        assertEquals(4, gate.gated(settled - 500, settled, 4))
    }

    @Test
    fun `one gait window ends the gating at once`() {
        val gate = GaitGate()
        val riding = gate.feed(BASE, 8.0) { busSeat(it) }
        assertEquals(4, gate.gated(riding - 500, riding, 4))
        // Off the bus and walking: the next verdict counts again, with no
        // delay to match the one engaging took. The gate is built to leak
        // rather than eat.
        val walking = gate.feed(riding + 40, 2.0) { briskWalk(it) }
        assertEquals(MotionVerdict.GAIT, gate.verdict)
        assertEquals(0, gate.gated(walking - 500, walking, 4))
    }

    @Test
    fun `a pause in the vibration restarts the wait`() {
        val gate = GaitGate()
        val riding = gate.feed(BASE, 8.0) { busSeat(it) }
        assertEquals(4, gate.gated(riding - 500, riding, 4))
        // A walk, then back on the bus. The window slides, so it takes a
        // few seconds of bus before a verdict is all bus again - and the
        // wait to engage starts from there, not from the ride before.
        val walked = gate.feed(riding + 40, 2.0) { briskWalk(it) }
        val back = gate.feed(walked + 40, 3.5) { busSeat(it) }
        assertEquals(MotionVerdict.VIBRATION, gate.verdict)
        assertEquals(0, gate.gated(back - 500, back, 4))
        val settled = gate.feed(back + 40, 3.0) { busSeat(it) }
        assertEquals(4, gate.gated(settled - 500, settled, 4))
    }

    // ---- every uncertainty counts the steps --------------------------------

    @Test
    fun `a verdict too old to speak for the steps gates nothing`() {
        val gate = GaitGate()
        val riding = gate.feed(BASE, 8.0) { busSeat(it) }
        assertEquals(4, gate.gated(riding - 500, riding, 4))
        // The accelerometer stopped delivering - a non-wake-up sensor with
        // the screen off, a hub that went quiet - and five seconds later a
        // batch of steps arrives. Nothing is known about them.
        val later = riding + 5_000
        assertEquals(MotionVerdict.UNKNOWN, gate.verdictFor(later))
        assertEquals(0, gate.gated(later - 500, later, 4))
    }

    @Test
    fun `a delta spanning more than one window is not judged by one`() {
        val gate = GaitGate()
        val riding = gate.feed(BASE, 8.0) { busSeat(it) }
        // Four hundred steps that arrived in one lump after a dead process.
        assertEquals(0, gate.gated(riding - 20 * 60_000L, riding, 400))
    }

    @Test
    fun `nothing is judged before a window has filled`() {
        val gate = GaitGate()
        val at = gate.feed(BASE, 1.0) { busSeat(it) }
        assertEquals(MotionVerdict.UNKNOWN, gate.verdict)
        assertEquals(0, gate.gated(at - 500, at, 4))
    }

    @Test
    fun `a batch replayed out of order is ignored`() {
        val gate = GaitGate()
        val at = gate.feed(BASE, 8.0) { briskWalk(it) }
        assertEquals(MotionVerdict.GAIT, gate.verdict)
        // A hub re-delivering samples it has already handed over must not
        // be able to rewrite the window with them.
        assertNull(gate.onSample(0f, 0f, 9.81f, at - 2_000))
        assertEquals(MotionVerdict.GAIT, gate.verdict)
    }

    @Test
    fun `reset forgets the ride`() {
        val gate = GaitGate()
        val riding = gate.feed(BASE, 8.0) { busSeat(it) }
        assertEquals(4, gate.gated(riding - 500, riding, 4))
        gate.reset()
        assertEquals(MotionVerdict.UNKNOWN, gate.verdict)
        assertEquals(0, gate.gated(riding - 500, riding, 4))
    }

    @Test
    fun `thresholds come from the rules`() {
        // A crossing rate limit a bus can live under leaves it counted, which
        // is what an app tuning this on its own devices is reaching for.
        val lenient = GaitGate(GaitGateRules(maxZeroCrossingRate = 50.0))
        lenient.feed(BASE, 8.0) { busSeat(it) }
        assertEquals(MotionVerdict.VIBRATION, lenient.verdict)
        // 3.7 Hz is outside the gait band, so that one is still caught; the
        // peak-share rule is what a lenient ratio turns off.
        val blind = GaitGate(GaitGateRules(maxZeroCrossingRate = 50.0, minPeakRatio = 0.0))
        val hiss = noise(0.60)
        blind.feed(BASE, 8.0) { hiss(it) + 1.0 * sin(2 * PI * 1.8 * it) }
        assertEquals(MotionVerdict.GAIT, blind.verdict)
    }

    @Test
    fun `a still phone can be gated by a variance floor low enough to see it`() {
        // The floor is what decides whether "barely moving" is still or
        // vibration, and it is the one threshold an app may want lower: a
        // phone wedged in a coach's seat pocket barely moves at all.
        val keen = GaitGate(GaitGateRules(stillVariance = 0.0001))
        val at = keen.feed(BASE, 8.0) { 0.08 * sin(2 * PI * 7.0 * it) }
        assertEquals(MotionVerdict.VIBRATION, keen.verdict)
        assertEquals(2, keen.gated(at - 500, at, 2))
    }

    // ---- what the counting path asks ---------------------------------------

    @Test
    fun `the counting path gates only what it can judge`() {
        val gate = GaitGate()
        val at = gate.feed(BASE, 8.0) { busSeat(it) }

        assertEquals(4, MotionGate.gated(gate, false, at - 500, at, 4, timed = true))
        // Only the arrival is known, so there is no window to judge it by -
        // which is what keeps a screen-off walk arriving in one batch.
        assertEquals(0, MotionGate.gated(gate, false, at - 500, at, 4, timed = false))
        // The gate is off.
        assertEquals(0, MotionGate.gated(null, false, at - 500, at, 4, timed = true))
        assertEquals(0, MotionGate.gated(gate, false, at - 500, at, 0, timed = true))
    }

    @Test
    fun `Activity Recognition saying in a vehicle is proof enough on its own`() {
        val gate = GaitGate()
        // Walking, as far as three seconds of accelerometer can tell - a
        // phone rocking in a cup holder on a rhythmic road. Activity
        // Recognition has watched minutes of it and says it is a car.
        val at = gate.feed(BASE, 8.0) { briskWalk(it) }
        assertEquals(MotionVerdict.GAIT, gate.verdict)
        assertEquals(6, MotionGate.gated(gate, true, at - 500, at, 6, timed = true))
        // Still nothing untimed, and still nothing while the gate is off:
        // `motionGate.enabled` governs both halves.
        assertEquals(0, MotionGate.gated(gate, true, at - 500, at, 6, timed = false))
        assertEquals(0, MotionGate.gated(null, true, at - 500, at, 6, timed = true))
    }

    @Test
    fun `walking long enough outvotes Activity Recognition's late word on leaving a vehicle`() {
        val gate = GaitGate()
        // Off the bus and walking, and Activity Recognition has not said so
        // yet - on some phones it never does. The first steps go with the
        // bus; once the walk has held for ten seconds it counts.
        val early = gate.feed(BASE, 8.0) { pocketWalk(it) }
        assertEquals(6, MotionGate.gated(gate, true, early - 500, early, 6, timed = true))
        val later = gate.feed(early + 40, 6.0) { pocketWalk(8.0 + it) }
        assertEquals(0, MotionGate.gated(gate, true, later - 500, later, 6, timed = true))
        // One window of anything else and the vehicle has the say again.
        val shaken = gate.feed(later + 40, 3.0) { busSeat(it) }
        assertEquals(6, MotionGate.gated(gate, true, shaken - 500, shaken, 6, timed = true))
    }
}
