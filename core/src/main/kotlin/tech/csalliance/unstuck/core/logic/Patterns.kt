package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.TaskItem
import java.time.LocalDate

// Schedule-pattern detection for the AI gateway — pure logic, zero-LLM.
// A "pattern" is a habit the calendar reveals: the same task scheduled on the
// same weekday in at least three distinct weeks of recent history. A "gap" is
// a pattern whose next occurrence has nothing on the calendar yet — the thing
// a good PA would gently ask about ("still on for Wednesday?").
//
// Port of lib/assistant/patterns.ts (+ UnstuckCore/Logic/Patterns.swift). All
// date math is LOCAL-calendar safe: 'YYYY-MM-DD' strings go through
// java.time.LocalDate (parse field-by-field, format field-by-field, DST-safe
// day arithmetic) — never an ISO-8601 UTC round-trip.

data class Pattern(
    val taskId: String,
    val taskName: String,
    /** Weekday of the habit, 0=Sun … 6=Sat (JS `getDay` convention). */
    val dow: Int,
    /** Most common startTime among the occurrences; null if none carried one. */
    val time: String? = null,
    /** Distinct history weeks the habit appeared in. */
    val weeksSeen: Int,
    /** e.g. "Gym most Wednesdays at 07:00 (4 of the last 4 weeks)". */
    val label: String,
)

/** A [Pattern] plus the next uncovered occurrence date ('YYYY-MM-DD', today or later). */
data class Gap(
    val taskId: String,
    val taskName: String,
    val dow: Int,
    val time: String? = null,
    val weeksSeen: Int,
    val label: String,
    val dueDate: String,
) {
    constructor(p: Pattern, dueDate: String) : this(
        taskId = p.taskId, taskName = p.taskName, dow = p.dow, time = p.time,
        weeksSeen = p.weeksSeen, label = p.label, dueDate = dueDate,
    )
}

internal val DAY_NAMES_FULL = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

/**
 * `YYYY-MM-DD` → a local calendar date, or null when it is malformed or
 * IMPOSSIBLE ("2026-02-30"). The gateway's single entry point from a stored
 * date string into java.time: JS rolls an impossible date over silently, while
 * `LocalDate.parse` throws — and a throw inside the moments/patterns engine
 * takes down the Compose surface rendering the card. Every parse of data that
 * came off the wire (a block's date, a fact's whenIso) goes through here.
 */
fun parseYmdOrNull(iso: String): LocalDate? = runCatching { LocalDate.parse(iso) }.getOrNull()

/** JS `getDay` for a LocalDate: 0=Sun … 6=Sat. */
internal fun LocalDate.dowJs(): Int = dayOfWeek.value % 7

/** Monday of the week containing [base]. */
internal fun weekStartDate(base: LocalDate): LocalDate = base.minusDays(((base.dowJs() + 6) % 7).toLong())

/**
 * Same task, same weekday, in ≥3 distinct weeks of recent HISTORY — blocks
 * dated before the Monday of the current week and no older than 35 days
 * before today. The current week never counts: a habit is something the past
 * shows, not something this week's plan asserts. Done occurrences DO count
 * (they're evidence the habit happened); blocks without a resolvable task
 * are ignored.
 */
fun derivePatterns(tasks: List<TaskItem>, blocks: List<CalBlock>, todayIso: String): List<Pattern> {
    val today = LocalDate.parse(todayIso)
    val historyEnd = weekStartDate(today)          // exclusive — current week is not history
    val historyStart = today.minusDays(35)         // inclusive — ~5 weeks back

    val taskById = HashMap<String, TaskItem>()
    for (t in tasks) taskById.putIfAbsent(t.id, t)

    class Bucket(val taskId: String, val taskName: String, val dow: Int) {
        val weeks = HashSet<String>()
        /** startTime → occurrence count, insertion-ordered (first seen wins ties). */
        val timeCounts = LinkedHashMap<String, Int>()
    }
    val buckets = LinkedHashMap<String, Bucket>()   // JS Map iteration order = insertion order

    for (b in blocks) {
        val tid = b.taskId
        if (tid.isNullOrEmpty()) continue
        val task = taskById[tid] ?: continue
        // A block whose `date` isn't a real 'YYYY-MM-DD' is not evidence of a
        // habit — skip it. (The web's lenient Date makes such a row a silent
        // NaN bucket; java.time throws, and a throw here would take down the
        // gateway card that calls derivePatterns on every render.)
        val d = parseYmdOrNull(b.date) ?: continue
        if (!d.isBefore(historyEnd) || d.isBefore(historyStart)) continue   // history only
        val dow = d.dowJs()
        val key = "$tid|$dow"
        val e = buckets.getOrPut(key) { Bucket(tid, task.name, dow) }
        e.weeks.add(weekStartDate(d).toString())
        val time = b.startTime.trim()
        if (time.isNotEmpty()) e.timeCounts[time] = (e.timeCounts[time] ?: 0) + 1
    }

    val patterns = ArrayList<Pattern>()
    for (e in buckets.values) {
        if (e.weeks.size < 3) continue
        var time: String? = null
        var best = 0
        for ((t, n) in e.timeCounts) if (n > best) { best = n; time = t }
        val weeksSeen = e.weeks.size
        // The window reads "the last 4 weeks" like the reference, but never
        // understates: a 5-distinct-week habit says "5 of the last 5".
        val window = maxOf(4, weeksSeen)
        val at = if (time != null) " at $time" else ""
        patterns.add(
            Pattern(
                taskId = e.taskId, taskName = e.taskName, dow = e.dow, time = time, weeksSeen = weeksSeen,
                label = "${e.taskName} most ${DAY_NAMES_FULL[e.dow]}s$at ($weeksSeen of the last $window weeks)",
            ),
        )
    }
    return patterns
}

/**
 * Patterns whose NEXT occurrence has nothing scheduled. The next occurrence
 * is the pattern's weekday this week if that's today or later, else the same
 * weekday next week — a PA asked on Saturday about a Wednesday habit
 * naturally means next Wednesday. A non-done block for the task on that
 * exact date counts as covered and suppresses the gap.
 */
fun patternGaps(patterns: List<Pattern>, blocks: List<CalBlock>, todayIso: String): List<Gap> {
    val today = LocalDate.parse(todayIso)
    val start = weekStartDate(today)
    val gaps = ArrayList<Gap>()
    for (p in patterns) {
        var due = start.plusDays(((p.dow + 6) % 7).toLong())   // Sunday-based dow → Monday-based offset
        if (due.isBefore(today)) due = due.plusDays(7)          // already passed this week → roll forward
        val dueDate = due.toString()
        val covered = blocks.any { it.taskId == p.taskId && it.date == dueDate && !it.done }
        if (!covered) gaps.add(Gap(p, dueDate))
    }
    return gaps
}
