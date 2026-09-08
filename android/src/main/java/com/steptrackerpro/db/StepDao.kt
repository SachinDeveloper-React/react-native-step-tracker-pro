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

    @Query("DELETE FROM daily_summary WHERE date < :cutoff")
    suspend fun deleteOlderThan(cutoff: String): Int

    @Query("DELETE FROM daily_summary")
    suspend fun deleteAll()
}
