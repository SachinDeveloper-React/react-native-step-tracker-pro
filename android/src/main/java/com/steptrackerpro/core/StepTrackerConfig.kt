package com.steptrackerpro.core

import android.content.Context
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
    /** One of [com.steptrackerpro.health.StepSourcePolicy]'s `jsValue`s. */
    val stepSource: String = "auto",
    /** Pins one Health Connect origin package as the source of truth. */
    val preferredStepSourcePackage: String? = null,
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
    val autoStartOnBoot: Boolean = true,
    /**
     * What to do with steps the hardware counted while the service was dead
     * and the gap crossed midnight. One of `split` (default), `today`, `drop`.
     * See [StepCounterEngine.GapRecovery].
     */
    val gapRecovery: String = "split",
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
    val accelerometerThreshold: Double = AccelerometerStepDetector.DEFAULT_THRESHOLD.toDouble()
) {

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
        privacyPolicyUrl = privacyPolicyUrl?.takeIf { it.isNotBlank() },
        gapRecovery = StepCounterEngine.GapRecovery.from(gapRecovery).jsValue,
        accelerometerThreshold = if (accelerometerThreshold.isFinite()) {
            accelerometerThreshold.coerceIn(0.3, 10.0)
        } else {
            AccelerometerStepDetector.DEFAULT_THRESHOLD.toDouble()
        }
    )

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
        put("stepSource", stepSource)
        put("preferredStepSourcePackage", preferredStepSourcePackage ?: JSONObject.NULL)
        put("privacyPolicyUrl", privacyPolicyUrl ?: JSONObject.NULL)
        put("remoteSyncUrl", remoteSyncUrl ?: JSONObject.NULL)
        put("remoteSyncHeaders", JSONObject(remoteSyncHeaders as Map<*, *>))
        put("remoteSyncAllowHttp", remoteSyncAllowHttp)
        put("autoStartOnBoot", autoStartOnBoot)
        put("gapRecovery", gapRecovery)
        put("watchdogEnabled", watchdogEnabled)
        put("accelerometerFallback", accelerometerFallback)
        put("accelerometerWakeLock", accelerometerWakeLock)
        put("accelerometerThreshold", accelerometerThreshold)
    }

    companion object {
        fun fromJson(json: JSONObject): StepTrackerConfig {
            val fallback = StepTrackerConfig()
            val headers = HashMap<String, String>()
            json.optJSONObject("remoteSyncHeaders")?.let { obj ->
                obj.keys().forEach { key -> headers[key] = obj.optString(key) }
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
                stepSource = json.optString("stepSource", fallback.stepSource),
                preferredStepSourcePackage =
                    json.optStringOrNull("preferredStepSourcePackage"),
                privacyPolicyUrl = json.optStringOrNull("privacyPolicyUrl"),
                remoteSyncUrl = json.optStringOrNull("remoteSyncUrl"),
                remoteSyncHeaders = headers,
                remoteSyncAllowHttp = json.optBoolean("remoteSyncAllowHttp", fallback.remoteSyncAllowHttp),
                autoStartOnBoot = json.optBoolean("autoStartOnBoot", fallback.autoStartOnBoot),
                gapRecovery = json.optString("gapRecovery", fallback.gapRecovery),
                watchdogEnabled = json.optBoolean("watchdogEnabled", fallback.watchdogEnabled),
                accelerometerFallback = json.optBoolean(
                    "accelerometerFallback", fallback.accelerometerFallback
                ),
                accelerometerWakeLock = json.optBoolean(
                    "accelerometerWakeLock", fallback.accelerometerWakeLock
                ),
                accelerometerThreshold = json.optDouble(
                    "accelerometerThreshold", fallback.accelerometerThreshold
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
