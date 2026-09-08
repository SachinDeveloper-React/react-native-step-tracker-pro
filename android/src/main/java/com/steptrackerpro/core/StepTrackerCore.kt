package com.steptrackerpro.core

import android.content.Context
import com.steptrackerpro.db.StepRepository
import com.steptrackerpro.health.HealthConnectManager
import com.steptrackerpro.sync.SyncScheduler
import com.steptrackerpro.util.StepEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
            StepEventBus.emit(
                StepEventBus.Events.STEPS_CHANGED,
                com.steptrackerpro.util.Bridge.snapshotMap(snapshot)
            )
        }

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

    // ---- sync ------------------------------------------------------------

    /** Pushes every unsynced day (plus today) into Health Connect. */
    suspend fun syncHealthConnect(): Map<String, Any?> = syncMutex.withLock {
        val config = config()
        // These three are states the user has to change, not transient faults.
        // Reporting them as retryable would have the periodic worker back off
        // and try again forever on a device that will never succeed.
        if (!config.healthConnectEnabled) {
            return@withLock syncResult("health_connect", 0, 0, false, "Disabled in config")
        }
        if (healthConnect.availability() != HealthConnectManager.Availability.AVAILABLE) {
            return@withLock syncResult(
                "health_connect", 0, 0, false, "Health Connect unavailable"
            )
        }
        if (!healthConnect.hasAllPermissions()) {
            return@withLock syncResult(
                "health_connect", 0, 0, false, "Health Connect permissions not granted"
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
        val syncedDates = ArrayList<String>(targets.size)
        for (day in targets) {
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

        // A write that failed against an available, permitted provider is worth
        // another attempt; nothing else here is.
        val result = syncResult(
            "health_connect", succeeded, failed, failed == 0, null, retryable = failed > 0
        )
        StepEventBus.emit(StepEventBus.Events.SYNC_COMPLETED, result)
        result
    }

    private fun syncResult(
        target: String,
        synced: Int,
        failed: Int,
        success: Boolean,
        error: String?,
        retryable: Boolean = false
    ): Map<String, Any?> = mapOf(
        "target" to target,
        "syncedRecords" to synced,
        "failedRecords" to failed,
        "success" to success,
        "error" to error,
        "retryable" to retryable
    )

    companion object {
        @Volatile
        private var instance: StepTrackerCore? = null

        fun get(context: Context): StepTrackerCore =
            instance ?: synchronized(this) {
                instance ?: StepTrackerCore(context).also { instance = it }
            }
    }
}
