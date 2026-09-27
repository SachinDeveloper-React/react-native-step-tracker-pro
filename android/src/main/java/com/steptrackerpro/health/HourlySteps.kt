package com.steptrackerpro.health

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Splits a step record across the local hours of the day it started on, so a
 * source can report what it counted hour by hour. Pure, JVM-tested.
 *
 * A record is split in proportion to how much of it falls in each hour, with
 * cumulative rounding so the hours always add back to the record's count.
 * Whatever runs past midnight stays in hour 23: records are bucketed by the
 * day they start on, and the day's hours must sum to the day's steps. On a
 * daylight-saving day the repeated hour's steps share one slot and the
 * skipped hour stays empty - the array is indexed by the hour on the clock.
 */
object HourlySteps {

    const val HOURS = 24

    /** 24 × -1: not computed, the convention of every other per-source field. */
    val UNKNOWN: List<Int> = List(HOURS) { -1 }

    fun add(into: IntArray, startMs: Long, endMs: Long, count: Int, zone: ZoneId) {
        if (count <= 0) return
        val start = Instant.ofEpochMilli(startMs).atZone(zone)
        val span = endMs - startMs
        if (span <= 0L) {
            into[start.hour] += count
            return
        }
        val dayEnd = start.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        var cursor = start.truncatedTo(ChronoUnit.HOURS)
        var covered = 0L
        var assigned = 0
        while (true) {
            val hourStart = maxOf(cursor.toInstant().toEpochMilli(), startMs)
            val next = cursor.plusHours(1)
            val hourEnd = minOf(next.toInstant().toEpochMilli(), endMs, dayEnd)
            if (hourEnd > hourStart) covered += hourEnd - hourStart
            val last = hourEnd >= endMs || hourEnd >= dayEnd
            val upTo = if (last) count else ((count.toLong() * covered + span / 2) / span).toInt()
            val share = (upTo - assigned).coerceAtLeast(0)
            into[cursor.hour] += share
            assigned += share
            if (last) break
            cursor = next
        }
    }
}
