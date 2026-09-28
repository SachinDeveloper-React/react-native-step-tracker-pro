package com.steptrackerpro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `authFailed` is what an app acts on at launch: a refusal over the
 * credentials that nothing has fixed since. Decided by counters - the
 * credentials generation an attempt read, and the order of attempts - so no
 * clock the user can move takes part.
 */
class RemoteSyncStatusTest {

    private fun failure(
        attempt: Long,
        reason: String,
        generation: Long = 0L,
        status: Int? = 401,
        at: Long = 1_000L
    ) = RemoteSyncStatus.Failure(
        at, reason, status, "Upload refused: HTTP $status", retryable = false,
        generation = generation, attempt = attempt
    )

    @Test
    fun `never attempted is not a failure`() {
        val status = RemoteSyncStatus()
        assertFalse(status.authFailed)
        assertNull(status.toMap(configured = false, pendingRecords = 0)["lastFailure"])
    }

    @Test
    fun `a refusal with nothing changed since is an auth failure`() {
        for (reason in listOf(RemoteSyncStatus.UNAUTHORIZED, RemoteSyncStatus.FORBIDDEN, RemoteSyncStatus.NO_KEY)) {
            val status = RemoteSyncStatus(lastFailure = failure(3, reason, generation = 2), credentialsGeneration = 2)
            assertTrue(reason, status.authFailed)
        }
    }

    @Test
    fun `new credentials after the refusal clear it`() {
        val status = RemoteSyncStatus(
            lastFailure = failure(3, RemoteSyncStatus.UNAUTHORIZED, generation = 2), credentialsGeneration = 3
        )
        assertFalse(status.authFailed)
    }

    @Test
    fun `the clock does not take part`() {
        // The refusal carries a wall time far in the future - the clock was
        // wound forward, then back before the fix. Only the generation counts.
        val refusedLater = failure(3, RemoteSyncStatus.UNAUTHORIZED, generation = 2, at = Long.MAX_VALUE / 2)
        assertFalse(RemoteSyncStatus(lastFailure = refusedLater, credentialsGeneration = 3).authFailed)
        // And one timed in the distant past is still current if nothing changed.
        val refusedEarly = failure(3, RemoteSyncStatus.UNAUTHORIZED, generation = 3, at = 1L)
        assertTrue(RemoteSyncStatus(lastFailure = refusedEarly, credentialsGeneration = 3, lastSuccessAt = 99_999L).authFailed)
    }

    @Test
    fun `a later accepted upload clears it and the failure stays as history`() {
        val status = RemoteSyncStatus(
            lastFailure = failure(3, RemoteSyncStatus.UNAUTHORIZED),
            lastSuccessAttempt = 4
        )
        assertFalse(status.authFailed)
        @Suppress("UNCHECKED_CAST")
        val last = status.toMap(configured = true, pendingRecords = 0)["lastFailure"] as Map<String, Any?>
        assertEquals("unauthorized", last["reason"])
        assertEquals(401, last["status"])
    }

    @Test
    fun `an earlier attempt that succeeds late does not clear a later refusal`() {
        val status = RemoteSyncStatus(lastFailure = failure(5, RemoteSyncStatus.FORBIDDEN, status = 403), lastSuccessAttempt = 4)
        assertTrue(status.authFailed)
    }

    @Test
    fun `a refusal stored before 2_2 carries no attempt and is not reported`() {
        // The next scheduled upload files it again, with its counters.
        assertFalse(RemoteSyncStatus(lastFailure = failure(0, RemoteSyncStatus.UNAUTHORIZED)).authFailed)
    }

    @Test
    fun `server and network failures are not auth failures`() {
        for (reason in listOf(RemoteSyncStatus.HTTP_ERROR, RemoteSyncStatus.NO_RESPONSE, RemoteSyncStatus.INSECURE_URL)) {
            val status = RemoteSyncStatus(lastFailure = failure(3, reason, status = 500))
            assertFalse(reason, status.authFailed)
        }
    }

    @Test
    fun `the map carries what getSyncStatus returns`() {
        val map = RemoteSyncStatus(lastAttemptAt = 5, consecutiveFailures = 2)
            .toMap(configured = true, pendingRecords = 3)
        assertEquals(
            setOf(
                "configured", "pendingRecords", "lastAttemptAt", "lastSuccessAt",
                "consecutiveFailures", "lastFailure", "authFailed"
            ),
            map.keys
        )
        assertEquals(3, map["pendingRecords"])
        assertEquals(2, map["consecutiveFailures"])
    }
}
