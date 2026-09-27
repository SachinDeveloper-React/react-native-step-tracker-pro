package com.steptrackerpro.util

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The service and the React module live in the same process, so events go
 * through a plain in-memory bus rather than broadcasts. The module attaches on
 * `initialize` and detaches on catalyst teardown; the service keeps running and
 * writing to storage whether or not anyone is listening.
 */
object StepEventBus {

    /** Names match the JS side minus the `StepTrackerPro:` prefix. */
    object Events {
        const val STEPS_CHANGED = "stepsChanged"
        const val GOAL_REACHED = "goalReached"
        const val GOAL_PROGRESS_CHANGED = "goalProgressChanged"
        const val TRACKING_STATE_CHANGED = "trackingStateChanged"
        const val DAY_CHANGED = "dayChanged"
        /**
         * A past day's stored total grew after the fact - gap recovery placed
         * steps on it. Fired once per affected day, after the write commits,
         * so an app that has already settled that day knows to look again.
         */
        const val HISTORY_BACKFILLED = "historyBackfilled"
        /** One motion signature window was stored. Payload is its features, never samples. */
        const val MOTION_WINDOW = "motionWindow"
        /**
         * The integrity checks found something they had not reported for
         * this day before. Payload is the new flags, the day's device count
         * and its suspect steps.
         */
        const val SUSPICIOUS_ACTIVITY = "suspiciousActivity"
        const val SYNC_COMPLETED = "syncCompleted"
        /** The app now counting for the user changed - watch on, watch off. */
        const val STEP_SOURCE_CHANGED = "stepSourceChanged"
        /** Health Connect was installed, updated, granted or revoked. */
        const val HEALTH_CONNECT_STATUS_CHANGED = "healthConnectStatusChanged"
        const val ERROR = "error"
    }

    fun interface Subscriber {
        fun onEvent(name: String, payload: Map<String, Any?>)
    }

    private val subscribers = CopyOnWriteArrayList<Subscriber>()

    /**
     * Identity-based: a subscriber added twice is delivered to twice. There is
     * no useful dedupe to do here, because every caller passes a freshly
     * SAM-converted lambda that is never equal to any other. The contract is
     * that whoever subscribes also unsubscribes — the React module does so in
     * `invalidate()`, and a subscriber that outlives its context is inert
     * because `emit` checks for a live React instance first.
     */
    fun subscribe(subscriber: Subscriber) {
        subscribers.add(subscriber)
    }

    fun unsubscribe(subscriber: Subscriber) {
        subscribers.remove(subscriber)
    }

    fun emit(name: String, payload: Map<String, Any?>) {
        subscribers.forEach { subscriber ->
            runCatching { subscriber.onEvent(name, payload) }
        }
    }

    fun hasSubscribers(): Boolean = subscribers.isNotEmpty()
}
