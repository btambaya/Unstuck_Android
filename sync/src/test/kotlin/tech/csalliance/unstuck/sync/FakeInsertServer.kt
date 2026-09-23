package tech.csalliance.unstuck.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.data.db.Tables

/**
 * A fake PostgREST for the stage-2 tests (deterministic occurrence ids, Ahmad
 * 2026-09-23), with the two new requests' REAL semantics as step 0 measured them
 * on prod (DECISIONS.md "Stage 2 step 0"):
 *  - [insertIfAbsent] is `INSERT … ON CONFLICT (id) DO NOTHING RETURNING id`:
 *    true for a new id, false (row untouched) for one the server already has;
 *  - [retimeIfOpen] is the column-scoped PATCH filtered by id, date, done=false
 *    and skipped=false: the row when it matched, else null.
 * A plain [upsert] merges every column it is sent (merge-duplicates). One server
 * can back several devices' engines — the twin tests.
 */
internal class FakeInsertServer : SyncRemote {
    /** table → id → row. */
    val tables = LinkedHashMap<String, LinkedHashMap<String, JsonObject>>()
    /** Every write, in order: "insert <id> inserted|ignored", "retime <id> retimed|ignored",
     *  "upsert <id>", "delete <id>". */
    val log = mutableListOf<String>()
    /** "<verb> <id>" keys that fail ONCE with a transient error (offline). */
    val failOnce = mutableSetOf<String>()
    /** Runs as a write starts — lets a test land something mid-request. */
    var beforeWrite: (suspend (verb: String, id: String) -> Unit)? = null
    /** Tables whose next full read fails ONCE with a transient error (offline). */
    val failReadOnce = mutableSetOf<String>()

    fun table(t: String): LinkedHashMap<String, JsonObject> = tables.getOrPut(t) { LinkedHashMap() }
    fun put(t: String, row: JsonObject) { table(t)[idOf(row)!!] = row }
    fun putBlock(b: CalBlock) = put(Tables.CAL_BLOCKS, DbRowCodec.encodeCalBlock(b))
    fun block(id: String): CalBlock? = table(Tables.CAL_BLOCKS)[id]?.let { DbRowCodec.decodeCalBlock(it) }
    fun blocks(): List<CalBlock> = table(Tables.CAL_BLOCKS).values.map { DbRowCodec.decodeCalBlock(it) }

    private suspend fun write(verb: String, id: String) {
        beforeWrite?.invoke(verb, id)
        if (failOnce.remove("$verb $id")) throw java.io.IOException("simulated offline ($verb $id)")
    }

    override suspend fun fetchAll(table: String): List<JsonObject> {
        if (failReadOnce.remove(table)) throw java.io.IOException("simulated offline (read $table)")
        return table(table).values.toList()
    }

    override suspend fun upsert(table: String, row: JsonObject, userId: String) {
        val id = idOf(row)!!
        write("upsert", id)
        val merged = JsonObject((table(table)[id] ?: JsonObject(emptyMap())) + row + ("user_id" to JsonPrimitive(userId)))
        table(table)[id] = merged
        log += "upsert $id"
    }

    override suspend fun delete(table: String, id: String) {
        write("delete", id)
        table(table).remove(id)
        log += "delete $id"
    }

    override suspend fun rpc(fn: String, params: JsonObject) = Unit

    override suspend fun insertIfAbsent(table: String, row: JsonObject, userId: String): Boolean {
        val id = idOf(row)!!
        write("insert", id)
        if (id in table(table)) {
            log += "insert $id ignored"
            return false
        }
        table(table)[id] = JsonObject(row + ("user_id" to JsonPrimitive(userId)))
        log += "insert $id inserted"
        return true
    }

    override suspend fun retimeIfOpen(table: String, id: String, date: String, startTime: String, durationMinutes: Int): JsonObject? {
        write("retime", id)
        val cur = table(table)[id]
        val open = cur != null && str(cur, "date") == date && bool(cur, "done") != true && bool(cur, "skipped") != true
        if (!open) {
            log += "retime $id ignored"
            return null
        }
        val next = JsonObject(cur!! + ("start_time" to JsonPrimitive(startTime)) + ("duration_minutes" to JsonPrimitive(durationMinutes)))
        table(table)[id] = next
        log += "retime $id retimed"
        return next
    }

    override suspend fun fetchSince(table: String, column: String, since: String, limit: Int) =
        FakeRemoteSupport.since(table(table).values.toList(), column, since, limit)

    override suspend fun fetchIds(table: String, offset: Int, limit: Int) =
        FakeRemoteSupport.ids(table(table).values.toList(), offset, limit)

    override suspend fun fetchByIds(table: String, ids: Collection<String>): List<JsonObject> =
        FakeRemoteSupport.byIds(table(table).values.toList(), ids)

    private fun idOf(row: JsonObject): String? = (row["id"] as? JsonPrimitive)?.contentOrNull
    private fun str(row: JsonObject, k: String): String? = (row[k] as? JsonPrimitive)?.contentOrNull
    private fun bool(row: JsonObject, k: String): Boolean? = (row[k] as? JsonPrimitive)?.booleanOrNull
}
