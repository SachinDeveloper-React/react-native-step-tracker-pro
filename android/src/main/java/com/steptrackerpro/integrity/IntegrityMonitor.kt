package com.steptrackerpro.integrity

import android.content.Context
import com.steptrackerpro.core.ActivityState
import com.steptrackerpro.core.DateKeys
import com.steptrackerpro.core.DayEvaluation
import com.steptrackerpro.core.DayTotals
import com.steptrackerpro.core.FraudDetector
import com.steptrackerpro.core.IntegrityEvent
import com.steptrackerpro.core.IntegrityFlag
import com.steptrackerpro.core.StepTimeline
import com.steptrackerpro.core.StepTrackerConfig
import com.steptrackerpro.db.StepRepository
import com.steptrackerpro.util.StepEventBus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * The integrity layer's moving parts, held by the core: the per-minute
 * timeline the engine feeds, the charging and activity state the service
 * feeds, the detector's evaluations, the event log, and the arithmetic that
 * turns a day's flags into `suspectSteps`.
 *
 * Every database touch runs on the core's write lane, in order with the day
 * table writes, so an evaluation always sees the minutes flushed before it.
 * Nothing here changes a count. With `fraudDetection.enabled` off it records
 * nothing and every figure it reports is zero.
 */
class IntegrityMonitor(
    private val context: Context,
    private val repository: StepRepository,
    private val scope: CoroutineScope,
    private val lane: CoroutineDispatcher,
    private val config: () -> StepTrackerConfig,
    /** This device's own count for a day - live for today, stored otherwise. */
    private val deviceSteps: suspend (String) -> Int
) {

    val timeline = StepTimeline()

    /** Whether the phone is plugged in, as the service last saw it. */
    @Volatile
    var charging: Boolean = false
        private set

    /** Activity Recognition's last state; [ActivityState.UNKNOWN] without Play Services. */
    @Volatile
    var activity: ActivityState = ActivityState.UNKNOWN
        private set

    /** Today's strong-flag union, for the paths that cannot suspend. */
    @Volatile
    private var liveDate: String? = null

    @Volatile
    private var liveFlagged: Int = 0

    /** Uptime clock, so a clock set back cannot hold the once-a-minute check for hours. */
    private val lastEvaluationAt = AtomicLong(com.steptrackerpro.core.StepTrackerCore.NEVER)

    /**
     * Called after an evaluation moved today's flagged total. Under exclude
     * mode that moves the number on screen with no sensor sample to carry
     * it, so the core re-emits `stepsChanged`.
     */
    @Volatile
    var onLiveChanged: (() -> Unit)? = null

    val enabled: Boolean get() = config().fraudDetectionEnabled

    init {
        // Restores today's verdict after a process restart, so exclude mode
        // does not show the flagged steps again until the next evaluation.
        scope.launch(lane) {
            runCatching {
                val today = DateKeys.today()
                repository.evaluation(today)?.let { stored ->
                    if (liveDate == null) {
                        liveDate = today
                        liveFlagged = stored.flaggedSteps
                    }
                }
            }
        }
    }

    // ---- inputs ----------------------------------------------------------------

    /** From the engine, under its lock: keep it to an in-memory append. */
    fun onObserved(fromMs: Long, toMs: Long, steps: Int, timed: Boolean = true) {
        if (!enabled) return
        timeline.record(fromMs, toMs, steps, charging, activity, timed)
    }

    /**
     * @param announce false for the state read when the receiver is first
     *   registered, which is a starting point rather than a change.
     */
    fun setCharging(value: Boolean, announce: Boolean) {
        if (charging == value) return
        charging = value
        if (announce) log(if (value) IntegrityEvent.CHARGING_STARTED else IntegrityEvent.CHARGING_STOPPED)
    }

    fun onActivity(state: ActivityState) {
        if (activity == state) return
        activity = state
        log(IntegrityEvent.ACTIVITY_CHANGED, mapOf("activity" to state.jsValue))
    }

    /** Activity Recognition was stopped; a state it reported earlier no longer holds. */
    fun resetActivity() {
        activity = ActivityState.UNKNOWN
    }

    /**
     * Appends to the event log. [force] logs even with detection off, for
     * the one event that matters most then: detection being turned off.
     */
    fun log(type: String, detail: Map<String, Any?> = emptyMap(), force: Boolean = false) {
        if (!force && !enabled) return
        val event = IntegrityEvent(System.currentTimeMillis(), type, detail)
        scope.launch(lane) { runCatching { repository.addEvent(event) } }
    }

    // ---- evaluation --------------------------------------------------------------

    /** From the sensor path: re-judges today at most once a minute. */
    fun maybeEvaluate(force: Boolean = false) {
        if (!enabled) return
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastEvaluationAt.get()
        if (!force && now - last < EVALUATE_INTERVAL_MS) return
        if (!lastEvaluationAt.compareAndSet(last, now)) return
        scope.launch(lane) { runCatching { evaluate(DateKeys.today()) } }
    }

    /** Writes pending minutes. On the lane. */
    suspend fun flush() {
        val drained = timeline.drain()
        if (drained.isNotEmpty()) repository.addMinutes(drained)
    }

    /**
     * Flushes, judges [date] from storage, stores the verdict, and tells JS
     * about any flag it has not reported before. On the lane.
     */
    suspend fun evaluate(date: String): DayEvaluation {
        flush()
        val cfg = config()
        val rules = cfg.integrityRules()
        val start = DateKeys.startOfDayMillis(date)
        val end = DateKeys.endOfDayMillis(date)
        val previous = repository.evaluation(date)
        val evaluation = FraudDetector.evaluate(
            repository.minutes(date, date),
            repository.motionWindows(start, end),
            rules,
            DateKeys.zone()
        )
        val device = deviceSteps(date)
        // The daily cap is stored with the rest only so its event fires once;
        // every read recomputes it against the current count.
        val volume = FraudDetector.dailyVolumeFlag(evaluation.flaggedSteps, device, rules, start, end + 1)
        val stored = evaluation.copy(flags = evaluation.flags + listOfNotNull(volume))
        repository.saveEvaluation(date, stored, System.currentTimeMillis())
        if (date == DateKeys.today()) {
            val moved = liveDate != date || liveFlagged != evaluation.flaggedSteps
            liveDate = date
            liveFlagged = evaluation.flaggedSteps
            if (moved && cfg.excludeSuspect) onLiveChanged?.invoke()
        }
        val known = previous?.flags?.mapTo(HashSet()) { it.key }.orEmpty()
        val fresh = stored.flags.filter { it.key !in known }
        if (fresh.isNotEmpty()) {
            StepEventBus.emit(
                StepEventBus.Events.SUSPICIOUS_ACTIVITY,
                mapOf(
                    "date" to date,
                    "flags" to fresh.map { it.toMap() },
                    "deviceSteps" to device,
                    "suspectSteps" to FraudDetector.suspectSteps(evaluation.flaggedSteps, device, rules),
                    "mode" to cfg.integrityMode.jsValue
                )
            )
        }
        return evaluation
    }

    /** The day that just closed gets a final verdict. On the lane. */
    suspend fun onDayClosed(date: String) {
        if (enabled) evaluate(date) else timeline.clear()
    }

    /**
     * A closed day grew by steps that arrived late, and its minutes by the
     * minutes they were taken in: judged again, so a shaker run up to
     * midnight with the phone asleep is caught on the day it counts for.
     * On the lane.
     */
    suspend fun onLateSteps(date: String) {
        if (enabled) evaluate(date)
    }

    /** `resetToday()` zeroed the count; the minutes and verdict describing it go too. On the lane. */
    suspend fun onReset(date: String) {
        timeline.clear()
        repository.clearIntegrityDay(date)
        if (liveDate == date) liveFlagged = 0
        log(IntegrityEvent.RESET_TODAY, mapOf("date" to date))
    }

    /** On the lane, after the repository has cleared the day tables. */
    fun onHistoryCleared() {
        timeline.clear()
        liveFlagged = 0
        log(IntegrityEvent.HISTORY_CLEARED)
    }

    // ---- suspect steps ------------------------------------------------------------

    /** Today's suspect steps from memory, for the sensor path and the notification. */
    fun liveSuspect(date: String, deviceSteps: Int): Int {
        if (!enabled) return 0
        val flagged = if (date == liveDate) liveFlagged else 0
        return FraudDetector.suspectSteps(flagged, deviceSteps, config().integrityRules())
    }

    suspend fun suspect(date: String, deviceSteps: Int): Int {
        if (!enabled) return 0
        val flagged = if (date == liveDate) liveFlagged else repository.evaluation(date)?.flaggedSteps ?: 0
        return FraudDetector.suspectSteps(flagged, deviceSteps, config().integrityRules())
    }

    /** Suspect steps for many days in one read, keyed by date. */
    suspend fun suspects(days: List<DayTotals>): Map<String, Int> {
        if (!enabled || days.isEmpty()) return emptyMap()
        val rules = config().integrityRules()
        val stored = repository.flaggedSteps(days.minOf { it.date }, days.maxOf { it.date })
        return days.associate { day ->
            val flagged = if (day.date == liveDate) liveFlagged else stored[day.date] ?: 0
            day.date to FraudDetector.suspectSteps(flagged, day.steps, rules)
        }
    }

    /** How much of [suspect] comes out of the numbers: all of it under exclude mode, none otherwise. */
    fun excluded(suspect: Int): Int = if (config().excludeSuspect) suspect else 0

    // ---- reads ------------------------------------------------------------------------

    /**
     * Everything the integrity layer knows about one day. Today is judged
     * afresh first; a past day reads its stored verdict.
     */
    suspend fun report(date: String): Map<String, Any?> {
        val cfg = config()
        val on = cfg.fraudDetectionEnabled
        val rules = cfg.integrityRules()
        val start = DateKeys.startOfDayMillis(date)
        val end = DateKeys.endOfDayMillis(date)
        val device = deviceSteps(date)
        val today = date == DateKeys.today()

        var flagged = 0
        var flags: List<IntegrityFlag> = emptyList()
        var evaluatedAt = 0L
        var events: List<IntegrityEvent> = emptyList()
        var minutes = emptyList<com.steptrackerpro.core.MinuteSample>()
        if (on) {
            if (today) {
                val fresh = withContext(lane) { evaluate(date) }
                flagged = fresh.flaggedSteps
                flags = fresh.flags
                evaluatedAt = System.currentTimeMillis()
            } else {
                repository.evaluation(date)?.let { stored ->
                    flagged = stored.flaggedSteps
                    flags = stored.flags.filter { it.type != IntegrityFlag.DAILY_VOLUME }
                    evaluatedAt = stored.evaluatedAt
                }
            }
            flags = (flags + listOfNotNull(FraudDetector.dailyVolumeFlag(flagged, device, rules, start, end + 1)))
                .sortedWith(compareBy({ it.from }, { it.type }))
            events = repository.events(start, end)
            minutes = repository.minutes(date, date)
        }
        return mapOf(
            "date" to date,
            "enabled" to on,
            "mode" to cfg.integrityMode.jsValue,
            "deviceSteps" to device,
            "suspectSteps" to if (on) FraudDetector.suspectSteps(flagged, device, rules) else 0,
            "flags" to flags.map { it.toMap() },
            "events" to events.map { it.toMap() },
            "minutes" to mapOf(
                "count" to minutes.size,
                "timedSteps" to minutes.sumOf { it.steps },
                "untimedSteps" to minutes.sumOf { it.untimedSteps },
                "chargingSteps" to minutes.sumOf { it.chargingSteps },
                "stillSteps" to minutes.sumOf { it.stillSteps },
                "vehicleSteps" to minutes.sumOf { it.vehicleSteps }
            ),
            "evaluatedAt" to evaluatedAt,
            "rules" to mapOf(
                "maxCadenceSpm" to rules.maxCadenceSpm,
                "steadyCadenceMinutes" to rules.steadyCadenceMinutes,
                "maxContinuousMinutes" to rules.maxContinuousMinutes,
                "maxDailySteps" to rules.maxDailySteps,
                "flagWhileCharging" to rules.flagWhileCharging
            ),
            "charging" to (today && charging),
            "activityRecognition" to mapOf(
                "requested" to (on && cfg.fraudActivityRecognition),
                "available" to ActivityRecognitionBridge.isAvailable(),
                "current" to if (today) activity.jsValue else ActivityState.UNKNOWN.jsValue
            ),
            "device" to DeviceSignals.collect(context)
        )
    }

    companion object {
        /** How often the sensor path may ask for today to be re-judged. */
        const val EVALUATE_INTERVAL_MS = 60_000L
    }
}
