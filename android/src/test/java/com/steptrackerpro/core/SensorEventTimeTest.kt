package com.steptrackerpro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The contract: a sample is placed at the time it was taken - however late a
 * sensor hub that held it while the CPU slept delivers it - and a sample
 * whose timestamp cannot say is placed at its arrival and marked untimed,
 * never passed off as timed. A screen-off walk delivered in one batch is
 * then judged on the minutes it was walked in.
 */
class SensorEventTimeTest {

    private val minute = 60_000L

    /** 10:00 UTC, so the detector's night rule stays out of the way. */
    private val t0 = ZonedDateTime.of(2026, 9, 14, 10, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    /** Ten hours of uptime, in nanos. */
    private val elapsedNow = 10 * 3_600_000L * 1_000_000L

    /** The elapsed-realtime timestamp of a sample taken [ageMs] before now. */
    private fun agedBy(ageMs: Long) = elapsedNow - ageMs * 1_000_000L

    @Test
    fun `a sample delivered as it happens is placed at its own time`() {
        val now = t0 + 30_000L
        assertEquals(
            SensorEventTime.Resolved(now - 120L, timed = true),
            SensorEventTime.resolve(agedBy(120L), elapsedNow, now)
        )
    }

    @Test
    fun `a sample the hub held for minutes keeps the time it was taken`() {
        val now = t0 + 10 * minute
        for (age in listOf(61_000L, 5 * minute, SensorEventTime.MAX_AGE_MS)) {
            assertEquals(
                SensorEventTime.Resolved(now - age, timed = true),
                SensorEventTime.resolve(agedBy(age), elapsedNow, now)
            )
        }
    }

    @Test
    fun `a sample older than the batching window is untimed at its arrival`() {
        val now = t0 + 10 * minute
        assertEquals(
            SensorEventTime.Resolved(now, timed = false),
            SensorEventTime.resolve(agedBy(SensorEventTime.MAX_AGE_MS + 1L), elapsedNow, now)
        )
    }

    @Test
    fun `a HAL that stamps wall-clock nanos is read on the wall clock`() {
        val now = t0 + 10 * minute
        val taken = now - 3 * minute
        assertEquals(
            SensorEventTime.Resolved(taken, timed = true),
            SensorEventTime.resolve(taken * 1_000_000L, elapsedNow, now)
        )
    }

    @Test
    fun `a timestamp just ahead of the clock is now, one far ahead is untimed`() {
        val now = t0 + 30_000L
        assertEquals(
            SensorEventTime.Resolved(now, timed = true),
            SensorEventTime.resolve(agedBy(-2_000L), elapsedNow, now)
        )
        assertEquals(
            SensorEventTime.Resolved(now, timed = false),
            SensorEventTime.resolve(agedBy(-SensorEventTime.MAX_SKEW_MS - 1_000L), elapsedNow, now)
        )
    }

    @Test
    fun `a timestamp on no clock at all is untimed`() {
        val now = t0 + 30_000L
        for (timestamp in listOf(0L, -5L, 1_000L, Long.MAX_VALUE)) {
            assertEquals(
                SensorEventTime.Resolved(now, timed = false),
                SensorEventTime.resolve(timestamp, elapsedNow, now)
            )
        }
    }

    // ---- end to end: a screen-off walk delivered in one batch -----------------

    /** Five minutes at 110 steps a minute: when each step was taken, and the batch's arrival. */
    private val stepTimes = List(550) { i -> t0 + 1_000L + i * 60_000L / 110 }
    private val arrival = t0 + 6 * minute + 20_000L

    /** Feeds one sample per step through the timeline, as the engine does, and judges the day. */
    private fun judge(resolve: (takenAt: Long) -> SensorEventTime.Resolved): Pair<List<MinuteSample>, DayEvaluation> {
        val timeline = StepTimeline()
        // The walk's first step was delivered live, before the screen went off.
        var previous = t0
        for (taken in stepTimes) {
            val at = resolve(taken)
            timeline.record(previous, at.atMillis, 1, charging = false, activity = ActivityState.UNKNOWN, timed = at.timed)
            previous = at.atMillis
        }
        val minutes = timeline.drain()
        return minutes to FraudDetector.evaluate(minutes, emptyList(), IntegrityRules(), ZoneOffset.UTC)
    }

    @Test
    fun `a batch delivered after a screen-off walk is judged on the minutes it was walked in`() {
        val (minutes, verdict) = judge { taken ->
            SensorEventTime.resolve(agedBy(arrival - taken), elapsedNow, arrival)
        }
        assertTrue(verdict.flags.isEmpty())
        assertEquals(0, verdict.flaggedSteps)
        assertEquals(550, minutes.sumOf { it.steps })
        assertEquals(0, minutes.sumOf { it.untimedSteps })
        // Spread over the minutes of the walk, none of them faster than a walk.
        assertEquals(List(6) { t0 + it * minute }, minutes.map { it.minuteStart })
        assertTrue(minutes.all { it.steps <= 111 })
    }

    @Test
    fun `stamped with its arrival as if that were known, the same batch reads as a shake`() {
        // What the service did before: every sample over a minute old was
        // given the time it arrived and treated as timed.
        val (minutes, verdict) = judge { SensorEventTime.Resolved(arrival, timed = true) }
        assertEquals(550, minutes.single().steps)
        assertEquals(FlagSeverity.STRONG, verdict.flags.single { it.type == IntegrityFlag.CADENCE }.severity)
        assertEquals(550, verdict.flaggedSteps)
    }

    @Test
    fun `a batch too old to place is parked untimed and judged on nothing`() {
        val late = SensorEventTime.MAX_AGE_MS + 10 * minute
        val (minutes, verdict) = judge { taken ->
            SensorEventTime.resolve(agedBy(arrival + late - taken), elapsedNow, arrival + late)
        }
        assertTrue(verdict.flags.isEmpty())
        assertEquals(0, minutes.sumOf { it.steps })
        assertEquals(550, minutes.single().untimedSteps)
        assertEquals(MinuteAttribution.minuteOf(arrival + late), minutes.single().minuteStart)
    }
}
