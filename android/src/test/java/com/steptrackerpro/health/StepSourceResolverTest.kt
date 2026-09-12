package com.steptrackerpro.health

import com.steptrackerpro.core.DayTotals
import com.steptrackerpro.core.MetricsCalculator
import com.steptrackerpro.core.StepTrackerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule under test is that origins are never summed. Everything else here
 * is about which single origin gets picked.
 */
class StepSourceResolverTest {

    private val metrics = MetricsCalculator(StepTrackerConfig().sanitised())
    private val date = "2026-09-09"

    private fun device(steps: Int) =
        DayTotals(date, steps, metrics.distance(steps), metrics.calories(steps))

    private fun source(
        pkg: String,
        steps: Int,
        kind: StepSourceKind,
        distance: Double = 0.0,
        calories: Double = 0.0,
        isSelf: Boolean = false,
        stepsBeforeCoverage: Int = -1
    ) = StepSource(pkg, pkg, kind, steps, distance, calories, 0L, isSelf, stepsBeforeCoverage)

    private fun resolve(
        policy: StepSourcePolicy,
        device: DayTotals,
        sources: List<StepSource>,
        preferred: String? = null
    ) = StepSourceResolver.resolve(policy, device, sources, preferred, metrics)

    @Test
    fun `never sums the phone and a watch covering the same day`() {
        val watch = source("com.fitbit.FitbitMobile", 8_000, StepSourceKind.WATCH)
        val result = resolve(StepSourcePolicy.AUTO, device(7_800), listOf(watch))

        assertEquals(8_000, result.totals.steps)
        assertTrue(result.totals.steps < 7_800 + 8_000)
        assertTrue(result.usedExternal)
    }

    @Test
    fun `device policy ignores Health Connect entirely`() {
        val watch = source("com.fitbit.FitbitMobile", 20_000, StepSourceKind.WATCH)
        val result = resolve(StepSourcePolicy.DEVICE, device(500), listOf(watch))

        assertEquals(500, result.totals.steps)
        assertFalse(result.usedExternal)
        assertEquals(StepSourceKind.SELF, result.kind)
    }

    @Test
    fun `auto keeps the phone when it counted more`() {
        val watch = source("com.fitbit.FitbitMobile", 3_000, StepSourceKind.WATCH)
        val result = resolve(StepSourcePolicy.AUTO, device(9_000), listOf(watch))

        assertEquals(9_000, result.totals.steps)
        assertFalse(result.usedExternal)
        // The alternative is still reported, so a UI can offer the switch.
        assertEquals(3_000, result.externalSteps)
    }

    @Test
    fun `wearable policy prefers the watch even when it counted less`() {
        val watch = source("com.fitbit.FitbitMobile", 3_000, StepSourceKind.WATCH)
        val result = resolve(StepSourcePolicy.WEARABLE, device(9_000), listOf(watch))

        assertEquals(3_000, result.totals.steps)
        assertTrue(result.usedExternal)
        assertEquals(9_000, result.deviceSteps)
    }

    @Test
    fun `wearable policy falls back to the phone when only phone apps published`() {
        val other = source("cc.pacer.androidapp", 12_000, StepSourceKind.PHONE)
        val result = resolve(StepSourcePolicy.WEARABLE, device(9_000), listOf(other))

        assertEquals(9_000, result.totals.steps)
        assertFalse(result.usedExternal)
    }

    @Test
    fun `health_connect policy takes the best external source of any kind`() {
        val other = source("cc.pacer.androidapp", 12_000, StepSourceKind.PHONE)
        val result = resolve(StepSourcePolicy.HEALTH_CONNECT, device(9_000), listOf(other))

        assertEquals(12_000, result.totals.steps)
        assertTrue(result.usedExternal)
    }

    @Test
    fun `our own mirror is never treated as an external source`() {
        // Without the isSelf filter this is what a stale write of our own looks
        // like: it would beat the live count and freeze the day's total.
        val mirror = source("com.example.app", 4_000, StepSourceKind.PHONE, isSelf = true)
        val result = resolve(StepSourcePolicy.AUTO, device(6_000), listOf(mirror))

        assertEquals(6_000, result.totals.steps)
        assertFalse(result.usedExternal)
    }

