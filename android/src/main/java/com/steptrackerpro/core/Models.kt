package com.steptrackerpro.core

/** Mirrors the `TrackingState` union in types.ts. */
enum class TrackingState(val jsValue: String) {
    IDLE("idle"),
    RUNNING("running"),
    PAUSED("paused"),
    STOPPED("stopped"),
    UNSUPPORTED("unsupported");

    companion object {
        fun from(value: String?): TrackingState =
            entries.firstOrNull { it.jsValue == value } ?: IDLE
    }
}

enum class SensorSource(val jsValue: String) {
    STEP_COUNTER("step_counter"),
    STEP_DETECTOR("step_detector"),
    /** Software pedometer over TYPE_ACCELEROMETER; see [AccelerometerStepDetector]. */
    ACCELEROMETER("accelerometer"),
    NONE("none");

    companion object {
        fun from(value: String?): SensorSource =
            entries.firstOrNull { it.jsValue == value } ?: NONE
    }
}

/** A live view of today. `distance` is metres, `calories` is kcal. */
data class StepSnapshot(
    val date: String,
    val steps: Int,
    val distance: Double,
    val calories: Double,
    val dailyGoal: Int,
    val goalProgress: Double,
    val goalReached: Boolean,
    val state: TrackingState,
    val source: SensorSource,
    val timestamp: Long
)

/**
 * Where a day's totals can be mirrored to. Each sink tracks its own progress:
 * one shared flag lets whichever sink runs first hide the row from the other,
 * so a day uploaded remotely would never reach Health Connect and vice versa.
 */
enum class SyncTarget { HEALTH_CONNECT, REMOTE }

/** One finalised day, as stored in Room and returned by history queries. */
data class DayTotals(
    val date: String,
    val steps: Int,
    val distance: Double,
    val calories: Double,
    /** Mirrored into Health Connect. */
    val synced: Boolean = false,
    /** Uploaded to `remoteSyncUrl`. Always false when no endpoint is configured. */
    val syncedRemote: Boolean = false
)

data class RangeStats(
    val startDate: String,
    val endDate: String,
    val totalSteps: Int,
    val totalDistance: Double,
    val totalCalories: Double,
    val averageSteps: Int,
    val activeDays: Int,
    val bestDay: DayTotals?,
    val days: List<DayTotals>,
    val goal: Int?,
    val goalProgress: Double?
)
