package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.TaskItem

/**
 * What the calendar's Edit-block sheet offers on a block, besides Start time,
 * Duration and Unschedule (Ahmad 2026-09-24: "Can't complete a task from
 * calendar"). The web calendar modal (cal-block-edit-modal.tsx) is the
 * reference: Start now, Mark complete, Open in tasks.
 *
 * [row] is the row Today shows for the block, from [taskForBlock]:
 *  - a repeating series' block → that day's OCCURRENCE (id = block id, done =
 *    the block's done), so Mark done ticks that one day and never ends the series;
 *  - anything else → the task itself.
 * The sheet hands [row] to the paths Today and the task screen already use
 * (AppViewModel.toggleDone / the focus overlay / the task route), so there is no
 * second way to complete a task.
 *
 * No [row] (a Google event, reserved time, a block shared with me, a block whose
 * task is gone): no task actions at all.
 */
data class CalBlockSheetActions(
    val row: TaskItem?,
    /** Mark done / Mark not done is offered. */
    val canComplete: Boolean,
    /** Start focus is offered. */
    val canFocus: Boolean,
    /** Who a task I assigned out now belongs to; the task is view-only here, as
     *  on the task screen (AppViewModel refuses both writes too). */
    val assignedTo: String? = null,
) {
    /** Open task is offered. */
    val canOpen: Boolean get() = row != null

    /** The row's done: the day's for an occurrence, the task's otherwise. */
    val done: Boolean get() = row?.done == true

    /** "Mark done", or "Mark not done" once it is done: one toggle, like the
     *  task screen's. */
    val completeLabel: String get() = if (done) MARK_NOT_DONE else MARK_DONE

    companion object {
        const val MARK_DONE = "Mark done"
        const val MARK_NOT_DONE = "Mark not done"
        const val START_FOCUS = "Start focus"
        const val OPEN_TASK = "Open task"

        /** A block the sheet has no task actions for. */
        val NONE = CalBlockSheetActions(row = null, canComplete = false, canFocus = false)
    }
}

/** [CalBlockSheetActions] for [block]. [assignedOut] is task id → the person it
 *  is assigned to (AppViewModel.assignedOut). */
fun calBlockSheetActions(
    block: CalBlock,
    tasks: List<TaskItem>,
    assignedOut: Map<String, String> = emptyMap(),
): CalBlockSheetActions {
    if (isSharedBlockId(block.id) || !isTaskBlock(block)) return CalBlockSheetActions.NONE
    val row = taskForBlock(block, tasks) ?: return CalBlockSheetActions.NONE
    // An occurrence's id is its block id, which is never a task id, so only a
    // plain task can match. Its recipient does it now: no Mark done, no Focus.
    val assignedTo = assignedOut[row.id]
    val canAct = assignedTo == null
    return CalBlockSheetActions(row = row, canComplete = canAct, canFocus = canAct, assignedTo = assignedTo)
}
