package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Time

private val OVERDUE_DOW = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

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

/**
 * Port of lib/occurrences.ts `projectOverdueOccurrences`. One synthetic
 * "overdue" occurrence row per recurring template whose most-recent PAST
 * occurrence was missed — i.e. that latest past occurrence is still incomplete
 * (NOT done AND NOT skipped) AND today is not itself a recurrence day for it (a
 * today occurrence supersedes the stale miss). This surfaces a missed
 * "Call mom every Friday" in Backlog the day after, instead of it vanishing
 * until next Friday.
 *
 * Keyed to the MOST-RECENT past occurrence on purpose:
 *   • At most ONE overdue row per template — missing several weeks never stacks.
 *   • Completing it marks that occurrence done → most-recent past is now
 *     complete → the row clears. Older incomplete misses are intentionally
 *     IGNORED ("you don't owe 3 calls").
 *   • The next live occurrence (today) takes over: no overdue row while today
 *     is a recurrence day for the template.
 *
 * Row id = the missed block's id (recoverable via [occurrenceBlockFor]); the
 * row's [TaskItem.completedAt] is cleared and recurrence nulled (a plain row).
 * Pure read — never mutates blocks.
 */
fun projectOverdueOccurrences(tasks: List<TaskItem>, blocks: List<CalBlock>, todayIso: String): List<TaskItem> {
    val templates = tasks.filter { it.recurrence != null }.associateBy { it.id }
    if (templates.isEmpty()) return emptyList()

    val latestPast = HashMap<String, CalBlock>()   // template id -> most-recent PAST occurrence block
    val hasToday = HashSet<String>()                // template ids with an occurrence dated today
    for (b in blocks) {
        if (!isTaskBlock(b) || b.taskId == null || b.taskId !in templates) continue
        when {
            b.date == todayIso -> hasToday.add(b.taskId)
            b.date > todayIso -> continue
            else -> {
                val cur = latestPast[b.taskId]
                if (cur == null || b.date > cur.date) latestPast[b.taskId] = b
            }
        }
    }

    return latestPast.mapNotNull { (templateId, b) ->
        if (templateId in hasToday) return@mapNotNull null   // today's occurrence takes over
        if (b.done || b.skipped) return@mapNotNull null       // most-recent past already handled
        val tpl = templates[templateId] ?: return@mapNotNull null
        tpl.copy(
            id = b.id,
            done = false,
            completedAt = null,
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

/**
 * "Overdue · Fri"-style label for a Backlog row that is a MISSED recurring
 * occurrence, or null if the row is anything else. Mirrors the indicator the
 * web shows on overdue occurrence rows. A row is an overdue occurrence when its
 * id resolves to a recurring template's cal_block ([occurrenceBlockFor]) whose
 * date is strictly before [todayIso] — exactly the rows
 * [projectOverdueOccurrences] surfaces in Backlog. The weekday is the missed
 * date's day (Sun…Sat), matching the calendar's DOW labels.
 */
fun overdueOccurrenceLabel(rowId: String, tasks: List<TaskItem>, blocks: List<CalBlock>, todayIso: String): String? {
    val b = occurrenceBlockFor(rowId, tasks, blocks) ?: return null
    if (b.date >= todayIso) return null
    val ms = Time.parseMillis(b.date) ?: return "Overdue"
    return "Overdue · ${OVERDUE_DOW[Time.dayOfWeekJs(ms)]}"
}

/** The row to open when a calendar block is tapped: the per-day OCCURRENCE
 *  (id = block id) when the block belongs to a recurring template, else the
 *  normal task. Lets the detail sheet treat it as an occurrence. */
fun taskForBlock(block: CalBlock, tasks: List<TaskItem>): TaskItem? {
    val t = tasks.firstOrNull { it.id == block.taskId } ?: return null
    return if (t.recurrence != null) {
        t.copy(id = block.id, recurrence = null, done = block.done, completedAt = block.completedAt, estimateMin = block.durationMinutes)
    } else {
        t
    }
}
