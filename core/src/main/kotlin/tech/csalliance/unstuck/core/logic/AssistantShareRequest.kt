package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.core.model.TaskItem

// Port of lib/assistant/share-request.ts. Assistant share requests — the ONE
// agent action that sends a user's content to another person, so it never
// executes on the model's say-so. The tool RESOLVES and STAGES a request (task +
// circle member + level); the panel renders a confirm card; only the user's tap
// performs the share RPC.
//
// Ahmad, 2026-08-02 ("yes wire shared task tool") — built with the confirm gate
// proposed alongside it, matching the assistant guardrail rule that high-impact
// actions ask first.

/** A person in the user's trusted circle who can actually receive a share. */
data class ShareCandidate(val userId: String, val name: String)

/** What the user's tap resolved a staged share to. */
enum class ShareOutcome { SHARED, DISMISSED, FAILED }

data class PendingShare(
    /** Stable id so the confirm card can key/dedupe. */
    val id: String,
    val taskId: String,
    val taskName: String,
    val recipientUserId: String,
    val recipientName: String,
    val level: ShareLevel,
    /** Set once the user confirms or dismisses — the card stops offering. */
    val outcome: ShareOutcome? = null,
)

/** Loose name match: exact (case-insensitive), then first-name, then prefix.
 *  Every fuzzy tier must be UNAMBIGUOUS — with two Anas in the circle, "Ana"
 *  resolves to nobody and the agent has to ask which one. Sharing to the wrong
 *  person is unrecoverable, so silence beats a confident guess. */
fun matchCandidate(who: String, people: List<ShareCandidate>): ShareCandidate? {
    val q = who.trim().lowercase()
    if (q.isEmpty()) return null
    fun norm(p: ShareCandidate) = p.name.trim().lowercase()
    val exact = people.filter { norm(it) == q }
    if (exact.size == 1) return exact[0]
    if (exact.size > 1) return null
    val first = people.filter { norm(it).split(Regex("\\s+")).firstOrNull() == q }
    if (first.size == 1) return first[0]
    if (first.size > 1) return null
    val starts = people.filter { norm(it).startsWith(q) }
    return if (starts.size == 1) starts[0] else null
}

/** The three real levels; anything else degrades to the least-permissive view. */
fun normalizeLevel(raw: String?): ShareLevel {
    val v = raw?.trim()?.lowercase().orEmpty()
    return ShareLevel.entries.firstOrNull { it.wire == v } ?: ShareLevel.VIEW
}

data class ResolveShareResult(
    /** Staged request awaiting the user's tap; null in every refusal path. */
    val pending: PendingShare? = null,
    /** Result string handed back to the model. */
    val message: String,
)

/**
 * Resolve a share request into something the user can confirm. NEVER performs the
 * share. Returns a model-readable message in all paths so the agent can explain
 * itself (e.g. "no one in your circle matches 'Sam'").
 */
fun resolveShareRequest(
    taskId: String?,
    taskName: String?,
    person: String?,
    level: String?,
    tasks: List<TaskItem>,
    people: List<ShareCandidate>,
    newId: () -> String,
): ResolveShareResult {
    val task = when {
        taskId != null -> tasks.firstOrNull { it.id == taskId }
        taskName != null -> {
            val q = taskName.trim().lowercase()
            tasks.firstOrNull { it.name.trim().lowercase() == q }
                ?: tasks.firstOrNull { it.name.lowercase().contains(q) }
        }
        else -> null
    } ?: return ResolveShareResult(message = "error: task not found — ask which task they mean")

    if (people.isEmpty()) {
        return ResolveShareResult(
            message = "error: the user has nobody in their trusted circle yet — tell them to add someone in Settings → People first",
        )
    }
    val who = person.orEmpty()
    val match = matchCandidate(who, people)
        ?: return ResolveShareResult(
            message = "error: no circle member matches \"$who\" — their circle is: " +
                people.joinToString(", ") { it.name } + ". Ask which person.",
        )

    val lvl = normalizeLevel(level)
    return ResolveShareResult(
        pending = PendingShare(
            id = newId(),
            taskId = task.id,
            taskName = task.name,
            recipientUserId = match.userId,
            recipientName = match.name,
            level = lvl,
        ),
        message = "ok: prepared a share of \"${task.name}\" with ${match.name} (${lvl.wire}). " +
            "The user must CONFIRM it on screen — tell them it's ready to confirm, and do not claim it is shared.",
    )
}
