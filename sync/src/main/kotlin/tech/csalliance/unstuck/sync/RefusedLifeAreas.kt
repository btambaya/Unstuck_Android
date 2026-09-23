package tech.csalliance.unstuck.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.OutboxEntity
import tech.csalliance.unstuck.data.db.Tables

/**
 * Life-area writes queued on this phone that the server can never take. Builds up to
 * vc100 re-created the signup seed at onboarding (Work / Personal / Home / Health under
 * fresh ids), but the server's handle_new_user trigger had already made those rows and
 * life_areas is unique(user_id, name): every flush was refused (23505 / 409), the ops
 * stayed queued for good (retried each launch, "parked" at sign-out and restored at the
 * next sign-in) and the Hydrator kept the local copies, so every picker listed the areas
 * twice (Android audit 2026-09-23, A8). Onboarding no longer seeds them; this clears
 * what those builds left, after each pull.
 *
 * A row whose queued write would give it EXACTLY the name of a row the server holds (a
 * stored row with nothing queued: pulled, or already flushed) is dropped, row and ops.
 * The constraint is case-sensitive, so "work" beside "Work" is a legal row and stays.
 * Nothing refers to an area by id (tasks name it), so the server's row just takes over.
 */
object RefusedLifeAreas {

    /** Drop them; how many rows went. One transaction over the rows and the outbox, so
     *  an area saved meanwhile is either read here or not queued yet. */
    suspend fun heal(store: LocalStore): Int = store.transaction {
        val ops = pending().filter { it.recordTable == Tables.LIFE_AREAS }
        if (ops.isEmpty()) return@transaction 0
        val refused = refusedIds(ops, snapshot(Tables.LIFE_AREAS, LifeArea.serializer()))
        for (id in refused) {
            delete(Tables.LIFE_AREAS, id)
            ops.filter { it.recordId == id }.forEach { dequeue(it.seq) }
        }
        refused.size
    }

    /** The row ids among [ops] (life_areas outbox ops) whose latest op is an upsert
     *  naming an area the server already has among [stored]. Read from the payload, not
     *  the stored row: an op parked at sign-out comes back with no local row. */
    internal fun refusedIds(ops: List<OutboxEntity>, stored: List<LifeArea>): Set<String> {
        val queued = ops.map { it.recordId }.toSet()
        val onServer = stored.filter { it.id !in queued }.map { it.name }.toSet()
        if (onServer.isEmpty()) return emptySet()
        return ops.groupBy { it.recordId }
            .filterValues { rowOps ->
                val latest = rowOps.maxBy { it.seq }
                latest.op == "upsert" && nameOf(latest.payload)?.let { it in onServer } == true
            }
            .keys
    }

    private fun nameOf(payload: String?): String? = payload?.let {
        runCatching { (Json.parseToJsonElement(it).jsonObject["name"] as? JsonPrimitive)?.contentOrNull }.getOrNull()
    }
}
