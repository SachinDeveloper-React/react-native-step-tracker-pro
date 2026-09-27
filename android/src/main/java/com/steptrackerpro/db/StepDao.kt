package com.steptrackerpro.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface StepHistoryDao {

    @Query("SELECT * FROM step_history WHERE date = :date LIMIT 1")
    suspend fun findByDate(date: String): StepHistoryEntity?

    @Query("SELECT * FROM step_history WHERE date BETWEEN :start AND :end ORDER BY date ASC")
    suspend fun findRange(start: String, end: String): List<StepHistoryEntity>

    @Query("SELECT COALESCE(SUM(steps), 0) FROM step_history WHERE date BETWEEN :start AND :end")
    suspend fun sumSteps(start: String, end: String): Int

    @Query("SELECT * FROM step_history WHERE synced = 0 ORDER BY date ASC LIMIT :limit")
    suspend fun findUnsyncedHealth(limit: Int = 100): List<StepHistoryEntity>

    @Query("SELECT * FROM step_history WHERE syncedRemote = 0 ORDER BY date ASC LIMIT :limit")
    suspend fun findUnsyncedRemote(limit: Int = 100): List<StepHistoryEntity>

    @Query("SELECT COUNT(*) FROM step_history WHERE synced = 0 OR syncedRemote = 0")
    suspend fun countUnsynced(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: StepHistoryEntity): Long

    @Query(
        """
        UPDATE step_history
        SET steps = :steps, distance = :distance, calories = :calories,
            synced = 0, syncedRemote = 0, updatedAt = :updatedAt
        WHERE date = :date
        """
    )
    suspend fun update(
        date: String,
        steps: Int,
        distance: Double,
        calories: Double,
        updatedAt: Long
    ): Int

    /**
     * Upsert that never lets a re-anchored counter walk a day backwards: a
     * lower step count for an existing date is ignored. Deliberate writes that
     * must be allowed to lower a day - `resetToday()` - go through [replace].
     */
    @Transaction
    suspend fun upsert(entity: StepHistoryEntity) {
        val existing = findByDate(entity.date)
        if (existing == null) {
            insert(entity)
            return
        }
        if (entity.steps < existing.steps) return
        update(
            date = entity.date,
            steps = entity.steps,
            distance = entity.distance,
            calories = entity.calories,
            updatedAt = System.currentTimeMillis()
        )
    }

    @Query("UPDATE step_history SET synced = 1, updatedAt = :updatedAt WHERE date IN (:dates)")
    suspend fun markSyncedHealth(dates: List<String>, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE step_history SET syncedRemote = 1, updatedAt = :updatedAt WHERE date IN (:dates)")
    suspend fun markSyncedRemote(dates: List<String>, updatedAt: Long = System.currentTimeMillis()): Int

    /** Overwrites a day outright, downwards included. */
    @Transaction
    suspend fun replace(entity: StepHistoryEntity) {
        val existing = findByDate(entity.date)
        if (existing == null) {
            insert(entity)
            return
        }
        update(
            date = entity.date,
            steps = entity.steps,
            distance = entity.distance,
            calories = entity.calories,
            updatedAt = System.currentTimeMillis()
        )
    }

    @Query("DELETE FROM step_history WHERE date < :cutoff")
    suspend fun deleteOlderThan(cutoff: String): Int

    @Query("DELETE FROM step_history")
    suspend fun deleteAll()
}

@Dao
interface DailySummaryDao {

    @Query("SELECT * FROM daily_summary WHERE date = :date LIMIT 1")
    suspend fun findByDate(date: String): DailySummaryEntity?

    @Query("SELECT * FROM daily_summary WHERE date BETWEEN :start AND :end ORDER BY date ASC")
    suspend fun findRange(start: String, end: String): List<DailySummaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DailySummaryEntity)

    /**
     * Upsert that never lets the recovered share go backwards. A snapshot
     * written for the live day carries the engine's running figure, which
     * only grows within a day; a stale write racing a backfill for the same
     * row must not undo the backfill's increment. Mirrors [StepHistoryDao.upsert]'s
     * refusal to lower the step count.
     */
    @Transaction
    suspend fun upsertKeepingRecovered(entity: DailySummaryEntity) {
        val existing = findByDate(entity.date)
        if (existing != null && entity.recoveredSteps < existing.recoveredSteps) {
            upsert(entity.copy(recoveredSteps = existing.recoveredSteps))
        } else {
            upsert(entity)
        }
    }

    @Query("DELETE FROM daily_summary WHERE date < :cutoff")
    suspend fun deleteOlderThan(cutoff: String): Int

    @Query("DELETE FROM daily_summary")
    suspend fun deleteAll()
}

