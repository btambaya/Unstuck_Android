package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CircleMember
import tech.csalliance.unstuck.core.model.CircleStatus
import tech.csalliance.unstuck.core.model.PendingInvite
import tech.csalliance.unstuck.core.model.PendingInviteKind
import tech.csalliance.unstuck.core.model.ShareLevel
import java.text.Normalizer

// Unified sharing v1 (docs/unified-sharing-spec.md §2 / §4) — the ONE
// vocabulary and the pure pieces behind the single Share screen. 1:1 port of
// iOS Sources/UnstuckCore/Logic/UnifiedSharing.swift:
//
//   • ShareAccess — "Can edit" / "Can view", mapped onto the two backends
//     (tasks: partner / view; collections: editor / viewer). "Assign" is no
//     longer a share level in the UI: it is the task action "Hand over to…".
//   • composeSharePeople — the People section (everyone you are connected
//     to, each annotated with what they already have on this item).
//   • sharePeopleSplit / sharePeopleCandidates — who already HAS the item
//     (listed inline with an access menu) vs. who could (the searchable
//     "Choose someone" picker). The whole roster is never listed inline.
//   • shareResultLine — the honest line under the button ("Shared with Maya
//     — they can edit." vs "Invite sent to x@y — waiting for them to sign
//     up." vs "Link copied — …").
//   • ShareFailure — the server's reason codes → what the user reads.
//   • pendingInviteLabel / composePeopleSections — Settings → People's
//     "Waiting to join" list (§2 "One place for people"): every email invite
//     I sent, whichever screen sent it, each shown once.
//
// Pure so the screen model, the People screen and the unit tests share one
// source of truth for copy and mapping.

/** The single user-facing access grade. Default is [EDIT]. */
enum class ShareAccess {
    EDIT,
    VIEW;

    /** The segmented-control / menu label. */
    val label: String get() = when (this) {
        EDIT -> "Can edit"
        VIEW -> "Can view"
    }

    /** The short verb phrase used in result lines ("they can edit"). */
    val verb: String get() = when (this) {
        EDIT -> "edit"
        VIEW -> "view"
    }

    /** What the grade means for THIS kind of item. A list is never "started"
     *  or "focused", so the task wording read as nonsense on a list's Share
     *  screen. */
    fun blurb(kind: ShareItemKind): String = when (kind) {
        ShareItemKind.TASK -> when (this) {
            EDIT -> "They can start, complete and focus on it with you."
            VIEW -> "They see it and hear when you start and finish."
        }
        ShareItemKind.COLLECTION -> when (this) {
            EDIT -> "They can add, tick off and edit everything on the list."
            VIEW -> "They can see the list and everything on it."
        }
    }

    /** The task-share capability grade this maps to (`task_share.p_level`). */
    val taskLevel: ShareLevel get() = when (this) {
        EDIT -> ShareLevel.PARTNER
        VIEW -> ShareLevel.VIEW
    }

    /** The collection role this maps to (`share-collection.role`). */
    val collectionRole: String get() = when (this) {
        EDIT -> "editor"
        VIEW -> "viewer"
    }

    companion object {
        /** Reverse mapping from a task share level. `assign` is not an access
         *  grade (it is "handed over") → null. */
        fun fromTaskLevel(level: ShareLevel): ShareAccess? = when (level) {
            ShareLevel.PARTNER -> EDIT
            ShareLevel.VIEW -> VIEW
            ShareLevel.ASSIGN -> null
        }

        /** Reverse mapping from a collection role. Anything but "viewer" is edit
         *  (the server itself coerces unknown roles to editor). */
        fun fromCollectionRole(role: String?): ShareAccess = if (role == "viewer") VIEW else EDIT
    }
}

/** What is being shared. Drives the vocabulary ("task" / "list") and which
 *  backend the screen talks to. */
enum class ShareItemKind {
    TASK,
    COLLECTION;

    /** The noun in copy ("this task" / "this list"). */
    val noun: String get() = when (this) {
        TASK -> "task"
        COLLECTION -> "list"
    }
}

