package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TagRow
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables

// RealtimeMirror — subscribes to postgres_changes per synced table and applies
// INSERT/UPDATE (upsert local) + DELETE (remove local). One channel per table,
// filtered by user_id (RLS enforces server-side; the filter is client safety).
// calendar_connections is intentionally NOT subscribed — its encrypted creds
// must never be broadcast. Port of the iOS RealtimeMirror.swift.

class RealtimeMirror(
    private val client: SupabaseClient,
    private val store: LocalStore,
    private val scope: CoroutineScope,
) {
    private val channels = mutableListOf<RealtimeChannel>()
    private val jobs = mutableListOf<Job>()

    suspend fun subscribeAll(userId: String) {
        unsubscribeAll()
        subscribe(Tables.TASKS, userId,
            { o -> val m = DbRowCodec.decodeTask(o); store.upsert(Tables.TASKS, m, TaskItem.serializer(), m.id, m.updatedAt) },
            { id -> store.delete(Tables.TASKS, id) })
        subscribe(Tables.SESSIONS, userId,
            { o -> val m = DbRowCodec.decodeSession(o); store.upsert(Tables.SESSIONS, m, Session.serializer(), m.id, m.completedAt) },
            { id -> store.delete(Tables.SESSIONS, id) })
        subscribe(Tables.CAL_BLOCKS, userId,
            { o -> val m = DbRowCodec.decodeCalBlock(o); store.upsert(Tables.CAL_BLOCKS, m, CalBlock.serializer(), m.id) },
            { id -> store.delete(Tables.CAL_BLOCKS, id) })
        subscribe(Tables.CAPTURES, userId,
            { o -> val m = DbRowCodec.decodeCapture(o); store.upsert(Tables.CAPTURES, m, Capture.serializer(), m.id, m.at) },
            { id -> store.delete(Tables.CAPTURES, id) })
        subscribe(Tables.REASON_LOGS, userId,
            { o -> val m = DbRowCodec.decodeReasonLog(o); store.upsert(Tables.REASON_LOGS, m, ReasonLog.serializer(), m.id, m.at) },
            { id -> store.delete(Tables.REASON_LOGS, id) })
        subscribe(Tables.COLLECTIONS, userId,
            { o -> val m = DbRowCodec.decodeCollection(o); store.upsert(Tables.COLLECTIONS, m, ItemCollection.serializer(), m.id) },
            { id -> store.delete(Tables.COLLECTIONS, id) })
        subscribe(Tables.TAGS, userId,
            { o -> val m = DbRowCodec.decodeTag(o); store.upsert(Tables.TAGS, m, TagRow.serializer(), m.id) },
            { id -> store.delete(Tables.TAGS, id) })
        subscribe(Tables.LIFE_AREAS, userId,
            { o -> val m = DbRowCodec.decodeLifeArea(o); store.upsert(Tables.LIFE_AREAS, m, LifeArea.serializer(), m.id) },
            { id -> store.delete(Tables.LIFE_AREAS, id) })
    }

    private suspend fun subscribe(
        tableName: String,
        userId: String,
        onUpsert: suspend (JsonObject) -> Unit,
        onDelete: suspend (String) -> Unit,
    ) {
        val channel = client.channel("unstuck_${tableName}_$userId")
        val flow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = tableName
            filter("user_id", FilterOperator.EQ, userId)
        }
        val job = flow.onEach { action ->
            when (action) {
                is PostgresAction.Insert -> onUpsert(action.record)
                is PostgresAction.Update -> onUpsert(action.record)
                is PostgresAction.Delete -> action.oldRecord["id"]?.let { onDelete(it.jsonPrimitive.content) }
                else -> {}
            }
        }.launchIn(scope)
        runCatching { channel.subscribe() }
            .onFailure { println("[realtime] subscribe $tableName failed: $it") }
        channels += channel
        jobs += job
    }

    suspend fun unsubscribeAll() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        channels.forEach { runCatching { it.unsubscribe() } }
        channels.clear()
    }
}
