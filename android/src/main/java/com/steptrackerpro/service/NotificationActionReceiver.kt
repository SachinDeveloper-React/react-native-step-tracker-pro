package com.steptrackerpro.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Routes the notification's Pause/Resume buttons back into the service. */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ServiceCommands.ACTION_PAUSE,
            ServiceCommands.ACTION_RESUME,
            ServiceCommands.ACTION_STOP -> ServiceCommands.send(context, intent.action!!)
        }
    }
}
