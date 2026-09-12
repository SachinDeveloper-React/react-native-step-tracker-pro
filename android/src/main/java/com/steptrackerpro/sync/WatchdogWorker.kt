package com.steptrackerpro.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.steptrackerpro.core.StepTrackerCore
import com.steptrackerpro.service.ServiceCommands
import com.steptrackerpro.util.PermissionHelper

/**
 * Brings the foreground service back when an OEM task killer has removed it.
 *
 * `START_STICKY` asks the OS to restart a killed service, and stock Android
 * does. MIUI, ColorOS, FuntouchOS, EMUI and Samsung's "sleeping apps" do not:
 * they kill the process and drop the restart. The hardware counter keeps
 * counting either way, so no steps are lost - the engine reconciles them on
 * the next sample - but the notification is gone, the database stops being
 * written and midnight rollovers wait until the app is next opened. This job
 * runs every fifteen minutes (WorkManager's floor) and re-launches the service
 * when the persisted state says it should be running and nothing has beaten
 * the heartbeat for a while.
 *
 * On Android 12+ a background start of a foreground service is refused unless
 * the app holds an exemption. A battery-optimisation exemption is one, which
 * is why the OEM guidance asks for it: it is what makes this worker work on
 * exactly the devices that need it. Without one the attempt fails quietly and
 * the React module's foreground restart takes over the next time the app is
 * opened.
 */
class WatchdogWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val core = StepTrackerCore.get(applicationContext)
        val config = core.config()
        if (!config.watchdogEnabled) return Result.success()
        if (!core.serviceLooksDead()) return Result.success()
        if (!PermissionHelper.canStartTracking(applicationContext)) return Result.success()

        val started = ServiceCommands.tryStart(applicationContext, recoveredBy = "watchdog")
        if (!started) {
            Log.w(TAG, "Service looks dead but a background start was refused; " +
                "will retry next period, or on next app foreground")
        }
        return Result.success()
    }

    companion object {
        const val NAME = "stp_watchdog"
        private const val TAG = "StepTrackerWatchdog"
    }
}
