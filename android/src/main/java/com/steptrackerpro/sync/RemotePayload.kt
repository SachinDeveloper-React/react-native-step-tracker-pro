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
        val sources: List<Map<String, Any?>>
    )

    /** The header carrying [idempotencyKey]. */
    const val IDEMPOTENCY_HEADER = "Idempotency-Key"

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
     *   entry gets `stepSource` of this device and `sources: []`, which is
     *   what a read that was not permitted looks like.
     */
    fun body(
        records: List<DayTotals>,
        shape: Shape,
        sentAt: Long,
        details: Map<String, DayDetail> = emptyMap()
    ): JSONObject = JSONObject().apply {
        put("source", "react-native-step-tracker-pro")
        put("sentAt", sentAt)
        put(
            "records",
            JSONArray().apply {
                records.forEach { record ->
                    put(
                        JSONObject().apply {
                            put("date", record.date)
                            put("steps", record.steps)
                            put("distance", record.distance)
                            put("calories", record.calories)
                            if (shape == Shape.FULL) {
                                // The stored row is this device's own count,
                                // whatever the policy showed the user.
                                put("deviceSteps", record.steps)
                                put("recoveredSteps", record.recoveredSteps)
                                val detail = details[record.date]
                                put("stepSource", JSONObject(detail?.stepSource ?: deviceOnly(record)))
                                put(
                                    "sources",
                                    JSONArray().apply {
                                        detail?.sources?.forEach { put(JSONObject(it)) }
                                    }
                                )
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
        "manualStepsExcluded" to 0
    )
}
