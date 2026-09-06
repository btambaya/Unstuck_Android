package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.LiveSession
import tech.csalliance.unstuck.core.model.ShareBadge
import tech.csalliance.unstuck.core.model.ShareLevel

/**
 * Does finalizing [live] accrue total_focused EXCLUSIVELY via the log_shared_focus
 * ledger (one-true-shared-session, exactly-once per session id) rather than the
 * direct `totalFocused += elapsed` bump?
 *
 * The share-badge cache is only ONE signal: on a cold start / offline relaunch the
 * badges RPC hasn't resolved (the map is empty), and routing by it alone made the
 * owner do the direct bump while the partner's finalize of the SAME session id also
 * landed in the ledger — the task was credited twice. The live blob itself proves a
 * session was shared-broadcast: a recipient session carries [LiveSession.sharedTitle]
 * / partner level, and any owner session that was announced or applied a partner
 * control carries a rev stamp. Mirrors iOS accruesViaSharedLedger + web
 * FocusCommands.end.
 */
fun accruesViaSharedLedger(live: LiveSession, taskId: String, badges: Map<String, List<ShareBadge>>): Boolean =
    live.sharedTitle != null ||
        live.sharedLevel == ShareLevel.PARTNER.wire ||
        live.sharedSessionRev != null ||
        live.lastAppliedRev != null ||
        badges[taskId].orEmpty().any { it.level == ShareLevel.PARTNER }
