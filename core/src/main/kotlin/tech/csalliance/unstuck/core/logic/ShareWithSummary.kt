package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CircleMember
import tech.csalliance.unstuck.core.model.CircleStatus
import tech.csalliance.unstuck.core.model.ShareLevel

// The New task sheet's ONE "Share with…" row. It replaced a stack of per-person
// cards, each with a full-width Off / View / Partner / Assign switch (Ahmad,
// 2026-09-24: "This is terrible" → "One row + picker"). The row opens the Share
// screen in its PRE-CREATE mode (ShareTarget.NewTask) and reads back what was
// picked as one line:
//
//   Only you                      nothing picked
//   James · can edit              one person
//   James, Anna · can edit        everyone at the same grade
//   James · edit, Anna · view     grades differ
//   James + 3 more · can edit     too long for the row (never wraps)
//
// Pure so the sheet, the screen and the tests share one source of truth.

/** One person picked on the New task sheet: a display name and the level the
 *  create applies (`task_share.p_level`). */
data class SharePick(val name: String, val level: ShareLevel)

/** The longest summary the row shows in full. Past it the summary compacts to
 *  "First + N more", so the row stays one line at phone width (a 13sp summary
 *  beside the "Share with…" label). The Text also ellipsizes as a backstop. */
const val SHARE_SUMMARY_MAX = 28

/** A truncated first name keeps at least this many letters before the "…". */
private const val MIN_NAME = 4

/** The summary when nothing is picked. */
const val SHARE_SUMMARY_NONE = "Only you"

/** The picks in ROSTER order (the order the Share screen lists people), each
 *  with the roster's display name. A pick the roster doesn't know (yet) reads
 *  "Someone" and goes last, so it is never silently dropped from the summary. */
fun sharePicksInRosterOrder(roster: List<CircleMember>, picks: Map<String, ShareLevel>): List<SharePick> {
    if (picks.isEmpty()) return emptyList()
    val out = ArrayList<SharePick>(picks.size)
    val seen = HashSet<String>()
    for (m in roster) {
        if (m.status != CircleStatus.ACTIVE) continue
        val uid = m.memberUserId ?: continue
        val level = picks[uid] ?: continue
        if (!seen.add(uid)) continue
        out += SharePick(m.memberName?.trim()?.takeIf { it.isNotEmpty() } ?: "Someone", level)
    }
    for ((uid, level) in picks) if (seen.add(uid)) out += SharePick("Someone", level)
    return out
}

/** The one-line summary on the "Share with…" row (see the table above). */
fun shareWithSummary(picks: List<SharePick>, max: Int = SHARE_SUMMARY_MAX): String {
    if (picks.isEmpty()) return SHARE_SUMMARY_NONE
    val names = picks.map { shareShortName(it.name.ifBlank { "Someone" }) }
    val uniform = picks.all { it.level == picks[0].level }
    val full = if (uniform) names.joinToString(", ") + " · " + gradeLong(picks[0].level)
    else picks.indices.joinToString(", ") { "${names[it]} · ${gradeShort(picks[it].level)}" }
    if (full.length <= max) return full

    // Compact: the first name, "+ N more", and the grade when everyone shares it.
    val more = if (picks.size > 1) " + ${picks.size - 1} more" else ""
    val grade = if (uniform) " · " + gradeLong(picks[0].level) else ""
    val first = names[0]
    val room = max - more.length - grade.length
    if (first.length <= room) return first + more + grade
    if (room > MIN_NAME) return clip(first, room) + more + grade
    // Not even a short name fits beside the grade: keep the name, drop the grade.
    val roomNoGrade = max - more.length
    return (if (first.length <= roomNoGrade) first else clip(first, maxOf(roomNoGrade, MIN_NAME + 1))) + more
}

/** What a screen reader announces for the row (plus the Button role): "Share
 *  with, Only you" / "Share with, James · can edit". */
fun shareWithLabel(summary: String): String = "Share with, $summary"

/** The honest line after a pick on the PRE-CREATE Share screen. Nothing is
 *  shared until the task is added, so it says what WILL happen — never
 *  "Shared with …". [level] null = the pick was removed. */
fun sharePreCreateLine(name: String, level: ShareLevel?): String {
    val who = shareShortName(name)
    return when (level) {
        ShareLevel.PARTNER -> "$who can edit once you add the task."
        ShareLevel.VIEW -> "$who can view once you add the task."
        ShareLevel.ASSIGN -> "$who gets it as their task once you add it — you keep view."
        null -> "$who won't get this task."
    }
}

/** The grade in a one-grade summary: "can edit" / "can view" / "handed over". */
private fun gradeLong(level: ShareLevel): String =
    ShareAccess.fromTaskLevel(level)?.label?.lowercase() ?: "handed over"

/** The grade beside each name when grades differ: "edit" / "view" / "handed over". */
private fun gradeShort(level: ShareLevel): String =
    ShareAccess.fromTaskLevel(level)?.verb ?: "handed over"

/** [s] cut to [room] characters, the last one an ellipsis. */
private fun clip(s: String, room: Int): String = s.take(room - 1).trimEnd() + "…"
