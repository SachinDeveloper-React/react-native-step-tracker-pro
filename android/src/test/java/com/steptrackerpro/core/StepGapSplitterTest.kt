package com.steptrackerpro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The contract: shares always sum to the input, a gap inside one day lands
 * wholly on that day, and a gap across midnight is split by time.
 */
class StepGapSplitterTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    private fun at(date: String, hour: Int, minute: Int = 0): Long =
        LocalDate.parse(date).atStartOfDay(zone).plusHours(hour.toLong())
            .plusMinutes(minute.toLong()).toInstant().toEpochMilli()

    @Test
    fun `gap inside one day is all that day`() {
        val shares = StepGapSplitter.split(at("2026-09-10", 9), at("2026-09-10", 17), 1200, zone)
        assertEquals(mapOf("2026-09-10" to 1200), shares)
    }

    @Test
    fun `overnight kill splits by time on each side of midnight`() {
        // Killed at 22:00, revived at 08:00: two hours yesterday, eight today.
        val shares = StepGapSplitter.split(at("2026-09-10", 22), at("2026-09-11", 8), 1000, zone)
        assertEquals(200, shares["2026-09-10"])
        assertEquals(800, shares["2026-09-11"])
        assertEquals(1000, shares.values.sum())
    }

    @Test
    fun `shares always sum to the input even with rounding`() {
        val shares = StepGapSplitter.split(at("2026-09-10", 23, 59), at("2026-09-11", 0, 1), 7, zone)
        assertEquals(7, shares.values.sum())
        assertEquals(setOf("2026-09-10", "2026-09-11"), shares.keys)
    }

    @Test
    fun `multi-day gap covers every day between`() {
        val shares = StepGapSplitter.split(at("2026-09-08", 12), at("2026-09-11", 12), 3000, zone)
        assertEquals(listOf("2026-09-08", "2026-09-09", "2026-09-10", "2026-09-11"), shares.keys.toList())
        assertEquals(3000, shares.values.sum())
        // Half a day, full, full, half.
        assertEquals(500, shares["2026-09-08"])
        assertEquals(1000, shares["2026-09-09"])
        assertEquals(1000, shares["2026-09-10"])
        assertEquals(500, shares["2026-09-11"])
    }

    @Test
    fun `no previous reading time puts everything on the closing day`() {
        val shares = StepGapSplitter.split(0L, at("2026-09-11", 8), 300, zone)
        assertEquals(mapOf("2026-09-11" to 300), shares)
    }

    @Test
    fun `zero steps is empty`() {
        assertTrue(StepGapSplitter.split(at("2026-09-10", 22), at("2026-09-11", 8), 0, zone).isEmpty())
    }
}
