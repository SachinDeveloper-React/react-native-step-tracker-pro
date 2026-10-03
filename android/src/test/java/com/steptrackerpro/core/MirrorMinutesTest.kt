package com.steptrackerpro.core

import com.steptrackerpro.health.HealthConnectManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * `healthConnectWriteGranularity: 'minute'`: where a delta's steps go, what
 * a day's records should be, and which days may hold minute records. The
 * contract that matters is the one Health Connect cannot check for us: the
 * records add up to the day's total exactly, and no two of them overlap.
 */
class MirrorMinutesTest {

    private val t0 = 1_757_800_800_000L // a whole minute
    private val minute = MinuteWritePlan.MINUTE_MS

    // ---- MinuteAttribution.spread ---------------------------------------------

    @Test
    fun `an untimed sample goes to the minute it arrived in`() {
        assertEquals(mapOf(t0 to 30), MinuteAttribution.spread(t0 - 600_000, t0 + 5_000, 30, timed = false))
    }

    @Test
    fun `a few steps after a long rest are the walk starting, in the arrival minute`() {
        assertEquals(mapOf(t0 to 3), MinuteAttribution.spread(t0 - 3_600_000, t0 + 5_000, 3))
    }

    @Test
    fun `a lump after a silence is spread evenly over the window it arrived after`() {
        // Ten minutes of silence, then 600 steps: about 60 a minute.
        val shares = MinuteAttribution.spread(t0, t0 + 10 * minute, 600)
        assertEquals(600, shares.values.sum())
        assertEquals((0 until 10).map { t0 + it * minute }, shares.keys.toList())
        assertTrue(shares.values.all { it == 60 })
    }

    @Test
    fun `a delta straddling a minute boundary is split by overlap, as for integrity`() {
        assertEquals(
            mapOf(t0 to 30, t0 + minute to 10),
            MinuteAttribution.spread(t0 + 30_000, t0 + 70_000, 40)
        )
    }

    @Test
    fun `no previous sample, or a clock that went back, leaves only the arrival`() {
        assertEquals(mapOf(t0 to 12), MinuteAttribution.spread(0L, t0 + 1_000, 12))
        assertEquals(mapOf(t0 to 12), MinuteAttribution.spread(t0 + 9_000, t0 + 1_000, 12))
        assertTrue(MinuteAttribution.spread(t0, t0 + 1_000, 0).isEmpty())
    }

    @Test
    fun `spreading never loses or invents a step`() {
        for (steps in listOf(1, 7, 21, 99, 1_001, 4_321)) {
            for (gap in listOf(1_000L, 59_000L, 61_000L, 7 * minute + 13_000L, 3_600_000L)) {
                assertEquals(steps, MinuteAttribution.spread(t0 + 17_000, t0 + 17_000 + gap, steps).values.sum())
            }
        }
    }

    @Test
    fun `the buffer adds up what it is given and empties on drain`() {
        val buffer = MirrorMinuteBuffer()
        buffer.record(t0 + 1_000, t0 + 2_000, 2)
        buffer.record(t0 + 2_000, t0 + 3_000, 3)
        buffer.record(t0 + minute + 500, t0 + minute + 900, 1, timed = false)
        assertEquals(mapOf(t0 to 5, t0 + minute to 1), buffer.drain())
        assertTrue(buffer.drain().isEmpty())
        buffer.record(t0 + 1_000, t0 + 2_000, 2)
        buffer.clear()
        assertTrue(buffer.drain().isEmpty())
    }

    // ---- MinuteWritePlan ------------------------------------------------------

    private val dayStart = t0
    private val dayEnd = t0 + 24 * 60 * minute

    private fun m(index: Int, steps: Int, written: Int = 0) =
        MinuteWritePlan.Minute(t0 + index * minute, steps, written)

