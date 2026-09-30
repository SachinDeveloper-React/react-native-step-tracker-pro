package com.steptrackerpro.core

import android.annotation.SuppressLint
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

    /**
     * Epoch millis from which this device has been counting the active day.
     * 0 means "from the start of the day" - the normal case, since with a
     * hardware counter gap recovery reaches back through any dead time to
     * midnight. Set only by a first-ever reading, i.e. an install: a
     * phone-side Health Connect source may then supply the steps taken before
     * that instant, and nothing after it. See StepSourceResolver.
     */
    var coverageStartAt: Long
        get() = prefs.getLong(KEY_COVERAGE_START, 0L)
        set(value) = prefs.edit().putLong(KEY_COVERAGE_START, value.coerceAtLeast(0L)).apply()

    /** Cached total so `getTodaySteps()` is instant even with the service dead. */
    var stepsToday: Int
        get() = prefs.getInt(KEY_STEPS_TODAY, 0)
        set(value) = prefs.edit().putInt(KEY_STEPS_TODAY, value).apply()

    /**
     * Of [stepsToday], how many gap recovery credited in one go - after a
     * kill across midnight, a reboot, or the since-boot claim on install -
     * rather than being observed sample by sample. Written with the counter
     * state so the two cannot drift, zeroed with it at rollover and reset,
     * and stored into `daily_summary.recoveredSteps` with every commit.
     */
    var recoveredToday: Int
        get() = prefs.getInt(KEY_RECOVERED_TODAY, 0)
        set(value) = prefs.edit().putInt(KEY_RECOVERED_TODAY, value.coerceAtLeast(0)).apply()

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

    /**
     * How many times the Health Connect permission sheet has been shown without
     * the user granting anything.
     *
     * Health Connect stops showing the sheet after two refusals for a given
     * permission set and simply returns the current (empty) grant, so a third
     * `requestHealthConnectPermissions()` looks identical to an instant denial.
     * Tracking the count is the only way to tell the two apart and send the
     * user to Health Connect's own settings instead of re-prompting into a
     * dialog that will never appear.
     */
    var healthPermissionDenials: Int
        get() = prefs.getInt(KEY_HC_DENIALS, 0)
        set(value) = prefs.edit().putInt(KEY_HC_DENIALS, value.coerceAtLeast(0)).apply()

    /**
     * Cleared once steps are granted for everything config turns on - distance
     * and calories may still be missing - so a later revoke starts over.
     */
    fun recordHealthPermissionResult(granted: Boolean) {
        healthPermissionDenials = if (granted) 0 else healthPermissionDenials + 1
    }

    /** Package name of the origin the user pinned as their step source. */
    var preferredStepSource: String?
        get() = prefs.getString(KEY_PREFERRED_SOURCE, null)?.takeIf { it.isNotEmpty() }
        set(value) = prefs.edit().putString(KEY_PREFERRED_SOURCE, value ?: "").apply()

    /** Last resolved source, used to fire `stepSourceChanged` only on a flip. */
    var lastResolvedSource: String?
        get() = prefs.getString(KEY_LAST_RESOLVED_SOURCE, null)?.takeIf { it.isNotEmpty() }
        set(value) = prefs.edit().putString(KEY_LAST_RESOLVED_SOURCE, value ?: "").apply()

    // ---- Health Connect continuity ---------------------------------------

    /**
     * The external lead over this device's own count for [continuityDate], as
     * last observed. Under the `auto` policy the number shown is
     * `deviceSteps + continuityOffset`, which is what lets a day that Health
     * Connect reported at 6,000 keep moving to 6,001, 6,005, 6,010 as the
     * phone's own sensor ticks, instead of freezing at 6,000 until the other
     * app next syncs. See [StepContinuity].
     */
    val continuityOffset: Int
        get() = prefs.getInt(KEY_CONTINUITY_OFFSET, 0)

    val continuityDate: String?
        get() = prefs.getString(KEY_CONTINUITY_DATE, null)?.takeIf { it.isNotEmpty() }

    val continuityPackage: String?
        get() = prefs.getString(KEY_CONTINUITY_PACKAGE, null)?.takeIf { it.isNotEmpty() }

    val continuityKind: String?
        get() = prefs.getString(KEY_CONTINUITY_KIND, null)?.takeIf { it.isNotEmpty() }

    val continuityAppName: String?
        get() = prefs.getString(KEY_CONTINUITY_APP_NAME, null)?.takeIf { it.isNotEmpty() }

    /** Epoch millis of the read that last raised the offset. */
    val continuityObservedAt: Long
        get() = prefs.getLong(KEY_CONTINUITY_AT, 0L)

    fun writeContinuity(
        date: String,
        offset: Int,
        packageName: String?,
        kind: String?,
        appName: String?,
        observedAt: Long
    ) {
        prefs.edit()
            .putString(KEY_CONTINUITY_DATE, date)
            .putInt(KEY_CONTINUITY_OFFSET, offset.coerceAtLeast(0))
            .putString(KEY_CONTINUITY_PACKAGE, packageName ?: "")
            .putString(KEY_CONTINUITY_KIND, kind ?: "")
            .putString(KEY_CONTINUITY_APP_NAME, appName ?: "")
            .putLong(KEY_CONTINUITY_AT, observedAt)
            .apply()
    }

    fun clearContinuity() {
        prefs.edit()
            .remove(KEY_CONTINUITY_DATE)
            .remove(KEY_CONTINUITY_OFFSET)
            .remove(KEY_CONTINUITY_PACKAGE)
            .remove(KEY_CONTINUITY_KIND)
            .remove(KEY_CONTINUITY_APP_NAME)
            .remove(KEY_CONTINUITY_AT)
            .apply()
    }

    // ---- service liveness ------------------------------------------------

    /**
     * Written by the service every sensor sample and on a timer while it is
     * alive. The watchdog and the React module compare it against the wall
     * clock to tell "the OEM killed us" from "the user has not moved".
     */
    var lastHeartbeatAt: Long
        get() = prefs.getLong(KEY_HEARTBEAT, 0L)
        set(value) = prefs.edit().putLong(KEY_HEARTBEAT, value).apply()

    /** Epoch millis of the last time something other than the user restarted the service. */
    var lastRecoveryAt: Long
        get() = prefs.getLong(KEY_LAST_RECOVERY_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_RECOVERY_AT, value).apply()

    /** How many times the service has had to be brought back since the last explicit start. */
    var recoveryCount: Int
        get() = prefs.getInt(KEY_RECOVERY_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_RECOVERY_COUNT, value.coerceAtLeast(0)).apply()

    /** Who brought it back last: `sticky`, `boot`, `watchdog`, `foreground`, `initialize`. */
    var lastRecoveryReason: String?
        get() = prefs.getString(KEY_LAST_RECOVERY_REASON, null)?.takeIf { it.isNotEmpty() }
        set(value) = prefs.edit().putString(KEY_LAST_RECOVERY_REASON, value ?: "").apply()

    fun recordRecovery(reason: String, at: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(KEY_LAST_RECOVERY_AT, at)
            .putInt(KEY_RECOVERY_COUNT, recoveryCount + 1)
            .putString(KEY_LAST_RECOVERY_REASON, reason)
            .apply()
    }

    fun resetRecovery() {
        prefs.edit()
            .remove(KEY_LAST_RECOVERY_AT)
            .remove(KEY_RECOVERY_COUNT)
            .remove(KEY_LAST_RECOVERY_REASON)
            .apply()
    }

    /**
     * Brings state written by an earlier version in line with the current
     * rules. Version 2 changed what a continuity baseline may contain (a
     * phone-side source is now bound by coverage), so a baseline from before
     * it is dropped rather than carried until midnight; the next read takes
     * a fresh one. Cheap and idempotent; called once per process.
     */
    fun migrate() {
        val stored = prefs.getInt(KEY_STATE_VERSION, 0)
        if (stored >= STATE_VERSION) return
        if (stored < 2) clearContinuity()
        // Version 3 tells a stop the user asked for from a tracker that never
        // started. Before it only the tracking state said so; a stopped
        // tracker read then is taken as stopped by the user.
        if (stored < 3 && !prefs.contains(KEY_STOPPED_BY_USER)) {
            stoppedByUser = trackingState == TrackingState.STOPPED && !shouldAutoStart
        }
        prefs.edit().putInt(KEY_STATE_VERSION, STATE_VERSION).apply()
    }

    // ---- remote sync outcome ------------------------------------------

    /** What remote uploads last did. See [RemoteSyncStatus]. */
    fun remoteSyncStatus(): RemoteSyncStatus {
        val failedAt = prefs.getLong(KEY_REMOTE_FAIL_AT, 0L)
        val failure = if (failedAt > 0L) {
            RemoteSyncStatus.Failure(
                at = failedAt,
                reason = prefs.getString(KEY_REMOTE_FAIL_REASON, null) ?: RemoteSyncStatus.HTTP_ERROR,
                status = prefs.getInt(KEY_REMOTE_FAIL_STATUS, NO_STATUS).takeIf { it != NO_STATUS },
                message = prefs.getString(KEY_REMOTE_FAIL_MESSAGE, null).orEmpty(),
                retryable = prefs.getBoolean(KEY_REMOTE_FAIL_RETRYABLE, false),
                generation = prefs.getLong(KEY_REMOTE_FAIL_GENERATION, 0L),
                attempt = prefs.getLong(KEY_REMOTE_FAIL_ATTEMPT, 0L)
            )
        } else {
            null
        }
        return RemoteSyncStatus(
            lastAttemptAt = prefs.getLong(KEY_REMOTE_ATTEMPT_AT, 0L),
            lastSuccessAt = prefs.getLong(KEY_REMOTE_SUCCESS_AT, 0L),
            consecutiveFailures = prefs.getInt(KEY_REMOTE_FAILURES, 0),
            lastFailure = failure,
            credentialsGeneration = prefs.getLong(KEY_REMOTE_CREDENTIALS_GENERATION, 0L),
            lastSuccessAttempt = prefs.getLong(KEY_REMOTE_SUCCESS_ATTEMPT, 0L)
        )
    }

    /**
     * Opens an upload attempt: its place in the sequence, and the credentials
     * generation it reads. Taken before config is read, so credentials that
     * change while it runs belong to a later generation than the attempt.
     */
    @Synchronized
    fun beginRemoteAttempt(): RemoteAttempt {
        val attempt = prefs.getLong(KEY_REMOTE_ATTEMPT_SEQUENCE, 0L) + 1L
        prefs.edit().putLong(KEY_REMOTE_ATTEMPT_SEQUENCE, attempt).apply()
        return RemoteAttempt(
            attempt = attempt,
            generation = prefs.getLong(KEY_REMOTE_CREDENTIALS_GENERATION, 0L),
            startedAt = System.currentTimeMillis()
        )
    }

    /** An upload attempt in flight; see [beginRemoteAttempt]. */
    data class RemoteAttempt(val attempt: Long, val generation: Long, val startedAt: Long)

    /** An accepted upload. The last failure is kept, as history. */
    @Synchronized
    fun recordRemoteSuccess(attempt: RemoteAttempt, at: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(KEY_REMOTE_ATTEMPT_AT, attempt.startedAt)
            .putLong(KEY_REMOTE_SUCCESS_AT, at)
            .putLong(
                KEY_REMOTE_SUCCESS_ATTEMPT,
                maxOf(attempt.attempt, prefs.getLong(KEY_REMOTE_SUCCESS_ATTEMPT, 0L))
            )
            .putInt(KEY_REMOTE_FAILURES, 0)
            .apply()
    }

    /** A failed or refused upload. */
    @Synchronized
    fun recordRemoteFailure(
        attempt: RemoteAttempt,
        reason: String,
        status: Int?,
        message: String,
        retryable: Boolean
    ) {
        prefs.edit()
            .putLong(KEY_REMOTE_ATTEMPT_AT, attempt.startedAt)
            .putInt(KEY_REMOTE_FAILURES, prefs.getInt(KEY_REMOTE_FAILURES, 0) + 1)
            .putLong(KEY_REMOTE_FAIL_AT, attempt.startedAt)
            .putString(KEY_REMOTE_FAIL_REASON, reason)
            .putInt(KEY_REMOTE_FAIL_STATUS, status ?: NO_STATUS)
            .putString(KEY_REMOTE_FAIL_MESSAGE, message)
            .putBoolean(KEY_REMOTE_FAIL_RETRYABLE, retryable)
            .putLong(KEY_REMOTE_FAIL_GENERATION, attempt.generation)
            .putLong(KEY_REMOTE_FAIL_ATTEMPT, attempt.attempt)
            .apply()
    }

    /** The upload credentials changed: config's URL, headers or auth mode, or the signing key. */
    @Synchronized
    fun recordRemoteCredentialsChanged() {
        prefs.edit()
            .putLong(
                KEY_REMOTE_CREDENTIALS_GENERATION,
                prefs.getLong(KEY_REMOTE_CREDENTIALS_GENERATION, 0L) + 1L
            )
            .apply()
    }

    /**
     * Steps late samples carried for days that had already closed, owed to
     * those days until the core adds them: `yyyy-MM-dd` to steps. Written in
     * the same edit as the counter state that left them out of the active
     * day, so a process death between the two can lose neither.
     */
    val lateSteps: Map<String, Int>
        get() = decodeLateSteps(prefs.getString(KEY_LATE_STEPS, null))

    /**
     * [lateSteps], cleared. The clear is committed to disk before this
     * returns: the caller then writes them to their days, and a death after
     * that write must not find them owed again.
     */
    @Synchronized
    @SuppressLint("ApplySharedPref") // The disk write before returning is the point.
    fun takeLateSteps(): Map<String, Int> {
        val owed = lateSteps
        if (owed.isNotEmpty()) prefs.edit().remove(KEY_LATE_STEPS).commit()
        return owed
    }

    /** Replaces [lateSteps] outright. */
    fun putLateSteps(owed: Map<String, Int>) {
        prefs.edit().putString(KEY_LATE_STEPS, encodeLateSteps(owed)).apply()
    }

    /**
     * The user stopped tracking with `stopTracking()` and has not started it
     * since, so the background jobs that stop cancelled stay cancelled. A
     * tracker that never started - an app that only reads Health Connect, a
     * user who never allowed activity recognition - is not stopped, and
     * keeps them.
     */
    var stoppedByUser: Boolean
        get() = prefs.getBoolean(KEY_STOPPED_BY_USER, false)
        set(value) = prefs.edit().putBoolean(KEY_STOPPED_BY_USER, value).apply()

    /** One atomic write for the hot path, instead of nine separate commits. */
    fun writeCounterState(
        bootId: Long,
        anchorValue: Float,
        anchorSteps: Int,
        lastRaw: Float,
        activeDate: String,
        stepsToday: Int,
        lastEventAt: Long,
        lastElapsed: Long,
        recoveredToday: Int,
        /** Replaces [lateSteps] when given; left as it is otherwise. */
        lateSteps: Map<String, Int>? = null
    ) {
        val edit = prefs.edit()
            .putLong(KEY_BOOT_ID, bootId)
            .putFloat(KEY_ANCHOR_VALUE, anchorValue)
            .putInt(KEY_ANCHOR_STEPS, anchorSteps)
            .putFloat(KEY_LAST_RAW, lastRaw)
            .putString(KEY_ACTIVE_DATE, activeDate)
            .putInt(KEY_STEPS_TODAY, stepsToday)
            .putLong(KEY_LAST_EVENT_AT, lastEventAt)
            .putLong(KEY_LAST_ELAPSED, lastElapsed)
            .putInt(KEY_RECOVERED_TODAY, recoveredToday.coerceAtLeast(0))
        if (lateSteps != null) edit.putString(KEY_LATE_STEPS, encodeLateSteps(lateSteps))
        edit.apply()
    }

    companion object {
        const val PREFS_NAME = "StepTrackerProState"
        const val BOOT_DRIFT_TOLERANCE_MS = 60_000L
        const val STATE_VERSION = 3
        private const val KEY_STATE_VERSION = "state_version"
        private const val KEY_LATE_STEPS = "late_steps"
        private const val KEY_STOPPED_BY_USER = "stopped_by_user"

        /** `2026-09-29=312;2026-09-28=5`. Anything unreadable is dropped rather than guessed at. */
        fun encodeLateSteps(owed: Map<String, Int>): String =
            owed.filterValues { it > 0 }.entries.joinToString(";") { (day, steps) -> "$day=$steps" }

        fun decodeLateSteps(text: String?): Map<String, Int> {
            if (text.isNullOrEmpty()) return emptyMap()
            val out = LinkedHashMap<String, Int>()
            for (entry in text.split(';')) {
                val day = entry.substringBefore('=', "")
                val steps = entry.substringAfter('=', "").toIntOrNull() ?: continue
                if (day.length == 10 && steps > 0) out[day] = (out[day] ?: 0) + steps
            }
            return out
        }

        private const val KEY_BOOT_ID = "boot_id"
        private const val KEY_ANCHOR_VALUE = "anchor_value"
        private const val KEY_ANCHOR_STEPS = "anchor_steps"
        private const val KEY_LAST_RAW = "last_raw_value"
        private const val KEY_LAST_ELAPSED = "last_elapsed_realtime"
        private const val KEY_ACTIVE_DATE = "active_date"
        private const val KEY_STEPS_TODAY = "steps_today"
        private const val KEY_RECOVERED_TODAY = "recovered_today"
        private const val KEY_COVERAGE_START = "coverage_start_at"
        private const val KEY_STATE = "tracking_state"
        private const val KEY_SOURCE = "sensor_source"
        private const val KEY_LAST_EVENT_AT = "last_event_at"
        private const val KEY_AUTO_START = "should_auto_start"
        private const val KEY_LAST_HC_SYNC = "last_hc_sync_at"
        private const val KEY_HC_VERSION = "hc_record_version"
        private const val KEY_HC_DENIALS = "hc_permission_denials"
        private const val KEY_PREFERRED_SOURCE = "preferred_step_source"
        private const val KEY_LAST_RESOLVED_SOURCE = "last_resolved_step_source"
        private const val KEY_CONTINUITY_DATE = "continuity_date"
        private const val KEY_CONTINUITY_OFFSET = "continuity_offset"
        private const val KEY_CONTINUITY_PACKAGE = "continuity_package"
        private const val KEY_CONTINUITY_KIND = "continuity_kind"
        private const val KEY_CONTINUITY_APP_NAME = "continuity_app_name"
        private const val KEY_CONTINUITY_AT = "continuity_observed_at"
        private const val KEY_HEARTBEAT = "service_heartbeat_at"
        private const val KEY_LAST_RECOVERY_AT = "last_recovery_at"
        private const val KEY_RECOVERY_COUNT = "recovery_count"
        private const val KEY_LAST_RECOVERY_REASON = "last_recovery_reason"
        private const val KEY_REMOTE_ATTEMPT_AT = "remote_last_attempt_at"
        private const val KEY_REMOTE_SUCCESS_AT = "remote_last_success_at"
        private const val KEY_REMOTE_FAILURES = "remote_consecutive_failures"
        private const val KEY_REMOTE_FAIL_AT = "remote_failure_at"
        private const val KEY_REMOTE_FAIL_REASON = "remote_failure_reason"
        private const val KEY_REMOTE_FAIL_STATUS = "remote_failure_status"
        private const val KEY_REMOTE_FAIL_MESSAGE = "remote_failure_message"
        private const val KEY_REMOTE_FAIL_RETRYABLE = "remote_failure_retryable"
        private const val KEY_REMOTE_CREDENTIALS_GENERATION = "remote_credentials_generation"
        private const val KEY_REMOTE_ATTEMPT_SEQUENCE = "remote_attempt_sequence"
        private const val KEY_REMOTE_SUCCESS_ATTEMPT = "remote_success_attempt"
        private const val KEY_REMOTE_FAIL_GENERATION = "remote_failure_generation"
        private const val KEY_REMOTE_FAIL_ATTEMPT = "remote_failure_attempt"

        /** No HTTP status stored. */
        private const val NO_STATUS = Int.MIN_VALUE
    }
}
