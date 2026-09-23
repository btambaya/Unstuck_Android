package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Time
import tech.csalliance.unstuck.core.time.WireTime
import java.time.LocalDate

// The deterministic brief for the AI gateway card — zero-LLM, so it renders
// instantly and never hallucinates. Register: a calm PA's one-two sentences.
// NO greeting prefix — the Today header already greets, and "Morning, Ahmad.
// Morning!" is the opposite of calm. Port of lib/assistant/brief.ts
// (+ UnstuckCore/Logic/Brief.swift).
//
//   "Three things scheduled today — 'Write the project update' at 11:00 is
//    the anchor. About 90 usable minutes before it."
//   "Nothing on the calendar today — 7 open tasks if you want to pull one in."
//   "A clear day. Add what's on your mind below."
//
// The ANCHOR is the day's centre of gravity: the first live block still ahead
// of now, or — once everything timed is behind us — the day's first block.
//
// `now` is epoch millis; only its LOCAL hours/minutes (system zone, like JS
// Date) are read — the module's `Time` conventions.

// British short months — September abbreviates to "Sept", not "Sep".
internal val MONTH_SHORT_GB = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sept", "Oct", "Nov", "Dec")

// Small counts read as words ("Three things"), larger ones as digits.
private val COUNT_WORDS = listOf("Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten")
internal fun countWord(n: Int): String = if (n >= 0 && n < COUNT_WORDS.size) COUNT_WORDS[n] else n.toString()

/** JS `Math.round`: halves round toward +∞ (−2.5 → −2), unlike Kotlin's `roundToInt` (half away from zero). */
internal fun jsRound(x: Double): Int = Math.floor(x + 0.5).toInt()

/** Local wall clock of an epoch-ms instant as 'HH:MM' (the brief/moment gates compare it lexicographically,
 *  so ASCII digits in every locale — Android audit 2026-09-23, A12). */
internal fun hmOfMillis(now: Long): String = WireTime.hm(Time.hourOf(now), Time.minuteOf(now))

/**
 * Today's live blocks (not done, not skipped) in start-time order — untimed
 * blocks sort first, like the calendar's any-time lane. Shared by the brief
 * and the first-touch moment. (Kotlin's `sortedBy` is stable, like V8's sort.)
 */
internal fun liveBlocksToday(blocks: List<CalBlock>, todayIso: String): List<CalBlock> =
    blocks.filter { it.date == todayIso && !it.done && !it.skipped }.sortedBy { it.startTime }

/** The brief's anchor: first live timed block at-or-after [nowHm], else the day's first block. */
internal fun anchorBlock(todayBlocks: List<CalBlock>, nowHm: String): CalBlock? =
    todayBlocks.firstOrNull { it.startTime.trim().isNotEmpty() && it.startTime >= nowHm } ?: todayBlocks.firstOrNull()

fun composeBrief(
    tasks: List<TaskItem>,
    blocks: List<CalBlock>,
    todayIso: String,
    now: Long,
    usableMinutes: Int? = null,
): String {
    // Live = not done, not skipped (a cancelled occurrence isn't "scheduled
    // today" any more — same convention as suggestions.ts). Untimed blocks
    // sort first, like the calendar's any-time lane.
    val todayBlocks = liveBlocksToday(blocks, todayIso)

    if (todayBlocks.isEmpty()) {
        // Open working set = not done, not a recurring template (occurrences
        // live on the calendar as blocks) — mirrors suggestions.ts.
        val open = tasks.count { !it.done && it.recurrence == null }
        return if (open > 0) {
            "Nothing on the calendar today — $open open ${if (open == 1) "task" else "tasks"} if you want to pull one in."
        } else {
            "A clear day. Add what’s on your mind below."
        }
    }

    val nowHm = hmOfMillis(now)
    val anchor = anchorBlock(todayBlocks, nowHm)!!
    // Prefer the task's current name (blocks denormalize it and can go stale).
    val anchorName = tasks.firstOrNull { it.id == anchor.taskId }?.name ?: anchor.taskName
    val anchorTime: String? = anchor.startTime.trim().ifEmpty { null }

    val n = todayBlocks.size
    val head = "${countWord(n)} ${if (n == 1) "thing" else "things"} scheduled today — ‘$anchorName’" +
        (if (anchorTime != null) " at $anchorTime" else "") + " is the anchor."

    // The runway sentence only earns its place when it's real: a meaningful
    // stretch (≥15 min) before an anchor that is genuinely still ahead.
    if (usableMinutes != null && usableMinutes >= 15 && anchorTime != null && anchorTime > nowHm) {
        val rounded = jsRound(usableMinutes / 5.0) * 5
        return "$head About $rounded usable minutes before it."
    }
    return head
}

/**
 * The gentle probe for a pattern gap:
 * "You usually do 'Gym' on Wednesdays — still on for Wednesday 2 Sept?"
 * Date parsed as a local calendar date (never a UTC-midnight ISO parse, which
 * reads back as the previous day west of Greenwich).
 */
fun probeQuestion(gap: Gap): String {
    val due = LocalDate.parse(gap.dueDate)
    return "You usually do ‘${gap.taskName}’ on ${DAY_NAMES_FULL[gap.dow]}s — still on for " +
        "${DAY_NAMES_FULL[due.dowJs()]} ${due.dayOfMonth} ${MONTH_SHORT_GB[due.monthValue - 1]}?"
}
