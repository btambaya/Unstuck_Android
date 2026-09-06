package tech.csalliance.unstuck.sync

import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import tech.csalliance.unstuck.core.logic.isExternalBlock
import tech.csalliance.unstuck.core.time.Time
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalendarConnection
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.core.model.ProfileFact
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TagRow
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables

// Hydrator — pulls every synced table and replaces the local store
// (server-canonical). Per-table error isolation: a table whose fetch fails is
// left intact (mirrors hydrate.ts's `if (res.ok) replace(...)`). cal_blocks
// preserves locally-cached Google external blocks across the replace, and every
// table preserves rows whose outbox upsert is still pending — INCLUDING rows the
// server also returned (an unflushed optimistic edit must never revert to the
// stale server copy; the pending set is read inside the replace transaction).
// RLS auto-scopes reads. Port of the iOS Hydrator.swift.

class Hydrator(private val gateway: SyncRemote, private val store: LocalStore) {

    // Injectable clock for the merged row's updated_at (tests pin it).
    internal var nowIso: () -> String = { Instant.now().toString() }

    /** Reconcile queued `tasks` upsert ops against the server BEFORE the flush.
     *  Two regimes, per op:
     *
     *  • The op carries a `base` (the server-shaped row the edit started from —
     *    schema v2): when the server row has moved on since, 3-WAY MERGE it at the
     *    field level ([mergeTaskRow]) instead of dropping the whole local op — the
     *    fields this device changed survive, everything it didn't change takes the
     *    server's value (a completion made on the web, an accrued total_focused…),
     *    and a field BOTH sides changed goes to the newer writer with a clock-skew
     *    margin ([LWW_SKEW_MS]). The merged row is written to the local store (the
     *    UI shows it at once) and back into the op (with base = the server row we
     *    just absorbed), stamped with a fresh updated_at so other devices' LWW
     *    accepts it.
     *
     *  • No base (a local create, or an op queued by a pre-v2 build): the row-level
     *    rule — drop the op when the server row is STRICTLY newer by more than the
     *    skew margin. Without it, a stale `done=false` re-pushes and clobbers a newer
     *    server change, which the following hydrate then faithfully pulls back as
     *    not-done (the original "completed on web, didn't reflect on the phone").
     *
     *  Only reads the server when task ops are actually queued, so it's free in the
     *  common empty-outbox case. Timestamps compare as instants, never strings. */
    suspend fun pruneStaleTaskOps() {
        val taskOps = store.pending().filter { it.recordTable == Tables.TASKS && it.op == "upsert" }
        if (taskOps.isEmpty()) return
        val serverRows = runCatching {
            gateway.fetchAll(Tables.TASKS).mapNotNull { row ->
                runCatching { DbRowCodec.decodeTask(row).id }.getOrNull()?.let { it to row }
            }.toMap()
        }.getOrElse { return }
        for (op in taskOps) {
            val payload = op.payload ?: continue
            val server = serverRows[op.recordId] ?: continue
            val local = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
            val serverMs = updatedAtMs(server) ?: continue
            val localMs = updatedAtMs(local) ?: continue
            val base = op.base?.let { b -> runCatching { Json.parseToJsonElement(b).jsonObject }.getOrNull() }
            if (base != null) {
                if (stripVolatile(server) == stripVolatile(base)) continue   // server unchanged since we read it → our op is the only change
                val merged = mergeTaskRow(base = base, local = local, server = server, localMs = localMs, serverMs = serverMs, nowIso = nowIso())
                val model = runCatching { DbRowCodec.decodeTask(merged) }.getOrNull() ?: continue
                println("[outbox] 3-way merged tasks op ${op.recordId} against a newer server row")
                store.upsert(Tables.TASKS, model, TaskItem.serializer(), model.id, model.updatedAt)
                store.rewriteOutbox(op.seq, merged.toString(), server.toString())
            } else if (serverMs > localMs + LWW_SKEW_MS) {
                println("[outbox] pruning stale tasks op ${op.recordId} — server is newer (no merge base)")
                store.dequeue(op.seq)
            }
        }
    }

