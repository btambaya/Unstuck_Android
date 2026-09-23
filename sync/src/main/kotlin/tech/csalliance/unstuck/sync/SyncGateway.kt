package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.plugins.ResponseException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// SyncGateway — the PostgREST CRUD primitive the engine builds on. Works in
// JsonObject row shapes (from DbRowCodec) so explicit-null semantics survive.
// On every write it injects `user_id` the way the web bridge does
// (payload = { ...row, user_id }). Reads rely on RLS to auto-scope.

/** The CRUD surface the offline engine (OutboxFlusher / Hydrator / CatchUp)
 *  depends on. Lets the JVM tests substitute a fake without a live
 *  SupabaseClient. */
interface SyncRemote {
    suspend fun fetchAll(table: String): List<JsonObject>
    suspend fun upsert(table: String, row: JsonObject, userId: String)
    suspend fun delete(table: String, id: String)
    /** Call a Postgres function (the shared-collection item RPCs). Throws
     *  [RpcRejected] when the server REFUSED the call (4xx — RLS denial, not a
     *  member, unknown collection): terminal, never retried. Any other throw is
     *  transient (offline, 5xx) and the outbox retries it. */
    suspend fun rpc(fn: String, params: JsonObject)

    /** INSERT-IF-ABSENT — a MINT of a repeating task's occurrence with its
     *  deterministic id (stage 2, "same id for same day", Ahmad 2026-09-23;
     *  deterministic-occurrence-ids.md §3c): `INSERT … ON CONFLICT (id) DO
     *  NOTHING RETURNING id`. True = inserted; false = the server already had
     *  the id and kept its row untouched (another device's occurrence, perhaps
     *  moved, done or retimed there).
     *
     *  The default THROWS (as a non-refusal, so the outbox keeps the op): an
     *  interface default cannot reach the client, and it must never fall back
     *  to [upsert] — a plain upsert would overwrite the other device's row
     *  (hazard c). */
    suspend fun insertIfAbsent(table: String, row: JsonObject, userId: String): Boolean =
        throw UnsupportedOperationException("insertIfAbsent is not implemented by this SyncRemote")

    /** RULE H's conditional retime, sent only after a USER's mint was ignored:
     *  `PATCH ?id=eq.X&date=eq.D&done=eq.false&skipped=eq.false` with only
     *  `start_time` + `duration_minutes`. It moves that day's OPEN occurrence to
     *  the time the user asked for; a row that moved, is done or skipped keeps
     *  the first write. The server row when it matched, else null. Column-scoped:
     *  the Google mapping, the name, the date and the done state are never
     *  touched. The default THROWS, as [insertIfAbsent]'s does. */
    suspend fun retimeIfOpen(table: String, id: String, date: String, startTime: String, durationMinutes: Int): JsonObject? =
        throw UnsupportedOperationException("retimeIfOpen is not implemented by this SyncRemote")

    /** CATCH-UP READ — the correctness path (see [CatchUpPuller]). Rows of
     *  [table] whose [column] (`updated_at`, or the table's monotonic stand-in)
     *  is STRICTLY GREATER than [since], ordered by ([column], id), at most
     *  [limit] of them. Ordered and bounded so the caller can page and advance
     *  its high-water mark row by row. */
    suspend fun fetchSince(table: String, column: String, since: String, limit: Int): List<JsonObject>

    /** The rest of a run of rows that share one stamp: rows of [table] whose
     *  [column] equals [stamp] and whose id sorts after [afterId], by id, at most
     *  [limit]. A page of [fetchSince] can end part-way through such a run, and
     *  "strictly after the last stamp" would skip its tail (Android audit
     *  2026-09-23, A11). The default reads the whole table; the gateway overrides
     *  it with a bounded query. */
    suspend fun fetchTie(table: String, column: String, stamp: String, afterId: String, limit: Int): List<JsonObject> {
        val at = CatchUpPuller.normalizeStamp(stamp)
        return fetchAll(table)
            .filter { r -> CatchUpPuller.stampOf(r, column) == at && ((r["id"] as? JsonPrimitive)?.contentOrNull ?: "") > afterId }
            .sortedBy { (it["id"] as? JsonPrimitive)?.contentOrNull }
            .take(limit)
    }

    /** DELETION-RECONCILE READ: just the ids of the user's rows in [table], one
     *  page at a time. A hard delete is invisible to a cursor pull, and an
     *  id-only page is the cheapest thing that can see it. */
    suspend fun fetchIds(table: String, offset: Int, limit: Int): List<String>

    /** [fetchIds] with each row's [column] alongside (null [column] = ids only),
     *  so the sweep can also spot a row whose stamp moved past this device's copy
     *  without the cursor seeing it (Android audit 2026-09-23, A11). The default
     *  carries no stamps; the gateway overrides it. */
    suspend fun fetchIdStamps(table: String, column: String?, offset: Int, limit: Int): List<Pair<String, String?>> =
        fetchIds(table, offset, limit).map { it to null }

