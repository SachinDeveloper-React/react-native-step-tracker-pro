package com.steptrackerpro.core

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns raw sensor readings into a per-day step total that survives reboots,
 * process death and midnight.
 *
 * TYPE_STEP_COUNTER reports steps since the device booted, so the total for a
 * day is:
 *
 *     stepsToday = anchorSteps + (rawValue - anchorValue)
 *
 * The anchor is re-pinned whenever the meaning of `rawValue` changes:
 *
 *  - **Reboot.** The counter restarts at zero. The boot timestamp
 *    (`wallClock - elapsedRealtime`) is only a *hint* that this happened: it is
 *    derived from the wall clock, so a manual clock change or an NTP correction
 *    moves it without the counter restarting, and claiming the steps since boot
 *    then would add the day's steps to themselves. Acting on it needs corroborating
 *    proof, of which there are two, either of which is enough: the counter
 *    reading went backwards, or `elapsedRealtime` did. The latter cannot be set
 *    by the user and restarts at zero on every boot, so it still catches a
 *    reboot whose first post-boot reading happens to exceed the last one seen.
 *
 *    Once a reboot is established: if it happened today the steps taken since
 *    boot are real and unclaimed, so the anchor goes to zero and they are
 *    picked up. If it was on a previous day they are spread across the days
 *    between boot and now by [StepGapSplitter], today's share is claimed and
 *    the rest is handed to [onBackfill].
 *  - **Gap without a reboot.** The anchor is also cleared by a midnight
 *    rollover, by [resetToday], and by a boot-timestamp move that turns out to
 *    be a wall-clock correction. In every one of those cases the counter did
 *    *not* restart, so `rawValue - lastRawValue` is exactly the number of
 *    steps taken since the last reading - including every step the hardware
 *    counted while an OEM task killer had the process dead. They are claimed,
 *    not dropped: a gap inside one day goes to today, a gap across midnight is
 *    split by [StepGapSplitter]. Before 1.3 these were discarded, which is
 *    what "I walked to work and the app shows zero" looked like on phones
 *    that kill services overnight.
 *  - **Counter reset without reboot.** Some OEM sensor HALs restart the counter
 *    when the last listener unregisters. Detected as `rawValue` dropping below
 *    the last reading we saw; the anchor is re-pinned at the current reading so
 *    the running total is preserved and nothing is double counted. Comparing
 *    against the anchor alone is not enough, because the anchor is zero for the
 *    whole of any day the device booted on.
 *  - **Midnight.** The previous day is finalised through [onDayRollover] and the
 *    anchor is re-pinned with `anchorSteps = 0`.
 *  - **Resume after pause.**
 *
 * All mutation happens under the instance lock; sensor callbacks arrive on the
 * service's sensor thread while JS reads arrive on the native modules thread.
 */
