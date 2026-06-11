package tech.csalliance.unstuck.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import tech.csalliance.unstuck.core.logic.isExternalBlock
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalendarConnection
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TagRow
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables

// Hydrator — pulls every synced table and replaces the local store
// (server-canonical). Per-table error isolation: a table whose fetch fails is
// left intact (mirrors hydrate.ts's `if (res.ok) replace(...)`). cal_blocks
// preserves locally-cached Google external blocks across the replace, and every
// table preserves rows whose outbox upsert is still pending (an unflushed
// optimistic write must not vanish from the UI). RLS auto-scopes reads. Port of
// the iOS Hydrator.swift.

class Hydrator(private val gateway: SyncGateway, private val store: LocalStore) {

    /** Drop queued `tasks` upsert ops the server already supersedes (its row is
     *  STRICTLY newer by updatedAt). Run BEFORE the flush: without it, a stale
     *  local op — e.g. an old `done=false` edit still sitting in the outbox —
     *  re-pushes and clobbers a newer server change (a completion made on the
     *  WEB), which the following hydrate then faithfully pulls back as not-done.
     *  This is the load-bearing fix for "completed on web, didn't reflect on the
     *  phone". Only reads the server when task ops are actually queued, so it's
     *  free in the common empty-outbox case. (Genuine offline edits — whose op is
     *  newer than the server — survive and flush normally.) */
    suspend fun pruneStaleTaskOps() {
        val taskOps = store.pending().filter { it.recordTable == Tables.TASKS && it.op == "upsert" }
        if (taskOps.isEmpty()) return
        val serverUpdatedAt = runCatching {
            gateway.fetchAll(Tables.TASKS).associate { val t = DbRowCodec.decodeTask(it); t.id to t.updatedAt }
        }.getOrElse { return }
        for (op in taskOps) {
            val payload = op.payload ?: continue
            val serverTime = serverUpdatedAt[op.recordId] ?: continue
            val localTime = runCatching {
                DbRowCodec.decodeTask(Json.parseToJsonElement(payload).jsonObject).updatedAt
            }.getOrNull() ?: continue
            if (serverTime > localTime) {
                println("[outbox] pruning stale tasks op ${op.recordId} — server is newer")
                store.dequeue(op.seq)
            }
        }
    }

    suspend fun hydrate(userId: String) {
        replace(Tables.TASKS, TaskItem.serializer(), { it.id }, { it.updatedAt }) { DbRowCodec.decodeTask(it) }
        replace(Tables.SESSIONS, Session.serializer(), { it.id }, { it.completedAt }) { DbRowCodec.decodeSession(it) }
        replace(Tables.CAPTURES, Capture.serializer(), { it.id }, { it.at }) { DbRowCodec.decodeCapture(it) }
        replace(Tables.REASON_LOGS, ReasonLog.serializer(), { it.id }, { it.at }) { DbRowCodec.decodeReasonLog(it) }
        hydrateCollections(userId)
        replace(Tables.TAGS, TagRow.serializer(), { it.id }) { DbRowCodec.decodeTag(it) }
        replace(Tables.LIFE_AREAS, LifeArea.serializer(), { it.id }) { DbRowCodec.decodeLifeArea(it) }
        replace(Tables.CALENDAR_CONNECTIONS, CalendarConnection.serializer(), { it.id }, { it.connectedAt }) { DbRowCodec.decodeConnection(it) }
        hydrateCalBlocks()
    }

