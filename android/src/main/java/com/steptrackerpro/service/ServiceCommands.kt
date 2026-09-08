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

    fun intent(context: Context, action: String): Intent =
        Intent(context, StepTrackerService::class.java).setAction(action)

    fun start(context: Context, fromBoot: Boolean = false) {
        val intent = intent(context, ACTION_START).putExtra(EXTRA_FROM_BOOT, fromBoot)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

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