    @Test
    fun `a pinned package wins over a higher-counting one`() {
        val garmin = source("com.garmin.android.apps.connectmobile", 4_000, StepSourceKind.WATCH)
        val fitbit = source("com.fitbit.FitbitMobile", 11_000, StepSourceKind.WATCH)
        val result = resolve(
            StepSourcePolicy.AUTO,
            device(1_000),
            listOf(fitbit, garmin),
            preferred = "com.garmin.android.apps.connectmobile"
        )

        assertEquals(4_000, result.totals.steps)
        assertEquals("com.garmin.android.apps.connectmobile", result.sourcePackage)
    }

    @Test
    fun `a pin naming a source with no data falls back rather than reporting zero`() {
        val fitbit = source("com.fitbit.FitbitMobile", 11_000, StepSourceKind.WATCH)
        val result = resolve(
            StepSourcePolicy.AUTO,
            device(1_000),
            listOf(fitbit),
            preferred = "com.garmin.android.apps.connectmobile"
        )

        assertEquals(1_000, result.totals.steps)
        assertFalse(result.usedExternal)
    }

    @Test
    fun `distance is derived when the watch published steps but no distance`() {
        val watch = source("com.fitbit.FitbitMobile", 10_000, StepSourceKind.WATCH)
        val result = resolve(StepSourcePolicy.WEARABLE, device(0), listOf(watch))

        assertEquals(metrics.distance(10_000), result.totals.distance, 0.001)
        assertEquals(metrics.calories(10_000), result.totals.calories, 0.001)
    }

    @Test
    fun `a watch's own distance is kept over the derived estimate`() {
        val watch = source(
            "com.fitbit.FitbitMobile", 10_000, StepSourceKind.WATCH,
            distance = 7_500.0, calories = 410.0
        )
        val result = resolve(StepSourcePolicy.WEARABLE, device(0), listOf(watch))

        assertEquals(7_500.0, result.totals.distance, 0.001)
        assertEquals(410.0, result.totals.calories, 0.001)
    }

    @Test
    fun `no Health Connect data leaves the phone's numbers untouched`() {
        val result = resolve(StepSourcePolicy.AUTO, device(5_432), emptyList())

        assertEquals(5_432, result.totals.steps)
        assertFalse(result.usedExternal)
        assertEquals(0, result.externalSteps)
    }

    @Test
    fun `ties go to the phone because its count is live`() {
        val watch = source("com.fitbit.FitbitMobile", 5_000, StepSourceKind.WATCH)
        val result = resolve(StepSourcePolicy.AUTO, device(5_000), listOf(watch))

        assertFalse(result.usedExternal)
    }

    @Test
    fun `an unknown policy string resolves to auto`() {
        assertEquals(StepSourcePolicy.AUTO, StepSourcePolicy.from("nonsense"))
        assertEquals(StepSourcePolicy.AUTO, StepSourcePolicy.from(null))
        assertEquals(StepSourcePolicy.DEVICE, StepSourcePolicy.from("device"))
    }

    @Test
    fun `health connect's own on-device count is classified as this phone, never a wearable`() {
        val self = "com.example.app"
        assertEquals(
            StepSourceKind.PHONE,
            StepSourceCatalog.classify("android", null, self)
        )
        // Post-June-2026 synthetic package name; the hash is per device and
        // per app, so only the prefix can be matched.
        assertEquals(
            StepSourceKind.PHONE,
            StepSourceCatalog.classify(
                "com.android.healthconnect.phone.jd5bdd37e1a8d3667a05d0abebfc4a89e", null, self
            )
        )
        // Even a record stamped TYPE_WATCH does not make the platform a watch.
        assertEquals(
            StepSourceKind.PHONE,
            StepSourceCatalog.classify(
                "com.android.healthconnect.phone.abc",
                androidx.health.connect.client.records.metadata.Device.TYPE_WATCH,
                self
            )
        )
        assertEquals("This phone (Android)", StepSourceCatalog.appName("android"))

        // Under the wearable policy it is therefore ignored, and under auto it
        // is bound by the coverage rule like any phone-side app.
        val platform = source("android", 6_000, StepSourceCatalog.classify("android", null, self))
        assertFalse(resolve(StepSourcePolicy.WEARABLE, device(500), listOf(platform)).usedExternal)
        assertTrue(platform.isPlatform)
    }

