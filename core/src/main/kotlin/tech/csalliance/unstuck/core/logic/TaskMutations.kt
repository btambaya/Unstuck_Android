package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.TaskItem

// Pure task-mutation rules from lib/use-tasks.ts: the done-flip completedAt
// stamping and the reschedule move-count bump. The web hook wraps these with
// storage + Supabase writes; the rules themselves are pure and injected with
// `nowISO` for determinism.

/** First done-flip captures the completion time; subsequent toggles preserve
 *  the original. Un-completing CLEARS the timestamp so a later re-completion
 *  re-stamps fresh. */
fun stampCompletion(isDone: Boolean, incomingCompletedAt: String?, priorCompletedAt: String?, nowISO: String): String? =
    if (isDone) incomingCompletedAt ?: priorCompletedAt ?: nowISO else null

/** Apply the completion stamp + bump updatedAt to `item`, given its prior
 *  stored version (for the preserve-original-timestamp rule). */
fun applyCompletion(item: TaskItem, prior: TaskItem?, nowISO: String): TaskItem =
    item.copy(
        completedAt = stampCompletion(item.done, item.completedAt, prior?.completedAt, nowISO),
        updatedAt = nowISO,
    )

/** Increment a task's reschedule counter (feeds the slip detector). */
fun bumpMoveCount(task: TaskItem, nowISO: String): TaskItem =
    task.copy(moveCount = (task.moveCount ?: 0) + 1, updatedAt = nowISO)

/**
 * "Parked for Later" and "it's on Tuesday at 2" are mutually exclusive states:
 * giving a task a real calendar slot ENDS its Later parking.
 *
 * Returns the task with `later` cleared, or null when there is nothing to do —
 * so a caller writes exactly once, and only when it matters. Without this a
 * scheduled task sat on the calendar at the time the user picked while every
 * active list filtered it out as deferred (Tasks → Today + Backlog, the Today
 * screen, Start-next, the assistant's suggestions); only the Later tab still
 * showed it. The task-detail sheet cleared the flag at its own call site, but
 * the calendar drop, the create sheet and the assistant did not — which is the
 * hole this closes, at the scheduling choke points.
 *
 * Recurring TEMPLATES are left alone: their occurrence blocks are generated
 * horizon fill rather than a per-task scheduling decision (and projected
 * occurrence rows are already `later = false`).
 */
fun clearLaterOnSchedule(task: TaskItem, nowISO: String): TaskItem? {
    if (task.later != true) return null
    if (task.recurrence != null) return null
    return task.copy(later = false, updatedAt = nowISO)
}
