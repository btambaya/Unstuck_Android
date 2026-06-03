package tech.csalliance.unstuck.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
// preserves locally-cached Google external blocks across the replace. RLS
// auto-scopes reads. Port of the iOS Hydrator.swift.

class Hydrator(private val gateway: SyncGateway, private val store: LocalStore) {

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
            store.replace(Tables.COLLECTIONS, enriched, ItemCollection.serializer(), { it.id })
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
            store.replace(table, models, ser, id, updatedAt)
        }.onFailure { println("[hydrate] $table failed, leaving local intact: $it") }
    }

    private suspend fun hydrateCalBlocks() {
        runCatching {
            val remote = gateway.fetchAll(Tables.CAL_BLOCKS).map { DbRowCodec.decodeCalBlock(it) }
            val localExternal = store.snapshot(Tables.CAL_BLOCKS, CalBlock.serializer()).filter { isExternalBlock(it) }
            val merged = SyncDecision.mergeHydratedCalBlocks(remote, localExternal)
            store.replace(Tables.CAL_BLOCKS, merged, CalBlock.serializer(), { it.id })
        }.onFailure { println("[hydrate] cal_blocks failed, leaving local intact: $it") }
    }
}
