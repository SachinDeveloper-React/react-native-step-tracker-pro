package com.steptrackerpro.core

import java.util.TreeMap

/**
 * What the phone was doing according to Google's Activity Recognition, when
 * the host app ships Play Services and `fraudDetection.activityRecognition`
 * is on. [UNKNOWN] everywhere else, which is also what every step is tagged
 * with before the first transition arrives.
 */
enum class ActivityState(val jsValue: String) {
    UNKNOWN("unknown"),
    STILL("still"),
    WALKING("walking"),
    RUNNING("running"),
    ON_BICYCLE("on_bicycle"),
    IN_VEHICLE("in_vehicle");

    companion object {
        fun from(value: String?): ActivityState =
            entries.firstOrNull { it.jsValue == value } ?: UNKNOWN
    }
}

/**
 * One minute of this device's own steps, as stored in `step_minute`. Only
 * minutes with steps exist; a missing minute is a minute with none.
 *
 * The tag counts are subsets of [totalSteps] and may overlap each other: a
 * step taken in a car while charging is in both [chargingSteps] and
 * [vehicleSteps].
 */
data class MinuteSample(
    /** Epoch ms of the minute's start, on a whole-minute boundary. */
    val minuteStart: Long,
    /** Steps whose timing is known to within this minute. */
    val steps: Int,
    /**
     * Steps that arrived in one lump after a silence - a sensor hub whose
     * batch overflowed, or a process that was dead - so the minute they were
     * taken in is unknown. Parked at the minute they arrived in, and never
     * used for a cadence judgement.
     */
    val untimedSteps: Int = 0,
    /** Of [totalSteps], how many arrived while the phone was plugged in. */
    val chargingSteps: Int = 0,
    /** Of [totalSteps], how many arrived while Activity Recognition said still. */
    val stillSteps: Int = 0,
    /** Of [totalSteps], how many arrived while Activity Recognition said in a vehicle. */
    val vehicleSteps: Int = 0
) {
    val totalSteps: Int get() = steps + untimedSteps

    fun toMap(): Map<String, Any?> = mapOf(
        "minuteStart" to minuteStart,
        "steps" to steps,
        "untimedSteps" to untimedSteps,
        "chargingSteps" to chargingSteps,
        "stillSteps" to stillSteps,
        "vehicleSteps" to vehicleSteps
    )
}

/**
 * Places a counter delta on the minutes it was taken in. Pure, so the rules
 * are pinned by JVM tests.
 *
 * The engine hands over "these many steps arrived between the previous
 * sample and this one". With the hardware counter reporting on every change
 * that interval is well under a second while walking, and each batched
 * sample keeps its own timestamp when the hub buffers them with the screen
 * off - so almost every delta has an exact time. Two cases do not:
 *
 *  - **A lump.** More than [LUMP_STEPS] steps after more than
 *    [MAX_TIMED_GAP_MS] of silence: the hub's FIFO overflowed and only the
 *    latest reading survived, or the process was dead and the first sample
 *    after the restart carries everything since. When those steps were taken
 *    is unknown. They are parked, untimed, at the minute they arrived in -
 *    never spread across the gap, which would invent a perfectly steady
 *    cadence for a detector to find.
 *  - **The first step after a rest.** A few steps after a long silence are
 *    simply the walk starting; they belong to the minute they arrived in.
 *
 * Everything else is split across the minutes the interval overlaps, in
 * proportion to the overlap, with the remainder handled so the shares always
 * sum to the delta.
 */
object MinuteAttribution {

    const val MINUTE_MS = 60_000L

    /** Longest silence a delta can follow and still count as timed. */
    const val MAX_TIMED_GAP_MS = 60_000L

    /** More than this many steps after a long silence is a lump, not a walk starting. */
    const val LUMP_STEPS = 20

    data class Share(val minuteStart: Long, val timed: Int, val untimed: Int)

    fun minuteOf(epochMs: Long): Long = Math.floorDiv(epochMs, MINUTE_MS) * MINUTE_MS

    fun attribute(fromMs: Long, toMs: Long, steps: Int): List<Share> {
        if (steps <= 0 || toMs <= 0L) return emptyList()
        val arrival = minuteOf(toMs)
        val gap = toMs - fromMs
        if (fromMs <= 0L || gap <= 0L) {
            // No previous sample to measure from, or a clock that moved
            // backwards between the two: only the arrival time is known.
            return listOf(park(arrival, steps))
        }
        if (gap > MAX_TIMED_GAP_MS) return listOf(park(arrival, steps))

        val first = minuteOf(fromMs)
        if (first == arrival) return listOf(Share(arrival, steps, 0))
        val shares = ArrayList<Share>(2)
        var minute = first
        var coveredMs = 0L
        var assigned = 0
        while (minute <= arrival) {
            val overlap = minOf(toMs, minute + MINUTE_MS) - maxOf(fromMs, minute)
            coveredMs += overlap.coerceAtLeast(0L)
            // Cumulative rounding: the running total is rounded, not each
            // share, so the shares always add back up to the delta.
            val upTo = if (minute == arrival) steps else ((steps.toLong() * coveredMs + gap / 2) / gap).toInt()
            val share = (upTo - assigned).coerceAtLeast(0)
            if (share > 0) shares.add(Share(minute, share, 0))
            assigned += share
            minute += MINUTE_MS
        }
        return shares
    }

    private fun park(minute: Long, steps: Int): Share =
        if (steps <= LUMP_STEPS) Share(minute, steps, 0) else Share(minute, 0, steps)
}

/**
 * Per-minute increments waiting to be written to `step_minute`. The sensor
 * thread records into it on every changed total; the core's write lane
 * drains it on every commit. In memory only for the seconds between the two,
 * and bounded, so a database that keeps failing costs integrity minutes and
 * never the count or the heap.
 */
class StepTimeline {

    private class Pending {
        var timed = 0
        var untimed = 0
        var charging = 0
        var still = 0
        var vehicle = 0
    }

    private val pending = TreeMap<Long, Pending>()

    @Synchronized
    fun record(
        fromMs: Long,
        toMs: Long,
        steps: Int,
        charging: Boolean,
        activity: ActivityState
    ) {
        for (share in MinuteAttribution.attribute(fromMs, toMs, steps)) {
            val entry = pending.getOrPut(share.minuteStart) { Pending() }
            val total = share.timed + share.untimed
            entry.timed += share.timed
            entry.untimed += share.untimed
            if (charging) entry.charging += total
            when (activity) {
                ActivityState.STILL -> entry.still += total
                ActivityState.IN_VEHICLE -> entry.vehicle += total
                else -> Unit
            }
        }
        while (pending.size > MAX_PENDING_MINUTES) pending.pollFirstEntry()
    }

    /** Everything recorded since the last drain, oldest first, and forgets it. */
    @Synchronized
    fun drain(): List<MinuteSample> {
        if (pending.isEmpty()) return emptyList()
        val out = pending.map { (minute, p) ->
            MinuteSample(minute, p.timed, p.untimed, p.charging, p.still, p.vehicle)
        }
        pending.clear()
        return out
    }

    /** Drops anything pending - a reset or a history clear made it moot. */
    @Synchronized
    fun clear() = pending.clear()

    @Synchronized
    fun isEmpty(): Boolean = pending.isEmpty()

    companion object {
        /** Two days of minutes; the write lane drains every few seconds of walking. */
        const val MAX_PENDING_MINUTES = 2 * 24 * 60
    }
}
