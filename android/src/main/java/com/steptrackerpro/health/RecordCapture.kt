package com.steptrackerpro.health

import com.steptrackerpro.health.HealthConnectManager.ReadOutcome
import com.steptrackerpro.health.HealthConnectManager.RecordType

/**
 * The raw records of [types] that a per-source read of one day pages
 * through anyway, kept as it goes - for a verification snapshot that asks
 * for the day's `sources` and its `healthConnectRecords` both. It used to
 * read the day twice: once record by record for the sources, then again for
 * the records, every page of it a second time against Health Connect's
 * rate limits. See [HealthConnectManager.readSourcesByDay].
 *
 * A type the per-source read does not go through - distance, when it is not
 * in `healthConnectReadTypes` - is left in [missing], to be read on its own
 * and added with [include]. Pure, so the shape it hands back is pinned by
 * JVM tests.
 */
class RecordCapture(
    val types: Set<RecordType>,
    /** Records kept per type, as for a raw read - see [HealthConnectManager.RAW_RECORDS_LIMIT]. */
    private val limit: Int = HealthConnectManager.RAW_RECORDS_LIMIT
) {
    private val records = ArrayList<Map<String, Any?>>()
    private val kept = HashMap<RecordType, Int>()
    private val outcomes = HashMap<RecordType, ReadOutcome>()
    private var truncated = false
    private var rateLimited = false

    fun wants(type: RecordType): Boolean = type in types

    /** One record of [type], as [HealthConnectManager.recordMap] has it. Past [limit] the list is cut. */
    @Synchronized
    fun add(type: RecordType, record: Map<String, Any?>) {
        val count = kept[type] ?: 0
        if (count >= limit) {
            truncated = true
            return
        }
        kept[type] = count + 1
        records += record
    }

    /** The read of [type] ended as [outcome]; [failure] is why, when it failed. */
    @Synchronized
    fun finished(type: RecordType, outcome: ReadOutcome, failure: Throwable? = null) {
        if (type !in types) return
        outcomes[type] = outcome
        if (outcome == ReadOutcome.TRUNCATED) truncated = true
        if (outcome == ReadOutcome.FAILED && HealthConnectErrors.isRateLimited(failure)) rateLimited = true
    }

    /** Whether the per-source read went through any of [types]. */
    val used: Boolean
        @Synchronized get() = outcomes.isNotEmpty()

    /** Of [types], those the per-source read did not go through: read them on their own. */
    val missing: Set<RecordType>
        @Synchronized get() = types.filterTo(LinkedHashSet()) { it !in outcomes }

    /**
     * Records [readRecords][HealthConnectManager.readRecords] fetched on
     * their own - the [missing] types - in its shape: `records` and
     * `truncated`.
     */
    @Synchronized
    fun include(read: Map<String, Any?>) {
        @Suppress("UNCHECKED_CAST")
        (read["records"] as? List<Map<String, Any?>>)?.let { records += it }
        if (read["truncated"] == true) truncated = true
        missing.forEach { outcomes[it] = ReadOutcome.COMPLETE }
    }

    /**
     * The snapshot's `status` when a read failed: `'rate_limited'` when
     * Health Connect refused it for quota, `'failed'` otherwise; null when
     * nothing failed.
     */
    val failureStatus: String?
        @Synchronized get() = when {
            outcomes.values.none { it == ReadOutcome.FAILED } -> null
            rateLimited -> com.steptrackerpro.core.HealthConnectRead.RATE_LIMITED
            else -> "failed"
        }

    /**
     * The records in one list, oldest first whichever type each is - the
     * order a raw read hands back - and whether any type was cut short.
     */
    @Synchronized
    fun result(): Pair<List<Map<String, Any?>>, Boolean> =
        records.sortedBy { it["startTime"] as? Long ?: 0L } to truncated
}
