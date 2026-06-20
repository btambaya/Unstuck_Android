package tech.csalliance.unstuck.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.OutboxEntity
import tech.csalliance.unstuck.data.db.Tables

// OutboxFlusher — drains the offline write-ahead queue to Supabase in op-seq
// order, honouring dependency ordering (a cal_block op stays queued until its
// parent task op flushes). The payload is the server-row JSON written by
// WriteThrough, sent through the gateway (which attaches user_id). On success
// the op is removed; if all remaining ops error the pass stops (retried on the
// next reconnect/sign-in). Port of the iOS OutboxFlusher.swift.

class OutboxFlusher(private val gateway: SyncRemote, private val store: LocalStore) {

    // Per-op consecutive-failure tally (keyed by outbox seq). After FAIL_CAP
    // failures an op is QUARANTINED (dead-lettered) so it can't wedge its
    // dependents (e.g. a cal_block whose parent task upsert keeps failing)
    // forever. Resets on app restart, so a transient failure still gets retries.
    private val failCounts = mutableMapOf<Long, Int>()

    // Dead-lettered op seqs: hit FAIL_CAP, so we stop RETRYING them this session,
    // but we DO NOT dequeue them — the op stays in the outbox so the next hydrate
    // still preserves the user's local row (pendingLocalRows keys off the outbox).
    // Dropping the op here is the data-loss bug: a transiently-failing write would
    // evaporate the row on the following replace. In-memory, so a restart retries.
    private val deadLettered = mutableSetOf<Long>()

    // One drain at a time. flush() is reachable from four concurrent contexts
    // (auth handle, SyncWorker, calendar connect, sign-out); overlapping drains
    // could re-apply an older payload AFTER a newer one for the same row
    // (server keeps the stale state, both ops dequeued) and race failCounts.
    private val mutex = Mutex()

    suspend fun flush(userId: String, currentUserId: () -> String? = { userId }) = mutex.withLock {
        while (true) {
            // Bail if the signed-in user changed mid-drain (sign-out + sign-in to
            // a different account). RLS already blocks a cross-account write, but
            // this avoids confusing FK/RLS errors + a stuck op. Mirrors the web
            // bridge's intendedUserId guard.
            if (currentUserId() != userId) return@withLock
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
                val ok = try {
                    apply(op, userId)
                    true
                } catch (e: CancellationException) {
                    // A cancelled drain (sign-out's 5s timeout, WorkManager stopping
                    // the SyncWorker) is normal control flow, not a server rejection —
                    // abort without burning failCounts toward the poison-drop cap.
                    throw e
                } catch (e: Throwable) {
                    println("[outbox] $rowKey failed: $e")
                    false
                }
                if (ok) {
                    store.dequeue(op.seq); failCounts.remove(op.seq); progressed = true
                } else {
                    blockedRows.add(rowKey)
                    val n = (failCounts[op.seq] ?: 0) + 1
                    failCounts[op.seq] = n
                    if (n >= FAIL_CAP) {
                        // QUARANTINE, don't drop. The op stays in the outbox so the row
                        // it represents is still preserved across the next hydrate; we
                        // just stop retrying it this session (a restart resets and tries
                        // again). Dropping it here would let a transiently-failing write
                        // evaporate the user's local row on the following replace.
                        println("[outbox] WARNING quarantining op $rowKey after $n failures — keeping local row, will retry after restart")
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

    /** Ids present in the local records cache for [table] (decoded snapshot).
     *  Only the dependsOn parent tables are ever queried. */
    private suspend fun localRowIds(table: String): Set<String> = when (table) {
        Tables.TASKS -> store.snapshot(Tables.TASKS, TaskItem.serializer()).map { it.id }.toSet()
        Tables.SESSIONS -> store.snapshot(Tables.SESSIONS, Session.serializer()).map { it.id }.toSet()
        else -> emptySet()
    }

    companion object {
        private const val FAIL_CAP = 5

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

    private suspend fun apply(op: OutboxEntity, userId: String) {
        if (op.op == "delete") {
            gateway.delete(op.recordTable, op.recordId)
            return
        }
        val payload = op.payload ?: return
        gateway.upsert(op.recordTable, Json.parseToJsonElement(payload).jsonObject, userId)
    }
}
