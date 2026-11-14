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

    @Test
    fun recordsBeforeCountFromAreNotCountedButKeptAsEvidence() = runBlocking {
        core.updateConfig(core.config().copy(healthConnectReadTypes = listOf("steps")))
        val records = setOf(HealthConnectManager.RecordType.STEPS)
        val stored = (readsFor(setOf(SnapshotPart.HEALTH_CONNECT_RECORDS), records).second["healthConnectRecords"]
            as Map<*, *>)["records"] as List<*>
        val record = stored.map { it as Map<*, *> }.single { it["count"] == 1_234L }
        val start = record["startTime"] as Long
        val end = record["endTime"] as Long
        // Signed in half way through the record.
        val at = start + (end - start) / 2
        try {
            core.setCountFrom(at)
            val snapshot = readsFor(setOf(SnapshotPart.HEALTH_CONNECT_RECORDS), records).second

            assertEquals(at, snapshot["countFrom"])
            // Counted for its share after countFrom...
            val own = (snapshot["sources"] as List<*>).map { it as Map<*, *> }.single { it["isSelf"] == true }
            assertEquals(com.steptrackerpro.health.CountFrom.shareAfter(1_234, start, end, at), own["steps"])
            // ...and kept whole as evidence, for a server to apply countFrom to itself.
            val evidence = (snapshot["healthConnectRecords"] as Map<*, *>)["records"] as List<*>
            assertTrue(evidence.any { (it as Map<*, *>)["count"] == 1_234L })

            // A countFrom after the record: nothing of it counts.
            core.setCountFrom(end + 1_000L)
            val after = readsFor(emptySet(), records).second
            assertTrue((after["sources"] as List<*>).none { (it as Map<*, *>)["isSelf"] == true })
        } finally {
            core.setCountFrom(0L)
        }
    }

    /** This app's own records of [type] for [date], straight from the provider. */
    private suspend fun <T : androidx.health.connect.client.records.Record> own(
        type: kotlin.reflect.KClass<T>,
        date: String
    ): List<T> {
        val hc = androidx.health.connect.client.HealthConnectClient.getOrCreate(context)
        return hc.readRecords(
            androidx.health.connect.client.request.ReadRecordsRequest(
                recordType = type,
                timeRangeFilter = androidx.health.connect.client.time.TimeRangeFilter.between(
                    DateKeys.startOfDayInstant(date), DateKeys.endOfDayInstant(date)
                ),
                dataOriginFilter = setOf(androidx.health.connect.client.records.metadata.DataOrigin(context.packageName))
            )
        ).records
    }

    @Test
    fun theCountFromDaysRecordStartsAtCountFrom() = runBlocking {
        val records = setOf(HealthConnectManager.RecordType.STEPS)
        val stored = (readsFor(setOf(SnapshotPart.HEALTH_CONNECT_RECORDS), records).second["healthConnectRecords"]
            as Map<*, *>)["records"] as List<*>
        val record = stored.map { it as Map<*, *> }.single { it["count"] == 1_234L }
        val start = record["startTime"] as Long
        val end = record["endTime"] as Long
        val at = start + (end - start) / 2
        try {
            core.setCountFrom(at)
            // A countFrom past the window: readHealthConnectSteps() asks
            // for nothing at all.
            assertTrue(
                core.healthConnect.readDailySteps(
                    java.time.Instant.ofEpochMilli(start), java.time.Instant.ofEpochMilli(end), countFromMs = end + 1
                ).isEmpty()
            )

            // The day record written now starts at countFrom, not midnight:
            // this phone counted nothing before it.
            val written = core.healthConnect.writeDays(listOf(DayTotals(today, 500, 400.0, 20.0)))
            assertEquals(setOf(today), written.written)
            val steps = own(androidx.health.connect.client.records.StepsRecord::class, today)
                .single { it.metadata.clientRecordId == "stp-steps-$today" }
            assertEquals(at, steps.startTime.toEpochMilli())
            assertEquals(500L, steps.count)
        } finally {
            core.setCountFrom(0L)
        }
        // With countFrom gone, the day is midnight's again.
        assertEquals(setOf(today), core.healthConnect.writeDays(listOf(DayTotals(today, 600, 400.0, 20.0))).written)
        val steps = own(androidx.health.connect.client.records.StepsRecord::class, today)
            .single { it.metadata.clientRecordId == "stp-steps-$today" }
        assertEquals(DateKeys.startOfDayMillis(today), steps.startTime.toEpochMilli())
    }

    /**
     * Puts this app on Health Connect's priority list for activity data,
     * which is all an aggregate reads from. Health Connect's own permission
     * screen does that when the user grants; `pm grant`, which is how a
     * test is granted, does not - so an aggregate returns nothing of a test
     * APK's records. Through the hidden method that screen calls, as the
     * platform's own CTS does, under the shell's identity: it is there only
     * with `adb shell settings put global hidden_api_policy 1`.
     */
    private fun joinThePriorityList(): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.MANAGE_HEALTH_PERMISSIONS")
        return try {
            val manager = context.getSystemService("healthconnect") ?: return false
            val grant = manager.javaClass.getMethod("grantHealthPermission", String::class.java, String::class.java)
            grant.invoke(manager, context.packageName, "android.permission.health.WRITE_STEPS")
            true
        } catch (_: Throwable) {
            false
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    @Test
    fun aRecordStraddlingCountFromCountsForItsShareAfterIt() = runBlocking {
        assumeTrue(joinThePriorityList())
        // The day record setUp wrote: 1,234 steps, midnight to now.
        val record = own(androidx.health.connect.client.records.StepsRecord::class, today)
            .single { it.metadata.clientRecordId == "stp-steps-$today" }
        val start = record.startTime
        val end = record.endTime
        assumeTrue(java.time.Duration.between(start, end).toMinutes() >= 10)
        // Health Connect's aggregate reads this app's record at all.
        assumeTrue(core.healthConnect.readDailySteps(start, end).sumOf { it.steps } == 1_234)

        // countFrom half way through it: half of it is counted. Health
        // Connect shares a record out by time over the window asked for,
        // and the day is asked for from countFrom on.
        val middle = start.plusMillis(java.time.Duration.between(start, end).toMillis() / 2)
        val half = core.healthConnect.readDailySteps(start, end, countFromMs = middle.toEpochMilli())
        assertEquals(listOf(today), half.map { it.date })
        assertTrue("read ${half.single().steps}", half.single().steps in 612..622)
        // Three quarters through: a quarter.
        val late = start.plusMillis(java.time.Duration.between(start, end).toMillis() * 3 / 4)
        val quarter = core.healthConnect.readDailySteps(start, end, countFromMs = late.toEpochMilli())
        assertTrue("read ${quarter.single().steps}", quarter.single().steps in 303..314)
        // At or before the window's start it changes nothing.
        assertEquals(1_234, core.healthConnect.readDailySteps(start, end, countFromMs = start.toEpochMilli()).sumOf { it.steps })
        // Past the window: no day at all.
        assertTrue(core.healthConnect.readDailySteps(start, end, countFromMs = end.toEpochMilli() + 1).isEmpty())
    }

    @Test
    fun caloriesAreWrittenAsActiveBurnAndTheOldTotalBurnRecordIsCleared() = runBlocking {
        val day = DateKeys.yesterday()
        val hc = androidx.health.connect.client.HealthConnectClient.getOrCreate(context)
        val dayStart = DateKeys.startOfDayInstant(day)
        val zone = java.time.ZoneId.systemDefault().rules.getOffset(dayStart)
        try {
            // What 2.9 wrote for the day: total burn, under this package's id.
            hc.insertRecords(
                listOf(
                    androidx.health.connect.client.records.TotalCaloriesBurnedRecord(
                        startTime = dayStart, startZoneOffset = zone,
                        endTime = dayStart.plusSeconds(3_600), endZoneOffset = zone,
                        energy = androidx.health.connect.client.units.Energy.kilocalories(55.0),
                        metadata = androidx.health.connect.client.records.metadata.Metadata.autoRecorded(
                            device = androidx.health.connect.client.records.metadata.Device(
                                type = androidx.health.connect.client.records.metadata.Device.TYPE_PHONE
                            ),
                            clientRecordId = "stp-calories-$day"
                        )
                    )
                )
            )
            assertEquals(1, own(androidx.health.connect.client.records.TotalCaloriesBurnedRecord::class, day).size)

            assertEquals(setOf(day), core.healthConnect.writeDays(listOf(DayTotals(day, 3_000, 2_100.0, 75.0))).written)

            val active = own(androidx.health.connect.client.records.ActiveCaloriesBurnedRecord::class, day)
            assertEquals(75.0, active.single().energy.inKilocalories, 0.01)
            assertEquals("stp-calories-$day", active.single().metadata.clientRecordId)
            // The walking is not in the day twice, once under each type.
            assertTrue(own(androidx.health.connect.client.records.TotalCaloriesBurnedRecord::class, day).isEmpty())

            // And a day deleted takes its old total-burn record with it,
            // though nothing was written over it first.
            val earlier = DateKeys.of(DateKeys.startOfDayMillis(day) - 12 * 3_600_000L)
            val earlierStart = DateKeys.startOfDayInstant(earlier)
            hc.insertRecords(
                listOf(
                    androidx.health.connect.client.records.TotalCaloriesBurnedRecord(
                        startTime = earlierStart, startZoneOffset = zone,
                        endTime = earlierStart.plusSeconds(3_600), endZoneOffset = zone,
                        energy = androidx.health.connect.client.units.Energy.kilocalories(41.0),
                        metadata = androidx.health.connect.client.records.metadata.Metadata.autoRecorded(
                            device = androidx.health.connect.client.records.metadata.Device(
                                type = androidx.health.connect.client.records.metadata.Device.TYPE_PHONE
                            ),
                            clientRecordId = "stp-calories-$earlier"
                        )
                    )
                )
            )
            assertEquals(1, own(androidx.health.connect.client.records.TotalCaloriesBurnedRecord::class, earlier).size)
            core.healthConnect.deleteOwnDays(listOf(earlier))
            assertTrue(own(androidx.health.connect.client.records.TotalCaloriesBurnedRecord::class, earlier).isEmpty())
        } finally {
            runCatching { core.healthConnect.deleteOwnDays(listOf(day)) }
        }
        Unit
    }

    private companion object {
        val PERMISSIONS = listOf(
            "android.permission.health.READ_STEPS",
            "android.permission.health.READ_DISTANCE",
            "android.permission.health.WRITE_STEPS",
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND",
            "android.permission.health.WRITE_ACTIVE_CALORIES_BURNED",
            "android.permission.health.READ_ACTIVE_CALORIES_BURNED",
            "android.permission.health.WRITE_TOTAL_CALORIES_BURNED",
            "android.permission.health.READ_TOTAL_CALORIES_BURNED"
        )
    }
}
