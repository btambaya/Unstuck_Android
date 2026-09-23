package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.RecurrenceSerializer
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.time.Time

// Port of lib/recurrence.ts. Materialise + regenerate cal_blocks for
// repeating tasks. Pure functions, no I/O — callers pipe the diff through
// sync. A task has at most one recurrence; when set, the client materialises
// cal_blocks RECURRENCE_HORIZON_DAYS ahead at the same start time. Past
// occurrences are preserved; future ones are regenerated on edit.

/** How far ahead we materialise occurrences on create/edit (8 weeks). */
const val RECURRENCE_HORIZON_DAYS = 56

data class MaterializedOccurrence(val date: String, val startTime: String)

private val Recurrence.daysOfWeek: List<Int>?
    get() = (this as? Recurrence.Weekly)?.daysOfWeek

/** Normalise stored weekly days to the canonical 0=Sun…6=Sat range. A row written
 *  by a different convention (e.g. JS getDay()'s 7 for Sunday, or a stray
 *  out-of-range value) would otherwise never match dayOfWeekJs (0..6), silently
 *  yielding zero occurrences. `((it % 7) + 7) % 7` folds any int into 0..6. */
fun normalizeWeekdays(days: List<Int>): List<Int> = days.map { ((it % 7) + 7) % 7 }.distinct()

private fun matchesRecurrence(r: Recurrence, startDate: Long, candidate: Long): Boolean {
    if (Time.startOfDayMillis(candidate) < Time.startOfDayMillis(startDate)) return false
    return when (r) {
        is Recurrence.Daily -> true
        is Recurrence.Weekly -> Time.dayOfWeekJs(candidate) in normalizeWeekdays(r.daysOfWeek)
        // Clamp the start day to the candidate month's length so a task set to the
        // 29th/30th/31st still fires on the last day of shorter months (Feb etc.)
        // instead of being silently skipped (web does this clamp).
        is Recurrence.Monthly -> Time.dayOfMonth(candidate) == minOf(Time.dayOfMonth(startDate), Time.daysInMonth(candidate))
    }
}

/** Date/time pairs for a recurrence starting at startDate/startTime, going
 *  horizonDays ahead (inclusive of startDate). Stops at recurrence.until
 *  (inclusive) when set. `startDate` is a local-midnight epoch ms. */
fun materializeOccurrences(
    recurrence: Recurrence,
    startDate: Long,
    startTime: String,
    horizonDays: Int = RECURRENCE_HORIZON_DAYS,
): List<MaterializedOccurrence> {
    val out = mutableListOf<MaterializedOccurrence>()
    val untilIso = recurrence.until
    for (i in 0 until horizonDays) {
        val day = Time.addDaysMillis(startDate, i)
        val iso = Clock.dateIso(day)
        if (untilIso != null && iso > untilIso) break
        if (matchesRecurrence(recurrence, startDate, day)) {
            out.add(MaterializedOccurrence(iso, startTime))
        }
    }
    return out
}

/** Diff to align a task's existing cal_blocks with `recurrence`: keep past
 *  occurrences, delete mismatched future ones, add missing ones. `todayIso`
 *  is injected so the boundary is testable. */
data class RegenPlan(val toUpsert: List<CalBlock>, val toDelete: List<String>)

/**
 * THE anchor a recurrence change regenerates from: the task's earliest LIVE
 * timed block at or after today, falling back to its latest past timed block
 * so a series with only history keeps its time of day. Null when the task has
 * no timed block at all (nothing to anchor on).
 *
 * Why (parity with iOS build 79, audit 2026-09-21): [regenerateForTask]
 * deletes every future block whose date|time doesn't match the anchor's, so
 * the anchor decides what survives. The editor and the assistant both used the
 * earliest block of ANY kind — done history at a time the series left long
 * ago, or a timeless legacy block. "Make Office every Monday at 11" then
 * deleted the Monday 11:00 block and rebuilt the series at the old time; and a
 * series whose first block was 56+ days old materialised nothing after today,
 * so any repeat edit deleted every future occurrence and added none.
 */
fun recurrenceAnchor(taskId: String, blocks: List<CalBlock>, todayIso: String): CalBlock? {
    val mine = blocks.filter { it.taskId == taskId && isTaskBlock(it) && it.startTime.isNotEmpty() }
    val live = mine.filter { !it.done && !it.skipped && it.date >= todayIso }
    live.minByOrNull { it.date + it.startTime }?.let { return it }
    return mine.maxByOrNull { it.date + it.startTime }
}

