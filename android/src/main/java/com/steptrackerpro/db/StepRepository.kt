package com.steptrackerpro.db

import android.content.Context
import androidx.room.withTransaction
import com.steptrackerpro.core.DateKeys
import com.steptrackerpro.core.DayTotals
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

    /**
     * Writes a day, refusing to lower an existing count. Both tables move in one
     * transaction so a process death cannot leave them disagreeing.
     */
    suspend fun saveDay(totals: DayTotals) = db.withTransaction {
        history.upsert(totals.toEntity())
        summaries.upsert(totals.toSummary())
    }

    /**
     * Writes a day even when that lowers it. Only for deliberate, user-initiated
     * writes - `resetToday()` - where [saveDay]'s guard would silently keep the
     * old total and leave history disagreeing with the live counter forever.
     */
    suspend fun overwriteDay(totals: DayTotals) = db.withTransaction {
        history.replace(totals.toEntity())
        summaries.upsert(totals.toSummary())
    }

    suspend fun getDay(date: String): DayTotals =
        history.findByDate(date)?.toTotals()
            ?: DayTotals(date, 0, 0.0, 0.0, synced = true, syncedRemote = true)

    /** Zero-filled, ascending, inclusive of both ends. */
    suspend fun getRange(start: String, end: String): List<DayTotals> {
        val stored = history.findRange(start, end).associateBy { it.date }
        return DateKeys.rangeOf(start, end).map { key ->
            stored[key]?.toTotals()
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

    suspend fun unsynced(target: SyncTarget, limit: Int = 100): List<DayTotals> = when (target) {
        SyncTarget.HEALTH_CONNECT -> history.findUnsyncedHealth(limit)
        SyncTarget.REMOTE -> history.findUnsyncedRemote(limit)
    }.map { it.toTotals() }

    suspend fun countUnsynced(): Int = history.countUnsynced()

    suspend fun markSynced(target: SyncTarget, dates: List<String>) {
        if (dates.isEmpty()) return
        when (target) {
            SyncTarget.HEALTH_CONNECT -> history.markSyncedHealth(dates)
            SyncTarget.REMOTE -> history.markSyncedRemote(dates)
        }
    }

    /** Drops rows older than the retention window. Returns rows removed. */
    suspend fun prune(retentionDays: Int): Int {
        val cutoff = DateKeys.minusDays(DateKeys.today(), retentionDays.coerceAtLeast(1))
        summaries.deleteOlderThan(cutoff)
        return history.deleteOlderThan(cutoff)
    }

    suspend fun clear() {
        history.deleteAll()
        summaries.deleteAll()
    }

    private fun StepHistoryEntity.toTotals() =
        DayTotals(date, steps, distance, calories, syncedHealth, syncedRemote)

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
        totalCalories = calories
    )
}
