package com.steptrackerpro.health

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Nothing before countFrom is counted. A Health Connect record is an
 * interval: before it, nothing; after it, all; across it, its share after
 * it by time - so a watch's ten-minute record that began five minutes
 * before a sign-in counts half.
 */
class CountFromTest {

    private val signIn = 1_791_100_000_000L
    private val minute = 60_000L

    @Test
    fun `a record before countFrom counts nothing, one after it counts whole`() {
        assertEquals(0, CountFrom.shareAfter(800, signIn - 20 * minute, signIn - 10 * minute, signIn))
        assertEquals(0, CountFrom.shareAfter(800, signIn - 10 * minute, signIn, signIn))
        assertEquals(800, CountFrom.shareAfter(800, signIn, signIn + 10 * minute, signIn))
        assertEquals(800, CountFrom.shareAfter(800, signIn + minute, signIn + 2 * minute, signIn))
    }

    @Test
    fun `a record across countFrom counts its share after it`() {
        assertEquals(400, CountFrom.shareAfter(800, signIn - 5 * minute, signIn + 5 * minute, signIn))
        assertEquals(1_500.0, CountFrom.shareAfter(2_000.0, signIn - minute, signIn + 3 * minute, signIn), 0.001)
        // What is left starts at countFrom, for the day and hour it lands on.
        assertEquals(signIn, CountFrom.startAfter(signIn - 5 * minute, signIn))
        assertEquals(signIn + minute, CountFrom.startAfter(signIn + minute, signIn))
    }

    @Test
    fun `with no countFrom everything counts`() {
        assertEquals(800, CountFrom.shareAfter(800, signIn - 5 * minute, signIn + 5 * minute, 0L))
        assertEquals(12.5, CountFrom.shareAfter(12.5, signIn - 5 * minute, signIn, 0L), 0.0)
        assertEquals(signIn - 5 * minute, CountFrom.startAfter(signIn - 5 * minute, 0L))
    }
}
