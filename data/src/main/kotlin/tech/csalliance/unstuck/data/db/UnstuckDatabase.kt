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
                .fallbackToDestructiveMigration()
                .build()
    }
}
