package tech.csalliance.unstuck.core.model

// Sharing + collaboration domain models + pure level logic. 1:1 port of the web
// lib/share-levels.ts (level semantics) and the exported interfaces in
// lib/use-circle.ts (CircleMember) + lib/use-task-shares.ts (ShareForTask,
// SharedWithMe, ShareBadge). Kept in :core so BOTH the sync client (which
// produces these from the SECURITY DEFINER RPC rows) and the later UI/ViewModels
// (which consume them) share one definition. camelCase throughout; the snake_case
// wire names + level strings live in the enum + the sync client's row DTOs.

/** Capability-based per-task sharing level (v3, migration 044). Wire: view / partner / assign.
 *   view    — recipient reads the task + is notified when the owner starts & finishes it.
 *   partner — view + either side can start/complete (live co-focus lands in phase 2).
 *   assign  — handed off as the recipient's task to do; owner keeps view + updates.
 * The RLS/RPC layer enforces these; the helpers keep the UI consistent with it. */
enum class ShareLevel(val wire: String) {
    VIEW("view"),
    PARTNER("partner"),
    ASSIGN("assign");

    /** Can the recipient act on the task (start / complete it)? View cannot.
     *  Mirrors levelCanComplete in share-levels.ts. */
    val canComplete: Boolean get() = this == PARTNER || this == ASSIGN

    /** The chip on the OWNER's own task row / "shared with" line — the level granted.
     *  Mirrors shareLevelLabel in share-levels.ts. */
    val ownerLabel: String get() = when (this) {
        VIEW -> "view"
        ASSIGN -> "assigned"
        PARTNER -> "partner"
    }

    companion object {
        /** Decode a wire string; an unknown/absent level degrades to VIEW (the
         *  least-capability level — a safe default if the server adds a level). */
        fun fromWire(value: String?): ShareLevel =
            entries.firstOrNull { it.wire == value } ?: VIEW
    }
}

/** The quiet chip on a "shared with you" row, from the RECIPIENT's side.
 *  Mirrors shareStatusLabel in share-levels.ts. */
fun shareStatusLabel(level: ShareLevel, done: Boolean): String = when {
    done -> "done"
    level == ShareLevel.VIEW -> "watching"
    level == ShareLevel.ASSIGN -> "yours"
    else -> "partner"
}

/** Status of a circle membership row. Mirrors the web union 'invited'|'active'|'revoked'. */
enum class CircleStatus(val wire: String) {
    INVITED("invited"),
    ACTIVE("active"),
    REVOKED("revoked");

    companion object {
        fun fromWire(value: String?): CircleStatus =
            entries.firstOrNull { it.wire == value } ?: INVITED
    }
}

/** A member (active) or pending invite of my trusted circle / connections list.
 *  Port of CircleMember in use-circle.ts. `level` here is the CIRCLE level
 *  ('view'|'comment') — distinct from per-task [ShareLevel] — so it stays a String. */
data class CircleMember(
    val id: String,
    val relationshipLabel: String?,
    val level: String,
    val status: CircleStatus,
    val inviteCode: String?,      // present only for pending invites (re-copy the link)
    val memberUserId: String?,    // the member's auth user id (active members)
    val memberName: String?,      // resolved display name for active members
    val createdAt: String,
) {
    /** Counts toward the roster like the web's activeCount (active + still-pending invites). */
    val isActiveOrInvited: Boolean get() = status == CircleStatus.ACTIVE || status == CircleStatus.INVITED
}

/** A single share on a task I own — drives the share sheet. Port of ShareForTask. */
data class ShareForTask(
    val shareId: String,
    val recipientUserId: String,
    val recipientName: String,
    val level: ShareLevel,
)

/** A task someone shared WITH me (read via the tasks_shared_with_me projection —
 *  RLS forbids reading the raw task row). Port of SharedWithMe. */
data class SharedWithMe(
    val shareId: String,
    val taskId: String,
    val ownerName: String,
    val level: ShareLevel,
    val title: String,
    val done: Boolean,   // every level projects the done state (v3)
)

/** Read-only detail for a task shared WITH me, from the shared_task_detail RPC
 *  (migration 045). Recipients still can't read the raw `tasks` row (RLS); this
 *  SECURITY DEFINER projection is the ONLY window, scoped to a share the caller holds
 *  at any level. Lets the recipient OPEN the task and see what it is — never edit it. */
data class SharedTaskDetail(
    val taskId: String,
    val ownerName: String,
    val level: ShareLevel,
    val title: String,
    val done: Boolean,
    val estimateMin: Int,
    val totalFocused: Int,
    val lifeArea: String?,
    val tags: List<String>,
    val objectives: List<Objective>,
    val dueAt: String?,
    val createdAt: String,
)

/** One outgoing share badge for my task row. Port of ShareBadge (the web keys these
 *  by taskId in a Record; each Android badge carries its taskId so a flat list groups). */
