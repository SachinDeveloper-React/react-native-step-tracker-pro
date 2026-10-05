package com.steptrackerpro.health

/**
 * Health Connect records against countFrom - the instant before which
 * nothing is counted (`setCountFrom()`). A record is an interval: one that
 * ends by then counts nothing, one that starts then or later counts whole,
 * and one that straddles it counts its share after it, by time - as a
 * record straddling coverage or an hour is split. Pure, so the arithmetic is
 * pinned by JVM tests.
 */
object CountFrom {

    /** Of [value] over [startMs]..[endMs], the share at or after [floorMs]; all of it when [floorMs] is 0. */
    fun shareAfter(value: Int, startMs: Long, endMs: Long, floorMs: Long): Int {
        if (floorMs <= 0L || startMs >= floorMs) return value
        if (endMs <= floorMs) return 0
        return (value.toLong() * (endMs - floorMs) / (endMs - startMs)).toInt()
    }

    /** As the [Int] form, for distance and energy. */
    fun shareAfter(value: Double, startMs: Long, endMs: Long, floorMs: Long): Double {
        if (floorMs <= 0L || startMs >= floorMs) return value
        if (endMs <= floorMs) return 0.0
        return value * (endMs - floorMs) / (endMs - startMs)
    }

    /** Where what is left of a record starting at [startMs] starts: [floorMs] when that is later. */
    fun startAfter(startMs: Long, floorMs: Long): Long = maxOf(startMs, floorMs)
}
