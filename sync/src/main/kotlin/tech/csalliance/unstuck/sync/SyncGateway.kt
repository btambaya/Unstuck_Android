package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.ktor.client.plugins.ResponseException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// SyncGateway — the PostgREST CRUD primitive the engine builds on. Works in
// JsonObject row shapes (from DbRowCodec) so explicit-null semantics survive.
// On every write it injects `user_id` the way the web bridge does
// (payload = { ...row, user_id }). Reads rely on RLS to auto-scope.

/** The CRUD surface the offline engine (OutboxFlusher / Hydrator) depends on.
 *  Lets the JVM tests substitute a fake without a live SupabaseClient. */
interface SyncRemote {
    suspend fun fetchAll(table: String): List<JsonObject>
    suspend fun upsert(table: String, row: JsonObject, userId: String)
    suspend fun delete(table: String, id: String)
    /** Call a Postgres function (the shared-collection item RPCs). Throws
     *  [RpcRejected] when the server REFUSED the call (4xx — RLS denial, not a
     *  member, unknown collection): terminal, never retried. Any other throw is
     *  transient (offline, 5xx) and the outbox retries it. */
    suspend fun rpc(fn: String, params: JsonObject)
}

/** A server-side refusal of an outbox `rpc` op — terminal (retrying can't change
 *  the answer). The flusher dequeues the op and asks the app layer to roll the
 *  optimistic write back. 408/429 are NOT rejections (transient). */
class RpcRejected(val status: Int, message: String) : Exception(message)

class SyncGateway(private val client: SupabaseClient) : SyncRemote {

    override suspend fun fetchAll(table: String): List<JsonObject> =
        client.from(table).select(Columns.ALL).decodeList<JsonObject>()

    override suspend fun upsert(table: String, row: JsonObject, userId: String) {
        client.from(table).upsert(withUserId(row, userId)) { onConflict = "id" }
    }

    override suspend fun delete(table: String, id: String) {
        client.from(table).delete { filter { eq("id", id) } }
    }

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
