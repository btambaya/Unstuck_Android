package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.model.TaskListView
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.time.Time

// Port of lib/visible-tasks.ts. Today is intentionally area-agnostic: even
// with an area filter active, the Today bucket surfaces tasks of every area
// that have a today-dated cal_block. The area filter only applies to
// All / Backlog / Upcoming / Later / Completed.

/** Sentinel area name for "no area assigned." Use via [matchesArea] so
 *  callers don't special-case the sentinel string. */
const val UNASSIGNED_AREA = "Unassigned"

private const val SLIP_AGE_MS = 21L * 24 * 60 * 60 * 1000
private const val SLIP_MOVE_THRESHOLD = 3

/** Single source of truth for "does this task belong to this area filter?".
 *  Keeps visibleTasks + pickStartNext in sync on the sentinel + no-filter. */
fun matchesArea(taskArea: String?, activeArea: String?): Boolean {
    if (activeArea.isNullOrEmpty()) return true
    if (activeArea == UNASSIGNED_AREA) return taskArea.isNullOrEmpty()
    return taskArea == activeArea
}

/** True if any of the task's tags matches the active tag (case-insensitive). */
fun matchesTag(taskTags: List<String>?, activeTag: String?): Boolean {
    if (activeTag.isNullOrEmpty()) return true
    return (taskTags ?: emptyList()).any { it.lowercase() == activeTag.lowercase() }
}

fun isSlipping(task: TaskItem, now: Long): Boolean {
    if (task.done) return false
    val moves = task.moveCount ?: 0
    if (moves >= SLIP_MOVE_THRESHOLD) return true
    val created = Time.parseMillis(task.createdAt) ?: return false
    return now - created >= SLIP_AGE_MS
}

/** Whole days between task.createdAt and `now` (0 today, 1 yesterday, …). */
fun daysSinceCreated(task: TaskItem, now: Long): Int {
    val created = Time.parseMillis(task.createdAt) ?: return 0
    val diffMs = maxOf(0L, now - created)
    return (diffMs / (24 * 60 * 60 * 1000)).toInt()
}

fun visibleTasks(
    view: TaskListView,
    tasks: List<TaskItem>,
    blocks: List<CalBlock>,
    now: Long,
    activeArea: String?,
    activeTag: String? = null,
    slipMode: Boolean,
): List<TaskItem> {
    val today = Clock.todayIso()
    val nonTemplates = tasks.filter { !isTemplate(it) }
    val templateIds = tasks.filter { it.recurrence != null }.map { it.id }.toSet()

    // Recurring occurrences surface ONLY in Today (the due day) + the single
    // NEXT upcoming one in Upcoming — never in All / Backlog / Later / Completed
    // (a repeating task would otherwise list once per horizon date). The template
    // itself lives only in the Recurring view. occurrence row id == its block id.
    val occBlocks = blocks.filter { isTaskBlock(it) && !it.skipped && it.taskId in templateIds && it.date >= today }
    val todayOccIds = occBlocks.filter { it.date == today }.map { it.id }.toSet()
    val nextPerTemplate = HashMap<String, CalBlock>()   // template id -> its earliest FUTURE occurrence block
    for (b in occBlocks) {
        if (b.date <= today) continue
        val tid = b.taskId ?: continue
        val cur = nextPerTemplate[tid]
        if (cur != null && cur.date <= b.date) continue
        nextPerTemplate[tid] = b
    }
    val nextUpcomingOccIds = nextPerTemplate.values.map { it.id }.toSet()
    val projected = projectOccurrences(tasks, blocks, today)
    val todayOccurrences = projected.filter { it.id in todayOccIds }
    val upcomingOccurrences = projected.filter { it.id in nextUpcomingOccIds }
    // Missed recurring occurrences: one overdue row per template whose most-
    // recent past occurrence went undone — surfaced in Backlog so a skipped
    // "every Friday" task doesn't silently vanish until next Friday.
    val overdueOccurrences = projectOverdueOccurrences(tasks, blocks, today)

    // Non-template task bucketing — over NON-recurring task blocks only (an
    // occurrence block's taskId is its template, never a row in these buckets).
    val taskBlocks = blocks.filter { isTaskBlock(it) && it.taskId !in templateIds }
    val todayTaskIds = taskBlocks.filter { it.date == today }.mapNotNull { it.taskId }.toSet()
    val upcomingTaskIds = taskBlocks.filter { it.date > today }.mapNotNull { it.taskId }.toSet()
    val scheduledTaskIds = taskBlocks.mapNotNull { it.taskId }.toSet()
    // Tasks whose only task-shaped cal_blocks are dated before today —
    // planned for a past day but never done. These are "overdue" → Backlog.
    val pastOnlyTaskIds = scheduledTaskIds.filter { it !in todayTaskIds && it !in upcomingTaskIds }.toSet()

    val byView = when (view) {
        TaskListView.RECURRING ->
            // The repeating definitions themselves (area/tag still narrow it).
            tasks.filter { isTemplate(it) }
        TaskListView.TODAY -> {
            // Scheduled today OR created today (fresh arrivals count), but not tasks
            // scheduled for a future day — plus today's recurring occurrences.
            val nt = nonTemplates.filter { t ->
                !t.done && t.later != true && (
                    t.id in todayTaskIds || (isCreatedToday(t, now) && t.id !in upcomingTaskIds)
                )
            }
            nt + todayOccurrences.filter { !it.done }
        }
        TaskListView.BACKLOG ->
            // Open work not actively planned AND sitting ≥ a day: never scheduled, or
            // only ever scheduled in the past (overdue). PLUS one overdue row per
            // recurring template whose most-recent occurrence was missed.
            nonTemplates.filter { t ->
                !t.done && t.later != true && !isCreatedToday(t, now) && (
                    t.id !in scheduledTaskIds || t.id in pastOnlyTaskIds
                )
            } + overdueOccurrences
        TaskListView.UPCOMING -> {
            // Future-scheduled tasks + the single NEXT occurrence per recurring series.
            val nt = nonTemplates.filter { t -> !t.done && t.id in upcomingTaskIds && t.id !in todayTaskIds }
            nt + upcomingOccurrences.filter { !it.done }
        }
        TaskListView.LATER ->
            nonTemplates.filter { !it.done && it.later == true }
        TaskListView.COMPLETED ->
            nonTemplates.filter { it.done }
        TaskListView.ALL ->
            // The master list of distinct tasks — NO per-day occurrence rows.
            nonTemplates.filter { !it.done || isCompletedToday(it, now) }
    }

    // Today is area-agnostic on purpose.
    val afterArea = if (view == TaskListView.TODAY) byView else byView.filter { matchesArea(it.lifeArea, activeArea) }

    // Tag filter applies to EVERY view including Today — an explicit narrowing.
    val afterTag = if (!activeTag.isNullOrEmpty()) {
        afterArea.filter { (it.tags ?: emptyList()).any { n -> n.lowercase() == activeTag.lowercase() } }
    } else {
        afterArea
    }

    val afterSlip = if (slipMode) afterTag.filter { isSlipping(it, now) } else afterTag

    // Open tasks first, then completed — preserving original order within each
    // bucket. Kotlin's filter keeps order, so partition by hand (mirrors iOS).
    return afterSlip.filter { !it.done } + afterSlip.filter { it.done }
}
