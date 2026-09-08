package com.steptrackerpro.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [StepHistoryEntity::class, DailySummaryEntity::class],
    version = 2,
    exportSchema = true
)
abstract class StepDatabase : RoomDatabase() {

    abstract fun stepHistoryDao(): StepHistoryDao
    abstract fun dailySummaryDao(): DailySummaryDao

    companion object {
        private const val NAME = "step_tracker_pro.db"

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

        @Volatile
        private var instance: StepDatabase? = null

        fun get(context: Context): StepDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    StepDatabase::class.java,
                    NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    // Counter state lives in SharedPreferences, so a corrupt or
                    // unmigratable history file costs history, never the live count.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { instance = it }
            }
    }
}
