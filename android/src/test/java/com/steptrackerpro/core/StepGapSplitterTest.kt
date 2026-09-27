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

    // ---- the four policies, applied ------------------------------------------

    private fun apply(
        policy: StepCounterEngine.GapRecovery,
        steps: Int,
        maxSteps: Int = StepCounterEngine.DEFAULT_GAP_RECOVERY_MAX_STEPS
    ) = StepGapSplitter.apply(
        policy, at("2026-09-10", 22), at("2026-09-11", 8), steps, "2026-09-11", maxSteps, zone
    )

    @Test
    fun `split policy spreads across midnight`() {
        assertEquals(
            mapOf("2026-09-10" to 200, "2026-09-11" to 800),
            apply(StepCounterEngine.GapRecovery.SPLIT, 1000)
        )
    }

    @Test
    fun `today policy credits everything to the active day`() {
        assertEquals(mapOf("2026-09-11" to 1000), apply(StepCounterEngine.GapRecovery.TODAY, 1000))
    }

    @Test
    fun `drop policy keeps nothing from a gap that started on another day`() {
        assertTrue(apply(StepCounterEngine.GapRecovery.DROP, 1000).isEmpty())
        // ...and everything from one inside the active day.
        assertEquals(
            mapOf("2026-09-11" to 1000),
            StepGapSplitter.apply(
                StepCounterEngine.GapRecovery.DROP, at("2026-09-11", 2), at("2026-09-11", 8),
                1000, "2026-09-11", zone = zone
            )
        )
    }

    @Test
    fun `today_capped behaves like today under the cap`() {
        assertEquals(
            apply(StepCounterEngine.GapRecovery.TODAY, 1000),
            apply(StepCounterEngine.GapRecovery.TODAY_CAPPED, 1000)
        )
        assertEquals(
            mapOf("2026-09-11" to 20_000),
            apply(StepCounterEngine.GapRecovery.TODAY_CAPPED, 20_000)
        )
    }

    @Test
    fun `today_capped drops everything over the cap rather than moving it`() {
        // A week-long kill and a counter glitch: 100,000 on the counter.
        val shares = apply(StepCounterEngine.GapRecovery.TODAY_CAPPED, 100_000)
        assertEquals(mapOf("2026-09-11" to 20_000), shares)
        // Nothing lands on yesterday - a closed day never changes.
        assertEquals(setOf("2026-09-11"), shares.keys)
        // A custom cap is honoured, and a zero cap credits nothing.
        assertEquals(mapOf("2026-09-11" to 600), apply(StepCounterEngine.GapRecovery.TODAY_CAPPED, 1000, 600))
        assertEquals(mapOf("2026-09-11" to 0), apply(StepCounterEngine.GapRecovery.TODAY_CAPPED, 1000, 0))
    }

    @Test
    fun `the policy string parses and unknown falls back to split`() {
        assertEquals(StepCounterEngine.GapRecovery.TODAY_CAPPED, StepCounterEngine.GapRecovery.from("today_capped"))
        assertEquals(StepCounterEngine.GapRecovery.SPLIT, StepCounterEngine.GapRecovery.from("nonsense"))
    }
}
