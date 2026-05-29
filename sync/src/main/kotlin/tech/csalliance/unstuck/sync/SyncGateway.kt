package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// SyncGateway — the PostgREST CRUD primitive the engine builds on. Works in
// JsonObject row shapes (from DbRowCodec) so explicit-null semantics survive.
// On every write it injects `user_id` the way the web bridge does
// (payload = { ...row, user_id }). Reads rely on RLS to auto-scope.

class SyncGateway(private val client: SupabaseClient) {

    suspend fun fetchAll(table: String): List<JsonObject> =
        client.from(table).select(Columns.ALL).decodeList<JsonObject>()

    suspend fun upsert(table: String, row: JsonObject, userId: String) {
        client.from(table).upsert(withUserId(row, userId)) { onConflict = "id" }
    }

    suspend fun delete(table: String, id: String) {
        client.from(table).delete { filter { eq("id", id) } }
    }

    private fun withUserId(row: JsonObject, userId: String): JsonObject =
        JsonObject(row + ("user_id" to JsonPrimitive(userId)))
}
