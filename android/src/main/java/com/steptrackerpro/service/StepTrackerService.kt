package com.steptrackerpro.service

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.steptrackerpro.core.AccelerometerStepDetector
import com.steptrackerpro.core.MotionWindowSampler
import com.steptrackerpro.core.SensorSource
import com.steptrackerpro.core.StepTrackerConfig
import com.steptrackerpro.core.StepTrackerCore
import com.steptrackerpro.core.TrackingState
import com.steptrackerpro.sync.SyncScheduler
import com.steptrackerpro.util.BatteryOptimizationHelper
import com.steptrackerpro.util.PermissionHelper
import com.steptrackerpro.util.StepEventBus

/**
 * The only component that touches the sensors.
 *
 * Runs as a `health` foreground service so Android keeps it alive with the
 * screen off and the app swiped away. START_STICKY plus persisted counter state
 * means a kill by the OS costs at most the steps taken between the last
 * SharedPreferences write and the restart — and with TYPE_STEP_COUNTER even
 * those are recovered, because the hardware kept counting.
 */
class StepTrackerService : Service(), SensorEventListener {

    private lateinit var core: StepTrackerCore
    private lateinit var notifications: NotificationFactory

    private var sensorManager: SensorManager? = null
    private var counterSensor: Sensor? = null
    private var detectorSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null

    /** Written on the main thread, read on the sensor thread. */
    @Volatile
    private var listening = false

    /**
     * Only built when the accelerometer is what we ended up on. A phone with
     * a hardware counter never allocates one. Replaced on the main thread
     * (config change, pause) and used on the sensor thread.
     */
    @Volatile
    private var accelerometer: AccelerometerStepDetector? = null

    /**
     * Held while sampling a non-wake-up accelerometer, so the CPU does not
     * sleep and take the samples with it. This is the battery cost of the
     * fallback and the reason it is a fallback.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Sensor callbacks would otherwise be delivered on the main looper, which is
     * where `registerListener` puts them when no handler is supplied. Every
     * sample writes SharedPreferences and every notification push is a binder
     * call, once per step; none of that belongs on the UI thread. The engine is
     * fully synchronized, so moving the callbacks is safe.
     */
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    @Volatile
    private var lastNotificationAt = 0L

    @Volatile
    private var lastNotifiedSteps = -1

    /** A notification redraw deferred by the throttle, so the last step is never left undrawn. */
    @Volatile
    private var trailingNotificationQueued = false

    private val heartbeat = object : Runnable {
        override fun run() {
            core.state.lastHeartbeatAt = System.currentTimeMillis()
            // A listener that could not be registered earlier - the sensor
            // service was not ready, a HAL hiccup - is retried here for as
            // long as the tracker is meant to be running, so a transient
            // failure never becomes a permanently idle service.
            if (!listening && core.shouldBeRunning() && !core.engine.paused) {
                if (registerSensors()) {
                    core.state.trackingState = TrackingState.RUNNING
                    core.emitTrackingState("sensor_recovered")
                    pushNotification(force = true)
                }
            }
            sensorHandler?.postDelayed(this, StepTrackerCore.HEARTBEAT_INTERVAL_MS)
        }
    }

    /** Short retries after a failed registration, before the heartbeat takes over. */
    private var registerRetries = 0

    // ---- motion signature windows ----------------------------------------
    //
    // All of this state lives on the sensor thread: the tick, the samples and
    // the close all run there, and the main-thread paths (pause, config,
    // stop) post to it rather than touching it. The one exception is
    // unregisterSensors(), which drops an open window from whichever thread
    // is tearing the listener down; the fields are volatile for that, and
    // a close racing a close is harmless - the second finds no sampler.

    /** The window being filled, or null between windows. */
    @Volatile
    private var motionSampler: MotionWindowSampler? = null

    /** `stepsToday` when the open window started, for `stepsDuringWindow`. */
    @Volatile
    private var motionStepsAtOpen = 0

