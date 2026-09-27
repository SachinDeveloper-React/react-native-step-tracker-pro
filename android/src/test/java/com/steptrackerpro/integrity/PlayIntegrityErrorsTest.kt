package com.steptrackerpro.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of Play's `StandardIntegrityErrorCode`s a caller may retry. */
class PlayIntegrityErrorsTest {

    @Test
    fun `transient codes are retryable`() {
        // NETWORK_ERROR, TOO_MANY_REQUESTS, CANNOT_BIND_TO_SERVICE,
        // GOOGLE_SERVER_UNAVAILABLE, CLIENT_TRANSIENT_ERROR,
        // INTEGRITY_TOKEN_PROVIDER_INVALID, INTERNAL_ERROR
        listOf(-3, -8, -9, -12, -18, -19, -100).forEach { assertTrue("$it", PlayIntegrityBridge.isRetryable(it)) }
    }

    @Test
    fun `codes that need something to change are not`() {
        // Play Store or services missing or outdated, not installed from
        // Play, uid mismatch, bad project number, hash too long.
        listOf(-1, -2, -5, -6, -7, -14, -15, -16, -17).forEach {
            assertFalse("$it", PlayIntegrityBridge.isRetryable(it))
        }
    }

    @Test
    fun `unknown or missing codes are never retried blindly`() {
        assertFalse(PlayIntegrityBridge.isRetryable(null))
        assertFalse(PlayIntegrityBridge.isRetryable(-999))
    }

    @Test
    fun `failures name their code`() {
        val network = PlayIntegrityBridge.Failure("offline", -3, null)
        assertEquals("NETWORK_ERROR", network.errorName)
        assertTrue(network.retryable)
        assertEquals("UNKNOWN", PlayIntegrityBridge.Failure("?", -999, null).errorName)
        assertNull(PlayIntegrityBridge.Failure("?", null, null).errorName)
    }
}