/** A grant that already exists on the item (a task share / a collection
 *  member) — the input the People composition annotates the roster with. */
data class ShareExistingGrant(
    val userId: String,
    /** A display name when the roster doesn't know this person (a legacy
     *  collection member who never became a connection). */
    val name: String? = null,
    /** Known for collection members (the edge function lists them by email);
     *  null for task shares. */
    val email: String? = null,
    /** null ⇒ the grant is `assign` (handed over), see [handedOver]. */
    val access: ShareAccess?,
    val handedOver: Boolean = false,
    /** The task share id (for `task_unshare`); null for collection members. */
    val shareId: String? = null,
)

/** One row of the People section. */
data class SharePersonRow(
    val id: String,
    val userId: String,
    val name: String,
    /** The relationship label ("Coach") when set. */
    val subtitle: String? = null,
    /** Known only when the grant carried it (collection members). */
    val email: String? = null,
    /** What they have on this item now; null = not shared with them yet. */
    val access: ShareAccess? = null,
    /** The task was handed over to them (level `assign`). */
    val handedOver: Boolean = false,
    /** The task share id, when shared (drives revoke). */
    val shareId: String? = null,
) {
    /** True when they hold anything on the item (a grade or a hand-over). */
    val isShared: Boolean get() = access != null || handedOver

    /** The trailing status text: "Can edit" / "Can view" / "Handed over". */
    val statusLabel: String? get() = if (handedOver) "Handed over" else access?.label
}

/** One pending (email) invite on the item — shown under "Someone new". */
data class SharePendingRow(
    val id: String,
    val email: String,
    val access: ShareAccess,
)

/** The People section: every ACTIVE connection (roster order), annotated with
 *  the grant they already hold on this item, followed by anyone who holds a
 *  grant but is not (yet) a connection — a legacy collection member — so an
 *  existing share is never invisible. Pending circle invites are NOT people
 *  (they show under Settings → People with their email). */
fun composeSharePeople(circle: List<CircleMember>, grants: List<ShareExistingGrant>): List<SharePersonRow> {
    val byUser = LinkedHashMap<String, ShareExistingGrant>()
    for (g in grants) if (g.userId.isNotEmpty()) byUser[g.userId] = g
    val rows = ArrayList<SharePersonRow>()
    val seen = HashSet<String>()
    for (m in circle) {
        if (m.status != CircleStatus.ACTIVE) continue
        val uid = m.memberUserId ?: continue
        if (uid.isEmpty() || !seen.add(uid)) continue
        val g = byUser[uid]
        rows += SharePersonRow(
            id = m.id, userId = uid,
            name = firstNonEmptyName(listOf(m.memberName, g?.name, g?.email)),
            subtitle = m.relationshipLabel, email = g?.email,
            access = g?.access, handedOver = g?.handedOver ?: false, shareId = g?.shareId,
        )
    }
    for (g in grants) {
        if (g.userId.isEmpty() || !seen.add(g.userId)) continue
        rows += SharePersonRow(
            id = "grant:${g.userId}", userId = g.userId,
            name = firstNonEmptyName(listOf(g.name, g.email)),
            subtitle = null, email = g.email,
            access = g.access, handedOver = g.handedOver, shareId = g.shareId,
        )
    }
    return rows
}

/** The first non-blank candidate, trimmed; "Someone" when there is none. */
private fun firstNonEmptyName(candidates: List<String?>): String {
    for (c in candidates) {
        val t = c?.trim() ?: continue
        if (t.isNotEmpty()) return t
    }
    return "Someone"
}

// ── People section · order + search ─────────────────────────────────────────

/** Shared-first, roster order preserved inside each half. [pinned] = the ids
 *  that already held the item when the screen OPENED, so a row never moves
 *  under the finger after a tap. */
fun sharePeopleOrdered(people: List<SharePersonRow>, pinned: Set<String>): List<SharePersonRow> =
    people.filter { it.id in pinned } + people.filter { it.id !in pinned }

/** Diacritic- and case-insensitive prefix-of-word over name, relationship and
 *  email. Every query term must prefix some word. */
