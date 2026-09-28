package com.steptrackerpro.core

import android.content.Context

/**
 * Fires each goal at most once per period. The "already fired" marker is the
 * period's start date, so a new week or month rearms automatically and a
 * process restart cannot double fire. Next to it is the goal that fired:
 * raising the goal mid-period fires again when the new one is reached, and
 * lowering it below what was already celebrated does not.
 */
class GoalTracker(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Reached(val type: String, val goal: Int, val steps: Int, val periodKey: String)

    fun checkDaily(date: String, steps: Int, goal: Int): Reached? =
        check(TYPE_DAILY, date, steps, goal)

    fun checkWeekly(steps: Int, goal: Int): Reached? =
        check(TYPE_WEEKLY, DateKeys.calendarWeek().first, steps, goal)

    fun checkMonthly(steps: Int, goal: Int): Reached? =
        check(TYPE_MONTHLY, DateKeys.calendarMonth().first, steps, goal)

    /** Emits progress only when the whole-percent bucket changes. */
    fun progressBucketChanged(type: String, percent: Int): Boolean {
        val key = "$KEY_BUCKET_PREFIX$type"
        if (prefs.getInt(key, -1) == percent) return false
        prefs.edit().putInt(key, percent).apply()
        return true
    }

    /** True when this goal has already been reported for the given period. */
    fun alreadyFired(type: String, periodKey: String): Boolean =
        prefs.getString("$KEY_FIRED_PREFIX$type", null) == periodKey

    /**
     * Rearms today's goal. Deliberately does not clear the weekly and monthly
     * markers: those are period-scoped, and wiping them when a single day is
     * reset lets both fire a second time inside the same week or month.
     */
    fun reset() {
        prefs.edit()
            .remove("$KEY_FIRED_PREFIX$TYPE_DAILY")
            .remove("$KEY_FIRED_GOAL_PREFIX$TYPE_DAILY")
            .remove("$KEY_BUCKET_PREFIX$TYPE_DAILY")
            .apply()
    }

    private fun check(type: String, periodKey: String, steps: Int, goal: Int): Reached? {
        if (goal <= 0 || steps < goal) return null
        val key = "$KEY_FIRED_PREFIX$type"
        val goalKey = "$KEY_FIRED_GOAL_PREFIX$type"
        if (prefs.getString(key, null) == periodKey) {
            // A marker from before 2.3.1 has no goal next to it: treat it as
            // covering any goal, so the upgrade itself never fires twice.
            val celebrated = prefs.getInt(goalKey, Int.MAX_VALUE)
            if (goal <= celebrated) return null
        }
        prefs.edit().putString(key, periodKey).putInt(goalKey, goal).apply()
        return Reached(type, goal, steps, periodKey)
    }

    companion object {
        const val PREFS_NAME = "StepTrackerProGoals"
        const val TYPE_DAILY = "daily"
        const val TYPE_WEEKLY = "weekly"
        const val TYPE_MONTHLY = "monthly"
        private const val KEY_FIRED_PREFIX = "fired_"
        private const val KEY_FIRED_GOAL_PREFIX = "fired_goal_"
        private const val KEY_BUCKET_PREFIX = "bucket_"
    }
}