    /** `stepsToday` and date when the last window closed: no new steps, no new window. */
    @Volatile
    private var motionLastSteps = -1

    @Volatile
    private var motionLastDate: String? = null

    /** Held only while a window is open on a non-wake-up accelerometer; timed, so it cannot leak. */
    @Volatile
    private var motionWakeLock: PowerManager.WakeLock? = null

    private val motionTick = object : Runnable {
        override fun run() {
            val config = core.config()
            // Not rescheduled once disabled; scheduleMotionSampling() starts
            // it again when config turns it back on.
            if (!config.motionSamplingEnabled) return
            maybeOpenMotionWindow(config)
            sensorHandler?.postDelayed(this, config.motionIntervalMinutes * 60_000L)
        }
    }

    /** Closes a window the sensor stopped delivering into, so one can never stay open. */
    private val motionClose = Runnable { closeMotionWindow(store = true) }

    override fun onCreate() {
        super.onCreate()
        isAlive = true
        core = StepTrackerCore.get(this)
        notifications = NotificationFactory(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
        counterSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        detectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        // The wake-up variant keeps delivering with the CPU asleep and needs
        // no wake lock; most budget phones only have the non-wake-up one.
        accelerometerSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorThread = HandlerThread("stp-sensor").also {
            it.start()
            sensorHandler = Handler(it.looper)
        }
        notifications.ensureChannel(core.config())
        // The heartbeat is what lets the watchdog and the React module tell
        // "killed by the OEM" from "the user has not moved": a still user
        // produces no samples, but a live service still beats.
        core.state.lastHeartbeatAt = System.currentTimeMillis()
        sensorHandler?.postDelayed(heartbeat, StepTrackerCore.HEARTBEAT_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        // A null intent is the OS restarting us after a kill; anything else with
        // no action, or ACTION_START, is a deliberate start.
        val explicitStart =
            intent != null && (action == null || action == ServiceCommands.ACTION_START)

        // Promote even though we are about to stop. Stopping inside the
        // startForegroundService() window is *supposed* to satisfy the OS
        // deadline on its own, but a service that never completes
        // startForeground() is killed with
        // ForegroundServiceDidNotStartInTimeException, and that is not a race
        // worth taking on a path the user hits deliberately. When the service
        // was already in the foreground this is a no-op re-post.
        if (action == ServiceCommands.ACTION_STOP) {
            promoteToForeground()
            stopTracking()
            return START_NOT_STICKY
        }

        // Every command arrives through startForegroundService(), which starts
        // the service in order to deliver it. Only an explicit start may leave a
        // service running for a tracker the user has stopped: that service
        // returns START_STICKY, and the OS restarting it would resurrect
        // tracking nobody asked for.
        if (!explicitStart && !core.state.shouldAutoStart) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        // Must happen inside 5s of startForegroundService, before anything that
        // can throw, or the OS raises ForegroundServiceDidNotStartInTimeException.
        if (!promoteToForeground()) return abandon()

        when (action) {
            ServiceCommands.ACTION_PAUSE -> pause()
            ServiceCommands.ACTION_RESUME -> resume()
            ServiceCommands.ACTION_CONFIG_CHANGED -> {
                notifications.ensureChannel(core.config())
                // A new threshold applies to the next step, not the next
                // service start. The filter state is a few hundred
                // milliseconds of history and is not worth preserving.
                accelerometer?.let {
                    accelerometer = AccelerometerStepDetector(
                        threshold = core.config().accelerometerThreshold.toFloat()
                    )
                }
                scheduleMotionSampling()
                pushNotification(force = true)
            }
            ServiceCommands.ACTION_REFRESH -> pushNotification(force = true)
            else -> {
                val fromBoot =
                    intent?.getBooleanExtra(ServiceCommands.EXTRA_FROM_BOOT, false) == true
                val recoveredBy = intent?.getStringExtra(ServiceCommands.EXTRA_RECOVERED_BY)
                // A restart or a boot must honour what the user last asked for
                // rather than starting fresh, pause included.
                val restore = !explicitStart || fromBoot || recoveredBy != null
                // Anything that is not the user pressing start is a recovery,
                // and how often those happen is the number an app needs in
                // order to decide whether to walk the user to the OEM's
                // battery settings.
                when {
                    intent == null -> core.state.recordRecovery("sticky")
                    fromBoot -> core.state.recordRecovery("boot")
                    recoveredBy != null -> core.state.recordRecovery(recoveredBy)
                    else -> core.state.resetRecovery()
                }
                startTracking(fromBoot = fromBoot, restore = restore)
            }
        }

        // Any command can be the one that revives us after the process was
        // killed. Claiming to be running without a listener attached is the
        // silent-stop failure this guard exists to prevent.
        if (!listening && core.state.trackingState == TrackingState.RUNNING) {
            registerSensors()
        }

        // START_STICKY: if the OS kills us for memory, restart with a null
        // intent and fall through to startTracking() above.
        return START_STICKY
    }

    // ---- lifecycle -------------------------------------------------------

    /**
     * @param restore true when the start was not a deliberate user action — a
     *   sticky restart or a boot — in which case a pause the user set before the
     *   process died must survive it.
     */
    private fun startTracking(fromBoot: Boolean, restore: Boolean = false) {
        if (!PermissionHelper.canStartTracking(this)) {
            StepEventBus.emit(
                StepEventBus.Events.ERROR,
                mapOf(
                    "code" to "E_PERMISSION_DENIED",
                    "message" to "ACTIVITY_RECOGNITION is required to count steps"
                )
            )
            stopTracking()
            return
        }

        core.state.shouldAutoStart = true
        registerRetries = 0

        // `paused` lives in memory only, so a restart has to read it back off
        // the persisted tracking state. setPaused() ignores a no-op transition,
        // which is what keeps the steps the hardware counted while the process
        // was dead from being discarded by a spurious re-anchor.
        val paused = restore && core.state.trackingState == TrackingState.PAUSED
        core.engine.setPaused(paused)
        core.engine.reconcile()

        if (!registerSensors()) {
            val capabilities = PermissionHelper.capabilities(
                this, allowAccelerometer = core.config().accelerometerFallback
            )
            if (capabilities["supported"] != true) {
                // Genuinely nothing to listen to. This is the only path that
                // marks the tracker unsupported, and the only one the
                // recovery paths will not retry.
                core.state.trackingState = TrackingState.UNSUPPORTED
                core.state.source = SensorSource.NONE
                StepEventBus.emit(
                    StepEventBus.Events.ERROR,
                    mapOf(
                        "code" to "E_NO_SENSOR",
                        "message" to "This device exposes no step sensor, and no accelerometer to fall back on"
                    )
                )
                core.emitTrackingState("no_sensor")
                stopSelf()
                return
            }
            // The sensor exists but registration failed - on several OEMs the
            // sensor service is not ready for a few seconds after boot, and
            // some HALs refuse a listener while another one is being torn
            // down. Stay up, keep the state the user asked for, and retry:
            // quickly a few times, then once a minute from the heartbeat.
            // Marking this unsupported is what used to leave the tracker idle
            // until the user pressed start again.
            StepEventBus.emit(
                StepEventBus.Events.ERROR,
                mapOf(
                    "code" to "E_SENSOR_UNAVAILABLE",
                    "message" to "Step sensor registration failed; retrying"
                )
            )
            scheduleRegisterRetry()
        }

        core.state.trackingState =
            if (paused) TrackingState.PAUSED else TrackingState.RUNNING
        SyncScheduler.schedule(this, core.config())
        SyncScheduler.scheduleWatchdog(this, core.config())
        scheduleMotionSampling()
        pushNotification(force = true)
        core.emitTrackingState(
            when {
                fromBoot -> "boot"
                restore -> core.state.lastRecoveryReason?.let { "restored_$it" } ?: "restored"
                else -> "started"
            }
        )
    }

    private fun scheduleRegisterRetry() {
        if (registerRetries >= REGISTER_RETRY_DELAYS_MS.size) return
        val delay = REGISTER_RETRY_DELAYS_MS[registerRetries++]
        sensorHandler?.postDelayed({
            if (listening || !core.shouldBeRunning()) return@postDelayed
            if (registerSensors()) {
                registerRetries = 0
                core.emitTrackingState("sensor_recovered")
                pushNotification(force = true)
            } else {
                scheduleRegisterRetry()
            }
        }, delay)
    }

    private fun pause() {
        core.engine.setPaused(true)
        core.state.trackingState = TrackingState.PAUSED
        core.flush()
        // The hardware counter costs nothing to keep listening to, and its
        // cumulative reading is what lets resume re-pin cleanly. The
        // accelerometer is the opposite: 25 Hz of samples and a wake lock for
        // steps that are going to be discarded. Let it go; resume() registers
        // again.
        if (core.state.source == SensorSource.ACCELEROMETER) unregisterSensors()
        // A paused tracker discards its steps; a signature of them would be
        // a signature of nothing that counts. Whatever is open is dropped.
        scheduleMotionSampling()
        pushNotification(force = true)
        core.emitTrackingState("paused")
    }

    private fun resume() {
        core.engine.setPaused(false)
        core.engine.reconcile()
        if (!listening) registerSensors()
        core.state.trackingState = TrackingState.RUNNING
        scheduleMotionSampling()
        pushNotification(force = true)
        core.emitTrackingState("resumed")
    }

    private fun stopTracking() {
        core.state.shouldAutoStart = false
        core.state.trackingState = TrackingState.STOPPED
        core.flush()
        scheduleMotionSampling()
        unregisterSensors()
        core.emitTrackingState("stopped")
        stopForegroundCompat()
        stopSelf()
    }

    /**
     * Leaves after a failed foreground promotion. Carrying on would report the
     * tracker as running on a service the OS is about to kill, and would not
     * stop it before the startForegroundService() deadline expires.
     * `shouldAutoStart` is deliberately left alone so a later start or reboot
     * can still recover from a transient failure.
     */
    private fun abandon(): Int {
        // A pause the user set has to survive this: `restore` reads it back off
        // trackingState, and overwriting it with STOPPED would silently resume
        // counting the next time the service comes up.
        if (core.state.trackingState != TrackingState.PAUSED) {
            core.state.trackingState = TrackingState.STOPPED
        }
        core.emitTrackingState("foreground_start_failed")
        unregisterSensors()
        stopForegroundCompat()
        stopSelf()
        return START_NOT_STICKY
    }

    // ---- sensors ---------------------------------------------------------

    private fun registerSensors(): Boolean {
        if (listening) return true
        val manager = sensorManager ?: return false
        val handler = sensorHandler ?: return false

        counterSensor?.let { sensor ->
            val ok = manager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL,
                MAX_REPORT_LATENCY_US,
                handler
            )
            if (ok) {
                listening = true
                core.state.source = SensorSource.STEP_COUNTER
                return true
            }
        }

        detectorSensor?.let { sensor ->
            val ok = manager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL,
                handler
            )
            if (ok) {
                listening = true
                core.state.source = SensorSource.STEP_DETECTOR
                return true
            }
        }

        val config = core.config()
        if (config.accelerometerFallback) {
            accelerometerSensor?.let { sensor ->
                // 25 Hz is plenty for gait and a quarter of SENSOR_DELAY_GAME's
                // load. The report latency lets a sensor hub with a FIFO batch
                // a second of samples per wake-up; hubs without one ignore it.
                val ok = manager.registerListener(
                    this,
                    sensor,
                    ACCELEROMETER_PERIOD_US,
                    ACCELEROMETER_MAX_LATENCY_US,
                    handler
                )
                if (ok) {
                    listening = true
                    accelerometer = AccelerometerStepDetector(
                        threshold = config.accelerometerThreshold.toFloat()
                    )
                    core.state.source = SensorSource.ACCELEROMETER
                    if (!sensor.isWakeUpSensor && config.accelerometerWakeLock) acquireWakeLock()
                    return true
                }
            }
        }

        return false
    }

