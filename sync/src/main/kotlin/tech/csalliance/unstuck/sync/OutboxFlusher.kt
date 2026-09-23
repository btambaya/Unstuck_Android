package tech.csalliance.unstuck.sync

import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import tech.csalliance.unstuck.core.logic.clampDurationMin
import tech.csalliance.unstuck.core.logic.clampEstimateMin
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.WireTime
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.OutboxEntity
import tech.csalliance.unstuck.data.db.Tables

// OutboxFlusher — drains the offline write-ahead queue to Supabase in op-seq
// order, honouring dependency ordering (a cal_block op stays queued until its
// parent task op flushes). The payload is the server-row JSON written by
// WriteThrough, sent through the gateway (which attaches user_id). On success
// the op is removed; if all remaining ops error the pass stops (retried on the
// next reconnect/sign-in). Port of the iOS OutboxFlusher.swift.

class OutboxFlusher(
    private val gateway: SyncRemote,
    private val store: LocalStore,
    /** Rule G (stage 2): every insert-family send is bracketed here, so a Google
     *  push of the row waits for the server's answer. Null in tests that don't
     *  mint. */
    private val mirrorGate: InsertMirrorGate? = null,
) {

    /** An outbox `rpc` op the server REFUSED (4xx): it has been dequeued (retrying
     *  can't change the answer) — the app layer must roll the optimistic local write
     *  back (re-pull the row) and tell the user. Never throws into the drain. */
    var onRpcRejected: (suspend (op: OutboxEntity, error: RpcRejected) -> Unit)? = null

    /** An insert-family op (a MINT, stage 2) resolved and was dequeued: inserted,
     *  retimed (rule H — the server row is already in the local store) or ignored.
     *  Fired once the drain has let go of its lock, so the listener may flush or
     *  write freely; it must not block (the coordinator only queues a Google push).
     *  Never throws into the drain. */
    var onInsertResolved: ((InsertResolution) -> Unit)? = null

    // Per-op tally of SERVER REFUSALS (keyed by outbox seq). After FAIL_CAP an
    // op is QUARANTINED (dead-lettered) so it can't wedge its dependents (e.g. a
    // cal_block whose parent task upsert keeps being refused) forever. Offline,
    // timeout, 5xx and auth-refresh failures never count ([classifyFailure]).
    // Resets on app restart and when the network returns ([releaseQuarantine]).
    private val failCounts = mutableMapOf<Long, Int>()

    // Dead-lettered op seqs: hit FAIL_CAP, so we stop RETRYING them this session,
    // but we DO NOT dequeue them — the op stays in the outbox so the next hydrate
    // still preserves the user's local row (pendingLocalRows keys off the outbox).
    // Dropping the op here is the data-loss bug: a transiently-failing write would
    // evaporate the row on the following replace. In-memory, so a restart retries.
    private val deadLettered = mutableSetOf<Long>()

    // Set from the network watcher's thread, applied by the next drain under the
    // mutex (the tallies above are only touched there).
    @Volatile private var quarantineReleased = false

    /** Connectivity came back: give quarantined ops a fresh round of FAIL_CAP
     *  tries on the next drain instead of waiting for a relaunch — a refusal
     *  seen through a captive portal, or an FK refusal whose parent has landed
     *  since, can succeed now (Android audit 2026-09-23, A10). */
    fun releaseQuarantine() { quarantineReleased = true }

    // One drain at a time. flush() is reachable from four concurrent contexts
    // (auth handle, SyncWorker, calendar connect, sign-out); overlapping drains
    // could re-apply an older payload AFTER a newer one for the same row
    // (server keeps the stale state, both ops dequeued) and race failCounts.
    private val mutex = Mutex()

    suspend fun flush(userId: String, currentUserId: () -> String? = { userId }) {
        val resolved = ArrayList<InsertResolution>()
        try {
            mutex.withLock { drain(userId, currentUserId, resolved) }
        } finally {
            // Outside the lock, and even when the drain was cut short: each of these
            // ops is already dequeued, and its "mirror wanted" already consumed.
            val hook = onInsertResolved
            if (hook != null) {
                for (r in resolved) runCatching { hook(r) }.onFailure { println("[outbox] insert-resolved hook failed: $it") }
            }
        }
    }

    private suspend fun drain(userId: String, currentUserId: () -> String?, resolved: MutableList<InsertResolution>) {
        if (quarantineReleased) {
            quarantineReleased = false
            deadLettered.clear(); failCounts.clear()
        }
        while (true) {
            // Bail if the signed-in user changed mid-drain (sign-out + sign-in to
            // a different account). RLS already blocks a cross-account write, but
            // this avoids confusing FK/RLS errors + a stuck op. Mirrors the web
            // bridge's intendedUserId guard.
            if (currentUserId() != userId) return
            val raw = store.pending()   // FIFO by seq
            if (raw.isEmpty()) break
            // Per-(table,id) coalescing: when two whole-row upserts for the SAME
            // row are queued, the older one carries a stale full payload that would
            // overwrite the newer one server-side (both flush, last-applied wins =
            // the older if order slips). Drop every superseded older upsert so only
            // the latest survives. tasks already had pruneStaleTaskOps; this covers
            // cal_blocks/sessions/captures/etc. Deletes are never coalesced.
            val superseded = supersededUpsertSeqs(raw)
            if (superseded.isNotEmpty()) {
                // A newer upsert for a row supersedes an older one — including a
                // dead-lettered one: a fresh edit replaces the stuck write and is
                // eligible to flush again, so clear its quarantine too.
                superseded.forEach { store.dequeue(it); failCounts.remove(it); deadLettered.remove(it) }
            }
            val all = raw.filter { it.seq !in superseded }
            if (all.isEmpty()) break
            val localIds = mutableMapOf<String, Set<String>>()   // per-pass snapshot cache
            // Quarantined (dead-lettered) ops are NOT retried — skip them when
            // choosing what to flush. They remain in `all` (and the outbox) so they
            // still gate their dependents and keep their local row preserved.
            val flushable = flushableOps(all) { table -> localIds.getOrPut(table) { localRowIds(table) } }
                .filter { it.seq !in deadLettered }
            if (flushable.isEmpty()) break
            var progressed = false
            // Once an op for a given row fails this pass, skip that row's LATER ops
            // so a newer edit isn't applied (then clobbered when the older one
            // retries) — preserve per-row order / last-writer-wins.
            val blockedRows = mutableSetOf<String>()
            for (op in flushable) {
                val rowKey = "${op.recordTable}:${op.recordId}"
                if (rowKey in blockedRows) continue
                // Rule G: from here until resolve the row counts as unresolved even
                // once the op is dequeued, so no Google push slips into that gap.
                val gate = if (isInsertFamily(op.op)) mirrorGate else null
                gate?.begin(op.recordId)
                var answer: InsertAnswer? = null
                val ok = try {
                    answer = apply(op, userId)
                    true
                } catch (e: CancellationException) {
                    // A cancelled drain (sign-out's 5s timeout, WorkManager stopping
                    // the SyncWorker) is normal control flow, not a server rejection —
                    // abort without burning failCounts toward the poison-drop cap.
                    gate?.let { withContext(NonCancellable) { it.abandon(op.recordId) } }
                    throw e
                } catch (e: RpcRejected) {
                    // TERMINAL: the server refused the RPC (RLS / not a member / gone).
                    // Drop the op — there is no local row to preserve for an rpc op —
                    // and hand the rollback to the app layer. Later ops on the same
                    // row still run (each RPC is an independent idempotent step).
                    println("[outbox] $rowKey rpc rejected (${e.status}): ${e.message} — dropping + rolling back")
                    store.dequeue(op.seq); failCounts.remove(op.seq); progressed = true
                    runCatching { onRpcRejected?.invoke(op, e) }
                        .onFailure { println("[outbox] rpc rollback hook failed: $it") }
                    continue
                } catch (e: Throwable) {
                    // The op stays queued, so the outbox keeps the row unresolved.
                    gate?.abandon(op.recordId)
                    if (classifyFailure(e) == FlushFailure.TRANSIENT) {
                        // Offline, timeout, 5xx, a 401 while the token refreshes: the
                        // op is fine, the network isn't. Hold the row for this pass
                        // (per-row order) and retry on the next drain. NEVER counted:
                        // five failed drains on a train used to dead-letter a valid
                        // write and its blocks until the process died (Android audit
                        // 2026-09-23, A10; parity with iOS).
                        println("[outbox] $rowKey transient failure, will retry: $e")
                        blockedRows.add(rowKey)
                        continue
                    }
                    println("[outbox] $rowKey rejected: $e")
                    false
                }
                if (ok) {
                    if (op.recordTable == Tables.TASKS && op.op == "upsert") landTaskUpsert(op) else store.dequeue(op.seq)
                    failCounts.remove(op.seq); progressed = true
                    answer?.let { a ->
                        // Rule H's answer is shown at once, like a realtime echo of that
                        // UPDATE: the other device's occurrence, now at this device's
                        // time, with ITS Google mapping — so a push that waited on this
                        // answer PATCHes that event instead of inserting a second one.
                        a.serverRow?.let { showRetimedRow(op.recordTable, op.recordId, it) }
                        val wanted = gate?.resolve(op.recordId, a.outcome) ?: false
                        resolved += InsertResolution(op.recordTable, op.recordId, a.outcome, wanted)
                    }
                } else {
                    blockedRows.add(rowKey)
                    val n = (failCounts[op.seq] ?: 0) + 1
                    failCounts[op.seq] = n
                    if (n >= FAIL_CAP) {
                        // QUARANTINE, don't drop. The op stays in the outbox so the row
                        // it represents is still preserved across the next hydrate; we
                        // just stop retrying it (a restart, or the network returning,
                        // resets and tries again). Dropping it here would let the
                        // user's local row evaporate on the following replace.
                        println("[outbox] WARNING quarantining op $rowKey after $n refusals — keeping local row, will retry after restart / reconnect")
                        deadLettered.add(op.seq)
                        // Also quarantine ops that depended on this row — their FK parent
                        // isn't on the server yet, so flushing them would fail in turn.
                        // Keep them queued (don't orphan/drop them) so their rows survive.
                        all.filter { it.dependsOn == op.recordId }.forEach { dep ->
                            println("[outbox] quarantining dependent ${dep.recordTable}:${dep.recordId} (parent $rowKey stuck)")
                            deadLettered.add(dep.seq)
                            blockedRows.add("${dep.recordTable}:${dep.recordId}")
                        }
                    }
                }
            }
            if (!progressed) break // all remaining ops errored — stop, retry later
        }
    }

    /** How a failed send counts toward FAIL_CAP ([classifyFailure]). */
    internal enum class FlushFailure {
        /** No definitive answer (offline, timeout, 5xx, auth refresh, rate
         *  limit): retry on the next drain, never counted. */
        TRANSIENT,
        /** The server understood these exact bytes and refused them (a
         *  PostgREST 4xx), or the payload can't even be parsed: retrying the
         *  same op can't succeed, so it counts. */
        REJECTED,
    }

    /** Dequeue a `tasks` upsert that landed, and re-base the row's edits queued
     *  behind it (made while it was in flight, so they carry ITS base) onto the
     *  payload that landed, in one transaction. Their base was the state before
     *  it: if one of them then failed to send, the next prune measured it against
     *  that older state, and an Undo of a Mark done that had landed read as
     *  "unchanged" and took the server's done=true (parity with iOS build 81,
     *  where each queued edit's base is the edit before it; audit 2026-09-22 C9). */
    private suspend fun landTaskUpsert(op: OutboxEntity) = store.transaction {
        dequeue(op.seq)
        rebaseLaterUpserts(op.recordTable, op.recordId, op.seq, op.payload)
    }

    /** Ids present in the local records cache for [table] (decoded snapshot).
     *  Only the dependsOn parent tables are ever queried. */
    private suspend fun localRowIds(table: String): Set<String> = when (table) {
        Tables.TASKS -> store.snapshot(Tables.TASKS, TaskItem.serializer()).map { it.id }.toSet()
        Tables.SESSIONS -> store.snapshot(Tables.SESSIONS, Session.serializer()).map { it.id }.toSet()
        else -> emptySet()
    }

    companion object {
        /** Outbox op kind for a queued RPC (vs "upsert" / "delete"). */
        const val OP_RPC = "rpc"

        /** A MINT (stage 2, deterministic occurrence ids, Ahmad 2026-09-23): a
         *  repeating task's occurrence written insert-if-absent — `INSERT … ON
         *  CONFLICT (id) DO NOTHING`, never over another device's row. The horizon
         *  top-up and the assistant's tail fill. Stored as text: no Room migration. */
        const val OP_INSERT = "insert"

        /** A MINT the USER asked for (an editor save, Schedule, Start repeating,
         *  set_task_recurrence, a first placement): [OP_INSERT], and when the server
         *  ignores it, rule H's conditional retime of that day's open occurrence. */
        const val OP_INSERT_OR_RETIME = "insert_or_retime"

        /** The insert family. Never coalesced with an upsert either way
         *  ([supersededUpsertSeqs] only matches "upsert"): it must resolve, or rule
         *  G's gate never opens. */
        fun isInsertFamily(op: String): Boolean = op == OP_INSERT || op == OP_INSERT_OR_RETIME

        /** A write of the row's content (an upsert or a mint) — what a delete
         *  cancels and a pull must not lay the server's copy over. */
        fun isRowWrite(op: String): Boolean = op == "upsert" || isInsertFamily(op)

        /** Encode an rpc op payload: `{"fn": name, "params": {...}, "legacy"?: {fn, params}}`.
         *  [legacy] is the pre-migration signature to fall back to when the server
         *  says the primary function doesn't exist (PGRST202). */
        fun encodeRpc(fn: String, params: JsonObject, legacy: Pair<String, JsonObject>? = null): String {
            val m = mutableMapOf<String, kotlinx.serialization.json.JsonElement>("fn" to JsonPrimitive(fn), "params" to params)
            legacy?.let { (lfn, lp) -> m["legacy"] = JsonObject(mapOf("fn" to JsonPrimitive(lfn), "params" to lp)) }
            return JsonObject(m).toString()
        }

        fun decodeRpc(payload: String): Pair<String, JsonObject>? = decodeRpcCall(payload)?.let { it.first to it.second }

        /** (fn, params, legacy?) */
        internal fun decodeRpcCall(payload: String): Triple<String, JsonObject, Pair<String, JsonObject>?>? = runCatching {
            val o = Json.parseToJsonElement(payload).jsonObject
            val fn = (o["fn"] as? JsonPrimitive)?.contentOrNull ?: return null
            val params = o["params"] as? JsonObject ?: JsonObject(emptyMap())
            val legacy = (o["legacy"] as? JsonObject)?.let { l ->
                val lfn = (l["fn"] as? JsonPrimitive)?.contentOrNull ?: return@let null
                lfn to (l["params"] as? JsonObject ?: JsonObject(emptyMap()))
            }
            Triple(fn, params, legacy)
        }.getOrNull()

        /** PostgREST "function not found" (PGRST202 → 404): the server predates the
         *  migration that introduced the primary signature. */
        internal fun isFunctionMissing(e: RpcRejected): Boolean =
            e.status == 404 && (e.message?.contains("PGRST202") == true || e.message?.contains("Could not find the function", ignoreCase = true) == true)

        private const val FAIL_CAP = 5

        /** Only a DEFINITE refusal counts; anything ambiguous is transient —
         *  retrying costs a request, miscounting cost the user's edits (parity
         *  with iOS SyncDecision.classifyFlushFailure, Android audit 2026-09-23,
         *  A10). supabase-kt 3.0.3 wraps every network failure in
         *  HttpRequestException, lets HttpRequestTimeoutException (an IOException)
         *  through, and reports a PostgREST error as a RestException carrying only
         *  the HTTP status (not the SQLSTATE), so the status decides, as iOS does
         *  for a non-PostgREST body. */
        internal fun classifyFailure(e: Throwable): FlushFailure = when (e) {
            is RestException -> if (isRefusalStatus(e.statusCode)) FlushFailure.REJECTED else FlushFailure.TRANSIENT
            is ResponseException -> if (isRefusalStatus(e.response.status.value)) FlushFailure.REJECTED else FlushFailure.TRANSIENT
            // A payload that won't parse (SerializationException is one) never will.
            is IllegalArgumentException -> FlushFailure.REJECTED
            else -> FlushFailure.TRANSIENT
        }

        /** 4xx is a refusal, except the auth-refresh / timing / rate-limit
         *  statuses a retry can clear; 5xx is the server's problem. */
        private fun isRefusalStatus(status: Int): Boolean =
            status in 400..499 && status !in setOf(401, 403, 408, 425, 429)

        /** [row] held to the server's CHECKs on the columns this client writes
         *  (migration 001): `tasks.estimate_min between 1 and 1440`,
         *  `cal_blocks.duration_minutes between 5 and 1440`. WriteThrough clamps
         *  every new write, but the queued payload is sent as stored — so an op a
         *  build without the clamp queued was refused, quarantined and re-sent on
         *  every launch for ever, stranding the task's blocks behind it. Clamping
         *  here heals those ops on the next drain (parity with iOS build 81,
         *  audit 2026-09-22 C4). */
        internal fun clampServerChecks(table: String, row: JsonObject): JsonObject {
            val key = when (table) {
                Tables.TASKS -> "estimate_min"
                Tables.CAL_BLOCKS -> "duration_minutes"
                else -> return row
            }
            val raw = (row[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return row
            val clamped = if (table == Tables.TASKS) clampEstimateMin(raw) else clampDurationMin(raw)
            return if (clamped == raw) row else JsonObject(row + (key to JsonPrimitive(clamped)))
        }

        /** A cal_blocks row's `date` and `start_time` in ASCII digits. Builds before
         *  the fix formatted them in the phone's locale, so an Arabic, Persian,
         *  Bengali, Marathi, Nepali or Burmese phone queued "۲۰۲۶-۰۹-۲۳" / "۱۰:۳۰":
         *  the server refuses both (a `date` column, the start_time CHECK), the op
         *  was quarantined and re-sent on every launch, and the block never left
         *  the phone. Normalising here heals those ops on the next drain
         *  (Android audit 2026-09-23, A12). */
        internal fun asciiBlockDateTime(table: String, row: JsonObject): JsonObject {
            if (table != Tables.CAL_BLOCKS) return row
            var out = row
            for (key in listOf("date", "start_time")) {
                val v = (out[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                val ascii = WireTime.asciiDigits(v)
                if (ascii != v) out = JsonObject(out + (key to JsonPrimitive(ascii)))
            }
            return out
        }

        /** Seqs of upsert ops that a LATER upsert for the same (table,id) makes
         *  redundant. Keeps only the highest-seq upsert per row; returns the older
         *  ones to drop. A `delete` op resets a row's run (an upsert after a delete
         *  is a genuine re-create, not a duplicate), so coalescing never spans a
         *  delete. Deletes themselves are never collapsed. */
        internal fun supersededUpsertSeqs(all: List<OutboxEntity>): Set<Long> {
            // Walk in seq order; track the most recent upsert seq per row and, when a
            // newer upsert arrives, mark the previous one superseded. A delete clears
            // the tracked upsert so a later re-create upsert isn't dropped.
            val latestUpsert = HashMap<String, Long>()   // "table:id" -> seq
            val drop = HashSet<Long>()
            for (op in all.sortedBy { it.seq }) {
                val key = "${op.recordTable}:${op.recordId}"
                if (op.op == "upsert") {
                    latestUpsert.remove(key)?.let { drop.add(it) }
                    latestUpsert[key] = op.seq
                } else {
                    latestUpsert.remove(key)
                }
            }
            return drop
        }

        /** The table a child table's dependsOn rowId lives in (its FK parent):
         *  cal_block upserts wait on their task row, capture upserts wait on
         *  their session row. */
        internal fun dependsOnParentTable(childTable: String): String? = when (childTable) {
            Tables.CAL_BLOCKS -> Tables.TASKS
            Tables.CAPTURES -> Tables.SESSIONS
            else -> null
        }

        /** The subset of [all] that is safe to push this pass. An op is held back
         *  while its dependsOn rowId still has a pending op, OR while the parent
         *  row doesn't exist in local records yet — e.g. a capture taken during a
         *  LIVE focus session: the sessions row is only written at session end, so
         *  pushing the capture now would hit the `captures.session_id` FK on every
         *  drain and burn failCounts toward a poison-drop of a perfectly valid
         *  write. A parent row present locally with no pending op has been flushed
         *  or hydrated, so the FK is satisfied server-side. */
        internal suspend fun flushableOps(
            all: List<OutboxEntity>,
            localRowIds: suspend (table: String) -> Set<String>,
        ): List<OutboxEntity> {
            val pendingIds = all.map { it.recordId }.toSet()
            return all.filter { op ->
                val dep = op.dependsOn ?: return@filter true
                if (dep in pendingIds) return@filter false
                val parent = dependsOnParentTable(op.recordTable) ?: return@filter true
                dep in localRowIds(parent)
            }
        }
    }

    /** A resolved insert-family op: its outcome, and the server row rule H moved. */
    private class InsertAnswer(val outcome: InsertOutcome, val serverRow: JsonObject? = null)

    /** Send one op. Returns the answer for an insert-family op, null otherwise. */
    private suspend fun apply(op: OutboxEntity, userId: String): InsertAnswer? {
        if (op.op == "delete") {
            gateway.delete(op.recordTable, op.recordId)
            return null
        }
        val payload = op.payload ?: return null
        if (op.op == OP_RPC) {
            // Shared-collection item edit: an idempotent server-side RPC (upsert-by-id
            // add / set flag / update / remove — a replay is a no-op), so it is safe to
            // retry across drains exactly like a row upsert. Payload = {fn, params}.
            val (fn, params, legacy) = decodeRpcCall(payload) ?: return null
            try {
                gateway.rpc(fn, params)
            } catch (e: RpcRejected) {
                if (legacy != null && isFunctionMissing(e)) gateway.rpc(legacy.first, legacy.second) else throw e
            }
            return null
        }
        // A queued mint heals exactly like an upsert: an op from a build without the
        // clamp / the ASCII digits goes out as the server accepts it.
        val row = clampServerChecks(op.recordTable, asciiBlockDateTime(op.recordTable, Json.parseToJsonElement(payload).jsonObject))
        if (isInsertFamily(op.op)) return applyInsert(op, row, userId)
        gateway.upsert(op.recordTable, row, userId)
        return null
    }

    /** A MINT (stage 2): insert-if-absent, and for `insert_or_retime` whose insert
     *  the server ignored, rule H's conditional retime with the op's date, start
     *  and length. An op missing any of those resolves as ignored — the insert was
     *  already refused, and nothing else is safe to send. */
    private suspend fun applyInsert(op: OutboxEntity, row: JsonObject, userId: String): InsertAnswer {
        if (gateway.insertIfAbsent(op.recordTable, row, userId)) return InsertAnswer(InsertOutcome.INSERTED)
        if (op.op != OP_INSERT_OR_RETIME) return InsertAnswer(InsertOutcome.IGNORED)
        val date = (row["date"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val start = (row["start_time"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val duration = (row["duration_minutes"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (date == null || start == null || duration == null) return InsertAnswer(InsertOutcome.IGNORED)
        val server = gateway.retimeIfOpen(op.recordTable, op.recordId, date, start, duration)
            ?: return InsertAnswer(InsertOutcome.IGNORED)
        return InsertAnswer(InsertOutcome.RETIMED, server)
    }

    /** Write a RETIMED answer's server row into the local store, unless the row has
     *  a pending op of its own (a newer local edit, or a delete — that op is the
     *  newer intent and flushes over it). Best-effort: the next pull brings it too. */
    private suspend fun showRetimedRow(table: String, id: String, serverRow: JsonObject) {
        if (table != Tables.CAL_BLOCKS) return
        try {
            val block = DbRowCodec.decodeCalBlock(serverRow)
            if (block.id != id) return
            store.transaction {
                if (!hasPendingOp(table, id)) upsert(table, block, CalBlock.serializer(), block.id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("[outbox] retimed $table:$id not shown locally: $e")
        }
    }

}
