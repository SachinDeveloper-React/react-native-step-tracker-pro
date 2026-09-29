package com.steptrackerpro.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.steptrackerpro.core.StepTrackerConfig
import java.util.concurrent.TimeUnit

/**
 * All WorkManager wiring.
 *
 * The two syncs use UPDATE rather than KEEP so a config change takes effect on
 * the next run; UPDATE preserves the existing work's `lastEnqueueTime` and
 * `periodCount`, so calling this on every app launch does not push the next run
 * out and starve the job the way REPLACE would. Retention and the watchdog use
 * KEEP: their parameters never change, and re-enqueueing them would only
 * reset their period.
 */
object SyncScheduler {

    fun schedule(context: Context, config: StepTrackerConfig) {
        val work = WorkManager.getInstance(context)

        // The sync worker only ever writes, so an app that reads without
        // mirroring (healthConnectWriteEnabled = false) has nothing for it to
        // do and does not get a job that wakes up every half hour to say so.
        if (config.healthConnectEnabled && config.healthConnectWriteEnabled &&
            config.healthConnectSyncIntervalMinutes > 0
        ) {
            val interval = config.healthConnectSyncIntervalMinutes.toLong()
                .coerceAtLeast(15L) // WorkManager's minimum period
            work.enqueueUniquePeriodicWork(
                HealthConnectSyncWorker.NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<HealthConnectSyncWorker>(interval, TimeUnit.MINUTES)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                    .build()
            )
        } else {
            work.cancelUniqueWork(HealthConnectSyncWorker.NAME)
        }

        if (!config.remoteSyncUrl.isNullOrEmpty()) {
            work.enqueueUniquePeriodicWork(
                RemoteSyncWorker.NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<RemoteSyncWorker>(1, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                    .build()
            )
        } else {
            work.cancelUniqueWork(RemoteSyncWorker.NAME)
        }

        work.enqueueUniquePeriodicWork(
            RetentionWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build()
        )

        if (!config.watchdogEnabled) work.cancelUniqueWork(WatchdogWorker.NAME)
    }

    /**
     * Enqueued by the service when tracking starts, not on every config
     * write: an app in Health-Connect-only mode never runs the service and
     * has nothing for a watchdog to watch.
     */
    fun scheduleWatchdog(context: Context, config: StepTrackerConfig) {
        val work = WorkManager.getInstance(context)
        if (!config.watchdogEnabled) {
            work.cancelUniqueWork(WatchdogWorker.NAME)
            return
        }
        work.enqueueUniquePeriodicWork(
            WatchdogWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
        )
    }

    /** Fires both syncs immediately, ignoring the periodic schedule. */
    fun runNow(context: Context, config: StepTrackerConfig) {
        val work = WorkManager.getInstance(context)
        if (config.healthConnectEnabled) {
            work.enqueueUniqueWork(
                "${HealthConnectSyncWorker.NAME}_now",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<HealthConnectSyncWorker>().build()
            )
        }
        if (!config.remoteSyncUrl.isNullOrEmpty()) {
            work.enqueueUniqueWork(
                "${RemoteSyncWorker.NAME}_now",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<RemoteSyncWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    // Reports even when there is nothing to send: the caller
                    // was told to listen for the outcome.
                    .setInputData(workDataOf(RemoteSyncWorker.KEY_ON_DEMAND to true))
                    .build()
            )
        }
    }

    /**
     * Drops every periodic job, retention included: once tracking is stopped the
     * database is not growing, so a daily wake-up to prune it is pure cost.
     * [schedule] re-enqueues all three when tracking starts again.
     */
    fun cancelAll(context: Context) {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(HealthConnectSyncWorker.NAME)
        work.cancelUniqueWork(RemoteSyncWorker.NAME)
        work.cancelUniqueWork(RetentionWorker.NAME)
        work.cancelUniqueWork(WatchdogWorker.NAME)
    }
}
