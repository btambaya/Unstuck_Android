package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.TaskItem

// Port of lib/occurrences.ts. A repeating task is a hidden TEMPLATE
// (task.recurrence != null). Its occurrence cal_blocks each carry their own
// done/skipped/completedAt (migration 033). At read time we PROJECT those
// blocks into synthetic one-day TaskItem rows (id = block id) so each
// occurrence appears in Today/All/Upcoming as an independent task, while the
// template is hidden everywhere except the "Recurring" tab. Completing /
// skipping an occurrence writes the cal_block, never the template.
//
// Detection note: an occurrence row's id IS a cal_block id, so any consumer can
// recover the occurrence via [occurrenceBlockFor] (a normal task's id is never
// a block id, so this is unambiguous).

/** A recurring TEMPLATE — hidden from every view except "Recurring". */
fun isTemplate(t: TaskItem): Boolean = t.recurrence != null

/**
 * Project one synthetic one-day occurrence row per non-skipped occurrence
 * cal_block of a recurring template, on or after [fromIso]. id = block id;
 * name/tags/area/priority inherited from the template; estimate/done/completedAt
 * from the block; recurrence cleared (a plain one-day task).
 */
fun projectOccurrences(tasks: List<TaskItem>, blocks: List<CalBlock>, fromIso: String): List<TaskItem> {
    val templates = tasks.filter { it.recurrence != null }.associateBy { it.id }
    if (templates.isEmpty()) return emptyList()
    return blocks.mapNotNull { b ->
        if (!isTaskBlock(b) || b.taskId == null || b.skipped || b.date < fromIso) return@mapNotNull null
        val tpl = templates[b.taskId] ?: return@mapNotNull null
        tpl.copy(
            id = b.id,
            done = b.done,
            completedAt = b.completedAt,
            estimateMin = b.durationMinutes,
            recurrence = null,
            later = false,
        )
    }
}

/** The occurrence cal_block behind a projected row id, or null if the row is a
 *  normal task. Routing (complete/skip/focus) uses this to target the block. */
fun occurrenceBlockFor(rowId: String, tasks: List<TaskItem>, blocks: List<CalBlock>): CalBlock? {
    val b = blocks.firstOrNull { it.id == rowId && isTaskBlock(it) } ?: return null
    return if (tasks.any { it.id == b.taskId && it.recurrence != null }) b else null
}
