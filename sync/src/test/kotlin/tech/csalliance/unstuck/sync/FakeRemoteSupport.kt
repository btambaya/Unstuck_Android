package tech.csalliance.unstuck.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import tech.csalliance.unstuck.core.time.Time

/**
 * Shared helpers so every test fake answers the two catch-up reads the same way
 * a real PostgREST would: ordered, strictly-greater-than, bounded, id-only.
 * Keeps the fakes honest — a fake that returned everything would make the cursor
 * tests pass for the wrong reason.
 */
internal object FakeRemoteSupport {

    fun since(rows: List<JsonObject>, column: String, since: String, limit: Int): List<JsonObject> {
        val cut = Time.parseMillis(since) ?: Long.MIN_VALUE
        return rows
            .mapNotNull { row -> stamp(row, column)?.let { it to row } }
            .filter { it.first > cut }
            .sortedBy { it.first }
            .take(limit)
            .map { it.second }
    }

    fun ids(rows: List<JsonObject>, offset: Int, limit: Int): List<String> =
        rows.mapNotNull { (it["id"] as? JsonPrimitive)?.contentOrNull }
            .sorted()
            .drop(offset)
            .take(limit)

    private fun stamp(row: JsonObject, column: String): Long? =
        (row[column] as? JsonPrimitive)?.contentOrNull?.let { Time.parseMillis(it) }
}
