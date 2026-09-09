package com.steptrackerpro.core

import android.content.Context
import com.steptrackerpro.db.StepRepository
import com.steptrackerpro.health.HealthConnectManager
import com.steptrackerpro.health.StepSource
import com.steptrackerpro.health.StepSourcePolicy
import com.steptrackerpro.health.StepSourceResolver
import com.steptrackerpro.sync.SyncScheduler
import com.steptrackerpro.util.StepEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val configStore = ConfigStore(appContext)
    val state = StepStateStore(appContext)
    val metrics = MetricsCalculator(configStore.get())
    val repository = StepRepository(appContext)
    val goals = GoalTracker(appContext)
    val healthConnect = HealthConnectManager(appContext, state)

    val engine = StepCounterEngine(state, metrics).apply {
        onDayRollover = { closing, newDate -> handleRollover(closing, newDate) }
    }

    private val lastEventAt = AtomicLong(0L)
    private val syncMutex = Mutex()
    private val sourceCache = SourceCache()

    fun config(): StepTrackerConfig = configStore.get()

    fun updateConfig(config: StepTrackerConfig): StepTrackerConfig {
        val saved = configStore.save(config)
        metrics.config = saved
        SyncScheduler.schedule(appContext, saved)
        return saved
    }

    fun isInitialized(): Boolean = configStore.isInitialized()

    // ---- step pipeline ---------------------------------------------------

    /**
     * Called from the sensor callback for every changed total. Emits a
     * throttled `stepsChanged`, fires goal events, and flushes to the database
     * every `persistEveryNSteps`.
     */
    fun onStepsChanged(snapshot: StepSnapshot, force: Boolean = false) {
        val config = config()

        val now = System.currentTimeMillis()
        val previous = lastEventAt.get()
        if (force || now - previous >= config.eventThrottleMs) {
            lastEventAt.set(now)
            val resolution = resolveFromCache(
                DayTotals(snapshot.date, snapshot.steps, snapshot.distance, snapshot.calories)
            )
            StepEventBus.emit(
                StepEventBus.Events.STEPS_CHANGED,
                com.steptrackerpro.util.Bridge.snapshotMap(
                    if (resolution.usedExternal) displaySnapshot(snapshot) else snapshot,
                    resolution
                )
            )
        }

        // Goals stay keyed off this device's own count. A wearable's total
        // arrives in jumps whenever its companion app syncs, and firing
        // "goal reached" off a number that can also move backwards between
        // syncs would let the notification fire twice for one day.
        val percent = metrics.goalPercent(snapshot.steps, config.dailyGoal)
        if (goals.progressBucketChanged(GoalTracker.TYPE_DAILY, percent)) {
            StepEventBus.emit(
                StepEventBus.Events.GOAL_PROGRESS_CHANGED,
                mapOf(
                    "type" to GoalTracker.TYPE_DAILY,
                    "goal" to config.dailyGoal,
                    "steps" to snapshot.steps,
                    "progress" to snapshot.goalProgress,
                    "date" to snapshot.date
                )
            )
        }

        goals.checkDaily(snapshot.date, snapshot.steps, config.dailyGoal)?.let { reached ->
            emitGoalReached(reached, snapshot.date)
        }

        if (force || engine.shouldCommit(config.persistEveryNSteps)) {
            val totals = engine.consumeCommit()
            scope.launch {
                repository.saveDay(totals)
                checkPeriodGoals(totals)
            }
        }
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

    /** Persists the closing day, tells JS, and trims history past retention. */
    private fun handleRollover(closing: DayTotals, newDate: String) {
        scope.launch {
            repository.saveDay(closing)
            // The local date can also move backwards - travelling west across
            // the date line - in which case the day being adopted is one that
            // already has steps against it. Restore them so the user does not
            // land to a counter reading zero halfway through their day.
            if (newDate < closing.date) {
                engine.seedActiveDay(newDate, repository.getDay(newDate).steps)
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

    /** Writes whatever is in memory to the database. Called before shutdown. */
    fun flush(): DayTotals {
        val totals = engine.consumeCommit()
        scope.launch { repository.saveDay(totals) }
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
            false
        )
    }

    suspend fun stats(start: String, end: String, goal: Int?): RangeStats {
        val today = DateKeys.today()
        val live = if (today in start..end) liveToday() else null
        return repository.stats(start, end, goal, live)
    }

    suspend fun dayTotals(date: String): DayTotals =
        if (date == DateKeys.today()) liveToday() else repository.getDay(date)

    // ---- step source resolution -----------------------------------------

    fun sourcePolicy(): StepSourcePolicy = StepSourcePolicy.from(config().stepSource)

    /** A config pin wins; otherwise whatever the user last chose at runtime. */
    fun preferredSourcePackage(): String? =
        config().preferredStepSourcePackage ?: state.preferredStepSource

    fun setPreferredSourcePackage(packageName: String?) {
        state.preferredStepSource = packageName
        sourceCache.invalidate()
    }

    /**
     * True when Health Connect should be consulted for the number at all.
     * Everything downstream short-circuits on this so that a device with no
     * provider, no grant or the `device` policy never pays for a read.
     */
    private suspend fun shouldConsultHealthConnect(): Boolean {
        val config = config()
        if (!config.healthConnectEnabled) return false
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
        val device = dayTotals(date)
        if (!shouldConsultHealthConnect()) {
            return StepSourceResolver.resolve(
                StepSourcePolicy.DEVICE, device, emptyList(), null, metrics
            )
        }
        return StepSourceResolver.resolve(
            sourcePolicy(),
            device,
            sourcesForDay(date),
            preferredSourcePackage(),
            metrics
        )
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
        val base = stats(start, end, goal)
        if (!shouldConsultHealthConnect()) return base

        val byDate = runCatching {
            healthConnect.readDailyStepsBySource(
                DateKeys.startOfDayInstant(start),
                minOf(DateKeys.endOfDayInstant(end), Instant.now())
            )
        }.getOrDefault(emptyMap())
        if (byDate.isEmpty()) return base

        val policy = sourcePolicy()
        val preferred = preferredSourcePackage()
        val resolved = base.days.map { day ->
            StepSourceResolver.resolve(
                policy, day, byDate[day.date].orEmpty(), preferred, metrics
            ).totals
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
        if (config().healthConnectEnabled &&
            healthConnect.availability() == HealthConnectManager.Availability.AVAILABLE &&
            healthConnect.canRead()
        ) {
            return runCatching {
                healthConnect.listSources(
                    DateKeys.startOfDayInstant(start),
                    minOf(DateKeys.endOfDayInstant(end), Instant.now())
                )
            }.getOrDefault(emptyList())
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
        val deviceOnly = {
            StepSourceResolver.resolve(
                StepSourcePolicy.DEVICE, totals, emptyList(), null, metrics
            )
        }
        val policy = sourcePolicy()
        if (policy == StepSourcePolicy.DEVICE || !config().healthConnectEnabled) {
            return deviceOnly()
        }
        val cached = sourceCache.get(totals.date) ?: return deviceOnly()
        return StepSourceResolver.resolve(
            policy, totals, cached, preferredSourcePackage(), metrics
        )
    }

    /**
     * The snapshot as it should be shown, with a wearable's numbers swapped in
     * where one owns the day. `state` and `source` still describe this device's
     * own sensor, which is a separate question from where the count came from.
     */
    fun displaySnapshot(base: StepSnapshot = engine.snapshot()): StepSnapshot {
        val resolution = resolveFromCache(
            DayTotals(base.date, base.steps, base.distance, base.calories)
        )
        if (!resolution.usedExternal) return base
        val totals = resolution.totals
        return base.copy(
            steps = totals.steps,
            distance = totals.distance,
            calories = totals.calories,
            goalProgress = metrics.goalProgress(totals.steps, base.dailyGoal),
            goalReached = base.dailyGoal > 0 && totals.steps >= base.dailyGoal
        )
    }

    private suspend fun sourcesForDay(date: String): List<StepSource> {
        sourceCache.get(date)?.let { return it }
        val end = minOf(DateKeys.endOfDayInstant(date), Instant.now())
        val start = DateKeys.startOfDayInstant(date)
        if (!end.isAfter(start)) return emptyList()
        val sources = runCatching {
            healthConnect.readDailyStepsBySource(start, end)[date].orEmpty()
        }.getOrDefault(emptyList())
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
            if (healthConnect.writeDay(day)) {
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
        /** Stands in for "this device" in [StepStateStore.lastResolvedSource]. */
        const val SELF_SOURCE_KEY = "__self__"

        @Volatile
        private var instance: StepTrackerCore? = null

        fun get(context: Context): StepTrackerCore =
            instance ?: synchronized(this) {
                instance ?: StepTrackerCore(context).also { instance = it }
            }
    }
}