fun sharePersonMatches(row: SharePersonRow, query: String): Boolean {
    val terms = fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return true
    val words = fold(listOf(row.name, row.subtitle ?: "", row.email ?: "").joinToString(" "))
        .split(Regex("[\\s@.]+")).filter { it.isNotEmpty() }
    return terms.all { t -> words.any { it.startsWith(t) } }
}

/** Case fold + strip combining marks ("Zoë" → "zoe"), so a search never fails
 *  on an accent the typist doesn't have on their keyboard. */
private fun fold(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

/** The People section never lists a whole roster: it shows the people who
 *  already have the item (each with its access menu) and hands everyone else
 *  to a searchable picker. Ahmad, 2026-09-17: "ten people is a wall". */
data class SharePeopleSplit(
    /** Already shared / handed over — pinned people stay here through a
     *  reload so a row never disappears under the finger. */
    val withAccess: List<SharePersonRow>,
    /** Everyone else, in roster order — the picker's list. */
    val candidates: List<SharePersonRow>,
)

fun sharePeopleSplit(people: List<SharePersonRow>, pinned: Set<String>, handOver: Boolean): SharePeopleSplit {
    val ordered = sharePeopleOrdered(people, pinned)
    val has: (SharePersonRow) -> Boolean = { if (handOver) it.handedOver else (it.isShared || it.id in pinned) }
    return SharePeopleSplit(withAccess = ordered.filter(has), candidates = ordered.filterNot(has))
}

/** The picker's rows for a query: every candidate when the query is blank,
 *  otherwise the ones whose name / label / email match it (case-insensitive). */
fun sharePeopleCandidates(candidates: List<SharePersonRow>, query: String): List<SharePersonRow> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return candidates
    return candidates.filter { sharePersonMatches(it, trimmed) }
}

// ── result lines (§2 "Feedback that is true") ───────────────────────────────

/** A successful outcome, in the vocabulary of §2 — rendered by [shareResultLine]. */
sealed class ShareResult {
    /** An existing account got the item right away. */
    data class Shared(val name: String, val access: ShareAccess) : ShareResult()
    /** No account for that email yet — invite stored, email sent. */
    data class Invited(val email: String) : ShareResult()
    /** The server took the share but does not say whether the address had an
     *  account (`share-collection add` deliberately answers the same for
     *  both) — the line stays neutral rather than guessing. */
    data class Accepted(val email: String) : ShareResult()
    /** A join link was copied / handed to the system share sheet. */
    data class LinkCopied(val kind: ShareItemKind) : ShareResult()
    /** The task was handed over (level `assign`). */
    data class HandedOver(val name: String) : ShareResult()
    /** Their grade changed. */
    data class AccessChanged(val name: String, val access: ShareAccess) : ShareResult()
    /** They no longer have the item. */
    data class Removed(val name: String) : ShareResult()
    /** A pending email invite was cancelled. */
    data class InviteCancelled(val email: String) : ShareResult()
}

/** The honest line under the button (§2 "Feedback that is true"). */
fun shareResultLine(r: ShareResult): String = when (r) {
    is ShareResult.Shared -> "Shared with ${shareShortName(r.name)} — they can ${r.access.verb}."
    is ShareResult.Invited -> "Invite sent to ${r.email} — waiting for them to sign up."
    is ShareResult.Accepted -> "Shared with ${r.email} — they'll see it as soon as they're in."
    is ShareResult.LinkCopied -> "Link copied — whoever opens it gets this ${r.kind.noun}."
    is ShareResult.HandedOver -> "Handed over to ${shareShortName(r.name)} — it's their task now; you keep view."
    is ShareResult.AccessChanged -> "${shareShortName(r.name)} can now ${r.access.verb}."
    is ShareResult.Removed -> "${shareShortName(r.name)} no longer has this."
    is ShareResult.InviteCancelled -> "Invite to ${r.email} cancelled."
}

// ── failures ────────────────────────────────────────────────────────────────

/** Why a share did not happen — the server's reason codes and the local
 *  guards, mapped to what the user reads. */
