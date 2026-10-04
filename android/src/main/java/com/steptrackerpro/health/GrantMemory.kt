package com.steptrackerpro.health

/**
 * What [HealthConnectManager.grantedPermissions] remembers between checks:
 * the answer it may reuse for [ttlMs], and the last one read, kept across
 * [invalidate] so a change of grants is noticed.
 *
 * A check that fails is not a revocation. The provider APK - Android 13 and
 * lower - refuses even the permission check once the app's quota is used
 * up, and reading that failure as "nothing granted" told the app, and the
 * user, that Health Connect had been switched off. So the last answer
 * stands, and it is reused like any other: every caller asking again,
 * straight away, a call Health Connect is refusing only kept the quota
 * used up.
 *
 * Pure apart from [now], the uptime clock: after the wall clock is set
 * back, "now minus then" would be negative and an answer would look fresh
 * for hours.
 */
class GrantMemory(private val now: () -> Long, private val ttlMs: Long) {

    private var cached: Set<String>? = null
    private var cachedAt = 0L

    /** The last answer Health Connect gave this process; null before the first. */
    @get:Synchronized
    var last: Set<String>? = null
        private set

    /** An answer young enough to reuse, or null to ask Health Connect. */
    @Synchronized
    fun reusable(): Set<String>? = cached?.takeIf { now() - cachedAt < ttlMs }

    /**
     * Health Connect answered [set]. True when that differs from the last
     * answer - the user allowed or refused something since.
     */
    @Synchronized
    fun answered(set: Set<String>): Boolean {
        cache(set)
        val previous = last
        last = set
        return previous != null && previous != set
    }

    /**
     * A check failed, or was held back while Health Connect refuses the
     * quota. The last answer stands - or, in a process that has had none,
     * [stored], the last one an earlier process read - and is reused like an
     * answer until [ttlMs] has passed.
     */
    @Synchronized
    fun failed(stored: Set<String>?): Set<String> {
        val answer = last ?: stored ?: emptySet()
        cache(answer)
        return answer
    }

    /** The next check asks Health Connect, whatever is cached. */
    @Synchronized
    fun invalidate() {
        cached = null
    }

    private fun cache(set: Set<String>) {
        cached = set
        cachedAt = now()
    }
}
