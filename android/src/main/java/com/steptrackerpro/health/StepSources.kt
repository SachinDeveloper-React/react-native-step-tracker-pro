package com.steptrackerpro.health

import androidx.health.connect.client.records.metadata.Device

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
    val isSelf: Boolean
) {
    val isWearable: Boolean get() = kind.isWearable

    fun toMap(): Map<String, Any?> = mapOf(
        "packageName" to packageName,
        "appName" to appName,
        "kind" to kind.jsValue,
        "steps" to steps,
        "distance" to distance,
        "calories" to calories,
        "lastRecordAt" to lastRecordAt,
        "isSelf" to isSelf,
        "isWearable" to isWearable
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

    fun appName(packageName: String): String =
        KNOWN[packageName]?.appName ?: packageName

    /**
     * Classifies an origin. `deviceType` comes from the record metadata and is
     * authoritative when present; the catalog only fills the gap left by apps
     * that omit it.
     */
    fun classify(packageName: String, deviceType: Int?, selfPackage: String): StepSourceKind {
        if (packageName == selfPackage) return StepSourceKind.SELF
        val fromDevice = StepSourceKind.fromDeviceType(deviceType)
        if (fromDevice != StepSourceKind.UNKNOWN) return fromDevice
        return KNOWN[packageName]?.kind ?: StepSourceKind.UNKNOWN
    }
}
