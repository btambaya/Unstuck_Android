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
    /** The ONE read every reactive collection re-runs. Deliberately NOT a Room
     *  `Flow<...>`: all synced rows share this table, so Room's per-table
     *  InvalidationTracker would fire every collector's query on every write.
     *  LocalStore drives the re-query from its own per-logical-table version
     *  counters instead — see the note on LocalStore.observe(). */
    @Query("SELECT * FROM records WHERE tableName = :table")
    suspend fun get(table: String): List<RecordEntity>

    @Query("SELECT * FROM records WHERE tableName = :table AND id = :id LIMIT 1")
    suspend fun getOne(table: String, id: String): RecordEntity?

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

    /** Ids in [table] with a queued outbox UPSERT — read INSIDE the replace
     *  transaction so a write landing between "read pending" and "wipe + insert"
     *  can't be dropped (the TOCTOU the non-transactional hydrate had). */
    @Query("SELECT recordId FROM outbox WHERE recordTable = :table AND op = 'upsert'")
    suspend fun pendingUpsertIds(table: String): List<String>

    /** Ids in [table] with a queued outbox DELETE: the server still has the row
     *  until it lands, and taking the server's copy would resurrect it. */
    @Query("SELECT recordId FROM outbox WHERE recordTable = :table AND op = 'delete'")
    suspend fun pendingDeleteIds(table: String): List<String>

    /** DELETION RECONCILE — the catch-up pull's other half. A cursor pull by
     *  `updated_at` can never see a HARD delete, so the freshness owner
     *  periodically fetches just the server's ids for a table and calls this:
     *  every local row the server no longer has is dropped. NEVER touches a row
     *  with a queued outbox upsert (an offline create the server hasn't seen yet
     *  would otherwise be swept away) nor a [preserveIdsPrefix] row (the
     *  local-only Google `g_` blocks). Pending ids are read in the SAME
     *  transaction as the deletes, so a write landing mid-sweep can't be lost.
     *  Returns how many rows were dropped. */
    @Transaction
    suspend fun retainIds(table: String, serverIds: Set<String>, preserveIdsPrefix: String? = null): Int {
        val pending = pendingUpsertIds(table).toSet()
        val doomed = get(table).filter {
            it.id !in serverIds &&
                it.id !in pending &&
                (preserveIdsPrefix == null || !it.id.startsWith(preserveIdsPrefix))
        }
        for (row in doomed) deleteById(table, row.id)
        return doomed.size
    }

    /** Replace-per-table that KEEPS every local row with a still-pending outbox
     *  upsert — even when the server also has that id. The local op is by
     *  construction the newer state (the engine reconciles it against the server
     *  row BEFORE flushing, so a genuinely superseded op never reaches here), and
     *  the server's copy of such a row is skipped rather than clobbering the edit
     *  the user just made. A row whose local DELETE is still queued stays gone,
     *  as the catch-up keeps it (Android audit 2026-09-23, A11: each launch now
     *  starts with this replace, and a delete a flaky pre-pull flush couldn't land
     *  brought the row back). Pending ids are read in the same transaction. */
    @Transaction
    suspend fun replaceTableKeepingPending(table: String, rows: List<RecordEntity>, preserveIdsPrefix: String? = null) {
        val pending = pendingUpsertIds(table).toSet()
        val deleted = pendingDeleteIds(table).toSet()
        if (pending.isEmpty() && deleted.isEmpty()) {
            replaceTable(table, rows, preserveIdsPrefix)
            return
        }
        val keep = get(table).filter { it.id in pending || (preserveIdsPrefix != null && it.id.startsWith(preserveIdsPrefix)) }
        clearTable(table)
        upsert(keep)
        upsert(rows.filter { it.id !in pending && it.id !in deleted })
    }

    /** [replaceTableKeepingPending] for rows that may be only PART of the table:
     *  upsert them and drop nothing, under the same pending rules. */
    @Transaction
    suspend fun upsertKeepingPending(table: String, rows: List<RecordEntity>) {
        val skip = pendingUpsertIds(table).toHashSet().apply { addAll(pendingDeleteIds(table)) }
        upsert(rows.filter { it.id !in skip })
    }
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

    /** The newest queued upsert for one row (its `base` is the state every later
     *  edit of that row still measures against). */
    @Query("SELECT * FROM outbox WHERE recordTable = :table AND recordId = :id AND op = 'upsert' ORDER BY seq DESC LIMIT 1")
    suspend fun latestUpsert(table: String, id: String): OutboxEntity?

    /** Does this device have a queued DELETE for that row? The catch-up pull
     *  asks before applying a server row: the server still has it (our delete
     *  hasn't landed), and re-applying it would resurrect something the user
     *  removed here. */
    @Query("SELECT COUNT(*) FROM outbox WHERE recordTable = :table AND recordId = :id AND op = 'delete'")
    suspend fun pendingDeleteCount(table: String, id: String): Int

    /** Rewrite an op after a 3-way merge against a newer server row. */
    @Query("UPDATE outbox SET payload = :payload, base = :base WHERE seq = :seq")
    suspend fun rewrite(seq: Long, payload: String?, base: String?)

    /** Re-base one row's upserts queued after [afterSeq] onto [base] (the payload
     *  of the op that just landed). One statement: the drain calls it per landed
     *  op, and reading the whole outbox each time cost O(n²) on a long drain. */
    @Query("UPDATE outbox SET base = :base WHERE recordTable = :table AND recordId = :id AND op = 'upsert' AND seq > :afterSeq")
    suspend fun rebaseLaterUpserts(table: String, id: String, afterSeq: Long, base: String?)
}

@Dao
interface ParkedOutboxDao {
    @Query("SELECT * FROM parked_outbox WHERE userId = :userId ORDER BY seq ASC")
    suspend fun forUser(userId: String): List<ParkedOutboxEntity>

    @Query("SELECT COUNT(*) FROM parked_outbox WHERE userId = :userId")
    suspend fun countForUser(userId: String): Int

    @Insert
    suspend fun insertAll(ops: List<ParkedOutboxEntity>)

    @Query("DELETE FROM parked_outbox WHERE userId = :userId")
    suspend fun clearForUser(userId: String)
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
