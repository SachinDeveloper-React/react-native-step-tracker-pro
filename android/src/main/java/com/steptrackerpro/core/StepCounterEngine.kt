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
 *    anchor is re-pinned with `anchorSteps = 0` - by the first sample after
 *    it, or by [rollIfNeeded] when none comes.
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
     * steps to add on top of whatever the day already has stored. The reason
     * says what the gap was - a dead process, or a proven reboot - so the
     * `historyBackfilled` event can carry it.
     */
    var onBackfill: ((Map<String, Int>, BackfillReason) -> Unit)? = null

    /**
     * Invoked with steps this device watched being taken, and the span they
     * were taken in: from the previous sample's event time to this one's.
     * `timed` is false when this sample's own timestamp was unusable and
     * `toMillis` is only when it arrived. Never called for steps gap
     * recovery credited in one go, nor for paused samples. Feeds the
     * per-minute integrity timeline; the callee must be cheap, because it
     * runs under this engine's lock on the sensor thread.
     */
    var onObserved: ((fromMillis: Long, toMillis: Long, steps: Int, timed: Boolean) -> Unit)? = null

    /**
     * Invoked when a late sample - one the sensor hub held across midnight
     * and delivered after the day it was taken on had closed - left steps
     * owed to that day in [StepStateStore.lateSteps], for the callee to add
     * with [takeLateSteps]. Runs under this engine's lock; keep it cheap.
     */
    var onLate: (() -> Unit)? = null

    /** Why a past day is being credited after the fact. Values match the JS event. */
    enum class BackfillReason(val jsValue: String) {
        /** Nothing was listening between the last reading and this one. */
        GAP("gap"),
        /** The counter restarted; the steps are the ones taken since boot. */
        REBOOT("reboot"),
        /** Samples taken on the day arrived after it had closed. */
        LATE("late")
    }

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

    /** The most [GapRecovery.TODAY_CAPPED] credits to the active day in one recovery. */
    @Volatile
    var gapRecoveryMaxSteps: Int = DEFAULT_GAP_RECOVERY_MAX_STEPS

    enum class GapRecovery(val jsValue: String) {
        /** Spread across the days in the gap in proportion to time. Default. */
        SPLIT("split"),
        /** Everything to the active day. */
        TODAY("today"),
        /**
         * Everything to the active day, up to [gapRecoveryMaxSteps]; the
         * rest is dropped. A closed day never changes, and one day can never
         * be handed a week's worth of counter.
         */
        TODAY_CAPPED("today_capped"),
        /** Discard anything that cannot be placed on the active day. */
        DROP("drop");

        companion object {
            fun from(value: String?): GapRecovery =
                entries.firstOrNull { it.jsValue == value } ?: SPLIT
        }
    }

    /** What one blank-anchor sample recovered: the active day's share, and other days'. */
    private class Recovered(val today: Int, val others: Map<String, Int>?)

    private fun recover(steps: Int, fromMillis: Long, nowMillis: Long): Recovered {
        if (steps <= 0) return Recovered(0, null)
        // A last reading that is in the future means the wall clock was set
        // back. There is no honest way to place the gap then; keep it on the
        // active day rather than crediting a day that has not happened yet.
        // The cap still applies: a clock set back is exactly the kind of
        // glitch it exists for.
        if (fromMillis > nowMillis) {
            val kept = if (gapRecovery == GapRecovery.TODAY_CAPPED) {
                steps.coerceAtMost(gapRecoveryMaxSteps.coerceAtLeast(0))
            } else {
                steps
            }
            return Recovered(kept, null)
        }
        val shares = StepGapSplitter.apply(
            gapRecovery, fromMillis, nowMillis, steps, state.activeDate, gapRecoveryMaxSteps
        )
        val today = shares[state.activeDate] ?: 0
        val others = shares.filterKeys { it != state.activeDate }.filterValues { it > 0 }
        return Recovered(today, others.takeIf { it.isNotEmpty() })
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
     * Ends the active day when the local date has moved on and no sample has
     * come along to do it - a phone lying still across midnight.
     *
     * @return true when the day rolled over.
     */
    @Synchronized
    fun rollIfNeeded(): Boolean {
        val before = state.activeDate
        rollDateIfNeeded()
        return state.activeDate != before
    }

    /**
     * Steps owed to closed days by late samples, taken and forgotten: the
     * caller adds them to those days. Committed before it returns, so a
     * process death after the caller's write cannot hand them out twice.
     */
    @Synchronized
    fun takeLateSteps(): Map<String, Int> = state.takeLateSteps()

    /** Owes [owed] again: taken by [takeLateSteps], but the write of them failed. */
    @Synchronized
    fun oweLateSteps(owed: Map<String, Int>) {
        var merged = state.lateSteps
        for ((day, steps) in owed) merged = merged + (day to (merged[day] ?: 0) + steps)
        state.putLateSteps(merged)
    }

    /**
     * @param rawValue cumulative steps since boot from TYPE_STEP_COUNTER.
     * @param timed false when [eventAtMillis] is only when the sample
     *   arrived, its own timestamp being unusable; see [SensorEventTime].
     * @return a snapshot when the total changed, otherwise null.
     */
    @Synchronized
    fun onCounterSample(rawValue: Float, eventAtMillis: Long, timed: Boolean = true): StepSnapshot? {
        if (rawValue < 0f || rawValue.isNaN()) return null
        // The day the steps were taken on, when the sample can say - which,
        // for a batch the hub held across midnight, is not the day it arrives.
        val takenOn = if (timed) DateKeys.of(eventAtMillis) else null
        rollDateIfNeeded(takenOn)
        val previousEventAt = state.lastEventAt

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
        var backfillReason = BackfillReason.GAP
        // Steps this sample credits to the active day in one go rather than
        // as an observed delta, so the day's record can say how much of it
        // was apportioned. Everything that goes through recover() qualifies,
        // and so does an install's since-boot claim: none of it was watched.
        var recoveredNow = 0
        // When the steps since an earlier instant ended: this sample's own
        // time when it can say, so a batch delivered after midnight places
        // the steps taken before it on the day they were taken on; the
        // arrival otherwise.
        fun endOfGapSince(start: Long) =
            if (timed && eventAtMillis >= start) eventAtMillis else clockProvider()
        if (state.anchorValue < 0f || bootIdMoved(currentBoot)) {
            // Steps accumulated between boot and this first sample are only
            // claimable when the counter really did restart - proven by the
            // reading going backwards, or by there being no previous reading at
            // all. Without that the reading still contains everything already
            // in stepsToday, and claiming it would add the day's steps to
            // themselves.
            val counterRestarted = !hasLastRaw || counterWentBackwards || elapsedWentBackwards
            val bootDate = DateKeys.of(currentBoot)
            val recovered: Recovered = when {
                // Never seen a reading: there is no evidence the app existed
                // when the since-boot steps were taken, so history is not
                // invented for them. Same-day boot claims them, as before.
                // Either way this is the moment this device's coverage of
                // the day begins, which is what lets a phone-side Health
                // Connect source fill in the hours before it.
                !hasLastRaw -> if (bootDate == state.activeDate) {
                    state.coverageStartAt = currentBoot
                    Recovered(rawValue.roundToInt(), null)
                } else {
                    state.coverageStartAt = clockProvider()
                    Recovered(0, null)
                }
                // A proven restart: everything since boot is real, unclaimed,
                // and happened after the last reading. Spread it from the
                // boot instant to this sample.
                counterRestarted -> {
                    backfillReason = BackfillReason.REBOOT
                    recover(rawValue.roundToInt(), currentBoot, endOfGapSince(currentBoot))
                }
                // The counter did not restart, so the delta since the last
                // reading is exact. It covers whatever happened while the
                // anchor was blank - a midnight rollover, a reset, a
                // wall-clock correction, or an OEM kill that spanned any of
                // those - and is spread from the last reading's time to this
                // sample's.
                else -> recover(
                    (rawValue - lastRaw).roundToInt().coerceAtLeast(0),
                    state.lastEventAt,
                    endOfGapSince(state.lastEventAt)
                )
            }
            anchorValue = rawValue - recovered.today
            anchorSteps = state.stepsToday
            backfill = recovered.others
            recoveredNow = recovered.today
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
            // Whatever recover() apportioned is discarded with them.
            state.writeCounterState(
                bootId = currentBoot,
                anchorValue = rawValue,
                anchorSteps = previous,
                lastRaw = rawValue,
                activeDate = state.activeDate,
                stepsToday = previous,
                lastEventAt = eventAtMillis,
                lastElapsed = currentElapsed,
                recoveredToday = state.recoveredToday
            )
            return null
        }

        // What this sample added beyond what recovery credited in one go is
        // what the sensor was seen counting.
        var observed = total - previous - recoveredNow
        var kept = total
        var owed: Map<String, Int>? = null
        val closedDay = takenOn?.takeIf { it < state.activeDate && observed > 0 }
        if (closedDay != null) {
            // Taken on a day that has closed since: a batch the hub held
            // across midnight, delivered after the heartbeat or a read ended
            // that day. They are that day's steps, not this one's.
            when (lateHandling()) {
                LateHandling.OWN_DAY -> {
                    kept = total - observed
                    owed = owedWith(closedDay, observed)
                }
                LateHandling.ACTIVE_DAY -> {
                    recoveredNow += observed
                    observed = 0
                }
                LateHandling.DROPPED -> {
                    kept = total - observed
                    observed = 0
                }
            }
            if (kept != total) {
                anchorValue = rawValue
                anchorSteps = kept
            }
        }

        state.writeCounterState(
            bootId = currentBoot,
            anchorValue = anchorValue,
            anchorSteps = anchorSteps,
            lastRaw = rawValue,
            activeDate = state.activeDate,
            stepsToday = kept,
            lastEventAt = eventAtMillis,
            lastElapsed = currentElapsed,
            // Never more than the day has: kept < previous + recoveredNow
            // only when the arithmetic clamped, and the share cannot exceed it.
            recoveredToday = (state.recoveredToday + recoveredNow).coerceAtMost(kept),
            lateSteps = owed
        )

        backfill?.let { shares -> onBackfill?.invoke(shares, backfillReason) }
        // Late steps kept for their own day are judged there, in the minutes
        // they were taken in.
        if (observed > 0) onObserved?.invoke(previousEventAt, eventAtMillis, observed, timed)
        if (owed != null) onLate?.invoke()

        if (kept == previous) return null
        pendingCommit += (kept - previous)
        return snapshot()
    }

    /**
     * TYPE_STEP_DETECTOR fires once per step and carries no cumulative value,
     * so the running total is incremented directly. Only used on devices with
     * no step counter, where steps are lost while the process is dead.
     *
     * @param timed as for [onCounterSample].
     */
    @Synchronized
    fun onDetectorSample(steps: Int, eventAtMillis: Long, timed: Boolean = true): StepSnapshot? {
        if (steps <= 0) return null
        val takenOn = if (timed) DateKeys.of(eventAtMillis) else null
        rollDateIfNeeded(takenOn)
        // A first-ever sample on a detector-only or accelerometer device is
        // where this device's coverage of the day begins, exactly as it is on
        // the counter path; without it a phone-side Health Connect source
        // could never fill the hours before an afternoon install.
        if (state.lastEventAt == 0L && state.lastRawValue < 0f) {
            state.coverageStartAt = eventAtMillis
        }
        if (paused) return null

        val previousEventAt = state.lastEventAt
        // A step taken on a closed day goes where it does on the counter path.
        var counted = steps
        var observed = steps
        var recoveredNow = 0
        var owed: Map<String, Int>? = null
        val closedDay = takenOn?.takeIf { it < state.activeDate }
        if (closedDay != null) {
            when (lateHandling()) {
                LateHandling.OWN_DAY -> {
                    counted = 0
                    owed = owedWith(closedDay, steps)
                }
                LateHandling.ACTIVE_DAY -> {
                    recoveredNow = steps
                    observed = 0
                }
                LateHandling.DROPPED -> {
                    counted = 0
                    observed = 0
                }
            }
        }
        val total = state.stepsToday + counted
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
            lastElapsed = elapsedProvider(),
            recoveredToday = (state.recoveredToday + recoveredNow).coerceAtMost(total),
            lateSteps = owed
        )
        // The counter total no longer matches the anchor arithmetic, so make the
        // next counter sample re-pin instead of recomputing from a stale anchor.
        reanchorOnNextSample = true
        if (observed > 0) onObserved?.invoke(previousEventAt, eventAtMillis, observed, timed)
        if (owed != null) onLate?.invoke()
        if (counted == 0) return null
        pendingCommit += counted
        return snapshot()
    }

    /**
     * Where steps taken on a day that has already closed go. The gap policy
     * decides, as it does for every step that belongs to a closed day:
     * `split` puts them on their own day; `today` and `today_capped`, which
     * never change a closed day, give them to the active one in one go; and
     * `drop` keeps nothing it cannot place on the active day.
     */
    private enum class LateHandling { OWN_DAY, ACTIVE_DAY, DROPPED }

    private fun lateHandling(): LateHandling = when (gapRecovery) {
        GapRecovery.SPLIT -> LateHandling.OWN_DAY
        GapRecovery.TODAY, GapRecovery.TODAY_CAPPED -> LateHandling.ACTIVE_DAY
        GapRecovery.DROP -> LateHandling.DROPPED
    }

    /** What is owed to closed days once [day] is owed [steps] more. */
    private fun owedWith(day: String, steps: Int): Map<String, Int> {
        val owed = state.lateSteps
        return owed + (day to (owed[day] ?: 0) + steps)
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
        return metrics.totals(
            state.activeDate, state.stepsToday, recoveredSteps = state.recoveredToday
        )
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
            timestamp = state.lastEventAt.takeIf { it > 0 } ?: clockProvider(),
            recoveredSteps = state.recoveredToday.coerceIn(0, steps)
        )
    }

    /** Zeroes today without touching stored history. */
    @Synchronized
    fun resetToday() {
        // A day that has ended - its midnight not yet acted on, or kept open
        // for a late batch - is closed first, with everything it counted,
        // rather than wiped along with today.
        rollDateIfNeeded()
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
            lastElapsed = elapsedProvider(),
            recoveredToday = 0
        )
    }

    /**
     * Restores the live total for the active day from storage. Used when the
     * local date moves backwards - travelling west across the date line - and
     * the adopted day already has steps recorded against it.
     *
     * @param recoveredSteps the stored row's recovered share, restored with
     *   the total so the day does not read as fully observed afterwards.
     */
    @Synchronized
    fun seedActiveDay(date: String, steps: Int, recoveredSteps: Int = 0) {
        if (date != state.activeDate || steps <= state.stepsToday) return
        state.writeCounterState(
            bootId = bootIdProvider(),
            anchorValue = -1f,
            anchorSteps = 0,
            lastRaw = state.lastRawValue,
            activeDate = state.activeDate,
            stepsToday = steps,
            lastEventAt = clockProvider(),
            lastElapsed = elapsedProvider(),
            recoveredToday = recoveredSteps.coerceIn(0, steps)
        )
    }

    /**
     * Finalises the previous day and starts a fresh one.
     *
     * @param takenOn the day a sample was taken on, when its timestamp can
     *   say. A late sample of the active day - a batch the hub held across
     *   midnight - does not close it: its steps are the active day's, and the
     *   first sample taken after midnight closes the day instead, unless the
     *   heartbeat or a read has closed it already.
     */
    private fun rollDateIfNeeded(takenOn: String? = null) {
        val today = DateKeys.today()
        val active = state.activeDate
        if (today == active) return
        if (takenOn != null && active < today && takenOn <= active) return

        // The date can also move *backwards*, when the zone moves west across
        // the date line. The day being left is then still in the future and is
        // not finished, and the day being adopted may already have steps against
        // it, so [onDayRollover]'s receiver compares the two dates and re-seeds
        // through [seedActiveDay] rather than letting the counter report zero
        // in the middle of a day the user has already been walking through.
        val closing = metrics.totals(
            active, state.stepsToday, recoveredSteps = state.recoveredToday
        )
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
            lastElapsed = elapsedProvider(),
            recoveredToday = 0
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

        /**
         * Default cap for [GapRecovery.TODAY_CAPPED]. Twice a very active
         * day: a genuine overnight gap on a phone that kills services is a
         * few thousand steps, and anything in the tens of thousands after a
         * long dead period is a counter glitch or a week's worth of walking
         * that cannot honestly be given to one day.
         */
        const val DEFAULT_GAP_RECOVERY_MAX_STEPS = 20_000

        /** Approximate epoch millis at which the device booted. */
        fun currentBootId(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    }
}
