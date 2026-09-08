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
    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long = System.currentTimeMillis()
)
