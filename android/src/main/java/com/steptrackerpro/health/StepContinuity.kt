package com.steptrackerpro.health

import com.steptrackerpro.core.DayTotals
import com.steptrackerpro.core.MetricsCalculator

/**
 * Keeps the number shown moving when Health Connect is ahead of the phone.
 *
 * The raw resolver picks one origin per day: under `auto`, whichever of the
 * phone and the best external source counted more. That answer is right at the
 * instant it is computed and wrong a minute later. A watch or another app
 * publishes to Health Connect in batches, so once it is ahead the displayed
 * number freezes at its last upload while the phone's own sensor keeps ticking
 * underneath - 6,000 stays 6,000 through a whole walk, then jumps to 7,000.
 *
 * Continuity fixes that by remembering the external *lead* rather than the
 * external total. When a read finds the best external source ahead of the
 * phone by N, N is stored as the day's baseline; from then on the number shown
 * is `deviceSteps + N`, and every step the phone counts moves it: 6,001,
 * 6,005, 6,010. The next read compares the external total against the number
 * already being shown and only raises the baseline when the other app has
 * genuinely pulled further ahead - which happens when the phone was on a desk
 * while a watch was walking - so nothing is counted twice. The baseline never
 * shrinks within a day and is dropped at midnight, so the shown number is
 * monotonic for the day, which is what lets goals key off it.
 *
 * Only the `auto` policy uses this. `wearable` and `health_connect` promise the
 * other app's exact number and keep it, jumps included.
 *
 * Pure, so it is tested on the JVM.
 */
object StepContinuity {

    data class Baseline(
        val date: String,
        /** How far ahead the external source was when observed. Always > 0. */
        val offset: Int,
        val packageName: String?,
        val kind: StepSourceKind,
        val appName: String,
        val observedAt: Long
    )

    /**
     * Folds a fresh raw resolution into the stored baseline.
     *
     * @param current the baseline as stored, or null.
     * @param raw the resolver's answer for [date] from a *fresh* Health
     *   Connect read, with [raw]'s `deviceSteps` taken at the same instant.
     * @return the baseline to store afterwards. The same instance as [current]
     *   when nothing changed, null when there is nothing to keep.
     */
    fun observe(
        current: Baseline?,
        raw: StepSourceResolver.Resolution,
        date: String,
        now: Long
    ): Baseline? {
        val stored = current?.takeIf { it.date == date }
        // No external candidate at all - nothing to learn. The stored lead, if
        // any, still stands: the other app has not disappeared, it has merely
        // not been read.
        if (raw.externalSteps <= 0) return stored
        // The resolver has already decided how far ahead the winner may be -
        // its whole margin for a watch, only the pre-coverage part of the day
        // for a phone-side app - so the lead is read off its answer.
        val lead = if (raw.usedExternal) raw.totals.steps - raw.deviceSteps else 0
        if (lead <= (stored?.offset ?: 0)) return stored
        // The external source is further ahead than the number currently
        // shown, so what it saw and the phone did not has grown. Adopt the
        // larger lead and remember who supplied it.
        return Baseline(
            date = date,
            offset = lead,
            packageName = raw.sourcePackage.takeIf { raw.usedExternal } ?: stored?.packageName,
            kind = if (raw.usedExternal) raw.kind else stored?.kind ?: raw.kind,
            appName = if (raw.usedExternal) raw.sourceName else stored?.appName ?: raw.sourceName,
            observedAt = now
        )
    }

    /**
     * The resolution to display: the raw answer with the baseline applied.
     *
     * Distance and calories are re-derived from the merged step count rather
     * than taken from either origin, because the count itself is a blend.
     */
    fun apply(
        raw: StepSourceResolver.Resolution,
        baseline: Baseline?,
        metrics: MetricsCalculator
    ): StepSourceResolver.Resolution {
        if (baseline == null || baseline.offset <= 0 || baseline.date != raw.totals.date) return raw
        val merged = raw.deviceSteps + baseline.offset
        // A fresh read may carry an external total the baseline has not
        // caught up with yet. The larger of the two is always the right one to
        // show; observe() brings the baseline in line on the next store.
        if (merged <= raw.totals.steps) {
            return raw.copy(merged = true, baselineSteps = baseline.offset)
        }
        return StepSourceResolver.Resolution(
            totals = DayTotals(
                date = raw.totals.date,
                steps = merged,
                distance = metrics.distance(merged),
                calories = metrics.calories(merged),
                synced = raw.totals.synced,
                syncedRemote = raw.totals.syncedRemote,
                recoveredSteps = raw.totals.recoveredSteps,
                suspectSteps = raw.totals.suspectSteps
            ),
            kind = baseline.kind,
            sourcePackage = baseline.packageName,
            sourceName = baseline.appName,
            deviceSteps = raw.deviceSteps,
            externalSteps = raw.externalSteps,
            usedExternal = true,
            merged = true,
            baselineSteps = baseline.offset,
            manualStepsExcluded = raw.manualStepsExcluded,
            suspectStepsExcluded = raw.suspectStepsExcluded
        )
    }
}
