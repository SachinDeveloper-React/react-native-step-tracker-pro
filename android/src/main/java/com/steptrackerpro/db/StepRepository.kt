package com.steptrackerpro.db

import android.content.Context
import androidx.room.withTransaction
import com.steptrackerpro.core.DateKeys
import com.steptrackerpro.core.DayEvaluation
import com.steptrackerpro.core.DayTotals
import com.steptrackerpro.core.IntegrityEvent
import com.steptrackerpro.core.IntegrityFlag
import com.steptrackerpro.core.JsonMaps
import com.steptrackerpro.core.MetricsCalculator
import com.steptrackerpro.core.MinuteSample
import com.steptrackerpro.core.MotionFeatures
import com.steptrackerpro.core.RangeStats
import com.steptrackerpro.core.SyncTarget

/**
 * Everything the rest of the package knows about storage. Callers deal in
 * [DayTotals], never in Room entities.
 */
class StepRepository(context: Context) {

    private val db = StepDatabase.get(context)
    private val history = db.stepHistoryDao()
    private val summaries = db.dailySummaryDao()
    private val motion = db.motionWindowDao()
    private val minuteRows = db.stepMinuteDao()
    private val integrity = db.integrityDao()

    /** The detector's stored verdict on one day. */
    data class StoredEvaluation(
        val flaggedSteps: Int,
        val flags: List<IntegrityFlag>,
        val evaluatedAt: Long
    )

    /**
     * Writes a day, refusing to lower an existing count. Both tables move in one
     * transaction so a process death cannot leave them disagreeing.
     */
    suspend fun saveDay(totals: DayTotals) = db.withTransaction {
        history.upsert(totals.toEntity())
        summaries.upsertKeepingRecovered(totals.toSummary())
    }

    /**
     * Writes a day even when that lowers it. Only for deliberate, user-initiated
     * writes - `resetToday()` - where [saveDay]'s guard would silently keep the
     * old total and leave history disagreeing with the live counter forever.
     * The recovered share is overwritten too: a reset day has recovered nothing.
     */
    suspend fun overwriteDay(totals: DayTotals) = db.withTransaction {
        history.replace(totals.toEntity())
        summaries.upsert(totals.toSummary())
    }

    /**
     * Adds steps on top of a stored day - the backfill path for steps the
     * hardware counted while the process was dead and the gap crossed
     * midnight. Distance and calories are re-derived from the new total so
     * the three stay consistent, the row is re-queued for both syncs by
     * [StepHistoryDao.update], and the day's recovered share grows by the
     * same amount so the row says how much of it was apportioned rather
     * than observed.
     *
     * @return the day as stored afterwards, or null when nothing was added.
     */
    suspend fun addToDay(date: String, steps: Int, metrics: MetricsCalculator): DayTotals? {
        if (steps <= 0) return null
        return db.withTransaction {
            val existing = history.findByDate(date)
            val recovered = (summaries.findByDate(date)?.recoveredSteps ?: 0) + steps
            val total = (existing?.steps ?: 0) + steps
            val totals = metrics.totals(date, total, recoveredSteps = recovered)
            history.replace(totals.toEntity())
            summaries.upsert(totals.toSummary())
            totals
        }
    }

    suspend fun getDay(date: String): DayTotals =
        history.findByDate(date)?.toTotals(summaries.findByDate(date))
            ?: DayTotals(date, 0, 0.0, 0.0, synced = true, syncedRemote = true)

    /** Zero-filled, ascending, inclusive of both ends. */
    suspend fun getRange(start: String, end: String): List<DayTotals> {
        val stored = history.findRange(start, end).associateBy { it.date }
        // The recovered share lives on the summary row, written in the same
        // transaction as the history row, so the two are read together.
        val recovered = summaries.findRange(start, end).associateBy { it.date }
        return DateKeys.rangeOf(start, end).map { key ->
            stored[key]?.toTotals(recovered[key])
                ?: DayTotals(key, 0, 0.0, 0.0, synced = true, syncedRemote = true)
        }
    }

