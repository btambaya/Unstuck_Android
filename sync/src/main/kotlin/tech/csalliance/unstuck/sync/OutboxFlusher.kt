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

    suspend fun flush(userId: String) {
        while (true) {
            val all = store.pending()
            if (all.isEmpty()) break
            val pendingIds = all.map { it.recordId }.toSet()
            // An op is held back while its dependsOn rowId still has a pending op.
            val flushable = all.filter { it.dependsOn == null || it.dependsOn !in pendingIds }
            if (flushable.isEmpty()) break
            var progressed = false
            for (op in flushable) {
                val ok = runCatching { apply(op, userId) }
                    .onFailure { println("[outbox] ${op.recordTable}#${op.recordId} failed: $it") }
                    .isSuccess
                if (ok) { store.dequeue(op.seq); progressed = true }
            }
            if (!progressed) break // all remaining ops errored — stop, retry later
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