    /** Every record the plan leaves in place, as (start, end, steps), and checks none overlap. */
    private fun records(plan: MinuteWritePlan.Plan): List<Triple<Long, Long, Int>> {
        val out = plan.targets.filterValues { it > 0 }.map { (start, steps) -> Triple(start, start + minute, steps) } +
            listOfNotNull(plan.residualSpan?.takeIf { plan.residual > 0 }?.let { Triple(it.start, it.end, plan.residual) })
        val sorted = out.sortedBy { it.first }
        sorted.zipWithNext().forEach { (a, b) -> assertTrue("$a overlaps $b", a.second <= b.first) }
        return sorted
    }

    @Test
    fun `steps no minute holds go to the longest stretch no minute covers`() {
        // Minutes at 08:00 and 08:01; 1,000 steps credited by gap recovery.
        val plan = MinuteWritePlan.plan(listOf(m(480, 100), m(481, 80)), 1_180, dayStart, dayEnd)
        assertEquals(mapOf(t0 + 480 * minute to 100, t0 + 481 * minute to 80), plan.upserts)
        assertEquals(1_000, plan.residual)
        // 08:02 to midnight is longer than midnight to 08:00.
        assertEquals(MinuteWritePlan.Span(t0 + 482 * minute, dayEnd), plan.residualSpan)
        assertEquals(1_180, records(plan).sumOf { it.third })
    }

    @Test
    fun `a day with no minutes is one record over the whole day`() {
        val plan = MinuteWritePlan.plan(emptyList(), 5_000, dayStart, dayEnd)
        assertEquals(5_000, plan.residual)
        assertEquals(MinuteWritePlan.Span(dayStart, dayEnd), plan.residualSpan)
        assertTrue(plan.upserts.isEmpty() && plan.deletes.isEmpty())
    }

    @Test
    fun `only minutes that changed since the last write are written again`() {
        val plan = MinuteWritePlan.plan(
            listOf(m(10, 50, written = 50), m(11, 60, written = 40), m(12, 30)),
            140, dayStart, dayEnd
        )
        assertEquals(mapOf(t0 + 11 * minute to 60, t0 + 12 * minute to 30), plan.upserts)
        assertEquals(0, plan.residual)
        assertNull(plan.residualSpan)
    }

    @Test
    fun `a profile change writes every minute again`() {
        val minutes = listOf(m(10, 50, written = 50), m(11, 60, written = 60))
        assertTrue(MinuteWritePlan.plan(minutes, 110, dayStart, dayEnd).upserts.isEmpty())
        assertEquals(2, MinuteWritePlan.plan(minutes, 110, dayStart, dayEnd, rewrite = true).upserts.size)
    }

    @Test
    fun `exclude mode's flagged steps come out across the day, exactly`() {
        // 100 flagged steps out of the day's 400.
        val plan = MinuteWritePlan.plan(
            listOf(m(1, 100), m(2, 100), m(3, 100), m(4, 100)), 300, dayStart, dayEnd, observed = 400
        )
        assertEquals(0, plan.residual)
        assertEquals(listOf(75, 75, 75, 75), plan.targets.values.toList())
        assertEquals(300, records(plan).sumOf { it.third })
    }

    @Test
    fun `steps counted after the total was read come off the newest minutes only`() {
        // A sync during a walk: the minutes were flushed a moment after the
        // day's 140 was read, with 15 more steps in them.
        val minutes = listOf(m(10, 50, written = 50), m(11, 60, written = 60), m(12, 30, written = 30), m(13, 15))
        val plan = MinuteWritePlan.plan(minutes, 140, dayStart, dayEnd)
        assertEquals(mapOf(t0 + 10 * minute to 50, t0 + 11 * minute to 60, t0 + 12 * minute to 30, t0 + 13 * minute to 0), plan.targets)
        // Nothing already written moves; the new minute waits for the next sync.
        assertTrue(plan.upserts.isEmpty() && plan.deletes.isEmpty())
        assertEquals(0, plan.residual)
        // Both at once: the newest give back what came late, then exclude mode scales.
        val both = MinuteWritePlan.plan(minutes, 70, dayStart, dayEnd, observed = 140)
        assertEquals(70, both.targets.values.sum())
        assertEquals(0, both.targets[t0 + 13 * minute])
    }

