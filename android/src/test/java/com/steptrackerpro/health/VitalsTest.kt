package com.steptrackerpro.health

import com.steptrackerpro.health.HealthConnectManager.PermissionScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vitals are read-only opt-ins: each one the app lists in
 * `healthConnectReadVitals` is one more read permission, summarised as a
 * count, a range, a mean and the latest measurement.
 */
class VitalsTest {

    @Test
    fun `measurements fold into a count, a range, a mean and the latest`() {
        val stats = VitalStats()
        stats.add(time = 1_000L, value = 60.0, packageName = "watch")
        stats.add(time = 3_000L, value = 80.0, packageName = "ring")
        stats.add(time = 2_000L, value = 70.0, packageName = "watch")
        // Not a measurement: a NaN would poison the mean.
        stats.add(time = 4_000L, value = Double.NaN, packageName = "watch")

        assertEquals(3, stats.count)
        assertEquals(60.0, stats.min()!!, 0.0)
        assertEquals(80.0, stats.max()!!, 0.0)
        assertEquals(70.0, stats.avg()!!, 1e-9)
        assertEquals(VitalMeasurement(3_000L, 80.0, "ring"), stats.latest)
    }

    @Test
    fun `nothing measured reports nulls, never zeros`() {
        val map = VitalStats().toMap()
        assertEquals(0, map["count"])
        assertNull(map["min"])
        assertNull(map["max"])
        assertNull(map["avg"])
        assertNull(map["latest"])
    }

    @Test
    fun `heart rate takes its figures from the aggregate and only its latest from a record`() {
        val stats = VitalStats()
        stats.setAggregate(count = 5_400L, min = 48.0, max = 171.0, avg = 92.0)
        stats.offerLatest(time = 9_000L, value = 101.0, packageName = "watch")
        stats.offerLatest(time = 8_000L, value = 99.0, packageName = "watch")

        assertEquals(5_400, stats.count)
        assertEquals(48.0, stats.min()!!, 0.0)
        assertEquals(171.0, stats.max()!!, 0.0)
        assertEquals(92.0, stats.avg()!!, 0.0)
        assertEquals(VitalMeasurement(9_000L, 101.0, "watch"), stats.latest)

        val none = VitalStats().apply { setAggregate(count = 0L, min = null, max = null, avg = null) }
        assertNull(none.avg())
        assertNull(none.min())
    }

    @Test
    fun `blood pressure reports systolic with diastolic alongside`() {
        val systolic = VitalStats().apply { add(1_000L, 120.0, "cuff") }
        val diastolic = VitalStats().apply { add(1_000L, 80.0, "cuff") }
        val map = VitalSummary(VitalType.BLOOD_PRESSURE, systolic, diastolic).toMap()

        assertEquals("bloodPressure", map["type"])
        assertEquals("mmHg", map["unit"])
        assertEquals(120.0, map["max"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(80.0, (map["diastolic"] as Map<String, Any?>)["max"])
        assertEquals(false, map["truncated"])
        assertFalse("diastolic" in VitalSummary(VitalType.HEART_RATE, VitalStats()).toMap())
    }

    @Test
    fun `vital names parse in declaration order, and an unknown one is refused or dropped`() {
        assertEquals(
            listOf(VitalType.HEART_RATE, VitalType.BLOOD_PRESSURE),
            VitalType.parse(listOf("bloodPressure", "heartRate"))?.toList()
        )
        assertNull(VitalType.parse(listOf("heartRate", "steps")))
        assertEquals(setOf(VitalType.OXYGEN_SATURATION), VitalType.parseLenient(listOf("steps", "oxygenSaturation")))
        assertTrue(VitalType.parse(emptyList())!!.isEmpty())
    }

    @Test
    fun `each vital is its own read permission`() {
        assertEquals("android.permission.health.READ_HEART_RATE", VitalType.HEART_RATE.permission)
        assertEquals("android.permission.health.READ_RESTING_HEART_RATE", VitalType.RESTING_HEART_RATE.permission)
        assertEquals("android.permission.health.READ_OXYGEN_SATURATION", VitalType.OXYGEN_SATURATION.permission)
        assertEquals("android.permission.health.READ_RESPIRATORY_RATE", VitalType.RESPIRATORY_RATE.permission)
        assertEquals("android.permission.health.READ_BLOOD_PRESSURE", VitalType.BLOOD_PRESSURE.permission)
        assertEquals("android.permission.health.READ_BODY_TEMPERATURE", VitalType.BODY_TEMPERATURE.permission)
        assertEquals("android.permission.health.READ_BLOOD_GLUCOSE", VitalType.BLOOD_GLUCOSE.permission)
    }

    @Test
    fun `vitals are asked for only when listed, and only with reads on`() {
        val heart = VitalType.HEART_RATE.permission
        assertFalse(heart in PermissionScope().requested)
        assertTrue(heart in PermissionScope(vitals = setOf(VitalType.HEART_RATE)).requested)
        assertFalse(heart in PermissionScope(read = false, vitals = setOf(VitalType.HEART_RATE)).requested)
        // An opt-in, never part of what the app cannot do without.
        assertFalse(heart in PermissionScope(vitals = setOf(VitalType.HEART_RATE)).required)
    }

    @Test
    fun `a listed vital the manifest leaves out is kept, so the request can say so`() {
        val declared = setOf(HealthConnectManager.ReadType.STEPS.permission)
        val scope = PermissionScope(
            write = false,
            readTypes = setOf(HealthConnectManager.ReadType.STEPS),
            vitals = setOf(VitalType.HEART_RATE)
        ).forManifest(declared)
        // Still asked for - and so reported undeclared, failing the request -
        // rather than quietly dropped like a default read type.
        assertTrue(VitalType.HEART_RATE.permission in scope.requested)
        assertEquals(setOf(VitalType.HEART_RATE.permission), scope.requested - declared)
        assertTrue(scope.dropped.isEmpty())
    }
}
