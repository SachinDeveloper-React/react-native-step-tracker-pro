package com.steptrackerpro.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.steptrackerpro.core.StepTrackerCore
import com.steptrackerpro.util.PermissionHelper

/**
 * Brings tracking back after a reboot or an app update.
 *
 * The receiver only restarts when the user had tracking on before, so an app
 * that has never called `startTracking()` never spawns a service at boot. The
 * counter itself is reconciled inside the engine — the receiver's job is just
 * to get the service running again so the notification and the database writes
 * resume.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return

        val core = StepTrackerCore.get(context)
        if (!core.isInitialized()) return
        // Logged whether or not tracking comes back: the counter restarted,
        // and a server comparing snapshots wants to know why.
        if (action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            core.integrity.log(com.steptrackerpro.core.IntegrityEvent.REBOOT)
        }
        if (!core.config().autoStartOnBoot) return
        if (!core.state.shouldAutoStart) return

        if (!PermissionHelper.canStartTracking(context)) {
            Log.w(TAG, "Skipping boot restart: ACTIVITY_RECOGNITION not granted")
            return
        }

        // Re-anchor against the new boot before the first sample arrives.
        core.engine.reconcile()

        runCatching { ServiceCommands.start(context, fromBoot = true) }
            .onFailure { Log.e(TAG, "Boot restart failed", it) }
    }

    companion object {
        private const val TAG = "StepTrackerBoot"

        // LOCKED_BOOT_COMPLETED is deliberately absent: it is only delivered to
        // direct-boot-aware components, and the counter state lives in
        // credential-encrypted storage that is not readable before unlock.
        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON"
        )
    }
}
