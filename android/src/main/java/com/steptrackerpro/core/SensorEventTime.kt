package com.steptrackerpro.core

/**
 * When a sensor sample was taken, and whether that is known at all. Pure, so
 * the rules are pinned by JVM tests.
 *
 * Timestamps are documented as elapsed-realtime nanos. With the screen off
 * and the CPU asleep, a non-wake-up sensor's samples wait in the sensor
 * hub's FIFO and arrive together when something next wakes the CPU -
 * minutes after the walk - each still carrying the time it was taken. Up to
 * [MAX_AGE_MS] of that lateness is read back into the time the step
 * happened. Several OEM HALs stamp wall-clock nanos instead; those are read
 * on the wall clock.
 *
 * A timestamp that fits neither is replaced with the arrival time and
 * reported untimed. Stamping it "now" as if that were known is what put a
 * whole screen-off walk into the minute its batch arrived in: hundreds of
 * steps in one minute, flagged for a cadence nobody walks at.
 */
object SensorEventTime {

    /** How late a batched sample may arrive and still be placed by its own timestamp. */
    const val MAX_AGE_MS = 30 * 60_000L

    /**
     * How far ahead of the clock a timestamp may be and still mean "now":
     * the hub's clock is synchronised with the CPU's, not identical to it.
     */
    const val MAX_SKEW_MS = 5_000L

    /** [atMillis] is epoch ms; with [timed] false it is only when the sample arrived. */
    data class Resolved(val atMillis: Long, val timed: Boolean)

    /**
     * @param timestampNanos the sample's own `SensorEvent.timestamp`.
     * @param elapsedNanos `SystemClock.elapsedRealtimeNanos()` now.
     * @param wallMillis `System.currentTimeMillis()` now.
     */
    fun resolve(timestampNanos: Long, elapsedNanos: Long, wallMillis: Long): Resolved {
        if (timestampNanos <= 0L) return Resolved(wallMillis, timed = false)
        val ageMs = plausible((elapsedNanos - timestampNanos) / 1_000_000L)
            ?: plausible(wallMillis - timestampNanos / 1_000_000L)
            ?: return Resolved(wallMillis, timed = false)
        return Resolved(wallMillis - ageMs, timed = true)
    }

    /** The age, not below zero, when it is one a batched sample can have; otherwise null. */
    private fun plausible(ageMs: Long): Long? =
        if (ageMs in -MAX_SKEW_MS..MAX_AGE_MS) ageMs.coerceAtLeast(0L) else null
}