@Dao
interface MotionWindowDao {

    @Insert
    suspend fun insert(entity: MotionWindowEntity): Long

    @Query(
        "SELECT * FROM motion_window WHERE startedAt BETWEEN :fromMs AND :toMs ORDER BY startedAt ASC"
    )
    suspend fun findRange(fromMs: Long, toMs: Long): List<MotionWindowEntity>

    /** Keeps the newest [keep] windows and drops the rest. */
    @Query(
        """
        DELETE FROM motion_window WHERE id NOT IN (
            SELECT id FROM motion_window ORDER BY startedAt DESC, id DESC LIMIT :keep
        )
        """
    )
    suspend fun pruneToNewest(keep: Int): Int

    @Query("SELECT COUNT(*) FROM motion_window")
    suspend fun count(): Int

    @Query("DELETE FROM motion_window")
    suspend fun deleteAll()
}

@Dao
interface StepMinuteDao {

    @Query("SELECT * FROM step_minute WHERE minuteStart = :minuteStart LIMIT 1")
    suspend fun find(minuteStart: Long): StepMinuteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: StepMinuteEntity)

    /**
     * Adds increments on top of whatever each minute already holds. A plain
     * upsert would let the second flush of a minute overwrite the first.
     * Read-then-write in one transaction rather than `ON CONFLICT DO UPDATE`,
     * which needs SQLite 3.24 and API 26 ships 3.18.
     */
    @Transaction
    suspend fun addAll(rows: List<StepMinuteEntity>) {
        for (row in rows) {
            val existing = find(row.minuteStart)
            put(
                if (existing == null) {
                    row
                } else {
                    existing.copy(
                        steps = existing.steps + row.steps,
                        untimedSteps = existing.untimedSteps + row.untimedSteps,
                        chargingSteps = existing.chargingSteps + row.chargingSteps,
                        stillSteps = existing.stillSteps + row.stillSteps,
                        vehicleSteps = existing.vehicleSteps + row.vehicleSteps
                    )
                }
            )
        }
    }

    @Query("SELECT * FROM step_minute WHERE date BETWEEN :start AND :end ORDER BY minuteStart ASC")
    suspend fun findRange(start: String, end: String): List<StepMinuteEntity>

    @Query("DELETE FROM step_minute WHERE date < :cutoff")
    suspend fun deleteOlderThan(cutoff: String): Int

    @Query("DELETE FROM step_minute WHERE date = :date")
    suspend fun deleteDate(date: String): Int

    @Query("DELETE FROM step_minute")
    suspend fun deleteAll()
}

@Dao
interface IntegrityDao {

    @Query("SELECT * FROM integrity_day WHERE date = :date LIMIT 1")
    suspend fun findDay(date: String): IntegrityDayEntity?

    @Query("SELECT * FROM integrity_day WHERE date BETWEEN :start AND :end ORDER BY date ASC")
    suspend fun findDays(start: String, end: String): List<IntegrityDayEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDay(entity: IntegrityDayEntity)

    @Query("DELETE FROM integrity_day WHERE date < :cutoff")
    suspend fun deleteDaysOlderThan(cutoff: String): Int

    @Query("DELETE FROM integrity_day WHERE date = :date")
    suspend fun deleteDay(date: String): Int

    @Query("DELETE FROM integrity_day")
    suspend fun deleteAllDays()

    @Insert
    suspend fun insertEvent(entity: IntegrityEventEntity): Long

    @Query("SELECT * FROM integrity_event WHERE at BETWEEN :fromMs AND :toMs ORDER BY at ASC, id ASC")
    suspend fun findEvents(fromMs: Long, toMs: Long): List<IntegrityEventEntity>

    /** Keeps the newest [keep] events and drops the rest. */
    @Query(
        """
        DELETE FROM integrity_event WHERE id NOT IN (
            SELECT id FROM integrity_event ORDER BY at DESC, id DESC LIMIT :keep
        )
        """
    )
    suspend fun pruneEventsToNewest(keep: Int): Int

    @Query("DELETE FROM integrity_event WHERE date < :cutoff")
    suspend fun deleteEventsOlderThan(cutoff: String): Int

    @Query("SELECT COUNT(*) FROM integrity_event")
    suspend fun countEvents(): Int
}
