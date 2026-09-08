package com.steptrackerpro

import android.app.Activity
import android.content.Intent
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import com.steptrackerpro.core.DateKeys
import com.steptrackerpro.core.StepTrackerConfig
import com.steptrackerpro.core.StepTrackerCore
import com.steptrackerpro.core.TrackingState
import com.steptrackerpro.health.HealthConnectManager
import com.steptrackerpro.health.HealthPermissionActivity
import com.steptrackerpro.service.ServiceCommands
import com.steptrackerpro.sync.SyncScheduler
import com.steptrackerpro.util.Bridge
import com.steptrackerpro.util.optBoolean
import com.steptrackerpro.util.optDouble
import com.steptrackerpro.util.optInt
import com.steptrackerpro.util.optLong
import com.steptrackerpro.util.optString
import com.steptrackerpro.util.BatteryOptimizationHelper
import com.steptrackerpro.util.PermissionHelper
import com.steptrackerpro.util.StepEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The JS-facing surface. Deliberately thin: it validates arguments, delegates
 * to [StepTrackerCore] or the service, and converts results for the bridge.
 * Nothing here is required for counting to continue.
 */
class StepTrackerProModule(private val reactContext: ReactApplicationContext) :
    StepTrackerProSpec(reactContext), LifecycleEventListener {

    private val core = StepTrackerCore.get(reactContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listenerCount = 0

    private val subscriber = StepEventBus.Subscriber { name, payload -> emit(name, payload) }

    init {
        StepEventBus.subscribe(subscriber)
        reactContext.addLifecycleEventListener(this)
    }

    override fun getName(): String = NAME

    override fun invalidate() {
        StepEventBus.unsubscribe(subscriber)
        reactContext.removeLifecycleEventListener(this)
        scope.cancel()
        super.invalidate()
    }

    override fun onHostResume() = Unit
    override fun onHostPause() = Unit
    override fun onHostDestroy() = Unit

    // ---- events ----------------------------------------------------------

    @ReactMethod
    override fun addListener(eventName: String) {
        listenerCount++
    }

    @ReactMethod
    override fun removeListeners(count: Double) {
        listenerCount = (listenerCount - count.toInt()).coerceAtLeast(0)
    }

    private fun emit(name: String, payload: Map<String, Any?>) {
        if (!reactContext.hasActiveReactInstance()) return
        runCatching {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("$EVENT_PREFIX$name", Bridge.map(payload))
        }
    }

    // ---- lifecycle -------------------------------------------------------

    @ReactMethod
    override fun initialize(config: ReadableMap, promise: Promise) {
        runSafely(promise) {
            val merged = mergeConfig(core.config(), config)
            core.updateConfig(merged)
            core.engine.reconcile()
            if (core.state.trackingState == TrackingState.RUNNING) {
                // The service may have been killed while JS was gone. This has
                // to be START: every other command starts the service without
                // registering a sensor listener, which would leave the tracker
                // reporting itself as running while counting nothing.
                ServiceCommands.start(reactContext)
            }
            promise.resolve(Bridge.snapshot(core.engine.snapshot()))
        }
    }

    @ReactMethod
    override fun updateConfig(config: ReadableMap, promise: Promise) {
        runSafely(promise) {
            val merged = mergeConfig(core.config(), config)
            core.updateConfig(merged)
            sendIfTracking(ServiceCommands.ACTION_CONFIG_CHANGED)
            promise.resolve(Bridge.map(configToMap(merged)))
        }
    }

    @ReactMethod
    override fun getConfig(promise: Promise) {
        runSafely(promise) { promise.resolve(Bridge.map(configToMap(core.config()))) }
    }

    @ReactMethod
    override fun startTracking(promise: Promise) {
        runSafely(promise) {
            if (!PermissionHelper.canStartTracking(reactContext)) {
                promise.reject(
                    "E_PERMISSION_DENIED",
                    "ACTIVITY_RECOGNITION must be granted before tracking can start"
                )
                return@runSafely
            }
            val capabilities = PermissionHelper.capabilities(reactContext)
            if (capabilities["supported"] != true) {
                promise.reject("E_NO_SENSOR", "No step sensor on this device")
                return@runSafely
            }
            ServiceCommands.start(reactContext)
            promise.resolve(Bridge.snapshot(core.engine.snapshot()))
        }
    }

    @ReactMethod
    override fun pauseTracking(promise: Promise) {
        runSafely(promise) {
            if (!core.state.shouldAutoStart) {
                promise.reject("E_NOT_TRACKING", "Tracking is not running")
                return@runSafely
            }
            ServiceCommands.send(reactContext, ServiceCommands.ACTION_PAUSE)
            promise.resolve(Bridge.snapshot(core.engine.snapshot()))
        }
    }

    @ReactMethod
    override fun resumeTracking(promise: Promise) {
        runSafely(promise) {
            if (!core.state.shouldAutoStart) {
                promise.reject("E_NOT_TRACKING", "Tracking is not running")
                return@runSafely
            }
            ServiceCommands.send(reactContext, ServiceCommands.ACTION_RESUME)
            promise.resolve(Bridge.snapshot(core.engine.snapshot()))
        }
    }

    @ReactMethod
    override fun stopTracking(promise: Promise) {
        runSafely(promise) {
            ServiceCommands.send(reactContext, ServiceCommands.ACTION_STOP)
            SyncScheduler.cancelAll(reactContext)
            promise.resolve(Bridge.snapshot(core.engine.snapshot()))
        }
    }

    @ReactMethod
    override fun getTrackingState(promise: Promise) {
        runSafely(promise) {
            val snapshot = core.engine.snapshot()
            promise.resolve(
                Bridge.map(
                    mapOf(
                        "state" to snapshot.state.jsValue,
                        "source" to snapshot.source.jsValue
                    )
                )
            )
        }
    }

    @ReactMethod
    override fun isTracking(promise: Promise) {
        runSafely(promise) {
            promise.resolve(core.state.trackingState == TrackingState.RUNNING)
        }
    }

    // ---- reads -----------------------------------------------------------

    @ReactMethod
    override fun getTodaySteps(promise: Promise) {
        runSafely(promise) {
            core.engine.reconcile()
            promise.resolve(Bridge.snapshot(core.engine.snapshot()))
        }
    }

    @ReactMethod
    override fun getStepsForDate(date: String, promise: Promise) {
        launchSafely(promise) { promise.resolve(Bridge.day(core.dayTotals(date))) }
    }

    @ReactMethod
    override fun getYesterdaySteps(promise: Promise) {
        launchSafely(promise) {
            promise.resolve(Bridge.day(core.repository.getDay(DateKeys.yesterday())))
        }
    }

    @ReactMethod
    override fun getStatsForRange(startDate: String, endDate: String, promise: Promise) {
        launchSafely(promise) {
            promise.resolve(Bridge.stats(core.stats(startDate, endDate, null)))
        }
    }

    @ReactMethod
    override fun getWeeklyStats(options: ReadableMap, promise: Promise) {
        launchSafely(promise) {
            val (start, end) = window(options, 7) { offset -> DateKeys.calendarWeek(offset) }
            promise.resolve(Bridge.stats(core.stats(start, end, core.config().weeklyGoal)))
        }
    }

    @ReactMethod
    override fun getMonthlyStats(options: ReadableMap, promise: Promise) {
        launchSafely(promise) {
            val (start, end) = window(options, 30) { offset -> DateKeys.calendarMonth(offset) }
            promise.resolve(Bridge.stats(core.stats(start, end, core.config().monthlyGoal)))
        }
    }

    @ReactMethod
    override fun getYearlyStats(options: ReadableMap, promise: Promise) {
        launchSafely(promise) {
            val (start, end) = window(options, 365) { offset -> DateKeys.calendarYear(offset) }
            promise.resolve(Bridge.stats(core.stats(start, end, null)))
        }
    }

    @ReactMethod
    override fun getHistory(startDate: String, endDate: String, promise: Promise) {
        launchSafely(promise) {
            val days = core.repository.getRange(startDate, endDate)
            promise.resolve(Bridge.map(mapOf("records" to days.map { day ->
                mapOf(
                    "date" to day.date,
                    "steps" to day.steps,
                    "distance" to day.distance,
                    "calories" to day.calories,
                    "synced" to day.synced,
                    "syncedRemote" to day.syncedRemote
                )
            })))
        }
    }

    // ---- writes ----------------------------------------------------------

    @ReactMethod
    override fun resetToday(promise: Promise) {
        launchSafely(promise) {
            core.engine.resetToday()
            core.goals.reset()
            // Deliberate write: saveDay() refuses to lower a day, which would
            // leave history and the live counter permanently disagreeing.
            core.repository.overwriteDay(core.liveToday())
            sendIfTracking(ServiceCommands.ACTION_REFRESH)
            promise.resolve(true)
        }
    }

    @ReactMethod
    override fun clearHistory(promise: Promise) {
        launchSafely(promise) {
            core.repository.clear()
            promise.resolve(true)
        }
    }

    @ReactMethod
    override fun pruneHistory(retentionDays: Double, promise: Promise) {
        launchSafely(promise) {
            promise.resolve(core.repository.prune(retentionDays.toInt()))
        }
    }

    // ---- permissions -----------------------------------------------------

    @ReactMethod
    override fun checkPermissions(promise: Promise) {
        runSafely(promise) { promise.resolve(Bridge.map(PermissionHelper.status(reactContext))) }
    }

    @ReactMethod
    override fun requestPermissions(promise: Promise) {
        val activity = getCurrentActivity()
        if (activity !is PermissionAwareActivity) {
            promise.reject("E_NO_ACTIVITY", "No foreground activity to attach the dialog to")
            return
        }
        val required = PermissionHelper.required()
            .filter { !PermissionHelper.isGranted(reactContext, it) }
        if (required.isEmpty()) {
            promise.resolve(Bridge.map(PermissionHelper.status(reactContext)))
            return
        }
        val listener = PermissionListener { _, _, _ ->
            promise.resolve(Bridge.map(PermissionHelper.status(reactContext)))
            true
        }
        runCatching {
            activity.requestPermissions(required.toTypedArray(), PERMISSION_REQUEST_CODE, listener)
        }.onFailure { promise.reject("E_UNKNOWN", it.message, it) }
    }

    @ReactMethod
    override fun getDeviceCapabilities(promise: Promise) {
        runSafely(promise) {
            promise.resolve(Bridge.map(PermissionHelper.capabilities(reactContext)))
        }
    }

    @ReactMethod
    override fun openAppSettings(promise: Promise) {
        runSafely(promise) { promise.resolve(PermissionHelper.openAppSettings(reactContext)) }
    }

    // ---- battery ---------------------------------------------------------

    @ReactMethod
    override fun isBatteryOptimizationEnabled(promise: Promise) {
        runSafely(promise) {
            promise.resolve(BatteryOptimizationHelper.isOptimizationEnabled(reactContext))
        }
    }

    @ReactMethod
    override fun requestDisableBatteryOptimization(promise: Promise) {
        runSafely(promise) {
            val host = getCurrentActivity() ?: reactContext
            promise.resolve(BatteryOptimizationHelper.requestExemption(host))
        }
    }

    @ReactMethod
    override fun openBatteryOptimizationSettings(promise: Promise) {
        runSafely(promise) {
            promise.resolve(BatteryOptimizationHelper.openSettings(getCurrentActivity() ?: reactContext))
        }
    }

    @ReactMethod
    override fun openManufacturerAutoStartSettings(promise: Promise) {
        runSafely(promise) {
            promise.resolve(
                BatteryOptimizationHelper.openAutoStartSettings(getCurrentActivity() ?: reactContext)
            )
        }
    }

    // ---- health connect --------------------------------------------------

    @ReactMethod
    override fun getHealthConnectStatus(promise: Promise) {
        launchSafely(promise) { promise.resolve(Bridge.map(core.healthConnect.status())) }
    }

    @ReactMethod
    override fun requestHealthConnectPermissions(promise: Promise) {
        val availability = core.healthConnect.availability()
        if (availability != HealthConnectManager.Availability.AVAILABLE) {
            promise.reject(
                "E_HEALTH_CONNECT_UNAVAILABLE",
                "Health Connect is not available on this device"
            )
            return
        }
        val activity: Activity? = getCurrentActivity()
        if (activity == null) {
            promise.reject("E_NO_ACTIVITY", "No foreground activity to launch the request from")
            return
        }
        // The callback is registered only after the launch succeeds. Registering
        // first left it armed when startActivity threw: the promise was rejected
        // here and then resolved again by the next request's result, which
        // React Native rejects as a second invocation of a one-shot callback.
        val settled = AtomicBoolean(false)
        HealthPermissionActivity.Callbacks.expect()
        runCatching {
            activity.startActivity(
                Intent(activity, HealthPermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }.onSuccess {
            HealthPermissionActivity.Callbacks.await { _ ->
                if (settled.compareAndSet(false, true)) {
                    scope.launch {
                        runCatching { promise.resolve(Bridge.map(core.healthConnect.status())) }
                    }
                }
            }
        }.onFailure { error ->
            if (settled.compareAndSet(false, true)) {
                promise.reject("E_UNKNOWN", error.message, error)
            }
        }
    }

    @ReactMethod
    override fun openHealthConnectSettings(promise: Promise) {
        runSafely(promise) {
            runCatching {
                reactContext.startActivity(core.healthConnect.settingsIntent())
                promise.resolve(true)
            }.onFailure { promise.resolve(false) }
        }
    }

    @ReactMethod
    override fun readHealthConnectSteps(startIso: String, endIso: String, promise: Promise) {
        launchSafely(promise) {
            val days = core.healthConnect.readDailySteps(
                Instant.parse(startIso),
                Instant.parse(endIso)
            )
            promise.resolve(
                Bridge.map(
                    mapOf(
                        "totalSteps" to days.sumOf { it.steps },
                        "records" to days.map { day ->
                            mapOf(
                                "date" to day.date,
                                "steps" to day.steps,
                                "distance" to day.distance,
                                "calories" to day.calories,
                                "synced" to true,
                                "syncedRemote" to false
                            )
                        }
                    )
                )
            )
        }
    }

    @ReactMethod
    override fun writeHealthConnectSteps(date: String, promise: Promise) {
        launchSafely(promise) {
            promise.resolve(core.healthConnect.writeDay(core.dayTotals(date)))
        }
    }

    @ReactMethod
    override fun syncWithHealthConnect(promise: Promise) {
        launchSafely(promise) { promise.resolve(Bridge.map(core.syncHealthConnect())) }
    }

    // ---- sync ------------------------------------------------------------

    @ReactMethod
    override fun getPendingSyncCount(promise: Promise) {
        launchSafely(promise) { promise.resolve(core.repository.countUnsynced()) }
    }

    @ReactMethod
    override fun syncNow(promise: Promise) {
        launchSafely(promise) {
            val results = ArrayList<Map<String, Any?>>()
            results.add(core.syncHealthConnect())
            val config = core.config()
            if (!config.remoteSyncUrl.isNullOrEmpty()) {
                SyncScheduler.runNow(reactContext, config)
                results.add(
                    mapOf(
                        "target" to "remote",
                        "syncedRecords" to 0,
                        "failedRecords" to 0,
                        "success" to true,
                        "error" to "Queued: listen for syncCompleted"
                    )
                )
            }
            promise.resolve(Bridge.map(mapOf("results" to results)))
        }
    }

    // ---- helpers ---------------------------------------------------------

    /**
     * Sends a service command only when tracking is actually on. Commands go out
     * through startForegroundService(), which starts the service to deliver
     * them, so sending one to a stopped tracker would leave a foreground service
     * and its notification running for something the user turned off.
     */
    private fun sendIfTracking(action: String) {
        if (core.state.shouldAutoStart) ServiceCommands.send(reactContext, action)
    }

    private inline fun runSafely(promise: Promise, block: () -> Unit) {
        runCatching { block() }.onFailure { promise.reject("E_UNKNOWN", it.message, it) }
    }

    private fun launchSafely(promise: Promise, block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onFailure { promise.reject("E_UNKNOWN", it.message, it) }
        }
    }

    private fun window(
        options: ReadableMap?,
        rollingDays: Int,
        calendar: (Int) -> Pair<String, String>
    ): Pair<String, String> {
        val mode = options?.optString("mode", "calendar") ?: "calendar"
        val offset = options?.optInt("offset", 0) ?: 0
        return if (mode == "rolling") DateKeys.rolling(rollingDays) else calendar(offset)
    }

    private fun mergeConfig(current: StepTrackerConfig, patch: ReadableMap): StepTrackerConfig =
        current.copy(
            heightCm = patch.optDouble("height", current.heightCm),
            weightKg = patch.optDouble("weight", current.weightKg),
            strideLengthM = patch.optDouble("strideLength", current.strideLengthM),
            sex = patch.optString("sex", current.sex) ?: current.sex,
            dailyGoal = patch.optInt("dailyGoal", current.dailyGoal),
            weeklyGoal = patch.optInt("weeklyGoal", current.weeklyGoal),
            monthlyGoal = patch.optInt("monthlyGoal", current.monthlyGoal),
            calorieCoefficient = patch.optDouble(
                "calorieCoefficient", current.calorieCoefficient
            ),
            historyRetentionDays = patch.optInt(
                "historyRetentionDays", current.historyRetentionDays
            ),
            notificationTitle = patch.optString("notificationTitle", current.notificationTitle),
            notificationText = patch.optString("notificationText", current.notificationText),
            notificationIcon = patch.optString("notificationIcon", current.notificationIcon),
            notificationChannelName = patch.optString(
                "notificationChannelName", current.notificationChannelName
            ),
            notificationActions = patch.optBoolean(
                "notificationActions", current.notificationActions
            ),
            notificationThrottleMs = patch.optLong(
                "notificationThrottleMs", current.notificationThrottleMs
            ),
            eventThrottleMs = patch.optLong("eventThrottleMs", current.eventThrottleMs),
            persistEveryNSteps = patch.optInt("persistEveryNSteps", current.persistEveryNSteps),
            healthConnectEnabled = patch.optBoolean(
                "healthConnectEnabled", current.healthConnectEnabled
            ),
            healthConnectSyncIntervalMinutes = patch.optInt(
                "healthConnectSyncIntervalMinutes", current.healthConnectSyncIntervalMinutes
            ),
            remoteSyncUrl = patch.optString("remoteSyncUrl", current.remoteSyncUrl),
            remoteSyncHeaders = if (patch.hasKey("remoteSyncHeaders")) {
                Bridge.stringMap(patch.getMap("remoteSyncHeaders"))
            } else {
                current.remoteSyncHeaders
            },
            autoStartOnBoot = patch.optBoolean("autoStartOnBoot", current.autoStartOnBoot)
        )

    private fun configToMap(config: StepTrackerConfig): Map<String, Any?> = mapOf(
        "height" to config.heightCm,
        "weight" to config.weightKg,
        "strideLength" to config.strideLengthM,
        "sex" to config.sex,
        "dailyGoal" to config.dailyGoal,
        "weeklyGoal" to config.weeklyGoal,
        "monthlyGoal" to config.monthlyGoal,
        "calorieCoefficient" to config.calorieCoefficient,
        "historyRetentionDays" to config.historyRetentionDays,
        "notificationActions" to config.notificationActions,
        "healthConnectEnabled" to config.healthConnectEnabled,
        "healthConnectSyncIntervalMinutes" to config.healthConnectSyncIntervalMinutes,
        "remoteSyncUrl" to config.remoteSyncUrl,
        "autoStartOnBoot" to config.autoStartOnBoot
    )

    companion object {
        const val NAME = "StepTrackerPro"
        const val EVENT_PREFIX = "StepTrackerPro:"
        private const val PERMISSION_REQUEST_CODE = 7731
    }
}
