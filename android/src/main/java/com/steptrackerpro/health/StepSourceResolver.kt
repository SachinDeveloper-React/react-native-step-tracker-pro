package com.steptrackerpro.health

import com.steptrackerpro.core.DayTotals
import com.steptrackerpro.core.MetricsCalculator

/**
 * How to reconcile the phone's own sensor with step data other apps published
 * to Health Connect.
 */
enum class StepSourcePolicy(val jsValue: String) {
    /** Phone sensor only. Health Connect is written to but never read back. */
    DEVICE("device"),

    /**
     * A watch, band or ring wins whenever one has published for the day, even
     * if it reports fewer steps than the phone. For users who treat the
     * wearable as the source of truth and want the two screens to agree.
     */
    WEARABLE("wearable"),

    /**
     * The best external Health Connect origin wins, wearable or not. Use when
     * another app owns step counting and this package is only presenting it.
     */
    HEALTH_CONNECT("health_connect"),

    /**
     * Default. Takes whichever of the phone and the best external source counted
     * more for the day. A phone left on a desk under-counts and a watch worn on
     * a walk does not, so the higher number is the one that saw the whole day.
     */
    AUTO("auto");

    companion object {
        fun from(value: String?): StepSourcePolicy =
            entries.firstOrNull { it.jsValue == value } ?: AUTO
    }
}

/**
 * What earns a source the `auto` policy's full trust - its whole margin over
 * the phone - rather than the coverage-bound treatment of a phone-side app.
 */
enum class WearableTrust(val jsValue: String) {
    /**
     * Default. The `Device.type` the writing app stamped on its records
     * decides, as it always has. Right for display: a Wear OS watch writing
     * through Health Services stamps `TYPE_WATCH` and no catalog can keep up
     * with every band on the market. Wrong as a fraud control: any app can
     * stamp `TYPE_WATCH`.
     */
    METADATA("metadata"),

    /**
     * Only a package the [StepSourceCatalog] knows as a wearable's companion
     * app, or one the app put on its `wearableAllowlist`, is trusted for its
     * whole margin. An unlisted package that stamps a wearable type keeps its
     * `kind` for display and is bound by the coverage rule like a phone-side
     * app: it may fill the part of the day before this device's coverage
     * began, and nothing after. For an app that pays per step.
     */
    CATALOG("catalog");

    companion object {
        fun from(value: String?): WearableTrust =
            entries.firstOrNull { it.jsValue == value } ?: METADATA
    }
}

/**
 * The one place the wearable-trust rule lives, so the resolver's decision and
 * the `trustedWearable` flag a consumer sees on each source cannot disagree.
 */
object StepSourceTrust {

    fun isTrustedWearable(
        source: StepSource,
        trust: WearableTrust,
        allowlist: Set<String>
    ): Boolean = when (trust) {
        WearableTrust.METADATA -> source.isWearable
        WearableTrust.CATALOG ->
            // The platform's own count is never a wearable whatever the
            // allowlist says; classify() already forces its kind to PHONE and
            // isWearable is false for it, so the catalog lookup alone would
            // do - the explicit check keeps that true if the catalog changes.
            !source.isPlatform && !source.isSelf &&
                (StepSourceCatalog.isKnownWearable(source.packageName) ||
                    source.packageName in allowlist)
    }

    /** The same sources with [StepSource.trustedWearable] set under [trust]. */
    fun stamp(
        sources: List<StepSource>,
        trust: WearableTrust,
        allowlist: Set<String>
    ): List<StepSource> = sources.map { source ->
        val trusted = isTrustedWearable(source, trust, allowlist)
        if (source.trustedWearable == trusted) source else source.copy(trustedWearable = trusted)
    }
}

/**
 * Picks the number a day is reported with.
 *
 * The one rule everything here exists to enforce: **origins are never summed.**
 * A user who walks 8,000 steps wearing a watch with the phone in their pocket
 * has 8,000 steps in Health Connect from the watch and about 8,000 from this
 * package's own sensor. Adding those shows 16,000. Every policy below selects
 * exactly one origin per day.
 */
object StepSourceResolver {

