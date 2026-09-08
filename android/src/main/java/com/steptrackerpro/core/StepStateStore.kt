package com.steptrackerpro.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Durable counter state. Kept in SharedPreferences rather than Room because it
 * is written on nearly every sensor batch and must be readable synchronously
 * from `onStartCommand` before any coroutine has a chance to run.
 *
 * Inspect on a debug build with:
 *   adb shell run-as <applicationId> cat shared_prefs/StepTrackerProState.xml
 */
class StepStateStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Approximate boot timestamp. A change larger than [BOOT_DRIFT_TOLERANCE_MS]
     * means the device rebooted and TYPE_STEP_COUNTER restarted from zero.
     */
    var bootId: Long
        get() = prefs.getLong(KEY_BOOT_ID, 0L)
        set(value) = prefs.edit().putLong(KEY_BOOT_ID, value).apply()

    /** Raw sensor reading that corresponds to [anchorSteps]. */
    var anchorValue: Float
        get() = prefs.getFloat(KEY_ANCHOR_VALUE, -1f)
        set(value) = prefs.edit().putFloat(KEY_ANCHOR_VALUE, value).apply()

    /** Steps already attributed to [activeDate] at the anchor point. */
    var anchorSteps: Int
        get() = prefs.getInt(KEY_ANCHOR_STEPS, 0)
        set(value) = prefs.edit().putInt(KEY_ANCHOR_STEPS, value).apply()

    var lastRawValue: Float
        get() = prefs.getFloat(KEY_LAST_RAW, -1f)
        set(value) = prefs.edit().putFloat(KEY_LAST_RAW, value).apply()

    /**
     * `SystemClock.elapsedRealtime()` at the last sample. Unlike the wall clock
     * it cannot be set by the user and it restarts at zero on every boot, so a
     * value below the stored one is *proof* of a reboot rather than the hint
     * [bootId] gives.
     */
    var lastElapsedRealtime: Long
        get() = prefs.getLong(KEY_LAST_ELAPSED, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_ELAPSED, value).apply()

    var activeDate: String
        get() = prefs.getString(KEY_ACTIVE_DATE, null) ?: DateKeys.today()
        set(value) = prefs.edit().putString(KEY_ACTIVE_DATE, value).apply()

    /** Cached total so `getTodaySteps()` is instant even with the service dead. */
    var stepsToday: Int
        get() = prefs.getInt(KEY_STEPS_TODAY, 0)
        set(value) = prefs.edit().putInt(KEY_STEPS_TODAY, value).apply()

    var trackingState: TrackingState
        get() = TrackingState.from(prefs.getString(KEY_STATE, null))
        set(value) = prefs.edit().putString(KEY_STATE, value.jsValue).apply()

    var source: SensorSource
        get() = SensorSource.from(prefs.getString(KEY_SOURCE, null))
        set(value) = prefs.edit().putString(KEY_SOURCE, value.jsValue).apply()

    var lastEventAt: Long
        get() = prefs.getLong(KEY_LAST_EVENT_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_EVENT_AT, value).apply()

    /** True when the user (or the OS) asked us to come back after reboot. */
    var shouldAutoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    var lastHealthSyncAt: Long
        get() = prefs.getLong(KEY_LAST_HC_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_HC_SYNC, value).apply()

    /**
     * Version stamp for Health Connect's `clientRecordVersion`, which resolves
     * same-id collisions by keeping the higher value. It must never go
     * backwards, so it cannot simply be the wall clock: after the user moves
     * the clock back, every write would be silently discarded by the provider.
     * Seeded from the clock so records written by earlier versions of this
     * package are still superseded.
     */
    @Synchronized
    fun nextHealthRecordVersion(): Long {
        val next = maxOf(prefs.getLong(KEY_HC_VERSION, 0L) + 1L, System.currentTimeMillis())
        prefs.edit().putLong(KEY_HC_VERSION, next).apply()
        return next
    }

    /** One atomic write for the hot path, instead of six separate commits. */
    fun writeCounterState(
        bootId: Long,
        anchorValue: Float,
        anchorSteps: Int,
        lastRaw: Float,
        activeDate: String,
        stepsToday: Int,
        lastEventAt: Long,
        lastElapsed: Long
    ) {
        prefs.edit()
            .putLong(KEY_BOOT_ID, bootId)
            .putFloat(KEY_ANCHOR_VALUE, anchorValue)
            .putInt(KEY_ANCHOR_STEPS, anchorSteps)
            .putFloat(KEY_LAST_RAW, lastRaw)
            .putString(KEY_ACTIVE_DATE, activeDate)
            .putInt(KEY_STEPS_TODAY, stepsToday)
            .putLong(KEY_LAST_EVENT_AT, lastEventAt)
            .putLong(KEY_LAST_ELAPSED, lastElapsed)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "StepTrackerProState"
        const val BOOT_DRIFT_TOLERANCE_MS = 60_000L

        private const val KEY_BOOT_ID = "boot_id"
        private const val KEY_ANCHOR_VALUE = "anchor_value"
        private const val KEY_ANCHOR_STEPS = "anchor_steps"
        private const val KEY_LAST_RAW = "last_raw_value"
        private const val KEY_LAST_ELAPSED = "last_elapsed_realtime"
        private const val KEY_ACTIVE_DATE = "active_date"
        private const val KEY_STEPS_TODAY = "steps_today"
        private const val KEY_STATE = "tracking_state"
        private const val KEY_SOURCE = "sensor_source"
        private const val KEY_LAST_EVENT_AT = "last_event_at"
        private const val KEY_AUTO_START = "should_auto_start"
        private const val KEY_LAST_HC_SYNC = "last_hc_sync_at"
        private const val KEY_HC_VERSION = "hc_record_version"
    }
}