fun regenerateForTask(
    task: TaskItem,
    recurrence: Recurrence?,
    existingBlocks: List<CalBlock>,
    todayIso: String,
    startTime: String,
    startDate: Long,
    horizonDays: Int = RECURRENCE_HORIZON_DAYS,
): RegenPlan {
    val existing = existingBlocks.filter { it.taskId == task.id && isTaskBlock(it) }
    val futureExisting = existing.filter { it.date > todayIso }

    if (recurrence == null) {
        // Clearing recurrence — delete future occurrences but keep any the user
        // already completed/skipped (history), same as web.
        return RegenPlan(emptyList(), futureExisting.filter { !it.done && !it.skipped }.map { it.id })
    }

    // A weekly recurrence with NO valid days (empty, or all out-of-range so they
    // normalise away) would materialise zero occurrences — regenerate would then
    // DELETE every future block and upsert nothing, silently erasing the series.
    // That's almost certainly a corrupt/legacy row, not an intentional "never
    // repeat", so skip regeneration and leave existing blocks untouched.
    if (recurrence is Recurrence.Weekly && normalizeWeekdays(recurrence.daysOfWeek).isEmpty()) {
        return RegenPlan(emptyList(), emptyList())
    }

    val desired = materializeOccurrences(recurrence, startDate, startTime, horizonDays)
        .filter { it.date > todayIso }
    val desiredKeys = desired.map { "${it.date}|${it.startTime}" }.toSet()
    val existingFutureKeys = futureExisting.map { "${it.date}|${it.startTime}" }.toSet()

    // Never delete an occurrence already completed/skipped — that would erase
    // history (and resurrect a done day as undone on retime).
    val toDelete = futureExisting.filter { !it.done && !it.skipped && "${it.date}|${it.startTime}" !in desiredKeys }.map { it.id }
    val toUpsert = desired.filter { "${it.date}|${it.startTime}" !in existingFutureKeys }.map { occurrenceBlock(task, it) }
    return RegenPlan(toUpsert, toDelete)
}

/** A new occurrence block for [task] — the one place a series mints a block.
 *  The server's CHECK is `duration_minutes between 5 and 1440`, so a 2-minute
 *  task minted occurrences it refused on flush, which then lived on this one
 *  phone for ever (parity with iOS build 81, audit 2026-09-22 C4). */
private fun occurrenceBlock(task: TaskItem, o: MaterializedOccurrence): CalBlock = CalBlock(
    id = newUuid(), taskId = task.id, taskName = task.name,
    startTime = o.startTime, durationMinutes = clampDurationMin(task.estimateMin),
    date = o.date, kind = CalBlockKind.TASK,
)

/** The most common start time in [pool]. Ties go to the time used most across
 *  [tieBreak], then to the one used most recently, then to the later string.
 *  Null when nothing in the pool has a time. */
private fun mostCommonStartTime(pool: List<CalBlock>, tieBreak: List<CalBlock>): String? {
    val total = tieBreak.filter { it.startTime.isNotEmpty() }.groupingBy { it.startTime }.eachCount()
    return pool.filter { it.startTime.isNotEmpty() }.groupBy { it.startTime }.entries
        .maxWithOrNull(
            compareBy<Map.Entry<String, List<CalBlock>>>(
                { it.value.size }, { total[it.key] ?: 0 }, { it.value.maxOf { b -> b.date } }, { it.key },
            ),
        )?.key
}

/**
 * The time of day a series runs at: the most common start time among the
 * task's timed blocks in the [horizonDays] up to [frontierIso] (all of its
 * timed blocks when none fall in that window). Ties go to the time used most
 * across every block passed in, then to the most recent. Null when no block
 * has a time.
 *
 * Why not the next open occurrence (parity with iOS build 81, audit
 * 2026-09-22 C1): that is often the one the user moved by hand — the
 * notification's Reschedule, a calendar drag, schedule_task — and taking it as
 * the series time copied the whole series at the moved time. The window keeps
 * a whole-series re-plan to a new time from being outvoted by older history.
 */
fun recurrenceSeriesTime(
    taskId: String,
    blocks: List<CalBlock>,
    frontierIso: String,
    horizonDays: Int = RECURRENCE_HORIZON_DAYS,
): String? {
    val timed = blocks.filter { it.taskId == taskId && isTaskBlock(it) && it.startTime.isNotEmpty() }
    val from = IsoDate.addDays(frontierIso, -(horizonDays - 1))
    val recent = timed.filter { it.date >= from && it.date <= frontierIso }
    return mostCommonStartTime(recent.ifEmpty { timed }, tieBreak = timed)
}

private data class SeriesDay(val day: Int, val votes: Int)

/** The day of the month a monthly series runs on, voted by its LAST THREE
 *  occurrences (any state) with how many of them agree on it. A month-end
 *  clamp counts for the longer day (a 31st series shows Feb 28 / Apr 30), so
 *  the clamp recovers to 31. A 1-1 split goes to the earlier day: a hand move
 *  of one occurrence almost always pushes it later (carry to tomorrow, "push
 *  it back"). Null when there are no blocks. */