    private fun unregisterSensors() {
        // Unregistering the listener takes the window's accelerometer with
        // it, so the window is dropped rather than left waiting for samples
        // that will not come.
        if (motionSampler != null) closeMotionWindow(store = false)
        if (!listening) return
        runCatching { sensorManager?.unregisterListener(this) }
        listening = false
        accelerometer = null
        releaseWakeLock()
    }

    // ---- motion signature windows ----------------------------------------

    /**
     * Arms or disarms the periodic tick from the current config and state.
     * Posted to the sensor thread, where the tick and the samples live.
     * Anything already open is dropped: a config change or a pause makes it
     * a window under rules that no longer apply.
     */
    private fun scheduleMotionSampling() {
        val handler = sensorHandler ?: return
        handler.post {
            handler.removeCallbacks(motionTick)
            closeMotionWindow(store = false)
            val config = core.config()
            if (!config.motionSamplingEnabled) return@post
            if (!core.shouldBeRunning() || core.engine.paused) return@post
            handler.postDelayed(motionTick, config.motionIntervalMinutes * 60_000L)
        }
    }

    /**
     * Opens a window when there is something worth a signature: tracking is
     * live, steps have accrued since the last window, and the phone is either
     * in the foreground or has the battery exemption. A background sample on
     * a phone the user has not exempted is exactly the kind of cost Doze
     * exists to stop, so it is not taken.
     */
    private fun maybeOpenMotionWindow(config: StepTrackerConfig) {
        if (motionSampler != null) return
        if (!listening || core.engine.paused) return
        val sensor = accelerometerSensor ?: return
        val manager = sensorManager ?: return
        val handler = sensorHandler ?: return

        val steps = core.state.stepsToday
        val date = core.state.activeDate
        if (steps <= 0) return
        if (date == motionLastDate && steps <= motionLastSteps) return

        if (!isAppInForeground() && BatteryOptimizationHelper.isOptimizationEnabled(this)) return

        val windowMs = config.motionWindowSeconds * 1_000L
        // On a phone counting over the accelerometer the samples are already
        // arriving; the sampler just listens in. Otherwise the accelerometer
        // is registered for the window and released with it.
        if (core.state.source != SensorSource.ACCELEROMETER) {
            val ok = manager.registerListener(
                this, sensor, ACCELEROMETER_PERIOD_US, ACCELEROMETER_MAX_LATENCY_US, handler
            )
            if (!ok) return
            // Same policy as the fallback pedometer: a non-wake-up sensor
            // stops delivering when the CPU sleeps, so hold it awake for the
            // window - and no longer, hence the timeout.
            if (!sensor.isWakeUpSensor && config.accelerometerWakeLock && wakeLock?.isHeld != true) {
                acquireMotionWakeLock(windowMs + MOTION_CLOSE_GRACE_MS)
            }
        }
        motionStepsAtOpen = steps
        motionSampler = MotionWindowSampler(System.currentTimeMillis(), windowMs)
        handler.postDelayed(motionClose, windowMs + MOTION_CLOSE_GRACE_MS)
    }