    /**
     * @param usedExternal true when the numbers came from Health Connect rather
     *   than this device's sensor. The sync path keys off this: a day owned by
     *   a wearable must not also be written back to Health Connect from here,
     *   or every other app reading Health Connect sees both copies.
     */
    data class Resolution(
        val totals: DayTotals,
        val kind: StepSourceKind,
        val sourcePackage: String?,
        val sourceName: String,
        val deviceSteps: Int,
        val externalSteps: Int,
        val usedExternal: Boolean,
        /**
         * True when `totals.steps` is an external baseline plus this device's
         * live delta on top - the `auto` policy's continuity mode, see
         * [StepContinuity]. `baselineSteps` is how far ahead the external
         * source was when the baseline was taken.
         */
        val merged: Boolean = false,
        val baselineSteps: Int = 0,
        /**
         * Manual-entry steps subtracted from the external source that was
         * evaluated, under `healthConnectIgnoreManualEntries`. Zero when the
         * flag is off. `externalSteps + manualStepsExcluded` is what Health
         * Connect itself shows for that source, which is what a UI needs in
         * order to explain why the number here is lower.
         */
        val manualStepsExcluded: Int = 0,
        /**
         * Of this device's count, how many flagged steps were taken out
         * before it competed, under `fraudDetection.mode: 'exclude'`. Zero
         * otherwise. `deviceSteps` is the count after the exclusion.
         */
        val suspectStepsExcluded: Int = 0,
        /**
         * Where `totals.distance` came from: `health_connect` when it is the
         * winning source's own distance, `derived` when it was estimated
         * from the steps shown - this device's count, a source that wrote
         * no distance or was not read for it, a merged count, or one with
         * manual entries taken out.
         */
        val distanceSource: String = DistanceSource.DERIVED
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "date" to totals.date,
            "steps" to totals.steps,
            "kind" to kind.jsValue,
            "packageName" to sourcePackage,
            "appName" to sourceName,
            "deviceSteps" to deviceSteps,
            "externalSteps" to externalSteps,
            "usedExternal" to usedExternal,
            "merged" to merged,
            "baselineSteps" to baselineSteps,
            "manualStepsExcluded" to manualStepsExcluded,
            "suspectStepsExcluded" to suspectStepsExcluded,
            "distanceSource" to distanceSource
        )
    }

    /**
     * @param device today's (or a past day's) totals from this package.
     * @param sources every Health Connect origin for that day, self included -
     *   entries flagged `isSelf` are dropped here, since counting our own
     *   mirror as an external source would let a stale write beat the live one.
     * @param preferredPackage pins one origin. When it names this package the
     *   phone sensor wins outright.
     */
    fun resolve(
        policy: StepSourcePolicy,
        device: DayTotals,
        sources: List<StepSource>,
        preferredPackage: String?,
        metrics: MetricsCalculator,
        /**
         * Whether this device's count vouches for the whole of the day it
         * covered - true on a hardware counter, false on the detector or the
         * accelerometer, where a dead process loses steps and a phone-side
         * app that kept counting has genuinely seen more.
         */
        deviceCoverageReliable: Boolean = true,
        /**
         * Subtract each external source's manual-entry steps before it
         * competes, so a hand-typed 20,000 can never become the day's number
         * under any policy or pin. The `healthConnectIgnoreManualEntries`
         * config. Sources read through the aggregate API carry no split and
         * are left as they are.
         */
        ignoreManualEntries: Boolean = false,
        /**
         * What earns a source the whole-margin trust under `auto` - the
         * `wearableTrust` config. [WearableTrust.METADATA] is the behaviour
         * every earlier release had.
         */
        wearableTrust: WearableTrust = WearableTrust.METADATA,
        /** Packages trusted as wearables under [WearableTrust.CATALOG] on top of the catalog. */
        wearableAllowlist: Set<String> = emptySet()
    ): Resolution {
        val deviceResolution = Resolution(
            totals = device,
            kind = StepSourceKind.SELF,
            sourcePackage = null,
            sourceName = "This device",
            deviceSteps = device.steps,
            externalSteps = 0,
            usedExternal = false
        )
        if (policy == StepSourcePolicy.DEVICE) return deviceResolution

        // With manual entries excluded every candidate competes on what it
        // counted rather than what was typed into it; the total it drops by
        // is carried back out on the resolution so a UI can say why.
        val external = sources.filterNot { it.isSelf }
            .map { if (ignoreManualEntries) it.excludingManual() else it }
        if (external.isEmpty()) return deviceResolution

        val pinned = preferredPackage?.takeIf { it.isNotEmpty() }
        // A pin naming this package is an explicit "ignore Health Connect for
        // the number", which is not the same as policy DEVICE: reads and
        // permission handling stay on.
        if (pinned != null && external.none { it.packageName == pinned }) {
            if (sources.any { it.isSelf && it.packageName == pinned }) return deviceResolution
        }

        val candidate = when {
            pinned != null -> external.firstOrNull { it.packageName == pinned }
            policy == StepSourcePolicy.WEARABLE ->
                external.filter { it.isWearable }.maxByOrNull { it.steps }
            else -> external.maxByOrNull { it.steps }
        } ?: return deviceResolution.copy(
            externalSteps = external.maxOfOrNull { it.steps } ?: 0
        )

        val externalSteps = candidate.steps
        val excluded = if (ignoreManualEntries) candidate.manualSteps.coerceAtLeast(0) else 0

        // A source with nothing left once its manual entries are out has no
        // count to offer under any policy - even `wearable`, which promises
        // the watch's number whenever it "has data": a typed-in number is
        // not data the watch produced. The phone answers, and the exclusion
        // is still reported so the discrepancy with Health Connect's own
        // screen can be explained. Only reached when something was actually
        // taken out, so a source that published a zero-count record behaves
        // exactly as it did before the flag existed.
        if (externalSteps <= 0 && excluded > 0) {
            return deviceResolution.copy(externalSteps = 0, manualStepsExcluded = excluded)
        }

        if (policy == StepSourcePolicy.WEARABLE || policy == StepSourcePolicy.HEALTH_CONNECT) {
            // The exact external number, as promised, lower or higher.
            return external(
                device, candidate, externalSteps, metrics, merged = false, lead = 0,
                excluded = excluded
            )
        }

        // AUTO. How far ahead the candidate is allowed to be depends on what
        // it is. A wearable, or a source the user pinned, is trusted outright:
        // a watch sees a walk the phone on the desk did not. A phone-side
        // origin - Samsung Health, the platform's own count, an aggregator -
        // reads the same phone, so for the hours this device was counting it
        // cannot legitimately have seen more; all it may add is the part of
        // the day before this device's coverage began (an install at 15:00),
        // and on a past day, the whole day only if this device has nothing
        // for it at all. Without that rule an aggregator that sums the
        // platform's count and ours would double the display, and a
        // phone-side algorithm that counts 5% high would creep the total up
        // sync after sync. What makes a candidate "a wearable" here is the
        // wearableTrust rule: the record's own Device stamp by default, the
        // catalog and the app's allowlist under `catalog`.
        val trusted = pinned != null ||
            StepSourceTrust.isTrustedWearable(candidate, wearableTrust, wearableAllowlist) ||
            !deviceCoverageReliable
        val lead = when {
            trusted -> externalSteps - device.steps
            candidate.stepsBeforeCoverage >= 0 -> candidate.stepsBeforeCoverage
            device.steps == 0 -> externalSteps
            else -> 0
        }
        // Ties go to the phone because its total is live rather than whatever
        // the companion app last uploaded.
        if (lead <= 0) {
            return deviceResolution.copy(
                externalSteps = externalSteps, manualStepsExcluded = excluded
            )
        }

        return if (trusted) {
            external(
                device, candidate, externalSteps, metrics, merged = false, lead = lead,
                excluded = excluded
            )
        } else {
            external(
                device, candidate, device.steps + lead, metrics, merged = true, lead = lead,
                excluded = excluded
            )
        }
    }

    private fun external(
        device: DayTotals,
        candidate: StepSource,
        steps: Int,
        metrics: MetricsCalculator,
        merged: Boolean,
        lead: Int,
        excluded: Int
    ): Resolution = Resolution(
        totals = DayTotals(
            date = device.date,
            steps = steps,
            // A watch that publishes StepsRecord but no DistanceRecord is
            // normal. Deriving from stride keeps distance consistent with
            // the step count actually being shown rather than reporting 0.
            // A merged count is a blend, so it is always derived.
            distance = candidate.distance.takeIf { it > 0.0 && !merged }
                ?: metrics.distance(steps),
            calories = candidate.calories.takeIf { it > 0.0 && !merged }
                ?: metrics.calories(steps),
            synced = device.synced,
            syncedRemote = device.syncedRemote,
            // This device's own figure, whatever answered for the number.
            recoveredSteps = device.recoveredSteps
        ),
        kind = candidate.kind,
        sourcePackage = candidate.packageName,
        sourceName = candidate.appName,
        deviceSteps = device.steps,
        externalSteps = candidate.steps,
        usedExternal = true,
        merged = merged,
        baselineSteps = lead.coerceAtLeast(0),
        manualStepsExcluded = excluded,
        distanceSource = if (candidate.distance > 0.0 && !merged) {
            DistanceSource.HEALTH_CONNECT
        } else {
            DistanceSource.DERIVED
        }
    )
}
