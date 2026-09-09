package com.steptrackerpro.util

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.steptrackerpro.core.DayTotals
import com.steptrackerpro.core.RangeStats
import com.steptrackerpro.core.StepSnapshot
import com.steptrackerpro.health.StepSourceResolver

/** Conversions between package models and the React Native bridge. */
object Bridge {

    fun snapshot(snapshot: StepSnapshot): WritableMap = Arguments.createMap().apply {
        putString("date", snapshot.date)
        putInt("steps", snapshot.steps)
        putDouble("distance", round(snapshot.distance, 2))
        putDouble("calories", round(snapshot.calories, 2))
        putInt("dailyGoal", snapshot.dailyGoal)
        putDouble("goalProgress", round(snapshot.goalProgress, 4))
        putBoolean("goalReached", snapshot.goalReached)
        putString("state", snapshot.state.jsValue)
        putString("source", snapshot.source.jsValue)
        putDouble("timestamp", snapshot.timestamp.toDouble())
    }

    /**
     * The snapshot as JS sees it once a Health Connect source has been picked.
     *
     * `state` and `source` still describe this device's sensor even when the
     * numbers came from a watch: they answer "is the foreground service
     * running", which stays a real and separate question. Where the count came
     * from is in `stepSource`.
     */
    fun snapshot(
        live: StepSnapshot,
        resolution: StepSourceResolver.Resolution
    ): WritableMap = snapshot(live).apply {
        if (!resolution.usedExternal) {
            putMap("stepSource", map(resolution.toMap()))
            return@apply
        }
        val totals = resolution.totals
        putInt("steps", totals.steps)
        putDouble("distance", round(totals.distance, 2))
        putDouble("calories", round(totals.calories, 2))
        // Goal progress has to follow the number being displayed, or a user on
        // 12,000 watch steps sees a half-full ring driven by the phone's 5,000.
        val goal = live.dailyGoal
        val progress = if (goal > 0) {
            (totals.steps.toDouble() / goal).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        putDouble("goalProgress", round(progress, 4))
        putBoolean("goalReached", goal > 0 && totals.steps >= goal)
        putMap("stepSource", map(resolution.toMap()))
    }

    fun resolvedDay(resolution: StepSourceResolver.Resolution): WritableMap =
        day(resolution.totals).apply {
            putMap("stepSource", map(resolution.toMap()))
        }

    fun snapshotMap(snapshot: StepSnapshot): Map<String, Any?> = mapOf(
        "date" to snapshot.date,
        "steps" to snapshot.steps,
        "distance" to round(snapshot.distance, 2),
        "calories" to round(snapshot.calories, 2),
        "dailyGoal" to snapshot.dailyGoal,
        "goalProgress" to round(snapshot.goalProgress, 4),
        "goalReached" to snapshot.goalReached,
        "state" to snapshot.state.jsValue,
        "source" to snapshot.source.jsValue,
        "timestamp" to snapshot.timestamp
    )

    fun snapshotMap(
        snapshot: StepSnapshot,
        resolution: StepSourceResolver.Resolution
    ): Map<String, Any?> = snapshotMap(snapshot) + mapOf(
        "stepSource" to resolution.toMap()
    )

    fun day(totals: DayTotals): WritableMap = Arguments.createMap().apply {
        putString("date", totals.date)
        putInt("steps", totals.steps)
        putDouble("distance", round(totals.distance, 2))
        putDouble("calories", round(totals.calories, 2))
        putBoolean("synced", totals.synced)
        putBoolean("syncedRemote", totals.syncedRemote)
    }

    fun days(list: List<DayTotals>): WritableArray = Arguments.createArray().apply {
        list.forEach { pushMap(day(it)) }
    }

    fun stats(stats: RangeStats): WritableMap = Arguments.createMap().apply {
        putString("startDate", stats.startDate)
        putString("endDate", stats.endDate)
        putInt("totalSteps", stats.totalSteps)
        putDouble("totalDistance", round(stats.totalDistance, 2))
        putDouble("totalCalories", round(stats.totalCalories, 2))
        putInt("averageSteps", stats.averageSteps)
        putInt("activeDays", stats.activeDays)
        if (stats.bestDay != null) putMap("bestDay", day(stats.bestDay)) else putNull("bestDay")
        putArray("days", days(stats.days))
        stats.goal?.let { putInt("goal", it) }
        stats.goalProgress?.let { putDouble("goalProgress", round(it, 4)) }
    }

    fun map(values: Map<String, Any?>): WritableMap = Arguments.createMap().apply {
        values.forEach { (key, value) ->
            when (value) {
                null -> putNull(key)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putDouble(key, value.toDouble())
                is Float -> putDouble(key, value.toDouble())
                is Double -> putDouble(key, value)
                is String -> putString(key, value)
                is List<*> -> putArray(key, list(value))
                is Map<*, *> -> putMap(key, map(value.entries.associate {
                    it.key.toString() to it.value
                }))
                else -> putString(key, value.toString())
            }
        }
    }

    private fun list(values: List<*>): WritableArray = Arguments.createArray().apply {
        values.forEach { value ->
            when (value) {
                null -> pushNull()
                is Boolean -> pushBoolean(value)
                is Int -> pushInt(value)
                is Long -> pushDouble(value.toDouble())
                is Float -> pushDouble(value.toDouble())
                is Double -> pushDouble(value)
                is String -> pushString(value)
                is List<*> -> pushArray(list(value))
                is Map<*, *> -> pushMap(map(value.entries.associate {
                    it.key.toString() to it.value
                }))
                // Mirrors map(): anything else crosses the bridge as its
                // toString rather than being dropped.
                else -> pushString(value.toString())
            }
        }
    }

    fun stringMap(source: ReadableMap?): Map<String, String> {
        if (source == null) return emptyMap()
        val out = HashMap<String, String>()
        val iterator = source.keySetIterator()
        while (iterator.hasNextKey()) {
            val key = iterator.nextKey()
            if (source.getType(key) == ReadableType.String) {
                source.getString(key)?.let { out[key] = it }
            }
        }
        return out
    }

    private fun round(value: Double, decimals: Int): Double {
        if (value.isNaN() || value.isInfinite()) return 0.0
        var factor = 1.0
        repeat(decimals) { factor *= 10 }
        return Math.round(value * factor) / factor
    }
}

// Top-level so they can be imported and used outside this file. Extensions
// declared inside an object are members and are not importable.

fun ReadableMap.optDouble(key: String, fallback: Double): Double =
    if (hasKey(key) && !isNull(key)) getDouble(key) else fallback

fun ReadableMap.optInt(key: String, fallback: Int): Int =
    if (hasKey(key) && !isNull(key)) getInt(key) else fallback

fun ReadableMap.optLong(key: String, fallback: Long): Long =
    if (hasKey(key) && !isNull(key)) getDouble(key).toLong() else fallback

fun ReadableMap.optBoolean(key: String, fallback: Boolean): Boolean =
    if (hasKey(key) && !isNull(key)) getBoolean(key) else fallback

fun ReadableMap.optString(key: String, fallback: String?): String? =
    if (hasKey(key) && !isNull(key)) getString(key) else fallback

fun ReadableArray.toStringList(): List<String> =
    (0 until size()).mapNotNull { getString(it) }
