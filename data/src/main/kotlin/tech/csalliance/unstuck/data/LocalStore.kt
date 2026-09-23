package tech.csalliance.unstuck.data

import androidx.room.withTransaction
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalendarConnection
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.core.model.LiveSession
import tech.csalliance.unstuck.core.model.ProfileFact
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TagRow
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Time
import tech.csalliance.unstuck.data.db.LiveSessionEntity
import tech.csalliance.unstuck.data.db.OutboxEntity
import tech.csalliance.unstuck.data.db.ParkedOutboxEntity
import tech.csalliance.unstuck.data.db.RecordEntity
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase

// Typed facade over Room. Stores each synced row as a domain-model JSON blob
// and exposes reactive Flows of the full collections; the app composes these
// with :core (visibleTasks / pickStartNext) in memory — same model as web/iOS.

class LocalStore(private val db: UnstuckDatabase) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val records get() = db.records()
    private val outboxDao get() = db.outbox()
    private val parkedDao get() = db.parkedOutbox()
    private val liveDao get() = db.liveSession()

    // --- reactive reads ---

    // PER-LOGICAL-TABLE INVALIDATION. Every synced row lives in ONE Room
    // `records` table, so Room's per-table InvalidationTracker fires for ALL of
    // them on ANY write: a single task toggle used to re-run EVERY observe()
    // SELECT (measured 24 ms for 5 of the ~10 observed tables on a heavy
    // account — the cal_blocks SELECT alone materialises 4000 rows with their
    // JSON blobs). The distinctUntilChanged below skipped the DECODE, never the
    // QUERY.
    //
    // So the invalidation signal is ours, not Room's: one version counter per
    // LOGICAL table, bumped by [invalidate] from every write path in this class
    // — this class is the only holder of RecordDao, so no write can bypass it.
    // A cal_blocks write no longer touches the tasks query at all. Semantics are
    // otherwise identical: the StateFlow always has a current value so a new
    // collector queries immediately (Room's initial emission), the bump happens
    // AFTER the write/transaction commits, and the same SQL returns the same
    // rows in the same order as before. Both distinctUntilChangeds stay — the
    // first (raw List<RecordEntity>, a data class → value equality) drops a
    // no-op write before the decode, the second the rare case where raw rows
    // differ but the decoded list doesn't. Decode still runs off the main
    // thread. Chain stays cold (one decode per collector).
    private val versions = ConcurrentHashMap<String, MutableStateFlow<Long>>()

    private fun version(table: String): MutableStateFlow<Long> =
        versions.getOrPut(table) { MutableStateFlow(0L) }

    /** Signal "rows of [table] changed" to that table's observers — and only
     *  to them. Called after the write has committed. */
    private fun invalidate(table: String) {
        version(table).update { it + 1 }
    }

    private fun <T> observe(table: String, ser: KSerializer<T>): Flow<List<T>> =
        version(table)
            .map { records.get(table) }
            .distinctUntilChanged()
            .map { rows -> rows.mapNotNull { runCatching { json.decodeFromString(ser, it.data) }.getOrNull() } }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    fun tasks(): Flow<List<TaskItem>> = observe(Tables.TASKS, TaskItem.serializer())
    fun blocks(): Flow<List<CalBlock>> = observe(Tables.CAL_BLOCKS, CalBlock.serializer())
    fun sessions(): Flow<List<Session>> = observe(Tables.SESSIONS, Session.serializer())
    fun captures(): Flow<List<Capture>> = observe(Tables.CAPTURES, Capture.serializer())
    fun reasonLogs(): Flow<List<ReasonLog>> = observe(Tables.REASON_LOGS, ReasonLog.serializer())
    fun collections(): Flow<List<ItemCollection>> = observe(Tables.COLLECTIONS, ItemCollection.serializer())
    fun tags(): Flow<List<TagRow>> = observe(Tables.TAGS, TagRow.serializer())
    fun lifeAreas(): Flow<List<LifeArea>> = observe(Tables.LIFE_AREAS, LifeArea.serializer())
    fun connections(): Flow<List<CalendarConnection>> = observe(Tables.CALENDAR_CONNECTIONS, CalendarConnection.serializer())
    /** Every profile_facts row INCLUDING tombstones (`active=false`) — callers
     *  that read facts filter on `active` (ProfileFactsService does). */
    fun profileFacts(): Flow<List<ProfileFact>> = observe(Tables.PROFILE_FACTS, ProfileFact.serializer())

    /** Reactive rows of a table the facade has no typed accessor for (the :sync
     *  read-only mirrors whose row type lives outside :core, e.g. call_requests).
     *  Same decode-once / distinct chain as the typed flows. */
    fun <T> observeTable(table: String, ser: KSerializer<T>): Flow<List<T>> = observe(table, ser)

    suspend fun <T> snapshot(table: String, ser: KSerializer<T>): List<T> =
        records.get(table).mapNotNull { runCatching { json.decodeFromString(ser, it.data) }.getOrNull() }

    /** Whether a row is held locally and what stamp it was stored with — O(1), no
     *  decode. The catch-up pull reads it to tell "this row is genuinely new to
     *  this device" (absent, or older than the server's) from "the live mirror
     *  already delivered it", which is how deafness is PROVEN rather than guessed.
     *  A null [updatedAt] means the table stores no comparable stamp. */
    data class RowMeta(val exists: Boolean, val updatedAt: String?)

    suspend fun rowMeta(table: String, id: String): RowMeta =
        records.getOne(table, id).let { RowMeta(it != null, it?.updatedAt) }

    /** One row by id (null when absent / undecodable). O(1) — the per-write base
     *  capture in WriteThrough must not decode the whole table. */
    suspend fun <T> getOne(table: String, id: String, ser: KSerializer<T>): T? =
        records.getOne(table, id)?.let { runCatching { json.decodeFromString(ser, it.data) }.getOrNull() }

    // --- writes (local-first; the sync layer mirrors to the server) ---

    fun <T> entity(table: String, model: T, ser: KSerializer<T>, id: String, updatedAt: String? = null): RecordEntity =
        RecordEntity(table, id, json.encodeToString(ser, model), updatedAt)

    suspend fun <T> upsert(table: String, model: T, ser: KSerializer<T>, id: String, updatedAt: String? = null) {
        records.upsertOne(entity(table, model, ser, id, updatedAt))
        invalidate(table)
    }

    /** Last-write-wins guarded upsert for INCOMING remote rows (realtime mirror).
     *  Skips the write — returning false — when the local row is STRICTLY newer
     *  than the incoming row by `updatedAt`, so a delayed/out-of-order remote
     *  echo can't clobber a newer local edit. Timestamps are parsed to epoch
     *  millis (instant compare), never string-compared. When the incoming row
     *  carries no timestamp, or neither can be parsed, the write proceeds (we
     *  can't prove it's stale) — matching the prior unconditional behaviour. */
    suspend fun <T> upsertIfNewer(table: String, model: T, ser: KSerializer<T>, id: String, incomingUpdatedAt: String?): Boolean {
        if (incomingUpdatedAt != null) {
            val incoming = Time.parseMillis(incomingUpdatedAt)
            val localIso = records.getOne(table, id)?.updatedAt
            val local = localIso?.let { Time.parseMillis(it) }
            if (incoming != null && local != null && local > incoming) return false
        }
        records.upsertOne(entity(table, model, ser, id, incomingUpdatedAt))
        invalidate(table)
        return true
    }

    suspend fun delete(table: String, id: String) {
        records.deleteById(table, id)
        invalidate(table)
    }

    /** Replace-per-table hydrate. cal_blocks preserve local external `g_` rows.
     *  [keepPendingUpserts] keeps every local row that still has a queued outbox
     *  upsert — even one the server also returned — so an unflushed optimistic edit
     *  never reverts to the stale server copy; the pending set is read inside the
     *  same transaction as the wipe + insert (no TOCTOU). */
    suspend fun <T> replace(
        table: String,
        items: List<T>,
        ser: KSerializer<T>,
        id: (T) -> String,
        updatedAt: (T) -> String? = { null },
        preservePrefix: String? = null,
        keepPendingUpserts: Boolean = false,
    ) {
        val rows = items.map { RecordEntity(table, id(it), json.encodeToString(ser, it), updatedAt(it)) }
        if (keepPendingUpserts) records.replaceTableKeepingPending(table, rows, preservePrefix)
        else records.replaceTable(table, rows, preservePrefix)
        invalidate(table)
    }

    /** DELETION RECONCILE (a cursor catch-up by `updated_at` can never see a HARD
     *  delete): drop every local row of [table] whose id the server no longer
     *  returns. Rows with a queued outbox upsert — and [preservePrefix] rows (the
     *  local-only Google `g_` blocks) — are never touched. Returns how many were
     *  dropped, and bumps THIS table's version counter (and only this one) when
     *  anything changed, so the removal reaches the UI without a relaunch. */
    suspend fun retainIds(table: String, serverIds: Set<String>, preservePrefix: String? = null): Int {
        val dropped = records.retainIds(table, serverIds, preservePrefix)
        if (dropped > 0) invalidate(table)
        return dropped
    }

    /** How many rows this table holds locally — the deletion reconcile's
     *  "is the server's empty answer plausible?" guard reads it. */
    suspend fun countRows(table: String): Int = records.get(table).size

    /** Sign-out / user-switch wipe. Deliberately leaves `parked_outbox` alone: those
     *  are another (or the same, returning) user's un-pushed edits. */
    suspend fun clearAll() {
        records.clearAll()
        outboxDao.clear()
        liveDao.clear()
        // Every table lost its rows — signal each one that has an observer
        // (a table with no counter yet has no collector to tell).
        for (v in versions.values) v.update { it + 1 }
    }

    // --- outbox ---

    suspend fun enqueue(op: OutboxEntity): Long = outboxDao.enqueue(op)
    suspend fun pending(): List<OutboxEntity> = outboxDao.all()
    suspend fun dequeue(seq: Long) = outboxDao.remove(seq)
    fun pendingCount(): Flow<Int> = outboxDao.count()

    /** The newest queued upsert for a row, if any (its `base` carries forward). */
    suspend fun latestPendingUpsert(table: String, id: String): OutboxEntity? = outboxDao.latestUpsert(table, id)

    /** True when a local DELETE for that row is still queued. */
    suspend fun hasPendingDelete(table: String, id: String): Boolean =
        outboxDao.pendingDeleteCount(table, id) > 0

    /** Rewrite a queued op's payload + base after a 3-way merge. */
    suspend fun rewriteOutbox(seq: Long, payload: String?, base: String?) = outboxDao.rewrite(seq, payload, base)

    // --- one transaction over rows + outbox ---

    /** The reads and writes the sync engine must make as ONE unit (the Android
     *  side of iOS's `OutboxStore.*(in:)` open-connection helpers). Only valid
     *  inside [transaction]'s block. Row writes do not signal observers here: a
     *  collector that re-queried before the commit would read the old rows and
     *  never be told again, so [transaction] bumps each written table once the
     *  commit has landed. */
    inner class Tx internal constructor() {
        internal val touched = LinkedHashSet<String>()

        suspend fun pending(): List<OutboxEntity> = outboxDao.all()
        suspend fun latestPendingUpsert(table: String, id: String): OutboxEntity? = outboxDao.latestUpsert(table, id)
        suspend fun enqueue(op: OutboxEntity): Long = outboxDao.enqueue(op)
        suspend fun dequeue(seq: Long) = outboxDao.remove(seq)
        suspend fun rewriteOutbox(seq: Long, payload: String?, base: String?) = outboxDao.rewrite(seq, payload, base)

        suspend fun <T> getOne(table: String, id: String, ser: KSerializer<T>): T? = this@LocalStore.getOne(table, id, ser)
        suspend fun <T> snapshot(table: String, ser: KSerializer<T>): List<T> = this@LocalStore.snapshot(table, ser)

        suspend fun <T> upsert(table: String, model: T, ser: KSerializer<T>, id: String, updatedAt: String? = null) {
            records.upsertOne(entity(table, model, ser, id, updatedAt))
            touched += table
        }

        /** Replace-per-table with rows the caller already merged (it read the
         *  stored rows and the outbox in this same transaction). */
        suspend fun <T> replace(table: String, items: List<T>, ser: KSerializer<T>, id: (T) -> String, updatedAt: (T) -> String? = { null }) {
            records.replaceTable(table, items.map { entity(table, it, ser, id(it), updatedAt(it)) })
            touched += table
        }
    }

    /** Run [block] as ONE Room transaction. A concurrent writer's transaction
     *  lands wholly before or wholly after it, never in between (audit
     *  2026-09-22 C8/C9: an edit queued while the prune's fetch was in flight,
     *  or a list edit acked mid-hydrate, fell into such a gap). */
    suspend fun <R> transaction(block: suspend Tx.() -> R): R {
        val tx = Tx()
        val result = db.withTransaction { tx.block() }
        for (table in tx.touched) invalidate(table)
        return result
    }

    // --- parked outbox (un-pushed ops kept across sign-out, per user) ---

    /** Move every queued op into [userId]'s parking lot and empty the outbox.
     *  Returns how many were parked. Called at sign-out AFTER the bounded drain
     *  so the following cache wipe can't discard them. */
    suspend fun parkOutbox(userId: String): Int {
        val ops = outboxDao.all()
        if (ops.isEmpty()) return 0
        parkedDao.insertAll(
            ops.map {
                ParkedOutboxEntity(
                    userId = userId, op = it.op, recordTable = it.recordTable, recordId = it.recordId,
                    payload = it.payload, dependsOn = it.dependsOn, createdAt = it.createdAt, base = it.base,
                )
            },
        )
        outboxDao.clear()
        return ops.size
    }

    /** Re-queue [userId]'s parked ops (original order, ahead of nothing — the
     *  outbox is empty right after the sign-in wipe) and clear the lot. Returns
     *  how many came back. */
    suspend fun restoreParkedOutbox(userId: String): Int {
        val parked = parkedDao.forUser(userId)
        if (parked.isEmpty()) return 0
        for (p in parked) {
            outboxDao.enqueue(
                OutboxEntity(
                    op = p.op, recordTable = p.recordTable, recordId = p.recordId, payload = p.payload,
                    dependsOn = p.dependsOn, createdAt = p.createdAt, base = p.base,
                ),
            )
        }
        parkedDao.clearForUser(userId)
        return parked.size
    }

    suspend fun parkedCount(userId: String): Int = parkedDao.countForUser(userId)

    /** Drop [userId]'s parked ops (account deleted — the writes are moot). */
    suspend fun clearParked(userId: String) = parkedDao.clearForUser(userId)

    // --- live session ---

    fun liveSession(): Flow<LiveSession?> = liveDao.observe().map { e ->
        e?.let { runCatching { json.decodeFromString(LiveSession.serializer(), it.data) }.getOrNull() }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun getLiveSession(): LiveSession? =
        liveDao.get()?.let { runCatching { json.decodeFromString(LiveSession.serializer(), it.data) }.getOrNull() }

    suspend fun setLiveSession(live: LiveSession?) {
        if (live == null) liveDao.clear()
        else liveDao.set(LiveSessionEntity(0, json.encodeToString(LiveSession.serializer(), live)))
    }
}
