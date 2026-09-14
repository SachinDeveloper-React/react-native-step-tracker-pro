package com.steptrackerpro.health

import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata

/**
 * What produced a set of Health Connect step records.
 *
 * The reliable signal is [Device.type] on the record metadata, which the
 * writing app fills in - a Wear OS watch writing through Health Services stamps
 * `TYPE_WATCH`. It is optional, though, and plenty of companion apps
 * (Fitbit, Garmin, Zepp) upload watch data from the phone without stamping a
 * device at all. [StepSourceCatalog] covers that case by package name.
 */
enum class StepSourceKind(val jsValue: String) {
    /** Records this package wrote itself. Never treated as an external source. */
    SELF("self"),
    WATCH("watch"),
    FITNESS_BAND("fitness_band"),
    RING("ring"),
    CHEST_STRAP("chest_strap"),
    /** Another app counting on this phone's own sensors. */
    PHONE("phone"),
    /** A platform app whose data may be phone- or wearable-sourced. */
    APP("app"),
    UNKNOWN("unknown");

    /**
     * Whether steps from this kind were counted by something worn on the body.
     * These win over the phone's own sensor under the `wearable` and `auto`
     * policies, because a phone left on a desk counts nothing while a watch
     * counts everything.
     */
    val isWearable: Boolean
        get() = this == WATCH || this == FITNESS_BAND || this == RING || this == CHEST_STRAP

    companion object {
        fun fromDeviceType(type: Int?): StepSourceKind = when (type) {
            Device.TYPE_WATCH -> WATCH
            Device.TYPE_FITNESS_BAND -> FITNESS_BAND
            Device.TYPE_RING -> RING
            Device.TYPE_CHEST_STRAP -> CHEST_STRAP
            Device.TYPE_PHONE -> PHONE
            else -> UNKNOWN
        }

        fun from(value: String?): StepSourceKind =
            entries.firstOrNull { it.jsValue == value } ?: UNKNOWN
    }
}

/**
 * How the steps behind a set of records were produced, per Health Connect's
 * `Metadata.recordingMethod`: counted by a sensor while the app was open
 * (active), counted by a sensor in the background (automatic), typed in by
 * the user (manual), or unstated (unknown - the value every record written
 * before the field existed carries, and what apps that never set it stamp).
 *
 * The writing app stamps it, so it is a statement rather than proof - but a
 * manual entry is the one honest way for a user to put 20,000 steps into
 * Health Connect from a keyboard, and every mainstream app labels it as such.
 * An app that pays for steps wants that bucket separated out.
 */
data class RecordingMethods(
    val active: Int,
    val automatic: Int,
    val manual: Int,
    val unknown: Int
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "active" to active,
        "automatic" to automatic,
        "manual" to manual,
        "unknown" to unknown
    )

    companion object {
        const val ACTIVE = 0
        const val AUTOMATIC = 1
        const val MANUAL = 2
        const val UNKNOWN = 3

        /** Bucket index for a raw `Metadata.recordingMethod` value. */
        fun bucketOf(method: Int): Int = when (method) {
            Metadata.RECORDING_METHOD_ACTIVELY_RECORDED -> ACTIVE
            Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED -> AUTOMATIC
            Metadata.RECORDING_METHOD_MANUAL_ENTRY -> MANUAL
            else -> UNKNOWN
        }

        fun fromBuckets(buckets: IntArray): RecordingMethods =
            RecordingMethods(buckets[ACTIVE], buckets[AUTOMATIC], buckets[MANUAL], buckets[UNKNOWN])
    }
}

/**
 * One app contributing steps to Health Connect over a time range, with the
 * totals it contributed.
 *
 * `steps` is that origin's own total. Health Connect de-duplicates within a
 * single origin, but not across origins: a phone pedometer app and a watch
 * both covering the same afternoon each report their own full count, so
 * summing origins double-counts. [StepSourceResolver] picks between them
 * instead of adding them.
 */
