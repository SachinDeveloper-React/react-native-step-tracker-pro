package com.steptrackerpro.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
        // and a server comparing snapshots wants to know why. Only once per
        // real boot, though - QUICKBOOT_POWERON is unprotected, so any app can
        // send it, and some devices deliver it next to BOOT_COMPLETED.
        if (action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            logRebootIfNew(context, core)
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

    /**
     * `Settings.Global.BOOT_COUNT` only moves when the device really boots,
     * and no app can write it. A broadcast that arrives with the count we
     * already logged is a duplicate or a forgery, and is not logged. A device
     * that does not expose the count - rare, pre-N firmware that trimmed it -
     * is logged with `verified: false`, for a server to weigh.
     */
    private fun logRebootIfNew(context: Context, core: StepTrackerCore) {
        val count = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1)
        if (count < 0) {
            core.integrity.log(
                com.steptrackerpro.core.IntegrityEvent.REBOOT,
                mapOf("bootCount" to null, "verified" to false)
            )
            return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_LAST_BOOT_COUNT, -1) == count) return
        prefs.edit().putInt(KEY_LAST_BOOT_COUNT, count).apply()
        core.integrity.log(
            com.steptrackerpro.core.IntegrityEvent.REBOOT,
            mapOf("bootCount" to count, "verified" to true)
        )
    }

    companion object {
        private const val TAG = "StepTrackerBoot"
        private const val PREFS = "StepTrackerProBoot"
        private const val KEY_LAST_BOOT_COUNT = "last_logged_boot_count"

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
