package com.steptrackerpro.integrity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.steptrackerpro.core.DateKeys
import com.steptrackerpro.core.IntegrityEvent
import com.steptrackerpro.core.StepCounterEngine
import com.steptrackerpro.core.StepTrackerCore
import kotlin.math.abs

/**
 * The service-side inputs to the integrity layer: whether the phone is
 * plugged in, when the clock or zone is changed, and Activity Recognition -
 * which the motion gate needs as well, and gets whether or not detection is
 * on. Registered while the service runs; every receiver is a dynamic one,
 * because none of these broadcasts reach a manifest receiver on API 26+,
 * and none is exported.
 */
class IntegritySignals(private val context: Context, private val core: StepTrackerCore) {

    private var registered = false
    private var activityStarted = false

    /** The boot id when the clock was last known, so a clock change can say how far it jumped. */
    private var lastBootId = 0L

    private val power = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> core.integrity.setCharging(true, announce = true)
                Intent.ACTION_POWER_DISCONNECTED -> core.integrity.setCharging(false, announce = true)
            }
        }
    }

    private val clock = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_TIME_CHANGED -> {
                    // A clock edit moves the wall clock and leaves uptime alone,
                    // so the boot id moves by exactly the jump.
                    val now = StepCounterEngine.currentBootId()
                    val jump = now - lastBootId
                    lastBootId = now
                    if (abs(jump) >= MIN_CLOCK_JUMP_MS) {
                        core.integrity.log(IntegrityEvent.CLOCK_CHANGED, mapOf("jumpMs" to jump))
                    }
                }
                Intent.ACTION_TIMEZONE_CHANGED -> core.integrity.log(
                    IntegrityEvent.TIMEZONE_CHANGED,
                    mapOf("timezone" to (intent.getStringExtra("time-zone") ?: DateKeys.zone().id))
                )
            }
        }
    }

    /** Brings registrations in line with config. Called on start, resume and config change. */
    fun apply() {
        val cfg = core.config()
        if (cfg.fraudDetectionEnabled) register() else unregister()
        // For the integrity tags when the app asked for them, and for the
        // motion gate whenever it is on: it holds nothing without this.
        val wantActivity = cfg.wantsActivityRecognition
        if (wantActivity && !activityStarted) {
            activityStarted = ActivityRecognitionBridge.start(context)
            // No request could be made - no Play Services, no permission:
            // nothing is listening, so nothing it said before stands.
            if (!activityStarted) core.onActivityStopped()
        } else if (!wantActivity && activityStarted) {
            ActivityRecognitionBridge.stop(context)
            activityStarted = false
            core.onActivityStopped()
        }
    }

    /**
     * Makes the Activity Recognition request again when the last one was
     * refused, or could not be made for want of the permission. From the
     * service's heartbeat, on the main thread like [apply].
     */
    fun retryActivityRecognition() {
        if (!core.config().wantsActivityRecognition) return
        if (!ActivityRecognitionBridge.shouldRetry(System.currentTimeMillis())) return
        activityStarted = ActivityRecognitionBridge.start(context)
    }

    fun stop() {
        unregister()
        if (activityStarted) {
            ActivityRecognitionBridge.stop(context)
            activityStarted = false
        }
        core.onActivityStopped()
    }

    private fun register() {
        if (registered) return
        ContextCompat.registerReceiver(
            context, power,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            context, clock,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        core.integrity.setCharging(pluggedIn(), announce = false)
        lastBootId = StepCounterEngine.currentBootId()
        registered = true
    }

    private fun unregister() {
        if (!registered) return
        runCatching { context.unregisterReceiver(power) }
        runCatching { context.unregisterReceiver(clock) }
        core.integrity.setCharging(false, announce = false)
        registered = false
    }

    /** The sticky battery broadcast says whether anything is plugged in right now. */
    private fun pluggedIn(): Boolean = runCatching {
        val sticky = ContextCompat.registerReceiver(
            context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }.getOrDefault(false)

    private companion object {
        /** Network time nudges the clock by milliseconds; a person moves it by minutes. */
        const val MIN_CLOCK_JUMP_MS = 1_000L
    }
}
