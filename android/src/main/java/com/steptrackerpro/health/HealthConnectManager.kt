package com.steptrackerpro.health

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import com.steptrackerpro.core.DateKeys
import com.steptrackerpro.core.DayTotals
import com.steptrackerpro.core.StepStateStore
import java.time.Instant
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId

/**
 * Health Connect read/write.
 *
 * Writes are idempotent: every record carries a stable `clientRecordId` of the
 * form `stp-<type>-<date>`, so re-syncing a day replaces the previous record
 * instead of stacking duplicates. That is what makes "sync today's total every
 * 30 minutes" safe. Health Connect keeps the higher `clientRecordVersion` on a
 * collision, so that value must never go backwards - see
 * [StepStateStore.nextHealthRecordVersion].
 */
class HealthConnectManager(
    private val context: Context,
    private val state: StepStateStore
) {

    enum class Availability { AVAILABLE, UPDATE_REQUIRED, UNAVAILABLE }

    @Volatile
    private var cachedClient: HealthConnectClient? = null

    /**
     * Only a *successful* client is cached. This used to be a `by lazy`, which
     * on a process-wide singleton meant that one call made before Health
     * Connect was installed pinned `null` for the life of the process: the user
     * could install the provider and grant everything, and every operation
     * would still fail until the app was killed.
     */
    private fun client(): HealthConnectClient? {
        cachedClient?.let { return it }
        if (availability() != Availability.AVAILABLE) return null
        return runCatching { HealthConnectClient.getOrCreate(context) }
            .getOrNull()
            ?.also { cachedClient = it }
    }

    fun availability(): Availability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.UPDATE_REQUIRED
        else -> Availability.UNAVAILABLE
    }

    suspend fun grantedPermissions(): Set<String> =
        client()?.permissionController?.getGrantedPermissions() ?: emptySet()

    suspend fun hasAllPermissions(): Boolean =
        grantedPermissions().containsAll(PERMISSIONS)

    suspend fun status(): Map<String, Any?> {
        val availability = availability()
        val granted = if (availability == Availability.AVAILABLE) grantedPermissions() else emptySet()
        return mapOf(
            "available" to (availability == Availability.AVAILABLE),
            "requiresUpdate" to (availability == Availability.UPDATE_REQUIRED),
            "granted" to granted.containsAll(PERMISSIONS),
            "grantedPermissions" to granted.toList()
        )
    }

    /** Daily step totals from every source Health Connect knows about. */
    suspend fun readDailySteps(start: Instant, end: Instant): List<DayTotals> {
        val hc = client() ?: return emptyList()
        val zone = ZoneId.systemDefault()
        val request = AggregateGroupByPeriodRequest(
            metrics = setOf(
                StepsRecord.COUNT_TOTAL,
                DistanceRecord.DISTANCE_TOTAL,
                TotalCaloriesBurnedRecord.ENERGY_TOTAL
            ),
            timeRangeFilter = TimeRangeFilter.between(
                LocalDateTime.ofInstant(start, zone),
                LocalDateTime.ofInstant(end, zone)
            ),
            timeRangeSlicer = Period.ofDays(1)
        )
        return runCatching { hc.aggregateGroupByPeriod(request) }
            .getOrDefault(emptyList<AggregationResultGroupedByPeriod>())
            .map { group ->
                DayTotals(
                    date = DateKeys.format(group.startTime.toLocalDate()),
                    steps = (group.result[StepsRecord.COUNT_TOTAL] ?: 0L).toInt(),
                    distance = group.result[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0,
                    calories = group.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]
                        ?.inKilocalories ?: 0.0,
                    synced = true
                )
            }
    }

    /**
     * Upserts one day. Returns false when the client is missing, permissions
     * were revoked, or the provider rejected the write.
     */
    suspend fun writeDay(totals: DayTotals): Boolean {
        val hc = client() ?: return false
        if (totals.steps <= 0) return true
        if (!hasAllPermissions()) return false

        val zone = ZoneId.systemDefault()
        val start = DateKeys.startOfDayInstant(totals.date)
        val rawEnd = DateKeys.endOfDayInstant(totals.date)
        // An open day must not be written with an end time in the future.
        val end = if (rawEnd.isAfter(Instant.now())) Instant.now() else rawEnd
        if (!end.isAfter(start)) return true

        val startOffset = zone.rules.getOffset(start)
        val endOffset = zone.rules.getOffset(end)
        // Health Connect resolves same-id collisions by keeping the higher
        // version, so this has to be monotonic. Using the wall clock meant that
        // after the user set the clock back, every write was silently discarded
        // by the provider while insertRecords still reported success.
        val version = state.nextHealthRecordVersion()
        val device = Device(type = Device.TYPE_PHONE)

        // Explicitly typed: the three record classes share only the
        // library-internal IntervalRecord supertype, which Kotlin 2.x refuses
        // to infer as a type argument.
        val records = listOf<Record>(
            StepsRecord(
                count = totals.steps.toLong(),
                startTime = start,
                endTime = end,
                startZoneOffset = startOffset,
                endZoneOffset = endOffset,
                metadata = Metadata.autoRecorded(
                    device = device,
                    clientRecordId = "stp-steps-${totals.date}",
                    clientRecordVersion = version
                )
            ),
            DistanceRecord(
                distance = Length.meters(totals.distance),
                startTime = start,
                endTime = end,
                startZoneOffset = startOffset,
                endZoneOffset = endOffset,
                metadata = Metadata.autoRecorded(
                    device = device,
                    clientRecordId = "stp-distance-${totals.date}",
                    clientRecordVersion = version
                )
            ),
            TotalCaloriesBurnedRecord(
                energy = Energy.kilocalories(totals.calories),
                startTime = start,
                endTime = end,
                startZoneOffset = startOffset,
                endZoneOffset = endOffset,
                metadata = Metadata.autoRecorded(
                    device = device,
                    clientRecordId = "stp-calories-${totals.date}",
                    clientRecordVersion = version
                )
            )
        )

        return runCatching { hc.insertRecords(records); true }.getOrDefault(false)
    }

    fun settingsIntent(): Intent =
        Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class)
        )

        fun permissionContract() =
            PermissionController.createRequestPermissionResultContract()
    }
}
