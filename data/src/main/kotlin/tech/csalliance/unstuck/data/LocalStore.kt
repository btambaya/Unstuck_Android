package tech.csalliance.unstuck.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalendarConnection
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.core.model.LiveSession
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

    // The whole local store lives in ONE `records` table, so Room's per-table
    // InvalidationTracker re-runs EVERY observe() query on ANY write — a single
    // task toggle re-emits the full row set to all ~9 collectors. The expensive
    // part is the per-row JSON decode (O(total rows) per write), so the FIRST
    // distinctUntilChanged is BEFORE the decode, on the raw List<RecordEntity>
    // (a data class → value equality): an unrelated table's write re-emits
    // byte-identical rows for this table, which we drop here and SKIP the decode
    // entirely — the cross-table re-decode the audit flagged. The decode then
    // runs off the main thread (flowOn Default), and a second distinctUntilChanged
    // suppresses the rare case where raw rows differ but the decoded list doesn't.
    // (The 9 cheap indexed query re-runs remain — eliminating those needs a
    // per-domain table split + a live-data migration; deferred as not worth the
    // risk once the decode is gone.) Chain stays cold (one decode per collector).
    private fun <T> observe(table: String, ser: KSerializer<T>): Flow<List<T>> =
        records.observe(table)
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

    suspend fun <T> snapshot(table: String, ser: KSerializer<T>): List<T> =
        records.get(table).mapNotNull { runCatching { json.decodeFromString(ser, it.data) }.getOrNull() }

    /** One row by id (null when absent / undecodable). O(1) — the per-write base
     *  capture in WriteThrough must not decode the whole table. */
    suspend fun <T> getOne(table: String, id: String, ser: KSerializer<T>): T? =
        records.getOne(table, id)?.let { runCatching { json.decodeFromString(ser, it.data) }.getOrNull() }

    // --- writes (local-first; the sync layer mirrors to the server) ---

    fun <T> entity(table: String, model: T, ser: KSerializer<T>, id: String, updatedAt: String? = null): RecordEntity =
        RecordEntity(table, id, json.encodeToString(ser, model), updatedAt)

    suspend fun <T> upsert(table: String, model: T, ser: KSerializer<T>, id: String, updatedAt: String? = null) {
        records.upsertOne(entity(table, model, ser, id, updatedAt))
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
        return true
    }

    suspend fun delete(table: String, id: String) = records.deleteById(table, id)

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
    }

    /** Sign-out / user-switch wipe. Deliberately leaves `parked_outbox` alone: those
     *  are another (or the same, returning) user's un-pushed edits. */
    suspend fun clearAll() {
        records.clearAll()
        outboxDao.clear()
        liveDao.clear()
    }

    // --- outbox ---

    suspend fun enqueue(op: OutboxEntity): Long = outboxDao.enqueue(op)
    suspend fun pending(): List<OutboxEntity> = outboxDao.all()
    suspend fun dequeue(seq: Long) = outboxDao.remove(seq)
    fun pendingCount(): Flow<Int> = outboxDao.count()

    /** The newest queued upsert for a row, if any (its `base` carries forward). */
    suspend fun latestPendingUpsert(table: String, id: String): OutboxEntity? = outboxDao.latestUpsert(table, id)

    /** Rewrite a queued op's payload + base after a 3-way merge. */
    suspend fun rewriteOutbox(seq: Long, payload: String?, base: String?) = outboxDao.rewrite(seq, payload, base)

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
