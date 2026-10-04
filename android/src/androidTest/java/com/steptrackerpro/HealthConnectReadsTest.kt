package com.steptrackerpro

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.steptrackerpro.core.DateKeys
import com.steptrackerpro.core.DayTotals
import com.steptrackerpro.core.SnapshotPart
import com.steptrackerpro.core.StepTrackerCore
import com.steptrackerpro.health.HealthConnectManager
import com.steptrackerpro.health.HealthConnectQuota
import com.steptrackerpro.util.PermissionHelper
import androidx.work.await
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The package's Health Connect reads against the platform's own provider -
 * Android 14 and later - rather than a fake. Opt-in, and on its own: the
 * test APK declares the health permissions only with
 * `-PstpHealthConnectTests`, and grants them to itself here, which no other
 * instrumented test expects. Revoking them again would kill the process, so
 * they stay granted until the run uninstalls the APK. See docs/TESTING.md.
 */
@RunWith(AndroidJUnit4::class)
class HealthConnectReadsTest {

    private lateinit var context: Context
    private lateinit var core: StepTrackerCore
    private val today = DateKeys.today()

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        assumeTrue(PermissionHelper.declaredPermissions(context).containsAll(PERMISSIONS))
        core = StepTrackerCore.get(context)
        assumeTrue(core.healthConnect.availability() == HealthConnectManager.Availability.AVAILABLE)
        // Nothing in the background reads Health Connect while calls are
        // counted: the periodic sync, which reads to see who owns each day,
        // is not scheduled without writes on.
        core.updateConfig(core.config().copy(healthConnectWriteEnabled = false))
        androidx.work.WorkManager.getInstance(context).cancelAllWork().await()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        PERMISSIONS.forEach { automation.grantRuntimePermission(context.packageName, it) }
        core.healthConnect.invalidatePermissionCache()
        // A record of this app's own for today, as the mirror writes it.
        val written = core.healthConnect.writeDays(listOf(DayTotals(today, 1_234, 900.0, 40.0)))
        assertEquals(setOf(today), written.written)
    }

    @After
    fun tearDown() = runBlocking {
        if (::core.isInitialized) runCatching { core.healthConnect.deleteOwnDays(listOf(today)) }
        Unit
    }

    private fun reads() = core.healthConnect.quota.callsLast15Minutes(HealthConnectQuota.Category.READ)

    /** Read calls a snapshot of today makes, once the changes feed and the device question are out of the way. */
    private suspend fun readsFor(include: Set<SnapshotPart>, recordTypes: Set<HealthConnectManager.RecordType>): Pair<Int, Map<String, Any?>> {
        core.resolveDay(today)
        val before = reads()
        val snapshot = core.verificationSnapshot(today, include = include, recordTypes = recordTypes)
        return reads() - before to snapshot
    }

    @Test
    fun aSnapshotWithItsRecordsReadsTheDayOnce() = runBlocking {
        core.updateConfig(core.config().copy(healthConnectReadTypes = listOf("steps", "distance")))
        val steps = setOf(HealthConnectManager.RecordType.STEPS)

        val (alone, sourcesOnly) = readsFor(emptySet(), steps)
        val (both, withRecords) = readsFor(setOf(SnapshotPart.HEALTH_CONNECT_RECORDS), steps)

        assertEquals("read", sourcesOnly["sourcesStatus"])
        assertTrue(alone > 0)
        // The records come with the sources' read: not a call more.
        assertEquals(alone, both)
        assertEquals("read", withRecords["sourcesStatus"])
        val records = withRecords["healthConnectRecords"] as Map<*, *>
        assertEquals("read", records["status"])
        assertEquals(false, records["truncated"])
        assertTrue((records["records"] as List<*>).any { (it as Map<*, *>)["count"] == 1_234L })
        val sources = withRecords["sources"] as List<*>
        assertTrue(sources.any { (it as Map<*, *>)["isSelf"] == true && it["steps"] == 1_234 })
    }

    @Test
    fun aRecordTypeTheSourcesDoNotReadIsReadOnItsOwn() = runBlocking {
        // Distance asked for by the snapshot, not by the sources.
        core.updateConfig(core.config().copy(healthConnectReadTypes = listOf("steps")))
        val types = setOf(HealthConnectManager.RecordType.STEPS, HealthConnectManager.RecordType.DISTANCE)

        val (alone, _) = readsFor(emptySet(), types)
        val (both, withRecords) = readsFor(setOf(SnapshotPart.HEALTH_CONNECT_RECORDS), types)

        assertEquals(alone + 1, both)
        val records = withRecords["healthConnectRecords"] as Map<*, *>
        assertEquals("read", records["status"])
        assertEquals(listOf("steps", "distance"), records["recordTypes"])
        assertTrue((records["records"] as List<*>).any { (it as Map<*, *>)["count"] == 1_234L })
    }

    private companion object {
        val PERMISSIONS = listOf(
            "android.permission.health.READ_STEPS",
            "android.permission.health.READ_DISTANCE",
            "android.permission.health.WRITE_STEPS",
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
        )
    }
}