    /** The rows of [table] with these ids — the sweep's repair read. The default
     *  reads the whole table; the gateway overrides it with an `in` filter. */
    suspend fun fetchByIds(table: String, ids: Collection<String>): List<JsonObject> {
        val want = ids.toSet()
        return fetchAll(table).filter { (it["id"] as? JsonPrimitive)?.contentOrNull in want }
    }
}

/** A server-side refusal of an outbox `rpc` op — terminal (retrying can't change
 *  the answer). The flusher dequeues the op and asks the app layer to roll the
 *  optimistic write back. 408/429 are NOT rejections (transient). */
class RpcRejected(val status: Int, message: String) : Exception(message)

class SyncGateway(private val client: SupabaseClient) : SyncRemote {

    override suspend fun fetchAll(table: String): List<JsonObject> =
        client.from(table).select(Columns.ALL).decodeList<JsonObject>()

    override suspend fun fetchSince(table: String, column: String, since: String, limit: Int): List<JsonObject> =
        client.from(table).select(Columns.ALL) {
            filter { gt(column, since) }
            order(column, Order.ASCENDING)
            // id breaks ties, so a page that ends mid-run can be finished by fetchTie.
            order("id", Order.ASCENDING)
            limit(limit.toLong())
        }.decodeList<JsonObject>()

    override suspend fun fetchTie(table: String, column: String, stamp: String, afterId: String, limit: Int): List<JsonObject> =
        client.from(table).select(Columns.ALL) {
            filter {
                eq(column, stamp)
                gt("id", afterId)
            }
            order("id", Order.ASCENDING)
            limit(limit.toLong())
        }.decodeList<JsonObject>()

    override suspend fun fetchIdStamps(table: String, column: String?, offset: Int, limit: Int): List<Pair<String, String?>> =
        client.from(table).select(Columns.list(*listOfNotNull("id", column).toTypedArray())) {
            order("id", Order.ASCENDING)
            range(offset.toLong(), (offset + limit - 1).toLong())
        }.decodeList<JsonObject>().mapNotNull { o ->
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            id to column?.let { (o[it] as? JsonPrimitive)?.contentOrNull }
        }

    override suspend fun fetchByIds(table: String, ids: Collection<String>): List<JsonObject> =
        if (ids.isEmpty()) emptyList()
        else client.from(table).select(Columns.ALL) { filter { isIn("id", ids.toList()) } }.decodeList<JsonObject>()

    override suspend fun fetchIds(table: String, offset: Int, limit: Int): List<String> =
        client.from(table).select(Columns.list("id")) {
            // Ordered so the paging window is stable across requests (an unordered
            // PostgREST range can repeat/skip rows between pages).
            order("id", Order.ASCENDING)
            range(offset.toLong(), (offset + limit - 1).toLong())
        }.decodeList<JsonObject>().mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }

    override suspend fun upsert(table: String, row: JsonObject, userId: String) {
        client.from(table).upsert(withUserId(row, userId)) { onConflict = "id" }
    }

    override suspend fun delete(table: String, id: String) {
        client.from(table).delete { filter { eq("id", id) } }
    }

    /** `POST /<table>?on_conflict=id&select=id` with `Prefer:
     *  resolution=ignore-duplicates,return=representation`: PostgREST answers
     *  `[{"id":…}]` for a row it inserted and `[]` for an id it already had
     *  (checked against prod with two temporary users, 2026-09-23 — DECISIONS.md
     *  "Stage 2 step 0"). The request shape is pinned by
     *  InsertRequestShapeTest. */
    override suspend fun insertIfAbsent(table: String, row: JsonObject, userId: String): Boolean =
        client.from(table).upsert(withUserId(row, userId)) {
            onConflict = "id"
            ignoreDuplicates = true
            select(Columns.list("id"))
        }.decodeList<JsonObject>().isNotEmpty()

    override suspend fun retimeIfOpen(table: String, id: String, date: String, startTime: String, durationMinutes: Int): JsonObject? =
        client.from(table).update(
            buildJsonObject {
                put("start_time", startTime)
                put("duration_minutes", durationMinutes)
            },
        ) {
            select()
            filter {
                eq("id", id)
                eq("date", date)
                eq("done", false)
                eq("skipped", false)
            }
        }.decodeList<JsonObject>().firstOrNull()

    override suspend fun rpc(fn: String, params: JsonObject) {
        try {
            client.postgrest.rpc(fn, params)
        } catch (e: ResponseException) {
            val code = e.response.status.value
            if (code in 400..499 && code != 408 && code != 429) throw RpcRejected(code, "$fn rejected ($code): ${e.message}")
            throw e
        }
    }

    private fun withUserId(row: JsonObject, userId: String): JsonObject =
        JsonObject(row + ("user_id" to JsonPrimitive(userId)))
}
