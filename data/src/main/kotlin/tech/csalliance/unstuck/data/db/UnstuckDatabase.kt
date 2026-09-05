package tech.csalliance.unstuck.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RecordEntity::class, OutboxEntity::class, ParkedOutboxEntity::class, LiveSessionEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class UnstuckDatabase : RoomDatabase() {
    abstract fun records(): RecordDao
    abstract fun outbox(): OutboxDao
    abstract fun parkedOutbox(): ParkedOutboxDao
    abstract fun liveSession(): LiveSessionDao

    companion object {
        /** v1 → v2: `outbox.base` (the pre-edit server row for 3-way merges) and the
         *  per-user `parked_outbox` (un-pushed ops kept across sign-out). Additive —
         *  existing outbox rows keep flowing with base = null (row-level LWW). The
         *  column/table shapes must match the entities EXACTLY or Room's identity
         *  check throws on open; keep them in lockstep with Entities.kt. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `outbox` ADD COLUMN `base` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `parked_outbox` (" +
                        "`seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`userId` TEXT NOT NULL, `op` TEXT NOT NULL, `recordTable` TEXT NOT NULL, " +
                        "`recordId` TEXT NOT NULL, `payload` TEXT, `dependsOn` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, `base` TEXT)",
                )
            }
        }

        fun build(context: Context): UnstuckDatabase =
            Room.databaseBuilder(context, UnstuckDatabase::class.java, "unstuck.db")
                .addMigrations(MIGRATION_1_2)
                // Never silently destroy local data on an UPGRADE — that would wipe the
                // `outbox` (the only copy of unsynced offline writes). A future version
                // bump without a registered Migration now fails loudly in dev/test
                // instead of nuking production data. Downgrade (older APK) still resets.
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
