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

class OutboxFlusher(private val gateway: SyncGateway, private val store: LocalStore) {

    // Per-op consecutive-failure tally (keyed by outbox seq). After FAIL_CAP
    // failures an op is treated as a poison pill and dropped so it can't wedge
    // its dependents (e.g. a cal_block whose parent task upsert keeps failing)
    // forever. Resets on app restart, so a transient failure still gets retries.
    private val failCounts = mutableMapOf<Long, Int>()

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
            val all = store.pending()   // FIFO by seq
            if (all.isEmpty()) break
            val localIds = mutableMapOf<String, Set<String>>()   // per-pass snapshot cache
            val flushable = flushableOps(all) { table -> localIds.getOrPut(table) { localRowIds(table) } }
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
                        println("[outbox] dropping poison op $rowKey after $n failures")
                        store.dequeue(op.seq); failCounts.remove(op.seq); progressed = true
                        // Also drop ops that depended on this row — their FK parent will
                        // never exist server-side, so flushing them would push a dangling
                        // reference (or fail forever in turn). Don't orphan them.
                        all.filter { it.dependsOn == op.recordId }.forEach { dep ->
                            println("[outbox] dropping orphaned dependent ${dep.recordTable}:${dep.recordId}")
                            store.dequeue(dep.seq); failCounts.remove(dep.seq)
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
