package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Recurrence
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

private fun matchesRecurrence(r: Recurrence, startDate: Long, candidate: Long): Boolean {
    if (Time.startOfDayMillis(candidate) < Time.startOfDayMillis(startDate)) return false
    return when (r) {
        is Recurrence.Daily -> true
        is Recurrence.Weekly -> Time.dayOfWeekJs(candidate) in r.daysOfWeek
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
        // Clearing recurrence — delete every future occurrence, keep history.
        return RegenPlan(emptyList(), futureExisting.map { it.id })
    }

    val desired = materializeOccurrences(recurrence, startDate, startTime, horizonDays)
        .filter { it.date > todayIso }
    val desiredKeys = desired.map { "${it.date}|${it.startTime}" }.toSet()
    val existingFutureKeys = futureExisting.map { "${it.date}|${it.startTime}" }.toSet()

    val toDelete = futureExisting.filter { "${it.date}|${it.startTime}" !in desiredKeys }.map { it.id }
    val toUpsert = desired.filter { "${it.date}|${it.startTime}" !in existingFutureKeys }.map { o ->
        CalBlock(
            id = newUuid(), taskId = task.id, taskName = task.name,
            startTime = o.startTime, durationMinutes = task.estimateMin,
            date = o.date, kind = CalBlockKind.TASK,
        )
    }
    return RegenPlan(toUpsert, toDelete)
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
    if (r == null) return ""
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
