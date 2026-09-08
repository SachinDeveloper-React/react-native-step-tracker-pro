package com.steptrackerpro.service

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
import android.os.SystemClock
import android.util.Log
import com.steptrackerpro.core.SensorSource
import com.steptrackerpro.core.StepTrackerCore
import com.steptrackerpro.core.TrackingState
import com.steptrackerpro.sync.SyncScheduler
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
    private var listening = false

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

    override fun onCreate() {
        super.onCreate()
        core = StepTrackerCore.get(this)
        notifications = NotificationFactory(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
        counterSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        detectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        sensorThread = HandlerThread("stp-sensor").also {
            it.start()
            sensorHandler = Handler(it.looper)
        }
        notifications.ensureChannel(core.config())
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
                pushNotification(force = true)
            }
            ServiceCommands.ACTION_REFRESH -> pushNotification(force = true)
            else -> {
                val fromBoot =
                    intent?.getBooleanExtra(ServiceCommands.EXTRA_FROM_BOOT, false) == true
                // A restart or a boot must honour what the user last asked for
                // rather than starting fresh, pause included.
                startTracking(fromBoot = fromBoot, restore = !explicitStart || fromBoot)
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

        // `paused` lives in memory only, so a restart has to read it back off
        // the persisted tracking state. setPaused() ignores a no-op transition,
        // which is what keeps the steps the hardware counted while the process
        // was dead from being discarded by a spurious re-anchor.
        val paused = restore && core.state.trackingState == TrackingState.PAUSED
        core.engine.setPaused(paused)
        core.engine.reconcile()

        if (!registerSensors()) {
            core.state.trackingState = TrackingState.UNSUPPORTED
            core.state.source = SensorSource.NONE
            StepEventBus.emit(
                StepEventBus.Events.ERROR,
                mapOf(
                    "code" to "E_NO_SENSOR",
                    "message" to "This device exposes neither TYPE_STEP_COUNTER nor TYPE_STEP_DETECTOR"
                )
            )
            core.emitTrackingState("no_sensor")
            stopSelf()
            return
        }

        core.state.trackingState =
            if (paused) TrackingState.PAUSED else TrackingState.RUNNING
        SyncScheduler.schedule(this, core.config())
        pushNotification(force = true)
        core.emitTrackingState(
            when {
                fromBoot -> "boot"
                restore -> "restored"
                else -> "started"
            }
        )
    }

    private fun pause() {
        core.engine.setPaused(true)
        core.state.trackingState = TrackingState.PAUSED
        core.flush()
        pushNotification(force = true)
        core.emitTrackingState("paused")
    }

    private fun resume() {
        core.engine.setPaused(false)
        core.engine.reconcile()
        if (!listening) registerSensors()
        core.state.trackingState = TrackingState.RUNNING
        pushNotification(force = true)
        core.emitTrackingState("resumed")
    }

    private fun stopTracking() {
        core.state.shouldAutoStart = false
        core.state.trackingState = TrackingState.STOPPED
        core.flush()
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

        return false
    }

    private fun unregisterSensors() {
        if (!listening) return
        runCatching { sensorManager?.unregisterListener(this) }
        listening = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val sensorEvent = event ?: return
        val values = sensorEvent.values
        if (values == null || values.isEmpty()) return

        val snapshot = when (sensorEvent.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> core.engine.onCounterSample(values[0], eventTime(sensorEvent))
            Sensor.TYPE_STEP_DETECTOR -> core.engine.onDetectorSample(1, eventTime(sensorEvent))
            else -> null
        } ?: return

        core.onStepsChanged(snapshot)
        pushNotification()
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
        val notification = notifications.build(core.engine.snapshot(), config, core.metrics)
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
        val snapshot = core.engine.snapshot()
        if (!force) {
            if (snapshot.steps == lastNotifiedSteps) return
            if (now - lastNotificationAt < config.notificationThrottleMs) return
        }
        lastNotificationAt = now
        lastNotifiedSteps = snapshot.steps
        notifications.update(notifications.build(snapshot, config, core.metrics))
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
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
         * Batch window for the step counter. 0 delivers every sample as it
         * happens, which keeps the notification live; the hardware FIFO makes
         * this cheap because the SoC sensor hub does the counting either way.
         */
        private const val MAX_REPORT_LATENCY_US = 0

        /** Widest plausible age for a batched sample, used to sanity-check HALs. */
        private const val MAX_EVENT_AGE_MS = 60_000L
    }
}
