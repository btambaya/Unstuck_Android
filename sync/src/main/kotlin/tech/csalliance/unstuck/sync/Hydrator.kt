package tech.csalliance.unstuck.sync

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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
import tech.csalliance.unstuck.data.db.OutboxEntity
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
     *  Two regimes, decided by each row's oldest queued op (see the chain note
     *  below):
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
     *  common empty-outbox case. Timestamps compare as instants, never strings.
     *
     *  A row's queued edits are judged as ONE chain, by its oldest op (parity with
     *  iOS build 81, audit 2026-09-22 C9). Each later op carries the head's base,
     *  so merging each op on its own against the server row took the server's
     *  value for every field that op didn't change against THAT base: an Undo
     *  queued behind a Mark done that reached the server but reported a failure
     *  merged back to done. On a conflict the head is merged onto the server row
     *  and every later op's OWN diff (against the op before it, as queued) onto the
     *  previous merged row. The ops are re-read and rewritten in one transaction
     *  after the fetch, so an edit queued while the fetch was in flight joins the
     *  chain instead of flushing unmerged. */
    suspend fun pruneStaleTaskOps() {
        if (store.pending().none { isLiveTaskUpsert(it) }) return
        val serverRows = runCatching {
            gateway.fetchAll(Tables.TASKS).mapNotNull { row ->
                runCatching { DbRowCodec.decodeTask(row).id }.getOrNull()?.let { it to row }
            }.toMap()
        }.getOrElse {
            // The sign-out drain's timeout cancels this read: rethrow, so the flush
            // after it doesn't start with the ops unpruned (iOS drainBeforeSignOut).
            if (it is CancellationException) throw it
            return
        }
        val now = nowIso()
        store.transaction {
            val chains = LinkedHashMap<String, MutableList<OutboxEntity>>()
            for (op in pending()) {
                if (isLiveTaskUpsert(op)) chains.getOrPut(op.recordId) { mutableListOf() }.add(op)   // seq order
            }
            for ((rowId, chain) in chains) {
                val server = serverRows[rowId] ?: continue
                // One row's failure must not stop every other row's merge.
                val plan = try {
                    planTaskChain(chain, server, now)
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    println("[outbox] tasks op chain $rowId not reconciled: $t")
                    continue
                }
                // A failed WRITE is not caught: it rolls the whole prune back and
                // aborts the flush after it. iOS rolls just the row back to a
                // savepoint; Room has none (a failed nested transaction rolls back
                // the outer one), and committing half a chain would flush a tail
                // that never took the server's changes in (audit 2026-09-22 C9).
                for (seq in plan.drops) dequeue(seq)
                for ((seq, merged) in plan.rewrites) rewriteOutbox(seq, merged, server.toString())
                plan.local?.let { upsert(Tables.TASKS, it, TaskItem.serializer(), it.id, it.updatedAt) }
            }
        }
    }

    private fun isLiveTaskUpsert(op: OutboxEntity) = op.recordTable == Tables.TASKS && op.op == "upsert"

    /** One row's chain reconcile, worked out in full before anything is written:
     *  the ops to drop, each merged op's new payload (its base becomes the server
     *  row), and the local row to save. */
    private class ChainPlan {
        val drops = mutableListOf<Long>()
        val rewrites = mutableListOf<Pair<Long, String>>()
        var local: TaskItem? = null
    }

    /** Judge + merge one row's queued edits (seq order) against the server row.
     *  Writes nothing: [pruneStaleTaskOps] applies the plan.
     *
     *  Every rewritten op gets base = the server row, not the previous merged row
     *  as on iOS: the flusher coalesces a row's upserts, so only the tail is ever
     *  sent, and its payload already holds every earlier edit. A tail based on a
     *  merged row that never reached the server would drop those edits at the
     *  next prune. */
    private fun planTaskChain(chain: List<OutboxEntity>, server: JsonObject, now: String): ChainPlan {
        val plan = ChainPlan()
        val serverMs = updatedAtMs(server) ?: return plan
        var onto: JsonObject? = null          // the previous op's merged row, once the chain is in conflict
        var previous: JsonObject? = null      // the previous op's payload as queued
        var lastMerged: Pair<Long, TaskItem>? = null
        for (op in chain) {
            val payload = op.payload ?: continue
            val local = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
            val localMs = updatedAtMs(local) ?: continue
            val diffBase = previous
            previous = local
            val prevMerged = onto
            if (prevMerged != null && diffBase != null) {
                // This op's own change is its payload against the op before it.
                val merged = mergeTaskRow(base = diffBase, local = local, server = prevMerged, localMs = localMs, serverMs = serverMs, nowIso = now)
                val model = runCatching { DbRowCodec.decodeTask(merged) }.getOrNull() ?: continue
                plan.rewrites += op.seq to merged.toString()
                onto = merged
                lastMerged = op.seq to model
                continue
            }
            // The chain's head.
            val base = op.base?.let { b -> runCatching { Json.parseToJsonElement(b).jsonObject }.getOrNull() }
            if (base != null) {
                if (stripVolatile(server) == stripVolatile(base)) return plan   // server unchanged since we read it → the chain is the only change
                val merged = mergeTaskRow(base = base, local = local, server = server, localMs = localMs, serverMs = serverMs, nowIso = now)
                val model = runCatching { DbRowCodec.decodeTask(merged) }.getOrNull() ?: return plan
                println("[outbox] 3-way merged tasks op ${op.recordId} against a newer server row")
                plan.rewrites += op.seq to merged.toString()
                onto = merged
                lastMerged = op.seq to model
            } else if (serverMs > localMs + LWW_SKEW_MS) {
                println("[outbox] pruning stale tasks op ${op.recordId} — server is newer (no merge base)")
                plan.drops += op.seq   // the next op becomes the head
            } else {
                return plan
            }
        }
        // The local row follows the chain's LAST op (the UI shows the server's
        // changes to the fields this device didn't touch), but only when that op
        // was merged; otherwise the local row already is its intent.
        val (seq, model) = lastMerged ?: return plan
        if (seq == chain.last().seq) plan.local = model
        return plan
    }

    /** The newest SERVER stamp seen per table during the last [hydrate] — the
     *  seed for the catch-up's high-water marks. Server values only: seeding from
     *  a local row would adopt this device's wall clock and a fast clock would
     *  then skip real server rows for ever. A table whose fetch FAILED is absent
     *  (it isn't converged, so it must not get a mark). */
    private val serverMaxima = LinkedHashMap<String, String>()

    /**
     * Full server-canonical pull (the cold-start / no-cursor path). Returns the
     * per-table server maxima so the caller can seed the catch-up cursors. Every
     * row in the snapshot is at or below its table's maximum, so a later catch-up
     * starting there can only re-see rows, never skip one.
     */
    suspend fun hydrate(userId: String): Map<String, String> {
        serverMaxima.clear()
        replace(Tables.TASKS, TaskItem.serializer(), { it.id }, { it.updatedAt }) { DbRowCodec.decodeTask(it) }
        replace(Tables.SESSIONS, Session.serializer(), { it.id }, { it.completedAt }) { DbRowCodec.decodeSession(it) }
        replace(Tables.CAPTURES, Capture.serializer(), { it.id }, { it.at }) { DbRowCodec.decodeCapture(it) }
        replace(Tables.REASON_LOGS, ReasonLog.serializer(), { it.id }, { it.at }) { DbRowCodec.decodeReasonLog(it) }
        hydrateCollections(userId)
        replace(Tables.TAGS, TagRow.serializer(), { it.id }) { DbRowCodec.decodeTag(it) }
        replace(Tables.LIFE_AREAS, LifeArea.serializer(), { it.id }) { DbRowCodec.decodeLifeArea(it) }
        // profile_facts — the assistant's cross-device memory. Server tombstones
        // (active=false) land as local tombstones so "forget" propagates everywhere
        // and nothing resurrects; a local save / forget whose push hasn't landed
        // (still-queued upsert) survives the replace exactly like every other
        // table, so an offline "Noted" can't vanish until the flush. Mirrors the
        // web hydrateProfileFacts (remote wins on shared ids, local-only pushed).
        replace(Tables.PROFILE_FACTS, ProfileFact.serializer(), { it.id }, { it.updatedAt }) { DbRowCodec.decodeProfileFact(it) }
        hydrateCallRequests()
        hydrateNonCursorTables()
        pushTimezone()
        return LinkedHashMap(serverMaxima)
    }

    /** cal_blocks + calendar_connections have NO monotonic column server-side, so
     *  a cursor pull can't cover them: every catch-up pass full-replaces these two
     *  (which also reconciles their deletions). Still far less work than the full
     *  hydrate the 60s pull used to run over every table. */
    suspend fun hydrateNonCursorTables() {
        replace(Tables.CALENDAR_CONNECTIONS, CalendarConnection.serializer(), { it.id }, { it.connectedAt }) { DbRowCodec.decodeConnection(it) }
        hydrateCalBlocks()
        // Every catch-up pass, not only the rare full hydrate: a zone change the
        // TIMEZONE_CHANGED receiver couldn't push (offline) lands on the next
        // pull. A no-op until the zone changes (parity with iOS build 78).
        pushTimezone()
    }

    // ── timezone (migration 053 C, android-gateway-plan risk 8) ─────────────────
    // The call dispatcher (and every server cron) converts the user's wall-clock
    // with `notification_preferences.timezone`. Only register-push-token used to
    // write it, so a zone change after install (travel, a DST-less OEM default)
    // left calls ringing on the OLD zone. Mirror the device zone on every hydrate,
    // sent at most once per zone per process: the RPC is a partial upsert that
    // no-ops server-side when unchanged, and a pre-053 server (404 → RpcRejected)
    // or an offline pull is simply retried by the next hydrate.

    /** Injectable: the device's IANA zone id (tests pin it). */
    internal var zoneId: () -> String = { java.util.TimeZone.getDefault().id }
    /** The zone the server has acknowledged this process; null until the first success. */
    internal var timezoneSent: String? = null
        private set

    internal suspend fun pushTimezone() {
        val tz = zoneId().trim()
        if (tz.isEmpty() || tz == timezoneSent) return
        runCatching { gateway.rpc(SET_TIMEZONE_RPC, buildJsonObject { put(SET_TIMEZONE_PARAM, tz) }) }
            .onSuccess { timezoneSent = tz }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; println("[hydrate] set_timezone($tz) failed, will retry next pull: $it") }
    }

    // One collections hydrate at a time (parity with iOS build 81, audit
    // 2026-09-22 C8). The unfiltered collection_members channel runs one per burst
    // of events, and refreshCollections / onRpcRejected / the full hydrate run one
    // each; two overlapping replaces let an older snapshot land last. Every call
    // takes a ticket; a run covers every ticket issued before it STARTED, so a
    // caller that arrived mid-run waits for the next run and returns after it,
    // and a burst costs one run plus one trailing run.
    private val collectionsMutex = Mutex()
    private val collectionsCalls = AtomicLong(0)
    private var collectionsCovered = 0L              // guarded by collectionsMutex
    @Volatile private var collectionsUserId: String? = null

    /** True while the last membership read (a collections hydrate's or the
     *  catch-up's) failed. Only a successful read clears it; until then every
     *  catch-up re-reads, because a device that knew nothing (a fresh sign-in)
     *  filled its lists with no members and the owner's edits would route as
     *  unshared (audit 2026-09-22 C8).
     *
     *  It starts TRUE, so a process whose first pull is a catch-up still re-reads
     *  membership once; each launch's full hydrate (Android audit 2026-09-23, A11)
     *  settles it too, as iOS's does. */
    @Volatile private var membershipUnresolved = true

    /** Collections + their membership. RLS returns own AND shared-with-me rows;
     *  collection_members (visible to member or owner) supplies each row's
     *  members[] + the current user's myRole. Mirrors hydrate.ts. Also invoked
     *  standalone when a collection_members realtime event fires. */
    suspend fun hydrateCollections(userId: String) {
        collectionsUserId = userId
        val ticket = collectionsCalls.incrementAndGet()
        collectionsMutex.withLock {
            if (collectionsCovered >= ticket) return   // a run that started after this call answered it
            val before = collectionsCovered
            collectionsCovered = collectionsCalls.get()
            try {
                performHydrateCollections(collectionsUserId ?: userId)
            } catch (t: CancellationException) {
                collectionsCovered = before   // nobody was answered: the next caller in line runs
                throw t
            }
        }
    }

    private suspend fun performHydrateCollections(userId: String) {
        // A collections op queued now can be acked (and its row echoed) while the
        // reads below are in flight, so their snapshot predates it and the replace
        // finds nothing queued. Those rows count as pending anyway: an edit acked
        // mid-read went back to the older snapshot (and the owner's next whole-row
        // upsert, built on it, deleted the edit on the server), and a list deleted
        // mid-read came back (parity with iOS build 81, audit 2026-09-22 C8).
        try {
            val queuedAtStart = store.pending().filter { it.recordTable == Tables.COLLECTIONS }
            // Per-row tolerant decode (see replace()): a single bad collection row
            // mustn't drop the user's entire list of collections.
            val collectionRows = gateway.fetchAll(Tables.COLLECTIONS)
            noteServerMax(Tables.COLLECTIONS, collectionRows)
            val base = collectionRows.mapNotNull { runCatching { DbRowCodec.decodeCollection(it) }.getOrNull() }
            // The membership select is a SEPARATE request: when it alone fails
            // (timeout, transient 5xx) the collections replace must NOT strip every
            // row's members[]/myRole — that flipped each shared list back to "solo"
            // (the owner resumed whole-row upserts over members' atomic edits, and a
            // member's list lost its role until the next pull). Carry the LOCAL
            // membership over per id instead (the realtime mergeKeep rule); a row
            // we've never seen stays unknown → read-only for a non-owner.
            val memberRows = runCatching { gateway.fetchAll("collection_members") }
                .onFailure {
                    if (it is CancellationException) throw it
                    println("[hydrate] collection_members failed, keeping local membership: $it")
                }
                .getOrNull()
            val byColl = membersByCollection(memberRows.orEmpty())
            store.transaction {
                val local = snapshot(Tables.COLLECTIONS, ItemCollection.serializer())
                val known = local.associateBy { it.id }
                val enriched = base.map { c ->
                    if (memberRows == null) {
                        val prev = known[c.id]
                        return@map c.copy(
                            members = prev?.members.orEmpty(),
                            myRole = prev?.myRole ?: (if (c.ownerId == userId) "owner" else null),
                        )
                    }
                    val ms = byColl[c.id].orEmpty()
                    val myRole = if (c.ownerId == userId) "owner" else ms.firstOrNull { it.first == userId }?.second
                    c.copy(members = ms.map { it.first }, myRole = myRole)
                }
                // Keep optimistic local collections whose outbox op hasn't flushed
                // yet. A queued item RPC counts (its optimistic items would revert),
                // and a list whose DELETE is queued stays gone: this runs on every
                // membership event now, not only after a flush.
                val ops = pending().filter { it.recordTable == Tables.COLLECTIONS } + queuedAtStart
                val pendingDeletes = ops.filter { it.op == "delete" }.mapTo(HashSet()) { it.recordId }
                val pendingIds = ops.filter { it.op == "upsert" || it.op == OutboxFlusher.OP_RPC }.mapTo(HashSet()) { it.recordId }
                val onServer = HashSet<String>()
                val rows = ArrayList<ItemCollection>()
                for (r in enriched) {
                    if (r.id in pendingDeletes) continue
                    onServer += r.id
                    val l = known[r.id]
                    // A queued row keeps its content intent but takes the membership
                    // just read: membership is server truth, never a local edit.
                    rows += if (r.id in pendingIds && l != null) l.copy(members = r.members, myRole = r.myRole) else r
                }
                for (l in local) if (l.id in pendingIds && l.id !in onServer) rows += l
                replace(Tables.COLLECTIONS, rows, ItemCollection.serializer(), { it.id })
            }
            membershipUnresolved = memberRows == null
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            // The catch-up may have applied rows whose membership it can't know.
            membershipUnresolved = true
            println("[hydrate] collections failed, leaving local intact: $t")
        }
    }

    /**
     * The catch-up's membership re-read (parity with iOS build 81, audit
     * 2026-09-22 C8). The server's collections row carries no membership, and the
     * catch-up (like realtime) carries the local members forward, so migration 056
     * §4's `updated_at` bump on every collection_members change is the owner's ONLY
     * pull-side signal that a list became shared (a join by link, an invite claimed
     * at sign-up, a share made on another device) or lost a member. Without this
     * re-read the owner's phone kept `members == []` across foregrounds AND
     * relaunches (the cursors persist, so a cold launch never full-hydrates),
     * `isShared` stayed false, and its item edits went out as whole-row upserts that
     * deleted what the members added.
     *
     * It only PATCHES members/myRole onto the local rows, read and written in one
     * transaction: the pull right before it already applied every newer
     * collections row, and a content replace here (it also runs after the user's
     * own list edits, since realtime never moves the cursor) could revert an edit
     * acked or echoed meanwhile.
     */
    suspend fun refreshCollectionMembership(userId: String, collectionsChanged: Boolean) {
        if (!collectionsChanged && !membershipUnresolved) return
        val memberRows = try {
            gateway.fetchAll("collection_members")
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            membershipUnresolved = true
            println("[catchup] collection_members failed, retrying on the next catch-up: $t")
            return
        }
        val byColl = membersByCollection(memberRows)
        try {
            store.transaction {
                for (c in snapshot(Tables.COLLECTIONS, ItemCollection.serializer())) {
                    val ms = byColl[c.id].orEmpty()
                    val role = if (c.ownerId == userId) "owner" else ms.firstOrNull { it.first == userId }?.second
                    // Someone else's list with no row for me: I can no longer see it,
                    // and the reconcile / the members event removes it. Don't strip
                    // its role in the meantime.
                    if (c.ownerId != userId && role == null) continue
                    val members = ms.map { it.first }
                    if (c.members == members && c.myRole == role) continue
                    upsert(Tables.COLLECTIONS, c.copy(members = members, myRole = role), ItemCollection.serializer(), c.id)
                }
            }
            membershipUnresolved = false
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            membershipUnresolved = true
            println("[catchup] collection membership not saved, retrying on the next catch-up: $t")
        }
    }

    /** collectionId -> [(userId, role)], in server order. */
    private fun membersByCollection(rows: List<JsonObject>): Map<String, List<Pair<String, String>>> {
        val byColl = HashMap<String, MutableList<Pair<String, String>>>()
        for (m in rows) {
            val cid = (m["collection_id"] as? JsonPrimitive)?.contentOrNull ?: continue
            val uid = (m["user_id"] as? JsonPrimitive)?.contentOrNull ?: continue
            val role = (m["role"] as? JsonPrimitive)?.contentOrNull ?: "editor"
            byColl.getOrPut(cid) { mutableListOf() }.add(uid to role)
        }
        return byColl
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
            val rows = gateway.fetchAll(table)
            val models = rows.mapNotNull { runCatching { decode(it) }.getOrNull() }
            if (mayBeTruncated(table, rows)) {
                store.upsertAllKeepingPending(table, models, ser, id, updatedAt)
                return@runCatching
            }
            noteServerMax(table, rows)
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

    /** PostgREST cuts an unpaged select at max_rows ([SERVER_ROW_CAP]) without an
     *  error, so a fetch that size may be only part of the table. Replacing the
     *  table with it wiped every row past the cap, and seeding the mark from it
     *  skipped rows that were cut. With a full hydrate at every launch that hit
     *  each cold start, not only a sign-in (Android audit 2026-09-23, A11). Such a
     *  fetch is merged instead, and gets no mark: the catch-up carries on from the
     *  table's old mark (or from the start), and the id sweep drops what the
     *  server deleted. */
    private fun mayBeTruncated(table: String, rows: List<JsonObject>): Boolean {
        if (rows.size < SERVER_ROW_CAP) return false
        println("[hydrate] $table returned ${rows.size} rows, the server's cap: merged, not replaced")
        return true
    }

    /** Record the newest SERVER stamp this table's fetch returned (the catch-up
     *  cursor seed). An empty-but-successful fetch seeds the epoch, so the table
     *  still gets a mark and the next pass is a cursor pull, not a full hydrate. */
    private fun noteServerMax(table: String, rows: List<JsonObject>) {
        val column = CatchUpPuller.cursorColumn(table) ?: return
        serverMaxima[table] = CatchUpPuller.maxStamp(rows, column) ?: CatchUpPuller.EPOCH
    }

    /** call_requests — the read-only bookings mirror (no outbox ops exist for the
     *  table). Server-canonical, with one preservation rule: a LOCAL-ONLY row
     *  stamped newer than every server row is a booking absorbed after this
     *  fetch started — kept until the next pull confirms it, instead of the call
     *  list flickering empty right after booking (parity with iOS build 72,
     *  CallRequestsMirror.mergeHydrated). The local read sits right before the
     *  replace, after the fetch, so the gap it leaves is milliseconds, not the
     *  round trip. */
    private suspend fun hydrateCallRequests() {
        runCatching {
            val rows = gateway.fetchAll(Tables.CALL_REQUESTS)
            val remote = rows.mapNotNull { runCatching { DbRowCodec.decodeCallRequest(it) }.getOrNull() }
            if (mayBeTruncated(Tables.CALL_REQUESTS, rows)) {
                store.upsertAllKeepingPending(Tables.CALL_REQUESTS, remote, CallRequest.serializer(), { it.id }, { it.updatedAt })
                return@runCatching
            }
            noteServerMax(Tables.CALL_REQUESTS, rows)
            val local = store.snapshot(Tables.CALL_REQUESTS, CallRequest.serializer())
            val merged = CallRequestsMirror.mergeHydrated(remote, local)
            store.replace(Tables.CALL_REQUESTS, merged, CallRequest.serializer(), { it.id }, { it.updatedAt }, keepPendingUpserts = true)
        }.onFailure { println("[hydrate] ${Tables.CALL_REQUESTS} failed, leaving local intact: $it") }
    }

    /** The last SUCCESSFUL cal_blocks read (stage 2, deterministic-occurrence-ids.md
     *  §3c): the horizon top-up runs only after one, and never over one that hit
     *  the row cap. The generic "pull finished" signal can't tell: this read's
     *  failure was swallowed and [hydrated] fired anyway. Null until one succeeds
     *  in this process, and again after a sign-out. */
    @Volatile var calBlocksPull: CalBlocksPull? = null
        private set
    private val calBlocksSeq = AtomicLong(0)

    /** Runs after every successful cal_blocks read (the rule-G gate releases the
     *  pushes that waited for a row this read brought back). */
    internal var onCalBlocksPulled: (suspend () -> Unit)? = null

    /** Sign-out / user switch: the next account's top-up waits for its own read. */
    fun resetCalBlocksPull() { calBlocksPull = null }

    private suspend fun hydrateCalBlocks() {
        try {
            // Per-row tolerant decode (see replace()): a single bad cal_block row
            // mustn't wipe the whole schedule.
            val rows = gateway.fetchAll(Tables.CAL_BLOCKS)
            val remote = rows.mapNotNull { runCatching { DbRowCodec.decodeCalBlock(it) }.getOrNull() }
            val local = store.snapshot(Tables.CAL_BLOCKS, CalBlock.serializer())
            val localExternal = local.filter { isExternalBlock(it) }
            val merged = SyncDecision.mergeHydratedCalBlocks(remote, localExternal)
            // Preserve unsynced optimistic TASK blocks (a pending outbox upsert or a
            // queued mint) — even when the server already has an older copy of that
            // block (a move that hasn't flushed yet must not snap back). See replace().
            store.replace(Tables.CAL_BLOCKS, merged, CalBlock.serializer(), { it.id }, keepPendingUpserts = true)
            calBlocksPull = CalBlocksPull(calBlocksSeq.incrementAndGet(), rows.size)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            println("[hydrate] cal_blocks failed, leaving local intact: $t")
            return
        }
        runCatching { onCalBlocksPulled?.invoke() }.onFailure { if (it is CancellationException) throw it }
    }

    /** One successful cal_blocks read: its order in this process, and how many
     *  rows it returned. PostgREST cuts an unpaged select at [SERVER_ROW_CAP]
     *  without saying so, so a read that size may be missing rows — the top-up
     *  would then keep re-minting days it can't see (§f; paginating the pull is
     *  the real fix). */
    data class CalBlocksPull(val seq: Long, val rowCount: Int) {
        val mayBeTruncated: Boolean get() = rowCount >= SERVER_ROW_CAP
    }

    companion object {
        /** `set_timezone(p_tz text)` — migration 053 C. */
        const val SET_TIMEZONE_RPC = "set_timezone"
        const val SET_TIMEZONE_PARAM = "p_tz"

        /** PostgREST's max_rows on this project (the Supabase default): the most
         *  rows one unpaged select returns. */
        internal const val SERVER_ROW_CAP = 1_000

        /** Clock-skew tolerance between devices' updated_at stamps (each client
         *  stamps its own wall clock). Within it, "the server is newer" is not
         *  conclusive, so the local op is kept (no base) or the local value wins a
         *  field conflict (merge). */
        internal const val LWW_SKEW_MS = 2_000L

        // Columns that are not part of the user's edit and must not make a base look
        // "changed": the gateway-injected owner + the stamp itself.
        private val VOLATILE_KEYS = setOf("user_id", "updated_at")

        private fun stripVolatile(o: JsonObject): Map<String, JsonElement> = o.filterKeys { it !in VOLATILE_KEYS }

        // The task columns that hold an instant. PostgREST writes them "…+00:00",
        // the phone "…Z", so the merge compares these as instants.
        private val TIMESTAMP_KEYS = setOf("completed_at", "due_at", "created_at")

        /** Equal values for [key]: the same JSON, or for a timestamp column the
         *  same instant. A base taken from the phone's own payload (the op queued
         *  before it, or the one that just landed) holds the phone's text, and the
         *  server's copy of the same instant read as a server change (audit
         *  2026-09-22 C9: an Undo under a slow commit kept the old completion time). */
        private fun sameValue(key: String, a: JsonElement?, b: JsonElement?): Boolean {
            if (a == b) return true
            if (key !in TIMESTAMP_KEYS) return false
            val am = (a as? JsonPrimitive)?.contentOrNull?.let { Time.parseMillis(it) } ?: return false
            val bm = (b as? JsonPrimitive)?.contentOrNull?.let { Time.parseMillis(it) } ?: return false
            return am == bm
        }

        internal fun updatedAtMs(row: JsonObject): Long? =
            (row["updated_at"] as? JsonPrimitive)?.contentOrNull?.let { Time.parseMillis(it) }

        /** Field-level 3-way merge of a queued task row against a server row that
         *  changed since [base] was read. Per key (union of local + server):
         *   - unchanged locally → the server's value (whatever it is now)
         *   - changed locally, unchanged on the server → the local value
         *   - changed on BOTH → the newer writer, with a skew margin: the server
         *     wins only when it is newer by more than [LWW_SKEW_MS], else local.
         *  Timestamp columns compare as instants ([sameValue]).
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
                val localChanged = !sameValue(key, l, b)
                val serverChanged = !sameValue(key, s, b)
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
