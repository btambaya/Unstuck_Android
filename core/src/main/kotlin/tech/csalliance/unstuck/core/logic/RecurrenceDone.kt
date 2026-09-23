package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.TaskItem

// The done state when a task's repeat is turned on or off (parity with iOS
// build 81 Recurrence.swift, audit 2026-09-22 C3). A series' TEMPLATE carries no
// done of its own — each day's done lives on its occurrence block — while a plain
// task's done is task-level, so the done has to cross over when the repeat
// changes. Used by AppViewModel.setRecurrence and set_task_recurrence.

/**
 * A task with its repeat turned on or off:
 *  • OFF ("Never", set_task_recurrence none): the ex-template is bucketed as a
 *    plain task, so a ticked today reappeared unticked. When today's occurrences
 *    are all ticked, the tick carries onto the task (done, with the latest of
 *    their completedAt). An open or absent today leaves it open — no silent
 *    completion (owner decision); its kept history puts it in Backlog as overdue.
 *  • ON for a plain task that is done: the done is cleared. A done TEMPLATE is an
 *    ended series — no reminders, no server calls — and "Daily → Never → Daily"
 *    on a ticked day would otherwise make one. The day it was done keeps its tick
 *    ([occurrencesCarryingTaskDone]).
 * Changing one repeat rule for another leaves the done state as it is.
 */
fun taskAfterSettingRecurrence(
    task: TaskItem,
    recurrence: Recurrence?,
    blocks: List<CalBlock>,
    todayIso: String,
    nowIso: String,
): TaskItem {
    var next = task.copy(recurrence = recurrence)
    if (recurrence == null && task.recurrence != null && !task.done) {
        val today = blocks.filter { it.taskId == task.id && isTaskBlock(it) && it.date == todayIso && !it.skipped }
        if (today.isNotEmpty() && today.all { it.done }) {
            next = next.copy(done = true, completedAt = today.mapNotNull { it.completedAt }.maxOrNull() ?: nowIso)
        }
    } else if (recurrence != null && task.recurrence == null && task.done) {
        next = next.copy(done = false, completedAt = null)
    }
    return next
}

/**
 * The occurrence blocks a done task's tick moves onto when its repeat is turned
 * ON. [taskAfterSettingRecurrence] clears the task's done, so the day it was done
 * became an open occurrence: "Stretch" ticked this morning, made daily, and
 * today's 07:30 row was back in Today to tick again; ticked on yesterday's slot,
 * and that slot showed in Backlog as overdue. The tick lands on the day it
 * fulfilled — the task's latest scheduled day on or before the day it was done —
 * stamped with the task's completedAt, the shape the UI's occurrence tick writes.
 * A slot after that day (done early) stays that day's open occurrence. Empty for
 * every other change, and for a day already ticked.
 */
fun occurrencesCarryingTaskDone(
    task: TaskItem,
    recurrence: Recurrence?,
    blocks: List<CalBlock>,
    todayIso: String,
    nowIso: String,
): List<CalBlock> {
    if (recurrence == null || task.recurrence != null || !task.done) return emptyList()
    val doneDay = minOf(task.completedAt?.let(::isoToLocalYmd) ?: todayIso, todayIso)
    val slots = blocks.filter { it.taskId == task.id && isTaskBlock(it) && !it.skipped && it.date <= doneDay }
    val day = slots.maxOfOrNull { it.date } ?: return emptyList()
    return slots.filter { it.date == day && !it.done }.map { it.copy(done = true, completedAt = task.completedAt ?: nowIso) }
}
