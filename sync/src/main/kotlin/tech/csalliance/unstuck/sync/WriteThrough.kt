package tech.csalliance.unstuck.sync

import tech.csalliance.unstuck.core.logic.clampDurationMin
import tech.csalliance.unstuck.core.logic.clampEstimateMin
import tech.csalliance.unstuck.core.logic.isUuid
import tech.csalliance.unstuck.core.logic.occurrenceBlockFor
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.core.model.ProfileFact
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TagRow
import tech.csalliance.unstuck.core.model.TaskItem
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
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
    // the block RE-STAMPED with the Google mapping (external_event_id AND
    // external_connection_id — the server's /disconnect + event_gone cleanup select
    // pushed rows by the connection id, so an unstamped block was invisible to
    // them), or null when nothing changed / no Google connection. Kept as a seam so
    // :data/:core stay Google-agnostic.
    internal var pushCalBlock: (suspend (CalBlock) -> CalBlock?)? = null
    internal var pushCalBlockDelete: (suspend (CalBlock) -> Unit)? = null

    /** Fired after EVERY enqueued op (same seam as iOS `setOnEnqueue`). The
     *  SyncCoordinator hooks a debounced flush here so a mid-session edit reaches
     *  the server within ~1.5 s instead of waiting for the next auth event / the
     *  30-min worker — and so the foreground pulls (which flush first anyway) find
     *  an empty outbox. Never throws into the write path. */
    internal var onEnqueue: (() -> Unit)? = null

    suspend fun upsertTask(task: TaskItem) {
        // Every task write passes here, so the estimate is clamped to the server's
        // `estimate_min between 1 and 1440` CHECK first and the local row and the op
        // agree. A row outside it was refused on every flush and quarantined, and as
        // the FK parent it held every block of the task back behind it (parity with
        // iOS build 81, audit 2026-09-22 C4).
        val t = task.copy(estimateMin = clampEstimateMin(task.estimateMin))
        // Capture the merge base BEFORE the local write: the server-shaped row this
        // edit started from. If an earlier edit of the same row is still queued, its
        // base carries forward (the flusher coalesces the older op away, so the base
        // must stay the last SYNCED state, not the intermediate local one). Null for
        // a brand-new row. Hydrator.pruneStaleTaskOps 3-way merges against it.
        // The base read, the local write and the enqueue are ONE transaction, so
        // the prune's re-read sees this edit whole (it joins the row's chain) or
        // not at all (parity with iOS build 81, audit 2026-09-22 C9).
        store.transaction {
            val base = mergeBaseFor(Tables.TASKS, t.id) {
                getOne(Tables.TASKS, t.id, TaskItem.serializer())?.let { DbRowCodec.encodeTask(it).toString() }
            }
            upsert(Tables.TASKS, t, TaskItem.serializer(), t.id, t.updatedAt)
            enqueue(outboxOp("tasks", t.id, "upsert", DbRowCodec.encodeTask(t).toString(), base = base))
        }
        runCatching { onEnqueue?.invoke() }
    }

    /** The base for a new upsert of (table,id): the still-queued upsert's base when
     *  one exists (even if that is null — a local create stays a create), else the
     *  current local row encoded by [current]. */
    private suspend fun LocalStore.Tx.mergeBaseFor(table: String, id: String, current: suspend () -> String?): String? {
        val queued = latestPendingUpsert(table, id)
        return if (queued != null) queued.base else current()
    }

    suspend fun upsertCalBlock(block: CalBlock) {
        // External Google events (g_ ids) are mirrored read-only — never push them
        // to our cal_blocks table (the row id/shape isn't ours; it would fail forever
        // and stall the outbox) and never re-push them to Google.
        val external = block.kind == CalBlockKind.EXTERNAL || block.id.startsWith("g_")
        // Our own blocks are clamped to the server's `duration_minutes between 5 and
        // 1440` CHECK BEFORE the local write, so the row, the op and the Google event
        // agree; a refused block was quarantined and lived on this phone only. A
        // Google mirror keeps its real length (parity with iOS build 81, audit
        // 2026-09-22 C4).
        val b = if (external) block else block.copy(durationMinutes = clampDurationMin(block.durationMinutes))
        store.upsert(Tables.CAL_BLOCKS, b, CalBlock.serializer(), b.id)
        if (external) return
        val dependsOn = b.taskId?.let { if (isUuid(it)) it else null } // wait for parent task op
        enqueue("cal_blocks", b.id, "upsert", DbRowCodec.encodeCalBlock(b).toString(), dependsOn)
        // Mirror to Google (best-effort). An INSERT mints an event id we persist on the
        // block (with the connection it lives on) so later edits PATCH the same event
        // and a pull won't duplicate it; an event_gone PATCH re-inserts and re-stamps.
        val push = pushCalBlock ?: return
        val stamped = push(b) ?: return
        if (stamped.externalEventId != b.externalEventId || stamped.externalConnectionId != b.externalConnectionId) {
            store.upsert(Tables.CAL_BLOCKS, stamped, CalBlock.serializer(), stamped.id)
            enqueue("cal_blocks", stamped.id, "upsert", DbRowCodec.encodeCalBlock(stamped).toString(), dependsOn)
        }
    }

    /** Queue an idempotent shared-collection item RPC (add / update / flag / remove /
     *  promotion) through the OUTBOX instead of firing it and forgetting: an offline or
     *  5xx failure retries on the next drain like any row write, and a server refusal
     *  (RpcRejected) is surfaced via OutboxFlusher.onRpcRejected so the optimistic
     *  local row is rolled back with a visible error. The caller has already applied
     *  the optimistic local write. Keyed on the collection id so per-row ordering
     *  (FIFO, blockedRows) holds across a list's edits. */
    suspend fun enqueueCollectionRpc(collectionId: String, fn: String, params: JsonObject, legacy: Pair<String, JsonObject>? = null) {
        enqueue(Tables.COLLECTIONS, collectionId, OutboxFlusher.OP_RPC, OutboxFlusher.encodeRpc(fn, params, legacy))
    }

    suspend fun enqueueCollectionRpc(collectionId: String, call: CollectionRpc) =
        enqueueCollectionRpc(collectionId, call.fn, call.params, call.legacy?.let { it.fn to it.params })

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
        enqueue("captures", c.id, "upsert", DbRowCodec.encodeCapture(c).toString(), captureParent(c))
    }

    private fun captureParent(c: Capture): String? = c.sessionId?.let { if (isUuid(it)) it else null }

    /** A focus session ended WITHOUT a Session row (cancel_focus, its task deleted
     *  meanwhile, a session on a task shared with me): the captures queued behind
     *  it wait for a parent row that will never exist, so they never left the
     *  phone. Re-queue each still-pending one with session_id = null, replacing
     *  its held op, in one transaction (Android audit 2026-09-23, A14). */
    suspend fun detachCapturesFromSession(sessionId: String) {
        requeueCaptures {
            val detach: (Capture, List<OutboxEntity>) -> Capture? = { c, _ -> if (c.sessionId == sessionId) c.copy(sessionId = null) else null }
            detach
        }
    }

    /** Task [taskId] was deleted on another device while this phone still queued
     *  captures filed on it (focus on it went on meanwhile). captures.task_id
     *  references tasks(id), so each was refused (23503) and quarantined on every
     *  launch. Re-queue them without the task, still waiting on their session's
     *  row. A task still stored here is left alone (Android audit 2026-09-23, A14). */
    suspend fun unlinkCapturesFromTask(taskId: String) {
        requeueCaptures {
            val unlink: (Capture, List<OutboxEntity>) -> Capture? = { c, _ -> if (c.taskId == taskId) c.copy(taskId = null) else null }
            if (getOne(Tables.TASKS, taskId, TaskItem.serializer()) != null) null else unlink
        }
    }

    /** Captures an earlier build left stuck in the outbox (Android audit 2026-09-23,
     *  A14): filed on a repeating task's DAY, whose id is a cal_block id (refused
     *  by captures_task_id_fkey and quarantined on every launch), or held behind a
     *  session that ended without a Session row. The day's id becomes its series;
     *  a session that is neither queued, stored nor live is dropped from the
     *  capture. Only captures still stored here and queued before [queuedBefore]
     *  (before this app run) are touched, so a session ending right now is never
     *  misread as one that never will; after a sign-out the parked ones come back
     *  without their rows and are left alone. Returns how many were re-queued. */
    suspend fun healStrandedCaptures(queuedBefore: Long): Int = requeueCaptures {
        // Nothing an earlier run queued (the usual start): no row reads at all.
        if (pending().none { it.recordTable == Tables.CAPTURES && it.op == "upsert" && it.createdAt < queuedBefore }) return@requeueCaptures null
        val tasks = snapshot(Tables.TASKS, TaskItem.serializer())
        val blocks = snapshot(Tables.CAL_BLOCKS, CalBlock.serializer())
        val sessions = snapshot(Tables.SESSIONS, Session.serializer()).map { it.id }.toSet() +
            pending().filter { it.recordTable == Tables.SESSIONS }.map { it.recordId } +
            listOfNotNull(store.getLiveSession()?.id)
        val heal: (Capture, List<OutboxEntity>) -> Capture? = { c, ops ->
            if (ops.any { it.createdAt >= queuedBefore }) null
            else c.copy(
                taskId = c.taskId?.takeIf { id -> tasks.none { it.id == id } }?.let { occurrenceBlockFor(it, tasks, blocks)?.taskId } ?: c.taskId,
                sessionId = c.sessionId?.takeIf { captureParent(c) == null || it in sessions },
            )
        }
        heal
    }

    /** Re-queue each capture with a queued upsert that [plan]'s edit changes (null
     *  = leave it): its queued upserts are replaced by one carrying the edited row,
     *  waiting on the session that row names, as [upsertCapture] queues it. [plan]
     *  runs once inside the transaction; a null plan changes nothing. */
    private suspend fun requeueCaptures(plan: suspend LocalStore.Tx.() -> ((Capture, List<OutboxEntity>) -> Capture?)?): Int {
        val n = store.transaction {
            val edit = plan() ?: return@transaction 0
            var n = 0
            val queued = pending().filter { it.recordTable == Tables.CAPTURES && it.op == "upsert" }.groupBy { it.recordId }
            for ((id, ops) in queued) {
                val c = getOne(Tables.CAPTURES, id, Capture.serializer()) ?: continue
                val next = edit(c, ops)?.takeIf { it != c } ?: continue
                ops.forEach { dequeue(it.seq) }
                upsert(Tables.CAPTURES, next, Capture.serializer(), next.id, next.at)
                enqueue(outboxOp(Tables.CAPTURES, next.id, "upsert", DbRowCodec.encodeCapture(next).toString(), captureParent(next)))
                n++
            }
            n
        }
        if (n > 0) runCatching { onEnqueue?.invoke() }
        return n
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

    /** Optimistic local save of a profile fact + push. A soft delete ("forget")
     *  is the SAME op with `active=false` — an upsert on `id` that tombstones the
     *  server row (the web does an UPDATE; the result is identical, and this also
     *  tombstones a row the server never received). profile_facts never
     *  hard-deletes. Any older queued upsert for the fact is dropped first so the
     *  outbox carries ONE op per fact — its latest state — regardless of how two
     *  rapid saves (a refine right after a save) interleave with the drain. */
    suspend fun upsertProfileFact(f: ProfileFact) {
        store.upsert(Tables.PROFILE_FACTS, f, ProfileFact.serializer(), f.id, f.updatedAt)
        cancelPendingUpserts(Tables.PROFILE_FACTS, f.id)
        enqueue(Tables.PROFILE_FACTS, f.id, "upsert", DbRowCodec.encodeProfileFact(f).toString())
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
    suspend fun deleteCollection(id: String) {
        // A still-queued item RPC for a list being deleted would only be refused
        // (and roll back a row that no longer exists) — drop it with the upserts.
        cancelPendingRpcs(Tables.COLLECTIONS, id)
        deleteLocalAndEnqueue(Tables.COLLECTIONS, id)
    }
    suspend fun deleteSession(id: String) = deleteLocalAndEnqueue(Tables.SESSIONS, id)
    suspend fun deleteCapture(id: String) = deleteLocalAndEnqueue(Tables.CAPTURES, id)
    suspend fun deleteReasonLog(id: String) = deleteLocalAndEnqueue(Tables.REASON_LOGS, id)

    private suspend fun deleteLocalAndEnqueue(table: String, id: String) {
        // The row delete, the cancel of its queued upserts and the delete op are
        // ONE transaction (parity with iOS build 81 deleteAndEnqueue, audit
        // 2026-09-22 C9). Apart, the task prune's transaction could land after the
        // row delete, find the upserts still queued and save its merged row back:
        // a task the server no longer has.
        store.transaction {
            delete(table, id)
            for (op in pending()) {
                if (op.recordTable == table && op.recordId == id && op.op == "upsert") dequeue(op.seq)
            }
            enqueue(outboxOp(table, id, "delete", null))
        }
        runCatching { onEnqueue?.invoke() }
    }

    /** Drop any queued upsert ops for a row about to be deleted, so a held-back
     *  upsert can't resurrect it on the server after the delete flushes. */
    private suspend fun cancelPendingUpserts(table: String, id: String) {
        store.pending()
            .filter { it.recordTable == table && it.recordId == id && it.op == "upsert" }
            .forEach { store.dequeue(it.seq) }
    }

    private suspend fun cancelPendingRpcs(table: String, id: String) {
        store.pending()
            .filter { it.recordTable == table && it.recordId == id && it.op == OutboxFlusher.OP_RPC }
            .forEach { store.dequeue(it.seq) }
    }

    private suspend fun enqueue(table: String, id: String, op: String, payload: String?, dependsOn: String? = null, base: String? = null) {
        store.enqueue(outboxOp(table, id, op, payload, dependsOn, base))
        // Outside the store write so a hook failure can never lose the local edit.
        runCatching { onEnqueue?.invoke() }
    }

    private fun outboxOp(table: String, id: String, op: String, payload: String?, dependsOn: String? = null, base: String? = null) =
        OutboxEntity(op = op, recordTable = table, recordId = id, payload = payload, dependsOn = dependsOn, createdAt = nowMillis(), base = base)

    // Injectable seam — overridable in tests (Date.now() is non-deterministic).
    internal var nowMillis: () -> Long = { System.currentTimeMillis() }
}
