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
