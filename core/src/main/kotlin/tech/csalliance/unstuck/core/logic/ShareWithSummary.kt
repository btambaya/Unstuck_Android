package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CircleMember
import tech.csalliance.unstuck.core.model.ShareLevel

// The New task sheet's ONE "Share with…" row. It replaced a stack of per-person
// cards, each with a full-width Off / View / Partner / Assign switch (Ahmad,
// 2026-09-24: "This is terrible" → "One row + picker"). The row opens the Share
// screen in its PRE-CREATE mode (ShareTarget.NewTask) and reads back what was
// picked as one line. ONE rule on iOS, Android and web (2026-09-24 alignment):
//
//   0 picked               Only you
//   1                      James · can edit        (· can view / · handed over)
//   2, one grade           James, Anna · can edit
//   2, grades differ       James · edit, Anna · view
//   3 or more              James + 2 more · can edit   (the grade ONLY when
//                          everyone has the same one) / James + 2 more
//
// Names are first names ("James" from "James Wilson"; the part before the @ of
// a typed address; a blank name is "Someone"), in PICK order — connections
// first, then typed addresses. The line is held to [SHARE_SUMMARY_MAX]
// characters: a long name is cut with "…" BEFORE the grade, so the grade always
// shows. With two names, a name no longer than half the room stays whole and
// the other gets the rest; when both are longer, the first gets the larger half.
//
// Pure so the sheet, the screen and the tests share one source of truth.

/** One pick on the New task sheet: a display name (a typed address is its own
 *  name) and the level the create applies (`task_share.p_level`). */
data class SharePick(val name: String, val level: ShareLevel)

/** What the New task sheet will share once the task is added. Nothing leaves the
 *  device before "Add task"; a cancelled sheet sends nothing.
 *
 *  [people]: connection user id → level (`task_share` on submit). [emails]: a
 *  typed address → level (`share-task add` on submit — an existing account gets
 *  it at once, anyone else an invite that is claimed on sign-up). Both keep PICK
 *  order (LinkedHashMap); a new grade for someone already picked keeps their
 *  place. An address is only ever Can edit / Can view. */
data class NewTaskShares(
    val people: Map<String, ShareLevel> = emptyMap(),
    val emails: Map<String, ShareLevel> = emptyMap(),
) {
    val isEmpty: Boolean get() = people.isEmpty() && emails.isEmpty()

    /** Pick [userId] at [level]; null = un-pick. A blank id is ignored. */
    fun withPerson(userId: String, level: ShareLevel?): NewTaskShares {
        if (userId.isBlank()) return this
        val next = LinkedHashMap(people)
        if (level == null) next.remove(userId) else next[userId] = level
        return copy(people = next)
    }

    /** Hold a typed address at [level] (normalised: trimmed, lower-cased); null =
     *  drop it. Something that isn't an address changes nothing. A hand-over is
     *  not an email grade — it is held as Can edit. */
    fun withEmail(raw: String, level: ShareLevel?): NewTaskShares {
        val email = normalizedShareEmail(raw)
        if (level == null) return if (email in emails) copy(emails = LinkedHashMap(emails).apply { remove(email) }) else this
        if (!isEmailLike(email)) return this
        val next = LinkedHashMap(emails)
        next[email] = if (level == ShareLevel.ASSIGN) ShareLevel.PARTNER else level
        return copy(emails = next)
    }

    /** For rememberSaveable: "p|<userId>|<LEVEL>" / "e|<address>|<LEVEL>", pick order. */
    fun toSaved(): List<String> =
        people.map { (id, l) -> "p|$id|${l.name}" } + emails.map { (e, l) -> "e|$e|${l.name}" }

    companion object {
        /** [toSaved]'s inverse; anything unreadable is skipped. */
        fun fromSaved(saved: List<String>): NewTaskShares {
            var out = NewTaskShares()
            for (s in saved) {
                if (s.length < 3 || s[1] != '|') continue
                val rest = s.substring(2)
                val key = rest.substringBeforeLast("|", "")
                val level = runCatching { ShareLevel.valueOf(rest.substringAfterLast("|")) }.getOrNull() ?: continue
                out = when (s[0]) {
                    'p' -> out.withPerson(key, level)
                    'e' -> out.withEmail(key, level)
                    else -> out
                }
            }
            return out
        }
    }
}

/** The longest summary the row shows (see the rule above). It fits beside
 *  "Share with…" and the chevron on a 360dp phone; the Text still ellipsizes as
 *  a last resort. The SAME budget on every platform. */
const val SHARE_SUMMARY_MAX = 28

/** The summary when nothing is picked. */
const val SHARE_SUMMARY_NONE = "Only you"

/** The name of a pick the roster can't name. */
const val SHARE_PICK_UNNAMED = "Someone"

