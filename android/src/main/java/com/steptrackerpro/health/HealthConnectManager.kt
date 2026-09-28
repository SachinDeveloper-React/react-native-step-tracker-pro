package com.steptrackerpro.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
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
import kotlin.reflect.KClass
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit

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
     * Read `ActiveCaloriesBurnedRecord` alongside steps, per source. Set by
     * the core from `healthConnectReadActiveCalories`; only acted on once the
     * permission is actually granted.
     */
    @Volatile
    var readActiveCalories: Boolean = false

    /**
     * Which record types reads cover, from `healthConnectReadTypes`. Steps
     * always; distance and total calories only when the app asked for them,
     * so an app that wants steps alone declares and justifies one permission.
     * A type left out reads as 0 - derived from stride where the resolver
     * does that already - exactly as a source that never wrote it.
     */
    @Volatile
    var readTypes: Set<ReadType> = ReadType.ALL

    /**
     * Days must carry the recording-method split - `healthConnectIgnoreManualEntries`
     * is on - so days an incomplete read missed are read again one at a time
     * rather than answered from aggregates, which carry none.
     */
    @Volatile
    var needsRecordingMethods: Boolean = false

    /**
     * The record types `healthConnectReadTypes` can name - also the three
     * this package writes, so each carries its write permission too.
     */
    enum class ReadType(val jsValue: String, val permission: String, val writePermission: String) {
        STEPS(
            "steps",
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getWritePermission(StepsRecord::class)
        ),
        DISTANCE(
            "distance",
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class)
        ),
        TOTAL_CALORIES(
            "totalCalories",
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class)
        );

        companion object {
            val ALL: Set<ReadType> = entries.toSet()

            /** Steps plus every recognised name; unknown names are dropped, steps can't be. */
            fun parse(values: Collection<String>): Set<ReadType> = buildSet {
                add(STEPS)
                values.forEach { value -> entries.firstOrNull { it.jsValue == value }?.let(::add) }
            }

            fun permissions(types: Set<ReadType>): Set<String> = types.mapTo(LinkedHashSet()) { it.permission }
        }
    }

    /**
     * The record types the raw reads and change tracking return. A distance
     * check per interval needs the distance records next to the step ones.
     */
    enum class RecordType(val jsValue: String, val type: KClass<out Record>, val permission: String) {
        STEPS("steps", StepsRecord::class, HealthPermission.getReadPermission(StepsRecord::class)),
        DISTANCE("distance", DistanceRecord::class, HealthPermission.getReadPermission(DistanceRecord::class));

        companion object {
            /** Recognised names in declaration order; null when one is unknown or none are given. */
            fun parse(values: Collection<String>): Set<RecordType>? {
                if (values.isEmpty()) return null
                val out = LinkedHashSet<RecordType>()
                for (value in values) out += entries.firstOrNull { it.jsValue == value } ?: return null
                return entries.filterTo(LinkedHashSet()) { it in out }
            }
        }
    }

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

    @Volatile
    private var grantedCache: Pair<Long, Set<String>>? = null

    /** The last grant set read, kept across cache invalidation so a change is noticed. */
    @Volatile
    private var lastGranted: Set<String>? = null

    /**
     * Called when a fresh read of the grants differs from the last one - the
     * user allowed or refused something since. Set by the core, which drops
     * whatever it cached under the old grants.
     */
    @Volatile
    var onGrantsChanged: (() -> Unit)? = null

    /**
     * The grants this app holds. Cached briefly: every resolved read checks
     * them, the sensor path can trigger one a minute, and each check is a
     * cross-process call. [status] and anything that just changed a grant
     * ask for a fresh answer.
     */
    suspend fun grantedPermissions(fresh: Boolean = false): Set<String> {
        if (!fresh) {
            grantedCache?.let { (at, set) ->
                if (System.currentTimeMillis() - at < GRANT_CACHE_MS) return set
            }
        }
        val set = runCatching { client()?.permissionController?.getGrantedPermissions() }
            .getOrNull() ?: emptySet()
        grantedCache = System.currentTimeMillis() to set
        val previous = lastGranted
        lastGranted = set
        if (previous != null && previous != set) onGrantsChanged?.invoke()
        return set
    }

    /** See [access]. */
    data class Access(
        /** `READ_STEPS` is granted: Health Connect is read for the number. */
        val readSteps: Boolean,
        /** Of the configured read types, those granted - in declaration order. */
        val readTypes: Set<ReadType>,
        /** `WRITE_STEPS` is granted: this device's count is mirrored. */
        val writeSteps: Boolean,
        /** The types whose write permission is granted. */
        val writeTypes: Set<ReadType>
    )

    fun invalidatePermissionCache() {
        grantedCache = null
    }

    suspend fun hasAllPermissions(): Boolean = grantedPermissions().containsAll(REQUIRED)

    suspend fun hasPermissions(scope: PermissionScope): Boolean {
        val required = scope.required
        return required.isNotEmpty() && grantedPermissions().containsAll(required)
    }

    /**
     * Every configured read type granted. What `HealthConnectStatus.canRead`
     * reports; not what decides whether Health Connect is read - see
     * [canReadSteps].
     */
    suspend fun canRead(): Boolean = grantedPermissions().containsAll(ReadType.permissions(readTypes))

    /**
     * Steps can be read: the only grant resolving a day's number needs. The
     * sheet lets the user untick any single permission, and a user who
     * allowed steps but not distance must still get their watch's steps -
     * distance is then derived from stride, as for a watch that writes none.
     */
    suspend fun canReadSteps(): Boolean = access(grantedPermissions(), readTypes).readSteps

    /** Of the configured read types, the ones granted. */
    suspend fun grantedReadTypes(): Set<ReadType> = access(grantedPermissions(), readTypes).readTypes

    /** Steps can be written: all mirroring needs. Distance and calories go along when granted. */
    suspend fun canWriteSteps(): Boolean = access(grantedPermissions(), readTypes).writeSteps

    /** Every read permission [types] need, granted. */
    suspend fun canReadRecords(types: Set<RecordType>): Boolean =
        grantedPermissions().containsAll(types.map { it.permission })

    suspend fun canWrite(): Boolean = grantedPermissions().containsAll(WRITE_PERMISSIONS)

    suspend fun revokeAll(): Boolean = runCatching {
        client()?.permissionController?.revokeAllPermissions()
        state.healthPermissionDenials = 0
        invalidatePermissionCache()
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
        val historyRead: Boolean = false,
        /** Also read `ActiveCaloriesBurnedRecord`, per source. Only meaningful with [read]. */
        val activeCalories: Boolean = false,
        /** Which types [read] covers. Steps always. */
        val readTypes: Set<ReadType> = ReadType.ALL,
        /** Which types [write] covers. Steps always. */
        val writeTypes: Set<ReadType> = ReadType.ALL,
        /** The app named [readTypes] itself: [forManifest] keeps every one of them. */
        val readTypesExplicit: Boolean = false,
        /** The app turned [write] on itself: [forManifest] keeps it. */
        val writeExplicit: Boolean = false,
        /**
         * Permissions config would ask for that [forManifest] left out
         * because the manifest does not declare them. Reported in
         * `undeclaredPermissions`, never requested, never a rejection.
         */
        val dropped: Set<String> = emptySet()
    ) {
        /** The read permissions [readTypes] need. */
        val readPermissions: Set<String>
            get() = ReadType.permissions(readTypes + ReadType.STEPS)

        /** The write permissions [writeTypes] need. */
        val writePermissions: Set<String>
            get() = (writeTypes + ReadType.STEPS).mapTo(LinkedHashSet()) { it.writePermission }

        /** The required set: everything the app cannot do its job without. */
        val required: Set<String>
            get() = buildSet {
                if (read) addAll(readPermissions)
                if (write) addAll(writePermissions)
            }

        /**
         * What Health Connect needs to be on at all: `READ_STEPS` when reads
         * are on - reading a watch is what the app gets out of it - and
         * `WRITE_STEPS` only for an app that does nothing but mirror. Writing,
         * distance and calories are extras on top: a user who unticked them
         * on the sheet has not refused Health Connect, and is neither counted
         * as refusing nor asked again unprompted.
         */
        val essential: Set<String>
            get() = when {
                read -> setOf(ReadType.STEPS.permission)
                write -> setOf(ReadType.STEPS.writePermission)
                else -> emptySet()
            }

        /** [essential] is non-empty and every part of it is in [granted]. */
        fun stepsGranted(granted: Set<String>): Boolean =
            essential.isNotEmpty() && granted.containsAll(essential)

        /**
         * This scope, fitted to what the app's manifest [declared]. What the
         * package uses only by default is left out when undeclared rather
         * than failing the request: the write set (writes are on by default,
         * and an app that declares no `WRITE_STEPS` does not want them), and
         * distance and calories among the default read types and among
         * writes. What the app asked for itself stays, so leaving it out of
         * the manifest is still reported as the mistake it is: `READ_STEPS`
         * with reads on, read types it named, writes it turned on, and every
         * opt-in - background reads, history, active calories.
         */
        fun forManifest(declared: Set<String>): PermissionScope {
            val writes = write && (writeExplicit || ReadType.STEPS.writePermission in declared)
            val fitted = copy(
                write = writes,
                readTypes = readTypes.filterTo(LinkedHashSet()) {
                    readTypesExplicit || it == ReadType.STEPS || it.permission in declared
                },
                writeTypes = if (writes) {
                    writeTypes.filterTo(LinkedHashSet()) {
                        it == ReadType.STEPS || it.writePermission in declared
                    }
                } else {
                    writeTypes
                }
            )
            return fitted.copy(dropped = requested - fitted.requested)
        }

        /** Required plus whichever optional grants were opted into. */
        val requested: Set<String>
            get() = buildSet {
                addAll(required)
                if (backgroundRead) PERMISSION_BACKGROUND_READ?.let { add(it) }
                if (historyRead) PERMISSION_HISTORY_READ?.let { add(it) }
                if (read && activeCalories) add(READ_ACTIVE_CALORIES)
            }
    }

    /**
     * Of what [scope] asks for, what the app's manifest does not declare.
     * Health Connect silently leaves undeclared permissions off its sheet, so
     * a request for them looks exactly like a refusal; from 2.0 the library
     * no longer declares them for every app, so this is checked up front.
     */
    fun undeclaredPermissions(scope: PermissionScope): Set<String> =
        scope.requested - com.steptrackerpro.util.PermissionHelper.declaredPermissions(context)

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
        val granted = if (availability == Availability.AVAILABLE) {
            grantedPermissions(fresh = true)
        } else {
            emptySet()
        }
        val required = scope.required
        val missing = scope.requested - granted
        val denials = state.healthPermissionDenials
        val access = access(granted, scope.readTypes)
        return mapOf(
            "available" to (availability == Availability.AVAILABLE),
            "availability" to availability.jsValue,
            "requiresUpdate" to (availability == Availability.UPDATE_REQUIRED),
            "installable" to (availability == Availability.NOT_INSTALLED ||
                availability == Availability.UPDATE_REQUIRED),
            // Everything *this app* needs. With reads off in config a
            // write-only grant is a full grant, and vice versa.
            "granted" to (required.isNotEmpty() && granted.containsAll(required)),
            "canRead" to granted.containsAll(scope.readPermissions),
            // What the user actually allowed, type by type. The sheet lets
            // them untick any one permission; steps alone is enough to read
            // and to write, and the rest are used when present.
            "canReadSteps" to access.readSteps,
            "grantedReadTypes" to access.readTypes.map { it.jsValue },
            "canWriteSteps" to access.writeSteps,
            "grantedWriteTypes" to access.writeTypes.map { it.jsValue },
            "canWrite" to granted.containsAll(WRITE_PERMISSIONS),
            "readRequired" to scope.read,
            "writeRequired" to scope.write,
            "backgroundReadGranted" to
                (PERMISSION_BACKGROUND_READ != null && granted.contains(PERMISSION_BACKGROUND_READ)),
            "historyReadGranted" to
                (PERMISSION_HISTORY_READ != null && granted.contains(PERMISSION_HISTORY_READ)),
            "grantedPermissions" to granted.toList(),
            "missingPermissions" to missing.toList(),
            // Asked for by config but absent from the manifest - add them, see
            // docs/PERMISSIONS.md. A request rejects while any are listed.
            // Of those, the ones config would use but can do without -
            // distance, calories, the write set - are simply not asked for;
            // only READ_STEPS and the explicit opt-ins fail a request.
            "undeclaredPermissions" to (undeclaredPermissions(scope) + scope.dropped).toList(),
            // Steps are allowed for everything config turns on. What
            // enableHealthConnect() waits for; the rest is optional.
            "stepsGranted" to scope.stepsGranted(granted),
            // Requests after which steps were still refused.
            "denialCount" to denials,
            // Past the provider's prompt limit the sheet no longer appears, so
            // the only route left is Health Connect's own settings screen.
            // Only for steps: an unticked distance is not a refusal.
            "shouldOpenSettings" to (denials >= MAX_PROMPTS && !scope.stepsGranted(granted) &&
                scope.essential.isNotEmpty())
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
        // Only types the app reads and holds a grant for: an aggregate that
        // names one ungranted metric fails as a whole, and a steps-only app
        // would get nothing back at all.
        val granted = grantedPermissions()
        val types = readTypes.filter { it.permission in granted }.toSet()
        val request = AggregateGroupByPeriodRequest(
            metrics = buildSet {
                add(StepsRecord.COUNT_TOTAL)
                if (ReadType.DISTANCE in types) add(DistanceRecord.DISTANCE_TOTAL)
                if (ReadType.TOTAL_CALORIES in types) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            },
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
        end: Instant,
        /**
         * Epoch millis from which this device covered the day it falls on.
         * Records on that day are additionally split into the part before it,
         * reported as [StepSource.stepsBeforeCoverage]. 0 disables the split.
         */
        coverageStartMs: Long = 0L,
        /** False inside [perDay], so a gap is never filled a day at a time twice over. */
        perDayFill: Boolean = true,
        /**
         * `SystemClock.elapsedRealtime()` by which the caller's own timeout
         * ends the whole read - the uptime clock, which a clock change cannot
         * move. A per-day refill takes only what is left of it, so a slow
         * first read cannot push the read as a whole past the caller's limit.
         */
        deadline: Long = Long.MAX_VALUE
    ): Map<String, List<StepSource>> {
        val hc = client() ?: return emptyMap()
        val self = context.packageName
        val coverageDate = if (coverageStartMs > 0L) DateKeys.of(coverageStartMs) else null

        // A raw read is bounded by MAX_PAGES * PAGE_SIZE records. A watch that
        // writes one record a minute produces 1,440 a day, so anything past a
        // few weeks would be silently truncated - and a truncated month looks
        // like a watch that stopped counting half way through. Long windows
        // are answered per origin through the aggregate API instead, which
        // costs one query per origin per window rather than one page per
        // thousand records.
        val days = java.time.Duration.between(start, end).toDays()
        if (days > RAW_READ_MAX_DAYS) return readDailyStepsBySourceAggregated(hc, start, end, deadline)

        // date -> package -> accumulator
        val buckets = HashMap<String, HashMap<String, Accumulator>>()
        val zone = DateKeys.zone()

        // Bucketed by start time, so a record straddling midnight lands wholly
        // on the day it began rather than being split across both. Step records
        // are written in minutes-long slices by every source seen in practice,
        // so the error is bounded by one such slice per day.
        //
        // Newest first: when a busy window - a watch and Health Connect's own
        // count each writing a record a minute - outruns the page cap, the
        // days lost are the oldest, not today. Whatever the read did not
        // reach is answered from the aggregate API below.
        var oldestRead = Long.MAX_VALUE
        val stepsOutcome = readAll(hc, StepsRecord::class.java, start, end, newestFirst = true) { record ->
            val recordStart = record.startTime.toEpochMilli()
            if (recordStart < oldestRead) oldestRead = recordStart
            val recordEnd = record.endTime.toEpochMilli()
            val date = DateKeys.of(recordStart)
            val pkg = record.metadata.dataOrigin.packageName
            val acc = buckets.getOrPut(date) { HashMap() }
                .getOrPut(pkg) { Accumulator(pkg, self) }
            val count = record.count.toInt()
            acc.steps += count
            // Bucketed by how the writing app says the steps were produced.
            // A manually typed record is the one honest way to put 20,000
            // steps into Health Connect from a keyboard, and it is
            // indistinguishable from a sensor's count without this split.
            val bucket = RecordingMethods.bucketOf(record.metadata.recordingMethod)
            acc.methods[bucket] += count
            // Written long after the steps it describes. Evidence only.
            if (record.metadata.lastModifiedTime.toEpochMilli() - recordEnd > LATE_WRITE_MS) {
                acc.lateSteps += count
            }
            if (date == coverageDate) {
                // The part of this record that predates our coverage. A record
                // straddling the instant is split by time; step records are
                // minutes long, so the error is bounded by one of them.
                val before = when {
                    recordEnd <= coverageStartMs -> count
                    recordStart >= coverageStartMs -> 0
                    else -> {
                        val span = (recordEnd - recordStart).coerceAtLeast(1L).toDouble()
                        ((coverageStartMs - recordStart) / span * count).toInt()
                    }
                }
                acc.stepsBefore += before
                // The manual part of the pre-coverage share, so excluding
                // manual entries can take a hand-entered morning out of what
                // a phone-side source may supply from before install.
                if (bucket == RecordingMethods.MANUAL) acc.manualBefore += before
            }
            acc.observe(record.metadata.device?.type, recordEnd)
            HourlySteps.add(acc.hourly, recordStart, recordEnd, count, zone)
        }
        // Distance and calories are optional companions: an origin that wrote
        // steps but no distance keeps 0.0 here and has it derived from stride
        // by StepSourceResolver - as does every origin when the app does not
        // read that type, or the user did not grant it. Only granted types
        // are asked for: an ungranted one would fail every time.
        //
        // A read that did not finish - a page failed, or the page cap cut it
        // short - is thrown away rather than kept: a partial distance
        // labelled as measured would fail a server's distance check, so the
        // figure is zeroed, reported `not_read`, and derived like any other
        // missing distance.
        val types = grantedReadTypes()
        //
        // Each is read newest first like steps, so a read the page cap cuts
        // short keeps the days it did cover - today's measured distance
        // survives a busy month - and only the day it stopped in and the
        // days before it are discarded.
        val lastDay = DateKeys.of(end.toEpochMilli())
        /** Applies [whole] to each origin's day the read covered, [cut] to the rest. */
        fun settle(outcome: ReadOutcome, oldest: Long, whole: (Accumulator) -> Unit, cut: (Accumulator) -> Unit) {
            val through = incompleteThrough(outcome, oldest.takeIf { it != Long.MAX_VALUE }?.let { DateKeys.of(it) }, lastDay)
            buckets.forEach { (date, byPackage) ->
                byPackage.values.forEach { if (through == null || date > through) whole(it) else cut(it) }
            }
        }
        if (ReadType.DISTANCE in types) {
            var oldest = Long.MAX_VALUE
            val outcome = readAll(hc, DistanceRecord::class.java, start, end, newestFirst = true) { record ->
                val at = record.startTime.toEpochMilli()
                if (at < oldest) oldest = at
                buckets[DateKeys.of(at)]?.get(record.metadata.dataOrigin.packageName)?.let {
                    it.distance += record.distance.inMeters
                    it.distanceSeen = true
                }
            }
            settle(outcome, oldest, whole = { it.distanceRead = true }, cut = {
                it.distance = 0.0
                it.distanceSeen = false
            })
        }
        if (ReadType.TOTAL_CALORIES in types) {
            var oldest = Long.MAX_VALUE
            val outcome = readAll(hc, TotalCaloriesBurnedRecord::class.java, start, end, newestFirst = true) { record ->
                val at = record.startTime.toEpochMilli()
                if (at < oldest) oldest = at
                buckets[DateKeys.of(at)]?.get(record.metadata.dataOrigin.packageName)?.let {
                    it.calories += record.energy.inKilocalories
                }
            }
            settle(outcome, oldest, whole = {}, cut = { it.calories = 0.0 })
        }
        // Opt-in, and only once granted: an ungranted type would fail the read.
        if (readActiveCalories && grantedPermissions().contains(READ_ACTIVE_CALORIES)) {
            buckets.values.forEach { byPackage -> byPackage.values.forEach { it.activeCalories = 0.0 } }
            var oldest = Long.MAX_VALUE
            val outcome = readAll(hc, ActiveCaloriesBurnedRecord::class.java, start, end, newestFirst = true) { record ->
                val at = record.startTime.toEpochMilli()
                if (at < oldest) oldest = at
                buckets[DateKeys.of(at)]?.get(record.metadata.dataOrigin.packageName)?.let {
                    it.activeCalories += record.energy.inKilocalories
                }
            }
            // A partial sum is reported as unknown, not as a low figure.
            settle(outcome, oldest, whole = {}, cut = { it.activeCalories = -1.0 })
        }

        val read = buckets.mapValues { (date, byPackage) ->
            byPackage.values
                .map { it.toStepSource(self, withCoverage = date == coverageDate) }
                .sortedByDescending { it.steps }
        }
        val incomplete = incompleteThrough(
            stepsOutcome, oldestRead.takeIf { it != Long.MAX_VALUE }?.let { DateKeys.of(it) }, DateKeys.of(end.toEpochMilli())
        ) ?: return read

        // The day the read stopped in, and every day before it, are answered
        // per origin from the aggregate API: exact totals without the
        // per-record detail. Origins come from the part that was read and
        // from the aggregate itself, so one that only wrote in the gap is
        // still found.
        val gapEnd = minOf(end, DateKeys.endOfDayInstant(incomplete))
        val kinds = HashMap<String, StepSourceKind>()
        val names = HashMap<String, String>()
        read.values.flatten().forEach { source ->
            if (kinds[source.packageName] == null || source.kind.isWearable) kinds[source.packageName] = source.kind
            names[source.packageName] = source.appName
        }
        originsWithSteps(hc, start, gapEnd).forEach { pkg ->
            kinds.getOrPut(pkg) { StepSourceCatalog.classify(pkg, null, self) }
        }
        // Aggregates carry no recording method, so a day answered from them
        // cannot have manual entries taken out. With that rule on, the gap is
        // read again a day at a time instead - each day well inside the cap -
        // so a range agrees with the day view. Those per-day reads fall back
        // to aggregates themselves only for a day that alone outruns the cap.
        val budget = perDayBudget(android.os.SystemClock.elapsedRealtime(), deadline)
        val filled = if (needsRecordingMethods && perDayFill && budget > 0) {
            val days = perDay(DateKeys.of(start.toEpochMilli()), incomplete, gapEnd, coverageStartMs, budget)
            // A day the budget ran out on is answered from aggregates - it
            // loses the manual split, but the range keeps its other apps
            // rather than timing out as a whole.
            val missing = DateKeys.rangeOf(DateKeys.of(start.toEpochMilli()), incomplete) - days.keys
            if (missing.isEmpty()) {
                days
            } else {
                days + aggregateBySource(start, gapEnd, kinds, names).filterKeys { it in missing }
            }
        } else {
            aggregateBySource(start, gapEnd, kinds, names)
        }
        return read.filterKeys { it > incomplete } + filled.filterKeys { it <= incomplete }
    }

    /**
     * Each day from [first] to [last] read on its own - never filled a day at
     * a time again - [PER_DAY_PARALLEL] at once, within [budgetMs].
     * A day with steps that was read comes back, even with no sources; a
     * day the budget did not reach is left out, for the caller to answer
     * some other way.
     */
    private suspend fun perDay(
        first: String,
        last: String,
        end: Instant,
        coverageStartMs: Long,
        budgetMs: Long
    ): Map<String, List<StepSource>> = kotlinx.coroutines.coroutineScope {
        val deadline = android.os.SystemClock.elapsedRealtime() + budgetMs
        val permits = kotlinx.coroutines.sync.Semaphore(PER_DAY_PARALLEL)
        DateKeys.rangeOf(first, last).map { date ->
            async {
                permits.withPermit {
                    val left = deadline - android.os.SystemClock.elapsedRealtime()
                    if (left <= 0) return@withPermit null
                    val dayEnd = minOf(DateKeys.endOfDayInstant(date), end)
                    kotlinx.coroutines.withTimeoutOrNull(left) {
                        date to readDailyStepsBySource(
                            DateKeys.startOfDayInstant(date), dayEnd, coverageStartMs, perDayFill = false
                        )[date].orEmpty()
                    }
                }
            }
        }.awaitAll().filterNotNull().toMap()
    }

    /**
     * Every origin that wrote steps between two instants, from the aggregate
     * API's list of contributors. Empty when it cannot be read.
     */
    private suspend fun originsWithSteps(hc: HealthConnectClient, start: Instant, end: Instant): Set<String> =
        try {
            hc.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            ).dataOrigins.mapTo(HashSet()) { it.packageName }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptySet()
        }

    /**
     * Each of [kinds]' origins' daily totals from the aggregate API, as
     * sources without per-record detail: no recording-method split, no hourly
     * profile, no late-write share - the -1 and null every aggregate-answered
     * day carries.
     */
    private suspend fun aggregateBySource(
        start: Instant,
        end: Instant,
        kinds: Map<String, StepSourceKind>,
        names: Map<String, String>
    ): Map<String, List<StepSource>> {
        val self = context.packageName
        // readDailySteps asks for distance only when it is granted.
        val distanceRead = ReadType.DISTANCE in grantedReadTypes()
        val out = HashMap<String, ArrayList<StepSource>>()
        for ((pkg, kind) in kinds) {
            for (day in readDailySteps(start, end, setOf(pkg))) {
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
                        isSelf = pkg == self,
                        distanceSource = DistanceSource.of(
                            read = distanceRead, seen = day.distance > 0.0, isSelf = pkg == self
                        )
                    )
                )
            }
        }
        return out.mapValues { (_, list) -> list.sortedByDescending { it.steps } }
    }

    /**
     * The long-window form of [readDailyStepsBySource]. Origins and their
     * device types are discovered from a raw read of the most recent
     * [RAW_READ_MAX_DAYS] of the window, then each origin's daily totals are
     * aggregated across the whole window. An origin that only wrote in the
     * older part of the window is missed, which for a yearly chart is an
     * acceptable trade against a read that never finishes.
     *
     * The aggregate API returns totals with no per-record metadata, so the
     * days it answers carry no recording-method split: `manualSteps` and
     * `unknownMethodSteps` are -1 and `recordingMethods` is null on them, and
     * `healthConnectIgnoreManualEntries` has nothing to subtract. Every path
     * that resolves a single day - reads, events, the notification, the
     * verification snapshot - is a one-day window and never comes here.
     */
    private suspend fun readDailyStepsBySourceAggregated(
        hc: HealthConnectClient,
        start: Instant,
        end: Instant,
        deadline: Long
    ): Map<String, List<StepSource>> {
        val self = context.packageName
        val discoveryStart = maxOf(start, end.minus(java.time.Duration.ofDays(RAW_READ_MAX_DAYS)))
        val recent = readDailyStepsBySource(discoveryStart, end, deadline = deadline)
        val kinds = HashMap<String, StepSourceKind>()
        val names = HashMap<String, String>()
        recent.values.flatten().forEach { source ->
            val known = kinds[source.packageName]
            if (known == null || source.kind.isWearable) kinds[source.packageName] = source.kind
            names[source.packageName] = source.appName
        }
        if (kinds.isEmpty()) return emptyMap()

        val out = HashMap<String, List<StepSource>>(aggregateBySource(start, end, kinds, names))
        // The recent window's raw numbers are exact and carry real timestamps;
        // let them override the aggregate for the days they cover.
        recent.forEach { (date, sources) -> out[date] = sources }
        return out
    }

    /** Flattened view of [readDailyStepsBySource] over the whole range. */
    suspend fun listSources(
        start: Instant,
        end: Instant,
        deadline: Long = Long.MAX_VALUE
    ): List<StepSource> {
        val self = context.packageName
        val merged = HashMap<String, Accumulator>()
        readDailyStepsBySource(start, end, deadline = deadline).values.flatten().forEach { source ->
            val acc = merged.getOrPut(source.packageName) {
                Accumulator(source.packageName, self)
            }
            acc.steps += source.steps
            acc.distance += source.distance
            acc.calories += source.calories
            // Read on any day counts as read, a distance on any day as seen.
            if (source.distanceSource != DistanceSource.NOT_READ) acc.distanceRead = true
            if (source.distanceSource == DistanceSource.HEALTH_CONNECT ||
                source.distanceSource == DistanceSource.DERIVED
            ) {
                acc.distanceSeen = true
            }
            if (source.lateWrittenSteps >= 0) acc.lateSteps += source.lateWrittenSteps
            // Hour-of-day profile across the range; unknown if any day lacks it.
            if (source.hourlySteps.any { it < 0 }) {
                acc.hourlyKnown = false
            } else {
                source.hourlySteps.forEachIndexed { h, v -> acc.hourly[h] += v }
            }
            if (source.activeCalories < 0) {
                acc.activeKnown = false
            } else {
                acc.activeCalories = acc.activeCalories.coerceAtLeast(0.0) + source.activeCalories
            }
            // The method split is only as good as its worst day: one day
            // answered from the aggregate API has no split, and a partial sum
            // presented as the range's manual total would understate it.
            val methods = source.recordingMethods
            if (methods == null) {
                acc.methodsKnown = false
            } else {
                acc.methods[RecordingMethods.ACTIVE] += methods.active
                acc.methods[RecordingMethods.AUTOMATIC] += methods.automatic
                acc.methods[RecordingMethods.MANUAL] += methods.manual
                acc.methods[RecordingMethods.UNKNOWN] += methods.unknown
            }
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

    // ---- raw records and change tracking ----------------------------------

    /**
     * What every raw record carries, whatever its type: who wrote it, how,
     * on what device, when, and when it was last changed.
     */
    private fun metadataMap(
        recordType: RecordType,
        metadata: Metadata,
        startTime: Instant,
        endTime: Instant,
        startZoneOffset: java.time.ZoneOffset?,
        endZoneOffset: java.time.ZoneOffset?
    ): LinkedHashMap<String, Any?> = linkedMapOf(
        "recordType" to recordType.jsValue,
        "id" to metadata.id,
        "clientRecordId" to metadata.clientRecordId,
        "clientRecordVersion" to metadata.clientRecordVersion,
        "packageName" to metadata.dataOrigin.packageName,
        "recordingMethod" to RecordingMethods.nameOf(metadata.recordingMethod),
        "device" to metadata.device?.let {
            mapOf("type" to deviceTypeName(it.type), "manufacturer" to it.manufacturer, "model" to it.model)
        },
        "startTime" to startTime.toEpochMilli(),
        "endTime" to endTime.toEpochMilli(),
        "startZoneOffsetSeconds" to startZoneOffset?.totalSeconds,
        "endZoneOffsetSeconds" to endZoneOffset?.totalSeconds,
        "lastModifiedTime" to metadata.lastModifiedTime.toEpochMilli()
    )

    /** One step record as Health Connect stores it, for a server that wants the evidence itself. */
    fun recordMap(record: StepsRecord): Map<String, Any?> = metadataMap(
        RecordType.STEPS, record.metadata, record.startTime, record.endTime,
        record.startZoneOffset, record.endZoneOffset
    ).apply { put("count", record.count) }

    /** One distance record as stored. */
    fun recordMap(record: DistanceRecord): Map<String, Any?> = metadataMap(
        RecordType.DISTANCE, record.metadata, record.startTime, record.endTime,
        record.startZoneOffset, record.endZoneOffset
    ).apply { put("distanceMeters", record.distance.inMeters) }

    /** A record of one of the [RecordType]s, or null for any other. */
    private fun anyRecordMap(record: Record): Map<String, Any?>? = when (record) {
        is StepsRecord -> recordMap(record)
        is DistanceRecord -> recordMap(record)
        else -> null
    }

    /**
     * Every record of [types] between two instants, every origin, as stored,
     * oldest first. Each type is bounded by [MAX_RAW_PAGES] pages;
     * `truncated` says a token was left unread for at least one of them, so
     * the caller narrows the window rather than trusting a short list.
     * Throws when Health Connect cannot be read; callers check first.
     */
    suspend fun readRecords(
        start: Instant,
        end: Instant,
        types: Set<RecordType> = setOf(RecordType.STEPS)
    ): Map<String, Any?> {
        val hc = client() ?: throw IllegalStateException("Health Connect is not available")
        val out = ArrayList<Map<String, Any?>>()
        var truncated = false
        for (type in types) {
            var token: String? = null
            var pages = 0
            do {
                val response = hc.readRecords(
                    ReadRecordsRequest(
                        recordType = type.type,
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                        pageSize = PAGE_SIZE,
                        pageToken = token
                    )
                )
                response.records.forEach { record -> anyRecordMap(record)?.let { out += it } }
                token = response.pageToken
                pages++
            } while (token != null && pages < MAX_RAW_PAGES)
            if (token != null) truncated = true
        }
        // One list, in time order, whichever type each record is.
        out.sortBy { it["startTime"] as Long }
        return mapOf("records" to out, "truncated" to truncated)
    }

    /** Step records only - the 2.0 shape. */
    suspend fun readStepRecords(start: Instant, end: Instant): Map<String, Any?> =
        readRecords(start, end, setOf(RecordType.STEPS))

    /** A cursor for [changes] over [types], starting now. Valid for 30 days, per Health Connect. */
    suspend fun changesToken(types: Set<RecordType> = setOf(RecordType.STEPS)): String {
        val hc = client() ?: throw IllegalStateException("Health Connect is not available")
        return hc.getChangesToken(ChangesTokenRequest(recordTypes = types.map { it.type }.toSet()))
    }

    /**
     * Records inserted, updated or deleted since [token], across every
     * origin, and the token to use next - of whichever types the token was
     * taken for. An expired token - Health Connect keeps changes for 30 days
     * - comes back as `tokenExpired: true` with no changes: the caller takes
     * a new token and re-reads the window with [readRecords]. At most
     * [MAX_CHANGE_PAGES] pages per call; `hasMore` says to call again with
     * `nextToken`.
     */
    suspend fun changes(token: String): Map<String, Any?> {
        val hc = client() ?: throw IllegalStateException("Health Connect is not available")
        val upserted = ArrayList<Map<String, Any?>>()
        val deleted = ArrayList<String>()
        var next = token
        var more = true
        var pages = 0
        while (more && pages < MAX_CHANGE_PAGES) {
            val response = hc.getChanges(next)
            if (response.changesTokenExpired) {
                return mapOf(
                    "tokenExpired" to true,
                    "upserted" to emptyList<Map<String, Any?>>(),
                    "deletedIds" to emptyList<String>(),
                    "nextToken" to null,
                    "hasMore" to false
                )
            }
            response.changes.forEach { change ->
                when (change) {
                    is UpsertionChange -> anyRecordMap(change.record)?.let { upserted += it }
                    is DeletionChange -> deleted += change.recordId
                }
            }
            next = response.nextChangesToken
            more = response.hasMore
            pages++
        }
        return mapOf(
            "tokenExpired" to false,
            "upserted" to upserted,
            "deletedIds" to deleted,
            "nextToken" to next,
            "hasMore" to more
        )
    }

    /**
     * Pages through a record type. Health Connect caps a response at 5000
     * records and hands back a token; ignoring it silently truncates a busy
     * day, which for step records is not rare - some watches write one record
     * per minute.
     */
    /** How a paged read ended. */
    enum class ReadOutcome {
        /** Every page was read. */
        COMPLETE,

        /** Stopped at [MAX_PAGES] with more to read. */
        TRUNCATED,

        /** A page failed - at once, or part way through. */
        FAILED
    }

    /** @return how the read ended; only [ReadOutcome.COMPLETE] saw every record. */
    private suspend fun <T : Record> readAll(
        hc: HealthConnectClient,
        type: Class<T>,
        start: Instant,
        end: Instant,
        newestFirst: Boolean = false,
        onRecord: (T) -> Unit
    ): ReadOutcome {
        var token: String? = null
        var pages = 0
        do {
            val response = try {
                hc.readRecords(
                    ReadRecordsRequest(
                        recordType = type.kotlin,
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                        ascendingOrder = !newestFirst,
                        pageSize = PAGE_SIZE,
                        pageToken = token
                    )
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // A caller's timeout: stop, rather than carry on as a failed read.
                throw cancelled
            } catch (_: Exception) {
                return ReadOutcome.FAILED
            }
            response.records.forEach(onRecord)
            token = response.pageToken
            pages++
        } while (token != null && pages < MAX_PAGES)
        return if (token == null) ReadOutcome.COMPLETE else ReadOutcome.TRUNCATED
    }

    private class Accumulator(val packageName: String, self: String) {
        var steps: Int = 0
        var stepsBefore: Int = 0
        var distance: Double = 0.0
        var calories: Double = 0.0
        var lastRecordAt: Long = 0L
        var kindOverride: StepSourceKind? = null
        private var deviceType: Int? = null
        val isSelf: Boolean = packageName == self

        /** Steps per recording method, indexed by [RecordingMethods.bucketOf]. */
        val methods = IntArray(4)
        /** Manual-entry steps that fell before coverage; a subset of [stepsBefore]. */
        var manualBefore: Int = 0
        /** Steps from records modified more than [LATE_WRITE_MS] after they ended. */
        var lateSteps: Int = 0
        /** Distance records were read for this origin's range. */
        var distanceRead: Boolean = false
        /** At least one distance record came from this origin. */
        var distanceSeen: Boolean = false
        /** Steps per local hour of the day. */
        val hourly = IntArray(HourlySteps.HOURS)
        var hourlyKnown: Boolean = true
        /** -1 until active calories are read for this origin. */
        var activeCalories: Double = -1.0
        var activeKnown: Boolean = true
        /**
         * False once any part of the total came from a read that carries no
         * per-record metadata (the aggregate path), at which point no split
         * can honestly be reported.
         */
        var methodsKnown: Boolean = true

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

        fun toStepSource(self: String, withCoverage: Boolean = false): StepSource {
            val split = if (methodsKnown) RecordingMethods.fromBuckets(methods) else null
            return StepSource(
                packageName = packageName,
                appName = StepSourceCatalog.appName(packageName),
                kind = kindOverride
                    ?: StepSourceCatalog.classify(packageName, deviceType, self),
                steps = steps,
                distance = distance,
                calories = calories,
                lastRecordAt = lastRecordAt,
                isSelf = isSelf,
                stepsBeforeCoverage = if (withCoverage) stepsBefore.coerceIn(0, steps) else -1,
                manualSteps = split?.manual ?: -1,
                unknownMethodSteps = split?.unknown ?: -1,
                recordingMethods = split,
                manualStepsBeforeCoverage = if (withCoverage && split != null) {
                    manualBefore.coerceIn(0, split.manual)
                } else {
                    -1
                },
                // Known exactly when the per-record metadata was, like the method split.
                lateWrittenSteps = if (methodsKnown) lateSteps.coerceIn(0, steps) else -1,
                hourlySteps = if (methodsKnown && hourlyKnown) hourly.toList() else HourlySteps.UNKNOWN,
                activeCalories = if (activeKnown) activeCalories else -1.0,
                distanceSource = DistanceSource.of(distanceRead, distanceSeen, isSelf)
            )
        }
    }

    // ---- writes ----------------------------------------------------------

    /**
     * Upserts one day. Returns false when the client is missing, steps may
     * not be written, or the provider rejected the write. Distance and
     * calories are written alongside when their grants are there and left
     * out when not: one insert carrying an ungranted type fails as a whole,
     * which stopped all mirroring for a user who unticked only distance.
     */
    suspend fun writeDay(totals: DayTotals): Boolean {
        val hc = client() ?: return false
        if (totals.steps <= 0) return true
        val access = access(grantedPermissions(), readTypes)
        if (!access.writeSteps) return false

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
        val records = listOfNotNull<Record>(
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
            if (ReadType.DISTANCE !in access.writeTypes) null else DistanceRecord(
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
            if (ReadType.TOTAL_CALORIES !in access.writeTypes) null else TotalCaloriesBurnedRecord(
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

        /**
         * Pages a per-source read takes before stopping. A watch writing one
         * record a minute writes 1,440 a day, so [RAW_READ_MAX_DAYS] of it is
         * about 50,400 records - past the 50 pages this used to allow.
         */
        private const val MAX_PAGES = 60

        /** Longest window answered from raw records; longer ones aggregate. */
        private const val RAW_READ_MAX_DAYS = 35L

        /** How long a granted-permissions answer is reused. */
        private const val GRANT_CACHE_MS = 5_000L

        /** A record last modified this long after it ended counts as written late. */
        const val LATE_WRITE_MS = 24L * 60 * 60 * 1000

        /** Pages [readStepRecords] reads before reporting `truncated`: 10,000 records. */
        private const val MAX_RAW_PAGES = 10

        /** Days a per-day refill reads at once. */
        private const val PER_DAY_PARALLEL = 4

        /** The most a per-day refill may take, however much time is left. */
        private const val PER_DAY_BUDGET_MS = 8_000L

        /** Kept back from a caller's deadline for the aggregate fill after a per-day refill. */
        private const val PER_DAY_RESERVE_MS = 2_000L

        /**
         * A per-day refill's time: [PER_DAY_BUDGET_MS], or what is left before
         * [deadline] once [PER_DAY_RESERVE_MS] is set aside, whichever is less.
         * Zero or less means there is no time for one - the gap is answered
         * from aggregates at once. [nowMs] and [deadline] are on one clock:
         * the uptime clock, as every caller passes them.
         */
        fun perDayBudget(nowMs: Long, deadline: Long): Long =
            if (deadline == Long.MAX_VALUE) {
                PER_DAY_BUDGET_MS
            } else {
                minOf(PER_DAY_BUDGET_MS, deadline - nowMs - PER_DAY_RESERVE_MS)
            }

        /** Pages [changes] reads per call before handing back `hasMore`. */
        private const val MAX_CHANGE_PAGES = 20

        fun deviceTypeName(type: Int): String = when (type) {
            Device.TYPE_WATCH -> "watch"
            Device.TYPE_PHONE -> "phone"
            Device.TYPE_SCALE -> "scale"
            Device.TYPE_RING -> "ring"
            Device.TYPE_HEAD_MOUNTED -> "head_mounted"
            Device.TYPE_FITNESS_BAND -> "fitness_band"
            Device.TYPE_CHEST_STRAP -> "chest_strap"
            Device.TYPE_SMART_DISPLAY -> "smart_display"
            else -> "unknown"
        }

        /**
         * The last day a newest-first read did not fully cover, or null when
         * it covered all of it. Records arrive newest first, so every day
         * after the oldest record read is whole; that day itself may be cut
         * part way, and every day before it was not reached. A read that got
         * nothing at all before failing covered nothing, up to [lastDay].
         */
        fun incompleteThrough(outcome: ReadOutcome, oldestReadDay: String?, lastDay: String): String? =
            when (outcome) {
                ReadOutcome.COMPLETE -> null
                else -> oldestReadDay ?: lastDay
            }

        /**
         * What a set of grants lets this package do. Pure, so the partial
         * grants the sheet allows - any single permission unticked - can be
         * tested without a provider.
         */
        fun access(granted: Set<String>, configuredReadTypes: Set<ReadType>): Access = Access(
            readSteps = ReadType.STEPS.permission in granted,
            readTypes = ReadType.entries.filterTo(LinkedHashSet()) {
                it in configuredReadTypes && it.permission in granted
            },
            writeSteps = ReadType.STEPS.writePermission in granted,
            writeTypes = ReadType.entries.filterTo(LinkedHashSet()) { it.writePermission in granted }
        )

        /** Every read type's permission: what reads need under the default `healthConnectReadTypes`. */
        val READ_PERMISSIONS: Set<String> = ReadType.permissions(ReadType.ALL)

        val WRITE_PERMISSIONS: Set<String> = ReadType.entries.mapTo(LinkedHashSet()) { it.writePermission }

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

        /** Opt-in: active calories per source (`healthConnectReadActiveCalories`). */
        val READ_ACTIVE_CALORIES: String =
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)

        /** Everything this package may ever ask for. */
        val ALL_PERMISSIONS: Set<String> = buildSet {
            addAll(REQUIRED)
            PERMISSION_BACKGROUND_READ?.let { add(it) }
            PERMISSION_HISTORY_READ?.let { add(it) }
            add(READ_ACTIVE_CALORIES)
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
