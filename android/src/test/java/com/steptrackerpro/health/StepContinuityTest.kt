package com.steptrackerpro.health

import com.steptrackerpro.core.DayTotals
import com.steptrackerpro.core.MetricsCalculator
import com.steptrackerpro.core.StepTrackerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scenario this exists for: Health Connect says 6,000, the phone then
 * counts on its own, and the user must see 6,001, 6,005, 6,010 - not 6,000
 * frozen until the other app next syncs, and never 6,000 + 6,000.
 */
class StepContinuityTest {

    private val metrics = MetricsCalculator(StepTrackerConfig().sanitised())
    private val date = "2026-09-11"

    private fun device(steps: Int) =
        DayTotals(date, steps, metrics.distance(steps), metrics.calories(steps))

    private fun watch(steps: Int) =
        StepSource("com.fitbit.FitbitMobile", "Fitbit", StepSourceKind.WATCH, steps, 0.0, 0.0, 0L, false)

    private fun raw(deviceSteps: Int, external: Int?) =
        StepSourceResolver.resolve(
            StepSourcePolicy.AUTO, device(deviceSteps),
            external?.let { listOf(watch(it)) } ?: emptyList(), null, metrics
        )

    @Test
    fun `health connect ahead becomes a baseline the phone counts on top of`() {
        // Fresh install at 15:00: the phone has 0, Health Connect has 6,000.
        val baseline = StepContinuity.observe(null, raw(0, 6_000), date, 1L)!!
        assertEquals(6_000, baseline.offset)
        assertEquals("com.fitbit.FitbitMobile", baseline.packageName)

        // The phone now counts 1, 5, 10 - the shown number follows.
        assertEquals(6_001, StepContinuity.apply(raw(1, null), baseline, metrics).totals.steps)
        assertEquals(6_005, StepContinuity.apply(raw(5, null), baseline, metrics).totals.steps)
        val shown = StepContinuity.apply(raw(10, null), baseline, metrics)
        assertEquals(6_010, shown.totals.steps)
        assertTrue(shown.merged)
        assertTrue(shown.usedExternal)
        assertEquals(6_000, shown.baselineSteps)
        assertEquals(StepSourceKind.WATCH, shown.kind)
    }

    @Test
    fun `next sync that agrees with the shown number does not double count`() {
        val baseline = StepContinuity.observe(null, raw(5_500, 6_000), date, 1L)!!
        assertEquals(500, baseline.offset)

        // Both counted the next 1,000. Shown is 7,000; the watch now says 7,000.
        val next = StepContinuity.observe(baseline, raw(6_500, 7_000), date, 2L)
        assertSame(baseline, next)
        assertEquals(7_000, StepContinuity.apply(raw(6_500, 7_000), next, metrics).totals.steps)
    }

    @Test
    fun `phone on a desk while the watch walks raises the baseline`() {
        val baseline = StepContinuity.observe(null, raw(2_000, 6_000), date, 1L)!!
        assertEquals(4_000, baseline.offset)

        // Watch walked 1,000 more, phone saw nothing.
        val next = StepContinuity.observe(baseline, raw(2_000, 7_000), date, 2L)!!
        assertEquals(5_000, next.offset)
        assertEquals(7_000, StepContinuity.apply(raw(2_000, 7_000), next, metrics).totals.steps)
    }

    @Test
    fun `phone ahead means no baseline and the raw answer stands`() {
        assertNull(StepContinuity.observe(null, raw(9_000, 3_000), date, 1L))
        val shown = StepContinuity.apply(raw(9_000, 3_000), null, metrics)
        assertEquals(9_000, shown.totals.steps)
        assertFalse(shown.merged)
        assertFalse(shown.usedExternal)
    }

    @Test
    fun `a stale external read never lowers the shown number`() {
        val baseline = StepContinuity.observe(null, raw(5_500, 6_000), date, 1L)!!
        // Phone walked on; the watch has not synced since.
        val shown = StepContinuity.apply(raw(6_200, 6_000), baseline, metrics)
        assertEquals(6_700, shown.totals.steps)
        // And observing the same stale read leaves the baseline alone.
        assertSame(baseline, StepContinuity.observe(baseline, raw(6_200, 6_000), date, 2L))
    }

    @Test
    fun `baseline is per day`() {
        val baseline = StepContinuity.observe(null, raw(0, 6_000), date, 1L)!!
        val tomorrow = raw(0, 6_000).let {
            StepSourceResolver.resolve(
                StepSourcePolicy.AUTO,
                DayTotals("2026-09-12", 40, 0.0, 0.0), emptyList(), null, metrics
            )
        }
        assertEquals(40, StepContinuity.apply(tomorrow, baseline, metrics).totals.steps)
        assertNull(StepContinuity.observe(baseline, tomorrow, "2026-09-12", 2L))
    }

    @Test
    fun `a fresh read that is ahead of the baseline shows the read while the baseline catches up`() {
        val baseline = StepContinuity.observe(null, raw(5_000, 5_100), date, 1L)!!
        assertEquals(100, baseline.offset)
        // Watch jumped to 8,000 (it was worn, the phone was not). apply() with
        // the old baseline must not hide that.
        val fresh = raw(5_000, 8_000)
        assertEquals(8_000, StepContinuity.apply(fresh, baseline, metrics).totals.steps)
        assertEquals(3_000, StepContinuity.observe(baseline, fresh, date, 2L)!!.offset)
    }

    @Test
    fun `never sums the two sources`() {
        val baseline = StepContinuity.observe(null, raw(7_800, 8_000), date, 1L)!!
        val shown = StepContinuity.apply(raw(7_800, 8_000), baseline, metrics)
        assertEquals(8_000, shown.totals.steps)
        assertTrue(shown.totals.steps < 7_800 + 8_000)
    }
}
