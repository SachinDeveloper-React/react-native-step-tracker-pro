package com.steptrackerpro.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Append-only-ish log of day totals. One row per day per write cycle target:
 * the row for a given date is updated in place while that day is active, and
 * frozen once the day rolls over.
 */
@Entity(
    tableName = "step_history",
    indices = [
        Index(value = ["date"], unique = true),
        Index(value = ["synced"]),
        Index(value = ["syncedRemote"])
    ]
)
data class StepHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** yyyy-MM-dd, device local. */
    val date: String,

    val steps: Int,

    /** Metres. */
    val distance: Double,

    /** Kilocalories. */
    val calories: Double,

    /**
     * 0 = pending, 1 = mirrored to Health Connect. Kept under its original
     * column name so existing rows migrate without a rewrite.
     */
    @ColumnInfo(name = "synced")
    val syncedHealth: Boolean = false,

    /** 0 = pending, 1 = uploaded to the configured remote endpoint. */
    @ColumnInfo(name = "syncedRemote", defaultValue = "0")
    val syncedRemote: Boolean = false,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Rolled-up totals, kept separate so statistics queries never contend with the
 * hot write path on `step_history`.
 */
@Entity(tableName = "daily_summary")
data class DailySummaryEntity(
    @PrimaryKey
    val date: String,
    val totalSteps: Int,
    val totalDistance: Double,
    val totalCalories: Double,
    /**
     * Of `totalSteps`, how many were credited to the day in one go by gap
     * recovery - after a process kill across midnight, a reboot, or the
     * since-boot claim on install - rather than observed sample by sample.
     * A server judging a day wants this separately: a recovered share is an
     * apportionment, not an observation. Added in version 3 with a default
     * of 0, so rows from before it read as fully observed, which is the
     * only honest answer for a day nobody recorded the split for.
     */
    @ColumnInfo(name = "recoveredSteps", defaultValue = "0")
    val recoveredSteps: Int = 0,
    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * One motion signature window: a handful of features describing how the
 * phone moved for a few seconds, never the samples themselves. Bounded by
 * `motionWindowRetention` (default 288, a day at five-minute intervals) and
 * pruned on every insert, so the table cannot grow past that.
 */
@Entity(
    tableName = "motion_window",
    indices = [Index(value = ["startedAt"])]
)
data class MotionWindowEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** yyyy-MM-dd the window opened on, device local. */
    val date: String,
    /** Epoch ms the window opened. */
    val startedAt: Long,
    val durationMs: Long,
    val sampleCount: Int,
    val dominantFrequencyHz: Double,
    val variance: Double,
    val zeroCrossingRate: Double,
    val peakRatio: Double,
    val stepsDuringWindow: Int
)
