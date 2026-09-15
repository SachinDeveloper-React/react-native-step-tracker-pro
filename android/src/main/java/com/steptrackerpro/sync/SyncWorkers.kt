package com.steptrackerpro.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.steptrackerpro.core.DayTotals
import com.steptrackerpro.core.StepTrackerCore
import com.steptrackerpro.core.SyncTarget
import com.steptrackerpro.util.StepEventBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mirrors unsynced day rows into Health Connect. Runs without a network
 * constraint because Health Connect is entirely on-device — offline sync is
 * still sync.
 */
class HealthConnectSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val core = StepTrackerCore.get(applicationContext)
        val config = core.config()
        if (!config.healthConnectEnabled) return Result.success()

        val result = core.syncHealthConnect()
        return when {
            result["success"] == true -> Result.success()
            // Provider missing, permissions not granted, disabled in config:
            // states only the user can change. Retrying backs off and tries
            // again forever on a device that will never succeed.
            result["retryable"] != true -> Result.success()
            runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            // Result.failure() on a PeriodicWorkRequest ends the whole periodic
            // chain, not just this attempt. Give up on this period instead and
            // let the next one come round.
            else -> Result.success()
        }
    }

    companion object {
        const val NAME = "stp_health_connect_sync"
        private const val MAX_ATTEMPTS = 5
    }
}

/**
 * Ships unsynced day rows to the configured HTTPS endpoint. This is the only
 * part of the package that needs the internet, and nothing else waits on it.
 */
class RemoteSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val core = StepTrackerCore.get(applicationContext)
        val config = core.config()
        val url = config.remoteSyncUrl ?: return Result.success()
        if (!config.remoteSyncAllowHttp && !url.startsWith("https://", ignoreCase = true)) {
            // Health data in the clear is a policy violation and a real leak.
            // Not retryable: the URL will not fix itself.
            StepEventBus.emit(
                StepEventBus.Events.SYNC_COMPLETED,
                mapOf(
                    "target" to "remote",
                    "syncedRecords" to 0,
                    "failedRecords" to 0,
                    "success" to false,
                    "error" to "remoteSyncUrl must use https (set remoteSyncAllowHttp to override)",
                    "retryable" to false
                )
            )
            return Result.success()
        }

        val pending = core.repository.unsynced(SyncTarget.REMOTE, limit = 200)
        if (pending.isEmpty()) return Result.success()

        // The full shape reads Health Connect once per pending day, at upload
        // time, so the server sees the origins as they stood when the batch
        // was built. Pending is normally the handful of days since the last
        // successful upload and each read is bounded and cached by the core,
        // but a provider that is hanging times out per read, and two hundred
        // of those would outlast the worker's own budget - so the whole pass
        // is bounded too, and a day it did not reach uploads as this device's
        // own with no origins, exactly as a day whose read was not permitted.
        val shape = RemotePayload.Shape.from(config.remoteSyncPayload)
        val details = HashMap<String, RemotePayload.DayDetail>()
        if (shape == RemotePayload.Shape.FULL) {
            withTimeoutOrNull(DETAIL_TIMEOUT_MS) {
                for (day in pending) {
                    details[day.date] = RemotePayload.DayDetail(
                        stepSource = detailOrNull { core.resolveDay(day.date).toMap() } ?: emptyMap(),
                        sources = detailOrNull { core.unresolvedSources(day.date).map { it.toMap() } }
                            ?: emptyList()
                    )
                }
            }
        }

        val ok = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            post(url, config.remoteSyncHeaders, pending, shape, details)
        } ?: false

        if (ok) {
            core.repository.markSynced(SyncTarget.REMOTE, pending.map { it.date })
            StepEventBus.emit(
                StepEventBus.Events.SYNC_COMPLETED,
                mapOf(
                    "target" to "remote",
                    "syncedRecords" to pending.size,
                    "failedRecords" to 0,
                    "success" to true
                )
            )
            return Result.success()
        }

        StepEventBus.emit(
            StepEventBus.Events.SYNC_COMPLETED,
            mapOf(
                "target" to "remote",
                "syncedRecords" to 0,
                "failedRecords" to pending.size,
                "success" to false,
                "error" to "Upload failed"
            )
        )
        // As above: failure() would retire the periodic work permanently, so an
        // endpoint that is down for a day would never be retried again.
        return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    /**
     * A failed read of one day is that day's problem, not the batch's - but a
     * cancellation is the timeout above ending the pass, and swallowing it
     * would have the loop spin through every remaining day instead of stopping.
     */
    private inline fun <T> detailOrNull(block: () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun post(
        url: String,
        headers: Map<String, String>,
        records: List<DayTotals>,
        shape: RemotePayload.Shape,
        details: Map<String, RemotePayload.DayDetail>
    ): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val body = RemotePayload.body(records, shape, System.currentTimeMillis(), details).toString()

            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                // Set before the app's own headers, so an app that wants to
                // name the key itself can still override it.
                setRequestProperty(
                    RemotePayload.IDEMPOTENCY_HEADER,
                    RemotePayload.idempotencyKey(applicationContext.packageName, records)
                )
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
            }
            code in 200..299
        } catch (error: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        const val NAME = "stp_remote_sync"
        private const val MAX_ATTEMPTS = 5
        private const val REQUEST_TIMEOUT_MS = 45_000L

        /** Most of the worker's budget the `full` shape may spend reading Health Connect. */
        private const val DETAIL_TIMEOUT_MS = 120_000L
    }
}

/** Enforces the retention window so the database cannot grow without bound. */
class RetentionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val core = StepTrackerCore.get(applicationContext)
        core.repository.prune(core.config().historyRetentionDays)
        return Result.success()
    }

    companion object {
        const val NAME = "stp_retention"
    }
}
