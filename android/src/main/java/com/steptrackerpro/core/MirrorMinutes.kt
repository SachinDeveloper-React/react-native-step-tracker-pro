package com.steptrackerpro.core

import java.util.TreeMap

/**
 * This device's steps per minute, waiting to be written to `mirror_minute`
 * for Health Connect's per-minute records -
 * `healthConnectWriteGranularity: 'minute'`. The sensor thread records into
 * it on every changed total, under the engine's lock; the core's write lane
 * drains it on every commit and before every sync. Like [StepTimeline], in
 * memory only for the seconds between the two, and bounded.
 */
class MirrorMinuteBuffer {

    private val pending = TreeMap<Long, Int>()

    /** Steps the sensor counted between [fromMs] and [toMs] - see [MinuteAttribution.spread]. */
    @Synchronized
    fun record(fromMs: Long, toMs: Long, steps: Int, timed: Boolean = true) {
        for ((minute, share) in MinuteAttribution.spread(fromMs, toMs, steps, timed)) {
            pending[minute] = (pending[minute] ?: 0) + share
        }
        while (pending.size > StepTimeline.MAX_PENDING_MINUTES) pending.pollFirstEntry()
    }

    /** Everything recorded since the last drain, oldest first, and forgets it: minute start -> steps. */
    @Synchronized
    fun drain(): Map<Long, Int> {
        if (pending.isEmpty()) return emptyMap()
        val out = LinkedHashMap(pending)
        pending.clear()
        return out
    }

    /** Drops anything pending - a reset made it moot. */
    @Synchronized
    fun clear() = pending.clear()
}

/**
 * What one day's per-minute Health Connect records should be, given what
 * this device counted in each minute, what was written last time, and the
 * day's total. Pure, so the arithmetic is pinned by JVM tests.
 *
 * The day's total is the authority; the minutes say when. They can fall
 * short of it - steps credited in one go by gap recovery or a reboot, a
 * minute lost to a crash before it was stored, the part of the day before
 * per-minute writes were turned on - and those steps, whose minute nobody
 * knows, go into one record over the longest stretch of the day no minute
 * covers: where they most likely happened, and never overlapping a minute
 * record. Health Connect counts only one of two overlapping records from the
 * same app, so an overlap would lose steps. The minutes can also run over
 * the total: steps counted after the total was read, which the newest
 * minutes give back, so a sync during a walk leaves the rest of the day's
 * records alone; and exclude mode taking flagged steps out, scaled down
 * across the day. Either way the records add up to the total exactly.
 */
object MinuteWritePlan {

    const val MINUTE_MS = 60_000L

    /** One stored minute: what was counted in it, and what Health Connect holds for it. */
    data class Minute(val minuteStart: Long, val steps: Int, val written: Int)

    data class Span(val start: Long, val end: Long) {
        val length: Long get() = end - start
    }

    data class Plan(
        /** Each minute's steps once the plan has landed; 0 for none. */
        val targets: Map<Long, Int>,
        /** Minutes whose record must be written: their target differs from what Health Connect holds. */
        val upserts: Map<Long, Int>,
        /** Minutes whose record must go: written before, nothing now. */
        val deletes: List<Long>,
        /** Steps no minute holds, written over [residualSpan]; 0 for none. */
        val residual: Int,
        val residualSpan: Span?
    )

