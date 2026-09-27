package com.steptrackerpro.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Immutable snapshot of user config. Persisted as JSON so the foreground
 * service can rebuild it after a process restart without touching JS.
 */
data class StepTrackerConfig(
    val heightCm: Double = 170.0,
    val weightKg: Double = 70.0,
    /**
     * Explicit override in metres. Zero means "derive from height and sex" -
     * a non-zero default here made [MetricsCalculator.effectiveStride]'s
     * documented fallback unreachable, so height never affected distance.
     */
    val strideLengthM: Double = 0.0,
    val sex: String = "unspecified",
    val dailyGoal: Int = 10_000,
    val weeklyGoal: Int = 70_000,
    val monthlyGoal: Int = 300_000,
    val calorieCoefficient: Double = 0.57,
    val historyRetentionDays: Int = 35,
    val notificationTitle: String? = null,
    val notificationText: String? = null,
    val notificationIcon: String? = null,
    val notificationChannelName: String? = null,
    val notificationActions: Boolean = true,
    val notificationThrottleMs: Long = 1_000L,
    val eventThrottleMs: Long = 500L,
    val persistEveryNSteps: Int = 10,
    val healthConnectEnabled: Boolean = true,
    val healthConnectSyncIntervalMinutes: Int = 30,
    /**
     * Read other apps' steps back out of Health Connect. Off means the
     * `READ_*` permissions are never requested and every policy behaves like
     * `device` - the shape of an app that only mirrors its own count out.
     */
    val healthConnectReadEnabled: Boolean = true,
    /**
     * Mirror this device's counts into Health Connect. Turning it off leaves
     * reads working, which is the right shape for an app that only wants to
     * display a watch's numbers without adding a second copy of its own. Off
     * also means the `WRITE_*` permissions are never requested.
     */
    val healthConnectWriteEnabled: Boolean = true,
    /** Ask for `READ_HEALTH_DATA_IN_BACKGROUND` alongside the required set. */
    val healthConnectBackgroundRead: Boolean = false,
    /** Ask for `READ_HEALTH_DATA_HISTORY`, needed to read past 30 days. */
    val healthConnectHistoryRead: Boolean = false,
    /**
     * Subtract manually entered steps (`RECORDING_METHOD_MANUAL_ENTRY`) from
     * every Health Connect source before the resolver picks a winner. Off by
     * default, because for a display app a user's typed-in correction is
     * legitimate data; on for an app that pays per step, where it is the
     * easiest way to fake a day. The records are still read and still
     * reported per source - only the resolved number leaves them out.
     */
    val healthConnectIgnoreManualEntries: Boolean = false,
    /** One of [com.steptrackerpro.health.StepSourcePolicy]'s `jsValue`s. */
    val stepSource: String = "auto",
    /** Pins one Health Connect origin package as the source of truth. */
    val preferredStepSourcePackage: String? = null,
    /**
     * One of [com.steptrackerpro.health.WearableTrust]'s `jsValue`s. What
     * earns a Health Connect source the `auto` policy's whole-margin trust:
     * the `Device` stamp on its records (`metadata`, the default and the
     * behaviour of every earlier release), or membership of the catalog or
     * [wearableAllowlist] (`catalog`). Display classification is unaffected.
     */
    val wearableTrust: String = "metadata",
    /**
     * Packages trusted as wearables under `wearableTrust: 'catalog'` on top
     * of the built-in catalog - a companion app the catalog has not caught up
     * with, or one the app's own users are known to wear.
     */
    val wearableAllowlist: List<String> = emptyList(),
    /**
     * Opened by [com.steptrackerpro.health.HealthPrivacyPolicyActivity] when
     * Health Connect asks why the app wants health data. Health Connect and
     * Play both require this link to exist.
     */
    val privacyPolicyUrl: String? = null,
    val remoteSyncUrl: String? = null,
    val remoteSyncHeaders: Map<String, String> = emptyMap(),
    /**
     * Permit a plain `http://` remote endpoint. Off by default: step data is
     * health data, and the worker refuses to send it in the clear unless the
     * app has opted in - for a local development server, say.
     */
    val remoteSyncAllowHttp: Boolean = false,
    /**
     * One of [com.steptrackerpro.sync.RemotePayload.Shape]'s `jsValue`s.
     * `totals` (default) is the record shape every earlier release sent;
     * `full` adds this device's own count, the recovered share, the resolved
     * source and the unresolved Health Connect origins per record.
     */
    val remoteSyncPayload: String = "totals",
    val autoStartOnBoot: Boolean = true,
    /**
     * What to do with steps the hardware counted while the service was dead
     * and the gap crossed midnight. One of `split` (default), `today`,
     * `today_capped`, `drop`. See [StepCounterEngine.GapRecovery].
     */
    val gapRecovery: String = "split",
    /**
     * Under `today_capped`, the most one recovery may credit to the active
     * day; the rest is dropped. A closed day never changes and one day can
     * never be handed a week of counter.
     */
    val gapRecoveryMaxSteps: Int = StepCounterEngine.DEFAULT_GAP_RECOVERY_MAX_STEPS,
    /**
     * Re-launch the service from a periodic WorkManager job when it is found
     * dead while tracking is supposed to be on. Needed on OEM skins that kill
     * foreground services and ignore START_STICKY.
     */
    val watchdogEnabled: Boolean = true,
    /**
     * Count with a software pedometer over the accelerometer when the device
     * has neither step sensor. Costs battery - the CPU has to stay awake to
     * sample - so it is only ever a last resort, never a preference.
     */
    val accelerometerFallback: Boolean = true,
    /**
     * Hold a partial wake lock while sampling the accelerometer, so counting
     * continues with the screen off on devices whose accelerometer is not a
     * wake-up sensor. Off means steps stop with the screen on those devices.
     */
    val accelerometerWakeLock: Boolean = true,
    /** Peak linear acceleration, m/s², that counts as a step. */
    val accelerometerThreshold: Double = AccelerometerStepDetector.DEFAULT_THRESHOLD.toDouble(),
    /**
     * Sample the accelerometer for one short window every few minutes while
     * steps are accruing, and store a handful of features describing the
     * motion - never the samples. Off by default: it is an extra sensor
     * registration and, on a non-wake-up accelerometer, a wake lock for the
     * window, and only an app that verifies steps server-side has a use for
     * the result. JS presents these three as `motionSampling: { enabled,
     * windowSeconds, intervalMinutes }`.
     */
    val motionSamplingEnabled: Boolean = false,
    val motionWindowSeconds: Int = MotionDefaults.WINDOW_SECONDS,
    val motionIntervalMinutes: Int = MotionDefaults.INTERVAL_MINUTES,
    /** How many windows to keep. 288 is a day at five-minute intervals. */
    val motionWindowRetention: Int = MotionDefaults.RETENTION,
    /**
     * Integrity checks: per-minute step buckets, the event log, and the
     * detector that flags shaken phones, swing gadgets, charging, vehicles
     * and implausible cadence or volume. Off by default - an app that does
     * not pay for steps has no use for it, and it is one more table write per
     * commit. JS presents these as `fraudDetection: { enabled, mode,
     * maxCadenceSpm, steadyCadenceMinutes, maxContinuousMinutes,
     * maxDailySteps, flagWhileCharging, activityRecognition }`.
     */
    val fraudDetectionEnabled: Boolean = false,
    /** One of [IntegrityMode]'s `jsValue`s: `flag` (default) or `exclude`. */
    val fraudMode: String = IntegrityMode.FLAG.jsValue,
    val fraudMaxCadenceSpm: Int = IntegrityRules.DEFAULT_MAX_CADENCE_SPM,
    val fraudSteadyCadenceMinutes: Int = IntegrityRules.DEFAULT_STEADY_MINUTES,
    val fraudMaxContinuousMinutes: Int = IntegrityRules.DEFAULT_MAX_CONTINUOUS_MINUTES,
    val fraudMaxDailySteps: Int = IntegrityRules.DEFAULT_MAX_DAILY_STEPS,
    val fraudFlagWhileCharging: Boolean = true,
    /**
     * Tag steps with Google's Activity Recognition state (still, in a
     * vehicle, ...). Only takes effect when the host app ships
     * `com.google.android.gms:play-services-location`; this package declares
     * it compile-only so an app that does not want Play Services never gets it.
     */
    val fraudActivityRecognition: Boolean = false
) {

    val integrityMode: IntegrityMode get() = IntegrityMode.from(fraudMode)

    /** True when flagged steps come out of every number shown, synced and resolved. */
    val excludeSuspect: Boolean get() = fraudDetectionEnabled && integrityMode == IntegrityMode.EXCLUDE

    fun integrityRules(): IntegrityRules = IntegrityRules(
        maxCadenceSpm = fraudMaxCadenceSpm,
        steadyCadenceMinutes = fraudSteadyCadenceMinutes,
        maxContinuousMinutes = fraudMaxContinuousMinutes,
        maxDailySteps = fraudMaxDailySteps,
        flagWhileCharging = fraudFlagWhileCharging
    )

    /**
     * Clamps every numeric field into a range the rest of the package can rely
     * on. The TypeScript layer validates a few of these, but it is not the only
     * way in: config is also rebuilt from persisted JSON after a process
     * restart, and the native module can be called directly. A negative
     * `calorieCoefficient` would report negative calories, a zero
     * `persistEveryNSteps` a database write per step, and a negative throttle
     * an event to JS per step.
     */
    fun sanitised(): StepTrackerConfig = copy(
        heightCm = heightCm.coerceIn(50.0, 260.0),
        weightKg = weightKg.coerceIn(10.0, 400.0),
        // Zero is meaningful: it means "derive from height and sex".
        strideLengthM = if (strideLengthM.isFinite()) strideLengthM.coerceIn(0.0, 3.0) else 0.0,
        dailyGoal = dailyGoal.coerceAtLeast(0),
        weeklyGoal = weeklyGoal.coerceAtLeast(0),
        monthlyGoal = monthlyGoal.coerceAtLeast(0),
        calorieCoefficient = if (calorieCoefficient.isFinite()) {
            calorieCoefficient.coerceIn(0.0, 10.0)
        } else {
            StepTrackerConfig().calorieCoefficient
        },
        historyRetentionDays = historyRetentionDays.coerceAtLeast(1),
        notificationThrottleMs = notificationThrottleMs.coerceAtLeast(0L),
        eventThrottleMs = eventThrottleMs.coerceAtLeast(0L),
        persistEveryNSteps = persistEveryNSteps.coerceAtLeast(1),
        healthConnectSyncIntervalMinutes = healthConnectSyncIntervalMinutes.coerceAtLeast(0),
        // An unrecognised policy falls back to the default rather than
        // disabling resolution: config also arrives from persisted JSON written
        // by an older version, where the key may be absent or spelled
        // differently, and silently counting nothing would be worse.
        stepSource = com.steptrackerpro.health.StepSourcePolicy.from(stepSource).jsValue,
        preferredStepSourcePackage = preferredStepSourcePackage?.takeIf { it.isNotBlank() },
        wearableTrust = com.steptrackerpro.health.WearableTrust.from(wearableTrust).jsValue,
        wearableAllowlist = wearableAllowlist.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        privacyPolicyUrl = privacyPolicyUrl?.takeIf { it.isNotBlank() },
        remoteSyncPayload = com.steptrackerpro.sync.RemotePayload.Shape.from(remoteSyncPayload).jsValue,
        gapRecovery = StepCounterEngine.GapRecovery.from(gapRecovery).jsValue,
        gapRecoveryMaxSteps = gapRecoveryMaxSteps.coerceAtLeast(0),
        accelerometerThreshold = if (accelerometerThreshold.isFinite()) {
            accelerometerThreshold.coerceIn(0.3, 10.0)
        } else {
            AccelerometerStepDetector.DEFAULT_THRESHOLD.toDouble()
        },
        // A window longer than the sampler keeps is pointless; an interval
        // under a minute is a poll, not a signature.
        motionWindowSeconds = motionWindowSeconds.coerceIn(1, MotionDefaults.MAX_WINDOW_SECONDS),
        motionIntervalMinutes = motionIntervalMinutes.coerceAtLeast(1),
        motionWindowRetention = motionWindowRetention.coerceAtLeast(1),
        // Zero turns a check off; anything else is held to a range where the
        // check still means something. A cadence cap of 50 would flag every
        // walk, a steady run of two minutes every treadmill.
        fraudMode = IntegrityMode.from(fraudMode).jsValue,
        fraudMaxCadenceSpm = offOr(fraudMaxCadenceSpm) { it.coerceIn(100, 400) },
        fraudSteadyCadenceMinutes = offOr(fraudSteadyCadenceMinutes) { it.coerceIn(5, 24 * 60) },
        fraudMaxContinuousMinutes = offOr(fraudMaxContinuousMinutes) { it.coerceIn(30, 24 * 60) },
        fraudMaxDailySteps = offOr(fraudMaxDailySteps) { it.coerceAtLeast(1_000) }
    )

    private inline fun offOr(value: Int, clamp: (Int) -> Int): Int = if (value <= 0) 0 else clamp(value)

    fun toJson(): JSONObject = JSONObject().apply {
        put("height", heightCm)
        put("weight", weightKg)
        put("strideLength", strideLengthM)
        put("sex", sex)
        put("dailyGoal", dailyGoal)
        put("weeklyGoal", weeklyGoal)
        put("monthlyGoal", monthlyGoal)
        put("calorieCoefficient", calorieCoefficient)
        put("historyRetentionDays", historyRetentionDays)
        put("notificationTitle", notificationTitle ?: JSONObject.NULL)
        put("notificationText", notificationText ?: JSONObject.NULL)
        put("notificationIcon", notificationIcon ?: JSONObject.NULL)
        put("notificationChannelName", notificationChannelName ?: JSONObject.NULL)
        put("notificationActions", notificationActions)
        put("notificationThrottleMs", notificationThrottleMs)
        put("eventThrottleMs", eventThrottleMs)
        put("persistEveryNSteps", persistEveryNSteps)
        put("healthConnectEnabled", healthConnectEnabled)
        put("healthConnectSyncIntervalMinutes", healthConnectSyncIntervalMinutes)
        put("healthConnectReadEnabled", healthConnectReadEnabled)
        put("healthConnectWriteEnabled", healthConnectWriteEnabled)
        put("healthConnectBackgroundRead", healthConnectBackgroundRead)
        put("healthConnectHistoryRead", healthConnectHistoryRead)
        put("healthConnectIgnoreManualEntries", healthConnectIgnoreManualEntries)
        put("stepSource", stepSource)
        put("preferredStepSourcePackage", preferredStepSourcePackage ?: JSONObject.NULL)
        put("wearableTrust", wearableTrust)
        put("wearableAllowlist", JSONArray(wearableAllowlist))
        put("privacyPolicyUrl", privacyPolicyUrl ?: JSONObject.NULL)
        put("remoteSyncUrl", remoteSyncUrl ?: JSONObject.NULL)
        put("remoteSyncHeaders", JSONObject(remoteSyncHeaders as Map<*, *>))
        put("remoteSyncAllowHttp", remoteSyncAllowHttp)
        put("remoteSyncPayload", remoteSyncPayload)
        put("autoStartOnBoot", autoStartOnBoot)
        put("gapRecovery", gapRecovery)
        put("gapRecoveryMaxSteps", gapRecoveryMaxSteps)
        put("watchdogEnabled", watchdogEnabled)
        put("accelerometerFallback", accelerometerFallback)
        put("accelerometerWakeLock", accelerometerWakeLock)
        put("accelerometerThreshold", accelerometerThreshold)
        put("motionSamplingEnabled", motionSamplingEnabled)
        put("motionWindowSeconds", motionWindowSeconds)
        put("motionIntervalMinutes", motionIntervalMinutes)
        put("motionWindowRetention", motionWindowRetention)
        put("fraudDetectionEnabled", fraudDetectionEnabled)
        put("fraudMode", fraudMode)
        put("fraudMaxCadenceSpm", fraudMaxCadenceSpm)
        put("fraudSteadyCadenceMinutes", fraudSteadyCadenceMinutes)
        put("fraudMaxContinuousMinutes", fraudMaxContinuousMinutes)
        put("fraudMaxDailySteps", fraudMaxDailySteps)
        put("fraudFlagWhileCharging", fraudFlagWhileCharging)
        put("fraudActivityRecognition", fraudActivityRecognition)
    }

    companion object {
        fun fromJson(json: JSONObject): StepTrackerConfig {
            val fallback = StepTrackerConfig()
            val headers = HashMap<String, String>()
            json.optJSONObject("remoteSyncHeaders")?.let { obj ->
                obj.keys().forEach { key -> headers[key] = obj.optString(key) }
            }
            val allowlist = ArrayList<String>()
            json.optJSONArray("wearableAllowlist")?.let { arr ->
                for (i in 0 until arr.length()) arr.optString(i)?.let { allowlist.add(it) }
            }
            return StepTrackerConfig(
                heightCm = json.optDouble("height", fallback.heightCm),
                weightKg = json.optDouble("weight", fallback.weightKg),
                strideLengthM = json.optDouble("strideLength", fallback.strideLengthM),
                sex = json.optString("sex", fallback.sex),
                dailyGoal = json.optInt("dailyGoal", fallback.dailyGoal),
                weeklyGoal = json.optInt("weeklyGoal", fallback.weeklyGoal),
                monthlyGoal = json.optInt("monthlyGoal", fallback.monthlyGoal),
                calorieCoefficient = json.optDouble(
                    "calorieCoefficient", fallback.calorieCoefficient
                ),
                historyRetentionDays = json.optInt(
                    "historyRetentionDays", fallback.historyRetentionDays
                ),
                notificationTitle = json.optStringOrNull("notificationTitle"),
                notificationText = json.optStringOrNull("notificationText"),
                notificationIcon = json.optStringOrNull("notificationIcon"),
                notificationChannelName = json.optStringOrNull("notificationChannelName"),
                notificationActions = json.optBoolean(
                    "notificationActions", fallback.notificationActions
                ),
                notificationThrottleMs = json.optLong(
                    "notificationThrottleMs", fallback.notificationThrottleMs
                ),
                eventThrottleMs = json.optLong("eventThrottleMs", fallback.eventThrottleMs),
                persistEveryNSteps = json.optInt(
                    "persistEveryNSteps", fallback.persistEveryNSteps
                ),
                healthConnectEnabled = json.optBoolean(
                    "healthConnectEnabled", fallback.healthConnectEnabled
                ),
                healthConnectSyncIntervalMinutes = json.optInt(
                    "healthConnectSyncIntervalMinutes",
                    fallback.healthConnectSyncIntervalMinutes
                ),
                healthConnectReadEnabled = json.optBoolean(
                    "healthConnectReadEnabled", fallback.healthConnectReadEnabled
                ),
                healthConnectWriteEnabled = json.optBoolean(
                    "healthConnectWriteEnabled", fallback.healthConnectWriteEnabled
                ),
                healthConnectBackgroundRead = json.optBoolean(
                    "healthConnectBackgroundRead", fallback.healthConnectBackgroundRead
                ),
                healthConnectHistoryRead = json.optBoolean(
                    "healthConnectHistoryRead", fallback.healthConnectHistoryRead
                ),
                healthConnectIgnoreManualEntries = json.optBoolean(
                    "healthConnectIgnoreManualEntries",
                    fallback.healthConnectIgnoreManualEntries
                ),
                stepSource = json.optString("stepSource", fallback.stepSource),
                preferredStepSourcePackage =
                    json.optStringOrNull("preferredStepSourcePackage"),
                wearableTrust = json.optString("wearableTrust", fallback.wearableTrust),
                wearableAllowlist = allowlist,
                privacyPolicyUrl = json.optStringOrNull("privacyPolicyUrl"),
                remoteSyncUrl = json.optStringOrNull("remoteSyncUrl"),
                remoteSyncHeaders = headers,
                remoteSyncAllowHttp = json.optBoolean("remoteSyncAllowHttp", fallback.remoteSyncAllowHttp),
                remoteSyncPayload = json.optString("remoteSyncPayload", fallback.remoteSyncPayload),
                autoStartOnBoot = json.optBoolean("autoStartOnBoot", fallback.autoStartOnBoot),
                gapRecovery = json.optString("gapRecovery", fallback.gapRecovery),
                gapRecoveryMaxSteps = json.optInt("gapRecoveryMaxSteps", fallback.gapRecoveryMaxSteps),
                watchdogEnabled = json.optBoolean("watchdogEnabled", fallback.watchdogEnabled),
                accelerometerFallback = json.optBoolean(
                    "accelerometerFallback", fallback.accelerometerFallback
                ),
                accelerometerWakeLock = json.optBoolean(
                    "accelerometerWakeLock", fallback.accelerometerWakeLock
                ),
                accelerometerThreshold = json.optDouble(
                    "accelerometerThreshold", fallback.accelerometerThreshold
                ),
                motionSamplingEnabled = json.optBoolean(
                    "motionSamplingEnabled", fallback.motionSamplingEnabled
                ),
                motionWindowSeconds = json.optInt("motionWindowSeconds", fallback.motionWindowSeconds),
                motionIntervalMinutes = json.optInt(
                    "motionIntervalMinutes", fallback.motionIntervalMinutes
                ),
                motionWindowRetention = json.optInt(
                    "motionWindowRetention", fallback.motionWindowRetention
                ),
                fraudDetectionEnabled = json.optBoolean(
                    "fraudDetectionEnabled", fallback.fraudDetectionEnabled
                ),
                fraudMode = json.optString("fraudMode", fallback.fraudMode),
                fraudMaxCadenceSpm = json.optInt("fraudMaxCadenceSpm", fallback.fraudMaxCadenceSpm),
                fraudSteadyCadenceMinutes = json.optInt(
                    "fraudSteadyCadenceMinutes", fallback.fraudSteadyCadenceMinutes
                ),
                fraudMaxContinuousMinutes = json.optInt(
                    "fraudMaxContinuousMinutes", fallback.fraudMaxContinuousMinutes
                ),
                fraudMaxDailySteps = json.optInt("fraudMaxDailySteps", fallback.fraudMaxDailySteps),
                fraudFlagWhileCharging = json.optBoolean(
                    "fraudFlagWhileCharging", fallback.fraudFlagWhileCharging
                ),
                fraudActivityRecognition = json.optBoolean(
                    "fraudActivityRecognition", fallback.fraudActivityRecognition
                )
            )
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
    }
}

/** Single source of truth for config, shared by the module and the service. */
class ConfigStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cached: StepTrackerConfig? = null

    @Synchronized
    fun get(): StepTrackerConfig {
        cached?.let { return it }
        val raw = prefs.getString(KEY_CONFIG, null)
        val parsed = if (raw.isNullOrEmpty()) {
            StepTrackerConfig()
        } else {
            runCatching { StepTrackerConfig.fromJson(JSONObject(raw)) }
                .getOrElse { StepTrackerConfig() }
        }
        cached = parsed.sanitised()
        return cached!!
    }

    @Synchronized
    fun save(config: StepTrackerConfig): StepTrackerConfig {
        val safe = config.sanitised()
        cached = safe
        prefs.edit().putString(KEY_CONFIG, safe.toJson().toString()).apply()
        return safe
    }

    fun isInitialized(): Boolean = prefs.contains(KEY_CONFIG)

    companion object {
        const val PREFS_NAME = "StepTrackerProConfig"
        private const val KEY_CONFIG = "config_json"
    }
}

/** Defaults for motion signature windows, shared by config and the TypeScript layer's docs. */
object MotionDefaults {
    const val WINDOW_SECONDS = 10
    const val INTERVAL_MINUTES = 5
    /** A day at five-minute intervals. */
    const val RETENTION = 288
    /** The sampler holds a minute at 50 Hz; longer would be resampling, which it does not do. */
    const val MAX_WINDOW_SECONDS = 60
}