    /**
     * @param live today's in-memory totals, which are newer than anything
     *   committed to the database. Pass null when today is outside the range.
     */
    suspend fun stats(
        start: String,
        end: String,
        goal: Int?,
        live: DayTotals?
    ): RangeStats {
        // The live counter is authoritative for today: it is newer than anything
        // committed, and after a reset it is legitimately lower than the stored
        // row. Preferring the larger of the two would resurrect the old total.
        val days = getRange(start, end).map { day ->
            if (live != null && day.date == live.date) live else day
        }
        val totalSteps = days.sumOf { it.steps }
        val totalDistance = days.sumOf { it.distance }
        val totalCalories = days.sumOf { it.calories }
        val activeDays = days.count { it.steps > 0 }

        // Average over elapsed days only, so a partially finished month is not
        // diluted by days that have not happened yet.
        val today = DateKeys.today()
        val elapsed = if (today in start..end) {
            DateKeys.daysBetween(start, if (today < end) today else end)
        } else {
            days.size
        }.coerceAtLeast(1)

        return RangeStats(
            startDate = start,
            endDate = end,
            totalSteps = totalSteps,
            totalDistance = totalDistance,
            totalCalories = totalCalories,
            averageSteps = totalSteps / elapsed,
            activeDays = activeDays,
            bestDay = days.maxByOrNull { it.steps }?.takeIf { it.steps > 0 },
            days = days,
            goal = goal,
            goalProgress = goal?.takeIf { it > 0 }?.let {
                (totalSteps.toDouble() / it).coerceIn(0.0, 1.0)
            }
        )
    }

    suspend fun sumSteps(start: String, end: String): Int = history.sumSteps(start, end)

    suspend fun unsynced(target: SyncTarget, limit: Int = 100): List<DayTotals> {
        val rows = when (target) {
            SyncTarget.HEALTH_CONNECT -> history.findUnsyncedHealth(limit)
            SyncTarget.REMOTE -> history.findUnsyncedRemote(limit)
        }
        if (rows.isEmpty()) return emptyList()
        val recovered = summaries.findRange(rows.first().date, rows.last().date)
            .associateBy { it.date }
        return rows.map { it.toTotals(recovered[it.date]) }
    }

    suspend fun countUnsynced(): Int = history.countUnsynced()

    /** Days not yet accepted by [target]. */
    suspend fun countUnsynced(target: SyncTarget): Int = when (target) {
        SyncTarget.HEALTH_CONNECT -> history.countUnsyncedHealth()
        SyncTarget.REMOTE -> history.countUnsyncedRemote()
    }

    suspend fun markSynced(target: SyncTarget, dates: List<String>) {
        if (dates.isEmpty()) return
        when (target) {
            SyncTarget.HEALTH_CONNECT -> history.markSyncedHealth(dates)
            SyncTarget.REMOTE -> history.markSyncedRemote(dates)
        }
    }

    /** Drops rows older than the retention window. Returns rows removed. */
    /**
     * Deletes what is older than [retentionDays]. A day a sync target has not
     * accepted yet is kept - with [keepUnsyncedRemote], one not uploaded;
     * with [keepUnsyncedHealth], one not mirrored - so an endpoint that is
     * down for longer than the retention window loses nothing. Kept days
     * keep everything the upload sends with them: the recovered share, the
     * integrity verdict, and the minutes and events its report is built from. Nothing is kept past [UNSYNCED_KEEP_DAYS]
     * however it stands, so storage stays bounded.
     */
    suspend fun prune(
        retentionDays: Int,
        keepUnsyncedRemote: Boolean = false,
        keepUnsyncedHealth: Boolean = false
    ): Int {
        val days = retentionDays.coerceAtLeast(1)
        val today = DateKeys.today()
        val cutoff = DateKeys.minusDays(today, days)
        val ceiling = DateKeys.minusDays(today, maxOf(days, UNSYNCED_KEEP_DAYS))
        val deleted = history.deleteOlderThanKeepingUnsynced(cutoff, keepUnsyncedRemote, keepUnsyncedHealth) +
            history.deleteOlderThan(ceiling)
        summaries.deleteOrphansOlderThan(cutoff)
        integrity.deleteOrphanDaysOlderThan(cutoff)
        minuteRows.deleteOrphansOlderThan(cutoff)
        integrity.deleteOrphanEventsOlderThan(cutoff)
        return deleted
    }

    /**
     * Deletes the user's step data. The integrity event log is kept on
     * purpose - the caller logs the clear itself into it - so a history wipe
     * cannot also wipe the record that it happened. It holds no steps, and
     * is bounded by count and by retention like everything else.
     */
    suspend fun clear() {
        history.deleteAll()
        summaries.deleteAll()
        motion.deleteAll()
        minuteRows.deleteAll()
        integrity.deleteAllDays()
    }

    // ---- integrity -----------------------------------------------------------

    /** Adds per-minute increments on top of whatever each minute already holds. */
    suspend fun addMinutes(samples: List<MinuteSample>) {
        if (samples.isEmpty()) return
        minuteRows.addAll(
            samples.map {
                StepMinuteEntity(
                    minuteStart = it.minuteStart,
                    date = DateKeys.of(it.minuteStart),
                    steps = it.steps,
                    untimedSteps = it.untimedSteps,
                    chargingSteps = it.chargingSteps,
                    stillSteps = it.stillSteps,
                    vehicleSteps = it.vehicleSteps
                )
            }
        )
    }

