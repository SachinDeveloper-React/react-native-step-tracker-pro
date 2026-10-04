package com.steptrackerpro.health

import com.steptrackerpro.health.HealthConnectManager.ReadOutcome
import com.steptrackerpro.health.HealthConnectManager.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A snapshot that asks for a day's sources and its raw records reads the
 * day once: the per-source read keeps the records it pages through, newest
 * first, and the snapshot hands them back as a raw read would - oldest
 * first, cut at the same size, with the same statuses.
 */
class RecordCaptureTest {

    private fun record(type: RecordType, start: Long) =
        mapOf("recordType" to type.jsValue, "startTime" to start, "endTime" to start + 60_000L)

    @Test
    fun `records come back oldest first whatever order the read met them in`() {
        val capture = RecordCapture(setOf(RecordType.STEPS, RecordType.DISTANCE))
        // The per-source read goes newest first, type by type.
        listOf(3_000L, 2_000L, 1_000L).forEach { capture.add(RecordType.STEPS, record(RecordType.STEPS, it)) }
        capture.finished(RecordType.STEPS, ReadOutcome.COMPLETE)
        listOf(2_500L, 500L).forEach { capture.add(RecordType.DISTANCE, record(RecordType.DISTANCE, it)) }
        capture.finished(RecordType.DISTANCE, ReadOutcome.COMPLETE)

        val (records, truncated) = capture.result()
        assertEquals(listOf(500L, 1_000L, 2_000L, 2_500L, 3_000L), records.map { it["startTime"] })
        assertFalse(truncated)
        assertTrue(capture.missing.isEmpty())
        assertNull(capture.failureStatus)
    }

    @Test
    fun `a type the per-source read does not go through is left to read on its own`() {
        // Distance asked for by the snapshot, but not in healthConnectReadTypes.
        val capture = RecordCapture(setOf(RecordType.STEPS, RecordType.DISTANCE))
        capture.add(RecordType.STEPS, record(RecordType.STEPS, 2_000L))
        capture.finished(RecordType.STEPS, ReadOutcome.COMPLETE)
        assertEquals(setOf(RecordType.DISTANCE), capture.missing)

        capture.include(mapOf("records" to listOf(record(RecordType.DISTANCE, 1_000L)), "truncated" to false))
        assertTrue(capture.missing.isEmpty())
        assertEquals(listOf(1_000L, 2_000L), capture.result().first.map { it["startTime"] })
    }

    @Test
    fun `nothing the snapshot did not ask for is kept`() {
        val capture = RecordCapture(setOf(RecordType.STEPS))
        assertFalse(capture.wants(RecordType.DISTANCE))
        capture.finished(RecordType.DISTANCE, ReadOutcome.FAILED, IllegalStateException("distance failed"))
        assertFalse(capture.used)
        assertNull(capture.failureStatus)
        assertEquals(setOf(RecordType.STEPS), capture.missing)
    }

    @Test
    fun `a read cut short, or past the raw read's size, says truncated`() {
        val capped = RecordCapture(setOf(RecordType.STEPS), limit = 2)
        listOf(3_000L, 2_000L, 1_000L).forEach { capped.add(RecordType.STEPS, record(RecordType.STEPS, it)) }
        capped.finished(RecordType.STEPS, ReadOutcome.COMPLETE)
        val (records, truncated) = capped.result()
        // The newest two: the read met those first.
        assertEquals(listOf(2_000L, 3_000L), records.map { it["startTime"] })
        assertTrue(truncated)

        val cut = RecordCapture(setOf(RecordType.STEPS))
        cut.finished(RecordType.STEPS, ReadOutcome.TRUNCATED)
        assertTrue(cut.result().second)
        assertEquals(10_000, HealthConnectManager.RAW_RECORDS_LIMIT)
    }

    @Test
    fun `a failed read says why, as a raw read would`() {
        val refused = RecordCapture(setOf(RecordType.STEPS))
        refused.finished(
            RecordType.STEPS, ReadOutcome.FAILED,
            HealthConnectRateLimitedException(HealthConnectQuota.Category.READ, 30_000L)
        )
        assertTrue(refused.used)
        assertEquals("rate_limited", refused.failureStatus)

        val failed = RecordCapture(setOf(RecordType.STEPS, RecordType.DISTANCE))
        failed.finished(RecordType.STEPS, ReadOutcome.COMPLETE)
        failed.finished(RecordType.DISTANCE, ReadOutcome.FAILED, SecurityException("READ_DISTANCE revoked"))
        assertEquals("failed", failed.failureStatus)
    }
}
