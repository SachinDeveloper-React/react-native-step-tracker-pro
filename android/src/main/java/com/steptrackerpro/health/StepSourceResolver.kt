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
        val baselineSteps: Int = 0
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
            "baselineSteps" to baselineSteps
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
        metrics: MetricsCalculator
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

        val external = sources.filterNot { it.isSelf }
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
        // AUTO is the only policy that lets the phone win over a present
        // external source, and it does so purely on which one counted more.
        // Ties go to the phone because its total is live rather than whatever
        // the companion app last uploaded.
        val useExternal = when (policy) {
            StepSourcePolicy.DEVICE -> false
            StepSourcePolicy.AUTO -> externalSteps > device.steps
            StepSourcePolicy.WEARABLE, StepSourcePolicy.HEALTH_CONNECT -> true
        }
        if (!useExternal) {
            return deviceResolution.copy(externalSteps = externalSteps)
        }

        return Resolution(
            totals = DayTotals(
                date = device.date,
                steps = externalSteps,
                // A watch that publishes StepsRecord but no DistanceRecord is
                // normal. Deriving from stride keeps distance consistent with
                // the step count actually being shown rather than reporting 0.
                distance = candidate.distance.takeIf { it > 0.0 }
                    ?: metrics.distance(externalSteps),
                calories = candidate.calories.takeIf { it > 0.0 }
                    ?: metrics.calories(externalSteps),
                synced = device.synced,
                syncedRemote = device.syncedRemote
            ),
            kind = candidate.kind,
            sourcePackage = candidate.packageName,
            sourceName = candidate.appName,
            deviceSteps = device.steps,
            externalSteps = externalSteps,
            usedExternal = true
        )
    }
}