data class StepSource(
    val packageName: String,
    /** Display name from the catalog, falling back to the package name. */
    val appName: String,
    val kind: StepSourceKind,
    val steps: Int,
    /** Metres. Zero when the origin wrote steps but no DistanceRecord. */
    val distance: Double,
    /** Kilocalories. Zero when the origin wrote no TotalCaloriesBurnedRecord. */
    val calories: Double,
    /** Epoch ms of the most recent record seen from this origin. */
    val lastRecordAt: Long,
    val isSelf: Boolean,
    /**
     * Of [steps], how many were recorded before this device started covering
     * the day (see `StepStateStore.coverageStartAt`). -1 when not computed -
     * past days, or aggregate reads. Under the `auto` policy this is all a
     * phone-side origin is allowed to add: the same phone cannot legitimately
     * count more steps than its own hardware counter for the hours both were
     * watching, so anything beyond the gap is another app's inflation.
     */
    val stepsBeforeCoverage: Int = -1,
    /**
     * Of [steps], how many came from records stamped
     * `RECORDING_METHOD_MANUAL_ENTRY` - typed in by the user rather than
     * counted by anything. -1 when not computed: the aggregate API used for
     * windows over 35 days returns totals with no per-record metadata, so
     * there is nothing to bucket. [steps] always includes them; the
     * `healthConnectIgnoreManualEntries` config decides whether the resolver
     * subtracts them.
     */
    val manualSteps: Int = -1,
    /** Of [steps], how many carried `RECORDING_METHOD_UNKNOWN`. -1 when not computed. */
    val unknownMethodSteps: Int = -1,
    /** The full split of [steps] by recording method. Null when not computed. */
    val recordingMethods: RecordingMethods? = null,
    /**
     * Of [manualSteps], how many fell before this device's coverage began.
     * Kept alongside [stepsBeforeCoverage] so excluding manual entries can
     * take them out of the pre-coverage share as well as the total - a
     * hand-entered morning must not survive as "steps from before install".
     * -1 when either half is not computed. Internal to the resolver.
     */
    val manualStepsBeforeCoverage: Int = -1,
    /**
     * Whether the `auto` policy trusts this source for its whole margin over
     * the phone. Under `wearableTrust: 'metadata'` (the default) it is
     * exactly [isWearable]; under `'catalog'` only a package the catalog or
     * the app's `wearableAllowlist` knows as a wearable qualifies, however
     * its records were stamped. Set by [StepSourceTrust.stamp] on the way
     * to JS so a consumer can see which rule applied; the resolver decides
     * for itself from the same rule rather than reading this back.
     */
    val trustedWearable: Boolean = kind.isWearable
) {
    val isWearable: Boolean get() = kind.isWearable

    /** Health Connect's own on-device count - see [StepSourceCatalog.isPlatformOrigin]. */
    val isPlatform: Boolean get() = StepSourceCatalog.isPlatformOrigin(packageName)

    /**
     * This source with its manual entries taken out, for the resolver: the
     * total and the pre-coverage share both drop by their manual part, and
     * distance and calories are zeroed so they are re-derived from the steps
     * that remain rather than carrying a hand-entered distance along.
     * [manualSteps] itself is kept, so the caller can still report how much
     * was excluded. Unchanged when nothing is known to be manual.
     */
    fun excludingManual(): StepSource {
        if (manualSteps <= 0) return this
        val kept = (steps - manualSteps).coerceAtLeast(0)
        val before = when {
            stepsBeforeCoverage < 0 -> -1
            manualStepsBeforeCoverage >= 0 ->
                (stepsBeforeCoverage - manualStepsBeforeCoverage).coerceIn(0, kept)
            // The split of the manual part around coverage is unknown: take
            // the conservative view that all of it could have been before,
            // so the pre-coverage share can never carry a manual entry.
            else -> (stepsBeforeCoverage - manualSteps).coerceIn(0, kept)
        }
        return copy(
            steps = kept,
            distance = 0.0,
            calories = 0.0,
            stepsBeforeCoverage = before
        )
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "packageName" to packageName,
        "appName" to appName,
        "kind" to kind.jsValue,
        "steps" to steps,
        "distance" to distance,
        "calories" to calories,
        "lastRecordAt" to lastRecordAt,
        "isSelf" to isSelf,
        "isWearable" to isWearable,
        "isPlatform" to isPlatform,
        "manualSteps" to manualSteps,
        "unknownMethodSteps" to unknownMethodSteps,
        "recordingMethods" to recordingMethods?.toMap(),
        "trustedWearable" to trustedWearable
    )
}

/**
 * Package names of the companion apps that ship wearable step data into Health
 * Connect, so a source can be named and classified even when the record
 * carries no [Device].
 *
 * This is a display and heuristic aid, never a gate: an unlisted package still
 * appears as a source, it is just labelled `app` rather than `watch`, and a
 * record that does stamp a device type always overrides the entry here.
 */
object StepSourceCatalog {

    /** Health Connect's own provider, which is never a data origin itself. */
    const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

    /**
     * Health Connect's own on-device step counting (Android 14 with SDK
     * extension 20+, once any app holds READ_STEPS). Recorded from the same
     * TYPE_STEP_COUNTER this package reads, batched about once a minute.
     * Attributed to the `android` package before the June 2026 provider
     * update and to a device- and app-specific synthetic package name of the
     * form `com.android.healthconnect.phone.<hash>` after it. The hash must
     * not be hard-coded, so the prefix is what is matched.
     */
    const val PLATFORM_PACKAGE = "android"
    const val PLATFORM_PACKAGE_PREFIX = "com.android.healthconnect.phone."
    const val PLATFORM_APP_NAME = "This phone (Android)"

    fun isPlatformOrigin(packageName: String): Boolean =
        packageName == PLATFORM_PACKAGE || packageName.startsWith(PLATFORM_PACKAGE_PREFIX)