/** How many monograms the row shows at most. */
const val SHARE_ROW_MAX_MONOGRAMS = 3

/** Everything picked, named, in PICK order: connections (named from the roster;
 *  one it doesn't know reads "Someone" — never silently dropped), then typed
 *  addresses (the address is the name). */
fun newTaskSharePicks(roster: List<CircleMember>, shares: NewTaskShares): List<SharePick> {
    if (shares.isEmpty) return emptyList()
    val names = HashMap<String, String>()
    for (m in roster) {
        val uid = m.memberUserId ?: continue
        val n = m.memberName?.trim().orEmpty()
        if (n.isNotEmpty() && uid !in names) names[uid] = n
    }
    return shares.people.map { (uid, level) -> SharePick(names[uid] ?: SHARE_PICK_UNNAMED, level) } +
        shares.emails.map { (email, level) -> SharePick(email, level) }
}

/** The one-line summary on the "Share with…" row (see the rule above). */
fun shareWithSummary(picks: List<SharePick>, max: Int = SHARE_SUMMARY_MAX): String {
    if (picks.isEmpty()) return SHARE_SUMMARY_NONE
    val names = picks.map { summaryName(it.name) }
    val uniform = picks.all { it.level == picks[0].level }
    val grade = gradeLong(picks[0].level)
    return when {
        picks.size == 1 -> fitNames(names, max) { "${it[0]} · $grade" }
        picks.size == 2 && uniform -> fitNames(names, max) { "${it[0]}, ${it[1]} · $grade" }
        picks.size == 2 -> fitNames(names, max) {
            "${it[0]} · ${gradeShort(picks[0].level)}, ${it[1]} · ${gradeShort(picks[1].level)}"
        }
        else -> fitNames(names.take(1), max) {
            "${it[0]} + ${picks.size - 1} more" + if (uniform) " · $grade" else ""
        }
    }
}

/** The row's monogram letters: the first [SHARE_ROW_MAX_MONOGRAMS] picks, in the
 *  summary's order, one upper-case letter each. */
fun shareRowMonograms(picks: List<SharePick>): List<String> =
    picks.take(SHARE_ROW_MAX_MONOGRAMS).map { p ->
        val n = p.name.trim().ifEmpty { SHARE_PICK_UNNAMED }
        n.first().uppercaseChar().toString()
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

/** The honest line after "Someone new" on the PRE-CREATE Share screen: the
 *  address is HELD until "Add task". [level] null = it was taken off. */
fun sharePreCreateEmailLine(email: String, level: ShareLevel?): String = when (level) {
    null -> "$email won't get this task."
    ShareLevel.VIEW -> "$email gets it when you add the task — they can view."
    else -> "$email gets it when you add the task — they can edit."
}

/** Under a held address on the PRE-CREATE Share screen: "Gets it when you add
 *  the task · can edit". */
fun shareHeldEmailStatus(level: ShareLevel): String =
    "Gets it when you add the task · ${if (level == ShareLevel.VIEW) "can view" else "can edit"}"

/** A pick's name in the summary: the first name, the part of an address before
 *  the @, or "Someone" for a blank. */
private fun summaryName(raw: String): String = if (raw.isBlank()) SHARE_PICK_UNNAMED else shareShortName(raw)

/** The grade for everyone: "can edit" / "can view" / "handed over". */
private fun gradeLong(level: ShareLevel): String =
    ShareAccess.fromTaskLevel(level)?.label?.lowercase() ?: "handed over"

/** Each person's grade when they differ: "edit" / "view" / "handed over". */
private fun gradeShort(level: ShareLevel): String =
    ShareAccess.fromTaskLevel(level)?.verb ?: "handed over"

/** [line] with [names] in it, the names cut so the whole stays within [max]. */
private fun fitNames(names: List<String>, max: Int, line: (List<String>) -> String): String {
    val full = line(names)
    if (full.length <= max) return full
    val room = max - line(names.map { "" }).length
    return line(
        if (names.size == 1) listOf(clip(names[0], room))
        else {
            val (a, b) = names
            val half = room / 2
            when {
                a.length <= half -> listOf(a, clip(b, room - a.length))
                b.length <= half -> listOf(clip(a, room - b.length), b)
                else -> listOf(clip(a, room - half), clip(b, half))
            }
        },
    )
}

/** [s] in at most [room] characters, the last an ellipsis — never below one
 *  letter + "…", never splitting a surrogate pair. */
private fun clip(s: String, room: Int): String {
    if (s.length <= room) return s
    var head = s.take(maxOf(room, 2) - 1)
    if (head.isNotEmpty() && head.last().isHighSurrogate()) head = head.dropLast(1)
    return head.trimEnd().ifEmpty { s.take(1) } + "…"
}
