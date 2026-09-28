package com.steptrackerpro.health

import com.steptrackerpro.core.DayTotals
import com.steptrackerpro.core.MetricsCalculator
import com.steptrackerpro.core.StepTrackerConfig
import com.steptrackerpro.health.HealthConnectManager.ReadType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Health Connect sheet lets the user untick any single permission. Steps
 * alone must be enough to read a watch and to mirror this phone; distance
 * and calories are used when granted and derived when not.
 */
class PartialGrantsTest {

    private val readSteps = ReadType.STEPS.permission
    private val readDistance = ReadType.DISTANCE.permission
    private val readCalories = ReadType.TOTAL_CALORIES.permission
    private val writeSteps = ReadType.STEPS.writePermission
    private val writeDistance = ReadType.DISTANCE.writePermission

    private fun access(vararg granted: String, configured: Set<ReadType> = ReadType.ALL) =
        HealthConnectManager.access(granted.toSet(), configured)

    @Test
    fun `steps granted and distance refused still reads, with distance left out`() {
        val access = access(readSteps, readCalories)
        assertTrue(access.readSteps)
        assertEquals(setOf(ReadType.STEPS, ReadType.TOTAL_CALORIES), access.readTypes)
    }

    @Test
    fun `steps and distance granted reads both, as before`() {
        val access = access(readSteps, readDistance, readCalories)
        assertTrue(access.readSteps)
        assertEquals(ReadType.ALL, access.readTypes)
    }

    @Test
    fun `distance without steps reads nothing`() {
        val access = access(readDistance)
        assertFalse(access.readSteps)
        // Reported as granted, so a UI can say which one is missing.
        assertEquals(setOf(ReadType.DISTANCE), access.readTypes)
    }

    @Test
    fun `nothing granted reads nothing`() {
        val access = access()
        assertFalse(access.readSteps)
        assertTrue(access.readTypes.isEmpty())
        assertFalse(access.writeSteps)
    }

    @Test
    fun `granted types are limited to the configured ones`() {
        val access = access(readSteps, readDistance, readCalories, configured = setOf(ReadType.STEPS))
        assertEquals(setOf(ReadType.STEPS), access.readTypes)
    }

    @Test
    fun `writing needs steps alone and carries whatever else is granted`() {
        val stepsOnly = access(writeSteps)
        assertTrue(stepsOnly.writeSteps)
        assertEquals(setOf(ReadType.STEPS), stepsOnly.writeTypes)

        val withDistance = access(writeSteps, writeDistance)
        assertEquals(setOf(ReadType.STEPS, ReadType.DISTANCE), withDistance.writeTypes)

        assertFalse(access(writeDistance).writeSteps)
        // Read grants say nothing about writing.
        assertFalse(access(readSteps).writeSteps)
    }

    @Test
    fun `a source's distance says where it came from`() {
        assertEquals(DistanceSource.NOT_READ, DistanceSource.of(read = false, seen = false, isSelf = false))
        assertEquals(DistanceSource.NONE, DistanceSource.of(read = true, seen = false, isSelf = false))
        assertEquals(DistanceSource.HEALTH_CONNECT, DistanceSource.of(read = true, seen = true, isSelf = false))
        // This app's own records hold its stride estimate.
        assertEquals(DistanceSource.DERIVED, DistanceSource.of(read = true, seen = true, isSelf = true))
        assertEquals(DistanceSource.NOT_READ, DistanceSource.of(read = false, seen = false, isSelf = true))
    }

    // ---- what counts as a refusal ---------------------------------------

    private val scope = HealthConnectManager.PermissionScope()

    @Test
    fun `steps allowed with distance unticked is not a refusal`() {
        assertTrue(scope.stepsGranted(setOf(readSteps, writeSteps)))
        // ...even though config is not fully granted.
        assertFalse((setOf(readSteps, writeSteps)).containsAll(scope.required))
    }

    @Test
    fun `steps refused is a refusal whatever else was allowed`() {
        assertFalse(scope.stepsGranted(setOf(readDistance, readCalories, writeSteps)))
        assertFalse(scope.stepsGranted(emptySet()))
    }

