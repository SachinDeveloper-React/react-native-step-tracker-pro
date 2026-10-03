package com.steptrackerpro.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
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
import com.steptrackerpro.health.HealthConnectQuota.Category
import java.time.Instant
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Health Connect read/write, permissions and provider availability.
 *
 * Writes are idempotent: every record carries a stable `clientRecordId` of the
 * form `stp-<type>-<date>` - or `stp-<type>-<date>-<epoch minute>` for a
 * per-minute record, see [writeMinutes] - so re-syncing a day replaces the
 * previous record instead of stacking duplicates. That is what makes "sync
 * today's total every 15 minutes" safe. Health Connect keeps the higher
 * `clientRecordVersion` on a collision, so that value must never go
 * backwards - see [StepStateStore.nextHealthRecordVersion].
 *
 * Every data call - each page read, each aggregate, each changes call, each
 * insert or delete - goes through [metered], which counts it against Health
 * Connect's rate limits and stops making calls on a quota Health Connect has
 * just refused - see [HealthConnectQuota]. Pages are as large as Health
 * Connect allows, writes go in batches, and the changes feed tells the
 * core's cache which days moved, so a day nobody touched is never read twice.
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
     * this package writes, so each carries its write permission too, and
     * the prefix of the `clientRecordId` its day records carry.
     */
    enum class ReadType(
        val jsValue: String,
        val recordClass: KClass<out Record>,
        private val clientIdPrefix: String
    ) {
        STEPS("steps", StepsRecord::class, "stp-steps-"),
        DISTANCE("distance", DistanceRecord::class, "stp-distance-"),
        TOTAL_CALORIES("totalCalories", TotalCaloriesBurnedRecord::class, "stp-calories-");

        val permission: String = HealthPermission.getReadPermission(recordClass)
        val writePermission: String = HealthPermission.getWritePermission(recordClass)

        /**
         * The `clientRecordId` of the record this package writes for [date]:
         * the whole day, or - in minute mode - the steps no minute holds.
         */
        fun clientRecordId(date: String): String = clientIdPrefix + date

        /**
         * The `clientRecordId` of [date]'s minute record starting at
         * [minuteStart] - `stp-steps-<date>-<epoch minute>`, the minutes since
         * 1970-01-01T00:00Z. Counted from the epoch rather than from the
         * day's midnight so a minute keeps its id whatever the zone does
         * afterwards - a flight, a clock change - and a delete finds the
         * record it wrote; and a day's ids can still be listed without
         * having stored them.
         */
        fun minuteRecordId(date: String, minuteStart: Long): String =
            "$clientIdPrefix$date-${Math.floorDiv(minuteStart, MINUTE_MS)}"

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

    fun availability(): Availability {
        // Health Connect does not work in a work profile: the sheet can grant
        // there, but no call succeeds and nothing is written. Said up front,
        // rather than as reads that fail and writes that vanish.
        if (workProfile) return Availability.NOT_SUPPORTED
        return when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.UPDATE_REQUIRED
            // From Android 14 Health Connect is part of the platform, so there is
            // nothing to install: an unavailable SDK there means the device does
            // not support it at all. Below 14 it is an APK the user can install
            // - from Android 9: the APK does not install on 8, where sending
            // the user to Play was a dead end.
            else -> if (Build.VERSION.SDK_INT >= 34 || Build.VERSION.SDK_INT < MIN_PROVIDER_SDK) {
                Availability.NOT_SUPPORTED
            } else if (isProviderInstalled()) {
                Availability.NOT_SUPPORTED
            } else {
                Availability.NOT_INSTALLED
            }
        }
    }

    private fun isProviderInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(StepSourceCatalog.PROVIDER_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** This app runs in a work profile, where Health Connect is not supported. Fixed for the process's life. */
    val workProfile: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && runCatching {
            context.getSystemService(android.os.UserManager::class.java)?.isManagedProfile == true
        }.getOrDefault(false)
    }

    /**
     * Whether the installed Health Connect has [feature], one of
     * `HealthConnectFeatures.FEATURE_*`. Background and history reads are
     * features: a provider too old for one cannot grant its permission, so
     * it is neither requested nor reported as missing there. A local check -
     * the provider's version - not a metered call.
     */
    fun featureAvailable(feature: Int): Boolean = runCatching {
        client()?.features?.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }.getOrDefault(false)

    val backgroundReadAvailable: Boolean
        get() = featureAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)

    val historyReadAvailable: Boolean
        get() = featureAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)

    // ---- rate limits -----------------------------------------------------

    /** What this process has drawn on Health Connect's quotas, and which it may not draw on now. */
    val quota = HealthConnectQuota { android.os.SystemClock.elapsedRealtime() }

    /**
     * Called with Health Connect's refusal each time it refuses a call for
     * quota - not for the calls held back afterwards. Set by the core,
     * which reports it.
     */
    @Volatile
    var onRateLimited: ((HealthConnectRateLimitedException) -> Unit)? = null

    /**
     * Makes one Health Connect data call on [category]'s quota - the
     * foreground or background one, as Health Connect will judge it. Throws
     * [HealthConnectRateLimitedException] without calling while Health
     * Connect has just refused that quota, and in place of its refusal.
     */
    private suspend fun <T> metered(category: Category, call: suspend () -> T): T {
        val foreground = inForegroundNow()
        val wait = quota.retryAfterMs(category, foreground)
        if (wait > 0L) throw HealthConnectRateLimitedException(category, wait)
        quota.record(category, foreground)
        return try {
            call().also { quota.onAccepted(category, foreground) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!HealthConnectErrors.isRateLimited(error)) throw error
            val refusal = HealthConnectRateLimitedException(category, quota.onRefused(category, foreground), error)
            runCatching { onRateLimited?.invoke(refusal) }
            throw refusal
        }
    }

    /**
     * Whether Health Connect counts this app as in the foreground right now
     * - see [inForegroundForReads]. Which quota a call draws on, and whether
     * a read needs the background grant.
     */
    fun inForegroundNow(): Boolean {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        return inForegroundForReads(info.importance)
    }

    /**
     * Whether work nobody is waiting for - a refresh the sensor asked for -
     * may read Health Connect now: see [HealthConnectQuota.hasHeadroom].
     */
    fun hasReadHeadroom(): Boolean = quota.hasHeadroom(Category.READ, inForegroundNow())

    // ---- this phone's own step counting ----------------------------------

    @Volatile
    private var deviceOrigin: String? = null

    @Volatile
    private var deviceOriginAsked = false

    /**
     * This phone's own synthetic package name in Health Connect, asked once
     * per process - and again after the grants change, since the platform
     * only answers an app that holds a read grant. See [DeviceDataSources].
     * Its callers hold `READ_STEPS`. A timeout stands like an answer: asked
     * again on every read, a platform that never answers would cost every
     * read the wait.
     */
    suspend fun currentDeviceOrigin(): String? {
        if (deviceOriginAsked) return deviceOrigin
        if (client() == null) return null
        val origin = kotlinx.coroutines.withTimeoutOrNull(DEVICE_ORIGIN_TIMEOUT_MS) {
            DeviceDataSources.currentOrigin(context)
        }
        deviceOrigin = origin
        origin?.let { StepSourceCatalog.currentDeviceOrigin = it }
        deviceOriginAsked = true
        return origin
    }

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
            // Uptime clock: after the wall clock is set back, "now minus then"
            // would be negative and the cache would look fresh for hours.
            grantedCache?.let { (at, set) ->
                if (android.os.SystemClock.elapsedRealtime() - at < GRANT_CACHE_MS) return set
            }
        }
        val hc = client()
        val set = if (hc == null) {
            emptySet()
        } else {
            try {
                hc.permissionController.getGrantedPermissions()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A check that failed is not a revocation. The provider APK
                // refuses even this call once the app's quota is used up, and
                // reading that as "nothing granted" told the app - and the
                // user - that Health Connect had been switched off. The last
                // answer stands, uncached, so the next call asks again.
                return lastGranted ?: emptySet()
            }
        }
        grantedCache = android.os.SystemClock.elapsedRealtime() to set
        val previous = lastGranted
        lastGranted = set
        if (previous != null && previous != set) {
            // The token's types may no longer be readable, and the platform
            // answers the device question only with a read grant.
            resetChangesFeed()
            deviceOriginAsked = false
            onGrantsChanged?.invoke()
        }
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
        val dropped: Set<String> = emptySet(),
        /** Vitals to read, from `healthConnectReadVitals` - opt-ins, like [activeCalories]. Only with [read]. */
        val vitals: Set<VitalType> = emptySet(),
        /**
         * The installed Health Connect has the background-read feature. A
         * provider without it cannot grant [backgroundRead]'s permission, so
         * it is not asked for - asking for a permission the provider does not
         * know can cost the whole sheet.
         */
        val backgroundReadAvailable: Boolean = true,
        /** The installed Health Connect has the history-read feature - see [backgroundReadAvailable]. */
        val historyReadAvailable: Boolean = true
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

        /** Required plus whichever optional grants were opted into, and the provider has. */
        val requested: Set<String>
            get() = buildSet {
                addAll(required)
                if (backgroundRead && backgroundReadAvailable) PERMISSION_BACKGROUND_READ?.let { add(it) }
                if (historyRead && historyReadAvailable) PERMISSION_HISTORY_READ?.let { add(it) }
                if (read && activeCalories) add(READ_ACTIVE_CALORIES)
                if (read) addAll(VitalType.permissions(vitals))
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
        val available = availability == Availability.AVAILABLE
        val granted = if (available) grantedPermissions(fresh = true) else emptySet()
        val required = scope.required
        val missing = scope.requested - granted
        val denials = state.healthPermissionDenials
        val access = access(granted, scope.readTypes)
        val origin = if (available && access.readSteps) currentDeviceOrigin() else null
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
                scope.essential.isNotEmpty()),
            // Whether the installed provider can grant the two optional
            // reads at all. Without the feature the permission is not asked
            // for, so `backgroundReadGranted` stays false however often.
            "backgroundReadAvailable" to (available && scope.backgroundReadAvailable),
            "historyReadAvailable" to (available && scope.historyReadAvailable),
            // Of `healthConnectReadVitals`, the ones the user allowed.
            "grantedVitals" to scope.vitals.filter { it.permission in granted }.map { it.jsValue },
            "workProfile" to workProfile,
            // Health Connect counting this phone's steps itself, and the
            // package name its records carry here when the platform says.
            "deviceStepTracking" to mapOf(
                "available" to (available && DeviceDataSources.stepTrackingAvailable()),
                "dataOrigin" to origin
            ),
            "rateLimit" to quota.toMap(inForegroundNow())
        )
    }

    // ---- reads -----------------------------------------------------------

    /**
     * Whether Health Connect can be read right now. It refuses a read from an
     * app in the background - no activity on screen and no foreground service
     * running - unless the app holds `READ_HEALTH_DATA_IN_BACKGROUND`. While
     * the tracking service runs, it is a foreground service, so that is not
     * the case. The resolved reads check this instead of
     * trying: a refused read is not one that found nothing, and is reported
     * as Health Connect not consulted.
     */
    suspend fun canReadNow(): Boolean {
        if (inForegroundNow()) return true
        val background = PERMISSION_BACKGROUND_READ ?: return false
        return grantedPermissions().contains(background)
    }

    /**
     * Daily totals aggregated across Health Connect origins. Throws when
     * they cannot be read: an empty list means nobody wrote steps, never
     * that the read went wrong.
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
        val hc = client() ?: throw IllegalStateException("Health Connect is not available")
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
        return metered(Category.READ) { hc.aggregateGroupByPeriod(request) }
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
     * [readSourcesByDay]'s answer: each day's origins, and which days were
     * answered in full.
     */
    data class DaySources(
        val byDate: Map<String, List<StepSource>>,
        /**
         * Days answered record by record with every companion type read
         * through - what a read of that day alone would have said. The rest
         * were filled from aggregates (no recording-method split, no hourly
         * profile) or had a companion type cut short: they serve the range
         * they were read for, and never stand in for a read of the day.
         */
        val detailed: Set<String>
    )

    /**
     * Every origin that published steps in the range, keyed by day, with the
     * device type each origin stamped on its records.
     *
     * This reads raw records rather than aggregating, because the aggregate API
     * returns totals with the contributing origins attached but no way to split
     * a total between them, and no device metadata at all - which is exactly
     * what tells a watch apart from a phone-side pedometer.
     *
     * Throws when the steps cannot be read, even from the aggregates that
     * stand in for a raw read that failed: an empty answer means no app wrote
     * steps, never that the read went wrong.
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
    ): Map<String, List<StepSource>> = readSourcesByDay(start, end, coverageStartMs, perDayFill, deadline).byDate

    /** [readDailyStepsBySource], saying which days it answered in full - see [DaySources.detailed]. */
    suspend fun readSourcesByDay(
        start: Instant,
        end: Instant,
        coverageStartMs: Long = 0L,
        perDayFill: Boolean = true,
        deadline: Long = Long.MAX_VALUE
    ): DaySources {
        val hc = client() ?: throw IllegalStateException("Health Connect is not available")
        val self = context.packageName
        val coverageDate = if (coverageStartMs > 0L) DateKeys.of(coverageStartMs) else null
        // Once per process: this phone's own name in Health Connect, so its
        // records are classified as this phone's whatever shape the name has.
        currentDeviceOrigin()

        // A raw read is bounded by MAX_PAGES * MAX_PAGE_SIZE records. A watch that
        // writes one record a minute produces 1,440 a day, so anything past a
        // few weeks would be silently truncated - and a truncated month looks
        // like a watch that stopped counting half way through. Long windows
        // are answered per origin through the aggregate API instead, which
        // costs one query per origin per window rather than one page per
        // five thousand records.
        val days = java.time.Duration.between(start, end).toDays()
        if (days > RAW_READ_MAX_DAYS) return readSourcesByDayAggregated(start, end, coverageStartMs, deadline)

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
        // The last day a companion type was cut short through: days up to it
        // carry a zeroed figure where a read of the day alone would have one.
        var cutThrough: String? = null
        /** Applies [whole] to each origin's day the read covered, [cut] to the rest. */
        fun settle(outcome: ReadOutcome, oldest: Long, whole: (Accumulator) -> Unit, cut: (Accumulator) -> Unit) {
            val through = incompleteThrough(outcome, oldest.takeIf { it != Long.MAX_VALUE }?.let { DateKeys.of(it) }, lastDay)
            val previous = cutThrough
            if (through != null && (previous == null || through > previous)) cutThrough = through
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
        val dates = datesOf(start, end)
        val cut = cutThrough
        fun whole(date: String): Boolean = cut == null || date > cut
        val incomplete = incompleteThrough(
            stepsOutcome, oldestRead.takeIf { it != Long.MAX_VALUE }?.let { DateKeys.of(it) }, DateKeys.of(end.toEpochMilli())
        ) ?: return DaySources(read, dates.filterTo(HashSet()) { whole(it) })

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
        var filledInFull: Set<String> = emptySet()
        val filled = if (needsRecordingMethods && perDayFill && budget > 0) {
            val perDays = perDay(DateKeys.of(start.toEpochMilli()), incomplete, gapEnd, coverageStartMs, budget)
            val days = perDays.mapValues { it.value.sources }
            filledInFull = perDays.filterValues { it.detailed }.keys
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
        return DaySources(
            byDate = read.filterKeys { it > incomplete } + filled.filterKeys { it <= incomplete },
            detailed = dates.filterTo(HashSet()) { if (it > incomplete) whole(it) else it in filledInFull }
        )
    }

    /** One day of a [perDay] refill: its sources, and whether that read answered it in full. */
    private data class DayRead(val sources: List<StepSource>, val detailed: Boolean)

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
    ): Map<String, DayRead> = kotlinx.coroutines.coroutineScope {
        val deadline = android.os.SystemClock.elapsedRealtime() + budgetMs
        val permits = kotlinx.coroutines.sync.Semaphore(PER_DAY_PARALLEL)
        DateKeys.rangeOf(first, last).map { date ->
            async {
                permits.withPermit {
                    val left = deadline - android.os.SystemClock.elapsedRealtime()
                    if (left <= 0) return@withPermit null
                    val dayEnd = minOf(DateKeys.endOfDayInstant(date), end)
                    kotlinx.coroutines.withTimeoutOrNull(left) {
                        val day = readSourcesByDay(
                            DateKeys.startOfDayInstant(date), dayEnd, coverageStartMs, perDayFill = false
                        )
                        date to DayRead(day.byDate[date].orEmpty(), date in day.detailed)
                    }
                }
            }
        }.awaitAll().filterNotNull().toMap()
    }

    /**
     * Every origin that wrote steps between two instants, from the aggregate
     * API's list of contributors. Throws when it cannot be read: an empty set
     * would pass for a window no other app wrote in.
     */
    private suspend fun originsWithSteps(hc: HealthConnectClient, start: Instant, end: Instant): Set<String> =
        metered(Category.READ) {
            hc.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
        }.dataOrigins.mapTo(HashSet()) { it.packageName }

    /**
     * Each of [kinds]' origins' daily totals from the aggregate API, as
     * sources without per-record detail: no recording-method split, no hourly
     * profile, no late-write share - the -1 and null every aggregate-answered
     * day carries. Throws when an origin's totals cannot be read.
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
     *
     * Only the recent window's days are answered in full; the older ones
     * could be missing an origin that wrote nothing recently.
     */
    private suspend fun readSourcesByDayAggregated(
        start: Instant,
        end: Instant,
        coverageStartMs: Long,
        deadline: Long
    ): DaySources {
        val discoveryStart = maxOf(start, end.minus(java.time.Duration.ofDays(RAW_READ_MAX_DAYS)))
        // With the coverage split, so today reads the same here as on its own.
        val recent = readSourcesByDay(discoveryStart, end, coverageStartMs, deadline = deadline)
        val kinds = HashMap<String, StepSourceKind>()
        val names = HashMap<String, String>()
        recent.byDate.values.flatten().forEach { source ->
            val known = kinds[source.packageName]
            if (known == null || source.kind.isWearable) kinds[source.packageName] = source.kind
            names[source.packageName] = source.appName
        }
        if (kinds.isEmpty()) return DaySources(emptyMap(), recent.detailed)

        val out = HashMap<String, List<StepSource>>(aggregateBySource(start, end, kinds, names))
        // The recent window's raw numbers are exact and carry real timestamps;
        // let them override the aggregate for the days they cover.
        recent.byDate.forEach { (date, sources) -> out[date] = sources }
        return DaySources(out, recent.detailed)
    }

    /** Flattened view of [readDailyStepsBySource] over the whole range. */
    suspend fun listSources(
        start: Instant,
        end: Instant,
        deadline: Long = Long.MAX_VALUE
    ): List<StepSource> = mergeSources(readDailyStepsBySource(start, end, deadline = deadline).values)

    /**
     * One entry per origin across a range, from each day's sources: totals
     * summed, and every per-record figure only as known as its worst day.
     */
    fun mergeSources(days: Collection<List<StepSource>>): List<StepSource> {
        val self = context.packageName
        val merged = HashMap<String, Accumulator>()
        days.flatten().forEach { source ->
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
                val response = metered(Category.READ) {
                    hc.readRecords(
                        ReadRecordsRequest(
                            recordType = type.type,
                            timeRangeFilter = TimeRangeFilter.between(start, end),
                            pageSize = MAX_PAGE_SIZE,
                            pageToken = token
                        )
                    )
                }
                response.records.forEach { record -> anyRecordMap(record)?.let { out += it } }
                token = response.pageToken
                pages++
            } while (hasMorePages(token) && pages < MAX_RAW_PAGES)
            if (hasMorePages(token)) truncated = true
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
        return metered(Category.READ) {
            hc.getChangesToken(ChangesTokenRequest(recordTypes = types.map { it.type }.toSet()))
        }
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
            val response = metered(Category.READ) { hc.getChanges(next) }
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

    // ---- the changes feed behind the source cache ---------------------------

    /** Which days the changes feed says moved - see [changedSinceLastLook]. */
    data class ChangedDays(
        /** Nothing can be vouched for: a new or expired token, a deletion, or more changes than one look reads. */
        val all: Boolean,
        /** The days records were inserted or updated on, by start time. */
        val dates: Set<String>
    ) {
        companion object {
            val ALL = ChangedDays(all = true, dates = emptySet())
        }
    }

    private val feedLock = Mutex()

    @Volatile
    private var feedToken: String? = null

    @Volatile
    private var feedTypes: Set<KClass<out Record>> = emptySet()

    /**
     * Which days' records moved since the previous call, from Health
     * Connect's changes feed - so the core's cache of each day's origins can
     * keep a day nobody touched instead of reading it again. One call - a
     * page of changes - when nothing moved, against a page per record type
     * for reading even one quiet day; Health Connect's own advice is to
     * follow changes rather than re-read.
     *
     * The token covers the types the reads use and are granted, so a change
     * to any of them is seen. A deletion says only which record went, not
     * which day, so it reports [ChangedDays.all]; so do a new token and an
     * expired one. Null when the feed cannot be read at all - no provider,
     * a grant gone, the quota - and the caller falls back on its own expiry.
     */
    suspend fun changedSinceLastLook(): ChangedDays? = feedLock.withLock {
        val hc = client() ?: return@withLock null
        try {
            val types = feedRecordTypes()
            val token = feedToken?.takeIf { types == feedTypes } ?: return@withLock restartFeed(hc, types)
            val dates = HashSet<String>()
            var deleted = false
            var next = token
            var pages = 0
            var more: Boolean
            do {
                val response = metered(Category.READ) { hc.getChanges(next) }
                if (response.changesTokenExpired) return@withLock restartFeed(hc, types)
                response.changes.forEach { change ->
                    when (change) {
                        is UpsertionChange -> startOf(change.record)?.let { dates += DateKeys.of(it.toEpochMilli()) }
                        is DeletionChange -> deleted = true
                    }
                }
                next = response.nextChangesToken
                more = response.hasMore
                pages++
            } while (more && pages < MAX_FEED_PAGES)
            // A backlog this long - a watch app pushing weeks of history - is
            // cheaper to answer by re-reading than by paging through.
            if (more) return@withLock restartFeed(hc, types)
            feedToken = next
            if (deleted) ChangedDays.ALL else ChangedDays(all = false, dates = dates)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: HealthConnectRateLimitedException) {
            // The token is still good; the next look carries on from it.
            null
        } catch (_: Exception) {
            feedToken = null
            null
        }
    }

    /** Forgets the token, so the next look starts a new one and vouches for nothing before it. */
    fun resetChangesFeed() {
        feedToken = null
    }

    private suspend fun restartFeed(hc: HealthConnectClient, types: Set<KClass<out Record>>): ChangedDays {
        feedToken = null
        val token = metered(Category.READ) { hc.getChangesToken(ChangesTokenRequest(recordTypes = types)) }
        feedTypes = types
        feedToken = token
        return ChangedDays.ALL
    }

    /** What the per-source reads consume and may read: steps, and each granted companion type. */
    private suspend fun feedRecordTypes(): Set<KClass<out Record>> {
        val granted = grantedPermissions()
        return buildSet {
            add(StepsRecord::class)
            access(granted, readTypes).readTypes.forEach { add(it.recordClass) }
            if (readActiveCalories && READ_ACTIVE_CALORIES in granted) add(ActiveCaloriesBurnedRecord::class)
        }
    }

    /** The start of a record the per-source reads bucket by, or null for a type they do not read. */
    private fun startOf(record: Record): Instant? = when (record) {
        is StepsRecord -> record.startTime
        is DistanceRecord -> record.startTime
        is TotalCaloriesBurnedRecord -> record.startTime
        is ActiveCaloriesBurnedRecord -> record.startTime
        else -> null
    }

    /** How a paged read ended. */
    enum class ReadOutcome {
        /** Every page was read. */
        COMPLETE,

        /** Stopped at [MAX_PAGES] with more to read. */
        TRUNCATED,

        /** A page failed - at once, or part way through - or Health Connect refused it for quota. */
        FAILED
    }

    /**
     * Pages through a record type, [MAX_PAGE_SIZE] records at a time - the
     * most Health Connect returns - so a busy day is one call rather than
     * five. Health Connect hands back a token while more remain; ignoring it
     * silently truncates a busy day, which for step records is not rare -
     * some watches write one record per minute. On Android 12 and 13, and
     * across some IPC boundaries, Health Connect's guidance is that the last
     * page's token can come back empty rather than null. Taken for "more to
     * come", it was sent back as the next page's token, and a read that had
     * finished carried on - a call that could fail the read, or start it over
     * until the page cap - so empty ends the read too, see [hasMorePages].
     *
     * @return how the read ended; only [ReadOutcome.COMPLETE] saw every record.
     */
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
                metered(Category.READ) {
                    hc.readRecords(
                        ReadRecordsRequest(
                            recordType = type.kotlin,
                            timeRangeFilter = TimeRangeFilter.between(start, end),
                            ascendingOrder = !newestFirst,
                            pageSize = MAX_PAGE_SIZE,
                            pageToken = token
                        )
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // A caller's timeout: stop, rather than carry on as a failed read.
                throw cancelled
            } catch (_: Exception) {
                // A refusal for quota too: steps that fail here fall through
                // to the aggregate fill, whose call then says so; a companion
                // type is cut, as for any failure.
                return ReadOutcome.FAILED
            }
            response.records.forEach(onRecord)
            token = response.pageToken
            pages++
        } while (hasMorePages(token) && pages < MAX_PAGES)
        return if (hasMorePages(token)) ReadOutcome.TRUNCATED else ReadOutcome.COMPLETE
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

    /** How a batch of day writes went - see [writeDays]. */
    data class WriteOutcome(
        /** Days Health Connect accepted, and days with nothing to write. */
        val written: Set<String>,
        /** Days not written: worth another attempt, unless nothing could be written at all. */
        val failed: Set<String>,
        /** Health Connect refused a batch for quota, and the batches after it were not tried. */
        val rateLimited: Boolean = false
    )

    /**
     * Upserts [days] in as few calls as Health Connect allows: up to
     * [MAX_RECORDS_PER_INSERT] records per insert, a day's records always in
     * the same one. Writing a hundred pending days used to be a hundred
     * inserts - a tenth of the background write quota for one sync. A batch
     * that fails costs only its own days, which stay pending, and those
     * already written stay written: Health Connect's advice is to retry from
     * where a write failed, not to start over. Once one is refused for
     * quota the rest wait for the next sync.
     *
     * Distance and calories are written alongside when their grants are
     * there and left out when not: one insert carrying an ungranted type
     * fails as a whole, which stopped all mirroring for a user who unticked
     * only distance. A day with no steps has nothing to write and counts as
     * written.
     */
    suspend fun writeDays(days: List<DayTotals>): WriteOutcome {
        val dates = days.mapTo(LinkedHashSet()) { it.date }
        val hc = client() ?: return WriteOutcome(emptySet(), dates)
        val access = access(grantedPermissions(), readTypes)
        if (!access.writeSteps) return WriteOutcome(emptySet(), dates)

        val now = Instant.now()
        val written = LinkedHashSet<String>()
        val toWrite = days.filter { day ->
            (dayInterval(day, now) != null).also { writable -> if (!writable) written += day.date }
        }
        if (toWrite.isEmpty()) return WriteOutcome(written, emptySet())

        // Health Connect resolves same-id collisions by keeping the higher
        // version, so this has to be monotonic. Using the wall clock meant that
        // after the user set the clock back, every write was silently discarded
        // by the provider while insertRecords still reported success. One
        // version for the batch: every record in it is newer than any before.
        val version = state.nextHealthRecordVersion()
        val batches = ArrayList<MutableList<Pair<String, List<Record>>>>()
        var size = 0
        for (day in toWrite) {
            val records = dayRecords(day, access.writeTypes, version, now)
            if (batches.isEmpty() || size + records.size > MAX_RECORDS_PER_INSERT) {
                batches.add(ArrayList())
                size = 0
            }
            batches.last().add(day.date to records)
            size += records.size
        }

        val failed = LinkedHashSet<String>()
        var rateLimited = false
        for (batch in batches) {
            if (rateLimited) {
                batch.forEach { failed += it.first }
                continue
            }
            try {
                metered(Category.WRITE) { hc.insertRecords(batch.flatMap { it.second }) }
                batch.forEach { written += it.first }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (limited: HealthConnectRateLimitedException) {
                rateLimited = true
                batch.forEach { failed += it.first }
            } catch (_: Exception) {
                batch.forEach { failed += it.first }
            }
        }
        return WriteOutcome(written, failed, rateLimited)
    }

    /**
     * The span one day's records cover: midnight to midnight, or to now for
     * the open day - never an end time in the future. Null when there is
     * nothing to write: no steps, or no time yet.
     */
    private fun dayInterval(totals: DayTotals, now: Instant): Pair<Instant, Instant>? {
        if (totals.steps <= 0) return null
        val start = DateKeys.startOfDayInstant(totals.date)
        val rawEnd = DateKeys.endOfDayInstant(totals.date)
        val end = if (rawEnd.isAfter(now)) now else rawEnd
        return if (end.isAfter(start)) start to end else null
    }

    /**
     * One day's records: steps, and distance and calories where [writeTypes]
     * allow, each with its zone offset and its device. The device says what
     * Health Connect's metadata guidance asks for - a phone, by maker and
     * model - so an app reading these knows what counted them.
     */
    private fun dayRecords(totals: DayTotals, writeTypes: Set<ReadType>, version: Long, now: Instant): List<Record> {
        val (start, end) = dayInterval(totals, now) ?: return emptyList()
        val zone = ZoneId.systemDefault()
        val startOffset = zone.rules.getOffset(start)
        val endOffset = zone.rules.getOffset(end)
        val device = thisPhone
        fun metadata(type: ReadType) = Metadata.autoRecorded(
            device = device,
            clientRecordId = type.clientRecordId(totals.date),
            clientRecordVersion = version
        )
        // Explicitly typed: the three record classes share only the
        // library-internal IntervalRecord supertype, which Kotlin 2.x refuses
        // to infer as a type argument.
        return listOfNotNull<Record>(
            StepsRecord(
                count = totals.steps.toLong(),
                startTime = start,
                endTime = end,
                startZoneOffset = startOffset,
                endZoneOffset = endOffset,
                metadata = metadata(ReadType.STEPS)
            ),
            if (ReadType.DISTANCE !in writeTypes) null else DistanceRecord(
                distance = Length.meters(totals.distance),
                startTime = start,
                endTime = end,
                startZoneOffset = startOffset,
                endZoneOffset = endOffset,
                metadata = metadata(ReadType.DISTANCE)
            ),
            if (ReadType.TOTAL_CALORIES !in writeTypes) null else TotalCaloriesBurnedRecord(
                energy = Energy.kilocalories(totals.calories),
                startTime = start,
                endTime = end,
                startZoneOffset = startOffset,
                endZoneOffset = endOffset,
                metadata = metadata(ReadType.TOTAL_CALORIES)
            )
        )
    }

    private val thisPhone: Device by lazy {
        Device(
            type = Device.TYPE_PHONE,
            manufacturer = Build.MANUFACTURER?.takeIf { it.isNotBlank() },
            model = Build.MODEL?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * Deletes the records this package wrote for [dates], by client record
     * id - so nothing else the app writes to Health Connect itself is
     * touched, which a time-range delete would take too - for every type
     * whose write permission is granted; Health Connect needs it to delete.
     * Each date's day record goes, and its minute records: those in
     * [minutes] (minute starts, by date), and for a date in [allMinutes]
     * every minute of the day, for a day whose written minutes nothing
     * remembers any more. Returns those types. Throws when a delete fails or
     * is refused for quota: the caller decides whether that matters.
     */
    suspend fun deleteOwnDays(
        dates: Collection<String>,
        minutes: Map<String, Collection<Long>> = emptyMap(),
        allMinutes: Set<String> = emptySet()
    ): Set<ReadType> {
        val hc = client() ?: throw IllegalStateException("Health Connect is not available")
        val types = access(grantedPermissions(), readTypes).writeTypes
        for (type in types) {
            val ids = LinkedHashSet<String>()
            dates.forEach { ids += type.clientRecordId(it) }
            minutes.forEach { (date, starts) -> starts.forEach { ids += type.minuteRecordId(date, it) } }
            allMinutes.forEach { date -> minutesOf(date).forEach { ids += type.minuteRecordId(date, it) } }
            for (chunk in ids.chunked(MAX_IDS_PER_DELETE)) {
                metered(Category.WRITE) {
                    hc.deleteRecords(recordType = type.recordClass, recordIdsList = emptyList(), clientRecordIdsList = chunk)
                }
            }
        }
        return types
    }

    // ---- per-minute writes -------------------------------------------------

    /** One day's per-minute write - see [writeMinutes]. */
    data class MinuteDay(
        /** The day's mirrored totals: every record's distance and calories are a share of them. */
        val totals: DayTotals,
        /** Where the day's records end: the next midnight, or now for today. */
        val dayEnd: Instant,
        /** Minute start -> steps, for each minute record to write. */
        val upserts: Map<Long, Int>,
        /** Minute starts whose record goes. */
        val deletes: List<Long>,
        /** The day's steps no minute holds, over [residualSpan]; 0 for none. */
        val residual: Int,
        val residualSpan: com.steptrackerpro.core.MinuteWritePlan.Span?,
        /** The day record goes: it held steps last time, or one from day mode may stand. */
        val deleteResidual: Boolean
    ) {
        val date: String get() = totals.date
    }

    /** How a [writeMinutes] went. */
    data class MinuteWriteOutcome(
        /** Minute start -> what Health Connect now holds for it (0 once deleted), for every part that landed. */
        val written: Map<Long, Int> = emptyMap(),
        /** Days every part of whose write landed. */
        val complete: Set<String> = emptySet(),
        /** Days some minute record was inserted for: they have per-minute records now. */
        val inserted: Set<String> = emptySet(),
        /** Days anything landed for, an insert or a delete: what Health Connect holds for them changed. */
        val changed: Set<String> = emptySet(),
        /** Days whose day record now holds their unplaced steps. */
        val residualWritten: Set<String> = emptySet(),
        /** Days whose day record was deleted. */
        val residualDeleted: Set<String> = emptySet(),
        /** Days some part of whose write did not land; worth another attempt. */
        val failed: Set<String> = emptySet(),
        /** Health Connect refused a call for quota, and what came after it was not tried. */
        val rateLimited: Boolean = false
    )

    /**
     * Writes days as per-minute records - `healthConnectWriteGranularity:
     * 'minute'`, which is what Health Connect's write guide asks of steps -
     * and the day record (`stp-steps-<date>`) for the steps no minute holds,
     * over the longest stretch of the day no minute covers. Only what moved
     * since the last write is sent, from the plans
     * [com.steptrackerpro.core.MinuteWritePlan] makes.
     *
     * Nothing two of these records cover overlaps - Health Connect counts
     * only one of two overlapping records from the same app - at any point
     * of the write, not only once it is done. Deletes go first, so a record
     * on its way out never stands beside the one replacing it, and a day
     * whose deletes failed writes nothing this time. Then each day's day
     * record, before its minutes: the full-day record day mode wrote, or
     * the last pass's residual over a stretch some new minute now falls in,
     * is cut down to this pass's residual before anything lands inside it,
     * and a day whose day record did not land writes no minute this time.
     * Up to [MAX_RECORDS_PER_INSERT] records an insert, a minute's records
     * always together; a batch that fails costs its days the rest of this
     * pass, and what landed is reported in `written`, so the next sync
     * carries on from there rather than starting the day over - Health
     * Connect's advice for a failed write.
     */
    suspend fun writeMinutes(days: List<MinuteDay>): MinuteWriteOutcome {
        val dates = days.mapTo(LinkedHashSet()) { it.date }
        if (days.isEmpty()) return MinuteWriteOutcome()
        val hc = client() ?: return MinuteWriteOutcome(failed = dates)
        val access = access(grantedPermissions(), readTypes)
        if (!access.writeSteps) return MinuteWriteOutcome(failed = dates)
        val types = access.writeTypes

        val written = HashMap<Long, Int>()
        val failed = LinkedHashSet<String>()
        val residualWritten = HashSet<String>()
        val residualDeleted = HashSet<String>()
        val inserted = HashSet<String>()
        val changed = HashSet<String>()
        var rateLimited = false

        // Deletes, one list of ids per type across every day; a chunk that
        // fails fails the days in it.
        for (type in types) {
            val ids = ArrayList<Pair<String, String>>()
            for (day in days) {
                day.deletes.forEach { ids += day.date to type.minuteRecordId(day.date, it) }
                if (day.deleteResidual) ids += day.date to type.clientRecordId(day.date)
            }
            for (chunk in ids.chunked(MAX_IDS_PER_DELETE)) {
                val chunkDates = chunk.mapTo(HashSet()) { it.first }
                if (rateLimited) {
                    failed += chunkDates
                    continue
                }
                try {
                    metered(Category.WRITE) {
                        hc.deleteRecords(type.recordClass, emptyList(), chunk.map { it.second })
                    }
                    changed += chunkDates
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (limited: HealthConnectRateLimitedException) {
                    rateLimited = true
                    failed += chunkDates
                } catch (_: Exception) {
                    failed += chunkDates
                }
            }
        }
        for (day in days) {
            if (day.date in failed) continue
            day.deletes.forEach { written[it] = 0 }
            if (day.deleteResidual) residualDeleted += day.date
        }

        // Inserts, a piece per minute and one for each day's residual - its
        // day record, first - so a minute's records land or fail together.
        val now = Instant.now()
        val version = state.nextHealthRecordVersion()
        class Piece(val date: String, val minute: Long?, val steps: Int, val records: List<Record>)
        val pieces = ArrayList<Piece>()
        for (day in days) {
            if (day.date in failed) continue
            val dayStart = DateKeys.startOfDayInstant(day.date)
            val end = minOf(day.dayEnd, now)
            val span = day.residualSpan
            if (day.residual > 0 && span != null) {
                val start = maxOf(Instant.ofEpochMilli(span.start), dayStart)
                val spanEnd = minOf(Instant.ofEpochMilli(span.end), end)
                if (spanEnd.isAfter(start)) {
                    pieces += Piece(
                        day.date, null, day.residual,
                        intervalRecords(day.totals, day.residual, start, spanEnd, types, version) { it.clientRecordId(day.date) }
                    )
                }
            }
            for ((minute, steps) in day.upserts) {
                val start = Instant.ofEpochMilli(minute)
                val minuteEnd = minOf(start.plusMillis(MINUTE_MS), end)
                // The minute that is still running, at its very first instant.
                if (!minuteEnd.isAfter(start)) continue
                pieces += Piece(
                    day.date, minute, steps,
                    intervalRecords(day.totals, steps, start, minuteEnd, types, version) { it.minuteRecordId(day.date, minute) }
                )
            }
        }
        val batches = ArrayList<MutableList<Piece>>()
        var size = 0
        for (piece in pieces) {
            if (batches.isEmpty() || size + piece.records.size > MAX_RECORDS_PER_INSERT) {
                batches.add(ArrayList())
                size = 0
            }
            batches.last().add(piece)
            size += piece.records.size
        }
        for (planned in batches) {
            // A day that failed in an earlier batch writes nothing more this
            // pass: what is left of it could land inside its old day record.
            val batch = planned.filter { it.date !in failed }
            if (batch.isEmpty()) continue
            if (rateLimited) {
                batch.forEach { failed += it.date }
                continue
            }
            try {
                metered(Category.WRITE) { hc.insertRecords(batch.flatMap { it.records }) }
                for (piece in batch) {
                    if (piece.minute != null) {
                        written[piece.minute] = piece.steps
                        inserted += piece.date
                    } else {
                        residualWritten += piece.date
                    }
                    changed += piece.date
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (limited: HealthConnectRateLimitedException) {
                rateLimited = true
                batch.forEach { failed += it.date }
            } catch (_: Exception) {
                batch.forEach { failed += it.date }
            }
        }
        return MinuteWriteOutcome(
            written = written,
            complete = dates - failed,
            inserted = inserted,
            changed = changed,
            residualWritten = residualWritten,
            residualDeleted = residualDeleted,
            failed = failed,
            rateLimited = rateLimited
        )
    }

    /**
     * The records for [steps] of a day over one interval: steps, and the
     * day's distance and calories in the same proportion where [types]
     * allow, each with its zone offsets, its device and its id.
     */
    private fun intervalRecords(
        totals: DayTotals,
        steps: Int,
        start: Instant,
        end: Instant,
        types: Set<ReadType>,
        version: Long,
        id: (ReadType) -> String
    ): List<Record> {
        val zone = ZoneId.systemDefault()
        val startOffset = zone.rules.getOffset(start)
        val endOffset = zone.rules.getOffset(end)
        val share = if (totals.steps > 0) steps.toDouble() / totals.steps else 0.0
        fun metadata(type: ReadType) = Metadata.autoRecorded(
            device = thisPhone,
            clientRecordId = id(type),
            clientRecordVersion = version
        )
        return listOfNotNull<Record>(
            StepsRecord(
                count = steps.toLong(),
                startTime = start,
                endTime = end,
                startZoneOffset = startOffset,
                endZoneOffset = endOffset,
                metadata = metadata(ReadType.STEPS)
            ),
            if (ReadType.DISTANCE !in types) null else DistanceRecord(
                distance = Length.meters(totals.distance * share),
                startTime = start,
                endTime = end,
                startZoneOffset = startOffset,
                endZoneOffset = endOffset,
                metadata = metadata(ReadType.DISTANCE)
            ),
            if (ReadType.TOTAL_CALORIES !in types) null else TotalCaloriesBurnedRecord(
                energy = Energy.kilocalories(totals.calories * share),
                startTime = start,
                endTime = end,
                startZoneOffset = startOffset,
                endZoneOffset = endOffset,
                metadata = metadata(ReadType.TOTAL_CALORIES)
            )
        )
    }

    // ---- vitals ----------------------------------------------------------

    /**
     * Each of [types]' measurements between two instants, summarised - see
     * [VitalSummary]. Heart rate comes from Health Connect's aggregate, since
     * a workout can carry a sample a second; the rest are read record by
     * record, newest first, up to [MAX_VITAL_PAGES] pages each. A type whose
     * read permission is not granted is left out and listed in `notGranted`.
     * Throws when Health Connect cannot be read.
     */
    suspend fun readVitals(start: Instant, end: Instant, types: Set<VitalType>): Map<String, Any?> {
        val hc = client() ?: throw IllegalStateException("Health Connect is not available")
        val granted = grantedPermissions()
        val (readable, refused) = types.partition { it.permission in granted }
        val filter = TimeRangeFilter.between(start, end)
        return mapOf(
            "vitals" to readable.map { readVital(hc, it, filter, start, end).toMap() },
            "notGranted" to refused.map { it.jsValue }
        )
    }

    private suspend fun readVital(
        hc: HealthConnectClient,
        type: VitalType,
        filter: TimeRangeFilter,
        start: Instant,
        end: Instant
    ): VitalSummary {
        val stats = VitalStats()
        fun origin(record: Record) = record.metadata.dataOrigin.packageName
        return when (type) {
            VitalType.HEART_RATE -> {
                val result = metered(Category.READ) {
                    hc.aggregate(
                        AggregateRequest(
                            metrics = setOf(
                                HeartRateRecord.BPM_MIN,
                                HeartRateRecord.BPM_MAX,
                                HeartRateRecord.BPM_AVG,
                                HeartRateRecord.MEASUREMENTS_COUNT
                            ),
                            timeRangeFilter = filter
                        )
                    )
                }
                stats.setAggregate(
                    count = result[HeartRateRecord.MEASUREMENTS_COUNT] ?: 0L,
                    min = result[HeartRateRecord.BPM_MIN]?.toDouble(),
                    max = result[HeartRateRecord.BPM_MAX]?.toDouble(),
                    avg = result[HeartRateRecord.BPM_AVG]?.toDouble()
                )
                // The newest record holds the latest sample: one call, one record.
                if (stats.count > 0) {
                    val newest = metered(Category.READ) {
                        hc.readRecords(
                            ReadRecordsRequest(
                                recordType = HeartRateRecord::class,
                                timeRangeFilter = filter,
                                ascendingOrder = false,
                                pageSize = 1
                            )
                        )
                    }
                    newest.records.firstOrNull()?.let { record ->
                        record.samples
                            .filter { !it.time.isBefore(start) && it.time.isBefore(end) }
                            .maxByOrNull { it.time }
                            ?.let { stats.offerLatest(it.time.toEpochMilli(), it.beatsPerMinute.toDouble(), origin(record)) }
                    }
                }
                VitalSummary(type, stats)
            }
            VitalType.BLOOD_PRESSURE -> {
                val diastolic = VitalStats()
                val truncated = readNewestFirst(hc, BloodPressureRecord::class, filter) { record ->
                    val at = record.time.toEpochMilli()
                    stats.add(at, record.systolic.inMillimetersOfMercury, origin(record))
                    diastolic.add(at, record.diastolic.inMillimetersOfMercury, origin(record))
                }
                VitalSummary(type, stats, diastolic, truncated)
            }
            VitalType.RESTING_HEART_RATE -> VitalSummary(
                type, stats, truncated = readNewestFirst(hc, RestingHeartRateRecord::class, filter) {
                    stats.add(it.time.toEpochMilli(), it.beatsPerMinute.toDouble(), origin(it))
                }
            )
            VitalType.OXYGEN_SATURATION -> VitalSummary(
                type, stats, truncated = readNewestFirst(hc, OxygenSaturationRecord::class, filter) {
                    stats.add(it.time.toEpochMilli(), it.percentage.value, origin(it))
                }
            )
            VitalType.RESPIRATORY_RATE -> VitalSummary(
                type, stats, truncated = readNewestFirst(hc, RespiratoryRateRecord::class, filter) {
                    stats.add(it.time.toEpochMilli(), it.rate, origin(it))
                }
            )
            VitalType.BODY_TEMPERATURE -> VitalSummary(
                type, stats, truncated = readNewestFirst(hc, BodyTemperatureRecord::class, filter) {
                    stats.add(it.time.toEpochMilli(), it.temperature.inCelsius, origin(it))
                }
            )
            VitalType.BLOOD_GLUCOSE -> VitalSummary(
                type, stats, truncated = readNewestFirst(hc, BloodGlucoseRecord::class, filter) {
                    stats.add(it.time.toEpochMilli(), it.level.inMillimolesPerLiter, origin(it))
                }
            )
        }
    }

    /**
     * Pages through [type] newest first, [MAX_VITAL_PAGES] pages at most.
     * Returns whether records were left unread - the figures then cover the
     * newest ones. Throws when a page fails, as the vitals read has nothing
     * to fall back on.
     */
    private suspend fun <T : Record> readNewestFirst(
        hc: HealthConnectClient,
        type: KClass<T>,
        filter: TimeRangeFilter,
        onRecord: (T) -> Unit
    ): Boolean {
        var token: String? = null
        var pages = 0
        do {
            val response = metered(Category.READ) {
                hc.readRecords(
                    ReadRecordsRequest(
                        recordType = type,
                        timeRangeFilter = filter,
                        ascendingOrder = false,
                        pageSize = MAX_PAGE_SIZE,
                        pageToken = token
                    )
                )
            }
            response.records.forEach(onRecord)
            token = response.pageToken
            pages++
        } while (hasMorePages(token) && pages < MAX_VITAL_PAGES)
        return hasMorePages(token)
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

        /** The oldest Android the Health Connect APK installs on: 9. */
        private const val MIN_PROVIDER_SDK = 28

        /**
         * Records per page: the most Health Connect returns in one, where the
         * default is 1,000. A busy day - a watch and Health Connect's own
         * count each writing a record a minute - is one call instead of three.
         */
        const val MAX_PAGE_SIZE = 5_000

        /**
         * Pages a per-source read takes before stopping: 120,000 records, so
         * [RAW_READ_MAX_DAYS] of two sources writing one record a minute
         * each - about 100,800 - is read whole.
         */
        private const val MAX_PAGES = 24

        /** Longest window answered from raw records; longer ones aggregate. */
        const val RAW_READ_MAX_DAYS = 35L

        /** How long a granted-permissions answer is reused. */
        private const val GRANT_CACHE_MS = 5_000L

        /** A record last modified this long after it ended counts as written late. */
        const val LATE_WRITE_MS = 24L * 60 * 60 * 1000

        /** Pages [readStepRecords] reads before reporting `truncated`: 10,000 records. */
        private const val MAX_RAW_PAGES = 2

        /** Health Connect's limit on records in one insert. */
        const val MAX_RECORDS_PER_INSERT = 1_000

        /** Client record ids per delete call - a day of minutes in two, well inside one binder transaction. */
        private const val MAX_IDS_PER_DELETE = 1_000

        private const val MINUTE_MS = 60_000L

        /**
         * The start, epoch ms, of every minute of [date] in the current zone:
         * 1,440, or an hour more or fewer across a clock change.
         */
        fun minutesOf(date: String): List<Long> {
            val first = Math.floorDiv(DateKeys.startOfDayMillis(date), MINUTE_MS)
            val end = Math.floorDiv(DateKeys.endOfDayMillis(date) + MINUTE_MS, MINUTE_MS)
            return (first until end).map { it * MINUTE_MS }
        }

        /** Pages of changes one look at the feed reads before it re-reads instead: 20,000 changes. */
        private const val MAX_FEED_PAGES = 20

        /** Pages a vitals read takes per type: 20,000 measurements, newest first. */
        private const val MAX_VITAL_PAGES = 4

        /**
         * How long the platform is given to name this phone's data source -
         * short, as the first read of the process waits for it inside its own
         * timeout.
         */
        private const val DEVICE_ORIGIN_TIMEOUT_MS = 1_000L

        /**
         * Every date a window touches, from its start to the last instant
         * before its end - the end itself is excluded, as by Health Connect's
         * time filter, so a window ending at midnight does not touch the day
         * after it.
         */
        fun datesOf(start: Instant, end: Instant): List<String> =
            if (!end.isAfter(start)) {
                emptyList()
            } else {
                DateKeys.rangeOf(DateKeys.of(start.toEpochMilli()), DateKeys.of(end.toEpochMilli() - 1))
            }

        /** Separate reads a range is split into before it is read as one span instead - see [runsOf]. */
        const val MAX_RANGE_RUNS = 4

        /**
         * Days split into runs of consecutive ones, each at most [maxDays]
         * long - one read apiece. Past [maxRuns] runs, the span from the
         * first day to the last is read instead, in [maxDays] pieces:
         * re-reading a few cached days in between costs less than a read per
         * scattered day.
         */
        fun runsOf(days: Collection<String>, maxDays: Int, maxRuns: Int = MAX_RANGE_RUNS): List<List<String>> {
            if (days.isEmpty()) return emptyList()
            val runs = ArrayList<MutableList<String>>()
            for (day in days.toSortedSet()) {
                val current = runs.lastOrNull()
                if (current != null && current.size < maxDays &&
                    DateKeys.parse(current.last()).plusDays(1) == DateKeys.parse(day)
                ) {
                    current += day
                } else {
                    runs += mutableListOf(day)
                }
            }
            if (runs.size <= maxRuns) return runs
            return DateKeys.rangeOf(runs.first().first(), runs.last().last()).chunked(maxDays)
        }

        /**
         * Whether a page token says more pages follow. Android 12 and 13 - and
         * some IPC boundaries - send the last page's token back empty rather
         * than null, so empty means done too.
         */
        fun hasMorePages(pageToken: String?): Boolean = !pageToken.isNullOrEmpty()

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

        /**
         * The JS name of a `Device.type`. The extended types newer Health
         * Connect releases add - from 9 - are named by value: the client
         * this package compiles against does not have their constants yet,
         * and a provider that has them hands them back all the same.
         */
        fun deviceTypeName(type: Int): String = when (type) {
            Device.TYPE_WATCH -> "watch"
            Device.TYPE_PHONE -> "phone"
            Device.TYPE_SCALE -> "scale"
            Device.TYPE_RING -> "ring"
            Device.TYPE_HEAD_MOUNTED -> "head_mounted"
            Device.TYPE_FITNESS_BAND -> "fitness_band"
            Device.TYPE_CHEST_STRAP -> "chest_strap"
            Device.TYPE_SMART_DISPLAY -> "smart_display"
            DEVICE_TYPE_CONSUMER_MEDICAL_DEVICE -> "consumer_medical_device"
            DEVICE_TYPE_GLASSES -> "glasses"
            DEVICE_TYPE_HEARABLE -> "hearable"
            DEVICE_TYPE_FITNESS_MACHINE -> "fitness_machine"
            DEVICE_TYPE_FITNESS_EQUIPMENT -> "fitness_equipment"
            DEVICE_TYPE_PORTABLE_COMPUTER -> "portable_computer"
            DEVICE_TYPE_METER -> "meter"
            else -> "unknown"
        }

        // Health Connect's extended device types, by value - see deviceTypeName.
        private const val DEVICE_TYPE_CONSUMER_MEDICAL_DEVICE = 9
        private const val DEVICE_TYPE_GLASSES = 10
        private const val DEVICE_TYPE_HEARABLE = 11
        private const val DEVICE_TYPE_FITNESS_MACHINE = 12
        private const val DEVICE_TYPE_FITNESS_EQUIPMENT = 13
        private const val DEVICE_TYPE_PORTABLE_COMPUTER = 14
        private const val DEVICE_TYPE_METER = 15

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
         * Whether Health Connect takes a process of this importance to be in
         * the foreground, where reads need no background grant. It asks
         * AppOps whether the app's uid is in the foreground, which holds up to
         * a foreground service: an activity on screen, or a foreground
         * service running - the tracking service is one. A process that is
         * only visible, perceptible or cached is in the background. Treating
         * the service as background, as 2.3.7 did, skipped reads Health
         * Connect would have answered.
         */
        fun inForegroundForReads(importance: Int): Boolean =
            importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE

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
            addAll(VitalType.permissions(VitalType.entries.toSet()))
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
