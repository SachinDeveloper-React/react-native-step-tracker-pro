package com.steptrackerpro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `authFailed` is what an app acts on at launch: a refusal over the
 * credentials that nothing has fixed since.
 */
class RemoteSyncStatusTest {

    private fun failure(at: Long, reason: String, status: Int? = 401) =
        RemoteSyncStatus.Failure(at, reason, status, "Upload refused: HTTP $status", retryable = false)

    @Test
    fun `never attempted is not a failure`() {
        val status = RemoteSyncStatus()
        assertFalse(status.authFailed)
        assertNull(status.toMap(configured = false, pendingRecords = 0)["lastFailure"])
    }

    @Test
    fun `a refusal with nothing changed since is an auth failure`() {
        for (reason in listOf(RemoteSyncStatus.UNAUTHORIZED, RemoteSyncStatus.FORBIDDEN, RemoteSyncStatus.NO_KEY)) {
            val status = RemoteSyncStatus(
                lastAttemptAt = 200, lastFailure = failure(200, reason), credentialsChangedAt = 100
            )
            assertTrue(reason, status.authFailed)
        }
    }

    @Test
    fun `new credentials after the refusal clear it`() {
        val status = RemoteSyncStatus(
            lastAttemptAt = 200, lastFailure = failure(200, RemoteSyncStatus.UNAUTHORIZED), credentialsChangedAt = 300
        )
        assertFalse(status.authFailed)
    }

    @Test
    fun `credentials changed while the refused upload ran are not blamed`() {
        // The failure is filed under the moment its credentials were read.
        val status = RemoteSyncStatus(
            lastAttemptAt = 200, lastFailure = failure(200, RemoteSyncStatus.FORBIDDEN, 403), credentialsChangedAt = 250
        )
        assertFalse(status.authFailed)
    }

    @Test
    fun `a later accepted upload clears it and the failure stays as history`() {
        val status = RemoteSyncStatus(
            lastAttemptAt = 300, lastSuccessAt = 310,
            lastFailure = failure(200, RemoteSyncStatus.UNAUTHORIZED)
        )
        assertFalse(status.authFailed)
        @Suppress("UNCHECKED_CAST")
        val last = status.toMap(configured = true, pendingRecords = 0)["lastFailure"] as Map<String, Any?>
        assertEquals("unauthorized", last["reason"])
        assertEquals(401, last["status"])
    }

    @Test
    fun `server and network failures are not auth failures`() {
        for (reason in listOf(RemoteSyncStatus.HTTP_ERROR, RemoteSyncStatus.NO_RESPONSE, RemoteSyncStatus.INSECURE_URL)) {
            val status = RemoteSyncStatus(lastAttemptAt = 200, lastFailure = failure(200, reason, 500))
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