    @Test
    fun `steps credited in one go stay unplaced while the newest minutes give back what came late`() {
        // 500 recovered after a reboot; the sensor saw 140, and 15 more since.
        val minutes = listOf(m(10, 50, written = 50), m(11, 60, written = 60), m(12, 30, written = 30), m(13, 15))
        val plan = MinuteWritePlan.plan(minutes, 640, dayStart, dayEnd, observed = 140)
        assertEquals(500, plan.residual)
        assertEquals(0, plan.targets[t0 + 13 * minute])
        assertTrue(plan.upserts.isEmpty())
        assertEquals(640, records(plan).sumOf { it.third })
    }

    @Test
    fun `a written minute left with nothing is deleted`() {
        val plan = MinuteWritePlan.plan(listOf(m(5, 0, written = 40), m(6, 20, written = 20)), 20, dayStart, dayEnd)
        assertEquals(listOf(t0 + 5 * minute), plan.deletes)
        assertTrue(plan.upserts.isEmpty())
        // A day emptied by exclude mode deletes every minute it wrote.
        val emptied = MinuteWritePlan.plan(listOf(m(5, 40, written = 40), m(6, 20, written = 20)), 0, dayStart, dayEnd)
        assertEquals(listOf(t0 + 5 * minute, t0 + 6 * minute), emptied.deletes)
        assertEquals(0, emptied.residual)
    }

    @Test
    fun `a written minute the day no longer spans is deleted and its steps go to the residual`() {
        // The zone moved: the minute now falls before the day's start.
        val plan = MinuteWritePlan.plan(
            listOf(MinuteWritePlan.Minute(dayStart - 5 * minute, 30, written = 30), m(100, 70)),
            100, dayStart, dayEnd
        )
        assertEquals(listOf(dayStart - 5 * minute), plan.deletes)
        assertEquals(30, plan.residual)
        assertEquals(100, records(plan).sumOf { it.third })
    }

    @Test
    fun `today ends now - nothing is planned past it`() {
        val now = t0 + 600 * minute + 30_000
        val plan = MinuteWritePlan.plan(listOf(m(599, 40), m(600, 10), m(700, 99)), 150, dayStart, now)
        // The minute at 11:40 has not happened yet: not written, its steps unplaced.
        assertFalse(plan.targets.containsKey(t0 + 700 * minute))
        assertEquals(100, plan.residual)
        assertEquals(MinuteWritePlan.Span(dayStart, t0 + 599 * minute), plan.residualSpan)
    }

    @Test
    fun `with every minute taken the rest is shared over the minutes`() {
        val start = t0
        val end = t0 + 3 * minute
        val plan = MinuteWritePlan.plan(listOf(m(0, 10), m(1, 10), m(2, 10)), 60, start, end)
        assertEquals(0, plan.residual)
        assertNull(plan.residualSpan)
        assertEquals(60, plan.targets.values.sum())
    }

    @Test
    fun `the records always add up and never overlap`() {
        val random = java.util.Random(42)
        repeat(300) {
            val minutes = (0 until random.nextInt(40)).map {
                m(random.nextInt(1_440), random.nextInt(120), written = if (random.nextBoolean()) random.nextInt(120) else 0)
            }.distinctBy { it.minuteStart }
            val total = random.nextInt(8_000)
            val plan = MinuteWritePlan.plan(minutes, total, dayStart, dayEnd)
            assertEquals(total, records(plan).sumOf { it.third })
            // Everything written before either stays, is rewritten, or goes.
            minutes.filter { it.written > 0 }.forEach { written ->
                val target = plan.targets[written.minuteStart] ?: 0
                assertTrue(
                    target == written.written || written.minuteStart in plan.upserts ||
                        written.minuteStart in plan.deletes
                )
            }
        }
    }

    @Test
    fun `the longest gap is the earliest of equals, and none when nothing is free`() {
        assertEquals(
            MinuteWritePlan.Span(t0, t0 + 10 * minute),
            MinuteWritePlan.longestGap(listOf(t0 + 10 * minute), t0, t0 + 21 * minute)
        )
        assertEquals(
            MinuteWritePlan.Span(t0 + 11 * minute, t0 + 30 * minute),
            MinuteWritePlan.longestGap(listOf(t0 + 10 * minute), t0, t0 + 30 * minute)
        )
        assertNull(MinuteWritePlan.longestGap(listOf(t0, t0 + minute), t0, t0 + 2 * minute))
    }

