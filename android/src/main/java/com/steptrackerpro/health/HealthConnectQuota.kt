package com.steptrackerpro.health

/**
 * Health Connect's rate limits, and what this package does about them.
 *
 * Health Connect meters every data call an app makes. Reads - a page of
 * records, an aggregate, a changes token, a page of changes - draw on one
 * quota; inserts, updates and deletes on another. Each has a 15-minute and a
 * 24-hour window, refilling linearly over it, and each is tighter for an app
 * in the background than in the foreground (an activity on screen or a
 * foreground service running, as AppOps sees it). Today's platform defaults
 * are 2,000 reads per 15 minutes and 16,000 per day in the foreground, 1,000
 * and 8,000 in the background, and 1,000 and 8,000 writes in either. Health
 * Connect tunes them remotely and does not publish them, so they are a guide
 * here, never something to count down to. Past them every call on that quota
 * fails until it refills - which for an app with a stats screen open is every
 * read on screen, and every sync job.
 *
 * Two things live here, both per quota (read or write, foreground or
 * background):
 * - a breaker: once Health Connect refuses a call for quota, calls on that
 *   quota are not made at all for a while - [MIN_BACKOFF_MS], doubling up to
 *   [MAX_BACKOFF_MS] while refusals continue, reset by a call that goes
 *   through. Hammering an exhausted quota only keeps it exhausted, and on the
 *   provider APK (Android 13 and lower) an exhausted quota refuses even the
 *   permission check every screen makes.
 * - a count of the calls this process made over both windows, so the work
 *   nobody is waiting for - a refresh the sensor asked for - stands down
 *   once it has used [OPTIONAL_SHARE] of either window, leaving the rest for
 *   what someone is waiting for. The count cannot see calls other processes
 *   or other libraries in the app made, which is another reason the line is
 *   drawn well short of the limit.
 *
 * Pure apart from [now], which every caller passes as the uptime clock: a
 * wall clock set back would hold the breaker open for as long as it moved.
 */
class HealthConnectQuota(private val now: () -> Long) {

    enum class Category { READ, WRITE }

    /** Health Connect's default limits for one quota - see the class comment. */
    data class Limits(val per15Minutes: Int, val perDay: Int)

    private val openUntil = LongArray(QUOTAS)
    private val backoff = LongArray(QUOTAS)
    private val last15Minutes = Array(QUOTAS) { RollingCounter(MINUTE_MS, 15) }
    private val lastDay = Array(QUOTAS) { RollingCounter(QUARTER_HOUR_MS, 96) }

    /** Ms before a call on this quota may be made again; 0 when it may be made now. */
    @Synchronized
    fun retryAfterMs(category: Category, foreground: Boolean): Long =
        (openUntil[index(category, foreground)] - now()).coerceAtLeast(0L)

    /** A call on this quota is about to be made. */
    @Synchronized
    fun record(category: Category, foreground: Boolean) {
        val at = now()
        val i = index(category, foreground)
        last15Minutes[i].add(at)
        lastDay[i].add(at)
    }

    /** A call went through: the quota has room, whatever the breaker last thought. */
    @Synchronized
    fun onAccepted(category: Category, foreground: Boolean) {
        val i = index(category, foreground)
        backoff[i] = 0L
        openUntil[i] = 0L
    }

    /**
     * Health Connect refused a call for quota. Opens the breaker for the
     * next backoff step and returns how long it stays open.
     */
    @Synchronized
    fun onRefused(category: Category, foreground: Boolean): Long {
        val i = index(category, foreground)
        val next = if (backoff[i] <= 0L) MIN_BACKOFF_MS else (backoff[i] * 2).coerceAtMost(MAX_BACKOFF_MS)
        backoff[i] = next
        openUntil[i] = now() + next
        return next
    }

    /**
     * Whether work nobody is waiting for may still use this quota: the
     * breaker is closed, and this process has used less than
     * [OPTIONAL_SHARE] of either window's default limit.
     */
    @Synchronized
    fun hasHeadroom(category: Category, foreground: Boolean): Boolean {
        val i = index(category, foreground)
        val at = now()
        if (openUntil[i] > at) return false
        val limits = limits(category, foreground)
        return last15Minutes[i].sum(at) < limits.per15Minutes * OPTIONAL_SHARE &&
            lastDay[i].sum(at) < limits.perDay * OPTIONAL_SHARE
    }

    /** Calls this process made on [category] in the last 15 minutes, foreground and background together. */
    @Synchronized
    fun callsLast15Minutes(category: Category): Int {
        val at = now()
        return last15Minutes[index(category, true)].sum(at) + last15Minutes[index(category, false)].sum(at)
    }

    /** Calls this process made on [category] in the last 24 hours, foreground and background together. */
    @Synchronized
    fun callsLastDay(category: Category): Int {
        val at = now()
        return lastDay[index(category, true)].sum(at) + lastDay[index(category, false)].sum(at)
    }

