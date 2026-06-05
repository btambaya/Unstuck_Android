package tech.csalliance.unstuck.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.OutboxEntity

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

    suspend fun flush(userId: String, currentUserId: () -> String? = { userId }) {
        while (true) {
            // Bail if the signed-in user changed mid-drain (sign-out + sign-in to
            // a different account). RLS already blocks a cross-account write, but
            // this avoids confusing FK/RLS errors + a stuck op. Mirrors the web
            // bridge's intendedUserId guard.
            if (currentUserId() != userId) return
            val all = store.pending()   // FIFO by seq
            if (all.isEmpty()) break
            val pendingIds = all.map { it.recordId }.toSet()
            // An op is held back while its dependsOn rowId still has a pending op.
            val flushable = all.filter { it.dependsOn == null || it.dependsOn !in pendingIds }
            if (flushable.isEmpty()) break
            var progressed = false
            // Once an op for a given row fails this pass, skip that row's LATER ops
            // so a newer edit isn't applied (then clobbered when the older one
            // retries) — preserve per-row order / last-writer-wins.
            val blockedRows = mutableSetOf<String>()
            for (op in flushable) {
                val rowKey = "${op.recordTable}:${op.recordId}"
                if (rowKey in blockedRows) continue
                val ok = runCatching { apply(op, userId) }
                    .onFailure { println("[outbox] $rowKey failed: $it") }
                    .isSuccess
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

    private companion object { const val FAIL_CAP = 5 }

    private suspend fun apply(op: OutboxEntity, userId: String) {
        if (op.op == "delete") {
            gateway.delete(op.recordTable, op.recordId)
            return
        }
        val payload = op.payload ?: return
        gateway.upsert(op.recordTable, Json.parseToJsonElement(payload).jsonObject, userId)
    }
}
