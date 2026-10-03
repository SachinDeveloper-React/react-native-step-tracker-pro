package com.steptrackerpro.health

import com.steptrackerpro.health.HealthConnectManager.PermissionScope
import com.steptrackerpro.health.HealthConnectManager.ReadType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Health Connect's feature, metadata and on-device tracking guidance
 * asks of the package: optional reads only where the provider has them,
 * every device type named, and this phone's own data recognised.
 */
class HealthConnectFeaturesTest {

    private val background = HealthConnectManager.PERMISSION_BACKGROUND_READ!!
    private val history = HealthConnectManager.PERMISSION_HISTORY_READ!!

    @After
    fun forgetDeviceOrigin() {
        StepSourceCatalog.currentDeviceOrigin = null
    }

    @Test
    fun `background and history reads are asked for only where the provider has them`() {
        val wanted = PermissionScope(backgroundRead = true, historyRead = true)
        assertTrue(background in wanted.requested)
        assertTrue(history in wanted.requested)

        val old = wanted.copy(backgroundReadAvailable = false, historyReadAvailable = false)
        assertFalse(background in old.requested)
        assertFalse(history in old.requested)
        // Neither is reported as a manifest mistake either.
        assertTrue(old.forManifest(old.required).dropped.isEmpty())
    }

    @Test
    fun `day records carry one client record id per type and day`() {
        assertEquals("stp-steps-2026-10-03", ReadType.STEPS.clientRecordId("2026-10-03"))
        assertEquals("stp-distance-2026-10-03", ReadType.DISTANCE.clientRecordId("2026-10-03"))
        assertEquals("stp-calories-2026-10-03", ReadType.TOTAL_CALORIES.clientRecordId("2026-10-03"))
    }

    @Test
    fun `every device type has a name, the extended ones included`() {
        assertEquals("watch", HealthConnectManager.deviceTypeName(1))
        assertEquals("smart_display", HealthConnectManager.deviceTypeName(8))
        assertEquals(
            listOf(
                "consumer_medical_device", "glasses", "hearable", "fitness_machine",
                "fitness_equipment", "portable_computer", "meter"
            ),
            (9..15).map { HealthConnectManager.deviceTypeName(it) }
        )
        assertEquals("unknown", HealthConnectManager.deviceTypeName(0))
        assertEquals("unknown", HealthConnectManager.deviceTypeName(99))
    }

    @Test
    fun `this phone's own data is recognised by every name it has had`() {
        assertTrue(StepSourceCatalog.isPlatformOrigin("android"))
        assertTrue(StepSourceCatalog.isPlatformOrigin("com.android.healthconnect.phone.jd5bdd37e1a8d3667a05d0abebfc4a89e"))
        assertFalse(StepSourceCatalog.isPlatformOrigin("com.android.healthconnect.tablet.abc"))

        // The name the platform gives this phone, whatever its shape.
        StepSourceCatalog.currentDeviceOrigin = "com.android.healthconnect.tablet.abc"
        assertTrue(StepSourceCatalog.isPlatformOrigin("com.android.healthconnect.tablet.abc"))
        assertEquals(
            StepSourceKind.PHONE,
            StepSourceCatalog.classify("com.android.healthconnect.tablet.abc", 1, "com.example.app")
        )
        assertEquals(StepSourceCatalog.PLATFORM_APP_NAME, StepSourceCatalog.appName("com.android.healthconnect.tablet.abc"))
    }
}