    suspend fun hydrate(userId: String) {
        replace(Tables.TASKS, TaskItem.serializer(), { it.id }, { it.updatedAt }) { DbRowCodec.decodeTask(it) }
        replace(Tables.SESSIONS, Session.serializer(), { it.id }, { it.completedAt }) { DbRowCodec.decodeSession(it) }
        replace(Tables.CAPTURES, Capture.serializer(), { it.id }, { it.at }) { DbRowCodec.decodeCapture(it) }
        replace(Tables.REASON_LOGS, ReasonLog.serializer(), { it.id }, { it.at }) { DbRowCodec.decodeReasonLog(it) }
        hydrateCollections(userId)
        replace(Tables.TAGS, TagRow.serializer(), { it.id }) { DbRowCodec.decodeTag(it) }
        replace(Tables.LIFE_AREAS, LifeArea.serializer(), { it.id }) { DbRowCodec.decodeLifeArea(it) }
        replace(Tables.CALENDAR_CONNECTIONS, CalendarConnection.serializer(), { it.id }, { it.connectedAt }) { DbRowCodec.decodeConnection(it) }
        hydrateCalBlocks()
        // profile_facts — the assistant's cross-device memory. Server tombstones
        // (active=false) land as local tombstones so "forget" propagates everywhere
        // and nothing resurrects; a local save / forget whose push hasn't landed
        // (still-queued upsert) survives the replace exactly like every other
        // table, so an offline "Noted" can't vanish until the flush. Mirrors the
        // web hydrateProfileFacts (remote wins on shared ids, local-only pushed).
        replace(Tables.PROFILE_FACTS, ProfileFact.serializer(), { it.id }, { it.updatedAt }) { DbRowCodec.decodeProfileFact(it) }
    }

    /** Collections + their membership. RLS returns own AND shared-with-me rows;
     *  collection_members (visible to member or owner) supplies each row's
     *  members[] + the current user's myRole. Mirrors hydrate.ts. Also invoked
     *  standalone when a collection_members realtime event fires. */
    suspend fun hydrateCollections(userId: String) {
        runCatching {
            // Per-row tolerant decode (see replace()): a single bad collection row
            // mustn't drop the user's entire list of collections.
            val base = gateway.fetchAll(Tables.COLLECTIONS).mapNotNull { runCatching { DbRowCodec.decodeCollection(it) }.getOrNull() }
            // The membership select is a SEPARATE request: when it alone fails
            // (timeout, transient 5xx) the collections replace must NOT strip every
            // row's members[]/myRole — that flipped each shared list back to "solo"
            // (the owner resumed whole-row upserts over members' atomic edits, and a
            // member's list lost its role until the next pull). Carry the LOCAL
            // membership over per id instead (the realtime mergeKeep rule); a row
            // we've never seen stays unknown → read-only for a non-owner.
            val memberRows = runCatching { gateway.fetchAll("collection_members") }
                .onFailure { println("[hydrate] collection_members failed, keeping local membership: $it") }
                .getOrNull()
            val local = if (memberRows == null) store.collections().first().associateBy { it.id } else emptyMap()
            val byColl = HashMap<String, MutableList<Pair<String, String>>>()   // collectionId -> [(userId, role)]
            for (m in memberRows.orEmpty()) {
                val cid = (m["collection_id"] as? JsonPrimitive)?.contentOrNull ?: continue
                val uid = (m["user_id"] as? JsonPrimitive)?.contentOrNull ?: continue
                val role = (m["role"] as? JsonPrimitive)?.contentOrNull ?: "editor"
                byColl.getOrPut(cid) { mutableListOf() }.add(uid to role)
            }
            val enriched = base.map { c ->
                if (memberRows == null) {
                    val prev = local[c.id]
                    return@map c.copy(
                        members = prev?.members.orEmpty(),
                        myRole = prev?.myRole ?: (if (c.ownerId == userId) "owner" else null),
                    )
                }
                val ms = byColl[c.id].orEmpty()
                val myRole = if (c.ownerId == userId) "owner" else ms.firstOrNull { it.first == userId }?.second
                c.copy(members = ms.map { it.first }, myRole = myRole)
            }
            // Keep optimistic local collections whose outbox upsert hasn't flushed yet
            // (same preservation the generic replace() applies).
            store.replace(Tables.COLLECTIONS, enriched, ItemCollection.serializer(), { it.id }, keepPendingUpserts = true)
        }.onFailure { println("[hydrate] collections failed, leaving local intact: $it") }
    }

