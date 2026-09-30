package com.steptrackerpro

import android.app.Activity
import android.content.Intent
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import com.steptrackerpro.core.DateKeys
import com.steptrackerpro.core.DayTotals
import com.steptrackerpro.core.SnapshotPart
import com.steptrackerpro.core.SyncTarget
import com.steptrackerpro.core.StepTrackerConfig
import com.steptrackerpro.core.StepTrackerCore
import com.steptrackerpro.core.TrackingState
import com.steptrackerpro.health.HealthConnectManager
import com.steptrackerpro.health.HealthPermissionActivity
import com.steptrackerpro.core.IntegrityEvent
import com.steptrackerpro.integrity.DeviceAttestation
import com.steptrackerpro.integrity.PlayIntegrityBridge
import com.steptrackerpro.service.ServiceCommands
import com.steptrackerpro.service.StepTrackerService
import com.steptrackerpro.sync.SyncScheduler
import com.steptrackerpro.util.Bridge
import com.steptrackerpro.util.optBoolean
import com.steptrackerpro.util.optDouble
import com.steptrackerpro.util.optInt
import com.steptrackerpro.util.optLong
import com.steptrackerpro.util.optString
import com.steptrackerpro.util.optStringList
import com.steptrackerpro.util.toStringList
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

    /** Last Health Connect status seen, so onHostResume only emits on a change. */
    @Volatile
    private var lastHealthFingerprint: String? = null

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

    /**
     * Health Connect grants can be revoked from outside the app, and the
     * provider can be installed or updated while the app is backgrounded.
     * Nothing notifies us, so the status is re-read on every foreground and an
     * event fires when it moved - which is also how a UI learns that the watch
     * the user just paired now has data to offer.
     */
    override fun onHostResume() {
        // The app being in the foreground is the one moment a background
        // start restriction cannot apply, so this is where a service the OEM
        // killed while the app was closed gets brought back. The hardware
        // counter kept counting in the meantime; the first sample after the
        // restart reconciles everything it saw.
        recoverServiceIfDead("foreground")

        scope.launch {
            runCatching {
                val config = core.config()
                if (!config.healthConnectEnabled) return@runCatching
                val status = core.healthConnect.status(core.permissionScope())
                val fingerprint = "${status["availability"]}|${status["granted"]}|" +
                    "${status["canRead"]}|${status["canWrite"]}"
                if (fingerprint != lastHealthFingerprint) {
                    lastHealthFingerprint = fingerprint
                    StepEventBus.emit(
                        StepEventBus.Events.HEALTH_CONNECT_STATUS_CHANGED, status
                    )
                }
            }
        }
    }

    private fun recoverServiceIfDead(reason: String) {
        if (!core.isInitialized()) return
        if (!core.serviceLooksDead()) return
        if (!PermissionHelper.canStartTracking(reactContext)) return
        core.engine.reconcile()
        ServiceCommands.tryStart(reactContext, recoveredBy = reason)
    }

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

    /**
     * Every event goes out through the codegen-typed emitters (`onStepsChanged`
     * and so on) on the new architecture; the old-architecture shim
     * implements the same `emitOn…` methods over `RCTDeviceEventEmitter` with
     * the `StepTrackerPro:` prefix, which is what the JS side subscribes to
     * there.
     */
    private fun emit(name: String, payload: Map<String, Any?>) {
        if (!reactContext.hasActiveReactInstance()) return
        runCatching {
            val value = Bridge.map(payload)
            when (name) {
                StepEventBus.Events.STEPS_CHANGED -> emitOnStepsChanged(value)
                StepEventBus.Events.GOAL_REACHED -> emitOnGoalReached(value)
                StepEventBus.Events.GOAL_PROGRESS_CHANGED -> emitOnGoalProgressChanged(value)
                StepEventBus.Events.TRACKING_STATE_CHANGED -> emitOnTrackingStateChanged(value)
                StepEventBus.Events.DAY_CHANGED -> emitOnDayChanged(value)
                StepEventBus.Events.HISTORY_BACKFILLED -> emitOnHistoryBackfilled(value)
                StepEventBus.Events.MOTION_WINDOW -> emitOnMotionWindow(value)
                StepEventBus.Events.SUSPICIOUS_ACTIVITY -> emitOnSuspiciousActivity(value)
                StepEventBus.Events.SYNC_COMPLETED -> emitOnSyncCompleted(value)
                StepEventBus.Events.SYNC_AUTH_FAILED -> emitOnSyncAuthFailed(value)
                StepEventBus.Events.STEP_SOURCE_CHANGED -> emitOnStepSourceChanged(value)
                StepEventBus.Events.HEALTH_CONNECT_STATUS_CHANGED -> emitOnHealthConnectStatusChanged(value)
                StepEventBus.Events.ERROR -> emitOnError(value)
                else -> Unit
            }
        }
    }

    // ---- lifecycle -------------------------------------------------------

    @ReactMethod
    override fun initialize(config: ReadableMap, promise: Promise) {
        runSafely(promise) {
            val before = core.config()
            val merged = mergeConfig(before, config)
            val saved = core.updateConfig(merged)
            core.engine.reconcile()
            // A live service picks up what changed - receivers for the
            // integrity checks, the motion schedule - without waiting for a
            // restart. Only on a real change: this runs on every app launch.
            if (saved != before && StepTrackerService.isAlive) {
                sendIfTracking(ServiceCommands.ACTION_CONFIG_CHANGED)
            }
            if (core.serviceLooksDead() && PermissionHelper.canStartTracking(reactContext)) {
                // The service may have been killed while JS was gone. This has
                // to be START: every other command starts the service without
                // registering a sensor listener, which would leave the tracker
                // reporting itself as running while counting nothing. Left
                // alone when it is demonstrably alive, so a JS reload does not
                // re-run the start path for nothing.
                ServiceCommands.tryStart(reactContext, recoveredBy = "initialize")
            }
            promise.resolve(cachedSnapshot())
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
            val capabilities = PermissionHelper.capabilities(
                reactContext, allowAccelerometer = core.config().accelerometerFallback
            )
            if (capabilities["supported"] != true) {
                promise.reject("E_NO_SENSOR", "No step sensor on this device")
                return@runSafely
            }
            // The user wants tracking, and the background work that comes
            // with it, back; the service schedules it as it starts.
            core.state.stoppedByUser = false
            ServiceCommands.start(reactContext)
            promise.resolve(cachedSnapshot())
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
            promise.resolve(cachedSnapshot())
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
            promise.resolve(cachedSnapshot())
        }
    }

    @ReactMethod
    override fun stopTracking(promise: Promise) {
        runSafely(promise) {
            // The decision is recorded here, before the command: the OS may
            // refuse startForegroundService() while the app is in the
            // background, and a stop that only lived in that command left
            // shouldAutoStart on - tracking came back at the next launch or
            // reboot. A live service that misses the command stops itself on
            // its next heartbeat.
            val wasAlive = StepTrackerService.isAlive
            core.state.shouldAutoStart = false
            core.state.trackingState = TrackingState.STOPPED
            // What keeps the next launch's initialize() from scheduling the
            // background work cancelled below all over again.
            core.state.stoppedByUser = true
            core.flush()
            ServiceCommands.send(reactContext, ServiceCommands.ACTION_STOP)
            SyncScheduler.cancelAll(reactContext)
            // The service announces its own stop; with none running, nobody
            // else will.
            if (!wasAlive) core.emitTrackingState("stopped")
            promise.resolve(cachedSnapshot())
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

    /**
     * Whether the tracker is actually working, as opposed to merely marked
     * running: service liveness, heartbeat age, how often it has had to be
     * recovered and by what. An app on a Xiaomi shows its "allow autostart"
     * prompt off `recoveryCount` and `looksDead`, not off a guess.
     */
    @ReactMethod
    override fun getTrackingHealth(promise: Promise) {
        runSafely(promise) {
            // Reading health is also the moment to act on it: a dead service
            // seen from the foreground can be started from the foreground.
            recoverServiceIfDead("foreground")
            promise.resolve(Bridge.map(core.trackingHealth()))
        }
    }

    // ---- reads -----------------------------------------------------------

    /**
     * Reads go through [StepTrackerCore.resolveToday] rather than the engine
     * directly, so a watch that counted more than this phone is what the user
     * sees. Under the default `auto` policy with no Health Connect grant this
     * is exactly the engine snapshot, so nothing changes for an app that never
     * touches Health Connect.
     */
    @ReactMethod
    override fun getTodaySteps(promise: Promise) {
        launchSafely(promise) {
            core.engine.reconcile()
            val resolution = core.resolveToday()
            promise.resolve(Bridge.snapshot(core.engine.snapshot(), resolution))
        }
    }

    @ReactMethod
    override fun getStepsForDate(date: String, promise: Promise) {
        launchSafely(promise) {
            promise.resolve(Bridge.resolvedDay(core.resolveDay(date)))
        }
    }

    @ReactMethod
    override fun getYesterdaySteps(promise: Promise) {
        launchSafely(promise) {
            promise.resolve(Bridge.resolvedDay(core.resolveDay(DateKeys.yesterday())))
        }
    }

    @ReactMethod
    override fun getStatsForRange(startDate: String, endDate: String, promise: Promise) {
        launchSafely(promise) {
            promise.resolve(Bridge.stats(core.resolvedStats(startDate, endDate, null)))
        }
    }

    @ReactMethod
    override fun getWeeklyStats(options: ReadableMap, promise: Promise) {
        launchSafely(promise) {
            val (start, end) = window(options, 7) { offset -> DateKeys.calendarWeek(offset) }
            promise.resolve(
                Bridge.stats(core.resolvedStats(start, end, core.config().weeklyGoal))
            )
        }
    }

    @ReactMethod
    override fun getMonthlyStats(options: ReadableMap, promise: Promise) {
        launchSafely(promise) {
            val (start, end) = window(options, 30) { offset -> DateKeys.calendarMonth(offset) }
            promise.resolve(
                Bridge.stats(core.resolvedStats(start, end, core.config().monthlyGoal))
            )
        }
    }

    @ReactMethod
    override fun getYearlyStats(options: ReadableMap, promise: Promise) {
        launchSafely(promise) {
            val (start, end) = window(options, 365) { offset -> DateKeys.calendarYear(offset) }
            promise.resolve(Bridge.stats(core.resolvedStats(start, end, null)))
        }
    }

    @ReactMethod
    override fun getHistory(startDate: String, endDate: String, promise: Promise) {
        launchSafely(promise) {
            val days = core.history(startDate, endDate)
            promise.resolve(Bridge.map(mapOf("records" to days.map { day ->
                mapOf(
                    "date" to day.date,
                    "steps" to day.steps,
                    "distance" to day.distance,
                    "calories" to day.calories,
                    "synced" to day.synced,
                    "syncedRemote" to day.syncedRemote,
                    "recoveredSteps" to day.recoveredSteps,
                    "suspectSteps" to day.suspectSteps
                )
            })))
        }
    }

    /**
     * One call with everything a server needs to judge a day, nothing
     * resolved for it. The date is validated in JS; a malformed one that
     * reaches here fails in DateKeys and rejects like any other bad input.
     */
    @ReactMethod
    override fun getVerificationSnapshot(date: String, options: ReadableMap, promise: Promise) {
        launchSafely(promise) {
            val sign = options.optBoolean("sign", false)
            val nonce = options.optString("nonce", null)?.takeIf { it.isNotEmpty() }
            val include = SnapshotPart.parse(options.optStringList("include").orEmpty())
            val typeNames = options.optStringList("healthConnectRecordTypes")
            val recordTypes = if (typeNames == null) {
                setOf(HealthConnectManager.RecordType.STEPS)
            } else {
                HealthConnectManager.RecordType.parse(typeNames)
            }
            if (include == null || recordTypes == null) {
                promise.reject(
                    "E_INVALID_CONFIG",
                    "include takes minutes, motionWindows and healthConnectRecords; " +
                        "healthConnectRecordTypes takes steps and distance"
                )
                return@launchSafely
            }
            val signedOnly = sign && options.optBoolean("signedOnly", false)
            promise.resolve(
                Bridge.map(core.verificationSnapshot(date, sign, nonce, include, recordTypes, signedOnly))
            )
        }
    }

    /** What the integrity checks found for one day: flags, events, per-minute totals, device hints. */
    @ReactMethod
    override fun getIntegrityReport(date: String, promise: Promise) {
        launchSafely(promise) {
            promise.resolve(Bridge.map(core.integrityReport(date)))
        }
    }

    /** The integrity event log between two dates, inclusive, oldest first. */
    @ReactMethod
    override fun getIntegrityEvents(startDate: String, endDate: String, promise: Promise) {
        launchSafely(promise) {
            val events = core.repository.events(
                DateKeys.startOfDayMillis(startDate), DateKeys.endOfDayMillis(endDate)
            )
            promise.resolve(Bridge.map(mapOf("events" to events.map { it.toMap() })))
        }
    }

    /** Per-minute step buckets between two dates, inclusive, oldest first. Only minutes with steps. */
    @ReactMethod
    override fun getStepMinutes(startDate: String, endDate: String, promise: Promise) {
        launchSafely(promise) {
            // Whatever is still in memory goes to storage first, so the read is current.
            val minutes = core.stepMinutes(startDate, endDate)
            promise.resolve(Bridge.map(mapOf("minutes" to minutes.map { it.toMap() })))
        }
    }

    /**
     * A Play Integrity token bound to [options].requestHash - normally a
     * signed snapshot's `payloadSha256` - for the cloud project in
     * [options].cloudProjectNumber. Needs the app to ship
     * `com.google.android.play:integrity`.
     */
    @ReactMethod
    override fun requestIntegrityToken(options: ReadableMap, promise: Promise) {
        launchSafely(promise) {
            val hash = options.optString("requestHash", null).orEmpty()
            val project = if (options.hasKey("cloudProjectNumber") && !options.isNull("cloudProjectNumber")) {
                options.getDouble("cloudProjectNumber").toLong()
            } else {
                0L
            }
            if (hash.isEmpty() || hash.length > PlayIntegrityBridge.MAX_REQUEST_HASH || project <= 0L) {
                promise.reject(
                    "E_INVALID_CONFIG",
                    "requestHash must be 1-${PlayIntegrityBridge.MAX_REQUEST_HASH} characters and cloudProjectNumber a positive number"
                )
                return@launchSafely
            }
            if (!PlayIntegrityBridge.isAvailable()) {
                promise.reject(
                    "E_INTEGRITY_UNAVAILABLE",
                    "Add com.google.android.play:integrity to your app's dependencies to use Play Integrity"
                )
                return@launchSafely
            }
            try {
                val token = PlayIntegrityBridge.requestToken(reactContext, project, hash)
                promise.resolve(Bridge.map(mapOf("token" to token, "requestHash" to hash)))
            } catch (failure: PlayIntegrityBridge.Failure) {
                rejectIntegrity(promise, failure)
            }
        }
    }

    /**
     * Prepares the Play Integrity token provider for a cloud project ahead
     * of the first [requestIntegrityToken], which otherwise pays for it.
     * Idempotent: a provider already prepared is reused.
     */
    @ReactMethod
    override fun prepareIntegrity(cloudProjectNumber: Double, promise: Promise) {
        launchSafely(promise) {
            val project = cloudProjectNumber.toLong()
            if (project <= 0L || project.toDouble() != cloudProjectNumber) {
                promise.reject("E_INVALID_CONFIG", "cloudProjectNumber must be a positive integer")
                return@launchSafely
            }
            if (!PlayIntegrityBridge.isAvailable()) {
                promise.reject(
                    "E_INTEGRITY_UNAVAILABLE",
                    "Add com.google.android.play:integrity to your app's dependencies to use Play Integrity"
                )
                return@launchSafely
            }
            try {
                PlayIntegrityBridge.prepare(reactContext, project)
                promise.resolve(true)
            } catch (failure: PlayIntegrityBridge.Failure) {
                rejectIntegrity(promise, failure)
            }
        }
    }

    /**
     * `E_INTEGRITY_FAILED` with Play's code in the message and, for code,
     * in `userInfo`: `playErrorCode`, `playError` (Play's name for it) and
     * `retryable` - whether backing off and calling again can succeed.
     */
    private fun rejectIntegrity(promise: Promise, failure: PlayIntegrityBridge.Failure) {
        promise.reject(
            "E_INTEGRITY_FAILED",
            "Play Integrity failed" +
                (failure.errorCode?.let { " (error $it ${failure.errorName})" } ?: "") +
                ": ${failure.message}",
            failure,
            Bridge.map(
                mapOf(
                    "playErrorCode" to failure.errorCode,
                    "playError" to failure.errorName,
                    "retryable" to failure.retryable
                )
            )
        )
    }

    /** Whether the install already has a signing key, so an app attests once rather than every launch. */
    @ReactMethod
    override fun hasAttestationKey(promise: Promise) {
        launchSafely(promise) { promise.resolve(DeviceAttestation.hasAttestedKey(reactContext)) }
    }

    /**
     * The current key's id, public key, chain and security level without
     * replacing it - null when there is none. The chain still carries the
     * challenge it was generated with.
     */
    @ReactMethod
    override fun getAttestationKeyInfo(promise: Promise) {
        launchSafely(promise) {
            promise.resolve(DeviceAttestation.describe(reactContext)?.let { Bridge.map(it) })
        }
    }

    /**
     * Generates a fresh Keystore key bound to the server's challenge and
     * returns its attestation chain. Every later signed snapshot and upload
     * uses this key. The challenge is validated in JS; a bad one reaching
     * here is rejected by the Keystore wrapper like any other bad input.
     */
    @ReactMethod
    override fun attestDevice(challenge: String, promise: Promise) {
        launchSafely(promise) {
            val bytes = challenge.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty() || bytes.size > DeviceAttestation.MAX_CHALLENGE_BYTES) {
                promise.reject(
                    "E_INVALID_CONFIG",
                    "challenge must be 1-${DeviceAttestation.MAX_CHALLENGE_BYTES} bytes of UTF-8"
                )
                return@launchSafely
            }
            val attestation = DeviceAttestation.attest(reactContext, bytes)
            // A new key: a signature-auth refusal is no longer the current state.
            core.state.recordRemoteCredentialsChanged()
            core.integrity.log(
                IntegrityEvent.DEVICE_ATTESTED,
                mapOf("keyId" to attestation["keyId"], "attested" to attestation["attested"])
            )
            promise.resolve(Bridge.map(attestation))
        }
    }

    /** Motion signature windows that opened between the two dates, inclusive, oldest first. */
    @ReactMethod
    override fun getMotionWindows(startDate: String, endDate: String, promise: Promise) {
        launchSafely(promise) {
            val windows = core.motionWindows(startDate, endDate)
            promise.resolve(Bridge.map(mapOf("windows" to windows.map { it.toMap() })))
        }
    }

    // ---- writes ----------------------------------------------------------

    @ReactMethod
    override fun resetToday(promise: Promise) {
        launchSafely(promise) {
            core.resetToday()
            sendIfTracking(ServiceCommands.ACTION_REFRESH)
            promise.resolve(true)
        }
    }

    @ReactMethod
    override fun clearHistory(promise: Promise) {
        launchSafely(promise) {
            core.clearHistory()
            promise.resolve(true)
        }
    }

    @ReactMethod
    override fun pruneHistory(retentionDays: Double, promise: Promise) {
        launchSafely(promise) {
            promise.resolve(core.pruneHistory(retentionDays.toInt()))
        }
    }

    // ---- permissions -----------------------------------------------------

    @ReactMethod
    override fun checkPermissions(promise: Promise) {
        runSafely(promise) { promise.resolve(Bridge.map(PermissionHelper.status(reactContext))) }
    }

    @ReactMethod
    override fun requestPermissions(promise: Promise) {
        val activity = reactContext.currentActivity
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
            promise.resolve(
                Bridge.map(
                    PermissionHelper.capabilities(
                        reactContext, allowAccelerometer = core.config().accelerometerFallback
                    )
                )
            )
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
            val host = reactContext.currentActivity ?: reactContext
            promise.resolve(BatteryOptimizationHelper.requestExemption(host))
        }
    }

    @ReactMethod
    override fun openBatteryOptimizationSettings(promise: Promise) {
        runSafely(promise) {
            promise.resolve(BatteryOptimizationHelper.openSettings(reactContext.currentActivity ?: reactContext))
        }
    }

    @ReactMethod
    override fun openManufacturerAutoStartSettings(promise: Promise) {
        runSafely(promise) {
            promise.resolve(
                BatteryOptimizationHelper.openAutoStartSettings(reactContext.currentActivity ?: reactContext)
            )
        }
    }

    /** Which background restrictions apply here, and which screens exist to lift them. */
    @ReactMethod
    override fun getBackgroundRestrictionStatus(promise: Promise) {
        runSafely(promise) {
            promise.resolve(Bridge.map(BatteryOptimizationHelper.status(reactContext)))
        }
    }

    // ---- health connect --------------------------------------------------

    @ReactMethod
    override fun getHealthConnectStatus(promise: Promise) {
        launchSafely(promise) {
            promise.resolve(Bridge.map(core.healthConnect.status(core.permissionScope())))
        }
    }

    /**
     * Opens the Health Connect permission sheet.
     *
     * Resolves with the post-request status rather than rejecting on a denial:
     * "the user said no" is a normal outcome an app has to render, not an
     * error. Only conditions the user cannot act on from here - no provider, no
     * activity - reject.
     */
    @ReactMethod
    override fun requestHealthConnectPermissions(options: ReadableMap, promise: Promise) {
        val availability = core.healthConnect.availability()
        if (availability != HealthConnectManager.Availability.AVAILABLE) {
            // Distinguishing these lets the caller offer the install button
            // instead of a dead end. See installHealthConnect().
            val code = when (availability) {
                HealthConnectManager.Availability.NOT_INSTALLED ->
                    "E_HEALTH_CONNECT_NOT_INSTALLED"
                HealthConnectManager.Availability.UPDATE_REQUIRED ->
                    "E_HEALTH_CONNECT_UPDATE_REQUIRED"
                else -> "E_HEALTH_CONNECT_UNAVAILABLE"
            }
            promise.reject(code, "Health Connect is ${availability.jsValue} on this device")
            return
        }
        val activity: Activity? = reactContext.currentActivity
        if (activity == null) {
            promise.reject("E_NO_ACTIVITY", "No foreground activity to launch the request from")
            return
        }

        val config = core.config()
        val permissionScope = core.permissionScope(
            backgroundRead = options.optBoolean(
                "backgroundRead", config.healthConnectBackgroundRead
            ),
            historyRead = options.optBoolean(
                "historyRead", config.healthConnectHistoryRead
            )
        )
        val permissions = core.healthConnect.permissionsFor(permissionScope)
        // From 2.0 the app declares its Health Connect permissions itself.
        // One it asks for but never declared would be left off the sheet
        // without a word, which looks exactly like the user refusing.
        val undeclared = core.healthConnect.undeclaredPermissions(permissionScope)
        if (undeclared.isNotEmpty()) {
            promise.reject(
                "E_HEALTH_CONNECT_NOT_DECLARED",
                "Declare these in your app's AndroidManifest.xml (see docs/PERMISSIONS.md): " +
                    undeclared.sorted().joinToString(", ")
            )
            return
        }
        if (permissions.isEmpty()) {
            // Reads and writes both disabled in config: there is nothing to
            // ask for, and launching an empty request would show nothing.
            launchSafely(promise) {
                promise.resolve(Bridge.map(core.healthConnect.status(permissionScope)))
            }
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
                    .putExtra(
                        HealthPermissionActivity.EXTRA_PERMISSIONS,
                        permissions.toTypedArray()
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }.onSuccess {
            HealthPermissionActivity.Callbacks.await { granted ->
                if (settled.compareAndSet(false, true)) {
                    scope.launch {
                        // Counting refusals is what lets a later call tell
                        // "denied just now" from "the sheet no longer opens",
                        // which Health Connect reports identically. Only
                        // steps count: a user who allowed them and unticked
                        // distance has not refused Health Connect.
                        core.state.recordHealthPermissionResult(
                            permissionScope.stepsGranted(granted.toSet())
                        )
                        val status = core.healthConnect.status(permissionScope)
                        StepEventBus.emit(
                            StepEventBus.Events.HEALTH_CONNECT_STATUS_CHANGED, status
                        )
                        runCatching { promise.resolve(Bridge.map(status)) }
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

    /**
     * Sends the user to the Play Store listing for Health Connect. The right
     * response to `availability: 'not_installed'` or `'update_required'`.
     */
    @ReactMethod
    override fun installHealthConnect(promise: Promise) {
        runSafely(promise) {
            runCatching {
                (reactContext.currentActivity ?: reactContext)
                    .startActivity(core.healthConnect.installIntent())
                promise.resolve(true)
            }.onFailure { promise.resolve(false) }
        }
    }

    /**
     * Drops every grant this app holds. Also clears the refusal counter, so the
     * next request starts from a clean sheet rather than being told to go to
     * settings straight away.
     */
    @ReactMethod
    override fun revokeHealthConnectPermissions(promise: Promise) {
        launchSafely(promise) {
            val revoked = core.healthConnect.revokeAll()
            if (revoked) {
                core.state.clearContinuity()
                StepEventBus.emit(
                    StepEventBus.Events.HEALTH_CONNECT_STATUS_CHANGED,
                    core.healthConnect.status(core.permissionScope())
                )
            }
            promise.resolve(revoked)
        }
    }

    /**
     * Every app's steps per day. Like the raw reads, and unlike the resolved
     * ones, a read that cannot happen rejects and says why - it used to
     * resolve with no records, which passed for a window nobody walked in.
     */
    @ReactMethod
    override fun readHealthConnectSteps(startIso: String, endIso: String, promise: Promise) {
        launchSafely(promise) {
            if (!requireHealthConnectRead(promise, setOf(HealthConnectManager.RecordType.STEPS))) {
                return@launchSafely
            }
            healthConnectRead(promise) {
                val days = core.healthConnect.readDailySteps(
                    Instant.parse(startIso),
                    Instant.parse(endIso)
                )
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
                                "syncedRemote" to false,
                                // An aggregate of other apps' records has no
                                // recovered share; the field is there so the
                                // row is a DayRecord like every other.
                                "recoveredSteps" to 0,
                                "suspectSteps" to 0
                            )
                        }
                    )
                )
            }
        }
    }

    /** Every record of the asked-for types in the window, as Health Connect stores it. */
    @ReactMethod
    override fun getHealthConnectRecords(startIso: String, endIso: String, options: ReadableMap, promise: Promise) {
        launchSafely(promise) {
            val types = recordTypes(options, promise) ?: return@launchSafely
            if (!requireHealthConnectRead(promise, types)) return@launchSafely
            healthConnectRead(promise) {
                Bridge.map(core.healthConnect.readRecords(Instant.parse(startIso), Instant.parse(endIso), types))
            }
        }
    }

    /** A change-tracking cursor for the asked-for record types, starting now. */
    @ReactMethod
    override fun getHealthConnectChangesToken(options: ReadableMap, promise: Promise) {
        launchSafely(promise) {
            val types = recordTypes(options, promise) ?: return@launchSafely
            if (!requireHealthConnectRead(promise, types)) return@launchSafely
            healthConnectRead(promise) { core.healthConnect.changesToken(types) }
        }
    }

    /** Records inserted, updated and deleted since a cursor, and the next cursor. */
    @ReactMethod
    override fun getHealthConnectChanges(token: String, promise: Promise) {
        launchSafely(promise) {
            // The token knows its types; steps are in every one this package hands out.
            if (!requireHealthConnectRead(promise, setOf(HealthConnectManager.RecordType.STEPS))) return@launchSafely
            healthConnectRead(promise) { Bridge.map(core.healthConnect.changes(token)) }
        }
    }

    /** `recordTypes` from [options], steps when absent; rejects and returns null on an unknown one. */
    private fun recordTypes(options: ReadableMap, promise: Promise): Set<HealthConnectManager.RecordType>? {
        val names = options.optStringList("recordTypes") ?: return setOf(HealthConnectManager.RecordType.STEPS)
        return HealthConnectManager.RecordType.parse(names) ?: run {
            promise.reject("E_INVALID_CONFIG", "recordTypes takes steps and distance")
            null
        }
    }

    /**
     * Rejects, and returns false, when raw Health Connect reads of [types]
     * cannot work: no provider, or a read grant missing. The resolved reads
     * fall back to this device quietly; a read asked for specifically says
     * why it cannot.
     */
    private suspend fun requireHealthConnectRead(
        promise: Promise,
        types: Set<HealthConnectManager.RecordType>
    ): Boolean {
        if (core.healthConnect.availability() != HealthConnectManager.Availability.AVAILABLE) {
            promise.reject("E_HEALTH_CONNECT_UNAVAILABLE", "Health Connect is not available on this device")
            return false
        }
        if (!core.healthConnect.canReadRecords(types)) {
            promise.reject(
                "E_HEALTH_CONNECT_DENIED",
                "Health Connect read permission is not granted: " +
                    types.joinToString(", ") { it.permission }
            )
            return false
        }
        return true
    }

    /**
     * A refusal rejects as denied, not as unknown: a grant revoked between
     * the check and the read, or the app in the background - no activity on
     * screen and no foreground service - without
     * `READ_HEALTH_DATA_IN_BACKGROUND`. That rule is Health Connect's to
     * apply, so it is not guessed at beforehand: a read it would allow is
     * never refused here.
     */
    private suspend fun healthConnectRead(promise: Promise, read: suspend () -> Any?) {
        try {
            promise.resolve(read())
        } catch (denied: SecurityException) {
            val message = denied.message ?: "Health Connect read permission is not granted"
            promise.reject(
                "E_HEALTH_CONNECT_DENIED",
                if (core.healthConnect.canReadNow()) {
                    message
                } else {
                    "$message (with no activity on screen and no foreground service - tracking off - " +
                        "Health Connect needs READ_HEALTH_DATA_IN_BACKGROUND: healthConnectBackgroundRead)"
                },
                denied
            )
        }
    }

    @ReactMethod
    override fun writeHealthConnectSteps(date: String, promise: Promise) {
        launchSafely(promise) {
            promise.resolve(core.healthConnect.writeDay(core.mirrorTotals(core.dayTotals(date))))
        }
    }

    @ReactMethod
    override fun syncWithHealthConnect(promise: Promise) {
        launchSafely(promise) { promise.resolve(Bridge.map(core.syncHealthConnect())) }
    }

    // ---- step sources ----------------------------------------------------

    /**
     * Every app that published steps in the window, with the totals each one
     * contributed and whether it counted on the body or in a pocket.
     *
     * These do not sum to a day's step count - two of them covering the same
     * walk each report all of it. Use it to let the user pick a source, not to
     * add up.
     */
    @ReactMethod
    override fun getStepSources(startDate: String, endDate: String, promise: Promise) {
        launchSafely(promise) {
            val list = core.listSources(startDate, endDate)
            val sources = list.sources
            promise.resolve(
                Bridge.map(
                    mapOf(
                        "sources" to sources.map { it.toMap() },
                        "hasWearable" to sources.any { !it.isSelf && it.isWearable },
                        "healthConnect" to list.healthConnect
                    )
                )
            )
        }
    }

    /** Which source is answering for today, and what the alternatives counted. */
    @ReactMethod
    override fun getCurrentStepSource(promise: Promise) {
        launchSafely(promise) {
            val resolution = core.resolveDay(DateKeys.today())
            promise.resolve(
                Bridge.map(
                    resolution.toMap() + mapOf(
                        "policy" to core.sourcePolicy().jsValue,
                        "preferredPackage" to core.preferredSourcePackage()
                    )
                )
            )
        }
    }

    /**
     * Pins one origin as the source of truth, or clears the pin with `null`.
     * Stored outside config so a user's choice survives an `initialize()` that
     * does not mention it.
     */
    @ReactMethod
    override fun setPreferredStepSource(packageName: String?, promise: Promise) {
        launchSafely(promise) {
            core.setPreferredSourcePackage(packageName?.takeIf { it.isNotBlank() })
            promise.resolve(Bridge.map(core.resolveToday().toMap()))
        }
    }

    /**
     * Wearable companion apps present on this phone, for an onboarding screen
     * that wants to say "we found Galaxy Wearable" before any data exists.
     */
    @ReactMethod
    override fun getInstalledCompanionApps(promise: Promise) {
        runSafely(promise) {
            promise.resolve(
                Bridge.map(mapOf("apps" to core.healthConnect.installedCompanionApps()))
            )
        }
    }

    // ---- sync ------------------------------------------------------------

    /**
     * What remote uploads last did, kept across process deaths: when, whether
     * they were accepted, the last failure and whether it was the
     * credentials. A background upload's refusal usually happens with no JS
     * to hear `syncAuthFailed`; this is where the app finds it.
     */
    @ReactMethod
    override fun getSyncStatus(promise: Promise) {
        launchSafely(promise) {
            val config = core.config()
            val remote = core.state.remoteSyncStatus().toMap(
                configured = !config.remoteSyncUrl.isNullOrEmpty(),
                pendingRecords = core.repository.countUnsynced(SyncTarget.REMOTE)
            )
            promise.resolve(Bridge.map(mapOf("remote" to remote)))
        }
    }

    @ReactMethod
    override fun getPendingSyncCount(promise: Promise) {
        launchSafely(promise) { promise.resolve(core.pendingSyncCount()) }
    }

    @ReactMethod
    override fun syncNow(promise: Promise) {
        launchSafely(promise) {
            val results = ArrayList<Map<String, Any?>>()
            results.add(core.syncHealthConnect())
            val config = core.config()
            if (!config.remoteSyncUrl.isNullOrEmpty()) {
                SyncScheduler.runNow(reactContext, config)
                // Nothing has been uploaded yet, and nothing has failed: the
                // upload is queued, and its outcome arrives on syncCompleted.
                results.add(
                    mapOf(
                        "target" to "remote",
                        "syncedRecords" to 0,
                        "failedRecords" to 0,
                        "success" to true,
                        "queued" to true
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

    /**
     * The live snapshot with `stepSource` attached from the source cache and
     * the day's continuity baseline - no Health Connect call, so it is safe
     * on the native modules thread. Every snapshot that reaches JS carries
     * `stepSource`; a lifecycle call must not hand back a shape the reads
     * would not.
     */
    private fun cachedSnapshot() = core.engine.snapshot().let { live ->
        Bridge.snapshot(
            live,
            core.resolveFromCache(DayTotals(live.date, live.steps, live.distance, live.calories))
        )
    }

    private inline fun runSafely(promise: Promise, block: () -> Unit) {
        runCatching { block() }.onFailure { promise.reject(codeOf(it), it.message, it) }
    }

    private fun launchSafely(promise: Promise, block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onFailure { promise.reject(codeOf(it), it.message, it) }
        }
    }

    /** `E_DATABASE` for storage that failed - a full disk, a corrupt file - and `E_UNKNOWN` otherwise. */
    private fun codeOf(error: Throwable): String =
        if (error is android.database.SQLException) "E_DATABASE" else "E_UNKNOWN"

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
            notificationLockScreen = patch.optString("notificationLockScreen", current.notificationLockScreen)
                ?: current.notificationLockScreen,
            notificationDistanceUnit = patch.optString("notificationDistanceUnit", current.notificationDistanceUnit)
                ?: current.notificationDistanceUnit,
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
            healthConnectReadEnabled = patch.optBoolean(
                "healthConnectReadEnabled", current.healthConnectReadEnabled
            ),
            healthConnectWriteEnabled = patch.optBoolean(
                "healthConnectWriteEnabled", current.healthConnectWriteEnabled
            ),
            // Set once the app passes the key; a patch without it changes nothing.
            healthConnectWriteExplicit = current.healthConnectWriteExplicit ||
                (patch.hasKey("healthConnectWriteEnabled") && !patch.isNull("healthConnectWriteEnabled")),
            healthConnectReadTypesExplicit = current.healthConnectReadTypesExplicit ||
                (patch.hasKey("healthConnectReadTypes") && !patch.isNull("healthConnectReadTypes")),
            healthConnectBackgroundRead = patch.optBoolean(
                "healthConnectBackgroundRead", current.healthConnectBackgroundRead
            ),
            healthConnectHistoryRead = patch.optBoolean(
                "healthConnectHistoryRead", current.healthConnectHistoryRead
            ),
            healthConnectIgnoreManualEntries = patch.optBoolean(
                "healthConnectIgnoreManualEntries", current.healthConnectIgnoreManualEntries
            ),
            healthConnectReadActiveCalories = patch.optBoolean(
                "healthConnectReadActiveCalories", current.healthConnectReadActiveCalories
            ),
            healthConnectReadTypes = if (patch.hasKey("healthConnectReadTypes") && !patch.isNull("healthConnectReadTypes")) {
                patch.getArray("healthConnectReadTypes")?.toStringList() ?: current.healthConnectReadTypes
            } else {
                current.healthConnectReadTypes
            },
            stepSource = patch.optString("stepSource", current.stepSource) ?: current.stepSource,
            preferredStepSourcePackage = patch.optString(
                "preferredStepSourcePackage", current.preferredStepSourcePackage
            ),
            wearableTrust = patch.optString("wearableTrust", current.wearableTrust)
                ?: current.wearableTrust,
            wearableAllowlist = if (patch.hasKey("wearableAllowlist") && !patch.isNull("wearableAllowlist")) {
                patch.getArray("wearableAllowlist")?.toStringList() ?: emptyList()
            } else {
                current.wearableAllowlist
            },
            privacyPolicyUrl = patch.optString("privacyPolicyUrl", current.privacyPolicyUrl),
            remoteSyncUrl = patch.optString("remoteSyncUrl", current.remoteSyncUrl),
            remoteSyncHeaders = if (patch.hasKey("remoteSyncHeaders")) {
                Bridge.stringMap(patch.getMap("remoteSyncHeaders"))
            } else {
                current.remoteSyncHeaders
            },
            remoteSyncAllowHttp = patch.optBoolean("remoteSyncAllowHttp", current.remoteSyncAllowHttp),
            remoteSyncPayload = patch.optString("remoteSyncPayload", current.remoteSyncPayload)
                ?: current.remoteSyncPayload,
            remoteSyncAuth = patch.optString("remoteSyncAuth", current.remoteSyncAuth)
                ?: current.remoteSyncAuth,
            autoStartOnBoot = patch.optBoolean("autoStartOnBoot", current.autoStartOnBoot),
            gapRecovery = patch.optString("gapRecovery", current.gapRecovery) ?: current.gapRecovery,
            gapRecoveryMaxSteps = patch.optInt("gapRecoveryMaxSteps", current.gapRecoveryMaxSteps),
            watchdogEnabled = patch.optBoolean("watchdogEnabled", current.watchdogEnabled),
            accelerometerFallback = patch.optBoolean(
                "accelerometerFallback", current.accelerometerFallback
            ),
            accelerometerWakeLock = patch.optBoolean(
                "accelerometerWakeLock", current.accelerometerWakeLock
            ),
            accelerometerThreshold = patch.optDouble(
                "accelerometerThreshold", current.accelerometerThreshold
            ),
            // JS groups the three under `motionSampling`; only the keys present
            // in the object move, like every other patch.
            motionSamplingEnabled = motion(patch)?.optBoolean("enabled", current.motionSamplingEnabled)
                ?: current.motionSamplingEnabled,
            motionWindowSeconds = motion(patch)?.optInt("windowSeconds", current.motionWindowSeconds)
                ?: current.motionWindowSeconds,
            motionIntervalMinutes = motion(patch)?.optInt("intervalMinutes", current.motionIntervalMinutes)
                ?: current.motionIntervalMinutes,
            motionWindowRetention = patch.optInt("motionWindowRetention", current.motionWindowRetention),
            // JS groups these under `fraudDetection`, patched key by key like motionSampling.
            fraudDetectionEnabled = fraud(patch)?.optBoolean("enabled", current.fraudDetectionEnabled)
                ?: current.fraudDetectionEnabled,
            fraudMode = fraud(patch)?.optString("mode", current.fraudMode) ?: current.fraudMode,
            fraudMaxCadenceSpm = fraud(patch)?.optInt("maxCadenceSpm", current.fraudMaxCadenceSpm)
                ?: current.fraudMaxCadenceSpm,
            fraudSteadyCadenceMinutes = fraud(patch)
                ?.optInt("steadyCadenceMinutes", current.fraudSteadyCadenceMinutes)
                ?: current.fraudSteadyCadenceMinutes,
            fraudMaxContinuousMinutes = fraud(patch)
                ?.optInt("maxContinuousMinutes", current.fraudMaxContinuousMinutes)
                ?: current.fraudMaxContinuousMinutes,
            fraudMaxDailySteps = fraud(patch)?.optInt("maxDailySteps", current.fraudMaxDailySteps)
                ?: current.fraudMaxDailySteps,
            fraudFlagWhileCharging = fraud(patch)
                ?.optBoolean("flagWhileCharging", current.fraudFlagWhileCharging)
                ?: current.fraudFlagWhileCharging,
            fraudActivityRecognition = fraud(patch)
                ?.optBoolean("activityRecognition", current.fraudActivityRecognition)
                ?: current.fraudActivityRecognition
        )

    private fun fraud(patch: ReadableMap): ReadableMap? =
        if (patch.hasKey("fraudDetection") && !patch.isNull("fraudDetection")) {
            patch.getMap("fraudDetection")
        } else {
            null
        }

    private fun motion(patch: ReadableMap): ReadableMap? =
        if (patch.hasKey("motionSampling") && !patch.isNull("motionSampling")) {
            patch.getMap("motionSampling")
        } else {
            null
        }

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
        "notificationLockScreen" to config.notificationLockScreen,
        "notificationDistanceUnit" to config.notificationDistanceUnit,
        "healthConnectEnabled" to config.healthConnectEnabled,
        "healthConnectSyncIntervalMinutes" to config.healthConnectSyncIntervalMinutes,
        "healthConnectReadEnabled" to config.healthConnectReadEnabled,
        "healthConnectWriteEnabled" to config.healthConnectWriteEnabled,
        "healthConnectBackgroundRead" to config.healthConnectBackgroundRead,
        "healthConnectHistoryRead" to config.healthConnectHistoryRead,
        "healthConnectIgnoreManualEntries" to config.healthConnectIgnoreManualEntries,
        "healthConnectReadActiveCalories" to config.healthConnectReadActiveCalories,
        "healthConnectReadTypes" to config.healthConnectReadTypes,
        "stepSource" to config.stepSource,
        "preferredStepSourcePackage" to config.preferredStepSourcePackage,
        "wearableTrust" to config.wearableTrust,
        "wearableAllowlist" to config.wearableAllowlist,
        "privacyPolicyUrl" to config.privacyPolicyUrl,
        "remoteSyncUrl" to config.remoteSyncUrl,
        "remoteSyncAllowHttp" to config.remoteSyncAllowHttp,
        "remoteSyncPayload" to config.remoteSyncPayload,
        "remoteSyncAuth" to config.remoteSyncAuth,
        "autoStartOnBoot" to config.autoStartOnBoot,
        "gapRecovery" to config.gapRecovery,
        "gapRecoveryMaxSteps" to config.gapRecoveryMaxSteps,
        "watchdogEnabled" to config.watchdogEnabled,
        "accelerometerFallback" to config.accelerometerFallback,
        "accelerometerWakeLock" to config.accelerometerWakeLock,
        "accelerometerThreshold" to config.accelerometerThreshold,
        "motionSampling" to mapOf(
            "enabled" to config.motionSamplingEnabled,
            "windowSeconds" to config.motionWindowSeconds,
            "intervalMinutes" to config.motionIntervalMinutes
        ),
        "motionWindowRetention" to config.motionWindowRetention,
        "fraudDetection" to mapOf(
            "enabled" to config.fraudDetectionEnabled,
            "mode" to config.fraudMode,
            "maxCadenceSpm" to config.fraudMaxCadenceSpm,
            "steadyCadenceMinutes" to config.fraudSteadyCadenceMinutes,
            "maxContinuousMinutes" to config.fraudMaxContinuousMinutes,
            "maxDailySteps" to config.fraudMaxDailySteps,
            "flagWhileCharging" to config.fraudFlagWhileCharging,
            "activityRecognition" to config.fraudActivityRecognition
        )
    )

    companion object {
        const val NAME = "StepTrackerPro"
        const val EVENT_PREFIX = "StepTrackerPro:"
        private const val PERMISSION_REQUEST_CODE = 7731
    }
}
