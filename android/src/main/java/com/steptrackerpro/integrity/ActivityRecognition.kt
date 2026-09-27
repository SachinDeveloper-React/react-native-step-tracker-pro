package com.steptrackerpro.integrity

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.steptrackerpro.core.ActivityState
import com.steptrackerpro.core.StepTrackerCore
import com.steptrackerpro.util.PermissionHelper

/**
 * Google's Activity Recognition transitions, when the host app ships Play
 * Services. This package declares `play-services-location` compile-only: an
 * app that adds it gets steps tagged "in a vehicle" or "still", an app that
 * does not never loads a Play Services class. [isAvailable] is checked
 * before anything in [Gms] is touched, so a missing class is never linked.
 *
 * It needs no permission beyond the `ACTIVITY_RECOGNITION` this package
 * already holds to count steps.
 */
object ActivityRecognitionBridge {

    private const val TAG = "StepTrackerAR"
    internal const val ACTION = "com.steptrackerpro.ACTIVITY_TRANSITION"
    private const val REQUEST_CODE = 7741

    /**
     * Probed through a class literal rather than a name lookup, so an app
     * that ships Play Services and minifies - which renames the class - is
     * still found, and one that does not ship it gets NoClassDefFoundError
     * here, caught, instead of anywhere else.
     */
    fun isAvailable(): Boolean = available

    private val available: Boolean by lazy { runCatching { Gms.probe() }.getOrDefault(false) }

    /** @return true when the request was handed to Play Services. */
    fun start(context: Context): Boolean {
        if (!isAvailable() || !PermissionHelper.hasActivityRecognition(context)) return false
        return runCatching { Gms.request(context, pendingIntent(context)); true }
            .onFailure { Log.w(TAG, "Activity transition request failed", it) }
            .getOrDefault(false)
    }

    fun stop(context: Context) {
        if (!isAvailable()) return
        runCatching { Gms.remove(context, pendingIntent(context)) }
            .onFailure { Log.w(TAG, "Activity transition removal failed", it) }
    }

    /**
     * Mutable because Play Services fills the transition result into the
     * intent's extras; explicit, to this package's own unexported receiver,
     * so nothing else can fire or read it.
     */
    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ActivityTransitionReceiver::class.java).setAction(ACTION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context.applicationContext, REQUEST_CODE, intent, flags)
    }

    /** Every Play Services reference lives here, reached only after [isAvailable]. */
    internal object Gms {

        /**
         * Uses the class, so the lookup cannot be compiled away as an unused
         * expression: a bare class literal statement was, and the probe then
         * reported Play Services on a build that had none.
         */
        fun probe(): Boolean =
            com.google.android.gms.location.ActivityRecognition::class.java.name.isNotEmpty()

        private val TYPES = listOf(
            com.google.android.gms.location.DetectedActivity.STILL,
            com.google.android.gms.location.DetectedActivity.WALKING,
            com.google.android.gms.location.DetectedActivity.RUNNING,
            com.google.android.gms.location.DetectedActivity.ON_BICYCLE,
            com.google.android.gms.location.DetectedActivity.IN_VEHICLE
        )

        @SuppressLint("MissingPermission") // checked by the caller
        fun request(context: Context, intent: PendingIntent) {
            val transitions = TYPES.flatMap { type ->
                listOf(
                    com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                    com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_EXIT
                ).map { direction ->
                    com.google.android.gms.location.ActivityTransition.Builder()
                        .setActivityType(type)
                        .setActivityTransition(direction)
                        .build()
                }
            }
            com.google.android.gms.location.ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(
                    com.google.android.gms.location.ActivityTransitionRequest(transitions), intent
                )
        }

        @SuppressLint("MissingPermission")
        fun remove(context: Context, intent: PendingIntent) {
            com.google.android.gms.location.ActivityRecognition.getClient(context)
                .removeActivityTransitionUpdates(intent)
        }

        /** The state after the last transition in the intent, or null when it carries none. */
        fun stateOf(intent: Intent): ActivityState? {
            if (!com.google.android.gms.location.ActivityTransitionResult.hasResult(intent)) return null
            val result = com.google.android.gms.location.ActivityTransitionResult.extractResult(intent)
                ?: return null
            val last = result.transitionEvents.lastOrNull() ?: return null
            if (last.transitionType ==
                com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_EXIT
            ) {
                return ActivityState.UNKNOWN
            }
            return when (last.activityType) {
                com.google.android.gms.location.DetectedActivity.STILL -> ActivityState.STILL
                com.google.android.gms.location.DetectedActivity.WALKING -> ActivityState.WALKING
                com.google.android.gms.location.DetectedActivity.RUNNING -> ActivityState.RUNNING
                com.google.android.gms.location.DetectedActivity.ON_BICYCLE -> ActivityState.ON_BICYCLE
                com.google.android.gms.location.DetectedActivity.IN_VEHICLE -> ActivityState.IN_VEHICLE
                else -> ActivityState.UNKNOWN
            }
        }
    }
}

/** Receives Play Services' transition results and hands the new state to the core. */
class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ActivityRecognitionBridge.ACTION) return
        if (!ActivityRecognitionBridge.isAvailable()) return
        val state = runCatching { ActivityRecognitionBridge.Gms.stateOf(intent) }.getOrNull() ?: return
        StepTrackerCore.get(context).integrity.onActivity(state)
    }
}
