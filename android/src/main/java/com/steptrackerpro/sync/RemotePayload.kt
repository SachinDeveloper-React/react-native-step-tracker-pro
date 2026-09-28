package com.steptrackerpro.sync

import com.steptrackerpro.core.DayTotals
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The body [RemoteSyncWorker] posts and the key that makes a retry of it
 * recognisable. Pure - no Android, no network - so the shape an endpoint
 * depends on is pinned by JVM tests rather than by nobody noticing.
 */
object RemotePayload {

    /** Which fields each record carries. `totals` is the shape every release before 1.4 sent. */
    enum class Shape(val jsValue: String) {
        /** `{ date, steps, distance, calories }` - the 1.3 shape, byte for byte. */
        TOTALS("totals"),

        /**
         * `totals` plus, per record, this device's own `deviceSteps` and
         * `recoveredSteps`, the `stepSource` the policy resolved to, and the
         * unresolved Health Connect `sources` for the day. For a server that
         * never trusts a single number.
         */
        FULL("full");

        companion object {
            fun from(value: String?): Shape = entries.firstOrNull { it.jsValue == value } ?: TOTALS
        }
    }

    /**
     * What the `full` shape adds for one day. `stepSource` is
     * `StepSourceResolver.Resolution.toMap()`; `sources` is each
     * `StepSource.toMap()`, empty when Health Connect could not be read.
     */
    class DayDetail(
        val stepSource: Map<String, Any?>,
        val sources: List<Map<String, Any?>>,
        /**
         * The day's integrity report, when `fraudDetection.enabled`. Its
         * presence is what adds `suspectSteps` and `integrity` to a `full`
         * record; without it the record is exactly the 1.4 shape.
         */
        val integrity: Map<String, Any?>? = null,
        /**
         * How [sources] was read - one of
         * [com.steptrackerpro.core.HealthConnectRead] - so an empty list is
         * never mistaken for "no other apps".
         */
        val sourcesStatus: String = com.steptrackerpro.core.HealthConnectRead.READ
    )

    /** The header carrying [idempotencyKey]. */
    const val IDEMPOTENCY_HEADER = "Idempotency-Key"

    /**
     * The header carrying a signature over the exact body bytes, sent when
     * the install has a Keystore key (see `attestDevice()`). A server checks
     * it against the public key it stored when it accepted the attestation.
     */
    const val SIGNATURE_HEADER = "Step-Tracker-Signature"

    /** What an upload's HTTP status means for the batch. */
    enum class Outcome {
        /** 2xx: the rows are marked uploaded. */
        UPLOADED,

        /**
         * 401 or 403: the credentials were refused. Retrying with the same
         * ones cannot help, so the batch waits for the app to refresh them.
         */
        AUTH_REFUSED,

        /** Anything else, or no response at all: worth another attempt. */
        RETRY
    }

    fun outcomeOf(status: Int): Outcome = when (status) {
        in 200..299 -> Outcome.UPLOADED
        401, 403 -> Outcome.AUTH_REFUSED
        else -> Outcome.RETRY
    }

    /** `keyId=<hex>;alg=SHA256withECDSA;sig=<base64>`, the [SIGNATURE_HEADER] value. */
    fun signatureHeader(keyId: String, algorithm: String, signature: String): String =
        "keyId=$keyId;alg=$algorithm;sig=$signature"

    /**
     * The step count a record is sent with: this device's count, less the
     * day's suspect steps when exclude mode is on.
     */
    fun sentSteps(record: DayTotals, suspects: Map<String, Int>, excludeSuspect: Boolean): Int {
        if (!excludeSuspect) return record.steps
        val suspect = (suspects[record.date] ?: 0).coerceIn(0, record.steps.coerceAtLeast(0))
        return record.steps - suspect
    }

    /**
     * A stable digest of what the batch says: the package, and every record's
     * date and step count, sorted by date so the order rows came out of the
     * database does not matter. Two uploads with the same key carry the same
     * numbers and a server may treat the second as a retry; a day whose count
     * has since grown - a backfill - produces a new key, because it is new
     * content. Distance and calories are derived from steps and left out.
     * WorkManager retries a failed batch verbatim, and `syncNow()` can queue
     * the same pending rows the periodic job is about to send, so without
     * this a server has to guess which of two identical bodies is the echo.
     */
    fun idempotencyKey(packageName: String, records: List<DayTotals>): String {
        val text = buildString {
            append(packageName).append('\n')
            records.sortedBy { it.date }.forEach { append(it.date).append(':').append(it.steps).append('\n') }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * @param details per-date detail for the `full` shape; a record with no
     *   entry, or one whose resolution came back empty because the read
     *   failed, gets `stepSource` of this device and `sources: []`, which is
     *   what a read that was not permitted looks like.
     */
    fun body(
        records: List<DayTotals>,
        shape: Shape,
        sentAt: Long,
        details: Map<String, DayDetail> = emptyMap(),
        /** Each day's suspect steps; empty when detection is off. */
        suspects: Map<String, Int> = emptyMap(),
        /** Take them out of `steps`, as `fraudDetection.mode: 'exclude'` does everywhere. */
        excludeSuspect: Boolean = false
    ): JSONObject = JSONObject().apply {
        put("source", "react-native-step-tracker-pro")
        put("sentAt", sentAt)
        put(
            "records",
            JSONArray().apply {
                records.forEach { record ->
                    val sent = sentSteps(record, suspects, excludeSuspect)
                    // Distance and calories are proportional to steps, so
                    // they scale down with an exclusion rather than being
                    // re-derived from a stride this object does not know.
                    val scale = if (record.steps > 0 && sent != record.steps) {
                        sent.toDouble() / record.steps
                    } else {
                        1.0
                    }
                    put(
                        JSONObject().apply {
                            put("date", record.date)
                            put("steps", sent)
                            put("distance", record.distance * scale)
                            put("calories", record.calories * scale)
                            if (shape == Shape.FULL) {
                                // The stored row is this device's own count,
                                // whatever the policy showed the user.
                                put("deviceSteps", record.steps)
                                put("recoveredSteps", record.recoveredSteps)
                                val detail = details[record.date]
                                val stepSource = detail?.stepSource?.takeIf { it.isNotEmpty() }
                                    ?: deviceOnly(record)
                                put("stepSource", JSONObject(stepSource))
                                put(
                                    "sources",
                                    JSONArray().apply {
                                        detail?.sources?.forEach { put(JSONObject(it)) }
                                    }
                                )
                                // A day the upload's detail pass did not reach
                                // before its budget ran out has no detail.
                                put(
                                    "sourcesStatus",
                                    detail?.sourcesStatus ?: com.steptrackerpro.core.HealthConnectRead.TIMED_OUT
                                )
                                detail?.integrity?.let { report ->
                                    put("suspectSteps", suspects[record.date] ?: 0)
                                    put("integrity", JSONObject(report))
                                }
                            }
                        }
                    )
                }
            }
        )
    }

    /** The `stepSource` of a day nothing external answered for, in the resolver's shape. */
    private fun deviceOnly(record: DayTotals): Map<String, Any?> = mapOf(
        "date" to record.date,
        "steps" to record.steps,
        "kind" to "self",
        "packageName" to null,
        "appName" to "This device",
        "deviceSteps" to record.steps,
        "externalSteps" to 0,
        "usedExternal" to false,
        "merged" to false,
        "baselineSteps" to 0,
        "manualStepsExcluded" to 0,
        "suspectStepsExcluded" to 0
    )
}
