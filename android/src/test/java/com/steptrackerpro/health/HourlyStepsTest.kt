package com.steptrackerpro.health

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** A record's steps land on the local hours it covered, and always add back up. */
class HourlyStepsTest {

    private val utc = ZoneId.of("UTC")

    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `a record inside one hour lands on that hour`() {
        val hours = IntArray(24)
        HourlySteps.add(hours, at(utc, 2026, 9, 14, 9, 10), at(utc, 2026, 9, 14, 9, 20), 800, utc)
        assertEquals(800, hours[9])
        assertEquals(800, hours.sum())
    }

    @Test
    fun `a record spanning hours is split by time and sums back`() {
        val hours = IntArray(24)
        // 09:30 to 11:00: half an hour in 9, a full hour in 10.
        HourlySteps.add(hours, at(utc, 2026, 9, 14, 9, 30), at(utc, 2026, 9, 14, 11, 0), 900, utc)
        assertEquals(300, hours[9])
        assertEquals(600, hours[10])
        assertEquals(900, hours.sum())
    }

    @Test
    fun `whatever runs past midnight stays on the day it started`() {
        val hours = IntArray(24)
        HourlySteps.add(hours, at(utc, 2026, 9, 14, 23, 30), at(utc, 2026, 9, 15, 0, 30), 100, utc)
        assertEquals(100, hours[23])
        assertEquals(100, hours.sum())
    }

    @Test
    fun `an instant record lands on its hour`() {
        val hours = IntArray(24)
        val t = at(utc, 2026, 9, 14, 7, 5)
        HourlySteps.add(hours, t, t, 12, utc)
        assertEquals(12, hours[7])
    }

    @Test
    fun `a daylight-saving day still sums to the record`() {
        val london = ZoneId.of("Europe/London")
        val hours = IntArray(24)
        // 29 March 2026: clocks go from 01:00 to 02:00, so 00:00-04:00 is three real hours.
        HourlySteps.add(hours, at(london, 2026, 3, 29, 0), at(london, 2026, 3, 29, 4), 3_000, london)
        assertEquals(3_000, hours.sum())
        assertEquals(0, hours[1])
        assertEquals(1_000, hours[0])
    }

    @Test
    fun `rounding never loses a step`() {
        for (count in 1..60) {
            val hours = IntArray(24)
            HourlySteps.add(hours, at(utc, 2026, 9, 14, 5, 59), at(utc, 2026, 9, 14, 8, 1), count, utc)
            assertEquals(count, hours.sum())
        }
    }
}