    /**
     * What `HealthConnectStatus.rateLimit` reports. Whether each quota is
     * held back is for the state the app is in now - the background quota
     * being exhausted says nothing about a read made from the screen.
     */
    fun toMap(foreground: Boolean): Map<String, Any?> {
        val reads = retryAfterMs(Category.READ, foreground)
        val writes = retryAfterMs(Category.WRITE, foreground)
        return mapOf(
            "readsLimited" to (reads > 0L),
            "readsRetryAfterMs" to reads,
            "writesLimited" to (writes > 0L),
            "writesRetryAfterMs" to writes,
            "readsLast15Minutes" to callsLast15Minutes(Category.READ),
            "readsLast24Hours" to callsLastDay(Category.READ),
            "writesLast15Minutes" to callsLast15Minutes(Category.WRITE),
            "writesLast24Hours" to callsLastDay(Category.WRITE)
        )
    }

    companion object {
        private const val QUOTAS = 4
        private const val MINUTE_MS = 60_000L
        private const val QUARTER_HOUR_MS = 15 * MINUTE_MS

        /** First pause after a refusal. A 15-minute window refills one call every second or so. */
        const val MIN_BACKOFF_MS = 30_000L

        /** Longest pause: the 15-minute window, by which it has refilled completely. */
        const val MAX_BACKOFF_MS = 15 * MINUTE_MS

        /** The share of a window optional work may use - see the class comment. */
        const val OPTIONAL_SHARE = 0.5

        fun limits(category: Category, foreground: Boolean): Limits = when {
            category == Category.READ && foreground -> Limits(per15Minutes = 2_000, perDay = 16_000)
            category == Category.READ -> Limits(per15Minutes = 1_000, perDay = 8_000)
            else -> Limits(per15Minutes = 1_000, perDay = 8_000)
        }

        private fun index(category: Category, foreground: Boolean): Int =
            category.ordinal * 2 + if (foreground) 1 else 0
    }
}

/**
 * Events over a sliding window, kept in [bins] fixed bins of [binMs] each:
 * constant memory, exact to one bin. The window ending at an instant is the
 * bin that instant falls in and the [bins] - 1 before it. Pure; the caller
 * passes the instant.
 */
class RollingCounter(private val binMs: Long, private val bins: Int) {
    private val counts = IntArray(bins)
    private val ids = LongArray(bins) { EMPTY }

    fun add(at: Long, count: Int = 1) {
        val id = Math.floorDiv(at, binMs)
        val slot = Math.floorMod(id, bins.toLong()).toInt()
        if (ids[slot] != id) {
            ids[slot] = id
            counts[slot] = 0
        }
        counts[slot] += count
    }

    fun sum(at: Long): Int {
        val current = Math.floorDiv(at, binMs)
        var total = 0
        for (slot in 0 until bins) {
            val id = ids[slot]
            if (id != EMPTY && id <= current && id > current - bins) total += counts[slot]
        }
        return total
    }

    private companion object {
        const val EMPTY = Long.MIN_VALUE
    }
}

/**
 * A Health Connect call refused for quota, or not made because its quota was
 * refused a moment ago - see [HealthConnectQuota]. [retryAfterMs] is how long
 * until a call on that quota is tried again.
 */
class HealthConnectRateLimitedException(
    val category: HealthConnectQuota.Category,
    val retryAfterMs: Long,
    cause: Throwable? = null
) : IllegalStateException(
    "Health Connect's ${category.name.lowercase()} quota is used up; " +
        "the next call is tried in ${(retryAfterMs + 999) / 1000}s",
    cause
)

/** What Health Connect's exceptions mean, wherever in the client they surface. */
object HealthConnectErrors {

    /** `android.health.connect.HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED`. */
    const val ERROR_RATE_LIMIT_EXCEEDED = 7

    private const val PLATFORM_EXCEPTION = "android.health.connect.HealthConnectException"

    /**
     * Whether [error] is Health Connect refusing a call for quota. Android 14
     * and later throw `HealthConnectException` with
     * [ERROR_RATE_LIMIT_EXCEEDED] ("API call quota exceeded, availableQuota:
     * …"), which the Jetpack client hands on wrapped in an
     * `IllegalStateException`; the provider APK on Android 13 and lower sends
     * a `RemoteException` that only its message tells apart ("Rate limited
     * request quota has been exceeded"). So the cause chain is walked, and
     * both the code and the message are checked.
     */
    fun isRateLimited(error: Throwable?): Boolean {
        var cause = error
        var depth = 0
        while (cause != null && depth < MAX_CAUSES) {
            if (cause is HealthConnectRateLimitedException) return true
            if (platformErrorCode(cause) == ERROR_RATE_LIMIT_EXCEEDED) return true
            if (isQuotaMessage(cause.message)) return true
            if (cause.cause === cause) break
            cause = cause.cause
            depth++
        }
        return false
    }

    /** The wording both providers use for a quota refusal. */
    fun isQuotaMessage(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        return QUOTA_PHRASES.any { it in text }
    }

    /**
     * The platform exception's error code, by reflection: the class only
     * exists from Android 14, and naming it would fail to load on anything
     * older.
     */
    private fun platformErrorCode(error: Throwable): Int? {
        if (error.javaClass.name != PLATFORM_EXCEPTION) return null
        return runCatching { error.javaClass.getMethod("getErrorCode").invoke(error) as? Int }.getOrNull()
    }

    private const val MAX_CAUSES = 8

    private val QUOTA_PHRASES = listOf(
        "quota exceeded",
        "rate limited",
        "rate limit exceeded",
        "quota has been exceeded"
    )
}
