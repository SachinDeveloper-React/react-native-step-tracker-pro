package com.steptrackerpro.core

import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Spreads a block of steps counted while nobody was listening across the
 * calendar days it may have happened on.
 *
 * TYPE_STEP_COUNTER keeps counting in the sensor hub while the process is
 * dead, so after an OEM task killer or an overnight kill the first sample back
 * carries every step since the last one we saw. When both readings fall on the
 * same day they all belong to today and this class is not involved. When the
 * gap crosses midnight there is no way to know which side of it each step was
 * taken on; the choice is between dropping them, giving them all to one day,
 * or spreading them. Dropping is what an app that "loses my morning walk"
 * looks like, and giving them all to one day moves a whole evening into the
 * next morning, so they are spread in proportion to how much of the gap fell
 * on each day. It is a heuristic and is documented as one.
 *
 * Pure, so it can be tested on the JVM.
 */
object StepGapSplitter {

    /**
     * @param fromMillis epoch millis of the last reading before the gap.
     * @param toMillis epoch millis of the reading that closed it.
     * @return steps per `yyyy-MM-dd`, every day between the two instants
     *   present, summing exactly to [steps]. A gap that does not cross
     *   midnight returns a single entry.
     */
    /**
     * Applies a [StepCounterEngine.GapRecovery] policy to a recovered block
     * of steps: which days get what. Pure, so the four policies are tested
     * on the JVM without an engine behind them.
     *
     * @param activeDate the day the closing reading fell on, which is the
     *   only day the engine can credit directly.
     * @param maxSteps for [StepCounterEngine.GapRecovery.TODAY_CAPPED]: the
     *   most the active day may be credited; the rest is dropped.
     */
    fun apply(
        policy: StepCounterEngine.GapRecovery,
        fromMillis: Long,
        toMillis: Long,
        steps: Int,
        activeDate: String,
        maxSteps: Int = StepCounterEngine.DEFAULT_GAP_RECOVERY_MAX_STEPS,
        zone: ZoneId = DateKeys.zone()
    ): Map<String, Int> {
        if (steps <= 0) return emptyMap()
        return when (policy) {
            StepCounterEngine.GapRecovery.SPLIT -> split(fromMillis, toMillis, steps, zone)
            StepCounterEngine.GapRecovery.TODAY -> mapOf(activeDate to steps)
            // Like TODAY, but bounded: a week-long kill followed by a counter
            // glitch could otherwise mint a hundred thousand steps on one
            // day, and an app paying for steps would rather lose them than
            // pay for them. Whatever is over the cap is dropped, not moved.
            StepCounterEngine.GapRecovery.TODAY_CAPPED ->
                mapOf(activeDate to steps.coerceAtMost(maxSteps.coerceAtLeast(0)))
            StepCounterEngine.GapRecovery.DROP -> {
                // Only the part provably inside the active day is kept: with
                // no timestamps per step that is nothing when the gap started
                // on another day, and all of it otherwise.
                val startDate = if (fromMillis > 0L) {
                    Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate().let(DateKeys::format)
                } else {
                    activeDate
                }
                if (startDate == activeDate) mapOf(activeDate to steps) else emptyMap()
            }
        }
    }

    fun split(
        fromMillis: Long,
        toMillis: Long,
        steps: Int,
        zone: ZoneId = DateKeys.zone()
    ): Map<String, Int> {
        if (steps <= 0) return emptyMap()
        val end = maxOf(fromMillis, toMillis)
        val start = minOf(fromMillis, toMillis)
        val endDate = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
        if (start <= 0L) return mapOf(DateKeys.format(endDate) to steps)

        val startDate = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        if (startDate == endDate) return mapOf(DateKeys.format(endDate) to steps)

        val totalMs = (end - start).coerceAtLeast(1L).toDouble()
        val out = LinkedHashMap<String, Int>()
        var assigned = 0
        var cursor = startDate
        var cursorStart = start
        while (cursor.isBefore(endDate)) {
            val next = cursor.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val share = ((next - cursorStart) / totalMs * steps).roundToInt()
                .coerceIn(0, steps - assigned)
            out[DateKeys.format(cursor)] = share
            assigned += share
            cursor = cursor.plusDays(1)
            cursorStart = next
        }
        // Whatever rounding left over lands on the closing day, so the shares
        // always sum to the input.
        out[DateKeys.format(endDate)] = steps - assigned
        return out
    }
}
