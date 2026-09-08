package com.steptrackerpro.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Distance and calorie estimation.
 *
 * Distance = steps × stride length, where stride length defaults to
 * height × 0.415 (male) / 0.413 (female) / 0.414 (unspecified).
 *
 * Calories use the standard walking approximation
 * `kcal = coefficient × bodyMassKg × distanceKm`, with a default coefficient
 * of 0.57 kcal/kg/km. This is gross expenditure at a typical walking pace; it
 * is an estimate, not a measurement, and it does not model incline or speed.
 */
class MetricsCalculator(@Volatile var config: StepTrackerConfig) {

    /** Metres. */
    fun distance(steps: Int): Double = max(0, steps) * effectiveStride()

    /** Kilocalories. */
    fun calories(steps: Int): Double {
        val km = distance(steps) / 1000.0
        return config.calorieCoefficient * config.weightKg * km
    }

    fun totals(date: String, steps: Int, synced: Boolean = false): DayTotals =
        DayTotals(date, steps, distance(steps), calories(steps), synced)

    fun goalProgress(steps: Int, goal: Int): Double {
        if (goal <= 0) return 0.0
        return min(1.0, max(0.0, steps.toDouble() / goal.toDouble()))
    }

    fun goalPercent(steps: Int, goal: Int): Int = (goalProgress(steps, goal) * 100).roundToInt()

    private fun effectiveStride(): Double {
        if (config.strideLengthM > 0.0) return config.strideLengthM
        val coefficient = when (config.sex) {
            "male" -> 0.415
            "female" -> 0.413
            else -> 0.414
        }
        return (config.heightCm * coefficient) / 100.0
    }
}