    /**
     * @param minutes the day's stored minutes; any order.
     * @param total the day's total as mirrored - what every record must add up to.
     * @param dayStart start of the day, epoch ms.
     * @param dayEnd end of what can be written: the next midnight, or now for today.
     * @param rewrite every minute with steps is written again, changed or not -
     *   its distance and calories follow a profile that changed.
     * @param observed of the day's own count, the steps the sensor was seen
     *   taking - not those credited in one go by recovery, and before
     *   exclude mode took flagged steps out of [total]. Minutes counting
     *   past it hold steps not in the count yet - taken after it was read,
     *   or late steps not credited to their day yet - and give them back
     *   from the end, where they are; only what is left over [total] is
     *   exclude mode's, scaled down across the day.
     */
    fun plan(
        minutes: List<Minute>,
        total: Int,
        dayStart: Long,
        dayEnd: Long,
        rewrite: Boolean = false,
        observed: Int = total
    ): Plan {
        val (inDay, outside) = minutes.sortedBy { it.minuteStart }
            .partition { it.minuteStart in dayStart until dayEnd }
        val starts = inDay.map { it.minuteStart }
        val steps = inDay.map { it.steps.coerceAtLeast(0) }.toIntArray()
        val want = total.coerceAtLeast(0)
        var counted = steps.sumOf { it.toLong() }
        val ceiling = observed.coerceAtLeast(0).toLong()
        if (counted > ceiling) {
            var excess = counted - ceiling
            for (i in steps.indices.reversed()) {
                if (excess <= 0L) break
                val cut = minOf(steps[i].toLong(), excess).toInt()
                steps[i] -= cut
                excess -= cut
            }
            counted = ceiling
        }
        val targets = LinkedHashMap<Long, Int>()
        var residual: Int
        if (counted <= want) {
            starts.forEachIndexed { i, start -> targets[start] = steps[i] }
            residual = (want - counted).toInt()
        } else {
            scaleInto(targets, starts, steps, want, counted)
            residual = 0
        }
        var span: Span? = null
        if (residual > 0) {
            span = longestGap(targets.filterValues { it > 0 }.keys.sorted(), dayStart, dayEnd)
            if (span == null) {
                // Every minute of the day holds steps - nowhere left to put
                // the rest without overlapping one. Spread it over them.
                scaleInto(targets, starts, steps, want, counted.coerceAtLeast(1L))
                residual = 0
            }
        }
        val written = inDay.associate { it.minuteStart to it.written }
        return Plan(
            targets = targets,
            upserts = targets.filter { (minute, steps) -> steps > 0 && (rewrite || steps != written[minute]) },
            // A minute the day no longer spans - the zone moved under it, or
            // the clock went back past it - keeps no record: its steps are in
            // the residual now, and both would count them.
            deletes = (inDay.filter { it.written > 0 && (targets[it.minuteStart] ?: 0) == 0 } +
                outside.filter { it.written > 0 }).map { it.minuteStart }.sorted(),
            residual = residual,
            residualSpan = span
        )
    }

    /** [want] steps over [starts] in proportion to [steps], adding up exactly. */
    private fun scaleInto(
        into: MutableMap<Long, Int>,
        starts: List<Long>,
        steps: IntArray,
        want: Int,
        counted: Long
    ) {
        var cumulative = 0L
        var assigned = 0
        starts.forEachIndexed { i, start ->
            cumulative += steps[i]
            val upTo = ((want.toLong() * cumulative + counted / 2) / counted).toInt()
            into[start] = (upTo - assigned).coerceAtLeast(0)
            assigned += into[start] ?: 0
        }
    }

    /**
     * The longest stretch between [dayStart] and [dayEnd] that no occupied
     * minute covers, the earliest on a tie; null when there is none.
     */
    fun longestGap(occupied: List<Long>, dayStart: Long, dayEnd: Long): Span? {
        var best: Span? = null
        var cursor = dayStart
        for (minute in occupied) {
            if (minute > cursor) best = longer(best, Span(cursor, minute))
            cursor = maxOf(cursor, minute + MINUTE_MS)
        }
        if (dayEnd > cursor) best = longer(best, Span(cursor, dayEnd))
        return best
    }

    private fun longer(best: Span?, candidate: Span): Span =
        if (best == null || candidate.length > best.length) candidate else best
}

