package com.steptrackerpro.service

import android.content.Context
import android.content.Intent
import android.os.Build

/** Every entry point into [StepTrackerService] goes through here. */
object ServiceCommands {

    const val ACTION_START = "com.steptrackerpro.action.START"
    const val ACTION_PAUSE = "com.steptrackerpro.action.PAUSE"
    const val ACTION_RESUME = "com.steptrackerpro.action.RESUME"
    const val ACTION_STOP = "com.steptrackerpro.action.STOP"
    const val ACTION_CONFIG_CHANGED = "com.steptrackerpro.action.CONFIG_CHANGED"
    const val ACTION_OPEN_APP = "com.steptrackerpro.action.OPEN_APP"
    const val ACTION_REFRESH = "com.steptrackerpro.action.REFRESH"

    const val EXTRA_FROM_BOOT = "from_boot"

    /** Set when something other than the user brought the service back: `watchdog`, `foreground`, `initialize`. */
    const val EXTRA_RECOVERED_BY = "recovered_by"

    fun intent(context: Context, action: String): Intent =
        Intent(context, StepTrackerService::class.java).setAction(action)

    /**
     * Starts the service. Throws on Android 12+ when called from the
     * background without an exemption (`ForegroundServiceStartNotAllowedException`);
     * callers that may be in the background use [tryStart].
     */
    fun start(context: Context, fromBoot: Boolean = false, recoveredBy: String? = null) {
        val intent = intent(context, ACTION_START)
            .putExtra(EXTRA_FROM_BOOT, fromBoot)
            .apply { if (recoveredBy != null) putExtra(EXTRA_RECOVERED_BY, recoveredBy) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /** [start] that reports failure instead of throwing. */
    fun tryStart(context: Context, fromBoot: Boolean = false, recoveredBy: String? = null): Boolean =
        runCatching { start(context, fromBoot, recoveredBy); true }.getOrDefault(false)

    fun send(context: Context, action: String) {
        val intent = intent(context, action)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
