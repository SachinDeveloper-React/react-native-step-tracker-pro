package com.steptrackerpro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract: a delta lands on the minutes it was taken in when its timing
 * is known, and parks untimed at its arrival minute when it is not - never
 * smeared across a gap, which would fabricate exactly the steady cadence the
 * detector looks for.
 */
class StepTimelineTest {

    private val t0 = 1_757_800_800_000L // a whole minute

    @Test
    fun `a delta inside one minute lands on that minute`() {
        val shares = MinuteAttribution.attribute(t0 + 1_000, t0 + 1_600, 1)
        assertEquals(listOf(MinuteAttribution.Share(t0, 1, 0)), shares)
    }

    @Test
    fun `a delta straddling a minute boundary is split by overlap and sums back`() {
        // 30 s before the boundary and 10 s after: three quarters and a quarter.
        val shares = MinuteAttribution.attribute(t0 + 30_000, t0 + 70_000, 40)
        assertEquals(2, shares.size)
        assertEquals(30, shares[0].timed)
        assertEquals(10, shares[1].timed)
        assertEquals(t0 + 60_000, shares[1].minuteStart)
        assertEquals(40, shares.sumOf { it.timed })
    }

    @Test
    fun `rounding never loses or invents a step`() {
        for (steps in 1..50) {
            val shares = MinuteAttribution.attribute(t0 + 59_999, t0 + 60_001 + steps * 7L, steps)
            assertEquals(steps, shares.sumOf { it.timed + it.untimed })
        }
    }

    @Test
    fun `a few steps after a long rest are the walk starting, timed at arrival`() {
        val shares = MinuteAttribution.attribute(t0 - 3_600_000, t0 + 5_000, 3)
        assertEquals(listOf(MinuteAttribution.Share(t0, 3, 0)), shares)
    }

    @Test
    fun `a lump after a silence is parked untimed at arrival, never spread`() {
        val shares = MinuteAttribution.attribute(t0 - 1_800_000, t0 + 5_000, 2_400)
        assertEquals(listOf(MinuteAttribution.Share(t0, 0, 2_400)), shares)
    }

    @Test
    fun `no previous sample or a clock that went backwards keeps only the arrival`() {
        assertEquals(listOf(MinuteAttribution.Share(t0, 5, 0)), MinuteAttribution.attribute(0L, t0 + 1, 5))
        assertEquals(listOf(MinuteAttribution.Share(t0, 0, 90)), MinuteAttribution.attribute(t0 + 9_000, t0 + 1, 90))
        assertTrue(MinuteAttribution.attribute(t0, t0 + 1, 0).isEmpty())
    }

    @Test
    fun `the timeline tags charging and activity and drains once`() {
        val timeline = StepTimeline()
        timeline.record(t0 + 1_000, t0 + 2_000, 2, charging = true, activity = ActivityState.UNKNOWN)
        timeline.record(t0 + 2_000, t0 + 3_000, 3, charging = false, activity = ActivityState.IN_VEHICLE)
        timeline.record(t0 + 61_000, t0 + 62_000, 4, charging = false, activity = ActivityState.STILL)
        val drained = timeline.drain()
        assertEquals(2, drained.size)
        assertEquals(MinuteSample(t0, 5, 0, chargingSteps = 2, stillSteps = 0, vehicleSteps = 3), drained[0])
        assertEquals(MinuteSample(t0 + 60_000, 4, 0, chargingSteps = 0, stillSteps = 4, vehicleSteps = 0), drained[1])
        assertTrue(timeline.drain().isEmpty())
    }

    @Test
    fun `the pending buffer is bounded`() {
        val timeline = StepTimeline()
        repeat(StepTimeline.MAX_PENDING_MINUTES + 10) { i ->
            val at = t0 + i * 60_000L
            timeline.record(at, at + 500, 1, charging = false, activity = ActivityState.UNKNOWN)
        }
        val drained = timeline.drain()
        assertEquals(StepTimeline.MAX_PENDING_MINUTES, drained.size)
        // The oldest were dropped, not the newest.
        assertEquals(t0 + 10 * 60_000L, drained.first().minuteStart)
    }
}
