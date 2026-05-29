package tech.csalliance.unstuck.sync

import tech.csalliance.unstuck.core.logic.isExternalBlock
import tech.csalliance.unstuck.core.model.CalBlock

// Pure sync-engine decisions extracted from the web bootstrap listener +
// hydrator so they're unit-testable without a network or DB. Port of the iOS
// SyncDecisions.swift.

/** Auth events that drive cache-wipe decisions (subset of the SDK's session
 *  sources the coordinator acts on). */
enum class SyncAuthEvent { SIGNED_IN, INITIAL_SESSION, USER_UPDATED }

object SyncDecision {

    /** Cache-wipe rule (mirrors bootstrap-listener.tsx):
     *  - SIGNED_IN: always wipe (fresh sign-in on this device).
     *  - INITIAL_SESSION: wipe only if the user changed since last run.
     *  - USER_UPDATED: never wipe (same user, metadata change). */
    fun shouldWipeCache(event: SyncAuthEvent, prevUserId: String?, currentUserId: String): Boolean =
        when (event) {
            SyncAuthEvent.SIGNED_IN -> true
            SyncAuthEvent.INITIAL_SESSION -> prevUserId != currentUserId
            SyncAuthEvent.USER_UPDATED -> false
        }

    /** Hydrate merge for cal_blocks: the server set is canonical, but locally-
     *  cached external (Google `g_`) blocks live only on-device (their ids
     *  aren't UUIDs so they never round-trip to Postgres), so preserve them
     *  across the replace. Remote rows win on id collision. */
    fun mergeHydratedCalBlocks(remote: List<CalBlock>, localExternal: List<CalBlock>): List<CalBlock> {
        val byId = LinkedHashMap<String, CalBlock>()
        for (b in localExternal) if (isExternalBlock(b)) byId[b.id] = b
        for (b in remote) byId[b.id] = b
        return byId.values.toList()
    }
}