    /** Minutes with steps on the days between the two keys, inclusive, oldest first. */
    suspend fun minutes(start: String, end: String): List<MinuteSample> =
        minuteRows.findRange(start, end).map {
            MinuteSample(
                it.minuteStart, it.steps, it.untimedSteps,
                it.chargingSteps, it.stillSteps, it.vehicleSteps
            )
        }

    suspend fun saveEvaluation(date: String, evaluation: DayEvaluation, at: Long) {
        integrity.putDay(
            IntegrityDayEntity(
                date = date,
                flaggedSteps = evaluation.flaggedSteps,
                flagsJson = IntegrityFlag.listToJson(evaluation.flags),
                evaluatedAt = at
            )
        )
    }

    suspend fun evaluation(date: String): StoredEvaluation? =
        integrity.findDay(date)?.let {
            StoredEvaluation(it.flaggedSteps, IntegrityFlag.listFromJson(it.flagsJson), it.evaluatedAt)
        }

    /** Stored strong-flag totals per day; days never evaluated are absent. */
    suspend fun flaggedSteps(start: String, end: String): Map<String, Int> =
        integrity.findDays(start, end).associate { it.date to it.flaggedSteps }

    /** Forgets one day's minutes and verdict - `resetToday()` zeroed the count they described. */
    suspend fun clearIntegrityDay(date: String) = db.withTransaction {
        minuteRows.deleteDate(date)
        integrity.deleteDay(date)
    }

    /** Appends to the event log and trims it to the newest [MAX_EVENTS]. */
    suspend fun addEvent(event: IntegrityEvent) = db.withTransaction {
        integrity.insertEvent(
            IntegrityEventEntity(
                at = event.at,
                date = DateKeys.of(event.at),
                type = event.type,
                detailJson = JsonMaps.toJson(event.detail)
            )
        )
        integrity.pruneEventsToNewest(MAX_EVENTS)
    }

    /** Events between the two instants, oldest first. */
    suspend fun events(fromMs: Long, toMs: Long): List<IntegrityEvent> =
        integrity.findEvents(fromMs, toMs).map { IntegrityEvent(it.at, it.type, JsonMaps.parse(it.detailJson)) }

    // ---- motion windows ----------------------------------------------------

    /**
     * Stores one window and trims the table to the newest [retention]. The
     * two run in one transaction so the table is never over the bound.
     */
    suspend fun addMotionWindow(features: MotionFeatures, retention: Int) = db.withTransaction {
        motion.insert(
            MotionWindowEntity(
                date = DateKeys.of(features.startedAt),
                startedAt = features.startedAt,
                durationMs = features.durationMs,
                sampleCount = features.sampleCount,
                dominantFrequencyHz = features.dominantFrequencyHz,
                variance = features.variance,
                zeroCrossingRate = features.zeroCrossingRate,
                peakRatio = features.peakRatio,
                stepsDuringWindow = features.stepsDuringWindow
            )
        )
        motion.pruneToNewest(retention.coerceAtLeast(1))
    }

    /** Windows that opened between the two instants, oldest first. */
    suspend fun motionWindows(fromMs: Long, toMs: Long): List<MotionFeatures> =
        motion.findRange(fromMs, toMs).map {
            MotionFeatures(
                startedAt = it.startedAt,
                durationMs = it.durationMs,
                sampleCount = it.sampleCount,
                dominantFrequencyHz = it.dominantFrequencyHz,
                variance = it.variance,
                zeroCrossingRate = it.zeroCrossingRate,
                peakRatio = it.peakRatio,
                stepsDuringWindow = it.stepsDuringWindow
            )
        }

    companion object {
        /** The longest a day waiting on a sync target is kept past retention. */
        const val UNSYNCED_KEEP_DAYS = 365

        /**
         * The event log's hard bound, whatever the retention. A clock that
         * is being changed in a loop, or a charger cable with a loose
         * contact, cannot grow the table past this.
         */
        const val MAX_EVENTS = 2_000
    }

    private fun StepHistoryEntity.toTotals(summary: DailySummaryEntity?) =
        DayTotals(
            date, steps, distance, calories, syncedHealth, syncedRemote,
            // Never more than the day has: a summary row is replaced whole,
            // and a reset that lowered the total lowered this with it.
            recoveredSteps = (summary?.recoveredSteps ?: 0).coerceIn(0, steps)
        )

    private fun DayTotals.toEntity() = StepHistoryEntity(
        date = date,
        steps = steps,
        distance = distance,
        calories = calories,
        syncedHealth = synced,
        syncedRemote = syncedRemote
    )

    private fun DayTotals.toSummary() = DailySummaryEntity(
        date = date,
        totalSteps = steps,
        totalDistance = distance,
        totalCalories = calories,
        recoveredSteps = recoveredSteps
    )
}
