package com.steptrackerpro.health

import com.steptrackerpro.health.HealthConnectManager.PermissionScope
import com.steptrackerpro.health.HealthConnectManager.ReadType
import com.steptrackerpro.health.HealthConnectManager.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `healthConnectReadTypes` decides which read permissions an app needs, and
 * the raw reads take their record types by name.
 */
class HealthConnectTypesTest {

    private val readSteps = "android.permission.health.READ_STEPS"
    private val readDistance = "android.permission.health.READ_DISTANCE"
    private val readCalories = "android.permission.health.READ_TOTAL_CALORIES_BURNED"

    @Test
    fun `read types always keep steps and drop unknown names`() {
        assertEquals(setOf(ReadType.STEPS), ReadType.parse(emptyList()))
        assertEquals(setOf(ReadType.STEPS), ReadType.parse(listOf("heartRate")))
        assertEquals(setOf(ReadType.STEPS, ReadType.DISTANCE), ReadType.parse(listOf("distance")))
        assertEquals(ReadType.ALL, ReadType.parse(listOf("steps", "distance", "totalCalories")))
    }

    @Test
    fun `each read type maps to its own permission`() {
        assertEquals(setOf(readSteps), ReadType.permissions(setOf(ReadType.STEPS)))
        assertEquals(setOf(readSteps, readDistance, readCalories), HealthConnectManager.READ_PERMISSIONS)
    }

    @Test
    fun `calories are read as total burn and written as active burn`() {
        // What other apps publish - a watch's whole day - is total burn;
        // what this package works out from distance is the walking alone,
        // which Health Connect calls active burn. Written as total until 2.10.
        assertEquals(readCalories, ReadType.TOTAL_CALORIES.permission)
        assertEquals(
            "android.permission.health.WRITE_ACTIVE_CALORIES_BURNED",
            ReadType.TOTAL_CALORIES.writePermission
        )
        assertEquals(
            "android.permission.health.WRITE_TOTAL_CALORIES_BURNED",
            HealthConnectManager.LEGACY_WRITE_TOTAL_CALORIES
        )
        assertEquals(
            setOf(
                "android.permission.health.WRITE_STEPS",
                "android.permission.health.WRITE_DISTANCE",
                "android.permission.health.WRITE_ACTIVE_CALORIES_BURNED"
            ),
            HealthConnectManager.WRITE_PERMISSIONS
        )
        // Steps and distance are read and written as the same type.
        assertEquals(ReadType.STEPS.recordClass, ReadType.STEPS.writeRecordClass)
        assertEquals(ReadType.DISTANCE.recordClass, ReadType.DISTANCE.writeRecordClass)
    }

    @Test
    fun `a steps-only app requires one read permission`() {
        val stepsOnly = PermissionScope(write = false, readTypes = setOf(ReadType.STEPS))
        assertEquals(setOf(readSteps), stepsOnly.required)
        assertEquals(setOf(readSteps), stepsOnly.requested)
    }

    @Test
    fun `the default scope still requires all three reads and writes`() {
        val scope = PermissionScope()
        assertEquals(6, scope.required.size)
        assertEquals(true, scope.required.containsAll(HealthConnectManager.READ_PERMISSIONS))
        assertEquals(true, scope.required.containsAll(HealthConnectManager.WRITE_PERMISSIONS))
    }

    @Test
    fun `a scope without steps in its types still reads steps`() {
        val scope = PermissionScope(write = false, readTypes = setOf(ReadType.DISTANCE))
        assertEquals(setOf(readSteps, readDistance), scope.required)
    }

    @Test
    fun `record types parse in declaration order and reject unknown or empty lists`() {
        assertEquals(listOf(RecordType.STEPS, RecordType.DISTANCE), RecordType.parse(listOf("distance", "steps"))?.toList())
        assertEquals(setOf(RecordType.DISTANCE), RecordType.parse(listOf("distance")))
        assertNull(RecordType.parse(emptyList()))
        assertNull(RecordType.parse(listOf("steps", "heartRate")))
        assertEquals(readDistance, RecordType.DISTANCE.permission)
    }
}
