package com.steptrackerpro.health

import com.steptrackerpro.core.DateKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A range is read in as few calls as cover the days the cache cannot
 * answer: one read per run of consecutive days, or one span once the runs
 * are many.
 */
class RangeRunsTest {

    @Test
    fun `consecutive days are one read, and a gap starts another`() {
        val runs = HealthConnectManager.runsOf(
            listOf("2026-09-03", "2026-09-01", "2026-09-02", "2026-09-10"), maxDays = Int.MAX_VALUE
        )
        assertEquals(listOf(listOf("2026-09-01", "2026-09-02", "2026-09-03"), listOf("2026-09-10")), runs)
    }

    @Test
    fun `a run is cut at the longest window a read answers in full`() {
        val days = DateKeys.rangeOf("2026-08-01", "2026-09-14") // 45 days
        val runs = HealthConnectManager.runsOf(days, maxDays = 35)
        assertEquals(listOf(35, 10), runs.map { it.size })
        assertEquals(days, runs.flatten())
    }

    @Test
    fun `scattered days past the run limit are read as one span`() {
        val scattered = listOf("2026-09-01", "2026-09-03", "2026-09-05", "2026-09-07", "2026-09-09")
        val runs = HealthConnectManager.runsOf(scattered, maxDays = Int.MAX_VALUE, maxRuns = 4)
        assertEquals(listOf(DateKeys.rangeOf("2026-09-01", "2026-09-09")), runs)
        // At the limit they stay apart.
        assertEquals(4, HealthConnectManager.runsOf(scattered.take(4), maxDays = Int.MAX_VALUE, maxRuns = 4).size)
    }

    @Test
    fun `no days, no reads`() {
        assertTrue(HealthConnectManager.runsOf(emptyList(), maxDays = 35).isEmpty())
    }

    @Test
    fun `a window ending at midnight does not touch the day after it`() {
        val start = DateKeys.startOfDayInstant("2026-09-01")
        val end = DateKeys.endOfDayInstant("2026-09-02") // midnight starting the 3rd
        assertEquals(listOf("2026-09-01", "2026-09-02"), HealthConnectManager.datesOf(start, end))
        assertTrue(HealthConnectManager.datesOf(end, end).isEmpty())
        assertEquals(listOf("2026-09-01"), HealthConnectManager.datesOf(start, start.plusSeconds(60)))
    }
}