sealed class ShareFailure {
    /** The email is the caller's own. */
    data object SelfShare : ShareFailure()
    /** A device-local block, or the server's `blocked`. */
    data object Blocked : ShareFailure()
    /** The limiter refused (`rate_limited`). */
    data object RateLimited : ShareFailure()
    /** The item is gone / unknown. */
    data object NotFound : ShareFailure()
    /** Not the owner / no permission. */
    data object NotAllowed : ShareFailure()
    /** The address didn't parse (locally or `invalid_email` / `bad_request`). */
    data object InvalidEmail : ShareFailure()
    /** No signed-in transport (demo boot / signed out). */
    data object NotSignedIn : ShareFailure()
    /** `task_share` still needs an active connection (`not_in_circle`). */
    data object NotConnected : ShareFailure()
    /** Sharing a LIST with a connection by name: the collection function
     *  resolves emails only and the roster has none (contract gap). */
    data object ListNeedsEmail : ShareFailure()
    /** Offline / a non-2xx with no readable reason. */
    data object Network : ShareFailure()
    /** Any other server code, kept for the log. */
    data class Server(val code: String) : ShareFailure()

    /** The line the user reads. */
    val message: String get() = when (this) {
        SelfShare -> "That's you."
        Blocked -> "You've blocked that person."
        RateLimited -> "Too many invites right now — try again in a few minutes."
        NotFound -> "Couldn't find that — it may have been deleted."
        NotAllowed -> "Only the owner can share this."
        InvalidEmail -> "That doesn't look like an email address."
        NotSignedIn -> "Sign in to share."
        NotConnected -> "You're not connected yet — share by email or a link below."
        ListNeedsEmail -> "Lists can't be shared by name yet — enter their email below."
        Network, is Server -> "Couldn't share — try again."
    }

    companion object {
        /** Map a server reason / error code (share-task `reason`, share-collection
         *  `error`, circle-invite `error`) to a failure. null / "network" → Network. */
        fun fromReason(reason: String?): ShareFailure = when (val code = (reason ?: "").trim().lowercase()) {
            "self" -> SelfShare
            "blocked" -> Blocked
            "rate_limited", "rate_limit", "too_many_requests", "circle_full" -> RateLimited
            "not_found" -> NotFound
            "forbidden", "not_owner", "not_your_task", "unauthorized", "not_allowed" -> NotAllowed
            "invalid_email", "bad_request", "bad_email" -> InvalidEmail
            "not_configured", "signed_out" -> NotSignedIn
            "not_in_circle" -> NotConnected
            "", "network", "invite_failed", "server_error" -> Network
            else -> Server(code)
        }

        /** The reason code a thrown RPC carries. PostgREST surfaces a function's
         *  `raise exception '<code>'` in the error message, so the known codes
         *  are looked for inside it; anything else reads as "network". */
        fun reasonFromMessage(message: String?): String {
            val m = message?.lowercase() ?: return "network"
            val known = listOf(
                "not_in_circle", "not_your_task", "not_owner", "not_allowed", "bad_level", "unauthorized",
                "forbidden", "not_found", "rate_limited", "self", "blocked",
            )
            return known.firstOrNull { m.contains(it) } ?: "network"
        }
    }
}

// ── helpers ─────────────────────────────────────────────────────────────────

/** A loose "is this an email address?" — enough to route the assistant's
 *  `person` argument and to guard the Someone-new field before a round trip. */
fun isEmailLike(raw: String): Boolean {
    val s = raw.trim()
    val at = s.indexOf('@')
    if (at <= 0) return false
    val domain = s.substring(at + 1)
    if (domain.isEmpty() || !domain.contains('.') || domain.startsWith(".") || domain.endsWith(".")) return false
    return s.none { it.isWhitespace() } && s.count { it == '@' } == 1
}

/** Normalise an address the way the server does (trim + lower-case). */
fun normalizedShareEmail(raw: String): String = raw.trim().lowercase()

