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

    /** How long after a refused request it is made again. */
    const val RETRY_AFTER_MS = 5 * 60_000L

    const val STATUS_NOT_REQUESTED = "not_requested"
    const val STATUS_UNAVAILABLE = "unavailable"
    const val STATUS_NO_PERMISSION = "no_permission"
    const val STATUS_PENDING = "pending"
    const val STATUS_REGISTERED = "registered"
    const val STATUS_FAILED = "failed"

    /**
     * Probed through a class literal rather than a name lookup, so an app
     * that ships Play Services and minifies - which renames the class - is
     * still found, and one that does not ship it gets NoClassDefFoundError
     * here, caught, instead of anywhere else.
     */
    fun isAvailable(): Boolean = available

    private val available: Boolean by lazy { runCatching { Gms.probe() }.getOrDefault(false) }

    /**
     * Where the registration stands: one of the `STATUS_` values. What the
     * integrity report says beside `available`, which is only whether the
     * app ships the library - a Play Services that refused the request, or
     * a permission not granted, used to show nowhere but in logcat.
     */
    @Volatile
    var status: String = STATUS_NOT_REQUESTED
        private set

    /** Why the last request failed, when it did. */
    @Volatile
    var lastError: String? = null
        private set

    /** When the last request failed, epoch ms; 0 when none has. */
    @Volatile
    var failedAt: Long = 0L
        private set

    /**
     * Asks Play Services for transitions. It answers later: [status] turns
     * `registered` or `failed` then, and a failure is retried - see
     * [shouldRetry].
     *
     * @return true when the request was handed to Play Services.
     */
    fun start(context: Context): Boolean {
        if (!isAvailable()) {
            status = STATUS_UNAVAILABLE
            return false
        }
        if (!PermissionHelper.hasActivityRecognition(context)) {
            status = STATUS_NO_PERMISSION
            return false
        }
        status = STATUS_PENDING
        return runCatching {
            Gms.request(context, pendingIntent(context))
                .addOnSuccessListener {
                    status = STATUS_REGISTERED
                    lastError = null
                }
                .addOnFailureListener { error -> failed(error) }
            true
        }.onFailure { failed(it) }.getOrDefault(false)
    }

    private fun failed(error: Throwable) {
        Log.w(TAG, "Activity transition request failed", error)
        status = STATUS_FAILED
        lastError = error.message ?: error.javaClass.simpleName
        failedAt = System.currentTimeMillis()
    }

    /**
     * Whether a request worth making again: one that failed at least
     * [RETRY_AFTER_MS] ago, or one that could not be made for want of the
     * permission, which the user may have granted since.
     */
    fun shouldRetry(nowMs: Long): Boolean = when (status) {
        STATUS_FAILED -> nowMs - failedAt >= RETRY_AFTER_MS
        STATUS_NO_PERMISSION -> true
        else -> false
    }

    /**
     * Whether the request an earlier process made can still be answered:
     * the app ships Play Services, holds the permission, and the
     * `PendingIntent` the transitions are sent to still exists. The system
     * keeps one across a killed process and drops it on a reboot, a force
     * stop - which is what some phones' task killers do - an update or
     * cleared data; Play Services drops the request with it. Asked before
     * this process makes the request again, which would create it.
     */
    fun registrationSurvives(context: Context): Boolean {
        if (!isAvailable() || !PermissionHelper.hasActivityRecognition(context)) return false
        return runCatching {
            val intent = Intent(context, ActivityTransitionReceiver::class.java).setAction(ACTION)
            val flags = PendingIntent.FLAG_NO_CREATE or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            PendingIntent.getBroadcast(context.applicationContext, REQUEST_CODE, intent, flags) != null
        }.getOrDefault(false)
    }

    fun stop(context: Context) {
        status = STATUS_NOT_REQUESTED
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