private fun recurrenceSeriesDay(blocks: List<CalBlock>): SeriesDay? {
    val dates = blocks.mapNotNull { IsoDate.parse(it.date) }.sorted().takeLast(3)
    var best: SeriesDay? = null
    for (day in dates.map { it.dayOfMonth }.toSet()) {
        val votes = dates.count { it.dayOfMonth == minOf(day, it.lengthOfMonth()) }
        val b = best
        if (b != null && (votes < b.votes || (votes == b.votes && day > b.day))) continue
        best = SeriesDay(day, votes)
    }
    return best
}

/** How many days either side of one of the series' dates a block still counts
 *  as that date's occurrence, moved: under half the gap to the neighbouring
 *  dates, so it is nearer that date than any other. A monthly date is at
 *  least 28 days from the next; a daily one has no room. */
private fun occurrenceReach(r: Recurrence): Int = when (r) {
    is Recurrence.Daily -> 0
    is Recurrence.Weekly -> {
        val sorted = normalizeWeekdays(r.daysOfWeek).sorted()
        if (sorted.isEmpty()) 0
        else ((sorted.zipWithNext { a, b -> b - a } + (7 - sorted.last() + sorted.first())).min() - 1) / 2
    }
    is Recurrence.Monthly -> 14
}

/** The nearest date on or before [onOrBefore] whose day of month is exactly
 *  [day] ([materializeOccurrences] takes a monthly series' day from its start
 *  date, so a clamped Feb 28 would turn a 31st series into a 28th one). */
private fun monthlyStart(day: Int, onOrBefore: String): String {
    for (back in 0 until 62) {
        val d = IsoDate.addDays(onOrBefore, -back)
        if (IsoDate.parse(d)?.dayOfMonth == day) return d
    }
    return onOrBefore
}

/** Where a recurrence EDIT regenerates the series from (setRecurrence,
 *  set_task_recurrence). */
data class RecurrenceStart(
    /** YYYY-MM-DD, handed to [regenerateForTask] as its startDate. */
    val date: String,
    /** HH:MM. */
    val startTime: String,
    /** Stretched so the horizon still ends 8 weeks after the anchor. */
    val horizonDays: Int,
    /** A block the edit must NOT delete although the plan lists it: this
     *  month's occurrence, moved later off a series day that has passed. */
    val keepId: String? = null,
)

/**
 * An edit keeps the series' OWN time and day. The time is the most common one
 * among its live upcoming occurrences once there are at least two (else the
 * anchor's, which keeps the "Office every Monday at 11" fix: one live 11:00
 * block over 09:15 history still gives 11:00). A monthly series takes its day
 * from the last three occurrences when two of them agree. Null when the task
 * has no timed block to anchor on.
 *
 * Why (parity with iOS build 81, audit 2026-09-22 C1): [regenerateForTask]
 * deletes every future block that doesn't match the start's date|time, and the
 * start was an arbitrary block. Editing only the repeat's end date on a day
 * when the next occurrence had been moved by hand deleted the whole series and
 * rebuilt it at the moved time (or, monthly, on the moved day).
 */
fun recurrenceEditStart(
    taskId: String,
    recurrence: Recurrence?,
    blocks: List<CalBlock>,
    todayIso: String,
    horizonDays: Int = RECURRENCE_HORIZON_DAYS,
): RecurrenceStart? {
    val anchor = recurrenceAnchor(taskId, blocks, todayIso) ?: return null
    val timed = blocks.filter { it.taskId == taskId && isTaskBlock(it) && it.startTime.isNotEmpty() }
    val live = timed.filter { !it.done && !it.skipped && it.date >= todayIso }
    val time = if (live.size >= 2) mostCommonStartTime(live, tieBreak = timed) ?: anchor.startTime else anchor.startTime
    // Two of the last three must agree before the day moves off the anchor's:
    // switching a weekly series to monthly keeps the next occurrence's day.
    val series = if (recurrence is Recurrence.Monthly) recurrenceSeriesDay(timed) else null
    if (series == null || series.votes < 2) return RecurrenceStart(anchor.date, time, horizonDays)
    val date = monthlyStart(series.day, onOrBefore = anchor.date)
    val back = IsoDate.daysUntil(date, anchor.date)
    // The anchor is this month's occurrence moved later, and the series day it
    // left has passed: regenerateForTask only wants dates after today, so it
    // deleted the moved one and the month lost its occurrence. Kept unless it
    // is past `until` or too far out to be this month's (then it is next
    // month's, moved earlier, and is re-aligned).
    val ownsPassedDay = date <= todayIso && anchor.date > todayIso &&
        back <= occurrenceReach(Recurrence.Monthly()) &&
        (recurrence?.until?.let { anchor.date <= it } ?: true)
    return RecurrenceStart(date, time, horizonDays + back, keepId = if (ownsPassedDay) anchor.id else null)
}

