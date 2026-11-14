package com.steptrackerpro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The gate's contract:
 *
 *  - **only Activity Recognition holds a step.** Without its word that the
 *    phone is in a vehicle or on a bicycle, every step is counted, whatever
 *    the accelerometer sees;
 *  - **held is not gone.** The walk away from a ride comes back - on
 *    "walking" from Activity Recognition, on the accelerometer's own
 *    confirmation, and, for the seconds it vouches for, on any other end
 *    of the ride;
 *  - **a ride cannot outlive its evidence**, and steps that cannot be placed
 *    in it are counted.
 */
class VehicleGateTest {

    private val gravity = 9.81

    /** 2026-01-01T00:00:00Z. */
    private val base = 1_767_225_600_000L

    private fun walk(t: Double) =
        1.3 * sin(2 * PI * 0.85 * t) + 1.0 * sin(2 * PI * 1.7 * t) + 0.6 * sin(2 * PI * 3.4 * t)

    /** Deterministic road noise, about 0.7 m/s² RMS. */
    private fun road(): (Double) -> Double {
        var seed = 7L
        var low = 0.0
        val trace = DoubleArray(120_000) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val white = ((seed ushr 11).toDouble() / (1L shl 53).toDouble() - 0.5) * 2.0
            low += 0.4 * (white - low)
            low * 2.4
        }
        return { t -> trace[(t * 100).toInt().coerceIn(0, trace.size - 1)] }
    }

    /** What happened while a stretch was fed: steps counted, held, given back, and how the ride ended. */
    private class Fed(
        var steps: Int = 0,
        var held: Int = 0,
        var released: Int = 0,
        /** The last change in holding, and every one in order. */
        var change: String? = null,
        val changes: MutableList<String> = ArrayList(),
        var lastMs: Long = 0L
    ) {
        val ended: String? get() = changes.lastOrNull { it == VehicleGate.ENDED_WALKING || it == VehicleGate.ENDED_STILL }
    }

    /**
     * Feeds [seconds] of accelerometer at 25 Hz from [fromMs], with a step
     * counted every [stepEveryMs] (0 for none), each asked of the gate as
     * the engine would.
     */
    private fun VehicleGate.ride(
        fromMs: Long,
        seconds: Int,
        stepEveryMs: Long,
        into: Fed = Fed(),
        signal: (Double) -> Double
    ): Fed {
        var nextStep = if (stepEveryMs > 0) fromMs + stepEveryMs else Long.MAX_VALUE
        var previousStep = fromMs
        for (i in 0 until seconds * 25) {
            val at = fromMs + i * 40L
            while (nextStep <= at) {
                into.steps++
                into.held += gated(previousStep, nextStep, 1, true)
                previousStep = nextStep
                nextStep += stepEveryMs
            }
            onSample(0f, 0f, (gravity + signal(i / 25.0)).toFloat(), at)?.let { outcome ->
                outcome.release?.let { into.released += it.steps }
                outcome.change?.let {
                    into.change = it
                    into.changes += it
                }
            }
            into.lastMs = at
        }
        return into
    }

    // ---- only Activity Recognition holds a step ------------------------------

    @Test
    fun `nothing is held until Activity Recognition reports a vehicle`() {
        val gate = VehicleGate()
        assertFalse(gate.holding)
        // The roughest road there is, and steps arriving through all of it:
        // the accelerometer has no say.
        val fed = gate.ride(base, 120, 2_000, signal = road())
        assertEquals(0, fed.held)
        assertEquals(0, gate.gated(base, base + 500, 40, true))
        // Nor do the states that are not a machine carrying the phone.
        for (state in listOf(ActivityState.STILL, ActivityState.WALKING, ActivityState.RUNNING, ActivityState.UNKNOWN)) {
            assertNull(gate.onActivity(state, base))
            assertFalse(gate.holding)
            assertEquals(0, gate.gated(base, base + 1_000, 5, true))
        }
    }

    @Test
    fun `a reported vehicle or bicycle holds the steps that follow`() {
        for (state in listOf(ActivityState.IN_VEHICLE, ActivityState.ON_BICYCLE)) {
            val gate = VehicleGate()
            assertNull(gate.onActivity(state, base))
            assertTrue(gate.holding)
            assertEquals(base, gate.vehicleSince)
            assertEquals(7, gate.gated(base + 4_000, base + 5_000, 7, true))
        }
    }

    @Test
    fun `a bus reclassified as a bicycle is one ride`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        assertEquals(3, gate.gated(base + 1_000, base + 2_000, 3, true))
        // Slow traffic reads as a bicycle now and then. Nothing is given
        // back for it, and the ride began when it began.
        assertNull(gate.onActivity(ActivityState.ON_BICYCLE, base + 60_000))
        assertTrue(gate.holding)
        assertEquals(base, gate.vehicleSince)
    }

    // ---- steps that cannot be placed are counted ---------------------------

    @Test
    fun `an untimed sample is never held`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        assertEquals(0, gate.gated(base + 1_000, base + 2_000, 30, false))
        assertEquals(0, gate.gated(0L, base + 2_000, 30, false))
    }

    @Test
    fun `steps taken before the ride began are counted, however late they are reported`() {
        val gate = VehicleGate()
        // The walk to the seat: the counter hands it over up to ten seconds
        // late, and Activity Recognition's own report is dated when the
        // ride began.
        gate.onActivity(ActivityState.IN_VEHICLE, base + 10_000)
        assertEquals(0, gate.gated(base + 2_000, base + 9_000, 12, true))
        assertEquals(4, gate.gated(base + 9_000, base + 11_000, 4, true))
    }

    @Test
    fun `a lump reaching back past the ride's start is counted, one inside the ride is held`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.IN_VEHICLE, base + 600_000)
        // The process was dead for ten minutes and the counter hands over
        // everything since: part of it was walked before the ride.
        assertEquals(0, gate.gated(base, base + 610_000, 400, true))
        // A handful after a long quiet is what a ride's first false steps
        // look like, and those are held.
        assertEquals(6, gate.gated(base + 400_000, base + 620_000, 6, true))
        // And a lump that began inside the ride is the ride's.
        assertEquals(90, gate.gated(base + 700_000, base + 900_000, 90, true))
    }

    // ---- held is not gone ----------------------------------------------------

    @Test
    fun `walking, reported late, gives back the walk away from the ride`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        // Twenty minutes into the ride, false steps now and then...
        var at = base + 1_000_000
        var held = 0
        repeat(5) {
            held += gate.gated(at - 1_000, at, 2, true)
            at += 20_000
        }
        // ...then the user gets off and walks, and Activity Recognition
        // takes its usual minute to say so.
        val alighted = at
        var walked = 0
        var step = alighted + 600
        while (step < alighted + 60_000) {
            walked += gate.gated(step - 600, step, 1, true)
            step += 600
        }
        assertEquals(99, walked)
        val release = gate.onActivity(ActivityState.WALKING, alighted + 60_000)
        assertNotNull(release)
        assertEquals(VehicleGate.REASON_ON_FOOT, release!!.reason)
        // All of the walk, and the false steps of the ride's last half
        // minute with it - the price of asking nothing more of a walker.
        assertTrue("released ${release.steps}", release.steps in walked..walked + 4)
        assertFalse(gate.holding)
        // Each comes back with the span the counter first gave it.
        assertTrue(release.held.all { it.toMs - it.fromMs in 1..1_000 })
        // The ride's earlier false steps stay held: ten of them, less the
        // tail that came back.
        assertTrue(held - (release.steps - walked) >= 6)
    }

    @Test
    fun `running ends a ride as walking does`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.ON_BICYCLE, base)
        assertEquals(5, gate.gated(base + 9_000, base + 10_000, 5, true))
        assertEquals(5, gate.onActivity(ActivityState.RUNNING, base + 20_000)?.steps)
    }

    @Test
    fun `a ride that ends on still gives back only what the accelerometer vouches for`() {
        val gate = VehicleGate()
        val noise = road()
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        // Two minutes of road with a false step every four seconds, then
        // the user gets off, walks twenty seconds to a bench and sits.
        val fed = gate.ride(base, 120, 4_000, signal = noise)
        gate.ride(fed.lastMs + 40, 20, 600, fed) { walk(it) }
        gate.ride(fed.lastMs + 40, 10, 0, fed) { 0.0 }
        assertTrue(fed.held > 55)
        assertEquals(0, fed.released)
        val release = gate.onActivity(ActivityState.STILL, fed.lastMs)
        assertNotNull(release)
        assertEquals(VehicleGate.REASON_LEFT_VEHICLE, release!!.reason)
        // The walk to the bench - thirty-three steps, of which the first
        // and last second sit at the edge of what the witness could see -
        // and none, or next to none, of the road before it.
        assertTrue("released ${release.steps}", release.steps in 27..35)
        assertFalse(gate.holding)
    }

    @Test
    fun `walking soon after an end on still claims the rest`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        // No accelerometer at all - a phone whose sensor sleeps with the
        // screen - so nothing is vouched for when the ride ends on "left
        // the vehicle" alone.
        var held = 0
        var step = base + 300_000
        while (step < base + 330_000) {
            held += gate.gated(step - 600, step, 1, true)
            step += 600
        }
        assertNull(gate.onActivity(ActivityState.UNKNOWN, base + 330_000))
        assertFalse(gate.holding)
        // "Walking" follows seconds later, as it does when the two are sent
        // apart, and the held walk is given back whole.
        val release = gate.onActivity(ActivityState.WALKING, base + 338_000)
        assertEquals(held, release?.steps)
        // Once only.
        assertNull(gate.onActivity(ActivityState.WALKING, base + 340_000))
    }

    @Test
    fun `walking long after an end on still claims nothing`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        assertEquals(8, gate.gated(base + 9_000, base + 10_000, 8, true))
        assertNull(gate.onActivity(ActivityState.STILL, base + 20_000))
        // A bus at a red light, and a passenger walking away ten minutes
        // on: those eight were the bus.
        assertNull(gate.onActivity(ActivityState.WALKING, base + 620_000))
    }

    @Test
    fun `a new ride before anyone walked keeps what the last one held`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        assertEquals(8, gate.gated(base + 9_000, base + 10_000, 8, true))
        assertNull(gate.onActivity(ActivityState.STILL, base + 20_000))
        // The light turns green.
        assertNull(gate.onActivity(ActivityState.IN_VEHICLE, base + 50_000))
        assertTrue(gate.holding)
        // Getting off later gives back only what is recent, never the
        // eight from before the light.
        assertEquals(3, gate.gated(base + 400_000, base + 401_000, 3, true))
        assertEquals(3, gate.onActivity(ActivityState.WALKING, base + 410_000)?.steps)
    }

    @Test
    fun `only the last minute and a half comes back on walking`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        assertEquals(5, gate.gated(base + 9_000, base + 10_000, 5, true))
        assertEquals(6, gate.gated(base + 99_000, base + 100_000, 6, true))
        assertEquals(7, gate.gated(base + 149_000, base + 150_000, 7, true))
        val release = gate.onActivity(ActivityState.WALKING, base + 195_000)
        assertEquals(7, release?.steps)
    }

    // ---- the accelerometer, for the walker -----------------------------------

    @Test
    fun `a walk the accelerometer confirms pauses holding and gives back what it held`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        // Activity Recognition has not noticed yet. The user walks.
        val fed = gate.ride(base, 45, 600, signal = ::walk)
        assertEquals(listOf(VehicleGate.PAUSED_WALKING), fed.changes)
        // Everything held while the witness made sure is given back, and
        // nothing after that was held at all: twenty-six seconds of steps,
        // which is twenty-four seconds of rhythm and the three it takes to
        // see the first.
        assertEquals(fed.held, fed.released)
        assertTrue("held ${fed.held}", fed.held in 40..50)
        assertEquals(74, fed.steps)
        // Not holding, and still a ride: nothing has said it is over.
        assertFalse(gate.holding)
        assertTrue(gate.inRide)
        assertEquals(0, gate.gated(fed.lastMs, fed.lastMs + 600, 1, true))
    }

    @Test
    fun `holding starts again when the walk stops and nothing has said the ride is over`() {
        val gate = VehicleGate()
        val noise = road()
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        // Down a train's corridor and back to a seat.
        val fed = gate.ride(base, 40, 600, signal = ::walk)
        assertEquals(VehicleGate.PAUSED_WALKING, fed.change)
        gate.ride(fed.lastMs + 40, 120, 3_000, fed) { noise(it) }
        assertEquals(listOf(VehicleGate.PAUSED_WALKING, VehicleGate.RESUMED), fed.changes)
        assertTrue(gate.holding)
        // The rest of the journey is held, as it would have been had the
        // walk never happened; 2.9.0 counted all of it.
        val heldBefore = fed.held
        val stepsBefore = fed.steps
        gate.ride(fed.lastMs + 40, 300, 3_000, fed) { noise(it + 160.0) }
        assertEquals(fed.steps - stepsBefore, fed.held - heldBefore)
        assertEquals(99, fed.steps - stepsBefore)
    }

    @Test
    fun `a walk whose rhythm falters is still the walk`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        val walked = gate.ride(base, 40, 600, signal = ::walk)
        assertEquals(listOf(VehicleGate.PAUSED_WALKING), walked.changes)
        // Ten seconds the accelerometer cannot read a stride in - the phone
        // taken out and typed on - and the walk goes on. Fewer seconds of
        // rhythm than it takes to confirm a walk, more than half: not taken
        // for over, and not a step of it held.
        val faltered = gate.ride(walked.lastMs + 40, 10, 600, signal = road())
        val on = gate.ride(faltered.lastMs + 40, 30, 600, signal = ::walk)
        assertTrue(faltered.changes.toString(), faltered.changes.isEmpty())
        assertTrue(on.changes.toString(), on.changes.isEmpty())
        assertEquals(0, faltered.held + on.held)
        assertTrue(gate.walking)
        // Sitting down again is seen within the half minute.
        val sat = gate.ride(on.lastMs + 40, 25, 0, signal = road())
        assertEquals(listOf(VehicleGate.RESUMED), sat.changes)
        assertTrue(gate.holding)
    }

    @Test
    fun `three minutes of walking ends a ride nobody said was over`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        val fed = gate.ride(base, 240, 600, signal = ::walk)
        assertEquals(listOf(VehicleGate.PAUSED_WALKING, VehicleGate.ENDED_WALKING), fed.changes)
        assertFalse(gate.inRide)
        // And stays over until a vehicle is reported afresh.
        assertEquals(0, gate.gated(fed.lastMs, fed.lastMs + 600, 1, true))
        gate.onActivity(ActivityState.IN_VEHICLE, fed.lastMs + 10_000)
        assertEquals(1, gate.gated(fed.lastMs + 10_000, fed.lastMs + 10_600, 1, true))
    }

    @Test
    fun `a ride on a rough road is held from end to end`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        val fed = gate.ride(base, 900, 3_000, signal = road())
        assertEquals(299, fed.held)
        assertEquals(0, fed.released)
        assertNull(fed.ended)
        assertTrue(gate.holding)
    }

    @Test
    fun `what the accelerometer saw before the ride is no evidence about its end`() {
        val gate = VehicleGate()
        // A walk to the bus stop, seen because another ride had just ended
        // - then the bus. The walk before must not confirm a walk during.
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        val walked = gate.ride(base, 40, 0, signal = ::walk)
        gate.onActivity(ActivityState.STILL, walked.lastMs)
        assertFalse(gate.inRide)
        gate.onActivity(ActivityState.IN_VEHICLE, base + 41_000)
        val fed = gate.ride(base + 41_000, 20, 2_000, signal = road())
        assertTrue(fed.changes.isEmpty())
        assertTrue(gate.holding)
        assertEquals(9, fed.held)
    }

    @Test
    fun `a train standing at a station is still the ride when it moves off`() {
        val gate = VehicleGate()
        val noise = road()
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        // Ten minutes at a platform with the phone on the seat, nothing
        // from Activity Recognition, and on again.
        val fed = gate.ride(base, 600, 0) { 0.0 }
        assertTrue(fed.changes.isEmpty())
        assertTrue(gate.holding)
        gate.ride(fed.lastMs + 40, 60, 3_000, fed) { noise(it) }
        assertEquals(19, fed.held)
    }

    @Test
    fun `half an hour of a phone lying still ends the ride`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        assertEquals(4, gate.gated(base + 1_000, base + 2_000, 4, true))
        // A missed "left the vehicle": the phone is on a table.
        val fed = gate.ride(base + 3_000, 29 * 60, 0) { 0.0 }
        assertNull(fed.ended)
        assertTrue(gate.holding)
        gate.ride(fed.lastMs + 40, 70, 0, fed) { 0.0 }
        assertEquals(VehicleGate.ENDED_STILL, fed.ended)
        assertFalse(gate.inRide)
        // Nothing is given back for it - there was no walk - and nothing
        // more is held.
        assertEquals(0, fed.released)
        assertEquals(0, gate.gated(fed.lastMs, fed.lastMs + 1_000, 3, true))
    }

    @Test
    fun `no ride is held for more than twelve hours on one report`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        val eleven = base + 11 * 3_600_000L
        assertEquals(2, gate.gated(eleven, eleven + 1_000, 2, true))
        val thirteen = base + 13 * 3_600_000L
        assertEquals(0, gate.gated(thirteen, thirteen + 1_000, 2, true))
        assertFalse(gate.holding)
    }

    @Test
    fun `stopping Activity Recognition ends whatever it had said`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        assertEquals(2, gate.gated(base + 1_000, base + 2_000, 2, true))
        gate.onActivityStopped()
        assertFalse(gate.holding)
        assertEquals(0, gate.gated(base + 2_000, base + 3_000, 2, true))
        // And nothing held on its word is owed to a "walking" it can no
        // longer send.
        assertNull(gate.onActivity(ActivityState.WALKING, base + 4_000))
    }

    // ---- the ledger ------------------------------------------------------------

    @Test
    fun `a held step waits two minutes to be given back and no longer`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        assertEquals(5, gate.gated(base + 1_000, base + 2_000, 5, true))
        // Kept alive only by later steps being held: the ledger is trimmed
        // as it is written.
        assertEquals(1, gate.gated(base + 200_000, base + 201_000, 1, true))
        // "Walking", dated - absurdly - before the first of them: only the
        // one still in the ledger can come back.
        assertEquals(1, gate.onActivity(ActivityState.WALKING, base + 50_000)?.steps)
    }

    @Test
    fun `a day that starts over owes nothing held before it`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        assertEquals(30, gate.gated(base + 9_000, base + 10_000, 30, true))
        // resetToday() in the middle of a ride.
        gate.forgetHeld()
        assertTrue(gate.holding)
        assertEquals(4, gate.gated(base + 19_000, base + 20_000, 4, true))
        // Only what was held since can come back.
        assertEquals(4, gate.onActivity(ActivityState.WALKING, base + 30_000)?.steps)
        // And a "walking" owed from an end on still is forgotten with it.
        gate.onActivity(ActivityState.IN_VEHICLE, base + 60_000)
        assertEquals(5, gate.gated(base + 69_000, base + 70_000, 5, true))
        assertNull(gate.onActivity(ActivityState.STILL, base + 80_000))
        gate.forgetHeld()
        assertNull(gate.onActivity(ActivityState.WALKING, base + 85_000))
    }

    @Test
    fun `new rules keep the ride and start the witness over`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        val fed = gate.ride(base, 20, 600, signal = ::walk)
        val before = gate.witness
        gate.setRules(WitnessRules(stillVariance = 0.05))
        assertTrue(gate.holding)
        assertTrue(gate.witness !== before)
        assertEquals(0.05, gate.witness.rules.stillVariance, 0.0)
        // Twenty seconds of walk were not enough, and they do not carry
        // over: the new witness needs its own thirty.
        gate.ride(fed.lastMs + 40, 20, 600, fed) { walk(it + 20.0) }
        assertNull(fed.ended)
        // The same rules again change nothing.
        val kept = gate.witness
        gate.setRules(WitnessRules(stillVariance = 0.05))
        assertTrue(gate.witness === kept)
    }

    // ---- Activity Recognition's events, in order -------------------------------

    @Test
    fun `an exit only ends the activity it names`() {
        fun enter(state: ActivityState) = ActivityEvent(state, enter = true, atMillis = base)
        fun exit(state: ActivityState) = ActivityEvent(state, enter = false, atMillis = base)
        // The usual pair, in either order.
        assertEquals(
            ActivityState.WALKING,
            ActivityState.IN_VEHICLE.after(exit(ActivityState.IN_VEHICLE)).after(enter(ActivityState.WALKING))
        )
        assertEquals(
            ActivityState.WALKING,
            ActivityState.IN_VEHICLE.after(enter(ActivityState.WALKING)).after(exit(ActivityState.IN_VEHICLE))
        )
        // A bare exit leaves nothing known.
        assertEquals(ActivityState.UNKNOWN, ActivityState.IN_VEHICLE.after(exit(ActivityState.IN_VEHICLE)))
        // A late "left still" does not wipe out the ride that began after it.
        assertEquals(ActivityState.IN_VEHICLE, ActivityState.IN_VEHICLE.after(exit(ActivityState.STILL)))
        assertTrue(ActivityState.IN_VEHICLE.carried && ActivityState.ON_BICYCLE.carried)
        assertFalse(ActivityState.STILL.carried || ActivityState.WALKING.carried || ActivityState.UNKNOWN.carried)
        assertTrue(ActivityState.WALKING.onFoot && ActivityState.RUNNING.onFoot)
        assertFalse(ActivityState.STILL.onFoot || ActivityState.IN_VEHICLE.onFoot)
    }

    @Test
    fun `left one activity and entered another is one transition`() {
        fun enter(state: ActivityState, at: Long) = ActivityEvent(state, enter = true, atMillis = at)
        fun exit(state: ActivityState, at: Long) = ActivityEvent(state, enter = false, atMillis = at)
        val ride = ActivityState.IN_VEHICLE

        // Got off and walked: only "walking" is acted on, so the whole of
        // the last minute and a half comes back at once.
        assertEquals(
            listOf(ActivityState.WALKING to base + 5L),
            ride.through(listOf(exit(ActivityState.IN_VEHICLE, base), enter(ActivityState.WALKING, base + 5L)))
        )
        // A bus read as a bicycle in slow traffic is the same ride.
        val renamed = ride.through(
            listOf(exit(ActivityState.IN_VEHICLE, base), enter(ActivityState.ON_BICYCLE, base))
        )
        assertEquals(listOf(ActivityState.ON_BICYCLE to base), renamed)
        val gate = VehicleGate()
        gate.onActivity(ride, base - 600_000L)
        assertEquals(4, gate.gated(base - 2_000L, base - 1_000L, 4, true))
        for ((state, at) in renamed) assertNull(gate.onActivity(state, at))
        assertTrue(gate.holding)
        assertEquals(base - 600_000L, gate.vehicleSince)

        // An exit with nothing after it is all that is known.
        assertEquals(
            listOf(ActivityState.UNKNOWN to base),
            ride.through(listOf(exit(ActivityState.IN_VEHICLE, base)))
        )
        // A late "left still" changes nothing and is not reported as a change.
        assertTrue(ride.through(listOf(exit(ActivityState.STILL, base))).isEmpty())
        // A broadcast that waited: off the bus, a walk, and onto another.
        assertEquals(
            listOf(ActivityState.WALKING to base, ActivityState.IN_VEHICLE to base + 300_000L),
            ride.through(
                listOf(
                    exit(ActivityState.IN_VEHICLE, base), enter(ActivityState.WALKING, base),
                    exit(ActivityState.WALKING, base + 300_000L), enter(ActivityState.IN_VEHICLE, base + 300_000L)
                )
            )
        )
        // Entering what the phone is already in is no change either.
        assertTrue(ride.through(listOf(enter(ActivityState.IN_VEHICLE, base))).isEmpty())
        assertTrue(ActivityState.UNKNOWN.through(emptyList()).isEmpty())
    }

    // ---- a bicycle ------------------------------------------------------------------

    @Test
    fun `pedalling is not a walk that ends a bicycle ride`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.ON_BICYCLE, base)
        // A pedalling leg is the same rhythm as a stride: the witness would
        // confirm it thirty seconds in. On a bicycle it is not asked.
        val fed = gate.ride(base, 600, 700, signal = ::walk)
        assertTrue(fed.changes.isEmpty())
        assertEquals(0, fed.released)
        assertEquals(fed.steps, fed.held)
        assertTrue(gate.holding)
        // Activity Recognition saying "walking" gives back the last ninety
        // seconds - the walk away from the bike, and the last of the ride.
        val release = gate.onActivity(ActivityState.WALKING, fed.lastMs)
        assertTrue("released ${release?.steps}", (release?.steps ?: 0) in 120..130)
        assertFalse(gate.inRide)
    }

    @Test
    fun `a bicycle ride ending on still gives nothing back on the accelerometer's word`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.ON_BICYCLE, base)
        val fed = gate.ride(base, 120, 700, signal = ::walk)
        // On a bicycle every second "vouches": pedalling. None is believed.
        assertNull(gate.onActivity(ActivityState.STILL, fed.lastMs))
        // A "walking" that follows still claims the rest, as after any ride.
        assertTrue((gate.onActivity(ActivityState.WALKING, fed.lastMs + 5_000)?.steps ?: 0) > 100)
    }

    @Test
    fun `a bus read as a bicycle stops listening to the accelerometer`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        val fed = gate.ride(base, 40, 600, signal = ::walk)
        assertFalse(gate.holding)
        gate.onActivity(ActivityState.ON_BICYCLE, fed.lastMs)
        assertTrue(gate.holding)
        assertEquals(ActivityState.ON_BICYCLE, gate.carried)
    }

    @Test
    fun `a bicycle read as a vehicle gives the accelerometer its say from that moment`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.ON_BICYCLE, base)
        // Two minutes of pedalling, every "step" of it held.
        val pedalled = gate.ride(base, 120, 700, signal = ::walk)
        assertEquals(pedalled.steps, pedalled.held)
        // The same ride, now reported as a vehicle. What the accelerometer
        // watched while it had no say is not a walk confirmed on the spot...
        val flipAt = pedalled.lastMs + 40
        assertNull(gate.onActivity(ActivityState.IN_VEHICLE, flipAt))
        assertEquals(ActivityState.IN_VEHICLE, gate.carried)
        val first = gate.ride(flipAt, 20, 700, signal = ::walk)
        assertTrue(first.changes.isEmpty())
        assertEquals(0, first.released)
        assertTrue(gate.holding)
        // ...and when it does confirm one, thirty seconds on, it gives back
        // only what was held since: not the pedalling before.
        val then = gate.ride(first.lastMs + 40, 20, 700, signal = ::walk)
        assertEquals(listOf(VehicleGate.PAUSED_WALKING), then.changes)
        assertTrue("released ${then.released}", then.released in 1..(first.held + then.held))
        assertTrue(gate.walking)
    }

    @Test
    fun `a walk being confirmed in a ride is walking, and nothing else is`() {
        val gate = VehicleGate()
        assertFalse(gate.walking)
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        assertFalse(gate.walking)
        val walked = gate.ride(base, 40, 600, signal = ::walk)
        assertTrue(gate.walking)
        assertFalse(gate.holding)
        // The walk stops: holding again, and no longer walking.
        gate.ride(walked.lastMs + 40, 30, 0, signal = road())
        assertFalse(gate.walking)
        assertTrue(gate.holding)
        // Nor once the ride is over.
        gate.ride(walked.lastMs + 40 + 30_000, 40, 600, signal = ::walk)
        assertTrue(gate.walking)
        gate.onActivity(ActivityState.WALKING, walked.lastMs + 80_000)
        assertFalse(gate.walking)
    }

    // ---- across a process death ---------------------------------------------------

    @Test
    fun `a ride and its held steps survive the process`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        assertEquals(5, gate.gated(base + 9_000, base + 10_000, 5, true))
        assertEquals(6, gate.gated(base + 59_000, base + 60_000, 6, true))
        val saved = gate.saved()

        // The system kills the process; a new one starts thirty seconds on.
        val next = VehicleGate()
        next.restore(saved, base + 90_000)
        assertTrue(next.holding)
        assertEquals(base, next.vehicleSince)
        assertEquals(ActivityState.IN_VEHICLE, next.carried)
        assertEquals(3, next.gated(base + 95_000, base + 96_000, 3, true))
        // And the walk it was holding comes back when "walking" arrives -
        // in 2.9.0 it stayed held for good.
        assertEquals(14, next.onActivity(ActivityState.WALKING, base + 100_000)?.steps)
    }

    @Test
    fun `what was saved too long ago is not restored`() {
        val gate = VehicleGate()
        gate.onActivity(ActivityState.IN_VEHICLE, base)
        assertEquals(5, gate.gated(base + 9_000, base + 10_000, 5, true))
        val saved = gate.saved()
        // Three minutes on: the ride stands, the held steps are past keeping.
        val later = VehicleGate().also { it.restore(saved, base + 190_000) }
        assertTrue(later.inRide)
        assertNull(later.onActivity(ActivityState.WALKING, base + 191_000))
        // Thirteen hours on: no ride either.
        val much = VehicleGate().also { it.restore(saved, base + 13 * 3_600_000L) }
        assertFalse(much.inRide)
        assertEquals(0, much.gated(base + 13 * 3_600_000L, base + 13 * 3_600_000L + 1_000, 2, true))
        // Nothing saved, nothing restored.
        val empty = VehicleGate().also { it.restore(VehicleGate().saved(), base) }
        assertFalse(empty.inRide)
    }

    // ---- a day in the life ------------------------------------------------------

    @Test
    fun `a commute holds the bus and none of the walking`() {
        val gate = VehicleGate()
        val noise = road()
        var counted = 0
        var held = 0
        var released = 0

        fun stretch(fromMs: Long, seconds: Int, stepEveryMs: Long, signal: (Double) -> Double): Long {
            val fed = gate.ride(fromMs, seconds, stepEveryMs, signal = signal)
            held += fed.held
            released += fed.released
            counted += fed.steps
            return fed.lastMs + 40
        }

        // Five minutes' walk to the stop; nothing is being watched.
        var at = stretch(base, 300, 600, ::walk)
        assertEquals(0, held)
        val walkedToStop = counted
        // On the bus. Activity Recognition notices a minute in; the bus's
        // false steps of that minute are counted, which is its latency.
        at = stretch(at, 60, 4_000, noise)
        val leaked = counted - walkedToStop
        assertEquals(0, held)
        gate.onActivity(ActivityState.IN_VEHICLE, at)
        // Twenty minutes of ride.
        at = stretch(at, 1_200, 4_000, noise)
        val rideSteps = counted - walkedToStop - leaked
        assertEquals(rideSteps, held)
        // Off the bus and walking; "walking" arrives fifty seconds later.
        val beforeWalkHome = counted
        at = stretch(at, 50, 600, ::walk)
        gate.onActivity(ActivityState.WALKING, at)?.let { released += it.steps }
        // Whichever got there first - the accelerometer at thirty seconds
        // or Activity Recognition at fifty - the walk is whole.
        stretch(at, 250, 600) { walk(it + 50.0) }
        val walkedHome = counted - beforeWalkHome

        val shown = counted - held + released
        val real = walkedToStop + walkedHome
        // Every real step is in the total...
        assertTrue("shown $shown, real $real", shown >= real)
        // ...and of the bus's 314, only its first minute and its last few
        // seconds are.
        val busInTotal = shown - real
        assertTrue("bus steps counted: $busInTotal of ${leaked + rideSteps}", busInTotal <= leaked + 10)
        assertTrue(held - released >= rideSteps - 10)
    }
}
