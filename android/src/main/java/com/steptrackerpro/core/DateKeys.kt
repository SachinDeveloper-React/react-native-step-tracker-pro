package com.steptrackerpro.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * All persistence keys off dates as `yyyy-MM-dd` in the device's current zone.
 * Everything that needs a wall-clock day goes through here so a timezone change
 * is handled in exactly one place.
 */
object DateKeys {

    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun zone(): ZoneId = ZoneId.systemDefault()

    fun today(): String = LocalDate.now(zone()).format(FORMAT)

    fun yesterday(): String = LocalDate.now(zone()).minusDays(1).format(FORMAT)

    fun of(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone()).toLocalDate().format(FORMAT)

    fun parse(key: String): LocalDate = LocalDate.parse(key, FORMAT)

    fun format(date: LocalDate): String = date.format(FORMAT)

    fun startOfDayMillis(key: String): Long =
        parse(key).atStartOfDay(zone()).toInstant().toEpochMilli()

    fun endOfDayMillis(key: String): Long =
        parse(key).plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli() - 1

    fun startOfDayInstant(key: String): Instant =
        parse(key).atStartOfDay(zone()).toInstant()

    fun endOfDayInstant(key: String): Instant =
        parse(key).plusDays(1).atStartOfDay(zone()).toInstant()

    /** Every date from start to end, inclusive, ascending. */
    fun rangeOf(start: String, end: String): List<String> {
        val from = parse(start)
        val to = parse(end)
        if (to.isBefore(from)) return emptyList()
        val out = ArrayList<String>()
        var cursor = from
        while (!cursor.isAfter(to)) {
            out.add(format(cursor))
            cursor = cursor.plusDays(1)
        }
        return out
    }

    fun daysBetween(start: String, end: String): Int =
        (parse(end).toEpochDay() - parse(start).toEpochDay()).toInt() + 1

    // ---- window helpers -------------------------------------------------

    /** Monday-anchored ISO week. `offset` 1 means last week. */
    fun calendarWeek(offset: Int = 0): Pair<String, String> {
        val base = LocalDate.now(zone()).minusWeeks(offset.toLong())
        val start = base.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return format(start) to format(start.plusDays(6))
    }

    fun calendarMonth(offset: Int = 0): Pair<String, String> {
        val base = LocalDate.now(zone()).withDayOfMonth(1).minusMonths(offset.toLong())
        return format(base) to format(base.with(TemporalAdjusters.lastDayOfMonth()))
    }

    fun calendarYear(offset: Int = 0): Pair<String, String> {
        val base = LocalDate.now(zone()).withDayOfYear(1).minusYears(offset.toLong())
        return format(base) to format(base.with(TemporalAdjusters.lastDayOfYear()))
    }

    /** Last `days` days ending today, inclusive. */
    fun rolling(days: Int): Pair<String, String> {
        val end = LocalDate.now(zone())
        return format(end.minusDays((days - 1).toLong())) to format(end)
    }

    fun minusDays(key: String, days: Int): String = format(parse(key).minusDays(days.toLong()))

    fun nowZoned(): ZonedDateTime = ZonedDateTime.now(zone())
}