    // ---- DateSpans ------------------------------------------------------------

    @Test
    fun `consecutive days are kept as one run`() {
        val spans = DateSpans.EMPTY.plus(listOf("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-07"))
        assertEquals("2026-10-01..2026-10-03,2026-10-07..2026-10-07", spans.encode())
        assertTrue("2026-10-02" in spans)
        assertFalse("2026-10-05" in spans)
        assertEquals(spans, DateSpans.decode(spans.encode()))
    }

    @Test
    fun `taking a day out splits its run`() {
        val spans = DateSpans.decode("2026-10-01..2026-10-05").minus(listOf("2026-10-03", "2026-12-01"))
        assertEquals("2026-10-01..2026-10-02,2026-10-04..2026-10-05", spans.encode())
        assertTrue(DateSpans.decode("2026-10-01..2026-10-01").minus(listOf("2026-10-01")).isEmpty())
    }

    @Test
    fun `junk in storage is dropped, not trusted`() {
        assertEquals(
            "2026-10-01..2026-10-02",
            DateSpans.decode("nonsense,2026-10-01..2026-10-02,2026-10-09..2026-10-04,,2026-13-01..2026-13-02").encode()
        )
        assertTrue(DateSpans.decode(null).isEmpty())
    }

    @Test
    fun `past the cap the closest runs merge - never forgetting a day`() {
        // Every other day for a long while: one run per day.
        val days = (0 until DateSpans.MAX_RUNS + 10).map { DateKeys.format(DateKeys.parse("2026-01-01").plusDays(2L * it)) }
        val spans = DateSpans.EMPTY.plus(days)
        assertTrue(spans.encode().split(',').size <= DateSpans.MAX_RUNS)
        days.forEach { assertTrue(it in spans) }
    }

    // ---- record ids -------------------------------------------------------------

    @Test
    fun `a minute's record id is its day and its epoch minute, whatever the zone`() {
        val type = HealthConnectManager.ReadType.STEPS
        assertEquals("stp-steps-2025-09-13-${t0 / minute}", type.minuteRecordId("2025-09-13", t0))
        assertEquals(
            "stp-distance-2025-09-13-${t0 / minute}",
            HealthConnectManager.ReadType.DISTANCE.minuteRecordId("2025-09-13", t0 + 59_999)
        )
        assertEquals("stp-steps-2025-09-13", type.clientRecordId("2025-09-13"))
    }

    @Test
    fun `every minute of a day is listed, and a clock change gives or takes an hour`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
            val plain = HealthConnectManager.minutesOf("2026-10-03")
            assertEquals(1_440, plain.size)
            assertEquals(DateKeys.startOfDayMillis("2026-10-03"), plain.first())
            assertEquals(1_500, HealthConnectManager.minutesOf("2026-10-25").size)
            assertEquals(1_380, HealthConnectManager.minutesOf("2026-03-29").size)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    // ---- config -------------------------------------------------------------------

    @Test
    fun `granularity is per day unless the app asks for minutes, and survives the stored JSON`() {
        assertEquals("day", StepTrackerConfig().healthConnectWriteGranularity)
        assertEquals("day", StepTrackerConfig(healthConnectWriteGranularity = "hour").sanitised().healthConnectWriteGranularity)
        val config = StepTrackerConfig(healthConnectWriteGranularity = "minute").sanitised()
        val restored = StepTrackerConfig.fromJson(JSONObject(config.toJson().toString())).sanitised()
        assertEquals("minute", restored.healthConnectWriteGranularity)
        val stored = StepTrackerConfig().toJson().apply { remove("healthConnectWriteGranularity") }
        assertEquals("day", StepTrackerConfig.fromJson(stored).sanitised().healthConnectWriteGranularity)
    }
}
