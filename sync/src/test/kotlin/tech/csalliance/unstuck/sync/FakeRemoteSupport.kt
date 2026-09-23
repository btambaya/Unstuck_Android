package tech.csalliance.unstuck.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import tech.csalliance.unstuck.core.time.Time

/**
 * Shared helpers so every test fake answers the catch-up reads the same way a
 * real PostgREST would: ordered, strictly-greater-than, bounded, id-only.
 * Keeps the fakes honest — a fake that returned everything would make the cursor
 * tests pass for the wrong reason.
 */
internal object FakeRemoteSupport {

    fun since(rows: List<JsonObject>, column: String, since: String, limit: Int): List<JsonObject> {
        val cut = Time.parseMillis(since) ?: Long.MIN_VALUE
        return rows
            .mapNotNull { row -> stamp(row, column)?.let { it to row } }
            .filter { it.first > cut }
            // (column, id), as SyncGateway.fetchSince orders it.
            .sortedWith(compareBy({ it.first }, { id(it.second) }))
            .take(limit)
            .map { it.second }
    }

    /** SyncGateway.fetchTie: the rows stamped exactly [stamp] with an id after [afterId]. */
    fun tie(rows: List<JsonObject>, column: String, stamp: String, afterId: String, limit: Int): List<JsonObject> {
        val at = Time.parseMillis(stamp)
        return rows
            .filter { stamp(it, column) == at && (id(it) ?: "") > afterId }
            .sortedBy { id(it) }
            .take(limit)
    }

    fun ids(rows: List<JsonObject>, offset: Int, limit: Int): List<String> =
        rows.mapNotNull { id(it) }
            .sorted()
            .drop(offset)
            .take(limit)

    /** SyncGateway.fetchIdStamps: id-ordered (id, [column]) pairs. */
    fun idStamps(rows: List<JsonObject>, column: String?, offset: Int, limit: Int): List<Pair<String, String?>> =
        rows.mapNotNull { r -> id(r)?.let { it to column?.let { c -> (r[c] as? JsonPrimitive)?.contentOrNull } } }
            .sortedBy { it.first }
            .drop(offset)
            .take(limit)

    fun byIds(rows: List<JsonObject>, ids: Collection<String>): List<JsonObject> {
        val want = ids.toSet()
        return rows.filter { id(it) in want }
    }

    private fun id(row: JsonObject): String? = (row["id"] as? JsonPrimitive)?.contentOrNull

    private fun stamp(row: JsonObject, column: String): Long? =
        (row[column] as? JsonPrimitive)?.contentOrNull?.let { Time.parseMillis(it) }
}
