package com.steptrackerpro.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.steptrackerpro.core.DayTotals
import com.steptrackerpro.core.RemoteSyncAuth
import com.steptrackerpro.core.RemoteSyncStatus
import com.steptrackerpro.core.StepTrackerCore
import com.steptrackerpro.core.SyncTarget
import com.steptrackerpro.integrity.DeviceAttestation
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
        // Opened before config is read: the attempt carries the credentials
        // generation it read, so new ones set while it ran are not reported
        // as refused - by counter, whatever the clock does.
        val attempt = core.state.beginRemoteAttempt()
        val config = core.config()
        val url = config.remoteSyncUrl ?: return Result.success()
        if (!config.remoteSyncAllowHttp && !url.startsWith("https://", ignoreCase = true)) {
            // Health data in the clear is a policy violation and a real leak.
            // Not retryable: the URL will not fix itself.
            core.state.recordRemoteFailure(
                attempt, RemoteSyncStatus.INSECURE_URL, null,
                "remoteSyncUrl must use https (set remoteSyncAllowHttp to override)", retryable = false
            )
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

        // Signature-only auth stores no secret at all, so without a key there
        // is nothing to authenticate with. Not retried: the app attests first.
        val signatureOnly = config.remoteSyncAuth == RemoteSyncAuth.SIGNATURE
        if (signatureOnly && !DeviceAttestation.hasKey()) {
            authRefused(
                core, attempt, status = null, reason = RemoteSyncStatus.NO_KEY,
                auth = config.remoteSyncAuth, pending = pending.size
            )
            return Result.success()
        }

        // The full shape reads Health Connect once per pending day, at upload
        // time, so the server sees the origins as they stood when the batch
        // was built. Pending is normally the handful of days since the last
        // successful upload and each read is bounded and cached by the core,
        // but a provider that is hanging times out per read, and two hundred
        // of those would outlast the worker's own budget - so the whole pass
        // is bounded too, and a day it did not reach uploads as this device's
        // own with no origins, exactly as a day whose read was not permitted.
        val shape = RemotePayload.Shape.from(config.remoteSyncPayload)
        // Each day's suspect steps, from stored verdicts - one read for the batch.
        val suspects = detailOrNull { core.integrity.suspects(pending) } ?: emptyMap()
        val details = HashMap<String, RemotePayload.DayDetail>()
        if (shape == RemotePayload.Shape.FULL) {
            withTimeoutOrNull(DETAIL_TIMEOUT_MS) {
                for (day in pending) {
                    details[day.date] = RemotePayload.DayDetail(
                        stepSource = detailOrNull { core.resolveDay(day.date).toMap() } ?: emptyMap(),
                        sources = detailOrNull { core.unresolvedSources(day.date).map { it.toMap() } }
                            ?: emptyList(),
                        // The device hints are the same for every record and
                        // travel with verification snapshots instead.
                        integrity = if (config.fraudDetectionEnabled) {
                            detailOrNull { core.integrity.report(day.date) - "device" }
                        } else {
                            null
                        }
                    )
                }
            }
        }

        val headers = if (signatureOnly) emptyMap() else config.remoteSyncHeaders
        val status = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            post(url, headers, pending, shape, details, suspects, config.excludeSuspect)
        } ?: NO_RESPONSE
        val outcome = RemotePayload.outcomeOf(status)

        if (outcome == RemotePayload.Outcome.AUTH_REFUSED) {
            // The token expired or the key is not one the server accepted.
            // The same request would be refused again, so it is not retried:
            // JS hears it, refreshes, and calls syncNow().
            authRefused(
                core, attempt,
                status = status,
                reason = if (status == 401) RemoteSyncStatus.UNAUTHORIZED else RemoteSyncStatus.FORBIDDEN,
                auth = config.remoteSyncAuth,
                pending = pending.size
            )
            return Result.success()
        }

        if (outcome == RemotePayload.Outcome.UPLOADED) {
            core.repository.markSynced(SyncTarget.REMOTE, pending.map { it.date })
            core.state.recordRemoteSuccess(attempt)
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

        val error = if (status == NO_RESPONSE) "Upload failed: no response" else "Upload failed: HTTP $status"
        core.state.recordRemoteFailure(
            attempt,
            if (status == NO_RESPONSE) RemoteSyncStatus.NO_RESPONSE else RemoteSyncStatus.HTTP_ERROR,
            status.takeIf { it != NO_RESPONSE },
            error,
            retryable = true
        )
        StepEventBus.emit(
            StepEventBus.Events.SYNC_COMPLETED,
            mapOf(
                "target" to "remote",
                "syncedRecords" to 0,
                "failedRecords" to pending.size,
                "success" to false,
                "error" to error,
                "retryable" to true
            )
        )
        // As above: failure() would retire the periodic work permanently, so an
        // endpoint that is down for a day would never be retried again.
        return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    /**
     * Files the refusal where `getSyncStatus()` finds it - JS is usually not
     * running to hear the event - then tells JS if it is, and reports the
     * batch as failed and not retryable.
     */
    private fun authRefused(
        core: StepTrackerCore,
        attempt: com.steptrackerpro.core.StepStateStore.RemoteAttempt,
        status: Int?,
        reason: String,
        auth: String,
        pending: Int
    ) {
        val error = when (reason) {
            RemoteSyncStatus.NO_KEY -> "remoteSyncAuth 'signature' needs a key from attestDevice()"
            else -> "Upload refused: HTTP $status"
        }
        core.state.recordRemoteFailure(attempt, reason, status, error, retryable = false)
        StepEventBus.emit(
            StepEventBus.Events.SYNC_AUTH_FAILED,
            mapOf("target" to "remote", "status" to status, "reason" to reason, "auth" to auth)
        )
        StepEventBus.emit(
            StepEventBus.Events.SYNC_COMPLETED,
            mapOf(
                "target" to "remote",
                "syncedRecords" to 0,
                "failedRecords" to pending,
                "success" to false,
                "error" to error,
                "retryable" to false
            )
        )
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
        details: Map<String, RemotePayload.DayDetail>,
        suspects: Map<String, Int>,
        excludeSuspect: Boolean
    ): Int = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val body = RemotePayload.body(
                records, shape, System.currentTimeMillis(), details, suspects, excludeSuspect
            ).toString()
            val bytes = body.toByteArray(Charsets.UTF_8)
            // Keyed on what is sent, so an exclusion that grows is new content.
            val sentRecords = records.map { it.copy(steps = RemotePayload.sentSteps(it, suspects, excludeSuspect)) }
            // Signed only once the app has asked for a key; an install that
            // never called attestDevice() uploads exactly as before.
            val signature = if (DeviceAttestation.hasKey()) {
                runCatching { DeviceAttestation.sign(applicationContext, bytes) }.getOrNull()
            } else {
                null
            }

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
                    RemotePayload.idempotencyKey(applicationContext.packageName, sentRecords)
                )
                signature?.let {
                    setRequestProperty(
                        RemotePayload.SIGNATURE_HEADER,
                        RemotePayload.signatureHeader(
                            it["keyId"] as String, it["algorithm"] as String, it["value"] as String
                        )
                    )
                }
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
            }
            code
        } catch (error: Exception) {
            NO_RESPONSE
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        const val NAME = "stp_remote_sync"
        private const val MAX_ATTEMPTS = 5
        private const val REQUEST_TIMEOUT_MS = 45_000L

        /** No HTTP status: a timeout, a refused connection, a TLS failure. */
        private const val NO_RESPONSE = -1

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
