package com.steptrackerpro.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
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
 * Health Connect read/write, permissions and provider availability.
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

    /**
     * Why Health Connect cannot be used, at the granularity the UI needs.
     *
     * [NOT_INSTALLED] and [UPDATE_REQUIRED] are both fixed by sending the user
     * to the Play Store, [NOT_SUPPORTED] never is. The previous single
     * `UNAVAILABLE` value collapsed the first and the third, so an app could
     * only ever say "unavailable" to a user who was one tap from having it.
     */
    enum class Availability(val jsValue: String) {
        AVAILABLE("available"),
        UPDATE_REQUIRED("update_required"),
        NOT_INSTALLED("not_installed"),
        NOT_SUPPORTED("not_supported");
    }

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
        // From Android 14 Health Connect is part of the platform, so there is
        // nothing to install: an unavailable SDK there means the device does
        // not support it at all. Below 14 it is an APK the user can install.
        else -> if (Build.VERSION.SDK_INT >= 34) {
            Availability.NOT_SUPPORTED
        } else if (isProviderInstalled()) {
            Availability.NOT_SUPPORTED
        } else {
            Availability.NOT_INSTALLED
        }
    }

    private fun isProviderInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(StepSourceCatalog.PROVIDER_PACKAGE, 0)
        true
    }.getOrDefault(false)

    // ---- permissions -----------------------------------------------------

    suspend fun grantedPermissions(): Set<String> =
        runCatching { client()?.permissionController?.getGrantedPermissions() }
            .getOrNull() ?: emptySet()

    suspend fun hasAllPermissions(): Boolean = grantedPermissions().containsAll(REQUIRED)

    suspend fun hasPermissions(scope: PermissionScope): Boolean {
        val required = scope.required
        return required.isNotEmpty() && grantedPermissions().containsAll(required)
    }

    /** Enough to display data, even when writing was refused. */
    suspend fun canRead(): Boolean = grantedPermissions().containsAll(READ_PERMISSIONS)

    suspend fun canWrite(): Boolean = grantedPermissions().containsAll(WRITE_PERMISSIONS)

    suspend fun revokeAll(): Boolean = runCatching {
        client()?.permissionController?.revokeAllPermissions()
        state.healthPermissionDenials = 0
        true
    }.getOrDefault(false)

    /**
     * Which grants an app actually needs, derived from config. A read-only
     * display app never asks for `WRITE_*`, a mirror-only app never asks for
     * `READ_*`: every permission on the sheet is one the user can refuse the
     * whole sheet over, and every declared permission is one Play asks the
     * developer to justify.
     */
    data class PermissionScope(
        val read: Boolean = true,
        val write: Boolean = true,
        val backgroundRead: Boolean = false,
        val historyRead: Boolean = false
    ) {
        /** The required set: everything the app cannot do its job without. */
        val required: Set<String>
            get() = buildSet {
                if (read) addAll(READ_PERMISSIONS)
                if (write) addAll(WRITE_PERMISSIONS)
            }

        /** Required plus whichever optional grants were opted into. */
        val requested: Set<String>
            get() = buildSet {
                addAll(required)
                if (backgroundRead) PERMISSION_BACKGROUND_READ?.let { add(it) }
                if (historyRead) PERMISSION_HISTORY_READ?.let { add(it) }
            }
    }

    /**
     * Resolves the permission set a request should ask for. Optional
     * permissions are only included when the caller opted into them, because
     * Health Connect shows one sheet for the whole set and a user who declines
     * background reads there declines the rest with it.
     */
    fun permissionsFor(scope: PermissionScope): Set<String> = scope.requested

    @Deprecated("Pass a PermissionScope so read/write follow config.")
    fun permissionsFor(backgroundRead: Boolean, historyRead: Boolean): Set<String> =
        permissionsFor(PermissionScope(backgroundRead = backgroundRead, historyRead = historyRead))

    suspend fun status(scope: PermissionScope = PermissionScope()): Map<String, Any?> {
        val availability = availability()
        val granted = if (availability == Availability.AVAILABLE) grantedPermissions() else emptySet()
        val required = scope.required
        val missing = scope.requested - granted
        val denials = state.healthPermissionDenials
        return mapOf(
            "available" to (availability == Availability.AVAILABLE),
            "availability" to availability.jsValue,
            "requiresUpdate" to (availability == Availability.UPDATE_REQUIRED),
            "installable" to (availability == Availability.NOT_INSTALLED ||
                availability == Availability.UPDATE_REQUIRED),
            // Everything *this app* needs. With reads off in config a
            // write-only grant is a full grant, and vice versa.
            "granted" to (required.isNotEmpty() && granted.containsAll(required)),
            "canRead" to granted.containsAll(READ_PERMISSIONS),
            "canWrite" to granted.containsAll(WRITE_PERMISSIONS),
            "readRequired" to scope.read,
            "writeRequired" to scope.write,
            "backgroundReadGranted" to
                (PERMISSION_BACKGROUND_READ != null && granted.contains(PERMISSION_BACKGROUND_READ)),
            "historyReadGranted" to
                (PERMISSION_HISTORY_READ != null && granted.contains(PERMISSION_HISTORY_READ)),
            "grantedPermissions" to granted.toList(),
            "missingPermissions" to missing.toList(),
            "denialCount" to denials,
            // Past the provider's prompt limit the sheet no longer appears, so
            // the only route left is Health Connect's own settings screen.
            "shouldOpenSettings" to (denials >= MAX_PROMPTS && missing.isNotEmpty())
        )
    }

    // ---- reads -----------------------------------------------------------

    /**
     * Daily totals aggregated across Health Connect origins.
     *
     * @param origins restricts the aggregate to these packages. Empty means
     *   every origin, which is only correct for a display that is not also
     *   showing this device's own count - see [readDailyStepsBySource].
     */
    suspend fun readDailySteps(
        start: Instant,
        end: Instant,
        origins: Set<String> = emptySet()
    ): List<DayTotals> {
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
            timeRangeSlicer = Period.ofDays(1),
            dataOriginFilter = origins.map { DataOrigin(it) }.toSet()
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
     * Every origin that published steps in the range, keyed by day, with the
     * device type each origin stamped on its records.
     *
     * This reads raw records rather than aggregating, because the aggregate API
     * returns totals with the contributing origins attached but no way to split
     * a total between them, and no device metadata at all - which is exactly
     * what tells a watch apart from a phone-side pedometer.
     */
    suspend fun readDailyStepsBySource(
        start: Instant,
        end: Instant
    ): Map<String, List<StepSource>> {
        val hc = client() ?: return emptyMap()
        val self = context.packageName

        // A raw read is bounded by MAX_PAGES * PAGE_SIZE records. A watch that
        // writes one record a minute produces 1,440 a day, so anything past a
        // few weeks would be silently truncated - and a truncated month looks
        // like a watch that stopped counting half way through. Long windows
        // are answered per origin through the aggregate API instead, which
        // costs one query per origin per window rather than one page per
        // thousand records.
        val days = java.time.Duration.between(start, end).toDays()
        if (days > RAW_READ_MAX_DAYS) return readDailyStepsBySourceAggregated(hc, start, end)

        // date -> package -> accumulator
        val buckets = HashMap<String, HashMap<String, Accumulator>>()

        // Bucketed by start time, so a record straddling midnight lands wholly
        // on the day it began rather than being split across both. Step records
        // are written in minutes-long slices by every source seen in practice,
        // so the error is bounded by one such slice per day.
        readAll(hc, StepsRecord::class.java, start, end) { record ->
            val date = DateKeys.of(record.startTime.toEpochMilli())
            val pkg = record.metadata.dataOrigin.packageName
            val acc = buckets.getOrPut(date) { HashMap() }
                .getOrPut(pkg) { Accumulator(pkg, self) }
            acc.steps += record.count.toInt()
            acc.observe(record.metadata.device?.type, record.endTime.toEpochMilli())
        }
        // Distance and calories are optional companions: an origin that wrote
        // steps but no distance keeps 0.0 here and has it derived from stride
        // by StepSourceResolver.
        readAll(hc, DistanceRecord::class.java, start, end) { record ->
            val date = DateKeys.of(record.startTime.toEpochMilli())
            val pkg = record.metadata.dataOrigin.packageName
            buckets[date]?.get(pkg)?.let { it.distance += record.distance.inMeters }
        }
        readAll(hc, TotalCaloriesBurnedRecord::class.java, start, end) { record ->
            val date = DateKeys.of(record.startTime.toEpochMilli())
            val pkg = record.metadata.dataOrigin.packageName
            buckets[date]?.get(pkg)?.let { it.calories += record.energy.inKilocalories }
        }

        return buckets.mapValues { (_, byPackage) ->
            byPackage.values
                .map { it.toStepSource(self) }
                .sortedByDescending { it.steps }
        }
    }

    /**
     * The long-window form of [readDailyStepsBySource]. Origins and their
     * device types are discovered from a raw read of the most recent
     * [RAW_READ_MAX_DAYS] of the window, then each origin's daily totals are
     * aggregated across the whole window. An origin that only wrote in the
     * older part of the window is missed, which for a yearly chart is an
     * acceptable trade against a read that never finishes.
     */
    private suspend fun readDailyStepsBySourceAggregated(
        hc: HealthConnectClient,
        start: Instant,
        end: Instant
    ): Map<String, List<StepSource>> {
        val self = context.packageName
        val discoveryStart = maxOf(start, end.minus(java.time.Duration.ofDays(RAW_READ_MAX_DAYS)))
        val recent = readDailyStepsBySource(discoveryStart, end)
        val kinds = HashMap<String, StepSourceKind>()
        val names = HashMap<String, String>()
        recent.values.flatten().forEach { source ->
            val known = kinds[source.packageName]
            if (known == null || source.kind.isWearable) kinds[source.packageName] = source.kind
            names[source.packageName] = source.appName
        }
        if (kinds.isEmpty()) return emptyMap()

        val out = HashMap<String, ArrayList<StepSource>>()
        for ((pkg, kind) in kinds) {
            val perDay = readDailySteps(start, end, setOf(pkg))
            for (day in perDay) {
                if (day.steps <= 0) continue
                out.getOrPut(day.date) { ArrayList() }.add(
                    StepSource(
                        packageName = pkg,
                        appName = names[pkg] ?: StepSourceCatalog.appName(pkg),
                        kind = kind,
                        steps = day.steps,
                        distance = day.distance,
                        calories = day.calories,
                        lastRecordAt = minOf(
                            DateKeys.endOfDayMillis(day.date), System.currentTimeMillis()
                        ),
                        isSelf = pkg == self
                    )
                )
            }
        }
        // The recent window's raw numbers are exact and carry real timestamps;
        // let them override the aggregate for the days they cover.
        recent.forEach { (date, sources) -> out[date] = ArrayList(sources) }
        return out.mapValues { (_, list) -> list.sortedByDescending { it.steps } }
    }

    /** Flattened view of [readDailyStepsBySource] over the whole range. */
    suspend fun listSources(start: Instant, end: Instant): List<StepSource> {
        val self = context.packageName
        val merged = HashMap<String, Accumulator>()
        readDailyStepsBySource(start, end).values.flatten().forEach { source ->
            val acc = merged.getOrPut(source.packageName) {
                Accumulator(source.packageName, self)
            }
            acc.steps += source.steps
            acc.distance += source.distance
            acc.calories += source.calories
            // A source is wearable-backed for the range if it was on any day in
            // it. Taking the last day's classification would let one day the
            // companion app relayed without device metadata mask a watch.
            if (acc.kindOverride == null || source.kind.isWearable) {
                acc.kindOverride = source.kind
            }
            acc.lastRecordAt = maxOf(acc.lastRecordAt, source.lastRecordAt)
        }
        return merged.values.map { it.toStepSource(self) }.sortedByDescending { it.steps }
    }

    /**
     * Pages through a record type. Health Connect caps a response at 5000
     * records and hands back a token; ignoring it silently truncates a busy
     * day, which for step records is not rare - some watches write one record
     * per minute.
     */
    private suspend fun <T : Record> readAll(
        hc: HealthConnectClient,
        type: Class<T>,
        start: Instant,
        end: Instant,
        onRecord: (T) -> Unit
    ) {
        var token: String? = null
        var pages = 0
        do {
            val response = runCatching {
                hc.readRecords(
                    ReadRecordsRequest(
                        recordType = type.kotlin,
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                        pageSize = PAGE_SIZE,
                        pageToken = token
                    )
                )
            }.getOrNull() ?: return
            response.records.forEach(onRecord)
            token = response.pageToken
            pages++
        } while (token != null && pages < MAX_PAGES)
    }

    private class Accumulator(val packageName: String, self: String) {
        var steps: Int = 0
        var distance: Double = 0.0
        var calories: Double = 0.0
        var lastRecordAt: Long = 0L
        var kindOverride: StepSourceKind? = null
        private var deviceType: Int? = null
        val isSelf: Boolean = packageName == self

        fun observe(type: Int?, at: Long) {
            // A wearable type wins over TYPE_UNKNOWN or TYPE_PHONE: companion
            // apps that relay a watch sometimes write a mix, and the day is
            // wearable-sourced if any of it was.
            if (type != null && type != Device.TYPE_UNKNOWN) {
                val incoming = StepSourceKind.fromDeviceType(type)
                if (deviceType == null || incoming.isWearable) deviceType = type
            }
            if (at > lastRecordAt) lastRecordAt = at
        }

        fun toStepSource(self: String): StepSource = StepSource(
            packageName = packageName,
            appName = StepSourceCatalog.appName(packageName),
            kind = kindOverride
                ?: StepSourceCatalog.classify(packageName, deviceType, self),
            steps = steps,
            distance = distance,
            calories = calories,
            lastRecordAt = lastRecordAt,
            isSelf = isSelf
        )
    }

    // ---- writes ----------------------------------------------------------

    /**
     * Upserts one day. Returns false when the client is missing, permissions
     * were revoked, or the provider rejected the write.
     */
    suspend fun writeDay(totals: DayTotals): Boolean {
        val hc = client() ?: return false
        if (totals.steps <= 0) return true
        if (!canWrite()) return false

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

    // ---- intents ---------------------------------------------------------

    fun settingsIntent(): Intent =
        Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Play Store deep link that installs or updates the provider. The
     * `healthConnectOnboarding` referrer is what makes Health Connect run its
     * setup flow straight after the install rather than dropping the user on a
     * blank app.
     */
    fun installIntent(): Intent {
        val uri = Uri.parse(
            "market://details" +
                "?id=${StepSourceCatalog.PROVIDER_PACKAGE}" +
                "&url=healthconnect%3A%2F%2Fonboarding"
        )
        val market = Intent(Intent.ACTION_VIEW, uri)
            .setPackage("com.android.vending")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("overlay", true)
            .putExtra("callerId", context.packageName)
        if (market.resolveActivity(context.packageManager) != null) return market
        return Intent(
            Intent.ACTION_VIEW,
            Uri.parse(
                "https://play.google.com/store/apps/details" +
                    "?id=${StepSourceCatalog.PROVIDER_PACKAGE}"
            )
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Companion apps installed on this phone that are known to publish wearable
     * data. Useful before any Health Connect data exists: it lets an onboarding
     * screen say "we found Galaxy Wearable" rather than asking blind.
     */
    fun installedCompanionApps(): List<Map<String, Any?>> {
        val pm = context.packageManager
        return StepSourceCatalog.COMPANION_PACKAGES.mapNotNull { pkg ->
            val installed = runCatching {
                pm.getPackageInfo(pkg, 0); true
            }.getOrDefault(false)
            if (!installed) return@mapNotNull null
            mapOf(
                "packageName" to pkg,
                "appName" to StepSourceCatalog.appName(pkg),
                "kind" to StepSourceCatalog.classify(pkg, null, context.packageName).jsValue
            )
        }
    }

    companion object {
        /** Health Connect stops showing the sheet after this many refusals. */
        const val MAX_PROMPTS = 2

        private const val PAGE_SIZE = 1_000
        private const val MAX_PAGES = 50

        /** Longest window answered from raw records; longer ones aggregate. */
        private const val RAW_READ_MAX_DAYS = 35L

        val READ_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
        )

        val WRITE_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),
            HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class)
        )

        val REQUIRED: Set<String> = READ_PERMISSIONS + WRITE_PERMISSIONS

        /**
         * Reading while the app is in the background, added in Health Connect
         * 1.1. Resolved reflectively so the package still builds and runs
         * against the 1.0 client a consumer may have pinned through
         * `ext.healthConnectVersion`.
         */
        val PERMISSION_BACKGROUND_READ: String? = constantOrNull(
            "PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND"
        )

        /** Reading further back than 30 days. Also 1.1+. */
        val PERMISSION_HISTORY_READ: String? = constantOrNull(
            "PERMISSION_READ_HEALTH_DATA_HISTORY"
        )

        private fun constantOrNull(field: String): String? = runCatching {
            HealthPermission::class.java.getDeclaredField(field).let {
                it.isAccessible = true
                it.get(null) as? String
            }
        }.getOrNull()

        /** Everything this package may ever ask for. */
        val ALL_PERMISSIONS: Set<String> = buildSet {
            addAll(REQUIRED)
            PERMISSION_BACKGROUND_READ?.let { add(it) }
            PERMISSION_HISTORY_READ?.let { add(it) }
        }

        @Deprecated(
            "Ambiguous once optional permissions existed.",
            ReplaceWith("REQUIRED")
        )
        val PERMISSIONS: Set<String> get() = REQUIRED

        fun permissionContract() =
            PermissionController.createRequestPermissionResultContract()
    }
}
