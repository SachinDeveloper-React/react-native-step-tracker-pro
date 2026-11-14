package com.steptrackerpro.integrity

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.steptrackerpro.core.ActivityEvent
import com.steptrackerpro.core.ActivityState
import com.steptrackerpro.core.StepTrackerCore
import com.steptrackerpro.util.PermissionHelper

/**
 * Google's Activity Recognition transitions, when the host app ships Play
 * Services. This package declares `play-services-location` compile-only: an
 * app that adds it gets a motion gate that can hold a ride's steps back
 * (`motionGate`) and steps tagged "in a vehicle" or "still"
 * (`fraudDetection.activityRecognition`); an app that does not never loads
 * a Play Services class. [isAvailable] is checked before anything in [Gms]
 * is touched, so a missing class is never linked.
 *
 * It needs no permission beyond the `ACTIVITY_RECOGNITION` this package
 * already holds to count steps.
 */
object ActivityRecognitionBridge {

    private const val TAG = "StepTrackerAR"
    internal const val ACTION = "com.steptrackerpro.ACTIVITY_TRANSITION"
    private const val REQUEST_CODE = 7741

    /** How far back a transition may be dated. Longer ago than this, it is taken as now. */
    private const val MAX_EVENT_AGE_MS = 5 * 60_000L

    /**
     * Probed through a class literal rather than a name lookup, so an app
     * that ships Play Services and minifies - which renames the class - is
     * still found, and one that does not ship it gets NoClassDefFoundError
     * here, caught, instead of anywhere else.
     */
    fun isAvailable(): Boolean = available

    private val available: Boolean by lazy { runCatching { Gms.probe() }.getOrDefault(false) }

    /**
     * @return true when the request was handed to Play Services. It answers
     *   later, and a refusal then - a Play Services too old, or disabled -
     *   is logged: nothing is held on a registration that never took, which
     *   is the safe way round, and the integrity report's `activity` staying
     *   `unknown` is how it shows.
     */
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
    internal fun pendingIntent(context: Context): PendingIntent {
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

        /** The request as made, for the caller that wants to wait on Play Services' answer. */
        @SuppressLint("MissingPermission") // checked by the caller
        fun request(
            context: Context,
            intent: PendingIntent
        ): com.google.android.gms.tasks.Task<Void> {
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
            return com.google.android.gms.location.ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(
                    com.google.android.gms.location.ActivityTransitionRequest(transitions), intent
                )
                .addOnFailureListener { Log.w(TAG, "Play Services refused the activity transition request", it) }
        }

        @SuppressLint("MissingPermission")
        fun remove(context: Context, intent: PendingIntent) {
            com.google.android.gms.location.ActivityRecognition.getClient(context)
                .removeActivityTransitionUpdates(intent)
        }

        /**
         * Every transition in the intent, oldest first, each with when Play
         * Services says it happened. Empty when the intent carries none.
         *
         * All of them, and not just the last: "left the vehicle" and
         * "entered walking" arrive together, and it is the pair, in order,
         * that says the user got off and walked - the last one alone reads
         * the same whether a bus stopped at a light or its passenger left.
         */
        fun eventsOf(intent: Intent): List<ActivityEvent> {
            if (!com.google.android.gms.location.ActivityTransitionResult.hasResult(intent)) return emptyList()
            val result = com.google.android.gms.location.ActivityTransitionResult.extractResult(intent)
                ?: return emptyList()
            val nowMillis = System.currentTimeMillis()
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            return result.transitionEvents.mapNotNull { event ->
                val state = when (event.activityType) {
                    com.google.android.gms.location.DetectedActivity.STILL -> ActivityState.STILL
                    com.google.android.gms.location.DetectedActivity.WALKING -> ActivityState.WALKING
                    com.google.android.gms.location.DetectedActivity.RUNNING -> ActivityState.RUNNING
                    com.google.android.gms.location.DetectedActivity.ON_BICYCLE -> ActivityState.ON_BICYCLE
                    com.google.android.gms.location.DetectedActivity.IN_VEHICLE -> ActivityState.IN_VEHICLE
                    else -> return@mapNotNull null
                }
                // The event's own clock is time since boot; the steps are
                // placed on the wall clock. Never later than now - an event
                // dated in the future would hold nothing until it came - and
                // never older than a broadcast is ever late by: a timestamp
                // that says hours is a clock this code does not understand,
                // and a ride dated hours back would be past its own limit
                // before it held a step.
                val ageMillis = ((nowNanos - event.elapsedRealTimeNanos) / 1_000_000L)
                    .coerceIn(0L, MAX_EVENT_AGE_MS)
                ActivityEvent(
                    state = state,
                    enter = event.transitionType ==
                        com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                    atMillis = nowMillis - ageMillis
                )
            }
        }
    }
}

/** Receives Play Services' transition results and hands them to the core. */
class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ActivityRecognitionBridge.ACTION) return
        if (!ActivityRecognitionBridge.isAvailable()) return
        val events = runCatching { ActivityRecognitionBridge.Gms.eventsOf(intent) }.getOrNull() ?: return
        if (events.isEmpty()) return
        runCatching { StepTrackerCore.get(context).onActivityEvents(events) }
            .onFailure { Log.w("StepTrackerAR", "Activity transition could not be applied", it) }
    }
}
