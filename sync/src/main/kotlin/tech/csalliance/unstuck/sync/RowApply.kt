package tech.csalliance.unstuck.sync

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import tech.csalliance.unstuck.core.model.CalBlock
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

/**
 * ONE definition of "apply this server row to the local store", shared by the
 * live mirror ([RealtimeMirror]) and the catch-up pull ([CatchUpPuller]).
 *
 * Both paths carry the same rows in the same wire shape; before this they each
 * had their own copy of the decode + last-write-wins rules, which is exactly how
 * a live path and a recovery path drift apart. Every write goes through
 * [LocalStore], so the per-logical-table version counters are bumped and the UI
 * re-reads — a row applied here reaches the screen without a relaunch.
 *
 * Returns TRUE when the row was written, FALSE when the store's last-write-wins
 * guard rejected it because the LOCAL row is strictly newer. A false is not an
 * error and not a gap: we have seen the row and deliberately kept ours.
 */
internal object RowApply {

    /** The tables a server row can be applied to. cal_blocks/tags/life_areas/
     *  collections have no comparable per-row stamp, so they stay last-write-wins;
     *  everything else goes through upsertIfNewer. */
    suspend fun apply(table: String, row: JsonObject, store: LocalStore, userId: String): Boolean = when (table) {
        Tables.TASKS -> DbRowCodec.decodeTask(row).let {
            store.upsertIfNewer(table, it, TaskItem.serializer(), it.id, it.updatedAt)
        }
        Tables.SESSIONS -> DbRowCodec.decodeSession(row).let {
            store.upsertIfNewer(table, it, Session.serializer(), it.id, it.completedAt)
        }
        Tables.CAL_BLOCKS -> DbRowCodec.decodeCalBlock(row).let {
            store.upsert(table, it, CalBlock.serializer(), it.id); true
        }
        Tables.CAPTURES -> DbRowCodec.decodeCapture(row).let {
            store.upsertIfNewer(table, it, Capture.serializer(), it.id, it.at)
        }
        Tables.REASON_LOGS -> DbRowCodec.decodeReasonLog(row).let {
            store.upsertIfNewer(table, it, ReasonLog.serializer(), it.id, it.at)
        }
        // Collections: the incoming row carries neither members[] nor myRole (they
        // live in collection_members), so preserve whatever the local row knows —
        // the realtime.ts mergeKeep rule. A row we've never seen is owned by me
        // only when the server says so.
        Tables.COLLECTIONS -> {
            val m = DbRowCodec.decodeCollection(row)
            val existing = store.collections().first().firstOrNull { it.id == m.id }
            val merged = m.copy(
                members = existing?.members ?: emptyList(),
                myRole = existing?.myRole ?: (if (m.ownerId == userId) "owner" else null),
            )
            store.upsert(table, merged, ItemCollection.serializer(), m.id)
            true
        }
        Tables.TAGS -> DbRowCodec.decodeTag(row).let {
            store.upsert(table, it, TagRow.serializer(), it.id); true
        }
        Tables.LIFE_AREAS -> DbRowCodec.decodeLifeArea(row).let {
            store.upsert(table, it, LifeArea.serializer(), it.id); true
        }
        // profile_facts: a "forget" on another device arrives as active=false and is
        // kept as a local tombstone (every read filters it out).
        Tables.PROFILE_FACTS -> DbRowCodec.decodeProfileFact(row).let {
            store.upsertIfNewer(table, it, ProfileFact.serializer(), it.id, it.updatedAt)
        }
        Tables.CALL_REQUESTS -> DbRowCodec.decodeCallRequest(row).let {
            store.upsertIfNewer(table, it, CallRequest.serializer(), it.id, it.updatedAt)
        }
        else -> false
    }
}