    // ---- the coverage rule for phone-side sources under auto ----------------

    @Test
    fun `an app installed at 15_00 takes the phone-side steps from before it, then counts on`() {
        // Samsung Health has 6,000 for today, 5,990 of them before this app's
        // first reading; this app has counted 10 since.
        val samsung = source(
            "com.sec.android.app.shealth", 6_000, StepSourceKind.APP, stepsBeforeCoverage = 5_990
        )
        val result = resolve(StepSourcePolicy.AUTO, device(10), listOf(samsung))

        assertEquals(6_000, result.totals.steps)
        assertTrue(result.usedExternal)
        assertTrue(result.merged)
        assertEquals(5_990, result.baselineSteps)
        assertEquals("com.sec.android.app.shealth", result.sourcePackage)
    }

    @Test
    fun `a phone-side app cannot pull a fully covered day upward`() {
        // An aggregator that summed the platform's count and ours reports
        // double. This device covered the whole day, so it may add nothing.
        val aggregator = source(
            "com.example.aggregator", 16_000, StepSourceKind.APP, stepsBeforeCoverage = 0
        )
        val result = resolve(StepSourcePolicy.AUTO, device(8_000), listOf(aggregator))

        assertEquals(8_000, result.totals.steps)
        assertFalse(result.usedExternal)
        // Still reported, so a UI can show the discrepancy.
        assertEquals(16_000, result.externalSteps)
    }

    @Test
    fun `a phone-side algorithm that counts five percent high does not creep the total`() {
        val samsung = source(
            "com.sec.android.app.shealth", 8_400, StepSourceKind.APP, stepsBeforeCoverage = 0
        )
        assertEquals(8_000, resolve(StepSourcePolicy.AUTO, device(8_000), listOf(samsung)).totals.steps)
    }

    @Test
    fun `a watch may be ahead at any time of day`() {
        val watch = source("com.fitbit.FitbitMobile", 9_000, StepSourceKind.WATCH, stepsBeforeCoverage = 0)
        val result = resolve(StepSourcePolicy.AUTO, device(8_000), listOf(watch))

        assertEquals(9_000, result.totals.steps)
        assertTrue(result.usedExternal)
        assertFalse(result.merged)
    }

    @Test
    fun `a pinned phone-side source is trusted like a watch`() {
        val samsung = source(
            "com.sec.android.app.shealth", 8_400, StepSourceKind.APP, stepsBeforeCoverage = 0
        )
        val result = resolve(
            StepSourcePolicy.AUTO, device(8_000), listOf(samsung),
            preferred = "com.sec.android.app.shealth"
        )
        assertEquals(8_400, result.totals.steps)
        assertTrue(result.usedExternal)
    }

    @Test
    fun `on a past day a phone-side source only fills a day this device has nothing for`() {
        // Coverage is unknown for past days (stepsBeforeCoverage = -1).
        val platform = source("android", 7_000, StepSourceKind.PHONE)
        assertEquals(7_000, resolve(StepSourcePolicy.AUTO, device(0), listOf(platform)).totals.steps)
        assertEquals(6_500, resolve(StepSourcePolicy.AUTO, device(6_500), listOf(platform)).totals.steps)
    }

    @Test
    fun `wearable and health_connect policies are exact and ignore coverage`() {
        val samsung = source(
            "com.sec.android.app.shealth", 4_000, StepSourceKind.APP, stepsBeforeCoverage = 0
        )
        val result = resolve(StepSourcePolicy.HEALTH_CONNECT, device(8_000), listOf(samsung))
        assertEquals(4_000, result.totals.steps)
        assertTrue(result.usedExternal)
        assertFalse(result.merged)
    }
}
