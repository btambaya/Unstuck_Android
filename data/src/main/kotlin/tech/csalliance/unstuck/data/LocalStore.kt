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
    private val liveDao get() = db.liveSession()

    // --- reactive reads ---

    // Room invalidates per-table, so ANY write to a table re-emits its full row set
    // to every collector. Decode off the main thread (flowOn Default) and drop
    // structurally-identical emissions (distinctUntilChanged) so an unrelated write —
    // or a no-op re-emit — doesn't propagate a fresh decode + recomposition downstream.
    // The chain stays cold (one decode per active collector); semantics are unchanged
    // beyond suppressing duplicate emissions.
    private fun <T> observe(table: String, ser: KSerializer<T>): Flow<List<T>> =
        records.observe(table)
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

    /** Replace-per-table hydrate. cal_blocks preserve local external `g_` rows. */
    suspend fun <T> replace(
        table: String,
        items: List<T>,
        ser: KSerializer<T>,
        id: (T) -> String,
        updatedAt: (T) -> String? = { null },
        preservePrefix: String? = null,
    ) {
        val rows = items.map { RecordEntity(table, id(it), json.encodeToString(ser, it), updatedAt(it)) }
        records.replaceTable(table, rows, preservePrefix)
    }

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
