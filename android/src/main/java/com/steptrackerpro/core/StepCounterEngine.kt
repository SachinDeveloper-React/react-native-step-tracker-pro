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
 *    picked up. If it was on a previous day they cannot be split across
 *    midnight, so the anchor is pinned at the current reading and they are
 *    dropped rather than misattributed.
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
    private val bootIdProvider: () -> Long = { currentBootId() }
) {

    /** Invoked with the finalised previous day when the date rolls over. */
    var onDayRollover: ((DayTotals, String) -> Unit)? = null

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

        var anchorValue = state.anchorValue
        var anchorSteps = state.anchorSteps

        if (state.anchorValue < 0f || bootIdMoved(currentBoot)) {
            val bootDate = DateKeys.of(currentBoot)
            // Steps accumulated between boot and this first sample are only
            // claimable when the counter really did restart - proven by the
            // reading going backwards, or by there being no previous reading at
            // all - and the boot itself happened on the active day. Without that
            // the reading still contains everything already in stepsToday, and
            // claiming it would add the day's steps to themselves.
            val counterRestarted = !hasLastRaw || counterWentBackwards || elapsedWentBackwards
            anchorValue = if (counterRestarted && bootDate == state.activeDate) 0f else rawValue
            anchorSteps = state.stepsToday
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
            timestamp = state.lastEventAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        )
    }

    /** Zeroes today without touching stored history. */
    @Synchronized
    fun resetToday() {
        pendingCommit = 0
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
            lastEventAt = System.currentTimeMillis(),
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
            lastEventAt = System.currentTimeMillis(),
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
        state.writeCounterState(
            bootId = bootIdProvider(),
            anchorValue = -1f,
            anchorSteps = 0,
            lastRaw = state.lastRawValue,
            activeDate = today,
            stepsToday = 0,
            lastEventAt = System.currentTimeMillis(),
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
        /** Approximate epoch millis at which the device booted. */
        fun currentBootId(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    }
}