/**
 * A set of day keys kept as runs of consecutive days -
 * `2026-01-05..2026-01-09,2026-03-01..2026-10-03` - so a year of days costs
 * one entry while they run on. Past [MAX_RUNS], the two runs closest
 * together merge, days between them included: the set only ever grows past
 * what it was told, never shrinks short of it, which is the safe way round
 * for "may hold records".
 */
class DateSpans private constructor(private val runs: List<Pair<String, String>>) {

    operator fun contains(date: String): Boolean = runs.any { (start, end) -> date in start..end }

    fun isEmpty(): Boolean = runs.isEmpty()

    fun plus(dates: Collection<String>): DateSpans {
        if (dates.isEmpty()) return this
        val days = java.util.TreeSet<java.time.LocalDate>()
        runs.forEach { (start, end) -> days.addAll(expand(start, end)) }
        dates.forEach { date -> parse(date)?.let { days += it } }
        return of(days)
    }

    fun minus(dates: Collection<String>): DateSpans {
        val drop = dates.filter { it in this }.mapNotNull { parse(it) }.toSet()
        if (drop.isEmpty()) return this
        val days = java.util.TreeSet<java.time.LocalDate>()
        runs.forEach { (start, end) -> days.addAll(expand(start, end)) }
        days.removeAll(drop)
        return of(days)
    }

    fun encode(): String = runs.joinToString(",") { (start, end) -> "$start..$end" }

    override fun equals(other: Any?): Boolean = other is DateSpans && other.runs == runs

    override fun hashCode(): Int = runs.hashCode()

    override fun toString(): String = encode()

    companion object {
        /** Runs kept apart before the closest two merge. */
        const val MAX_RUNS = 64

        val EMPTY = DateSpans(emptyList())

        fun decode(raw: String?): DateSpans {
            if (raw.isNullOrBlank()) return EMPTY
            val days = java.util.TreeSet<java.time.LocalDate>()
            for (part in raw.split(',')) {
                val bounds = part.split("..")
                val start = parse(bounds.first()) ?: continue
                val end = parse(bounds.last()) ?: continue
                if (end < start) continue
                days.addAll(expand(start, end))
            }
            return of(days)
        }

        private fun of(days: java.util.TreeSet<java.time.LocalDate>): DateSpans {
            val runs = ArrayList<Pair<java.time.LocalDate, java.time.LocalDate>>()
            for (day in days) {
                val last = runs.lastOrNull()
                if (last != null && last.second.plusDays(1) == day) {
                    runs[runs.size - 1] = last.first to day
                } else {
                    runs += day to day
                }
            }
            while (runs.size > MAX_RUNS) {
                // Merge the pair with the fewest days between them.
                var closest = 0
                for (i in 1 until runs.size - 1) {
                    if (gap(runs[i], runs[i + 1]) < gap(runs[closest], runs[closest + 1])) closest = i
                }
                runs[closest] = runs[closest].first to runs[closest + 1].second
                runs.removeAt(closest + 1)
            }
            return DateSpans(runs.map { (start, end) -> DateKeys.format(start) to DateKeys.format(end) })
        }

        private fun gap(
            a: Pair<java.time.LocalDate, java.time.LocalDate>,
            b: Pair<java.time.LocalDate, java.time.LocalDate>
        ): Long = java.time.temporal.ChronoUnit.DAYS.between(a.second, b.first)

        private fun parse(key: String): java.time.LocalDate? =
            if (key.length == 10) runCatching { DateKeys.parse(key) }.getOrNull() else null

        private fun expand(start: String, end: String): List<java.time.LocalDate> {
            val from = parse(start) ?: return emptyList()
            val to = parse(end) ?: return emptyList()
            return expand(from, to)
        }

        private fun expand(start: java.time.LocalDate, end: java.time.LocalDate): List<java.time.LocalDate> {
            val out = ArrayList<java.time.LocalDate>()
            var day = start
            while (!day.isAfter(end)) {
                out += day
                day = day.plusDays(1)
            }
            return out
        }
    }
}