    private data class Entry(val appName: String, val kind: StepSourceKind)

    private val KNOWN: Map<String, Entry> = mapOf(
        // Wear OS and Google
        "com.google.android.wearable.app" to Entry("Wear OS", StepSourceKind.WATCH),
        "com.google.android.apps.wearable.health" to
            Entry("Wear OS Health", StepSourceKind.WATCH),
        "com.google.android.apps.fitness" to Entry("Google Fit", StepSourceKind.APP),
        "com.google.android.gms" to Entry("Google Play services", StepSourceKind.APP),
        // Samsung
        "com.sec.android.app.shealth" to Entry("Samsung Health", StepSourceKind.APP),
        "com.samsung.android.wear.shealth" to
            Entry("Samsung Health (Watch)", StepSourceKind.WATCH),
        "com.samsung.android.app.watchmanager" to
            Entry("Galaxy Wearable", StepSourceKind.WATCH),
        // Fitbit / Pixel Watch
        "com.fitbit.FitbitMobile" to Entry("Fitbit", StepSourceKind.WATCH),
        // Garmin
        "com.garmin.android.apps.connectmobile" to Entry("Garmin Connect", StepSourceKind.WATCH),
        // Huawei / Honor
        "com.huawei.health" to Entry("Huawei Health", StepSourceKind.WATCH),
        "com.hihonor.health" to Entry("Honor Health", StepSourceKind.WATCH),
        // Xiaomi / Amazfit / Zepp
        "com.mi.health" to Entry("Mi Fitness", StepSourceKind.WATCH),
        "com.xiaomi.wearable" to Entry("Xiaomi Wearable", StepSourceKind.WATCH),
        "com.zepp.life" to Entry("Zepp Life", StepSourceKind.FITNESS_BAND),
        "com.huami.midong" to Entry("Zepp Life", StepSourceKind.FITNESS_BAND),
        "com.huami.watch.hmwatchmanager" to Entry("Amazfit", StepSourceKind.WATCH),
        // Oppo / OnePlus / realme
        "com.heytap.health" to Entry("OPPO Health", StepSourceKind.WATCH),
        "com.oneplus.health.international" to Entry("OnePlus Health", StepSourceKind.WATCH),
        // Other wearables
        "com.ouraring.oura" to Entry("Oura", StepSourceKind.RING),
        "com.whoop.android" to Entry("WHOOP", StepSourceKind.FITNESS_BAND),
        "fi.polar.polarflow" to Entry("Polar Flow", StepSourceKind.WATCH),
        "com.stt.android.suunto" to Entry("Suunto", StepSourceKind.WATCH),
        "com.withings.wiscale2" to Entry("Withings", StepSourceKind.WATCH),
        "com.yf.smart.coros.dist" to Entry("COROS", StepSourceKind.WATCH),
        "com.wahoofitness.bolt" to Entry("Wahoo", StepSourceKind.APP),
        // Phone-side fitness apps
        "com.strava" to Entry("Strava", StepSourceKind.APP),
        "com.myfitnesspal.android" to Entry("MyFitnessPal", StepSourceKind.APP),
        "com.runtastic.android" to Entry("Adidas Running", StepSourceKind.APP),
        "cc.pacer.androidapp" to Entry("Pacer", StepSourceKind.PHONE),
        "com.fitnesskeeper.runkeeper.pro" to Entry("Runkeeper", StepSourceKind.APP)
    )

    /** Companion apps worth probing for with PackageManager. */
    val COMPANION_PACKAGES: List<String> =
        KNOWN.filterValues { it.kind.isWearable }.keys.toList()

    /**
     * Whether the catalog itself knows this package as a wearable's companion
     * app - the `wearableTrust: 'catalog'` rule. Unlike [classify] it ignores
     * whatever `Device` the records were stamped with: any app can stamp
     * `TYPE_WATCH`, and this is the list of ones that have earned it.
     */
    fun isKnownWearable(packageName: String): Boolean = KNOWN[packageName]?.kind?.isWearable == true

    fun appName(packageName: String): String = when {
        isPlatformOrigin(packageName) -> PLATFORM_APP_NAME
        else -> KNOWN[packageName]?.appName ?: packageName
    }

    /**
     * Classifies an origin. `deviceType` comes from the record metadata and is
     * authoritative when present; the catalog only fills the gap left by apps
     * that omit it.
     */
    fun classify(packageName: String, deviceType: Int?, selfPackage: String): StepSourceKind {
        if (packageName == selfPackage) return StepSourceKind.SELF
        // The platform's own count is this phone's hardware counter under
        // another name. Never a wearable, whatever the record stamps.
        if (isPlatformOrigin(packageName)) return StepSourceKind.PHONE
        val fromDevice = StepSourceKind.fromDeviceType(deviceType)
        if (fromDevice != StepSourceKind.UNKNOWN) return fromDevice
        return KNOWN[packageName]?.kind ?: StepSourceKind.UNKNOWN
    }
}