data class ShareBadge(
    val taskId: String,
    val level: ShareLevel,
    val recipientName: String,
)

/** taskId → assignee name, for tasks the current user has shared at 'assign'.
 *  Derives from the share badges (my_task_share_badges). Mirrors assignedOutMap
 *  in components/tasks/delegated-group.tsx. */
fun assignedOutMap(byTask: Map<String, List<ShareBadge>>): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for ((taskId, badges) in byTask) {
        badges.firstOrNull { it.level == ShareLevel.ASSIGN }?.let { out[taskId] = it.recipientName }
    }
    return out
}

/** The set of task ids the current user has assigned away — for excluding them from
 *  "Start Next" / "Up Next" recommendations (they're someone else's now). Mirrors
 *  assignedOutIds in components/tasks/delegated-group.tsx. */
fun assignedOutIds(byTask: Map<String, List<ShareBadge>>): Set<String> =
    byTask.filterValues { badges -> badges.any { it.level == ShareLevel.ASSIGN } }.keys

// ── Co-focus presence (M5) — live "who's here with me" for a partner-shared task,
// built on Supabase Realtime Presence. Port of the CoFocusState / CoFocusPeer types
// in lib/cofocus-presence.ts. Kept in :core so the :sync engine (which decodes the
// presence payloads) and the UI (which renders peers) share one definition. ──

/** How you appear to the other side on a co-focus channel. Mirrors the web union.
 *   focusing — you're in a focus session on this task (owner side).
 *   here     — you're sitting with them / body-doubling (recipient "Sit with them").
 * Observe-only (broadcast nothing) is the null track, not a value here. */
enum class CoFocusState(val wire: String) {
    FOCUSING("focusing"),
    HERE("here");

    companion object {
        /** Decode a wire string; anything that isn't "focusing" degrades to HERE
         *  (mirrors the web `m.state === 'focusing' ? 'focusing' : 'here'`). */
        fun fromWire(value: String?): CoFocusState = if (value == FOCUSING.wire) FOCUSING else HERE
    }
}

/** A focusing peer's LIVE session timer, carried in the presence payload so the other
 *  side renders the SAME running/paused mm:ss — a SHARED VIEW, never a remote control.
 *  Minor clock skew (a few seconds) is fine; this is a calm indicator, not a synced
 *  machine. All epoch ms. Wire names (sessionStartMs / paused / pausedAtMs / estimateMin)
 *  ship IDENTICALLY on web + iOS + Android so the three interoperate on one channel.
 *  Only a FOCUSING peer with a live session carries one (HERE / observe → null). */
data class CoFocusTimer(
    val sessionStartMs: Long,   // resume-adjusted: elapsed = now − sessionStartMs while running
    val paused: Boolean,
    val pausedAtMs: Long?,      // epoch ms when paused, else null
    val estimateMin: Int,       // the focusing peer's session estimate
)

/** Another participant present on a co-focus channel (never yourself). Port of CoFocusPeer.
 *  [timer] is non-null only for a FOCUSING peer broadcasting a live session. */
data class CoFocusPeer(
    val userId: String,
    val name: String,
    val state: CoFocusState,
    val sinceMs: Long,
    val timer: CoFocusTimer? = null,
)

/** Seconds elapsed in a focusing peer's shared session: FROZEN at the pause point while
 *  paused, else wall-clock since (resume-adjusted) start. Clamped at ≥ 0. Pure — the SAME
 *  computation runs on web + iOS + Android so the shared timer agrees across platforms. */
fun coFocusElapsedSec(timer: CoFocusTimer, now: Long): Int {
    val elapsedMs =
        if (timer.paused && timer.pausedAtMs != null) timer.pausedAtMs - timer.sessionStartMs
        else now - timer.sessionStartMs
    return (elapsedMs / 1000L).coerceAtLeast(0L).toInt()
}

/** Seconds remaining against the peer's session estimate; may go negative on overrun
 *  (the caller decides how to render that). Pure — same math on every platform. */
fun coFocusRemainingSec(timer: CoFocusTimer, now: Long): Int =
    timer.estimateMin * 60 - coFocusElapsedSec(timer, now)

/** First name for the calm "X is here with you" label. Mirrors firstName() in cofocus.tsx. */
fun coFocusFirstName(name: String): String =
    (name.ifBlank { "Someone" }).split(' ', '\t', '\n', '@').firstOrNull { it.isNotBlank() } ?: "Someone"

/** "Sam", "Sam & Jo", or "Sam & 2 others". Mirrors peopleLabel() in cofocus.tsx. */
fun coFocusPeopleLabel(peers: List<CoFocusPeer>): String {
    val names = peers.map { coFocusFirstName(it.name) }
    return when (names.size) {
        0 -> ""
        1 -> names[0]
        2 -> "${names[0]} & ${names[1]}"
        else -> "${names[0]} & ${names.size - 1} others"
    }
}