/** What scheduling a series onto a day at a time must do on that day once a
 *  [RegenPlan] is applied. */
sealed class ChosenDateAction {
    /** The day already has its occurrence: nothing to write. */
    data object Covered : ChosenDateAction()
    /** Move this existing block to the chosen time (and un-skip it). */
    data class Retime(val block: CalBlock) : ChosenDateAction()
    /** Nothing on the day: mint a new occurrence. */
    data object Mint : ChosenDateAction()
}

/**
 * Pure decision behind scheduleTaskNow's guarantee on the chosen day (and the
 * assistant's schedule_task on a series). An existing block the plan is about
 * to DELETE does not count (or the day would end up empty); a planned upsert
 * on the date does.
 *
 * Why not "any block on the day covers it" (parity with iOS build 81, audit
 * 2026-09-22 C7): regenerateForTask never touches today, so scheduling a
 * series for today at 16:00 left today's occurrence at 07:00, and a SKIPPED
 * occurrence counted as coverage, so the day just scheduled stayed hidden. Now
 * an open occurrence at another time is retimed, a skipped one is retimed and
 * un-skipped, and a done one still covers the day so no second open copy
 * appears. Retiming rather than minting keeps one block per task per day.
 */
fun recurrenceChosenDateAction(existing: List<CalBlock>, plan: RegenPlan, iso: String, startTime: String): ChosenDateAction {
    if (plan.toUpsert.any { it.date == iso }) return ChosenDateAction.Covered
    val deleting = plan.toDelete.toSet()
    val onDay = existing.filter { it.date == iso && isTaskBlock(it) && it.id !in deleting }
    val live = onDay.filter { !it.done && !it.skipped }
    if (live.any { it.startTime == startTime }) return ChosenDateAction.Covered
    live.minByOrNull { it.startTime }?.let { return ChosenDateAction.Retime(it) }
    if (onDay.any { it.done }) return ChosenDateAction.Covered
    onDay.firstOrNull { it.skipped }?.let { return ChosenDateAction.Retime(it) }
    return ChosenDateAction.Mint
}

/**
 * Does the create sheet need a time before it may add this task? [date] is
 * null for Later.
 *
 * Why (parity with iOS build 81, audit 2026-09-22 C7): every occurrence is a
 * timed block and nothing can invent the time later, so a repeating task saved
 * without one had zero occurrences and showed nowhere but Tasks → Recurring.
 * The free-slot finder stops at 18:00, so that was the normal evening case, and
 * Later + Repeat got there too. A one-off for a later day with no time got no
 * block and silently lost its day. A one-off for today may still be added
 * without a time.
 */
fun newTaskNeedsTime(repeats: Boolean, date: String?, todayIso: String, pickedTime: String?): Boolean {
    if (date == null) return repeats
    if (pickedTime != null) return false
    return repeats || date != todayIso
}

private val DOW_LABELS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
private val MONTH_LABELS =
    listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun formatDays(days: List<Int>): String {
    val sorted = days.toSortedSet().toList()
    if (sorted.size == 5 && listOf(1, 2, 3, 4, 5).all { it in sorted }) return "weekdays"
    if (sorted.size == 2 && 0 in sorted && 6 in sorted) return "weekends"
    return sorted.filter { it in DOW_LABELS.indices }.joinToString("/") { DOW_LABELS[it] }
}

/** Short human label for the detail pane / row chips. */
fun recurrenceLabel(r: Recurrence?): String {
    // An unrecognised recurrence kind decodes to a no-op sentinel (see
    // RecurrenceSerializer.UNKNOWN_UNTIL) — render nothing, not a bogus "until 0001" chip.
    if (r == null || RecurrenceSerializer.isUnknown(r)) return ""
    val base = when (r) {
        is Recurrence.Daily -> "Repeats daily"
        is Recurrence.Weekly -> if (r.daysOfWeek.size == 7) "Repeats daily" else "Repeats ${formatDays(r.daysOfWeek)}"
        is Recurrence.Monthly -> "Repeats monthly"
    }
    val until = r.until
    if (until != null) {
        val parts = until.split("-").mapNotNull { it.toIntOrNull() }
        if (parts.size == 3) {
            val (y, m, d) = parts
            if (m in 1..12) return "$base until ${MONTH_LABELS[m - 1]} $d, $y"
        }
    }
    return base
}
