package com.steptrackerpro.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [StepHistoryEntity::class, DailySummaryEntity::class, MotionWindowEntity::class],
    version = StepDatabase.VERSION,
    exportSchema = true
)
abstract class StepDatabase : RoomDatabase() {

    abstract fun stepHistoryDao(): StepHistoryDao
    abstract fun dailySummaryDao(): DailySummaryDao
    abstract fun motionWindowDao(): MotionWindowDao

    companion object {
        private const val NAME = "step_tracker_pro.db"

        /** Kept next to the `@Database` annotation; the migration test targets it. */
        const val VERSION = 4

        /**
         * Health Connect and the remote endpoint used to share the `synced`
         * flag, so whichever synced first hid the row from the other. Existing
         * rows keep `synced` as the Health Connect flag and start out pending
         * for the remote endpoint, which at worst re-uploads days the endpoint
         * already has - the upload is keyed by date and is idempotent.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE step_history ADD COLUMN syncedRemote INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_step_history_syncedRemote " +
                        "ON step_history (syncedRemote)"
                )
            }
        }

        /**
         * `daily_summary.recoveredSteps`: how many of a day's steps were
         * credited by gap recovery rather than observed. Existing rows get 0,
         * the only honest value for a day whose split was never recorded.
         * A destructive fallback is not an option here: users have 35 days
         * of history in this table and nothing else holds it.
         */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE daily_summary ADD COLUMN recoveredSteps INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * The `motion_window` table for opt-in motion signature windows. A
         * new table, so nothing existing is touched; the day tables are
         * exactly as version 3 left them.
         */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `motion_window` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`date` TEXT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "`sampleCount` INTEGER NOT NULL, " +
                        "`dominantFrequencyHz` REAL NOT NULL, " +
                        "`variance` REAL NOT NULL, " +
                        "`zeroCrossingRate` REAL NOT NULL, " +
                        "`peakRatio` REAL NOT NULL, " +
                        "`stepsDuringWindow` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_motion_window_startedAt` " +
                        "ON `motion_window` (`startedAt`)"
                )
            }
        }

        /** Every migration, in order, for the builder and the migration test. */
        internal val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        @Volatile
        private var instance: StepDatabase? = null

        fun get(context: Context): StepDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    StepDatabase::class.java,
                    NAME
                )
                    .addMigrations(*MIGRATIONS)
                    // Counter state lives in SharedPreferences, so a corrupt or
                    // unmigratable history file costs history, never the live count.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { instance = it }
            }
    }
}
