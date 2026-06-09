package tech.csalliance.unstuck.sync

import tech.csalliance.unstuck.core.logic.isUuid
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TagRow
import tech.csalliance.unstuck.core.model.TaskItem
import kotlinx.coroutines.flow.first
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.OutboxEntity
import tech.csalliance.unstuck.data.db.Tables

// WriteThrough — optimistic local write + enqueue a server outbox op. The
// local write makes the UI update immediately (Room Flows re-emit); the
// OutboxFlusher drains the op to Supabase (FIFO, dependency-ordered).
// cal_block upserts carry dependsOn = task.id so the parent task flushes
// first. Port of the iOS WriteThrough.swift.

class WriteThrough(private val store: LocalStore) {

    // Google Calendar push hooks — wired by SyncCoordinator. `pushCalBlock` returns
    // the (new/existing) Google event id to persist on the block; both no-op when
    // there's no Google connection. Kept as a seam so :data/:core stay Google-agnostic.
    internal var pushCalBlock: (suspend (CalBlock) -> String?)? = null
    internal var pushCalBlockDelete: (suspend (CalBlock) -> Unit)? = null

    suspend fun upsertTask(t: TaskItem) {
        store.upsert(Tables.TASKS, t, TaskItem.serializer(), t.id, t.updatedAt)
        enqueue("tasks", t.id, "upsert", DbRowCodec.encodeTask(t).toString())
    }

    suspend fun upsertCalBlock(b: CalBlock) {
        store.upsert(Tables.CAL_BLOCKS, b, CalBlock.serializer(), b.id)
        // External Google events (g_ ids) are mirrored read-only — never push them
        // to our cal_blocks table (the row id/shape isn't ours; it would fail forever
        // and stall the outbox) and never re-push them to Google.
        if (b.kind == CalBlockKind.EXTERNAL || b.id.startsWith("g_")) return
        val dependsOn = b.taskId?.let { if (isUuid(it)) it else null } // wait for parent task op
        enqueue("cal_blocks", b.id, "upsert", DbRowCodec.encodeCalBlock(b).toString(), dependsOn)
        // Mirror to Google (best-effort). An INSERT mints an event id we persist on the
        // block so later edits PATCH the same event and a pull won't duplicate it.
        val push = pushCalBlock ?: return
        val eventId = push(b)
        if (!eventId.isNullOrBlank() && eventId != b.externalEventId) {
            val stamped = b.copy(externalEventId = eventId)
            store.upsert(Tables.CAL_BLOCKS, stamped, CalBlock.serializer(), stamped.id)
            enqueue("cal_blocks", stamped.id, "upsert", DbRowCodec.encodeCalBlock(stamped).toString(), dependsOn)
        }
    }

    suspend fun upsertSession(s: Session) {
        store.upsert(Tables.SESSIONS, s, Session.serializer(), s.id, s.completedAt)
        enqueue("sessions", s.id, "upsert", DbRowCodec.encodeSession(s).toString())
    }

    suspend fun upsertCapture(c: Capture) {
        store.upsert(Tables.CAPTURES, c, Capture.serializer(), c.id, c.at)
        // Wait for the parent session row to flush first — a capture taken DURING a
        // session references a session_id (FK) whose `sessions` row is only written at
        // session end. OutboxFlusher holds a dependsOn op while the parent row has a
        // pending op OR doesn't exist in local records yet (the live-session case), so
        // the capture can't push ahead, hit the FK, and be poison-dropped.
        val dependsOn = c.sessionId?.let { if (isUuid(it)) it else null }
        enqueue("captures", c.id, "upsert", DbRowCodec.encodeCapture(c).toString(), dependsOn)
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
    suspend fun deleteCalBlock(id: String) {
        // Read the block first so we can push the Google delete (needs its event id).
        // Skip the read for g_ (external) ids — those are never pushed.
        val block = if (!id.startsWith("g_")) store.blocks().first().firstOrNull { it.id == id } else null
        store.delete(Tables.CAL_BLOCKS, id)
        if (!id.startsWith("g_")) {
            // Cancel any still-queued upsert for this block first. A cal_block upsert
            // carries dependsOn=task.id, so it can be held back while the delete (no
            // dependsOn) flushes ahead of it — which would re-create the block on the
            // server AFTER the delete. Drop the stale upsert so the row stays deleted.
            cancelPendingUpserts(Tables.CAL_BLOCKS, id)
            enqueue(Tables.CAL_BLOCKS, id, "delete", null) // external rows aren't ours
        }
        block?.let { pushCalBlockDelete?.invoke(it) }
    }
    suspend fun deleteTag(id: String) = deleteLocalAndEnqueue(Tables.TAGS, id)
    suspend fun deleteLifeArea(id: String) = deleteLocalAndEnqueue(Tables.LIFE_AREAS, id)
    suspend fun deleteCollection(id: String) = deleteLocalAndEnqueue(Tables.COLLECTIONS, id)
    suspend fun deleteSession(id: String) = deleteLocalAndEnqueue(Tables.SESSIONS, id)
    suspend fun deleteCapture(id: String) = deleteLocalAndEnqueue(Tables.CAPTURES, id)
    suspend fun deleteReasonLog(id: String) = deleteLocalAndEnqueue(Tables.REASON_LOGS, id)

    private suspend fun deleteLocalAndEnqueue(table: String, id: String) {
        store.delete(table, id)
        cancelPendingUpserts(table, id)
        enqueue(table, id, "delete", null)
    }

    /** Drop any queued upsert ops for a row about to be deleted, so a held-back
     *  upsert can't resurrect it on the server after the delete flushes. */
    private suspend fun cancelPendingUpserts(table: String, id: String) {
        store.pending()
            .filter { it.recordTable == table && it.recordId == id && it.op == "upsert" }
            .forEach { store.dequeue(it.seq) }
    }

    private suspend fun enqueue(table: String, id: String, op: String, payload: String?, dependsOn: String? = null) {
        store.enqueue(
            OutboxEntity(op = op, recordTable = table, recordId = id, payload = payload, dependsOn = dependsOn, createdAt = nowMillis()),
        )
    }

    // Injectable seam — overridable in tests (Date.now() is non-deterministic).
    internal var nowMillis: () -> Long = { System.currentTimeMillis() }
}