    /** Collections + their membership. RLS returns own AND shared-with-me rows;
     *  collection_members (visible to member or owner) supplies each row's
     *  members[] + the current user's myRole. Mirrors hydrate.ts. Also invoked
     *  standalone when a collection_members realtime event fires. */
    suspend fun hydrateCollections(userId: String) {
        runCatching {
            val base = gateway.fetchAll(Tables.COLLECTIONS).map { DbRowCodec.decodeCollection(it) }
            val memberRows = runCatching { gateway.fetchAll("collection_members") }.getOrDefault(emptyList())
            val byColl = HashMap<String, MutableList<Pair<String, String>>>()   // collectionId -> [(userId, role)]
            for (m in memberRows) {
                val cid = (m["collection_id"] as? JsonPrimitive)?.contentOrNull ?: continue
                val uid = (m["user_id"] as? JsonPrimitive)?.contentOrNull ?: continue
                val role = (m["role"] as? JsonPrimitive)?.contentOrNull ?: "editor"
                byColl.getOrPut(cid) { mutableListOf() }.add(uid to role)
            }
            val enriched = base.map { c ->
                val ms = byColl[c.id].orEmpty()
                val myRole = if (c.ownerId == userId) "owner" else ms.firstOrNull { it.first == userId }?.second
                c.copy(members = ms.map { it.first }, myRole = myRole)
            }
            // Keep optimistic local collections whose outbox upsert hasn't flushed yet
            // (same preservation the generic replace() applies).
            val localPending = pendingLocalRows(Tables.COLLECTIONS, ItemCollection.serializer(), { it.id }, enriched.map { it.id }.toSet())
            store.replace(Tables.COLLECTIONS, enriched + localPending, ItemCollection.serializer(), { it.id })
        }.onFailure { println("[hydrate] collections failed, leaving local intact: $it") }
    }

    private suspend fun <T> replace(
        table: String,
        ser: KSerializer<T>,
        id: (T) -> String,
        updatedAt: (T) -> String? = { null },
        decode: (JsonObject) -> T,
    ) {
        runCatching {
            val models = gateway.fetchAll(table).map(decode)
            // Preserve optimistic local rows with a still-pending outbox upsert (e.g.
            // a transient flush failure followed by a successful fetch): they're not
            // in `models`, so the replace would wipe them off the UI until the next
            // successful flush. Same rule hydrateCalBlocks applies for cal_blocks.
            val localPending = pendingLocalRows(table, ser, id, models.map(id).toSet())
            store.replace(table, models + localPending, ser, id, updatedAt)
        }.onFailure { println("[hydrate] $table failed, leaving local intact: $it") }
    }

    /** Local rows for [table] that still have a queued outbox upsert and are not
     *  in the server set — re-added across the replace so an unflushed write
     *  doesn't vanish from the UI. */
    private suspend fun <T> pendingLocalRows(
        table: String,
        ser: KSerializer<T>,
        id: (T) -> String,
        serverIds: Set<String>,
    ): List<T> {
        val pendingIds = store.pending()
            .filter { it.recordTable == table && it.op == "upsert" }
            .map { it.recordId }.toSet()
        if (pendingIds.isEmpty()) return emptyList()
        return store.snapshot(table, ser).filter { id(it) in pendingIds && id(it) !in serverIds }
    }

    private suspend fun hydrateCalBlocks() {
        runCatching {
            val remote = gateway.fetchAll(Tables.CAL_BLOCKS).map { DbRowCodec.decodeCalBlock(it) }
            val local = store.snapshot(Tables.CAL_BLOCKS, CalBlock.serializer())
            val localExternal = local.filter { isExternalBlock(it) }
            val merged = SyncDecision.mergeHydratedCalBlocks(remote, localExternal)
            // Preserve unsynced optimistic TASK blocks (a pending outbox upsert): they're
            // in neither `remote` nor `localExternal`, so the replace would wipe them off
            // the UI until the next flush. Keep any not already present from the server.
            val pendingIds = store.pending()
                .filter { it.recordTable == Tables.CAL_BLOCKS && it.op == "upsert" }
                .map { it.recordId }.toSet()
            val mergedIds = merged.map { it.id }.toSet()
            val localPending = local.filter { it.id in pendingIds && it.id !in mergedIds && !isExternalBlock(it) }
            store.replace(Tables.CAL_BLOCKS, merged + localPending, CalBlock.serializer(), { it.id })
        }.onFailure { println("[hydrate] cal_blocks failed, leaving local intact: $it") }
    }
}