    @Test
    fun `steps are judged only for what config turns on`() {
        val readOnly = HealthConnectManager.PermissionScope(write = false)
        assertEquals(setOf(readSteps), readOnly.essential)
        assertTrue(readOnly.stepsGranted(setOf(readSteps)))
        val neither = HealthConnectManager.PermissionScope(read = false, write = false)
        assertFalse(neither.stepsGranted(setOf(readSteps, writeSteps)))
    }

    // ---- fitting the request to the manifest ----------------------------

    @Test
    fun `an app that declares no WRITE_STEPS is not asked to write`() {
        val fitted = scope.forManifest(setOf(readSteps, readDistance, readCalories))
        assertFalse(fitted.write)
        assertEquals(setOf(readSteps, readDistance, readCalories), fitted.requested)
        assertEquals(HealthConnectManager.WRITE_PERMISSIONS, fitted.dropped)
    }

    @Test
    fun `undeclared distance and calories are left out, not failed on`() {
        val fitted = scope.forManifest(setOf(readSteps, writeSteps))
        assertEquals(setOf(readSteps, writeSteps), fitted.requested)
        assertEquals(setOf(ReadType.STEPS), fitted.readTypes)
        assertEquals(setOf(readDistance, readCalories, writeDistance, ReadType.TOTAL_CALORIES.writePermission), fitted.dropped)
    }

    @Test
    fun `READ_STEPS and explicit opt-ins stay in, so leaving them out is still reported`() {
        val fitted = HealthConnectManager.PermissionScope(historyRead = true, activeCalories = true)
            .forManifest(emptySet())
        assertTrue(readSteps in fitted.requested)
        assertTrue(HealthConnectManager.READ_ACTIVE_CALORIES in fitted.requested)
        HealthConnectManager.PERMISSION_HISTORY_READ?.let { assertTrue(it in fitted.requested) }
        assertFalse(fitted.write)
    }

    @Test
    fun `a fully declared app is asked for everything, as before`() {
        val all = HealthConnectManager.READ_PERMISSIONS + HealthConnectManager.WRITE_PERMISSIONS
        val fitted = scope.forManifest(all)
        assertEquals(scope.requested, fitted.requested)
        assertTrue(fitted.dropped.isEmpty())
    }

    // ---- what a resolved day reports -----------------------------------

    private val metrics = MetricsCalculator(StepTrackerConfig().sanitised())
    private val date = "2026-09-09"
    private fun device(steps: Int) = DayTotals(date, steps, metrics.distance(steps), metrics.calories(steps))
    private fun watch(steps: Int, distance: Double, distanceSource: String) = StepSource(
        "com.fitbit.FitbitMobile", "Fitbit", StepSourceKind.WATCH, steps, distance, 0.0, 0L, false,
        distanceSource = distanceSource
    )
    private fun resolve(device: DayTotals, vararg sources: StepSource) =
        StepSourceResolver.resolve(StepSourcePolicy.AUTO, device, sources.toList(), null, metrics)

    @Test
    fun `a watch read without distance still wins, with its distance derived`() {
        // Steps granted, distance refused: the watch's steps, and a distance
        // from stride that says it is an estimate.
        val result = resolve(device(5_000), watch(8_000, 0.0, DistanceSource.NOT_READ))
        assertTrue(result.usedExternal)
        assertEquals(8_000, result.totals.steps)
        assertEquals(metrics.distance(8_000), result.totals.distance, 0.001)
        assertEquals(DistanceSource.DERIVED, result.distanceSource)
        assertEquals("derived", result.toMap()["distanceSource"])
    }

    @Test
    fun `a watch's own distance is reported as Health Connect's`() {
        val result = resolve(device(5_000), watch(8_000, 6_100.0, DistanceSource.HEALTH_CONNECT))
        assertEquals(6_100.0, result.totals.distance, 0.001)
        assertEquals(DistanceSource.HEALTH_CONNECT, result.distanceSource)
    }

    @Test
    fun `this device's own count is always derived`() {
        val result = resolve(device(5_000))
        assertFalse(result.usedExternal)
        assertEquals(DistanceSource.DERIVED, result.distanceSource)
    }

    @Test
    fun `sources carry their distance source to JS`() {
        assertEquals("not_read", watch(1, 0.0, DistanceSource.NOT_READ).toMap()["distanceSource"])
    }
}
