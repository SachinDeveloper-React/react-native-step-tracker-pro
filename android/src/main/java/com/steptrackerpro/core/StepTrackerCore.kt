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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * Process-wide singleton shared by the React module, the foreground service,
 * the boot receiver and the workers. Holding one instance is what keeps the
 * counter consistent when JS is dead but the service is alive.
 */
class StepTrackerCore private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * Background work - the saves, the closing of a day, a backfill - runs
     * here with no caller to hand a failure to. One that throws is logged:
     * uncaught, a database write on a full disk took the whole app down with
     * it, again at every save. A save writes the day's whole total, so the
     * next one makes up for one that failed.
     */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            android.util.Log.w(TAG, "Background work failed", error)
        }
    )

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
        readTypes = HealthConnectManager.ReadType.parse(configStore.get().healthConnectReadTypes)
        needsRecordingMethods = configStore.get().healthConnectIgnoreManualEntries
    }

    /** Per-minute buckets, the detector, the event log and suspect-step arithmetic. */
    val integrity = IntegrityMonitor(
        appContext, repository, scope, writeLane, ::config
    ) { date -> dayTotals(date).steps }

    /**
     * This device's steps per minute on their way to `mirror_minute`, for
     * Health Connect's per-minute records - only under
     * `healthConnectWriteGranularity: 'minute'`.
     */
    private val mirrorMinutes = MirrorMinuteBuffer()

    /**
     * Held while minutes go from [mirrorMinutes] to `mirror_minute`, so the
     * per-minute mirror can read today's count and today's minutes as of
     * one instant - see [liveTodayWithMinutes].
     */
    private val mirrorRowsLock = Mutex()

    val engine = StepCounterEngine(state, metrics).apply {
        onDayRollover = { closing, newDate -> handleRollover(closing, newDate) }
        onBackfill = { shares, reason -> handleBackfill(shares, reason) }
        onObserved = { from, to, steps, timed ->
            integrity.onObserved(from, to, steps, timed)
            if (writesMinutes()) mirrorMinutes.record(from, to, steps, timed)
        }
        onLate = { scheduleLateSteps() }
        gapRecovery = StepCounterEngine.GapRecovery.from(configStore.get().gapRecovery)
        gapRecoveryMaxSteps = configStore.get().gapRecoveryMaxSteps
    }

    /** A late-steps write is queued; one covers a whole batch. */
    private val lateStepsQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Held while late steps are added to their day and it is judged again:
     * two of those at once would race to store the day's verdict, and a
     * read of the day waits for one in flight.
     */
    private val lateStepsLock = Mutex()

    /**
     * The latest rollover's close of the day it ended: the final save and
     * verdict. A read of a day waits for it - see [awaitClosedDay].
     */
    @Volatile
    private var closingDay: Job? = null

    /**
     * Told when a day ends, whatever ended it: a sample, the service's
     * heartbeat, or a read - a sync job's, an app's. The service redraws its
     * notification from it; a day a sync job ended overnight otherwise left
     * the shade on the day before's count until the first step. Called under
     * the engine's lock, so it only posts.
     */
    @Volatile
    var onDayEnded: (() -> Unit)? = null

    init {
        // Exclude mode: a verdict that moved today's flagged total moved the
        // shown number too, and JS hears about it without waiting for a step.
        integrity.onLiveChanged = { emitLiveSteps() }
    }

    /** `stepsChanged` with the live count as shown, for a change no sensor sample carried. */
    private fun emitLiveSteps() {
        val live = engine.snapshot()
        val resolution = resolveFromCache(DayTotals(live.date, live.steps, live.distance, live.calories))
        StepEventBus.emit(
            StepEventBus.Events.STEPS_CHANGED,
            com.steptrackerpro.util.Bridge.snapshotMap(displaySnapshot(live, resolution), resolution)
        )
    }

    // The in-memory intervals below - the event throttle, the source refresh,
    // the source cache - are measured on the uptime clock: a wall clock the
    // user sets back makes "now minus then" negative, and a throttle keyed
    // on it would hold for as many hours as the clock moved. They start far
    // in the past so the first one is never held back.
    private val lastEventAt = AtomicLong(NEVER)
    private val trailingEventQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lastSourceRefreshAt = AtomicLong(NEVER)
    private val syncMutex = Mutex()
    private val sourceCache = SourceCache()

    /** The last look at the changes feed behind [sourceCache] - see [confirmCachedSources]. */
    private val feedCheckedAt = AtomicLong(NEVER)
    private val feedMutex = Mutex()

    init {
        // Sources cached under the old grants would carry the old answer -
        // no distance after the user allowed it, say - for up to ten minutes
        // on a past day. Dropped the moment a fresh read sees a change.
        healthConnect.onGrantsChanged = { sourceCache.invalidate() }
        // Health Connect refusing a call for quota is the app's to know about
        // - it is also every other Health Connect call the app makes, through
        // any library - and nothing else would tell it: the reads that follow
        // fall back quietly. Once per refusal; the calls held back after it
        // say nothing.
        healthConnect.onRateLimited = { refusal ->
            StepEventBus.emit(
                StepEventBus.Events.ERROR,
                mapOf(
                    "code" to "E_HEALTH_CONNECT_RATE_LIMITED",
                    "message" to (refusal.message ?: "Health Connect rate limit reached"),
                    "retryAfterMs" to refusal.retryAfterMs,
                    "quota" to refusal.category.name.lowercase()
                )
            )
        }
        // Owed by a process that died before it could add them. Last, so
        // everything the write touches is in place when it runs.
        if (state.lateSteps.isNotEmpty()) scheduleLateSteps(delayMs = 0L)
    }

    fun config(): StepTrackerConfig = configStore.get()

    fun updateConfig(config: StepTrackerConfig): StepTrackerConfig {
        val previous = configStore.get()
        val saved = configStore.save(config)
        metrics.config = saved
        healthConnect.readActiveCalories = saved.healthConnectReadActiveCalories
        healthConnect.readTypes = HealthConnectManager.ReadType.parse(saved.healthConnectReadTypes)
        healthConnect.needsRecordingMethods = saved.healthConnectIgnoreManualEntries
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
            // Cached sources were read without (or with) active calories,
            // or with a different set of types.
            previous.healthConnectReadActiveCalories != saved.healthConnectReadActiveCalories ||
            previous.healthConnectReadTypes != saved.healthConnectReadTypes
        ) {
            state.clearContinuity()
            sourceCache.invalidate()
        }
        // What `getSyncStatus().remote.authFailed` measures against: new
        // credentials clear a refusal until they are refused in turn.
        if (previous.remoteSyncUrl != saved.remoteSyncUrl ||
            previous.remoteSyncHeaders != saved.remoteSyncHeaders ||
            previous.remoteSyncAuth != saved.remoteSyncAuth
        ) {
            state.recordRemoteCredentialsChanged()
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
        // Background work follows config, except after stopTracking(): that
        // cancels it, and initialize() - which runs this on every launch -
        // must not bring it back. A tracker that never started keeps it, so
        // an app that only reads Health Connect still gets its hourly upload.
        if (!state.stoppedByUser) SyncScheduler.schedule(appContext, saved)
        return saved
    }

    /** Config keys whose change moves a count or a verdict, for the event log. */
    private fun integrityRelevantChanges(previous: StepTrackerConfig, saved: StepTrackerConfig): List<String> {
        val before = previous.toJson()
        val after = saved.toJson()
        return INTEGRITY_CONFIG_KEYS.filter { key -> before.opt(key)?.toString() != after.opt(key)?.toString() }
    }

    fun isInitialized(): Boolean = configStore.isInitialized()

    /**
     * The Health Connect grants this app needs, as configured and fitted to
     * what its manifest declares - see
     * [HealthConnectManager.PermissionScope.forManifest]. An app that
     * declares no `WRITE_STEPS` is not asked to write however the default
     * reads, and undeclared distance or calories are not asked for.
     */
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
            activeCalories = config.healthConnectReadActiveCalories,
            readTypes = HealthConnectManager.ReadType.parse(config.healthConnectReadTypes),
            readTypesExplicit = config.healthConnectReadTypesExplicit,
            writeExplicit = config.healthConnectWriteExplicit,
            vitals = com.steptrackerpro.health.VitalType.parseLenient(config.healthConnectReadVitals),
            backgroundReadAvailable = healthConnect.backgroundReadAvailable,
            historyReadAvailable = healthConnect.historyReadAvailable
        ).forManifest(com.steptrackerpro.util.PermissionHelper.declaredPermissions(appContext))
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
            // The heartbeat is stored across process deaths, so it is a wall
            // time; after the clock is set back it can lie in the future, and
            // an age from that is unknown rather than negative.
            "heartbeatAgeMs" to (if (beat > 0L && now >= beat) now - beat else -1L),
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

        val now = android.os.SystemClock.elapsedRealtime()
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
                lastEventAt.set(android.os.SystemClock.elapsedRealtime())
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
                runCatching { flushMirrorMinutes() }
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
        scope.launch(writeLane) {
            shares.forEach { (date, steps) -> addToPastDay(date, steps, reason) }
            sourceCache.invalidate()
        }
    }

    /**
     * Adds [steps] to a stored past day, unless it is past retention, and
     * tells JS once the write has committed. Recovered steps grow the day's
     * recovered share; late ones were watched being taken, and do not. On
     * the write lane.
     *
     * @return whether the day was written.
     */
    private suspend fun addToPastDay(
        date: String,
        steps: Int,
        reason: StepCounterEngine.BackfillReason
    ): Boolean {
        val cutoff = DateKeys.minusDays(DateKeys.today(), config().historyRetentionDays)
        if (date < cutoff || steps <= 0) return false
        val stored = repository.addToDay(
            date, steps, metrics, recovered = reason != StepCounterEngine.BackfillReason.LATE
        ) ?: return false
        StepEventBus.emit(
            StepEventBus.Events.HISTORY_BACKFILLED,
            mapOf(
                "date" to date,
                "addedSteps" to steps,
                "totalSteps" to stored.steps,
                "reason" to reason.jsValue
            )
        )
        return true
    }

    /**
     * Queues the write of what late samples owed to closed days. A batch the
     * hub held arrives in one burst, so the write waits a moment and takes
     * all of it: one addition, one event and one new verdict per day, not
     * one per sample. The debt itself is already stored with the counter
     * state, so the wait costs nothing if the process dies meanwhile.
     */
    private fun scheduleLateSteps(delayMs: Long = LATE_STEPS_DELAY_MS) {
        if (!lateStepsQueued.compareAndSet(false, true)) return
        scope.launch {
            delay(delayMs)
            lateStepsQueued.set(false)
            runCatching { creditLateSteps() }
        }
    }

    /**
     * Adds what late samples owed to the days they were taken on, and judges
     * each such day again: its late minutes are in now, and so are the steps
     * they describe. Never alongside the close of such a day - its own save
     * and verdict - nor alongside another credit: two verdicts for one day
     * written at once leave whichever landed last. A write that fails leaves
     * its steps owed, for the next credit. Runs on the write lane, marked as
     * closing work so the reads it makes never wait for it.
     */
    private suspend fun creditLateSteps() {
        // Already inside closing work, which this must never wait for.
        if (currentCoroutineContext()[ClosingDay] != null) return
        closingDay?.join()
        if (state.lateSteps.isEmpty() && !lateStepsLock.isLocked) return
        withContext(writeLane + ClosingDay) {
            lateStepsLock.withLock {
                val owed = engine.takeLateSteps()
                if (owed.isEmpty()) return@withLock
                val unwritten = HashMap<String, Int>()
                for ((date, steps) in owed.toSortedMap()) {
                    val written = try {
                        addToPastDay(date, steps, StepCounterEngine.BackfillReason.LATE)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        unwritten[date] = steps
                        false
                    }
                    if (written) runCatching { integrity.onLateSteps(date) }
                }
                if (unwritten.isNotEmpty()) engine.oweLateSteps(unwritten)
                sourceCache.invalidate()
            }
        }
    }

    /**
     * Closes the day that ended - its final save and verdict, then
     * `dayChanged` - which is what a read of that day waits for (see
     * [awaitClosedDay]). Retention and the Health Connect pass follow on
     * their own, without holding those reads up.
     */
    private fun handleRollover(closing: DayTotals, newDate: String) {
        state.clearContinuity()
        sourceCache.invalidate()
        val closed = scope.launch(writeLane + ClosingDay) {
            repository.saveDay(closing)
            runCatching { flushMirrorMinutes() }
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
            StepEventBus.emit(
                StepEventBus.Events.DAY_CHANGED,
                mapOf(
                    "previousDate" to closing.date,
                    "currentDate" to newDate,
                    "previousDaySteps" to closing.steps
                )
            )
        }
        closingDay = closed
        onDayEnded?.invoke()
        scope.launch(writeLane) { runCatching { prune(config().historyRetentionDays) } }
        scope.launch {
            closed.join()
            if (config().healthConnectEnabled) runCatching { syncHealthConnect() }
        }
    }

    /**
     * Ends the day if it is due, and waits for the latest close to finish.
     * Without it `getYesterdaySteps()` or a signed snapshot of yesterday, at
     * the first open of the morning, could read the stored day before the
     * rollover's save of its last steps had landed. The close reads days
     * too, and must not wait for itself.
     */
    private suspend fun awaitClosedDay() {
        rollDayIfDue()
        if (currentCoroutineContext()[ClosingDay] != null) return
        closingDay?.join()
        // Steps a late batch owes a closed day are part of it too: added now
        // if they are still waiting out their delay, or waited for if they
        // are being added.
        creditLateSteps()
    }

    /** Marks a rollover's close of its day - see [awaitClosedDay]. */
    private object ClosingDay : CoroutineContext.Element, CoroutineContext.Key<ClosingDay> {
        override val key: CoroutineContext.Key<*> get() = this
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
        // So do the minutes kept for Health Connect's per-minute records -
        // all but what Health Connect holds for each, which the next pass
        // compares against to delete it.
        mirrorRowsLock.withLock {
            mirrorMinutes.clear()
            runCatching { repository.clearMirrorSteps(today.date) }
        }
        // What was mirrored before the reset goes too. Nothing would replace
        // it otherwise: a day with no steps has nothing to write, so every
        // other app reading Health Connect kept the old count until the
        // first step - and the sync after it - wrote over it. In order with
        // the syncs, and only while the day is still empty: once a step has
        // come, the next sync writes over the old count anyway.
        scope.launch {
            runCatching {
                syncMutex.withLock {
                    if (liveToday().steps == 0 && healthMirrorInUse()) deleteMirrored(listOf(today.date))
                }
            }
        }
    }

    suspend fun clearHistory() = withContext(writeLane) {
        // Nothing is owed to days that are gone - nor added back to them by
        // a credit in flight.
        lateStepsLock.withLock {
            repository.clear()
            engine.takeLateSteps()
        }
        integrity.onHistoryCleared()
    }

    suspend fun pruneHistory(retentionDays: Int): Int = withContext(writeLane) { prune(retentionDays) }

    /**
     * Retention, sparing days a sync target that is actually in use has not
     * accepted. A target that can never accept - writes never granted -
     * does not hold history past the window.
     */
    suspend fun prune(retentionDays: Int): Int =
        repository.prune(retentionDays, remoteSyncInUse(), healthMirrorInUse())

    /**
     * Days a sync target in use has not accepted yet, as `getPendingSyncCount()`
     * reports them. A target not in use has nothing pending: without a URL
     * nothing is ever uploaded, and with writes off nothing is ever mirrored.
     */
    suspend fun pendingSyncCount(): Int =
        repository.countPending(remoteSyncInUse(), healthMirrorInUse(), DateKeys.today())

    /** Uploads have somewhere to go. */
    private fun remoteSyncInUse(): Boolean = !config().remoteSyncUrl.isNullOrEmpty()

    /** Days can be mirrored into Health Connect: writes on in config, a provider, and the grant. */
    private suspend fun healthMirrorInUse(): Boolean {
        val config = config()
        return config.healthConnectEnabled && config.healthConnectWriteEnabled &&
            healthConnect.availability() == HealthConnectManager.Availability.AVAILABLE &&
            healthConnect.canWriteSteps()
    }

    /** Writes whatever is in memory to the database. Called before shutdown. */
    fun flush(): DayTotals {
        val totals = engine.consumeCommit()
        scope.launch(writeLane) {
            repository.saveDay(totals)
            runCatching { integrity.flush() }
            runCatching { flushMirrorMinutes() }
            // Late steps still waiting out their delay go now, while the
            // process is sure to be here.
            runCatching { creditLateSteps() }
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

    /**
     * Ends the day when the date has moved on and no sample has come along to
     * do it - a phone lying still across midnight. Until then the
     * notification and every read of today would still be yesterday's total
     * under yesterday's date, and `dayChanged`, the closing day's final save
     * and its verdict would wait for the first step of the morning. The
     * service's heartbeat calls this, and so does every read of a day.
     *
     * @return true when the day rolled over.
     */
    fun rollDayIfDue(): Boolean {
        if (!engine.rollIfNeeded()) return false
        emitLiveSteps()
        return true
    }

    /** Today's live totals, straight from the counter rather than the database. */
    fun liveToday(): DayTotals {
        rollDayIfDue()
        return totalsOf(engine.snapshot())
    }

    private fun totalsOf(snapshot: StepSnapshot): DayTotals = DayTotals(
        snapshot.date,
        snapshot.steps,
        snapshot.distance,
        snapshot.calories,
        false,
        recoveredSteps = snapshot.recoveredSteps
    )

    /**
     * Today's live totals and its stored per-minute mirror rows, as of one
     * instant: the buffer is drained under the engine's lock in the same
     * breath as the count is read, and no other flush lands until the rows
     * are read back. Read apart, a step taken between the two would sit in
     * one and not the other, and the record of unplaced steps would come
     * and go with every walk.
     */
    private suspend fun liveTodayWithMinutes(): Pair<DayTotals, List<MinuteWritePlan.Minute>> {
        rollDayIfDue()
        return mirrorRowsLock.withLock {
            val (snapshot, drained) = synchronized(engine) { engine.snapshot() to mirrorMinutes.drain() }
            runCatching { repository.addMirrorMinutes(drained) }
            totalsOf(snapshot) to repository.mirrorMinutes(snapshot.date)
        }
    }

    suspend fun stats(start: String, end: String, goal: Int?): RangeStats {
        awaitClosedDay()
        val today = DateKeys.today()
        val live = if (today in start..end) liveToday() else null
        return repository.stats(start, end, goal, live)
    }

    /** Today live; a past day as stored, once any close of it has landed. */
    suspend fun dayTotals(date: String): DayTotals {
        if (date == DateKeys.today()) return liveToday()
        awaitClosedDay()
        return repository.getDay(date)
    }

    /** Stored rows for a range, as they are, with each day's suspect steps stamped on. */
    suspend fun history(start: String, end: String): List<DayTotals> {
        awaitClosedDay()
        val days = repository.getRange(start, end)
        val suspects = integrity.suspects(days)
        if (suspects.isEmpty()) return days
        return days.map { it.copy(suspectSteps = suspects[it.date] ?: 0) }
    }

    /** [IntegrityMonitor.report], once any close of the day - its final verdict - has landed. */
    suspend fun integrityReport(date: String): Map<String, Any?> {
        awaitClosedDay()
        return integrity.report(date)
    }

    /** Minutes with steps between the two days, inclusive, those still in memory included. */
    suspend fun stepMinutes(start: String, end: String): List<MinuteSample> {
        awaitClosedDay()
        withContext(writeLane) { integrity.flush() }
        return repository.minutes(start, end)
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
        // Steps alone: a user who unticked distance or calories on the sheet
        // still gets their watch's steps, with those derived instead. And
        // only when a read is allowed now: in the background without the
        // background grant it would be refused, and a refusal is not an
        // answer.
        return healthConnect.canReadSteps() && healthConnect.canReadNow()
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
    suspend fun unresolvedSources(date: String, fresh: Boolean = false): SourceList {
        if (!shouldConsultHealthConnect()) return SourceList(emptyList(), HealthConnectRead.NOT_CONSULTED)
        // Fresh for evidence that gets signed: a cached list may be half a
        // minute old, and a hot-path timeout is too short for a busy day.
        // Evidence never falls back on an old answer when a read is refused
        // for quota: it says so, with no sources.
        val list = readSourcesForDay(
            date,
            timeoutMs = if (fresh) HC_RANGE_READ_TIMEOUT_MS else HC_READ_TIMEOUT_MS,
            useCache = !fresh,
            staleIfRefused = false
        )
        return list.copy(sources = stampTrust(list.sources))
    }

    /**
     * Reads each of [dates]' Health Connect origins into the cache, in as
     * few reads as cover them, each answered in full - so a loop that then
     * asks for each day on its own - a `full` upload's - is answered from
     * the cache instead of reading every day by itself. A day the reads do
     * not reach is read on its own when asked for, as before.
     */
    suspend fun prefetchSources(dates: Collection<String>) {
        if (dates.isEmpty() || !shouldConsultHealthConnect()) return
        val sorted = dates.sorted()
        runCatching {
            sourcesForRange(
                sorted.first(), sorted.last(), HC_RANGE_READ_TIMEOUT_MS,
                requireDetail = true, only = sorted.toSet()
            )
        }
    }

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
        nonce: String? = null,
        include: Set<SnapshotPart> = emptySet(),
        recordTypes: Set<HealthConnectManager.RecordType> = setOf(HealthConnectManager.RecordType.STEPS),
        /**
         * With [sign], return only the signature block: the snapshot then
         * travels once, as `signedPayload`, instead of as an object and again
         * as its JSON text.
         */
        signedOnly: Boolean = false
    ): Map<String, Any?> {
        val today = date == DateKeys.today()
        if (today) engine.reconcile()
        val device = dayTotals(date)
        val report = integrity.report(date)
        val sourceList = unresolvedSources(date, fresh = true)
        val sources = sourceList.sources
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
            // Whether `sources` is what Health Connect holds, or empty because
            // it was not asked, ran out of time or failed - never the same
            // thing as "no other apps", and signed along with it.
            "sourcesStatus" to sourceList.healthConnect,
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
        // The evidence behind the totals, when asked for. Inside the signed
        // body, so one signature - and one Play Integrity requestHash - covers
        // the minutes, the motion windows and the records a server scores,
        // not only the totals built from them.
        if (include.isNotEmpty()) {
            // Echoed like the nonce, so "asked for and empty" is told apart
            // from "never asked for".
            snapshot["include"] = SnapshotPart.entries.filter { it in include }.map { it.jsValue }
        }
        // Each list says whether it is being recorded, so an empty one reads
        // as "nothing happened" or "not recording" rather than either.
        if (SnapshotPart.MINUTES in include) {
            integrity.flush()
            snapshot["minutesStatus"] = if (config().fraudDetectionEnabled) "enabled" else "disabled"
            snapshot["minutes"] = repository.minutes(date, date).map { it.toMap() }
        }
        if (SnapshotPart.MOTION_WINDOWS in include) {
            snapshot["motionWindowsStatus"] = if (config().motionSamplingEnabled) "enabled" else "disabled"
            snapshot["motionWindows"] = motionWindows(date, date).map { it.toMap() }
        }
        if (SnapshotPart.HEALTH_CONNECT_RECORDS in include) {
            snapshot["healthConnectRecords"] = snapshotRecords(date, recordTypes)
        }
        if (nonce != null) snapshot["nonce"] = nonce
        if (!sign) return snapshot
        val (body, signature) = signed(snapshot, now)
        return if (signedOnly) signature else body + ("signature" to signature)
    }

    /**
     * One day's raw Health Connect records for a snapshot, and whether they
     * could be read. An explicit ask, so unlike `sources` it does not follow
     * the step-source policy - only the provider, the grants for [types] and
     * `healthConnectEnabled` decide. Never throws: a snapshot is still worth
     * signing without the records, and `status` says why they are missing.
     */
    private suspend fun snapshotRecords(
        date: String,
        types: Set<HealthConnectManager.RecordType>
    ): Map<String, Any?> {
        val names = types.map { it.jsValue }
        fun result(status: String, records: Any? = emptyList<Any>(), truncated: Any? = false) =
            linkedMapOf("status" to status, "recordTypes" to names, "records" to records, "truncated" to truncated)

        if (!config().healthConnectEnabled) return result("disabled")
        if (healthConnect.availability() != HealthConnectManager.Availability.AVAILABLE) return result("unavailable")
        // In the background that includes the background grant, without
        // which the read would be refused.
        if (!healthConnect.canReadRecords(types) || !healthConnect.canReadNow()) return result("not_granted")
        val start = DateKeys.startOfDayInstant(date)
        val end = minOf(DateKeys.endOfDayInstant(date), Instant.now())
        if (!end.isAfter(start)) return result("read")
        val read = try {
            withTimeoutOrNull(HC_RECORDS_TIMEOUT_MS) { healthConnect.readRecords(start, end, types) }
                ?: return result("timeout")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: com.steptrackerpro.health.HealthConnectRateLimitedException) {
            return result(HealthConnectRead.RATE_LIMITED)
        } catch (_: Exception) {
            return result("failed")
        }
        return result("read", read["records"], read["truncated"])
    }

    /**
     * The snapshot with a `signature` block over its exact JSON text. The
     * text itself travels as `signedPayload`, so a server verifies the
     * signature over those bytes and then parses them - it never has to
     * re-serialise anything the same way this side did.
     */
    private fun signed(
        snapshot: Map<String, Any?>,
        now: Long
    ): Pair<Map<String, Any?>, Map<String, Any?>> {
        val body = LinkedHashMap(snapshot).apply { put("signedAt", now) }
        val payload = JsonMaps.toJson(body)
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val signature = DeviceAttestation.sign(appContext, bytes) + mapOf(
            "signedPayload" to payload,
            "payloadSha256" to DeviceAttestation.sha256Hex(bytes)
        )
        return body to signature
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
     * Today's origin split goes stale thirty seconds after the last read, or
     * the changes feed's last word on it. The sensor path cannot fetch it,
     * but it can ask for a fetch: a walk with the app closed then keeps the
     * notification and `stepsChanged` in step with a watch that syncs
     * mid-walk, instead of waiting for the next screen open. Throttled to
     * once a minute so it never becomes a poll, and when nothing moved the
     * fetch is one look at the feed rather than a read.
     */
    private fun refreshTodaySourcesIfStale() {
        val config = config()
        if (!config.healthConnectEnabled || !config.healthConnectReadEnabled) return
        if (sourcePolicy() == StepSourcePolicy.DEVICE) return
        val today = DateKeys.today()
        if (sourceCache.get(today) != null) return
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastSourceRefreshAt.get()
        if (now - last < SOURCE_REFRESH_MIN_INTERVAL_MS) return
        if (!lastSourceRefreshAt.compareAndSet(last, now)) return
        scope.launch {
            // Nobody is waiting for this one, so it stands down once this
            // process has used a good share of the read quota - leaving the
            // rest for the reads someone is waiting for. The notification
            // keeps the last answer and the day's lead meanwhile.
            if (!healthConnect.hasReadHeadroom()) return@launch
            runCatching { resolveToday() }
        }
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
     * The per-origin data for the window comes from the cache where the
     * changes feed still vouches for a day, and the rest is fetched in as few
     * reads as cover it rather than per day: a month of raw step records is a
     * single paged read, thirty of them is thirty. A stats screen refreshing
     * while its user walks re-reads today, not the month.
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
            if (baseline == null) {
                return (if (base === stored) stored else recomputeStats(base, base.days, goal))
                    .copy(healthConnect = HealthConnectRead.NOT_CONSULTED)
            }
            val today = DateKeys.today()
            val days = base.days.map { day ->
                if (day.date != today) return@map day
                val raw = StepSourceResolver.resolve(
                    StepSourcePolicy.DEVICE, day, emptyList(), null, metrics
                )
                StepContinuity.apply(raw, baseline, metrics).totals.copy(suspectSteps = day.suspectSteps)
            }
            return recomputeStats(base, days, goal).copy(healthConnect = HealthConnectRead.NOT_CONSULTED)
        }

        // A month of a busy watch is tens of thousands of records, so a range
        // gets longer than a single day's read. Running out of time is said,
        // not passed off as a range with no watch in it; the next call reads
        // again.
        val range = sourcesForRange(start, end, HC_RANGE_READ_TIMEOUT_MS)
        val read = range.healthConnect
        val byDate = range.byDate

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
        return recomputeStats(base, resolved, goal).copy(healthConnect = read)
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
    suspend fun listSources(start: String, end: String): SourceList {
        val config = config()
        if (!(config.healthConnectEnabled && config.healthConnectReadEnabled &&
                healthConnect.availability() == HealthConnectManager.Availability.AVAILABLE &&
                healthConnect.canReadSteps() && healthConnect.canReadNow())
        ) {
            return SourceList(emptyList(), HealthConnectRead.NOT_CONSULTED)
        }
        val range = sourcesForRange(start, end, HC_RANGE_READ_TIMEOUT_MS)
        return SourceList(stampTrust(healthConnect.mergeSources(range.byDate.values)), range.healthConnect)
    }

    /** [listSources]' answer: the sources, and how the read went - one of [HealthConnectRead]. */
    data class SourceList(val sources: List<StepSource>, val healthConnect: String)

    /**
     * Each day's Health Connect origins over a range, and how the reading
     * went. Under [HealthConnectRead.TIMED_OUT] and [HealthConnectRead.FAILED]
     * there are none, as a range's callers have always promised; under
     * [HealthConnectRead.RATE_LIMITED], the days the cache still holds.
     */
    private data class RangeSources(val byDate: Map<String, List<StepSource>>, val healthConnect: String)

    /**
     * Each day from [start] to [end] - or only [only]'s, when given - from
     * the cache where it still vouches for the day (see [confirmCachedSources]),
     * and the rest from as few reads as cover them: each run of days the
     * cache could not answer is one read, so a stats screen that refreshes
     * as its user walks re-reads today alone. With [requireDetail], only days
     * answered in full count as cached, and a run is read at most
     * [HealthConnectManager.RAW_READ_MAX_DAYS] at a time so that it is
     * answered in full too. Every day read is cached for the next caller.
     */
    private suspend fun sourcesForRange(
        start: String,
        end: String,
        timeoutMs: Long,
        requireDetail: Boolean = false,
        only: Set<String>? = null
    ): RangeSources {
        val today = DateKeys.today()
        val dates = DateKeys.rangeOf(start, if (end > today) today else end)
            .filter { only == null || it in only }
        if (dates.isEmpty()) return RangeSources(emptyMap(), HealthConnectRead.READ)
        confirmCachedSources()
        val byDate = HashMap<String, List<StepSource>>()
        val missing = ArrayList<String>()
        for (date in dates) {
            val cached = sourceCache.get(date, requireDetail)
            if (cached != null) byDate[date] = cached else missing += date
        }
        // On the uptime clock, like the timeout itself: a clock the user
        // moves mid-read cannot stretch or shrink what the refill is given.
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        val maxRun = if (requireDetail) HealthConnectManager.RAW_READ_MAX_DAYS.toInt() else Int.MAX_VALUE
        var read = HealthConnectRead.READ
        for (run in HealthConnectManager.runsOf(missing, maxRun)) {
            val left = deadline - android.os.SystemClock.elapsedRealtime()
            if (left <= 0L) {
                read = HealthConnectRead.TIMED_OUT
                break
            }
            val runStart = DateKeys.startOfDayInstant(run.first())
            val runEnd = minOf(DateKeys.endOfDayInstant(run.last()), Instant.now())
            if (!runEnd.isAfter(runStart)) {
                run.forEach { byDate[it] = emptyList() }
                continue
            }
            try {
                val days = withTimeoutOrNull(left) {
                    healthConnect.readSourcesByDay(runStart, runEnd, coverageStartForToday(), deadline = deadline)
                }
                if (days == null) {
                    read = HealthConnectRead.TIMED_OUT
                    break
                }
                for (date in run) {
                    val sources = days.byDate[date].orEmpty()
                    byDate[date] = sources
                    sourceCache.put(date, sources, detailed = date in days.detailed)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: com.steptrackerpro.health.HealthConnectRateLimitedException) {
                read = HealthConnectRead.RATE_LIMITED
                break
            } catch (_: Exception) {
                read = HealthConnectRead.FAILED
                break
            }
        }
        return when (read) {
            HealthConnectRead.READ -> RangeSources(byDate, read)
            // An old answer beats none while Health Connect will not be read.
            HealthConnectRead.RATE_LIMITED -> {
                for (date in dates) {
                    if (date !in byDate) sourceCache.getStale(date, requireDetail)?.let { byDate[date] = it }
                }
                RangeSources(byDate, read)
            }
            else -> RangeSources(emptyMap(), read)
        }
    }

    /**
     * Brings the source cache up to date with Health Connect's changes feed:
     * a day whose records moved since the last look is dropped, and every
     * other day is vouched for again - one call where re-reading even one
     * quiet day costs a page per record type. At most every
     * [FEED_CHECK_INTERVAL_MS]. The first look of a process takes the
     * feed's token before anything is cached, so the answers read after it
     * can be vouched for from then on; a look that had to start the token
     * over vouches for nothing. When the feed cannot be read, entries expire
     * as they always have.
     */
    private suspend fun confirmCachedSources() {
        if (android.os.SystemClock.elapsedRealtime() - feedCheckedAt.get() < FEED_CHECK_INTERVAL_MS) return
        feedMutex.withLock {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            if (startedAt - feedCheckedAt.get() < FEED_CHECK_INTERVAL_MS) return
            val changed = try {
                withTimeoutOrNull(HC_READ_TIMEOUT_MS) { healthConnect.changedSinceLastLook() }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            feedCheckedAt.set(android.os.SystemClock.elapsedRealtime())
            if (changed != null) sourceCache.confirm(changed, startedAt)
        }
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

    private suspend fun sourcesForDay(date: String): List<StepSource> =
        readSourcesForDay(date, HC_READ_TIMEOUT_MS, useCache = true, staleIfRefused = true).sources

    /**
     * One day's origins and how the read went. Only a finished read is
     * cached; a timeout or a failure is tried again next time. A caller's
     * own cancellation is passed on rather than read as a failed read.
     *
     * @param staleIfRefused when Health Connect refuses the read for quota,
     *   answer with the day's last read however old, for a number on screen
     *   - never for evidence, which says it was refused instead.
     */
    private suspend fun readSourcesForDay(
        date: String,
        timeoutMs: Long,
        useCache: Boolean,
        staleIfRefused: Boolean = false
    ): SourceList {
        if (useCache) {
            confirmCachedSources()
            sourceCache.get(date)?.let { return SourceList(it, HealthConnectRead.READ) }
        }
        val end = minOf(DateKeys.endOfDayInstant(date), Instant.now())
        val start = DateKeys.startOfDayInstant(date)
        if (!end.isAfter(start)) return SourceList(emptyList(), HealthConnectRead.READ)
        val coverage = if (date == DateKeys.today()) coverageStartForToday() else 0L
        return try {
            val day = withTimeoutOrNull(timeoutMs) {
                healthConnect.readSourcesByDay(start, end, coverage)
            } ?: return SourceList(emptyList(), HealthConnectRead.TIMED_OUT)
            val sources = day.byDate[date].orEmpty()
            sourceCache.put(date, sources, detailed = date in day.detailed)
            SourceList(sources, HealthConnectRead.READ)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: com.steptrackerpro.health.HealthConnectRateLimitedException) {
            val stale = if (staleIfRefused) sourceCache.getStale(date, requireDetail = true) else null
            SourceList(stale.orEmpty(), HealthConnectRead.RATE_LIMITED)
        } catch (_: Exception) {
            SourceList(emptyList(), HealthConnectRead.FAILED)
        }
    }

    /**
     * Each day's Health Connect origins, as last read. Health Connect reads
     * are cross-process IPC plus a database query, they count against its
     * rate limits, and `getTodaySteps()` is the call an app makes on every
     * screen focus. Serving the origin split from memory keeps that cheap;
     * the device's own count inside the resolution is always live
     * regardless, because it comes from the engine rather than from here.
     *
     * An entry is good for [TODAY_TTL_MS] (today) or [PAST_TTL_MS] (a past
     * day) after it was read - or after the changes feed last vouched that
     * nothing moved on that day, see [confirmCachedSources] - and never more
     * than [TODAY_MAX_AGE_MS] / [PAST_MAX_AGE_MS] after it was read, in case
     * the feed missed something. Expired entries are kept, for a read Health
     * Connect refuses for quota to fall back on. On the uptime clock.
     */
    private class SourceCache {
        private class Entry(
            val readAt: Long,
            var confirmedAt: Long,
            val sources: List<StepSource>,
            /** Read in full - see [HealthConnectManager.DaySources.detailed]. */
            val detailed: Boolean
        )

        private val entries = HashMap<String, Entry>()

        /**
         * The day's sources while they can still be vouched for. With
         * [requireDetail] - a day resolved on its own - only an entry read in
         * full: a day a long range filled from aggregates has no
         * recording-method split to take manual entries out of.
         */
        @Synchronized
        fun get(date: String, requireDetail: Boolean = true): List<StepSource>? {
            val entry = entries[date] ?: return null
            if (requireDetail && !entry.detailed) return null
            return entry.sources.takeIf { fresh(date, entry, android.os.SystemClock.elapsedRealtime()) }
        }

        /** The day's sources however old - only for a read Health Connect refused for quota. */
        @Synchronized
        fun getStale(date: String, requireDetail: Boolean): List<StepSource>? =
            entries[date]?.takeIf { !requireDetail || it.detailed }?.sources

        @Synchronized
        fun put(date: String, sources: List<StepSource>, detailed: Boolean) {
            val now = android.os.SystemClock.elapsedRealtime()
            // A range's partial answer never displaces a full one still good.
            val current = entries[date]
            if (!detailed && current != null && current.detailed && fresh(date, current, now)) return
            entries[date] = Entry(now, now, sources, detailed)
            if (entries.size > MAX_ENTRIES) {
                entries.entries.sortedBy { it.value.readAt }
                    .take(entries.size - MAX_ENTRIES)
                    .forEach { entries.remove(it.key) }
            }
        }

        /**
         * Applies a look at the changes feed taken at [at]: the days it saw
         * move are dropped, and every other entry read before [at] is
         * vouched for as of then.
         */
        @Synchronized
        fun confirm(changed: HealthConnectManager.ChangedDays, at: Long) {
            if (changed.all) {
                entries.clear()
                return
            }
            changed.dates.forEach { entries.remove(it) }
            entries.values.forEach { if (it.confirmedAt < at) it.confirmedAt = at }
        }

        @Synchronized
        fun invalidate() = entries.clear()

        @Synchronized
        fun invalidate(dates: Collection<String>) = dates.forEach { entries.remove(it) }

        private fun fresh(date: String, entry: Entry, now: Long): Boolean {
            val today = date == DateKeys.today()
            val ttl = if (today) TODAY_TTL_MS else PAST_TTL_MS
            val maxAge = if (today) TODAY_MAX_AGE_MS else PAST_MAX_AGE_MS
            return now - entry.confirmedAt <= ttl && now - entry.readAt <= maxAge
        }

        private companion object {
            const val TODAY_TTL_MS = 30_000L
            const val PAST_TTL_MS = 600_000L
            const val TODAY_MAX_AGE_MS = 60 * 60_000L
            const val PAST_MAX_AGE_MS = 6 * 60 * 60_000L

            /** A year of days and then some: a yearly chart keeps its days. */
            const val MAX_ENTRIES = 400
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
        // even though every call this method makes was permitted. And only
        // steps: distance and calories are written when granted, skipped when
        // the user unticked them.
        if (!healthConnect.canWriteSteps()) {
            return@withLock syncResult(
                "health_connect", 0, 0, false, "Health Connect permission to write steps not granted"
            )
        }

        // Today is written every time so the mirror stays close to live; older
        // days are only written while they are still marked unsynced.
        val today = liveToday()
        repository.saveDay(today)
        val pending = repository.unsynced(SyncTarget.HEALTH_CONNECT, limit = 100)
        // Today first, so its live totals win over the row just written for it.
        val targets = (listOf(today) + pending).distinctBy { it.date }

        // A day a wearable already owns must not be mirrored from here.
        // Health Connect keeps origins separate, so writing this phone's
        // parallel count of the same walk leaves every other app reading
        // Health Connect with both copies of it.
        val owners = externalOwners(targets, today.date)
        var skipped = 0
        var deferred = 0
        val done = ArrayList<DayTotals>(targets.size)
        val rows = HashMap<String, DayTotals>()
        val mirrors = ArrayList<DayTotals>()
        for (day in targets) {
            when (owners[day.date]) {
                // Health Connect would not say, for quota: neither written nor
                // skipped, but left for the next pass.
                null -> {
                    deferred++
                    continue
                }
                true -> {
                    skipped++
                    // Marked done rather than left pending: nothing about this day
                    // will ever make it writable, and leaving it queued would have
                    // the worker retry it for as long as it stays in retention.
                    if (day.date != today.date) done.add(day)
                    continue
                }
                false -> Unit
            }
            rows[day.date] = day
            mirrors += mirrorTotals(day)
        }
        val pass = mirror(rows, mirrors, config)
        val succeeded = pass.written.size
        val failed = pass.failed.size + deferred
        val rateLimited = pass.rateLimited || deferred > 0
        // Today stays unsynced: it is still moving.
        pass.written.forEach { date -> if (date != today.date) rows[date]?.let { done.add(it) } }
        // Only days still holding the count this pass read: one a backfill
        // grew meanwhile stays queued, and the next pass writes its new total.
        repository.markSynced(SyncTarget.HEALTH_CONNECT, done)
        state.lastHealthSyncAt = System.currentTimeMillis()
        // Our own records just changed on these days, so their cached origin
        // split is stale. Only these: the rest of what is cached still holds.
        sourceCache.invalidate(pass.changed)

        // A write that failed against an available, permitted provider is worth
        // another attempt; nothing else here is.
        val result = syncResult(
            "health_connect", succeeded, failed, failed == 0,
            if (rateLimited) RATE_LIMITED_MESSAGE else null,
            retryable = failed > 0, skipped = skipped, rateLimited = rateLimited
        )
        StepEventBus.emit(StepEventBus.Events.SYNC_COMPLETED, result)
        result
    }

    /** How one pass of mirroring went - see [mirror]. */
    private data class MirrorPass(
        /** Days everything was written for - including days with nothing to write. */
        val written: Set<String>,
        /** Days some of whose write did not land; worth another attempt. */
        val failed: Set<String>,
        /** Days whose records in Health Connect changed, so their cached origin split is stale. */
        val changed: Set<String>,
        /** Health Connect refused a call for quota. */
        val rateLimited: Boolean
    )

    /**
     * Mirrors [mirrors] - each [rows] day's totals as they go into Health
     * Connect - as one record per day, or per minute under
     * `healthConnectWriteGranularity: 'minute'`. Under the sync lock.
     */
    private suspend fun mirror(
        rows: Map<String, DayTotals>,
        mirrors: List<DayTotals>,
        config: StepTrackerConfig = config()
    ): MirrorPass =
        if (config.healthConnectWriteGranularity == WriteGranularity.MINUTE) {
            mirrorMinuteDays(rows, mirrors)
        } else {
            mirrorDays(rows, mirrors)
        }

    /** Day mode: one record per day and type, every pending day in one insert. */
    private suspend fun mirrorDays(rows: Map<String, DayTotals>, mirrors: List<DayTotals>): MirrorPass {
        // Every step taken out under exclude mode: there is nothing to
        // write, and whatever was written before - flagged steps included -
        // has to go, or other apps keep reading it.
        val emptied = mirrors.filter { it.steps <= 0 && (rows[it.date]?.steps ?: 0) > 0 }.map { it.date }
        val writable = mirrors.filter { it.date !in emptied }
        // A day minute mode wrote keeps no minute record under the day
        // record about to cover it: Health Connect counts only one of two
        // overlapping records from one app.
        val cleanup = clearMinuteRecords(writable.map { it.date })
        // One insert for every day there is to write, rather than one a day.
        val outcome = healthConnect.writeDays(writable.filter { it.date !in cleanup.blocked })
        outcome.written.maxOrNull()?.let { state.noteDayWrites(it) }
        val written = LinkedHashSet(outcome.written)
        val failed = LinkedHashSet(outcome.failed + cleanup.blocked)
        var rateLimited = outcome.rateLimited || cleanup.rateLimited
        if (emptied.isNotEmpty() && !rateLimited) {
            try {
                deleteMirrored(emptied)
                written += emptied
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: com.steptrackerpro.health.HealthConnectRateLimitedException) {
                rateLimited = true
                failed += emptied
            } catch (_: Exception) {
                failed += emptied
            }
        } else {
            failed += emptied
        }
        return MirrorPass(written, failed, written + cleanup.cleared, rateLimited)
    }

    /**
     * Minute mode: a record per minute with steps, and the day record for
     * the steps no minute holds - see [MinuteWritePlan] and
     * [HealthConnectManager.writeMinutes]. Only what moved since the last
     * pass is written, and a pass that fails part way leaves what landed
     * recorded, for the next one to carry on from.
     */
    private suspend fun mirrorMinuteDays(rows: Map<String, DayTotals>, mirrors: List<DayTotals>): MirrorPass {
        val today = DateKeys.today()
        // Today as of now, with its minutes as of the same instant; the
        // drain behind it stores every other day's pending minutes too.
        val live = if (mirrors.any { it.date == today }) {
            liveTodayWithMinutes().takeIf { (totals, _) -> totals.date == today }
        } else {
            runCatching { flushMirrorMinutes() }
            null
        }
        val now = Instant.now()
        val residuals = state.mirrorResiduals
        // Every minute's distance and calories are its share of the day's,
        // so a profile change - height, stride, weight - moves all of them:
        // today's are written again, not only the minutes that changed.
        val metricsNow = metricsSignature()
        val rewrite = metricsNow != state.mirrorMetrics
        // The last day day mode may have written a full-day record for; an
        // install from before 2.5 may have, up to today.
        val dayRecordsThrough = state.dayWritesThrough ?: today.also { state.noteDayWrites(it) }
        val idle = LinkedHashSet<String>()
        val days = mirrors.mapNotNull { listed ->
            val fresh = live?.takeIf { (totals, _) -> totals.date == listed.date }
            val mirror = fresh?.let { mirrorTotals(it.first) } ?: listed
            val own = fresh?.first ?: rows[listed.date]
            val minutes = fresh?.second ?: repository.mirrorMinutes(listed.date)
            val anyWritten = minutes.any { it.written > 0 }
            val stands = mirror.date in residuals
            val counted = (own?.steps ?: 0) > 0
            // No steps, and nothing of ours in Health Connect for the day: an
            // idle morning has nothing to write and nothing to delete.
            if (mirror.steps <= 0 && !counted && !anyWritten && !stands) {
                idle += mirror.date
                return@mapNotNull null
            }
            val dayEnd = minOf(DateKeys.endOfDayInstant(mirror.date), now)
            val plan = MinuteWritePlan.plan(
                minutes, mirror.steps, DateKeys.startOfDayMillis(mirror.date), dayEnd.toEpochMilli(), rewrite,
                observed = own?.let { it.steps - it.recoveredSteps } ?: mirror.steps
            )
            HealthConnectManager.MinuteDay(
                totals = mirror,
                dayEnd = dayEnd,
                upserts = plan.upserts,
                deletes = plan.deletes,
                residual = plan.residual,
                residualSpan = plan.residualSpan,
                // The day record goes once no step is left for it: it held
                // some last time, or - on a day's first pass - day mode may
                // have written the whole day into it.
                deleteResidual = plan.residual == 0 &&
                    (stands || (!anyWritten && counted && mirror.date <= dayRecordsThrough))
            )
        }
        val outcome = healthConnect.writeMinutes(days)
        withContext(writeLane) { repository.markMirrorWritten(outcome.written) }
        state.mirrorResiduals = residuals + outcome.residualWritten - outcome.residualDeleted
        state.noteMinuteWrites(outcome.inserted)
        if (rewrite && (today in outcome.complete || today in idle)) state.mirrorMetrics = metricsNow
        return MirrorPass(outcome.complete + idle, outcome.failed, outcome.changed, outcome.rateLimited)
    }

    /** Distance and calories per step as the profile stands - see [StepStateStore.mirrorMetrics]. */
    private fun metricsSignature(): String =
        String.format(java.util.Locale.ROOT, "%.6f|%.6f", metrics.distance(1), metrics.calories(1))

    /** What [clearMinuteRecords] did. */
    private data class MinuteCleanup(
        /** Days whose minute records are gone. */
        val cleared: Set<String> = emptySet(),
        /** Days whose minute records could not be deleted: not to be written as a day this time. */
        val blocked: Set<String> = emptySet(),
        /** Health Connect refused the delete for quota. */
        val rateLimited: Boolean = false
    )

    /**
     * Deletes the minute records [dates] still have in Health Connect from
     * minute mode, before a day record covers them.
     */
    private suspend fun clearMinuteRecords(dates: List<String>): MinuteCleanup {
        val spans = state.minuteWriteDays
        val candidates = dates.filterTo(HashSet()) { it in spans }
        if (candidates.isEmpty()) return MinuteCleanup()
        val written = repository.writtenMirrorMinutes(candidates.min(), candidates.max())
            .filterKeys { it in candidates }
        if (written.isEmpty()) return MinuteCleanup()
        return try {
            healthConnect.deleteOwnDays(emptyList(), minutes = written)
            withContext(writeLane) { repository.forgetMirrorWritten(written.keys) }
            state.mirrorResiduals = state.mirrorResiduals - written.keys
            state.forgetMinuteWrites(written.keys)
            MinuteCleanup(cleared = written.keys)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: com.steptrackerpro.health.HealthConnectRateLimitedException) {
            MinuteCleanup(blocked = written.keys, rateLimited = true)
        } catch (_: Exception) {
            MinuteCleanup(blocked = written.keys)
        }
    }

    /**
     * Deletes every record this package mirrored into Health Connect for
     * [dates] - day records and minute records - and forgets that the
     * minutes were written. A day minute mode wrote whose minutes the table
     * no longer remembers - pruned with history - has every minute of it
     * deleted by id. Throws as [HealthConnectManager.deleteOwnDays] does.
     */
    private suspend fun deleteMirrored(dates: Collection<String>): Set<HealthConnectManager.ReadType> {
        val sorted = dates.distinct().sorted()
        if (sorted.isEmpty()) return healthConnect.deleteOwnDays(emptyList())
        val wanted = sorted.toSet()
        val written = repository.writtenMirrorMinutes(sorted.first(), sorted.last()).filterKeys { it in wanted }
        val spans = state.minuteWriteDays
        val unremembered = if (spans.isEmpty()) {
            emptySet()
        } else {
            val remembered = repository.mirrorDates(sorted.first(), sorted.last())
            sorted.filterTo(LinkedHashSet()) { it in spans && it !in remembered }
        }
        val types = healthConnect.deleteOwnDays(sorted, written, unremembered)
        withContext(writeLane) { repository.forgetMirrorWritten(sorted) }
        state.mirrorResiduals = state.mirrorResiduals - wanted
        state.forgetMinuteWrites(wanted)
        return types
    }

    /**
     * `writeHealthConnectSteps(date)`: one day mirrored now, the way the
     * sync mirrors it - per minute in minute mode - and true once all of it
     * landed. In order with the syncs. A day a wearable owns is written all
     * the same: the caller asked for it.
     */
    suspend fun writeHealthConnectDay(date: String): Boolean = syncMutex.withLock {
        val day = dayTotals(date)
        val pass = mirror(mapOf(date to day), listOf(mirrorTotals(day)))
        sourceCache.invalidate(pass.changed)
        date in pass.written
    }

    /** Writes pending per-minute steps to `mirror_minute`. */
    private suspend fun flushMirrorMinutes() {
        mirrorRowsLock.withLock { repository.addMirrorMinutes(mirrorMinutes.drain()) }
    }

    /** Per-minute records are on: steps are kept a minute at a time for them. */
    private fun writesMinutes(): Boolean {
        val config = config()
        return config.healthConnectEnabled && config.healthConnectWriteEnabled &&
            config.healthConnectWriteGranularity == WriteGranularity.MINUTE
    }

    /**
     * Whether some other app owns each of [days] - its resolved answer is not
     * this device - or null for a day Health Connect refused to say about for
     * quota. Today goes through [resolveDay], where its continuity baseline
     * lives; every other day comes from one read per run of consecutive days
     * the cache cannot answer, where there used to be one read per day. Cheap in the common
     * case: the policy check short-circuits before any Health Connect call. A
     * read that fails or runs out of time leaves the days to this device, as
     * it always has.
     */
    private suspend fun externalOwners(days: List<DayTotals>, today: String): Map<String, Boolean?> {
        if (!shouldConsultHealthConnect()) return days.associate { it.date to false }
        val out = HashMap<String, Boolean?>()
        val past = days.filter { it.date != today }
        if (past.size < days.size) out[today] = runCatching { resolveDay(today).usedExternal }.getOrDefault(false)
        if (past.isEmpty()) return out
        val dates = past.mapTo(HashSet()) { it.date }
        val range = sourcesForRange(
            dates.min(), dates.max(), HC_RANGE_READ_TIMEOUT_MS, requireDetail = true, only = dates
        )
        val policy = sourcePolicy()
        val preferred = preferredSourcePackage()
        val reliable = coverageReliable()
        val ignoreManual = config().healthConnectIgnoreManualEntries
        val trust = wearableTrust()
        val allowlist = wearableAllowlist()
        for (day in past) {
            val sources = range.byDate[day.date]
            if (sources == null && range.healthConnect == HealthConnectRead.RATE_LIMITED) {
                out[day.date] = null
                continue
            }
            out[day.date] = runCatching {
                val suspect = integrity.suspect(day.date, day.steps)
                StepSourceResolver.resolve(
                    policy, adjusted(day, suspect, integrity.excluded(suspect)), sources.orEmpty(), preferred, metrics,
                    deviceCoverageReliable = reliable,
                    ignoreManualEntries = ignoreManual,
                    wearableTrust = trust,
                    wearableAllowlist = allowlist
                ).usedExternal
            }.getOrDefault(false)
        }
        return out
    }

    /**
     * Deletes the records this package mirrored into Health Connect for the
     * days from [start] to [end] - only its own, day and minute records, by
     * client record id - of each type whose write permission is granted,
     * which are returned. Nothing else local changes: a day already marked
     * synced is not written again, and today is, at the next sync, while
     * writes are on.
     */
    suspend fun deleteHealthConnectDays(start: String, end: String): Set<HealthConnectManager.ReadType> =
        syncMutex.withLock {
            val dates = DateKeys.rangeOf(start, end)
            deleteMirrored(dates).also { sourceCache.invalidate(dates) }
        }

    private fun syncResult(
        target: String,
        synced: Int,
        failed: Int,
        success: Boolean,
        error: String?,
        retryable: Boolean = false,
        skipped: Int = 0,
        rateLimited: Boolean = false
    ): Map<String, Any?> = mapOf(
        "target" to target,
        "syncedRecords" to synced,
        "failedRecords" to failed,
        /** Days left to a wearable that already owns them. */
        "skippedRecords" to skipped,
        "success" to success,
        "error" to error,
        "retryable" to retryable,
        /** Health Connect refused a call for quota; what it covered waits for the next pass. */
        "rateLimited" to rateLimited
    )

    companion object {
        private const val TAG = "StepTrackerPro"

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

        /** Floor between looks at the changes feed behind the source cache. */
        private const val FEED_CHECK_INTERVAL_MS = 15_000L

        /** The sync result's `error` when Health Connect refused it for quota. */
        const val RATE_LIMITED_MESSAGE = "Health Connect rate limit reached; the rest waits for the next sync"

        /**
         * How long a late batch's steps wait before they are added to their
         * day: enough for the rest of the burst to arrive, so a batch is one
         * write and one verdict rather than one per sample.
         */
        private const val LATE_STEPS_DELAY_MS = 2_000L

        /**
         * Health Connect reads are cross-process; a provider that is busy
         * migrating or being updated can block for a long time. Past this the
         * phone's own count is the answer, and the read is retried next time.
         */
        const val HC_READ_TIMEOUT_MS = 4_000L

        /** "Never happened" for an uptime-clock marker, far enough back that no interval holds it. */
        const val NEVER = Long.MIN_VALUE / 4

        /**
         * A multi-day read - range stats, the sources list. Up to 35 days of
         * raw records, and the aggregate fill for whatever those miss.
         */
        const val HC_RANGE_READ_TIMEOUT_MS = 15_000L

        /** A snapshot's raw records: up to 10,000 per type, so longer than a total's read. */
        const val HC_RECORDS_TIMEOUT_MS = 20_000L

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
