package tech.csalliance.unstuck.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
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

    suspend fun hydrate() {
        replace(Tables.TASKS, TaskItem.serializer(), { it.id }, { it.updatedAt }) { DbRowCodec.decodeTask(it) }
        replace(Tables.SESSIONS, Session.serializer(), { it.id }, { it.completedAt }) { DbRowCodec.decodeSession(it) }
        replace(Tables.CAPTURES, Capture.serializer(), { it.id }, { it.at }) { DbRowCodec.decodeCapture(it) }
        replace(Tables.REASON_LOGS, ReasonLog.serializer(), { it.id }, { it.at }) { DbRowCodec.decodeReasonLog(it) }
        replace(Tables.COLLECTIONS, ItemCollection.serializer(), { it.id }) { DbRowCodec.decodeCollection(it) }
        replace(Tables.TAGS, TagRow.serializer(), { it.id }) { DbRowCodec.decodeTag(it) }
        replace(Tables.LIFE_AREAS, LifeArea.serializer(), { it.id }) { DbRowCodec.decodeLifeArea(it) }
        replace(Tables.CALENDAR_CONNECTIONS, CalendarConnection.serializer(), { it.id }, { it.connectedAt }) { DbRowCodec.decodeConnection(it) }
        hydrateCalBlocks()
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
