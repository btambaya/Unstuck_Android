package tech.csalliance.unstuck.sync

import tech.csalliance.unstuck.core.logic.clampDurationMin
import tech.csalliance.unstuck.core.logic.clampEstimateMin
import tech.csalliance.unstuck.core.logic.isTaskBlock
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
import tech.csalliance.unstuck.core.time.WireTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.OutboxEntity
import tech.csalliance.unstuck.data.db.Tables

// WriteThrough — optimistic local write + enqueue a server outbox op. The
// local write makes the UI update immediately (Room Flows re-emit); the
// OutboxFlusher drains the op to Supabase (FIFO, dependency-ordered).
// cal_block upserts carry dependsOn = task.id so the parent task flushes
// first. Port of the iOS WriteThrough.swift.

class WriteThrough(
    private val store: LocalStore,
    // Where the Google worker runs (the SyncCoordinator's scope in the app).
    googleScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    /** Rule G of the deterministic occurrence ids (stage 2, Ahmad 2026-09-23):
     *  no Google push of a row whose insert is unresolved. Shared with the
     *  OutboxFlusher, which brackets every insert it sends. */
    val mirrorGate = InsertMirrorGate(store)

    /** Every Google call a task block makes, one at a time (see GoogleBlockMirror). */
    internal val googleMirror = GoogleBlockMirror(googleScope, store, mirrorGate) { id, eventId, connectionId ->
        stampCalBlockMapping(id, eventId, connectionId)
    }

    // Google Calendar push hooks — wired by SyncCoordinator. `pushCalBlock` returns
    // the block RE-STAMPED with the Google mapping (external_event_id AND
    // external_connection_id — the server's /disconnect + event_gone cleanup select
    // pushed rows by the connection id, so an unstamped block was invisible to
    // them), or null when nothing changed / no Google connection. Kept as a seam so
    // :data/:core stay Google-agnostic. Both run on [googleMirror]'s one worker,
    // after the local write (stage 2: they used to run inline, one caller at a
    // time, and a confirmed mint's push had nowhere to wait its turn).
    internal var pushCalBlock: (suspend (CalBlock) -> CalBlock?)?
        get() = googleMirror.push
        set(value) { googleMirror.push = value }
    internal var pushCalBlockDelete: (suspend (CalBlock) -> Unit)?
        get() = googleMirror.deleteEvent
        set(value) { googleMirror.deleteEvent = value }

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
        val clamped = if (external) block else block.copy(durationMinutes = clampDurationMin(block.durationMinutes))
        val b = asciiDateTime(clamped)
        if (external) {
            store.upsert(Tables.CAL_BLOCKS, b, CalBlock.serializer(), b.id)
            return
        }
        store.transaction {
            // The Google mapping belongs to the stamp ([stampCalBlockMapping]): every
            // other save carries the row's CURRENT mapping. A save built from a copy
            // read before a stamp landed — a series edit's rewrites, a Schedule, an
            // assistant move, all computed from a snapshot while the Google worker
            // stamps minted days — nulled the event id, and the push that followed
            // INSERTed a second event (parity with iOS build 85, stage 2 review).
            // Read inside the write, so no stamp slips between the read and the save.
            val current = getOne(Tables.CAL_BLOCKS, b.id, CalBlock.serializer())
            val row = if (current == null) b else b.copy(externalEventId = current.externalEventId, externalConnectionId = current.externalConnectionId)
            upsert(Tables.CAL_BLOCKS, row, CalBlock.serializer(), row.id)
            enqueue(outboxOp(Tables.CAL_BLOCKS, row.id, "upsert", DbRowCodec.encodeCalBlock(row).toString(), blockParent(row)))
        }
        runCatching { onEnqueue?.invoke() }
        // Mirror to Google (best-effort), from the row as it is when the worker gets
        // to it. An INSERT mints an event id the stamp persists on the block (with the
        // connection it lives on) so later edits PATCH the same event and a pull won't
        // duplicate it; an event_gone PATCH re-inserts and re-stamps. Rule G: a row
        // whose insert is unresolved is not pushed until the server confirms it.
        googleMirror.requestPush(b.id)
    }

    /** What a MINT did ([insertCalBlockIfAbsent]). */
    enum class MintOutcome {
        /** The row was written and its insert queued. */
        INSERTED,
        /** Rule H, applied locally (`retimeIfTaken` only): the id was already that
         *  day's OPEN occurrence at another time or length — typically minted by a
         *  top-up after the caller read the store. It now has the asked start and
         *  length (those two columns only), queued as `insert_or_retime` so the
         *  server makes the same conditional retime. */
        RETIMED,
        /** That day's open occurrence already has the asked start and length. */
        ALREADY_THERE,
        /** Rule A: the id lives on as a row that is NOT that day's open occurrence
         *  (moved, done or skipped), or any row holds it and this is a maintenance
         *  mint (a top-up never moves a row). Nothing written. */
        HELD;

        /** The day now has the asked occurrence (whatever was written). */
        val landed: Boolean get() = this != HELD
        /** An insert-family op was queued for the row. */
        val queued: Boolean get() = this == INSERTED || this == RETIMED
    }

    /**
     * A MINT: a repeating task's occurrence created with its deterministic id
     * (occurrenceId — stage 2, "same id for same day", Ahmad 2026-09-23).
     * Insert-if-absent end to end — rule A of deterministic-occurrence-ids.md:
     *  • locally it never overwrites a row with that id: a moved, done, skipped or
     *    kept occurrence lives on ([MintOutcome.HELD]). The check, the row write
     *    and the op are ONE Room transaction (and [deleteCalBlock]'s is too), so
     *    two back-to-back top-ups that read the store before either wrote can't
     *    both enqueue it;
     *  • on the server the op is `INSERT … ON CONFLICT (id) DO NOTHING` (`insert`),
     *    plus rule H's conditional retime when the USER asked for this day
     *    ([retimeIfTaken] → `insert_or_retime`).
     * A user's mint whose id is already that day's OPEN occurrence gets rule H
     * here too ([MintOutcome.RETIMED]): the planner would have retimed that row had
     * it seen it, and skipping silently dropped the user's time on every device
     * (parity with iOS build 85's review fix).
     * Otherwise as [upsertCalBlock]: the duration clamp, ASCII digits, dependsOn
     * the parent task, and a g_ / external row is never enqueued. It never pushes
     * to Google inline: "mirror wanted" is recorded BEFORE the op is queued, and
     * the push goes out once the server confirms the insert (rule G).
     */
    suspend fun insertCalBlockIfAbsent(block: CalBlock, retimeIfTaken: Boolean): MintOutcome {
        if (block.kind == CalBlockKind.EXTERNAL || block.id.startsWith("g_")) {
            return store.transaction {
                if (getOne(Tables.CAL_BLOCKS, block.id, CalBlock.serializer()) != null) return@transaction MintOutcome.HELD
                upsert(Tables.CAL_BLOCKS, block, CalBlock.serializer(), block.id)
                MintOutcome.INSERTED
            }
        }
        val b = asciiDateTime(block.copy(durationMinutes = clampDurationMin(block.durationMinutes)))
        val dependsOn = blockParent(b)
        // "Mirror wanted" goes on BEFORE the op is queued: a flush that resolved the
        // insert before this returned would otherwise let a later push through for
        // an insert the server ignored (rule G; the iOS build 85 fix).
        val expected = isTaskBlock(b) && mirrorGate.expectMirror(b.id)
        val outcome = store.transaction {
            val held = getOne(Tables.CAL_BLOCKS, b.id, CalBlock.serializer())
            if (held != null) {
                if (!retimeIfTaken || held.date != b.date || held.done || held.skipped) return@transaction MintOutcome.HELD
                if (held.startTime == b.startTime && held.durationMinutes == b.durationMinutes) return@transaction MintOutcome.ALREADY_THERE
                val next = held.copy(startTime = b.startTime, durationMinutes = b.durationMinutes)
                upsert(Tables.CAL_BLOCKS, next, CalBlock.serializer(), next.id)
                enqueue(outboxOp(Tables.CAL_BLOCKS, next.id, OutboxFlusher.OP_INSERT_OR_RETIME, DbRowCodec.encodeCalBlock(next).toString(), dependsOn))
                return@transaction MintOutcome.RETIMED
            }
            upsert(Tables.CAL_BLOCKS, b, CalBlock.serializer(), b.id)
            val op = if (retimeIfTaken) OutboxFlusher.OP_INSERT_OR_RETIME else OutboxFlusher.OP_INSERT
            enqueue(outboxOp(Tables.CAL_BLOCKS, b.id, op, DbRowCodec.encodeCalBlock(b).toString(), dependsOn))
            MintOutcome.INSERTED
        }
        if (!outcome.queued && expected) mirrorGate.forget(b.id)
        if (outcome.queued) {
            // A push already queued for this id belonged to the row before this
            // insert: after an IGNORED outcome it would stamp this device's copy over
            // another device's row. The insert's outcome pushes it instead.
            googleMirror.dropQueuedPush(b.id)
            runCatching { onEnqueue?.invoke() }
        }
        return outcome
    }

    /** The Google push's write-back: the new event id and connection go onto the
     *  row as it is NOW, in one transaction — the two mapping columns only, never
     *  the pushed copy (an edit made during the Google call survives). Queued as a
     *  plain upsert of that current row. Refused for a row that is gone, or that
     *  has an unresolved insert (rule G: it was deleted and minted again, or a
     *  user's mint retimed it, during the Google call). Parity with iOS build 85. */
    suspend fun stampCalBlockMapping(id: String, eventId: String?, connectionId: String?): MappingStamp {
        val result = store.transaction {
            val row = getOne(Tables.CAL_BLOCKS, id, CalBlock.serializer()) ?: return@transaction MappingStamp.GONE
            // A row whose newest queued op is its delete is going: a stale realtime
            // echo can put it back for a moment, and a stamp queued behind the delete
            // would re-create it on the server.
            if (isBeingDeleted(Tables.CAL_BLOCKS, id)) return@transaction MappingStamp.GONE
            if (row.kind == CalBlockKind.EXTERNAL || id.startsWith("g_")) return@transaction MappingStamp.UNCHANGED
            if (hasInsertFamilyOp(Tables.CAL_BLOCKS, id)) return@transaction MappingStamp.INSERT_UNRESOLVED
            if (row.externalEventId == eventId && row.externalConnectionId == connectionId) return@transaction MappingStamp.UNCHANGED
            val next = asciiDateTime(row.copy(externalEventId = eventId, externalConnectionId = connectionId, durationMinutes = clampDurationMin(row.durationMinutes)))
            upsert(Tables.CAL_BLOCKS, next, CalBlock.serializer(), next.id)
            enqueue(outboxOp(Tables.CAL_BLOCKS, id, "upsert", DbRowCodec.encodeCalBlock(next).toString(), blockParent(next)))
            MappingStamp.STAMPED
        }
        if (result == MappingStamp.STAMPED) runCatching { onEnqueue?.invoke() }
        return result
    }

    /** The server confirmed a mint whose Google push waited on it (rule G): push it
     *  once, from the row as it is then. */
    fun queueConfirmedMirror(id: String) = googleMirror.queueConfirmed(id)

    /** Sign-out: no mirror is owed to the previous account, and nothing queued for
     *  it goes to Google. */
    suspend fun resetMirrors() {
        mirrorGate.reset()
        googleMirror.reset()
    }

    /** The parent task a cal_block op waits for (FK), when it is a real task id. */
    private fun blockParent(b: CalBlock): String? = b.taskId?.let { if (isUuid(it)) it else null }

    /** Date and start time in ASCII digits whatever wrote them: in the phone's own
     *  digits the block never matched a day here and the server refused it, so it
     *  lived on this phone only (Android audit 2026-09-23, A12). */
    private fun asciiDateTime(b: CalBlock): CalBlock =
        b.copy(date = WireTime.asciiDigits(b.date), startTime = WireTime.asciiDigits(b.startTime))

    /** Rewrites, on this phone only, the cal_blocks rows an older build stored with
     *  the phone's own digits in `date` / `start_time`; returns how many. The drain
     *  heals their queued ops, but the cal_blocks pull keeps a row whose op is still
     *  queued, so offline, or behind a parent task op that never lands, the local
     *  "٢٠٢٦-٠٩-٢٣" stayed: it sorts after every ASCII date, so the block left Today
     *  for Upcoming (Android audit 2026-09-23, A12). No op is queued here, as the one
     *  already in the outbox carries the change (quarantined ops are never dropped).
     *  Runs at every start: one read and no write once nothing needs it. */
    suspend fun healNativeDigitBlocks(): Int {
        val stale = store.snapshot(Tables.CAL_BLOCKS, CalBlock.serializer()).filter { asciiDateTime(it) != it }
        if (stale.isEmpty()) return 0
        // Re-read each row inside the transaction so an edit or a pull that landed
        // after the snapshot is healed as it now stands, never overwritten.
        return store.transaction {
            var healed = 0
            for (s in stale) {
                val cur = getOne(Tables.CAL_BLOCKS, s.id, CalBlock.serializer()) ?: continue
                val b = asciiDateTime(cur)
                if (b == cur) continue
                upsert(Tables.CAL_BLOCKS, b, CalBlock.serializer(), b.id)
                healed++
            }
            healed
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
        // External rows (g_) aren't ours: local only, never queued or pushed.
        if (id.startsWith("g_")) {
            store.delete(Tables.CAL_BLOCKS, id)
            return
        }
        // The read (for the Google delete, which needs the event id), the row delete,
        // the cancel of its queued writes and the delete op are ONE transaction, so a
        // mint's check-then-insert ([insertCalBlockIfAbsent]) lands wholly before or
        // after it (stage 2). The cancel covers a queued MINT too: a cal_block write
        // carries dependsOn=task.id, so it can be held back while the delete (no
        // dependsOn) flushes ahead of it — which would re-create the block on the
        // server AFTER the delete.
        val block = store.transaction {
            val b = getOne(Tables.CAL_BLOCKS, id, CalBlock.serializer())
            delete(Tables.CAL_BLOCKS, id)
            for (op in pending()) {
                if (op.recordTable == Tables.CAL_BLOCKS && op.recordId == id && OutboxFlusher.isRowWrite(op.op)) dequeue(op.seq)
            }
            enqueue(outboxOp(Tables.CAL_BLOCKS, id, "delete", null))
            b
        }
        runCatching { onEnqueue?.invoke() }
        mirrorGate.forget(id)   // its cancelled insert will never resolve
        block?.let { googleMirror.requestDelete(it) }
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
                if (op.recordTable == table && op.recordId == id && OutboxFlusher.isRowWrite(op.op)) dequeue(op.seq)
            }
            enqueue(outboxOp(table, id, "delete", null))
        }
        runCatching { onEnqueue?.invoke() }
    }

    /** Drop any queued upsert ops for a row about to be deleted, so a held-back
     *  upsert can't resurrect it on the server after the delete flushes. A queued
     *  mint (stage 2) is a row write too. */
    private suspend fun cancelPendingUpserts(table: String, id: String) {
        store.pending()
            .filter { it.recordTable == table && it.recordId == id && OutboxFlusher.isRowWrite(it.op) }
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
