package tech.csalliance.unstuck.sync

import tech.csalliance.unstuck.core.logic.isUuid
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TagRow
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.OutboxEntity
import tech.csalliance.unstuck.data.db.Tables

// WriteThrough — optimistic local write + enqueue a server outbox op. The
// local write makes the UI update immediately (Room Flows re-emit); the
// OutboxFlusher drains the op to Supabase (FIFO, dependency-ordered).
// cal_block upserts carry dependsOn = task.id so the parent task flushes
// first. Port of the iOS WriteThrough.swift.

class WriteThrough(private val store: LocalStore) {

    suspend fun upsertTask(t: TaskItem) {
        store.upsert(Tables.TASKS, t, TaskItem.serializer(), t.id, t.updatedAt)
        enqueue("tasks", t.id, "upsert", DbRowCodec.encodeTask(t).toString())
    }

    suspend fun upsertCalBlock(b: CalBlock) {
        store.upsert(Tables.CAL_BLOCKS, b, CalBlock.serializer(), b.id)
        val dependsOn = b.taskId?.let { if (isUuid(it)) it else null } // wait for parent task op
        enqueue("cal_blocks", b.id, "upsert", DbRowCodec.encodeCalBlock(b).toString(), dependsOn)
    }

    suspend fun upsertSession(s: Session) {
        store.upsert(Tables.SESSIONS, s, Session.serializer(), s.id, s.completedAt)
        enqueue("sessions", s.id, "upsert", DbRowCodec.encodeSession(s).toString())
    }

    suspend fun upsertCapture(c: Capture) {
        store.upsert(Tables.CAPTURES, c, Capture.serializer(), c.id, c.at)
        enqueue("captures", c.id, "upsert", DbRowCodec.encodeCapture(c).toString())
    }

    suspend fun upsertReasonLog(r: ReasonLog) {
        store.upsert(Tables.REASON_LOGS, r, ReasonLog.serializer(), r.id, r.at)
        enqueue("reason_logs", r.id, "upsert", DbRowCodec.encodeReasonLog(r).toString())
    }

    suspend fun upsertCollection(c: ItemCollection) {
        store.upsert(Tables.COLLECTIONS, c, ItemCollection.serializer(), c.id)
        enqueue("collections", c.id, "upsert", DbRowCodec.encodeCollection(c).toString())
    }

    suspend fun upsertTag(t: TagRow) {
        store.upsert(Tables.TAGS, t, TagRow.serializer(), t.id)
        enqueue("tags", t.id, "upsert", DbRowCodec.encodeTag(t).toString())
    }

    suspend fun upsertLifeArea(a: LifeArea) {
        store.upsert(Tables.LIFE_AREAS, a, LifeArea.serializer(), a.id)
        enqueue("life_areas", a.id, "upsert", DbRowCodec.encodeLifeArea(a).toString())
    }

    suspend fun deleteTask(id: String) = deleteLocalAndEnqueue(Tables.TASKS, id)
    suspend fun deleteCalBlock(id: String) = deleteLocalAndEnqueue(Tables.CAL_BLOCKS, id)
    suspend fun deleteTag(id: String) = deleteLocalAndEnqueue(Tables.TAGS, id)
    suspend fun deleteLifeArea(id: String) = deleteLocalAndEnqueue(Tables.LIFE_AREAS, id)
    suspend fun deleteCollection(id: String) = deleteLocalAndEnqueue(Tables.COLLECTIONS, id)
    suspend fun deleteSession(id: String) = deleteLocalAndEnqueue(Tables.SESSIONS, id)

    private suspend fun deleteLocalAndEnqueue(table: String, id: String) {
        store.delete(table, id)
        enqueue(table, id, "delete", null)
    }

    private suspend fun enqueue(table: String, id: String, op: String, payload: String?, dependsOn: String? = null) {
        store.enqueue(
            OutboxEntity(op = op, recordTable = table, recordId = id, payload = payload, dependsOn = dependsOn, createdAt = nowMillis()),
        )
    }

    // Injectable seam — overridable in tests (Date.now() is non-deterministic).
    internal var nowMillis: () -> Long = { System.currentTimeMillis() }
}