/** "Maya" from "Maya Chen" / "maya@x.com" — the first name used in result lines. */
fun shareShortName(raw: String): String {
    val n = raw.trim()
    if (n.isEmpty()) return "them"
    if (n.contains('@')) return n.substringBefore('@').ifEmpty { n }
    return n.split(Regex("\\s+")).firstOrNull { it.isNotEmpty() } ?: n
}

/** The one-line explainer on the Hand-over picker (§2). */
const val HAND_OVER_EXPLAINER = "It becomes their task to do — you keep view and hear when it's done."

// ── Settings → People · "Waiting to join" (§2 "One place for people") ───────

/** What a pending invite is for, in the ONE vocabulary — the subtitle of a
 *  Waiting-to-join row: "Draft the deck · can edit", "Groceries · can view",
 *  "your people". A missing item name degrades to "a task" / "a list"; an
 *  unknown grade drops the suffix rather than inventing one. */
fun pendingInviteLabel(p: PendingInvite): String = when (p.kind) {
    PendingInviteKind.CIRCLE -> "your people"
    PendingInviteKind.TASK -> {
        val name = nonBlank(p.itemName) ?: "a task"
        val grade = when ((p.access ?: "").lowercase()) {
            "partner" -> "can edit"
            "view" -> "can view"
            "assign" -> "handed over"
            else -> null
        }
        if (grade != null) "$name · $grade" else name
    }
    PendingInviteKind.COLLECTION -> {
        val name = nonBlank(p.itemName) ?: "a list"
        val grade = when ((p.access ?: "").lowercase()) {
            "editor" -> "can edit"
            "viewer" -> "can view"
            else -> null
        }
        if (grade != null) "$name · $grade" else name
    }
}

/** The two lists Settings → People renders from one `circle_list()` and one
 *  `my_pending_invites()`. */
data class PeopleSections(
    /** Active connections + the pending roster rows the RPC did NOT report
     *  (link-only invites, or every pending row on a server without the RPC). */
    val roster: List<CircleMember>,
    /** Every outstanding invite, RPC order (createdAt desc), each listed once. */
    val waiting: List<PendingInvite>,
)

/** Compose the People screen. A circle invite the RPC reports (kind `circle`)
 *  REPLACES its roster pending row — matched by `trusted_circle.id`, or, as a
 *  belt-and-braces second key, by the invited address — so the same invite is
 *  never shown twice; the roster row's `invite_code` is carried onto the
 *  waiting row so "Copy link" still works. Anything the RPC does not know
 *  stays in the roster exactly as before, which is what keeps the screen
 *  whole on a server where `my_pending_invites` does not exist yet (the
 *  transport answers `[]`). Duplicate RPC rows collapse to the first. */
fun composePeopleSections(circle: List<CircleMember>, pending: List<PendingInvite>): PeopleSections {
    val invited = circle.filter { it.status == CircleStatus.INVITED }
    val pendingById = LinkedHashMap<String, CircleMember>()
    for (m in invited) pendingById.putIfAbsent(m.id, m)
    val pendingByEmail = LinkedHashMap<String, CircleMember>()
    for (m in invited) nonBlank(m.inviteeEmail)?.let { pendingByEmail.putIfAbsent(it.lowercase(), m) }

    val seen = HashSet<String>()
    val waiting = ArrayList<PendingInvite>()
    val replaced = HashSet<String>()
    for (raw in pending) {
        if (!seen.add(raw.id)) continue
        var p = raw
        if (p.kind == PendingInviteKind.CIRCLE) {
            val match = pendingById[p.inviteId] ?: nonBlank(p.email)?.let { pendingByEmail[it.lowercase()] }
            if (match != null) {
                replaced += match.id
                if (p.inviteCode == null) p = p.copy(inviteCode = match.inviteCode)
                if (p.email.isEmpty()) nonBlank(match.inviteeEmail)?.let { p = p.copy(email = it) }
            }
        }
        waiting += p
    }
    val roster = circle.filter { !(it.status == CircleStatus.INVITED && it.id in replaced) }
    return PeopleSections(roster = roster, waiting = waiting)
}

/** Trimmed, or null when blank / absent. */
private fun nonBlank(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() }
