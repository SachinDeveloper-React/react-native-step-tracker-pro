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

    /**
     * Days a sync target in use has not accepted: with [remote], not
     * uploaded; with [health], not mirrored - [today] aside, which Health
     * Connect sync writes on every pass and never marks, because it is
     * still moving.
     */
    @Query(
        """
        SELECT COUNT(*) FROM step_history
        WHERE (:remote AND syncedRemote = 0) OR (:health AND synced = 0 AND date != :today)
        """
    )
    suspend fun countPending(remote: Boolean, health: Boolean, today: String): Int

    @Query("SELECT COUNT(*) FROM step_history WHERE syncedRemote = 0")
    suspend fun countUnsyncedRemote(): Int

    @Query("SELECT COUNT(*) FROM step_history WHERE synced = 0")
    suspend fun countUnsyncedHealth(): Int

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

    /**
     * Marks a day mirrored only while it still holds [steps], the count the
     * sync read from it. A write that changed the day meanwhile - a backfill
     * growing it - also re-queued it, and marking it done over that would
     * keep the new steps out of Health Connect for good.
     */
    @Query("UPDATE step_history SET synced = 1, updatedAt = :updatedAt WHERE date = :date AND steps = :steps")
    suspend fun markSyncedHealthIfUnchanged(date: String, steps: Int, updatedAt: Long): Int

    /** As [markSyncedHealthIfUnchanged], for the remote upload. */
    @Query("UPDATE step_history SET syncedRemote = 1, updatedAt = :updatedAt WHERE date = :date AND steps = :steps")
    suspend fun markSyncedRemoteIfUnchanged(date: String, steps: Int, updatedAt: Long): Int

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

    /**
     * Retention that spares days a sync target still has to accept: with
     * [keepRemote], rows not yet uploaded; with [keepHealth], rows not yet
     * mirrored into Health Connect.
     */
    @Query(
        """
        DELETE FROM step_history WHERE date < :cutoff
        AND NOT ((:keepRemote AND syncedRemote = 0) OR (:keepHealth AND synced = 0))
        """
    )
    suspend fun deleteOlderThanKeepingUnsynced(cutoff: String, keepRemote: Boolean, keepHealth: Boolean): Int

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

    /** Older than [cutoff] and with no history row left - kept rows keep their recovered share. */
    @Query("DELETE FROM daily_summary WHERE date < :cutoff AND date NOT IN (SELECT date FROM step_history)")
    suspend fun deleteOrphansOlderThan(cutoff: String): Int

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

    /** Older than [cutoff] and with no history row left - a day kept for upload keeps its minutes. */
    @Query("DELETE FROM step_minute WHERE date < :cutoff AND date NOT IN (SELECT date FROM step_history)")
    suspend fun deleteOrphansOlderThan(cutoff: String): Int

    @Query("DELETE FROM step_minute WHERE date < :cutoff")
    suspend fun deleteOlderThan(cutoff: String): Int

    @Query("DELETE FROM step_minute WHERE date = :date")
    suspend fun deleteDate(date: String): Int

    @Query("DELETE FROM step_minute")
    suspend fun deleteAll()
}

@Dao
interface MirrorMinuteDao {

    @Query("SELECT * FROM mirror_minute WHERE minuteStart = :minuteStart LIMIT 1")
    suspend fun find(minuteStart: Long): MirrorMinuteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: MirrorMinuteEntity)

    /**
     * Adds steps on top of whatever each minute already holds, keeping what
     * was written for it. Read-then-write in one transaction, like
     * [StepMinuteDao.addAll].
     */
    @Transaction
    suspend fun addAll(rows: List<MirrorMinuteEntity>) {
        for (row in rows) {
            val existing = find(row.minuteStart)
            put(if (existing == null) row else existing.copy(steps = existing.steps + row.steps))
        }
    }

    @Query("SELECT * FROM mirror_minute WHERE date = :date ORDER BY minuteStart ASC")
    suspend fun findDate(date: String): List<MirrorMinuteEntity>

    @Query("SELECT * FROM mirror_minute WHERE date BETWEEN :start AND :end AND written > 0 ORDER BY minuteStart ASC")
    suspend fun findWritten(start: String, end: String): List<MirrorMinuteEntity>

    @Query("UPDATE mirror_minute SET written = :written WHERE minuteStart = :minuteStart")
    suspend fun setWritten(minuteStart: Long, written: Int)

    /** What Health Connect holds for these minutes now, after a sync's writes and deletes landed. */
    @Transaction
    suspend fun setWrittenAll(written: Map<Long, Int>) {
        for ((minute, steps) in written) setWritten(minute, steps)
    }

    /** `resetToday()`: the counted steps go, what Health Connect holds is kept until it is deleted. */
    @Query("UPDATE mirror_minute SET steps = 0 WHERE date = :date")
    suspend fun clearSteps(date: String)

    /** Health Connect no longer holds a record for any minute of these days. */
    @Query("UPDATE mirror_minute SET written = 0 WHERE date IN (:dates)")
    suspend fun forgetWritten(dates: List<String>)

    /** Days between the two keys this table holds any minute of. */
    @Query("SELECT DISTINCT date FROM mirror_minute WHERE date BETWEEN :start AND :end")
    suspend fun datesBetween(start: String, end: String): List<String>

    /** A row with nothing counted and nothing written says nothing. */
    @Query("DELETE FROM mirror_minute WHERE steps = 0 AND written = 0")
    suspend fun deleteEmpty(): Int

    /** Older than [cutoff] and with no history row left - a day kept for its sync keeps its minutes. */
    @Query("DELETE FROM mirror_minute WHERE date < :cutoff AND date NOT IN (SELECT date FROM step_history)")
    suspend fun deleteOrphansOlderThan(cutoff: String): Int

    /** `clearHistory()`: what was counted goes, what Health Connect holds stays known until it is deleted. */
    @Query("UPDATE mirror_minute SET steps = 0 WHERE date < :date")
    suspend fun clearStepsBefore(date: String)
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

    /** Older than [cutoff] and with no history row left - a kept upload keeps its verdict. */
    @Query("DELETE FROM integrity_day WHERE date < :cutoff AND date NOT IN (SELECT date FROM step_history)")
    suspend fun deleteOrphanDaysOlderThan(cutoff: String): Int

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

    /** Older than [cutoff] and with no history row left - a day kept for upload keeps its events. */
    @Query("DELETE FROM integrity_event WHERE date < :cutoff AND date NOT IN (SELECT date FROM step_history)")
    suspend fun deleteOrphanEventsOlderThan(cutoff: String): Int

    @Query("SELECT COUNT(*) FROM integrity_event")
    suspend fun countEvents(): Int
}
