package com.steptrackerpro.health

import com.steptrackerpro.health.HealthConnectQuota.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Health Connect meters every data call, per quota - read or write,
 * foreground or background - and refuses all of them once one is used up.
 * The package stops calling on a quota it was just refused, backing off
 * until a call goes through, and keeps optional work to half of each window.
 */
class RateLimitTest {

    private var now = 10_000_000L
    private val quota = HealthConnectQuota { now }

    @Test
    fun `a refusal holds the quota back, doubling to fifteen minutes, until a call goes through`() {
        assertEquals(0L, quota.retryAfterMs(Category.READ, foreground = false))
        assertEquals(30_000L, quota.onRefused(Category.READ, foreground = false))
        assertEquals(30_000L, quota.retryAfterMs(Category.READ, foreground = false))
        now += 10_000L
        assertEquals(20_000L, quota.retryAfterMs(Category.READ, foreground = false))

        assertEquals(60_000L, quota.onRefused(Category.READ, foreground = false))
        assertEquals(120_000L, quota.onRefused(Category.READ, foreground = false))
        repeat(10) { quota.onRefused(Category.READ, foreground = false) }
        assertEquals(15 * 60_000L, quota.retryAfterMs(Category.READ, foreground = false))

        now += 15 * 60_000L
        assertEquals(0L, quota.retryAfterMs(Category.READ, foreground = false))
        quota.onAccepted(Category.READ, foreground = false)
        // Back to the first step once Health Connect answers again.
        assertEquals(30_000L, quota.onRefused(Category.READ, foreground = false))
    }

    @Test
    fun `each quota is held back on its own`() {
        quota.onRefused(Category.READ, foreground = false)
        assertTrue(quota.retryAfterMs(Category.READ, foreground = false) > 0L)
        // The screen's reads, and every write, are other quotas.
        assertEquals(0L, quota.retryAfterMs(Category.READ, foreground = true))
        assertEquals(0L, quota.retryAfterMs(Category.WRITE, foreground = false))
        assertEquals(0L, quota.retryAfterMs(Category.WRITE, foreground = true))
    }

    @Test
    fun `optional work stands down at half of the window, and while the quota is held back`() {
        // Background reads: 1,000 per 15 minutes by default, so 500 is half.
        repeat(499) { quota.record(Category.READ, foreground = false) }
        assertTrue(quota.hasHeadroom(Category.READ, foreground = false))
        quota.record(Category.READ, foreground = false)
        assertFalse(quota.hasHeadroom(Category.READ, foreground = false))
        // Foreground reads allow 2,000, and are counted apart.
        assertTrue(quota.hasHeadroom(Category.READ, foreground = true))

        // Fifteen minutes on, the window has slid past them.
        now += 15 * 60_000L
        assertTrue(quota.hasHeadroom(Category.READ, foreground = false))

        quota.onRefused(Category.READ, foreground = true)
        assertFalse(quota.hasHeadroom(Category.READ, foreground = true))
    }

    @Test
    fun `the daily window holds what the 15-minute one has let go`() {
        // 4,000 background reads spread over four hours: never more than
        // 250 in any 15 minutes, but half of the 8,000 a day.
        repeat(16) {
            repeat(250) { quota.record(Category.READ, foreground = false) }
            now += 15 * 60_000L
        }
        assertFalse(quota.hasHeadroom(Category.READ, foreground = false))
        assertEquals(0, quota.callsLast15Minutes(Category.READ))
        assertEquals(4_000, quota.callsLastDay(Category.READ))
        now += 24 * 60 * 60_000L
        assertEquals(0, quota.callsLastDay(Category.READ))
        assertTrue(quota.hasHeadroom(Category.READ, foreground = false))
    }

    @Test
    fun `the status reports the quotas of the state the app is in, and every call made`() {
        quota.record(Category.READ, foreground = true)
        quota.record(Category.READ, foreground = false)
        quota.record(Category.WRITE, foreground = false)
        quota.onRefused(Category.WRITE, foreground = false)

        val background = quota.toMap(foreground = false)
        assertEquals(false, background["readsLimited"])
        assertEquals(true, background["writesLimited"])
        assertEquals(30_000L, background["writesRetryAfterMs"])
        assertEquals(2, background["readsLast15Minutes"])
        assertEquals(1, background["writesLast24Hours"])

        val foreground = quota.toMap(foreground = true)
        assertEquals(false, foreground["writesLimited"])
        assertEquals(0L, foreground["writesRetryAfterMs"])
    }

    @Test
    fun `a rolling counter counts what falls inside its window`() {
        val counter = RollingCounter(binMs = 1_000L, bins = 3)
        counter.add(0L)
        counter.add(1_500L, count = 2)
        counter.add(2_999L)
        assertEquals(4, counter.sum(2_999L))
        // The first bin has left a three-second window.
        assertEquals(3, counter.sum(3_000L))
        assertEquals(1, counter.sum(4_000L))
        assertEquals(0, counter.sum(10_000L))
        // A slot reused by a later bin starts from zero: the first bin's one
        // event is gone, the new one counts, and the two bins between stay.
        counter.add(3_000L)
        assertEquals(4, counter.sum(3_500L))
        assertEquals(2, counter.sum(4_500L))
    }

    @Test
    fun `quota refusals are recognised from either provider, through the cause chain`() {
        // Android 14 and later: the platform exception, wrapped by Jetpack.
        val platform = IllegalStateException(
            "android.health.connect.HealthConnectException: API call quota exceeded, availableQuota: 0.17 requested: 1"
        )
        assertTrue(HealthConnectErrors.isRateLimited(platform))
        // Android 13 and lower: the provider APK's RemoteException.
        val apk = Exception(
            "Request rejected. Rate limited request quota has been exceeded. " +
                "Please wait until quota has replenished before making further requests."
        )
        assertTrue(HealthConnectErrors.isRateLimited(apk))
        assertTrue(HealthConnectErrors.isRateLimited(RuntimeException("read failed", platform)))
        assertTrue(HealthConnectErrors.isRateLimited(HealthConnectRateLimitedException(Category.READ, 1_000L)))
    }

    @Test
    fun `other failures are not refusals for quota`() {
        assertFalse(HealthConnectErrors.isRateLimited(null))
        assertFalse(HealthConnectErrors.isRateLimited(SecurityException("Caller doesn't hold READ_STEPS")))
        assertFalse(HealthConnectErrors.isRateLimited(IOException("disk full")))
        assertFalse(HealthConnectErrors.isRateLimited(IllegalStateException("Health Connect is not available")))
    }

    @Test
    fun `the refusal says how long until the next call`() {
        val refusal = HealthConnectRateLimitedException(Category.WRITE, 29_001L)
        assertTrue(refusal.message!!.contains("write quota"))
        assertTrue(refusal.message!!.contains("30s"))
    }

    @Test
    fun `an empty page token ends a read, as a null one does`() {
        assertFalse(HealthConnectManager.hasMorePages(null))
        assertFalse(HealthConnectManager.hasMorePages(""))
        assertTrue(HealthConnectManager.hasMorePages("4711"))
    }
}
