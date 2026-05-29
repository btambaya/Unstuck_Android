package tech.csalliance.unstuck.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {
    @Query("SELECT * FROM records WHERE tableName = :table")
    fun observe(table: String): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE tableName = :table")
    suspend fun get(table: String): List<RecordEntity>

    @Upsert
    suspend fun upsert(rows: List<RecordEntity>)

    @Upsert
    suspend fun upsertOne(row: RecordEntity)

    @Query("DELETE FROM records WHERE tableName = :table AND id = :id")
    suspend fun deleteById(table: String, id: String)

    @Query("DELETE FROM records WHERE tableName = :table")
    suspend fun clearTable(table: String)

    @Query("DELETE FROM records")
    suspend fun clearAll()

    /** Replace-per-table: wipe the table then insert the fetched rows. Used by
     *  the Hydrator for each table that fetched OK. `preserveIdsPrefix` keeps
     *  local rows whose id starts with it (external `g_` cal_blocks the server
     *  doesn't own). */
    @Transaction
    suspend fun replaceTable(table: String, rows: List<RecordEntity>, preserveIdsPrefix: String? = null) {
        if (preserveIdsPrefix == null) {
            clearTable(table)
        } else {
            deleteNotPrefixed(table, "$preserveIdsPrefix%")
        }
        upsert(rows)
    }

    @Query("DELETE FROM records WHERE tableName = :table AND id NOT LIKE :likePattern")
    suspend fun deleteNotPrefixed(table: String, likePattern: String)
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY seq ASC")
    suspend fun all(): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox")
    fun count(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(op: OutboxEntity): Long

    @Query("DELETE FROM outbox WHERE seq = :seq")
    suspend fun remove(seq: Long)

    @Query("DELETE FROM outbox")
    suspend fun clear()
}

@Dao
interface LiveSessionDao {
    @Query("SELECT * FROM live_session WHERE id = 0")
    fun observe(): Flow<LiveSessionEntity?>

    @Query("SELECT * FROM live_session WHERE id = 0")
    suspend fun get(): LiveSessionEntity?

    @Upsert
    suspend fun set(row: LiveSessionEntity)

    @Query("DELETE FROM live_session")
    suspend fun clear()
}