class StepCounterEngine(
    private val state: StepStateStore,
    private val metrics: MetricsCalculator,
    /** Overridable so the reboot path can be exercised in tests. */
    private val elapsedProvider: () -> Long = { SystemClock.elapsedRealtime() },
    /** Overridable so the reboot path can be exercised in tests. */
    private val bootIdProvider: () -> Long = { currentBootId() },
    /** Wall clock, overridable so gap splitting is deterministic in tests. */
    private val clockProvider: () -> Long = { System.currentTimeMillis() }
) {

    /** Invoked with the finalised previous day when the date rolls over. */
    var onDayRollover: ((DayTotals, String) -> Unit)? = null

    /**
     * Invoked with steps recovered for days *other than* the active one, when
     * a gap in samples crossed midnight. Keys are `yyyy-MM-dd`; values are
     * steps to add on top of whatever the day already has stored.
     */
    var onBackfill: ((Map<String, Int>) -> Unit)? = null

    @Volatile
    var paused: Boolean = false
        private set

    @Volatile
    private var pendingCommit: Int = 0

    /**
     * Set on resume. The next sample pins the anchor at the current reading so
     * steps taken while paused are discarded instead of landing in the total.
     * Distinct from the first-sample branch, which may legitimately claim
     * everything since boot.
     */
    @Volatile
    private var reanchorOnNextSample: Boolean = false

    /**
     * What to do with steps the hardware counted while nothing was listening
     * and the gap crossed midnight. See [StepTrackerConfig.gapRecovery].
     */
    @Volatile
    var gapRecovery: GapRecovery = GapRecovery.SPLIT

    enum class GapRecovery(val jsValue: String) {
        /** Spread across the days in the gap in proportion to time. Default. */
        SPLIT("split"),
        /** Everything to the active day. */
        TODAY("today"),
        /** Discard anything that cannot be placed on the active day. */
        DROP("drop");

        companion object {
            fun from(value: String?): GapRecovery =
                entries.firstOrNull { it.jsValue == value } ?: SPLIT
        }
    }

    /**
     * @return today's share of [steps] and, when any of them belong to other
     *   days, the backfill for those days.
     */
    private fun recover(steps: Int, fromMillis: Long, nowMillis: Long): Pair<Int, Map<String, Int>?> {
        if (steps <= 0) return 0 to null
        // A last reading that is in the future means the wall clock was set
        // back. There is no honest way to place the gap then; keep it on the
        // active day rather than crediting a day that has not happened yet.
        if (fromMillis > nowMillis) return steps to null
        val shares = when (gapRecovery) {
            GapRecovery.SPLIT -> StepGapSplitter.split(fromMillis, nowMillis, steps)
            GapRecovery.TODAY -> mapOf(state.activeDate to steps)
            GapRecovery.DROP -> {
                // Only the part provably inside the active day is kept: with
                // no timestamps per step that is nothing when the gap started
                // on another day, and all of it otherwise.
                val startDate = if (fromMillis > 0L) DateKeys.of(fromMillis) else state.activeDate
                if (startDate == state.activeDate) mapOf(state.activeDate to steps) else emptyMap()
            }
        }
        val today = shares[state.activeDate] ?: 0
        val others = shares.filterKeys { it != state.activeDate }.filterValues { it > 0 }
        return today to others.takeIf { it.isNotEmpty() }
    }

    /**
     * Reconciles persisted state against the current boot and date. Call this
     * from `onStartCommand` before registering any listener.
     */
    @Synchronized
    fun reconcile(): StepSnapshot {
        rollDateIfNeeded()
        val currentBoot = bootIdProvider()
        if (bootIdMoved(currentBoot)) {
            // The counter may be about to restart at zero. Wait for the first
            // sample to decide the anchor - only that sample can tell a real
            // reboot from a wall-clock correction - but remember the new boot.
            // `lastRawValue` is deliberately preserved: it is the evidence.
            state.anchorValue = -1f
            state.bootId = currentBoot
        }
        return snapshot()
    }

    /**
     * @param rawValue cumulative steps since boot from TYPE_STEP_COUNTER.
     * @return a snapshot when the total changed, otherwise null.
     */
    @Synchronized
    fun onCounterSample(rawValue: Float, eventAtMillis: Long): StepSnapshot? {
        if (rawValue < 0f || rawValue.isNaN()) return null
        rollDateIfNeeded()

        val currentBoot = bootIdProvider()
        val currentElapsed = elapsedProvider()
        val lastRaw = state.lastRawValue
        val hasLastRaw = lastRaw >= 0f
        // The counter is cumulative and monotonic within a boot, so a reading
        // below the last one we saw proves it restarted.
        val counterWentBackwards = hasLastRaw && rawValue < lastRaw
        // So does uptime going backwards, and that one still fires when the
        // first post-boot reading happens to exceed the last pre-reboot one -
        // two reboots in quick succession, say.
        val lastElapsed = state.lastElapsedRealtime
        val elapsedWentBackwards = lastElapsed > 0L && currentElapsed < lastElapsed

        // Some HALs report a reading a step or two *below* the previous one
        // with no reset behind it - float rounding, or a hub re-ordering a
        // batch. Re-pinning on every such wobble added the wobble back on the
        // next sample, so a day of jitter crept upward. A dip this small is
        // ignored outright and the higher reading kept; a real restart drops
        // to near zero and is caught below. Uptime going backwards is still a
        // reboot whatever the reading did.
        if (hasLastRaw && rawValue < lastRaw && lastRaw - rawValue <= JITTER_TOLERANCE_STEPS &&
            !elapsedWentBackwards
        ) {
            return null
        }

        var anchorValue = state.anchorValue
        var anchorSteps = state.anchorSteps

        var backfill: Map<String, Int>? = null
        if (state.anchorValue < 0f || bootIdMoved(currentBoot)) {
            // Steps accumulated between boot and this first sample are only
            // claimable when the counter really did restart - proven by the
            // reading going backwards, or by there being no previous reading at
            // all. Without that the reading still contains everything already
            // in stepsToday, and claiming it would add the day's steps to
            // themselves.
            val counterRestarted = !hasLastRaw || counterWentBackwards || elapsedWentBackwards
            val bootDate = DateKeys.of(currentBoot)
            val recovered: Pair<Int, Map<String, Int>?> = when {
                // Never seen a reading: there is no evidence the app existed
                // when the since-boot steps were taken, so history is not
                // invented for them. Same-day boot claims them, as before.
                // Either way this is the moment this device's coverage of
                // the day begins, which is what lets a phone-side Health
                // Connect source fill in the hours before it.
                !hasLastRaw -> if (bootDate == state.activeDate) {
                    state.coverageStartAt = currentBoot
                    rawValue.roundToInt() to null
                } else {
                    state.coverageStartAt = clockProvider()
                    0 to null
                }
                // A proven restart: everything since boot is real, unclaimed,
                // and happened after the last reading. Spread it from the
                // boot instant to now.
                counterRestarted ->
                    recover(rawValue.roundToInt(), currentBoot, clockProvider())
                // The counter did not restart, so the delta since the last
                // reading is exact. It covers whatever happened while the
                // anchor was blank - a midnight rollover, a reset, a
                // wall-clock correction, or an OEM kill that spanned any of
                // those - and is spread from the last reading's time to now.
                else -> recover(
                    (rawValue - lastRaw).roundToInt().coerceAtLeast(0),
                    state.lastEventAt,
                    clockProvider()
                )
            }
            anchorValue = rawValue - recovered.first
            anchorSteps = state.stepsToday
            backfill = recovered.second
        } else if (reanchorOnNextSample || counterWentBackwards || elapsedWentBackwards ||
            rawValue < anchorValue
        ) {
            // Resume, or a sensor restart without a reboot: re-pin at the
            // current reading and keep the total.
            anchorValue = rawValue
            anchorSteps = state.stepsToday
        }
        reanchorOnNextSample = false

        val total = anchorSteps + (rawValue - anchorValue).roundToInt()
        if (total < 0) return null

        val previous = state.stepsToday
        if (paused) {
            // Absorb the delta into the anchor so paused steps never land in
            // the total, and the counter picks straight back up on resume.
            state.writeCounterState(
                bootId = currentBoot,
                anchorValue = rawValue,
                anchorSteps = previous,
                lastRaw = rawValue,
                activeDate = state.activeDate,
                stepsToday = previous,
                lastEventAt = eventAtMillis,
                lastElapsed = currentElapsed
            )
            return null
        }

        state.writeCounterState(
            bootId = currentBoot,
            anchorValue = anchorValue,
            anchorSteps = anchorSteps,
            lastRaw = rawValue,
            activeDate = state.activeDate,
            stepsToday = total,
            lastEventAt = eventAtMillis,
            lastElapsed = currentElapsed
        )

        backfill?.let { shares -> onBackfill?.invoke(shares) }

        if (total == previous) return null
        pendingCommit += (total - previous)
        return snapshot()
    }

    /**
     * TYPE_STEP_DETECTOR fires once per step and carries no cumulative value,
     * so the running total is incremented directly. Only used on devices with
     * no step counter, where steps are lost while the process is dead.
     */
    @Synchronized
    fun onDetectorSample(steps: Int, eventAtMillis: Long): StepSnapshot? {
        if (steps <= 0) return null
        rollDateIfNeeded()
        // A first-ever sample on a detector-only or accelerometer device is
        // where this device's coverage of the day begins, exactly as it is on
        // the counter path; without it a phone-side Health Connect source
        // could never fill the hours before an afternoon install.
        if (state.lastEventAt == 0L && state.lastRawValue < 0f) {
            state.coverageStartAt = eventAtMillis
        }
        if (paused) return null

        val total = state.stepsToday + steps
        // The counter fields are left exactly as they were. Blanking them here
        // would send the first counter sample on a dual-sensor device that fell
        // back to the detector down the claim-everything-since-boot path, on top
        // of the steps the detector already counted.
        state.writeCounterState(
            bootId = bootIdProvider(),
            anchorValue = state.anchorValue,
            anchorSteps = state.anchorSteps,
            lastRaw = state.lastRawValue,
            activeDate = state.activeDate,
            stepsToday = total,
            lastEventAt = eventAtMillis,
            lastElapsed = elapsedProvider()
        )
        // The counter total no longer matches the anchor arithmetic, so make the
        // next counter sample re-pin instead of recomputing from a stale anchor.
        reanchorOnNextSample = true
        pendingCommit += steps
        return snapshot()
    }

    @Synchronized
    fun setPaused(value: Boolean) {
        // Only a real transition arms the re-anchor. Arming it on every
        // startTracking() - including the START_STICKY restart after a process
        // kill - would throw away everything the hardware counted while the
        // process was gone, which is the entire point of TYPE_STEP_COUNTER.
        if (paused == value) return
        paused = value
        // Skip the paused window on the next sample. Clearing the anchor here
        // instead would send the next sample down the first-sample branch,
        // which can claim every step since boot.
        if (!value) reanchorOnNextSample = true
    }

    /**
     * True once enough steps have accumulated to justify a database write.
     * The threshold floors at 1: at 0 the comparison is true even with nothing
     * pending, which turns every sample into a database write and a pair of
     * aggregate queries.
     */
    @Synchronized
    fun shouldCommit(threshold: Int): Boolean =
        pendingCommit > 0 && pendingCommit >= threshold.coerceAtLeast(1)

    @Synchronized
    fun consumeCommit(): DayTotals {
        pendingCommit = 0
        return metrics.totals(state.activeDate, state.stepsToday)
    }

    @Synchronized
    fun snapshot(): StepSnapshot {
        val steps = state.stepsToday
        val goal = metrics.config.dailyGoal
        return StepSnapshot(
            date = state.activeDate,
            steps = steps,
            distance = metrics.distance(steps),
            calories = metrics.calories(steps),
            dailyGoal = goal,
            goalProgress = metrics.goalProgress(steps, goal),
            goalReached = goal > 0 && steps >= goal,
            state = state.trackingState,
            source = state.source,
            timestamp = state.lastEventAt.takeIf { it > 0 } ?: clockProvider()
        )
    }

    /** Zeroes today without touching stored history. */
    @Synchronized
    fun resetToday() {
        pendingCommit = 0
        state.coverageStartAt = 0L
        state.writeCounterState(
            bootId = bootIdProvider(),
            anchorValue = -1f,
            anchorSteps = 0,
            // Keep the last reading. Blanking it would make the next sample
            // look like a fresh counter and replay every step since boot,
            // undoing the reset within one sample on any device booted today.
            lastRaw = state.lastRawValue,
            activeDate = DateKeys.today(),
            stepsToday = 0,
            lastEventAt = clockProvider(),
            lastElapsed = elapsedProvider()
        )
    }

    /**
     * Restores the live total for the active day from storage. Used when the
     * local date moves backwards - travelling west across the date line - and
     * the adopted day already has steps recorded against it.
     */
    @Synchronized
    fun seedActiveDay(date: String, steps: Int) {
        if (date != state.activeDate || steps <= state.stepsToday) return
        state.writeCounterState(
            bootId = bootIdProvider(),
            anchorValue = -1f,
            anchorSteps = 0,
            lastRaw = state.lastRawValue,
            activeDate = state.activeDate,
            stepsToday = steps,
            lastEventAt = clockProvider(),
            lastElapsed = elapsedProvider()
        )
    }

    /** Finalises the previous day and starts a fresh one. */
    private fun rollDateIfNeeded() {
        val today = DateKeys.today()
        val active = state.activeDate
        if (today == active) return

        // The date can also move *backwards*, when the zone moves west across
        // the date line. The day being left is then still in the future and is
        // not finished, and the day being adopted may already have steps against
        // it, so [onDayRollover]'s receiver compares the two dates and re-seeds
        // through [seedActiveDay] rather than letting the counter report zero
        // in the middle of a day the user has already been walking through.
        val closing = metrics.totals(active, state.stepsToday)
        pendingCommit = 0
        // A new day is covered from its start: with a hardware counter, gap
        // recovery reaches back through any dead time to midnight.
        state.coverageStartAt = 0L
        state.writeCounterState(
            bootId = bootIdProvider(),
            anchorValue = -1f,
            anchorSteps = 0,
            lastRaw = state.lastRawValue,
            activeDate = today,
            stepsToday = 0,
            // Kept, along with lastRaw: together they say when and at what
            // reading the day being closed was last seen, which is what the
            // next sample needs in order to place the steps since then on the
            // right side of midnight. Stamping "now" here erased that and put
            // every overnight step into the new day.
            lastEventAt = state.lastEventAt,
            lastElapsed = elapsedProvider()
        )
        onDayRollover?.invoke(closing, today)
    }

    /**
     * True when the derived boot timestamp moved further than clock jitter.
     * This is a *hint* that the device rebooted, never proof: the value is
     * `wallClock - elapsedRealtime`, so any wall-clock correction moves it.
     * Callers must corroborate it with the counter reading itself.
     */
    private fun bootIdMoved(currentBoot: Long): Boolean {
        val stored = state.bootId
        if (stored == 0L) return false
        return abs(currentBoot - stored) > StepStateStore.BOOT_DRIFT_TOLERANCE_MS
    }

    companion object {
        /**
         * A backwards move of at most this many steps is sensor jitter, not a
         * restart. Restarts land near zero; jitter is a step or two.
         */
        const val JITTER_TOLERANCE_STEPS = 3f

        /** Approximate epoch millis at which the device booted. */
        fun currentBootId(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    }
}
