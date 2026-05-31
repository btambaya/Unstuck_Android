package tech.csalliance.unstuck.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [RecordEntity::class, OutboxEntity::class, LiveSessionEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class UnstuckDatabase : RoomDatabase() {
    abstract fun records(): RecordDao
    abstract fun outbox(): OutboxDao
    abstract fun liveSession(): LiveSessionDao

    companion object {
        fun build(context: Context): UnstuckDatabase =
            Room.databaseBuilder(context, UnstuckDatabase::class.java, "unstuck.db")
                // Never silently destroy local data on an UPGRADE — that would wipe the
                // `outbox` (the only copy of unsynced offline writes). A future version
                // bump without a registered Migration now fails loudly in dev/test
                // instead of nuking production data. Downgrade (older APK) still resets.
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