    private suspend fun <T> replace(
        table: String,
        ser: KSerializer<T>,
        id: (T) -> String,
        updatedAt: (T) -> String? = { null },
        decode: (JsonObject) -> T,
    ) {
        runCatching {
            // Per-ROW tolerant decode: one un-decodable row (e.g. a forward-compat
            // shape this build can't parse) must not abort the whole table and wipe
            // every good row off the UI. Drop only the bad row.
            val models = gateway.fetchAll(table).mapNotNull { runCatching { decode(it) }.getOrNull() }
            // keepPendingUpserts: every local row with a still-queued outbox upsert
            // survives the replace — whether or not the server also returned that id.
            // Rows NOT on the server (a transient flush failure) would otherwise vanish
            // until the next flush; rows the server DOES have would revert to its stale
            // copy (the edit reappearing only after the next flush + pull). The pending
            // set is read inside the replace transaction, so a write racing this pull
            // can't slip through the old read-then-replace gap.
            store.replace(table, models, ser, id, updatedAt, keepPendingUpserts = true)
        }.onFailure { println("[hydrate] $table failed, leaving local intact: $it") }
    }

    private suspend fun hydrateCalBlocks() {
        runCatching {
            // Per-row tolerant decode (see replace()): a single bad cal_block row
            // mustn't wipe the whole schedule.
            val remote = gateway.fetchAll(Tables.CAL_BLOCKS).mapNotNull { runCatching { DbRowCodec.decodeCalBlock(it) }.getOrNull() }
            val local = store.snapshot(Tables.CAL_BLOCKS, CalBlock.serializer())
            val localExternal = local.filter { isExternalBlock(it) }
            val merged = SyncDecision.mergeHydratedCalBlocks(remote, localExternal)
            // Preserve unsynced optimistic TASK blocks (a pending outbox upsert) — even
            // when the server already has an older copy of that block (a move that
            // hasn't flushed yet must not snap back). See replace().
            store.replace(Tables.CAL_BLOCKS, merged, CalBlock.serializer(), { it.id }, keepPendingUpserts = true)
        }.onFailure { println("[hydrate] cal_blocks failed, leaving local intact: $it") }
    }

    companion object {
        /** Clock-skew tolerance between devices' updated_at stamps (each client
         *  stamps its own wall clock). Within it, "the server is newer" is not
         *  conclusive, so the local op is kept (no base) or the local value wins a
         *  field conflict (merge). */
        internal const val LWW_SKEW_MS = 2_000L

        // Columns that are not part of the user's edit and must not make a base look
        // "changed": the gateway-injected owner + the stamp itself.
        private val VOLATILE_KEYS = setOf("user_id", "updated_at")

        private fun stripVolatile(o: JsonObject): Map<String, JsonElement> = o.filterKeys { it !in VOLATILE_KEYS }

        internal fun updatedAtMs(row: JsonObject): Long? =
            (row["updated_at"] as? JsonPrimitive)?.contentOrNull?.let { Time.parseMillis(it) }

        /** Field-level 3-way merge of a queued task row against a server row that
         *  changed since [base] was read. Per key (union of local + server):
         *   - unchanged locally → the server's value (whatever it is now)
         *   - changed locally, unchanged on the server → the local value
         *   - changed on BOTH → the newer writer, with a skew margin: the server
         *     wins only when it is newer by more than [LWW_SKEW_MS], else local.
         *  `updated_at` is re-stamped with [nowIso] (strictly newer than both, so the
         *  other devices' stale-write guards accept the merged row); `user_id` is
         *  dropped (the gateway re-attaches it). Pure — unit-tested. */
        internal fun mergeTaskRow(
            base: JsonObject,
            local: JsonObject,
            server: JsonObject,
            localMs: Long,
            serverMs: Long,
            nowIso: String,
        ): JsonObject {
            val serverWinsConflicts = serverMs > localMs + LWW_SKEW_MS
            val out = LinkedHashMap<String, JsonElement>()
            for (key in (local.keys + server.keys)) {
                if (key in VOLATILE_KEYS) continue
                val l = local[key]
                val s = server[key]
                val b = base[key]
                val localChanged = l != b
                val serverChanged = s != b
                val chosen = when {
                    !localChanged -> s
                    !serverChanged -> l
                    serverWinsConflicts -> s
                    else -> l
                }
                if (chosen != null) out[key] = chosen
            }
            out["updated_at"] = JsonPrimitive(nowIso)
            return JsonObject(out)
        }
    }
}