    /**
     * Ends the open window. With [store], the features are computed and
     * handed to the core - unless too few samples arrived to say anything,
     * in which case there is no signature and nothing is stored.
     */
    private fun closeMotionWindow(store: Boolean) {
        val sampler = motionSampler ?: return
        motionSampler = null
        sensorHandler?.removeCallbacks(motionClose)
        if (core.state.source != SensorSource.ACCELEROMETER) {
            // The sensor-specific overload: the bare one would take the step
            // counter down with it.
            accelerometerSensor?.let { runCatching { sensorManager?.unregisterListener(this, it) } }
        }
        releaseMotionWakeLock()
        val steps = core.state.stepsToday
        motionLastSteps = steps
        motionLastDate = core.state.activeDate
        if (!store) return
        // A rollover inside the window makes the difference negative; that
        // window's step count is simply unknown, and 0 is the honest floor.
        val features = sampler.features((steps - motionStepsAtOpen).coerceAtLeast(0))
        // Too few samples, or timestamps that never advanced (a HAL quirk),
        // is no signature at all - not an all-zero one.
        if (features.sampleCount < MotionWindowSampler.MIN_SAMPLES || features.durationMs <= 0L) return
        core.recordMotionWindow(features)
    }

    /** Whether an activity of this app is on screen; a foreground *service* alone does not count. */
    private fun isAppInForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun acquireMotionWakeLock(timeoutMs: Long) {
        if (motionWakeLock?.isHeld == true) return
        val power = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        motionWakeLock = runCatching {
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, MOTION_WAKE_LOCK_TAG).also {
                it.setReferenceCounted(false)
                it.acquire(timeoutMs)
            }
        }.getOrNull()
    }

    private fun releaseMotionWakeLock() {
        motionWakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        motionWakeLock = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val sensorEvent = event ?: return
        val values = sensorEvent.values
        if (values == null || values.isEmpty()) return

        // An open motion window listens in on whatever accelerometer samples
        // arrive, whether the sensor is the primary one or was registered for
        // the window. The sample that fills the window closes it.
        if (sensorEvent.sensor.type == Sensor.TYPE_ACCELEROMETER && values.size >= 3) {
            motionSampler?.let { sampler ->
                if (sampler.add(values[0], values[1], values[2], sensorEvent.timestamp / 1_000_000L)) {
                    closeMotionWindow(store = true)
                }
            }
        }

        val snapshot = when (sensorEvent.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> core.engine.onCounterSample(values[0], eventTime(sensorEvent))
            Sensor.TYPE_STEP_DETECTOR -> core.engine.onDetectorSample(1, eventTime(sensorEvent))
            Sensor.TYPE_ACCELEROMETER -> {
                if (values.size < 3) return
                // The detector only uses differences between timestamps, so
                // the sensor's own elapsed-realtime base is the right one: it
                // is monotonic and unaffected by wall-clock changes, which is
                // more than eventTime() can promise on every HAL.
                val steps = accelerometer?.onSample(
                    values[0], values[1], values[2], sensorEvent.timestamp / 1_000_000L
                ) ?: 0
                if (steps > 0) core.engine.onDetectorSample(steps, eventTime(sensorEvent)) else null
            }
            else -> null
        } ?: return

        core.onStepsChanged(snapshot)
        pushNotification()
    }

    /**
     * A partial wake lock with no timeout, on purpose: it is held exactly as
     * long as the accelerometer listener is registered, which is exactly as
     * long as the user has tracking on, and released in the same places the
     * listener is.
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        wakeLock = runCatching {
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        }.getOrNull()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
    }

    /**
     * Sensor timestamps are documented as elapsed-realtime nanos, but several
     * OEM HALs report wall-clock nanos instead, which turns the delta into
     * nonsense. Fall back to "now" whenever it lands outside a plausible
     * batching window rather than storing a garbage timestamp.
     */
    private fun eventTime(event: SensorEvent): Long {
        val now = System.currentTimeMillis()
        val ageMs = (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000L
        return if (ageMs in 0..MAX_EVENT_AGE_MS) now - ageMs else now
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ---- notification ----------------------------------------------------

    /** @return false when the promotion failed and the service must not continue. */
    private fun promoteToForeground(): Boolean {
        val config = core.config()
        notifications.ensureChannel(config)
        val notification = notifications.build(core.displaySnapshot(), config, core.metrics)
        return runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NotificationFactory.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                )
            } else {
                startForeground(NotificationFactory.NOTIFICATION_ID, notification)
            }
            true
        }.onFailure { error ->
            // Catching this does not cancel the system's own deadline: a service
            // started with startForegroundService() that never completes
            // startForeground() is killed with
            // ForegroundServiceDidNotStartInTimeException. The caller stops the
            // service instead of carrying on and reporting itself as running.
            Log.e(TAG, "startForeground failed", error)
            StepEventBus.emit(
                StepEventBus.Events.ERROR,
                mapOf(
                    "code" to "E_SERVICE_START_FAILED",
                    "message" to (error.message ?: "startForeground failed")
                )
            )
        }.getOrDefault(false)
    }

    private fun pushNotification(force: Boolean = false) {
        val config = core.config()
        val now = SystemClock.elapsedRealtime()
        // Deduped on the number actually drawn, not on this device's raw count.
        // While a wearable owns the day the phone's counter keeps moving without
        // changing anything visible, and redrawing for that is pure wakeups.
        val snapshot = core.displaySnapshot()
        if (!force) {
            if (snapshot.steps == lastNotifiedSteps) return
            val wait = config.notificationThrottleMs - (now - lastNotificationAt)
            if (wait > 0) {
                // Throttled. Draw once more when the window closes, so the
                // shade never sits on the count from one step ago after the
                // user stops walking.
                if (!trailingNotificationQueued) {
                    trailingNotificationQueued = true
                    sensorHandler?.postDelayed({
                        trailingNotificationQueued = false
                        if (listening) pushNotification()
                    }, wait)
                }
                return
            }
        }
        lastNotificationAt = now
        lastNotifiedSteps = snapshot.steps
        notifications.update(notifications.build(snapshot, config, core.metrics))
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ---- teardown --------------------------------------------------------

    /**
     * The user swiping the app away must not stop counting, which is the whole
     * point of the service. `stopWithTask=false` in the manifest keeps us
     * alive; this override just makes sure nothing in flight is lost.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        core.flush()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isAlive = false
        sensorHandler?.removeCallbacksAndMessages(null)
        unregisterSensors()
        core.flush()
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "StepTrackerService"

        /**
         * Whether an instance exists in this process. A SIGKILL clears it with
         * the process, which is the point: it can only be true when the
         * service is genuinely there.
         */
        @Volatile
        var isAlive: Boolean = false
            private set

        /**
         * Batch window for the step counter. 0 delivers every sample as it
         * happens, which keeps the notification live; the hardware FIFO makes
         * this cheap because the SoC sensor hub does the counting either way.
         */
        private const val MAX_REPORT_LATENCY_US = 0

        /** Widest plausible age for a batched sample, used to sanity-check HALs. */
        private const val MAX_EVENT_AGE_MS = 60_000L

        /** 25 Hz. Gait is 1–3 Hz; this is eight samples per step at a run. */
        private const val ACCELEROMETER_PERIOD_US = 40_000

        /** One second of FIFO batching where the hub supports it. */
        private const val ACCELEROMETER_MAX_LATENCY_US = 1_000_000

        private const val WAKE_LOCK_TAG = "steptrackerpro:accelerometer"
        private const val MOTION_WAKE_LOCK_TAG = "steptrackerpro:motion"

        /** How long past its length a window may wait for samples before it is closed anyway. */
        private const val MOTION_CLOSE_GRACE_MS = 3_000L

        /** Quick retries after a failed sensor registration; the heartbeat continues after. */
        private val REGISTER_RETRY_DELAYS_MS = longArrayOf(2_000L, 5_000L, 15_000L, 30_000L)
    }
}
