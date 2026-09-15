package com.steptrackerpro.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The history tables hold up to 35 days of a user's steps and nothing else
 * does, so a schema change has to carry every row across. These tests build
 * the database exactly as an older version created it (from the exported
 * schema JSON), insert rows, run the real migrations and check the rows are
 * still there with the right defaults. A destructive fallback would pass a
 * weaker version of this test by deleting everything, which is the point of
 * not having one.
 *
 *   ./gradlew :react-native-step-tracker-pro:connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class StepDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StepDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate2ToCurrentKeepsEveryRowAndReadsRecoveredAsZero() {
        // Version 2 as it shipped in 1.2 and 1.3: no recoveredSteps column.
        helper.createDatabase(NAME, 2).apply {
            execSQL(
                "INSERT INTO step_history (date, steps, distance, calories, synced, syncedRemote, createdAt, updatedAt) " +
                    "VALUES ('2026-09-01', 8123, 5900.5, 250.2, 1, 0, 1, 1)"
            )
            execSQL(
                "INSERT INTO step_history (date, steps, distance, calories, synced, syncedRemote, createdAt, updatedAt) " +
                    "VALUES ('2026-09-02', 11204, 7887.6, 336.4, 0, 0, 2, 2)"
            )
            execSQL(
                "INSERT INTO daily_summary (date, totalSteps, totalDistance, totalCalories, updatedAt) " +
                    "VALUES ('2026-09-01', 8123, 5900.5, 250.2, 1)"
            )
            execSQL(
                "INSERT INTO daily_summary (date, totalSteps, totalDistance, totalCalories, updatedAt) " +
                    "VALUES ('2026-09-02', 11204, 7887.6, 336.4, 2)"
            )
            close()
        }

        // validateDroppedTables = true: nothing may have been recreated by
        // dropping, which is what a destructive fallback would have done.
        // Run to the current version so the chain is what a 1.3 install
        // will actually go through.
        val db = helper.runMigrationsAndValidate(
            NAME, StepDatabase.VERSION, true, StepDatabase.MIGRATION_2_3, StepDatabase.MIGRATION_3_4
        )

        db.query("SELECT date, totalSteps, recoveredSteps FROM daily_summary ORDER BY date").use { cursor ->
            assertEquals(2, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("2026-09-01", cursor.getString(0))
            assertEquals(8123, cursor.getInt(1))
            // A day whose split was never recorded reads as fully observed.
            assertEquals(0, cursor.getInt(2))
            assertTrue(cursor.moveToNext())
            assertEquals("2026-09-02", cursor.getString(0))
            assertEquals(11204, cursor.getInt(1))
            assertEquals(0, cursor.getInt(2))
        }
        db.query("SELECT COUNT(*) FROM step_history").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
        // The motion table exists and starts empty; nothing invents windows.
        db.query("SELECT COUNT(*) FROM motion_window").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }

    @Test
    fun migratedDatabaseOpensThroughRoomAndReadsThroughTheDao() = runBlocking {
        helper.createDatabase(NAME, 2).apply {
            execSQL(
                "INSERT INTO step_history (date, steps, distance, calories, synced, syncedRemote, createdAt, updatedAt) " +
                    "VALUES ('2026-09-03', 5000, 3600.0, 150.0, 0, 0, 3, 3)"
            )
            execSQL(
                "INSERT INTO daily_summary (date, totalSteps, totalDistance, totalCalories, updatedAt) " +
                    "VALUES ('2026-09-03', 5000, 3600.0, 150.0, 3)"
            )
            close()
        }
        // Room's own open path: the identity hash of the migrated schema has
        // to match the entities, or it throws here.
        val room = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StepDatabase::class.java,
            NAME
        ).addMigrations(*StepDatabase.MIGRATIONS).build()
        try {
            val summary = room.dailySummaryDao().findByDate("2026-09-03")!!
            assertEquals(5000, summary.totalSteps)
            assertEquals(0, summary.recoveredSteps)
            // And the new column takes writes.
            room.dailySummaryDao().upsert(summary.copy(recoveredSteps = 300))
            assertEquals(300, room.dailySummaryDao().findByDate("2026-09-03")!!.recoveredSteps)
            // The motion table takes a row and prunes to the bound.
            val dao = room.motionWindowDao()
            repeat(5) { i ->
                dao.insert(
                    MotionWindowEntity(
                        date = "2026-09-03", startedAt = 1_000L + i, durationMs = 10_000L,
                        sampleCount = 250, dominantFrequencyHz = 1.8, variance = 2.0,
                        zeroCrossingRate = 3.6, peakRatio = 0.6, stepsDuringWindow = 18
                    )
                )
            }
            dao.pruneToNewest(3)
            assertEquals(3, dao.count())
            assertEquals(listOf(1_002L, 1_003L, 1_004L), dao.findRange(0L, 2_000L).map { it.startedAt })
        } finally {
            room.close()
        }
    }

    private companion object {
        const val NAME = "migration-test.db"
    }
}
