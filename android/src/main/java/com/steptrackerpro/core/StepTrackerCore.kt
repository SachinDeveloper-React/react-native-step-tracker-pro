package com.steptrackerpro.core

import android.content.Context
import com.steptrackerpro.db.StepRepository
import com.steptrackerpro.health.HealthConnectManager
import com.steptrackerpro.health.StepContinuity
import com.steptrackerpro.health.StepSource
import com.steptrackerpro.health.StepSourceKind
import com.steptrackerpro.health.StepSourcePolicy
import com.steptrackerpro.health.StepSourceResolver
import com.steptrackerpro.health.StepSourceTrust
import com.steptrackerpro.health.WearableTrust
import com.steptrackerpro.integrity.DeviceAttestation
import com.steptrackerpro.integrity.IntegrityMonitor
import com.steptrackerpro.sync.SyncScheduler
import com.steptrackerpro.util.StepEventBus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide singleton shared by the React module, the foreground service,
 * the boot receiver and the workers. Holding one instance is what keeps the
 * counter consistent when JS is dead but the service is alive.
 */
class StepTrackerCore private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Every write to the day tables goes through one lane, in submission
     * order. The engine can hand out a rollover and a backfill for the same
     * day within one sample, and two IO coroutines racing on that row would
     * let whichever ran second decide the total.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writeLane: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    val configStore = ConfigStore(appContext)
    val state = StepStateStore(appContext).also { it.migrate() }
    val metrics = MetricsCalculator(configStore.get())
    val repository = StepRepository(appContext)
    val goals = GoalTracker(appContext)
    val healthConnect = HealthConnectManager(appContext, state).apply {
        readActiveCalories = configStore.get().healthConnectReadActiveCalories
    }

    /** Per-minute buckets, the detector, the event log and suspect-step arithmetic. */
    val integrity = IntegrityMonitor(
        appContext, repository, scope, writeLane, ::config
    ) { date -> dayTotals(date).steps }

    val engine = StepCounterEngine(state, metrics).apply {
        onDayRollover = { closing, newDate -> handleRollover(closing, newDate) }
        onBackfill = { shares, reason -> handleBackfill(shares, reason) }
        onObserved = { from, to, steps -> integrity.onObserved(from, to, steps) }
        gapRecovery = StepCounterEngine.GapRecovery.from(configStore.get().gapRecovery)
        gapRecoveryMaxSteps = configStore.get().gapRecoveryMaxSteps
    }

    init {
        // Exclude mode: a verdict that moved today's flagged total moved the
        // shown number too, and JS hears about it without waiting for a step.
        integrity.onLiveChanged = {
            val live = engine.snapshot()
            val resolution = resolveFromCache(DayTotals(live.date, live.steps, live.distance, live.calories))
            StepEventBus.emit(
                StepEventBus.Events.STEPS_CHANGED,
                com.steptrackerpro.util.Bridge.snapshotMap(displaySnapshot(live, resolution), resolution)
            )
        }
    }

    private val lastEventAt = AtomicLong(0L)
    private val trailingEventQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lastSourceRefreshAt = AtomicLong(0L)
    private val syncMutex = Mutex()
    private val sourceCache = SourceCache()

    fun config(): StepTrackerConfig = configStore.get()

    fun updateConfig(config: StepTrackerConfig): StepTrackerConfig {
        val previous = configStore.get()
        val saved = configStore.save(config)
        metrics.config = saved
        healthConnect.readActiveCalories = saved.healthConnectReadActiveCalories
        engine.gapRecovery = StepCounterEngine.GapRecovery.from(saved.gapRecovery)
        engine.gapRecoveryMaxSteps = saved.gapRecoveryMaxSteps
        // A different policy or pin changes what the baseline means, so it is
        // taken again from the next read rather than carried across. So does
        // the manual-entry rule: a baseline taken from a typed-in total would
        // otherwise survive until midnight after the app turned the flag on.
        if (previous.stepSource != saved.stepSource ||
            previous.preferredStepSourcePackage != saved.preferredStepSourcePackage ||
            previous.healthConnectEnabled != saved.healthConnectEnabled ||
            previous.healthConnectReadEnabled != saved.healthConnectReadEnabled ||
            previous.healthConnectIgnoreManualEntries != saved.healthConnectIgnoreManualEntries ||
            previous.wearableTrust != saved.wearableTrust ||
            previous.wearableAllowlist != saved.wearableAllowlist ||
            // Exclude mode lowers the device count the baseline was measured against.
            previous.excludeSuspect != saved.excludeSuspect ||
            // Cached sources were read without (or with) active calories.
            previous.healthConnectReadActiveCalories != saved.healthConnectReadActiveCalories
        ) {
            state.clearContinuity()
            sourceCache.invalidate()
        }
        val changed = integrityRelevantChanges(previous, saved)
        if (changed.isNotEmpty()) {
            // Turning detection off is logged too, though nothing else is
            // logged while it is off: it is the change a server most needs.
            integrity.log(
                IntegrityEvent.CONFIG_CHANGED,
                mapOf("keys" to changed),
                force = previous.fraudDetectionEnabled && !saved.fraudDetectionEnabled
            )
        }
        SyncScheduler.schedule(appContext, saved)
        return saved
    }

    /** Config keys whose change moves a count or a verdict, for the event log. */
    private fun integrityRelevantChanges(previous: StepTrackerConfig, saved: StepTrackerConfig): List<String> {
        val before = previous.toJson()
        val after = saved.toJson()
        return INTEGRITY_CONFIG_KEYS.filter { key -> before.opt(key)?.toString() != after.opt(key)?.toString() }
    }

    fun isInitialized(): Boolean = configStore.isInitialized()

    /** The Health Connect grants this app needs, as configured. */
    fun permissionScope(
        backgroundRead: Boolean? = null,
        historyRead: Boolean? = null
    ): HealthConnectManager.PermissionScope {
        val config = config()
        return HealthConnectManager.PermissionScope(
            read = config.healthConnectReadEnabled,
            write = config.healthConnectWriteEnabled,
            backgroundRead = backgroundRead ?: config.healthConnectBackgroundRead,
            historyRead = historyRead ?: config.healthConnectHistoryRead,
            activeCalories = config.healthConnectReadActiveCalories
        )
    }

    /**
     * Whether the user has tracking on - started, never stopped, and not
     * refused by the hardware. A paused tracker counts: its service is meant
     * to be alive, showing the paused notification with its Resume button.
     */
    fun shouldBeRunning(): Boolean =
        state.shouldAutoStart && state.trackingState != TrackingState.UNSUPPORTED

    /**
     * Whether the tracker is supposed to be alive right now but has no
     * service behind it - the shape of an OEM kill. The service, the workers
     * and the React module all live in one process, so
     * [com.steptrackerpro.service.StepTrackerService.isAlive] is definitive:
     * a SIGKILL takes the flag with the process, and nothing else clears it
     * but `onDestroy`. The heartbeat is reported for diagnostics, not used
     * to decide this.
     */
    fun serviceLooksDead(): Boolean =
        shouldBeRunning() && !com.steptrackerpro.service.StepTrackerService.isAlive

    /** Everything a "is tracking actually working?" screen needs. */
    fun trackingHealth(): Map<String, Any?> {
        val now = System.currentTimeMillis()
        val beat = state.lastHeartbeatAt
        return mapOf(
            "serviceAlive" to com.steptrackerpro.service.StepTrackerService.isAlive,
            "shouldBeRunning" to shouldBeRunning(),
            "lastHeartbeatAt" to beat,
            "heartbeatAgeMs" to (if (beat > 0L) now - beat else -1L),
            "lastSensorEventAt" to state.lastEventAt,
            "lastRecoveryAt" to state.lastRecoveryAt,
            "lastRecoveryReason" to state.lastRecoveryReason,
            "recoveryCount" to state.recoveryCount,
            "looksDead" to serviceLooksDead(),
            "batteryOptimizationEnabled" to
                com.steptrackerpro.util.BatteryOptimizationHelper.isOptimizationEnabled(appContext),
            "aggressiveOem" to com.steptrackerpro.util.BatteryOptimizationHelper.isAggressiveOem(),
            "manufacturer" to android.os.Build.MANUFACTURER
        )
    }

    // ---- step pipeline ---------------------------------------------------

    /**
     * Called from the sensor callback for every changed total. Emits a
     * throttled `stepsChanged`, fires goal events, and flushes to the database
     * every `persistEveryNSteps`.
     */
    fun onStepsChanged(snapshot: StepSnapshot, force: Boolean = false) {
        val config = config()
        state.lastHeartbeatAt = System.currentTimeMillis()

        val resolution = resolveFromCache(
            DayTotals(snapshot.date, snapshot.steps, snapshot.distance, snapshot.calories)
        )
        val shown = displaySnapshot(snapshot, resolution)

        val now = System.currentTimeMillis()
        val previous = lastEventAt.get()
        if (force || now - previous >= config.eventThrottleMs) {
            lastEventAt.set(now)
            StepEventBus.emit(
                StepEventBus.Events.STEPS_CHANGED,
                com.steptrackerpro.util.Bridge.snapshotMap(shown, resolution)
            )
        } else if (trailingEventQueued.compareAndSet(false, true)) {
            // Throttled. Emit once more when the window closes, so JS never
            // sits on the count from one step ago after the user stops.
            scope.launch {
                delay(config.eventThrottleMs - (now - previous))
                trailingEventQueued.set(false)
                val live = engine.snapshot()
                val res = resolveFromCache(
                    DayTotals(live.date, live.steps, live.distance, live.calories)
                )
                lastEventAt.set(System.currentTimeMillis())
                StepEventBus.emit(
                    StepEventBus.Events.STEPS_CHANGED,
                    com.steptrackerpro.util.Bridge.snapshotMap(displaySnapshot(live, res), res)
                )
            }
        }

        // Goals follow the number on screen. Under `auto` that number is
        // monotonic for the day (see StepContinuity), and GoalTracker fires
        // each goal once per period regardless, so a wearable total that
        // arrives in jumps cannot make a goal fire twice.
        val percent = metrics.goalPercent(shown.steps, config.dailyGoal)
        if (goals.progressBucketChanged(GoalTracker.TYPE_DAILY, percent)) {
            StepEventBus.emit(
                StepEventBus.Events.GOAL_PROGRESS_CHANGED,
                mapOf(
                    "type" to GoalTracker.TYPE_DAILY,
                    "goal" to config.dailyGoal,
                    "steps" to shown.steps,
                    "progress" to shown.goalProgress,
                    "date" to shown.date
                )
            )
        }

        goals.checkDaily(shown.date, shown.steps, config.dailyGoal)?.let { reached ->
            emitGoalReached(reached, shown.date)
        }

        refreshTodaySourcesIfStale()

        if (force || engine.shouldCommit(config.persistEveryNSteps)) {
            val totals = engine.consumeCommit()
            scope.launch(writeLane) {
                repository.saveDay(totals)
                runCatching { integrity.flush() }
                checkPeriodGoals(totals)
            }
        }
        integrity.maybeEvaluate()
    }

    private fun emitGoalReached(reached: GoalTracker.Reached, date: String) {
        StepEventBus.emit(
            StepEventBus.Events.GOAL_REACHED,
            mapOf(
                "type" to reached.type,
                "goal" to reached.goal,
                "steps" to reached.steps,
                "date" to date,
                "timestamp" to System.currentTimeMillis()
            )
        )
    }

    /**
     * Runs on every commit, so it must not query unconditionally: once a period
     * goal has fired there is nothing left to learn from summing that period
     * again, and at `persistEveryNSteps = 10` this path is reached every few
     * seconds for the length of a walk.
     */
    private suspend fun checkPeriodGoals(totals: DayTotals) {
        val config = config()

        val (weekStart, weekEnd) = DateKeys.calendarWeek()
        if (config.weeklyGoal > 0 && !goals.alreadyFired(GoalTracker.TYPE_WEEKLY, weekStart)) {
            goals.checkWeekly(repository.sumSteps(weekStart, weekEnd), config.weeklyGoal)?.let {
                emitGoalReached(it, totals.date)
            }
        }

        val (monthStart, monthEnd) = DateKeys.calendarMonth()
        if (config.monthlyGoal > 0 && !goals.alreadyFired(GoalTracker.TYPE_MONTHLY, monthStart)) {
            goals.checkMonthly(repository.sumSteps(monthStart, monthEnd), config.monthlyGoal)?.let {
                emitGoalReached(it, totals.date)
            }
        }
    }

    /**
     * Steps the engine recovered for days other than the active one - the part
     * of an overnight gap that fell before midnight. Added on top of whatever
     * those days already have, then re-queued for sync, and announced per day
     * through `historyBackfilled` once the write has committed: an app that
     * has already settled that day - paid for it, shown it, uploaded it -
     * needs to know its number moved, and nothing else tells it.
     */
    private fun handleBackfill(
        shares: Map<String, Int>,
        reason: StepCounterEngine.BackfillReason
    ) {
        val retention = config().historyRetentionDays
        val cutoff = DateKeys.minusDays(DateKeys.today(), retention)
        scope.launch(writeLane) {
            shares.forEach { (date, steps) ->
                if (date < cutoff || steps <= 0) return@forEach
                val stored = repository.addToDay(date, steps, metrics) ?: return@forEach
                StepEventBus.emit(
                    StepEventBus.Events.HISTORY_BACKFILLED,
                    mapOf(
                        "date" to date,
                        "addedSteps" to steps,
                        "totalSteps" to stored.steps,
                        "reason" to reason.jsValue
                    )
                )
            }
            sourceCache.invalidate()
        }
    }

    /** Persists the closing day, tells JS, and trims history past retention. */
    private fun handleRollover(closing: DayTotals, newDate: String) {
        state.clearContinuity()
        sourceCache.invalidate()
        scope.launch(writeLane) {
            repository.saveDay(closing)
            // The closing day's final verdict, from everything flushed for it.
            runCatching { integrity.onDayClosed(closing.date) }
            // The local date can also move backwards - travelling west across
            // the date line - in which case the day being adopted is one that
            // already has steps against it. Restore them so the user does not
            // land to a counter reading zero halfway through their day.
            if (newDate < closing.date) {
                val stored = repository.getDay(newDate)
                engine.seedActiveDay(newDate, stored.steps, stored.recoveredSteps)
            }
            repository.prune(config().historyRetentionDays)
            StepEventBus.emit(
                StepEventBus.Events.DAY_CHANGED,
                mapOf(
                    "previousDate" to closing.date,
                    "currentDate" to newDate,
                    "previousDaySteps" to closing.steps
                )
            )
            if (config().healthConnectEnabled) syncHealthConnect()
        }
    }

    /**
     * Zeroes today. Runs on the write lane so a commit already queued for the
     * old total cannot land after the overwrite and resurrect it.
     */
    suspend fun resetToday() = withContext(writeLane) {
        engine.resetToday()
        state.clearContinuity()
        goals.reset()
        sourceCache.invalidate()
        // Deliberate write: saveDay() refuses to lower a day, which would
        // leave history and the live counter permanently disagreeing.
        val today = liveToday()
        repository.overwriteDay(today)
        // Logged, and today's minutes go with the count they described.
        runCatching { integrity.onReset(today.date) }
    }

    suspend fun clearHistory() = withContext(writeLane) {
        repository.clear()
        integrity.onHistoryCleared()
    }

    suspend fun pruneHistory(retentionDays: Int): Int =
        withContext(writeLane) { repository.prune(retentionDays) }

    /** Writes whatever is in memory to the database. Called before shutdown. */
    fun flush(): DayTotals {
        val totals = engine.consumeCommit()
        scope.launch(writeLane) {
            repository.saveDay(totals)
            runCatching { integrity.flush() }
        }
        return totals
    }

    fun emitTrackingState(reason: String? = null) {
        val snapshot = engine.snapshot()
        StepEventBus.emit(
            StepEventBus.Events.TRACKING_STATE_CHANGED,
            mapOf(
                "state" to snapshot.state.jsValue,
                "source" to snapshot.source.jsValue,
                "reason" to reason
            )
        )
    }

    // ---- reads -----------------------------------------------------------

    /** Today's live totals, straight from the counter rather than the database. */
    fun liveToday(): DayTotals {
        val snapshot = engine.snapshot()
        return DayTotals(
            snapshot.date,
            snapshot.steps,
            snapshot.distance,
            snapshot.calories,
            false,
            recoveredSteps = snapshot.recoveredSteps
        )
    }

    suspend fun stats(start: String, end: String, goal: Int?): RangeStats {
        val today = DateKeys.today()
        val live = if (today in start..end) liveToday() else null
        return repository.stats(start, end, goal, live)
    }

    suspend fun dayTotals(date: String): DayTotals =
        if (date == DateKeys.today()) liveToday() else repository.getDay(date)

    /** Stored rows for a range, as they are, with each day's suspect steps stamped on. */
    suspend fun history(start: String, end: String): List<DayTotals> {
        val days = repository.getRange(start, end)
        val suspects = integrity.suspects(days)
        if (suspects.isEmpty()) return days
        return days.map { it.copy(suspectSteps = suspects[it.date] ?: 0) }
    }

    // ---- integrity ---------------------------------------------------------

    /**
     * [day] as the resolver should see it: [excluded] flagged steps taken out
     * and distance and calories re-derived, and the day's suspect figure
     * stamped either way so every record can report it.
     */
    private fun adjusted(day: DayTotals, suspect: Int, excluded: Int): DayTotals {
        if (excluded <= 0) return if (day.suspectSteps == suspect) day else day.copy(suspectSteps = suspect)
        val kept = (day.steps - excluded).coerceAtLeast(0)
        return day.copy(
            steps = kept,
            distance = metrics.distance(kept),
            calories = metrics.calories(kept),
            recoveredSteps = day.recoveredSteps.coerceAtMost(kept),
            suspectSteps = suspect
        )
    }

    /** Carries the day's suspect figure onto whichever source won, and says how much was taken out. */
    private fun stamp(
        resolution: StepSourceResolver.Resolution,
        suspect: Int,
        excluded: Int
    ): StepSourceResolver.Resolution {
        if (suspect == 0 && excluded == 0) return resolution
        return resolution.copy(
            totals = resolution.totals.copy(suspectSteps = suspect),
            suspectStepsExcluded = excluded
        )
    }

    /**
     * What goes into Health Connect for a day: this device's own count, less
     * the flagged steps under exclude mode, so a shaken phone's steps are not
     * handed on to every other app reading Health Connect.
     */
    suspend fun mirrorTotals(day: DayTotals): DayTotals {
        val suspect = integrity.suspect(day.date, day.steps)
        val excluded = integrity.excluded(suspect)
        return if (excluded > 0) adjusted(day, suspect, excluded) else day
    }

    // ---- step source resolution -----------------------------------------

    fun sourcePolicy(): StepSourcePolicy = StepSourcePolicy.from(config().stepSource)

    fun wearableTrust(): WearableTrust = WearableTrust.from(config().wearableTrust)

    fun wearableAllowlist(): Set<String> = config().wearableAllowlist.toSet()

    /**
     * Sources as JS should see them, with `trustedWearable` decided under the
     * current trust rule. Stamped on the way out rather than when read, so a
     * cached list never carries a decision made under a previous config.
     */
    fun stampTrust(sources: List<StepSource>): List<StepSource> =
        StepSourceTrust.stamp(sources, wearableTrust(), wearableAllowlist())

    /** A config pin wins; otherwise whatever the user last chose at runtime. */
    fun preferredSourcePackage(): String? =
        config().preferredStepSourcePackage ?: state.preferredStepSource

    fun setPreferredSourcePackage(packageName: String?) {
        state.preferredStepSource = packageName
        state.clearContinuity()
        sourceCache.invalidate()
    }

    /**
     * True when Health Connect should be consulted for the number at all.
     * Everything downstream short-circuits on this so that a device with no
     * provider, no grant or the `device` policy never pays for a read.
     */
    private suspend fun shouldConsultHealthConnect(): Boolean {
        val config = config()
        if (!config.healthConnectEnabled || !config.healthConnectReadEnabled) return false
        if (sourcePolicy() == StepSourcePolicy.DEVICE) return false
        if (healthConnect.availability() != HealthConnectManager.Availability.AVAILABLE) return false
        return healthConnect.canRead()
    }

    /**
     * Decides which source owns one day and returns its totals.
     *
     * Falls back to this device silently whenever Health Connect cannot answer.
     * A read failure must not blank the screen: the phone's own count is always
     * a valid answer, just possibly a low one.
     */
    suspend fun resolveDay(date: String): StepSourceResolver.Resolution {
        val raw = dayTotals(date)
        val suspect = integrity.suspect(date, raw.steps)
        val excluded = integrity.excluded(suspect)
        return stamp(resolveWith(date, adjusted(raw, suspect, excluded)), suspect, excluded)
    }

    private suspend fun resolveWith(date: String, device: DayTotals): StepSourceResolver.Resolution {
        val policy = sourcePolicy()
        val today = date == DateKeys.today()
        if (!shouldConsultHealthConnect()) {
            val raw = StepSourceResolver.resolve(
                StepSourcePolicy.DEVICE, device, emptyList(), null, metrics
            )
            // A baseline taken earlier today still stands when Health Connect
            // can no longer be read - the grant was revoked from its settings,
            // the provider is mid-update. Those steps happened; and the sensor
            // path keeps applying the baseline, so this path must too, or the
            // screen and the notification disagree. An explicit revoke through
            // this package clears it.
            return if (policy == StepSourcePolicy.AUTO && today) {
                StepContinuity.apply(raw, storedBaseline(), metrics)
            } else {
                raw
            }
        }
        val raw = StepSourceResolver.resolve(
            policy, device, sourcesForDay(date), preferredSourcePackage(), metrics,
            deviceCoverageReliable = coverageReliable(),
            ignoreManualEntries = config().healthConnectIgnoreManualEntries,
            wearableTrust = wearableTrust(),
            wearableAllowlist = wearableAllowlist()
        )
        if (policy != StepSourcePolicy.AUTO || !today) return raw
        // Past days are closed: max() is the right answer and nothing is
        // moving underneath it. Today is where the frozen-number problem
        // lives, so today is where the baseline is taken and applied.
        return StepContinuity.apply(raw, observeContinuity(raw, date), metrics)
    }

    /**
     * Whether this device's count can vouch for the whole of the day it has
     * covered. With a hardware counter it can: gap recovery reaches back
     * through any dead time. With the detector or the accelerometer, steps
     * taken while the process was dead are gone, so a phone-side app that
     * kept counting - Samsung Health runs as a system app and is not killed -
     * has genuinely seen more, and is trusted for its whole margin.
     */
    private fun coverageReliable(): Boolean = state.source == SensorSource.STEP_COUNTER

    // ---- verification ----------------------------------------------------

    /**
     * Every Health Connect origin for one day, unresolved, self included,
     * with `trustedWearable` stamped - or nothing, under the same rules that
     * keep every other read off Health Connect when it cannot be consulted.
     */
    suspend fun unresolvedSources(date: String): List<StepSource> =
        if (shouldConsultHealthConnect()) stampTrust(sourcesForDay(date)) else emptyList()

    /**
     * Everything a server needs to judge one day, with nothing resolved for
     * it: this phone's own count and recovered share, every Health Connect
     * origin unresolved and self included, what the current policy would
     * have said (for comparison only), the sensor and device the count came
     * from, how often the service has had to be recovered, and the clock. A
     * server that pays for steps never trusts a single resolved number; this
     * is the shape it verifies instead of stitching four calls together.
     *
     * Health Connect is consulted under the same rules as every other read:
     * no grant, no provider or the `device` policy leave `sources` empty and
     * `resolved` on this device, never an error.
     */
    suspend fun verificationSnapshot(
        date: String,
        sign: Boolean = false,
        nonce: String? = null
    ): Map<String, Any?> {
        val today = date == DateKeys.today()
        if (today) engine.reconcile()
        val device = dayTotals(date)
        val report = integrity.report(date)
        val sources = unresolvedSources(date)
        val resolved = resolveDay(date)
        val capabilities = com.steptrackerpro.util.PermissionHelper.capabilities(
            appContext, allowAccelerometer = config().accelerometerFallback
        )
        val health = trackingHealth()
        val zone = DateKeys.zone()
        val now = System.currentTimeMillis()
        val snapshot = linkedMapOf<String, Any?>(
            // First, so a server can pick a parser before reading anything else.
            "schemaVersion" to SNAPSHOT_SCHEMA_VERSION,
            "libraryVersion" to com.steptrackerpro.BuildConfig.LIBRARY_VERSION,
            "date" to date,
            // This phone's own sensor, before any policy. Never Health Connect.
            "deviceSteps" to device.steps,
            "recoveredSteps" to device.recoveredSteps,
            "sensor" to state.source.jsValue,
            // Only today's coverage is known; a past day reads 0, which is
            // also what a day covered from midnight reads.
            "coverageStartAt" to if (today) {
                coverageStartForToday().takeIf { it > DateKeys.startOfDayMillis(date) } ?: 0L
            } else {
                0L
            },
            "sources" to sources.map { it.toMap() },
            "resolved" to resolved.toMap(),
            "capabilities" to mapOf(
                "hasStepCounter" to capabilities["hasStepCounter"],
                "hasStepDetector" to capabilities["hasStepDetector"],
                "manufacturer" to capabilities["manufacturer"],
                "model" to capabilities["model"],
                "sdkInt" to capabilities["sdkInt"]
            ),
            "health" to mapOf(
                "recoveryCount" to health["recoveryCount"],
                "lastRecoveryReason" to health["lastRecoveryReason"],
                "batteryOptimizationEnabled" to health["batteryOptimizationEnabled"],
                "aggressiveOem" to health["aggressiveOem"]
            ),
            // The wall clock next to a boot id derived from elapsedRealtime:
            // a clock edit moves the first and not the uptime behind the
            // second, so a server comparing snapshots can see the seam.
            "clock" to mapOf(
                "wallClockMs" to now,
                "bootId" to StepCounterEngine.currentBootId(),
                "timezone" to zone.id,
                "utcOffsetMinutes" to zone.rules.getOffset(Instant.ofEpochMilli(now)).totalSeconds / 60
            ),
            // Of deviceSteps, what the integrity checks flagged; and the
            // flags, events and device hints behind it.
            "suspectSteps" to report["suspectSteps"],
            "integrity" to report
        )
        if (nonce != null) snapshot["nonce"] = nonce
        if (!sign) return snapshot
        return signed(snapshot, now)
    }

    /**
     * The snapshot with a `signature` block over its exact JSON text. The
     * text itself travels as `signedPayload`, so a server verifies the
     * signature over those bytes and then parses them - it never has to
     * re-serialise anything the same way this side did.
     */
    private fun signed(snapshot: Map<String, Any?>, now: Long): Map<String, Any?> {
        val body = LinkedHashMap(snapshot).apply { put("signedAt", now) }
        val payload = JsonMaps.toJson(body)
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val signature = DeviceAttestation.sign(appContext, bytes) + mapOf(
            "signedPayload" to payload,
            "payloadSha256" to DeviceAttestation.sha256Hex(bytes)
        )
        return body + ("signature" to signature)
    }

    // ---- motion windows --------------------------------------------------

    /**
     * Stores one window's features and tells JS. On the write lane like every
     * other table write; the table is trimmed to `motionWindowRetention` in
     * the same transaction, so it is bounded by construction.
     */
    fun recordMotionWindow(features: MotionFeatures) {
        val retention = config().motionWindowRetention
        scope.launch(writeLane) {
            repository.addMotionWindow(features, retention)
            StepEventBus.emit(StepEventBus.Events.MOTION_WINDOW, features.toMap())
        }
    }

    /** Windows that opened on the days between the two keys, inclusive. */
    suspend fun motionWindows(start: String, end: String): List<MotionFeatures> =
        repository.motionWindows(DateKeys.startOfDayMillis(start), DateKeys.endOfDayMillis(end))

    // ---- continuity ------------------------------------------------------

    private fun storedBaseline(): StepContinuity.Baseline? {
        val date = state.continuityDate ?: return null
        val offset = state.continuityOffset
        if (offset <= 0) return null
        return StepContinuity.Baseline(
            date = date,
            offset = offset,
            packageName = state.continuityPackage,
            kind = StepSourceKind.from(state.continuityKind),
            appName = state.continuityAppName ?: "Health Connect",
            observedAt = state.continuityObservedAt
        )
    }

    /** Folds a fresh raw resolution into the stored baseline and returns it. */
    private fun observeContinuity(
        raw: StepSourceResolver.Resolution,
        date: String
    ): StepContinuity.Baseline? {
        val current = storedBaseline()
        val next = StepContinuity.observe(current, raw, date, System.currentTimeMillis())
        if (next !== current) {
            if (next == null) {
                state.clearContinuity()
            } else {
                state.writeContinuity(
                    next.date, next.offset, next.packageName,
                    next.kind.jsValue, next.appName, next.observedAt
                )
            }
        }
        return next
    }

    /**
     * Today's origin split goes stale thirty seconds after the last read. The
     * sensor path cannot fetch it, but it can ask for a fetch: a walk with the
     * app closed then keeps the notification and `stepsChanged` in step with a
     * watch that syncs mid-walk, instead of waiting for the next screen open.
     * Throttled well below the cache TTL so it never becomes a poll.
     */
    private fun refreshTodaySourcesIfStale() {
        val config = config()
        if (!config.healthConnectEnabled || !config.healthConnectReadEnabled) return
        if (sourcePolicy() == StepSourcePolicy.DEVICE) return
        val today = DateKeys.today()
        if (sourceCache.get(today) != null) return
        val now = System.currentTimeMillis()
        val last = lastSourceRefreshAt.get()
        if (now - last < SOURCE_REFRESH_MIN_INTERVAL_MS) return
        if (!lastSourceRefreshAt.compareAndSet(last, now)) return
        scope.launch { runCatching { resolveToday() } }
    }

    /**
     * Today, resolved, plus a `stepSourceChanged` event when the winner has
     * flipped since the last look - which is what a watch coming into range
     * mid-morning looks like from here.
     */
    suspend fun resolveToday(): StepSourceResolver.Resolution {
        val resolution = resolveDay(DateKeys.today())
        val key = resolution.sourcePackage ?: SELF_SOURCE_KEY
        if (state.lastResolvedSource != key) {
            state.lastResolvedSource = key
            StepEventBus.emit(StepEventBus.Events.STEP_SOURCE_CHANGED, resolution.toMap())
        }
        return resolution
    }

    /**
     * Range stats with every day resolved against Health Connect.
     *
     * The per-origin data for the whole window is fetched once rather than per
     * day: a month of raw step records is a single paged read, thirty of them
     * is thirty.
     */
    suspend fun resolvedStats(start: String, end: String, goal: Int?): RangeStats {
        val stored = stats(start, end, goal)
        // Each day's suspect figure stamped on, and under exclude mode taken
        // out before any source competes - the same order resolveDay() uses.
        val suspects = integrity.suspects(stored.days)
        val base = if (suspects.isEmpty()) {
            stored
        } else {
            stored.copy(days = stored.days.map { day ->
                val suspect = suspects[day.date] ?: 0
                adjusted(day, suspect, integrity.excluded(suspect))
            })
        }
        if (!shouldConsultHealthConnect()) {
            // Same rule as resolveDay(): today's baseline still applies.
            val baseline = if (sourcePolicy() == StepSourcePolicy.AUTO) storedBaseline() else null
            if (baseline == null) return if (base === stored) stored else recomputeStats(base, base.days, goal)
            val today = DateKeys.today()
            val days = base.days.map { day ->
                if (day.date != today) return@map day
                val raw = StepSourceResolver.resolve(
                    StepSourcePolicy.DEVICE, day, emptyList(), null, metrics
                )
                StepContinuity.apply(raw, baseline, metrics).totals.copy(suspectSteps = day.suspectSteps)
            }
            return recomputeStats(base, days, goal)
        }

        val byDate = runCatching {
            withTimeoutOrNull(HC_READ_TIMEOUT_MS) {
                healthConnect.readDailyStepsBySource(
                    DateKeys.startOfDayInstant(start),
                    minOf(DateKeys.endOfDayInstant(end), Instant.now()),
                    coverageStartForToday()
                )
            }
        }.getOrNull() ?: emptyMap()

        val policy = sourcePolicy()
        val preferred = preferredSourcePackage()
        val today = DateKeys.today()
        val baseline = if (policy == StepSourcePolicy.AUTO) storedBaseline() else null
        val reliable = coverageReliable()
        val ignoreManual = config().healthConnectIgnoreManualEntries
        val trust = wearableTrust()
        val allowlist = wearableAllowlist()
        val resolved = base.days.map { day ->
            val raw = StepSourceResolver.resolve(
                policy, day, byDate[day.date].orEmpty(), preferred, metrics,
                deviceCoverageReliable = reliable,
                ignoreManualEntries = ignoreManual,
                wearableTrust = trust,
                wearableAllowlist = allowlist
            )
            val totals = if (day.date == today && baseline != null) {
                StepContinuity.apply(raw, baseline, metrics).totals
            } else {
                raw.totals
            }
            if (totals.suspectSteps == day.suspectSteps) totals else totals.copy(suspectSteps = day.suspectSteps)
        }
        return recomputeStats(base, resolved, goal)
    }

    /**
     * Re-derives the aggregate fields after individual days were swapped out.
     * Deliberately not delegating back to the repository: these days no longer
     * match what is stored, so a re-query would undo the resolution.
     *
     * Every rule here has to match [StepRepository.stats], or a user with a
     * watch would see a different average from one without for the same data.
     */
    private fun recomputeStats(
        base: RangeStats,
        days: List<DayTotals>,
        goal: Int?
    ): RangeStats {
        val totalSteps = days.sumOf { it.steps }
        // Averaged over elapsed days only, so a partially finished month is not
        // diluted by days that have not happened yet.
        val today = DateKeys.today()
        val elapsed = if (today in base.startDate..base.endDate) {
            DateKeys.daysBetween(
                base.startDate,
                if (today < base.endDate) today else base.endDate
            )
        } else {
            days.size
        }.coerceAtLeast(1)
        return base.copy(
            totalSteps = totalSteps,
            totalDistance = days.sumOf { it.distance },
            totalCalories = days.sumOf { it.calories },
            averageSteps = totalSteps / elapsed,
            activeDays = days.count { it.steps > 0 },
            bestDay = days.maxByOrNull { it.steps }?.takeIf { it.steps > 0 },
            days = days,
            goal = goal,
            goalProgress = goal?.takeIf { it > 0 }?.let {
                (totalSteps.toDouble() / it).coerceIn(0.0, 1.0)
            }
        )
    }

    /** Every Health Connect origin contributing to a range, this device included. */
    suspend fun listSources(start: String, end: String): List<StepSource> {
        val config = config()
        if (config.healthConnectEnabled && config.healthConnectReadEnabled &&
            healthConnect.availability() == HealthConnectManager.Availability.AVAILABLE &&
            healthConnect.canRead()
        ) {
            return runCatching {
                withTimeoutOrNull(HC_READ_TIMEOUT_MS) {
                    healthConnect.listSources(
                        DateKeys.startOfDayInstant(start),
                        minOf(DateKeys.endOfDayInstant(end), Instant.now())
                    )
                }
            }.getOrNull()?.let { stampTrust(it) } ?: emptyList()
        }
        return emptyList()
    }

    /**
     * Resolution from data already in the cache, with no Health Connect call.
     *
     * The sensor callback and the notification both run on paths that cannot
     * suspend and fire several times a second during a walk, so they cannot do
     * IPC. Serving the last known origin split keeps the live stream, the
     * notification and `getTodaySteps()` reporting the same number instead of
     * disagreeing with each other; the cache is refreshed by any read that can
     * afford to, and by every Health Connect sync.
     */
    fun resolveFromCache(totals: DayTotals): StepSourceResolver.Resolution {
        val suspect = integrity.liveSuspect(totals.date, totals.steps)
        val excluded = integrity.excluded(suspect)
        return stamp(resolveCachedWith(adjusted(totals, suspect, excluded)), suspect, excluded)
    }

    private fun resolveCachedWith(totals: DayTotals): StepSourceResolver.Resolution {
        val deviceOnly = {
            StepSourceResolver.resolve(
                StepSourcePolicy.DEVICE, totals, emptyList(), null, metrics
            )
        }
        val config = config()
        val policy = sourcePolicy()
        if (policy == StepSourcePolicy.DEVICE || !config.healthConnectEnabled ||
            !config.healthConnectReadEnabled
        ) {
            return deviceOnly()
        }
        val cached = sourceCache.get(totals.date)
        val raw = if (cached == null) {
            deviceOnly()
        } else {
            StepSourceResolver.resolve(
                policy, totals, cached, preferredSourcePackage(), metrics,
                deviceCoverageReliable = coverageReliable(),
                ignoreManualEntries = config.healthConnectIgnoreManualEntries,
                wearableTrust = wearableTrust(),
                wearableAllowlist = wearableAllowlist()
            )
        }
        if (policy != StepSourcePolicy.AUTO) return raw
        // The baseline outlives the cache on purpose: with the cache expired
        // and no read in flight, the phone's live count plus the last known
        // lead is still the best number, and it keeps moving.
        return StepContinuity.apply(raw, storedBaseline(), metrics)
    }

    /**
     * The snapshot as it should be shown, with a wearable's numbers swapped in
     * where one owns the day. `state` and `source` still describe this device's
     * own sensor, which is a separate question from where the count came from.
     */
    fun displaySnapshot(base: StepSnapshot = engine.snapshot()): StepSnapshot =
        displaySnapshot(
            base,
            resolveFromCache(DayTotals(base.date, base.steps, base.distance, base.calories))
        )

    fun displaySnapshot(
        base: StepSnapshot,
        resolution: StepSourceResolver.Resolution
    ): StepSnapshot {
        val totals = resolution.totals
        // Swapped when another source won, and when exclude mode took
        // flagged steps out of this device's own count.
        if (!resolution.usedExternal && totals.steps == base.steps) {
            return if (base.suspectSteps == totals.suspectSteps) base else base.copy(suspectSteps = totals.suspectSteps)
        }
        return base.copy(
            steps = totals.steps,
            distance = totals.distance,
            calories = totals.calories,
            goalProgress = metrics.goalProgress(totals.steps, base.dailyGoal),
            goalReached = base.dailyGoal > 0 && totals.steps >= base.dailyGoal,
            suspectSteps = totals.suspectSteps
        )
    }

    /**
     * Epoch millis from which this device has been counting today, for the
     * coverage split on Health Connect reads. Start of day unless a first-ever
     * reading (an install) said otherwise.
     */
    private fun coverageStartForToday(): Long {
        val today = DateKeys.today()
        val stored = state.coverageStartAt
        val startOfDay = DateKeys.startOfDayMillis(today)
        return if (stored > startOfDay && state.activeDate == today) stored else startOfDay
    }

    private suspend fun sourcesForDay(date: String): List<StepSource> {
        sourceCache.get(date)?.let { return it }
        val end = minOf(DateKeys.endOfDayInstant(date), Instant.now())
        val start = DateKeys.startOfDayInstant(date)
        if (!end.isAfter(start)) return emptyList()
        val coverage = if (date == DateKeys.today()) coverageStartForToday() else 0L
        val sources = runCatching {
            withTimeoutOrNull(HC_READ_TIMEOUT_MS) {
                healthConnect.readDailyStepsBySource(start, end, coverage)[date].orEmpty()
            }
        }.getOrNull() ?: return emptyList() // a timeout is not cached: try again next read
        sourceCache.put(date, sources)
        return sources
    }

    /**
     * Health Connect reads are cross-process IPC plus a database query, and
     * `getTodaySteps()` is the call an app makes on every screen focus. Serving
     * a few seconds' stale origin split from memory keeps that cheap; the
     * device's own count inside the resolution is always live regardless,
     * because it comes from the engine rather than from here.
     */
    private class SourceCache {
        private data class Entry(val at: Long, val sources: List<StepSource>)

        private val entries = HashMap<String, Entry>()

        @Synchronized
        fun get(date: String): List<StepSource>? {
            val entry = entries[date] ?: return null
            val ttl = if (date == DateKeys.today()) TODAY_TTL_MS else PAST_TTL_MS
            if (System.currentTimeMillis() - entry.at > ttl) {
                entries.remove(date)
                return null
            }
            return entry.sources
        }

        @Synchronized
        fun put(date: String, sources: List<StepSource>) {
            if (entries.size > MAX_ENTRIES) entries.clear()
            entries[date] = Entry(System.currentTimeMillis(), sources)
        }

        @Synchronized
        fun invalidate() = entries.clear()

        private companion object {
            const val TODAY_TTL_MS = 30_000L
            const val PAST_TTL_MS = 600_000L
            const val MAX_ENTRIES = 64
        }
    }

    // ---- sync ------------------------------------------------------------

    /** Pushes every unsynced day (plus today) into Health Connect. */
    suspend fun syncHealthConnect(): Map<String, Any?> = syncMutex.withLock {
        val config = config()
        // These are states the user has to change, not transient faults.
        // Reporting them as retryable would have the periodic worker back off
        // and try again forever on a device that will never succeed.
        if (!config.healthConnectEnabled) {
            return@withLock syncResult("health_connect", 0, 0, false, "Disabled in config")
        }
        if (!config.healthConnectWriteEnabled) {
            return@withLock syncResult("health_connect", 0, 0, true, "Writing disabled in config")
        }
        if (healthConnect.availability() != HealthConnectManager.Availability.AVAILABLE) {
            return@withLock syncResult(
                "health_connect", 0, 0, false, "Health Connect unavailable"
            )
        }
        // Only the write grants matter here. Requiring the read grants too meant
        // a user who allowed writing but refused reading got no mirror at all,
        // even though every call this method makes was permitted.
        if (!healthConnect.canWrite()) {
            return@withLock syncResult(
                "health_connect", 0, 0, false, "Health Connect write permission not granted"
            )
        }

        // Today is written every time so the mirror stays close to live; older
        // days are only written while they are still marked unsynced.
        val today = liveToday()
        repository.saveDay(today)
        val pending = repository.unsynced(SyncTarget.HEALTH_CONNECT, limit = 100)
        // Today first, so its live totals win over the row just written for it.
        val targets = (listOf(today) + pending).distinctBy { it.date }

        var succeeded = 0
        var failed = 0
        var skipped = 0
        val syncedDates = ArrayList<String>(targets.size)
        for (day in targets) {
            // A day a wearable already owns must not be mirrored from here.
            // Health Connect keeps origins separate, so writing this phone's
            // parallel count of the same walk leaves every other app reading
            // Health Connect with both copies of it.
            if (isOwnedByExternalSource(day.date)) {
                skipped++
                // Marked done rather than left pending: nothing about this day
                // will ever make it writable, and leaving it queued would have
                // the worker retry it for as long as it stays in retention.
                if (day.date != today.date) syncedDates.add(day.date)
                continue
            }
            if (healthConnect.writeDay(mirrorTotals(day))) {
                succeeded++
                // Today stays unsynced: it is still moving.
                if (day.date != today.date) syncedDates.add(day.date)
            } else {
                failed++
            }
        }
        repository.markSynced(SyncTarget.HEALTH_CONNECT, syncedDates)
        state.lastHealthSyncAt = System.currentTimeMillis()
        // Our own records just changed, so the cached origin split is stale.
        sourceCache.invalidate()

        // A write that failed against an available, permitted provider is worth
        // another attempt; nothing else here is.
        val result = syncResult(
            "health_connect", succeeded, failed, failed == 0, null,
            retryable = failed > 0, skipped = skipped
        )
        StepEventBus.emit(StepEventBus.Events.SYNC_COMPLETED, result)
        result
    }

    /**
     * Whether the resolved owner of a day is some other app. Cheap in the common
     * case: the policy check short-circuits before any Health Connect call.
     */
    private suspend fun isOwnedByExternalSource(date: String): Boolean {
        if (!shouldConsultHealthConnect()) return false
        return runCatching { resolveDay(date).usedExternal }.getOrDefault(false)
    }

    private fun syncResult(
        target: String,
        synced: Int,
        failed: Int,
        success: Boolean,
        error: String?,
        retryable: Boolean = false,
        skipped: Int = 0
    ): Map<String, Any?> = mapOf(
        "target" to target,
        "syncedRecords" to synced,
        "failedRecords" to failed,
        /** Days left to a wearable that already owns them. */
        "skippedRecords" to skipped,
        "success" to success,
        "error" to error,
        "retryable" to retryable
    )

    companion object {
        /**
         * The verification snapshot's shape. Bumped whenever a field is
         * removed, renamed or changes meaning; adding a field does not bump
         * it. First sent by 2.0, as 2. No 1.x release sent it: a 1.5
         * snapshot is recognisable by `integrity`, a 1.4 one by its absence,
         * and both are version 2 with fields missing - nothing was removed
         * or renamed between them.
         */
        const val SNAPSHOT_SCHEMA_VERSION = 2

        /** Config keys whose change is logged as an integrity event. */
        private val INTEGRITY_CONFIG_KEYS = listOf(
            "fraudDetectionEnabled", "fraudMode", "fraudMaxCadenceSpm", "fraudSteadyCadenceMinutes",
            "fraudMaxContinuousMinutes", "fraudMaxDailySteps", "fraudFlagWhileCharging",
            "fraudActivityRecognition", "gapRecovery", "gapRecoveryMaxSteps", "stepSource",
            "preferredStepSourcePackage", "healthConnectIgnoreManualEntries", "wearableTrust",
            "wearableAllowlist", "accelerometerFallback", "accelerometerThreshold",
            "motionSamplingEnabled"
        )

        /** Stands in for "this device" in [StepStateStore.lastResolvedSource]. */
        const val SELF_SOURCE_KEY = "__self__"

        /** Floor between sensor-triggered Health Connect refreshes of today. */
        private const val SOURCE_REFRESH_MIN_INTERVAL_MS = 60_000L

        /**
         * Health Connect reads are cross-process; a provider that is busy
         * migrating or being updated can block for a long time. Past this the
         * phone's own count is the answer, and the read is retried next time.
         */
        const val HC_READ_TIMEOUT_MS = 4_000L

        /**
         * The service stamps [StepStateStore.lastHeartbeatAt] this often even
         * when the user is still, so `heartbeatAgeMs` in `getTrackingHealth()`
         * says how long ago the service was last known to be alive.
         */
        const val HEARTBEAT_INTERVAL_MS = 60_000L

        @Volatile
        private var instance: StepTrackerCore? = null

        fun get(context: Context): StepTrackerCore =
            instance ?: synchronized(this) {
                instance ?: StepTrackerCore(context).also { instance = it }
            }
    }
}
