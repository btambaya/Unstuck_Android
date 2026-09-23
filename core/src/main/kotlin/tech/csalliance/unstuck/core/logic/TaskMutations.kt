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

/**
 * The server CHECK is `estimate_min between 1 and 1440` (migration 001). An
 * out-of-range value is accepted locally, refused by PostgREST on flush,
 * retried five times and then quarantined — the row lives on that one phone
 * for ever, holds every block of the task back behind it, and the user is
 * never told. The ONE rule for the estimate range: WriteThrough and the
 * outbox flush clamp with it too, so a writer that forgets can no longer
 * strand a row (parity with iOS build 81, audit 2026-09-22 C4).
 */
fun clampEstimateMin(raw: Int?): Int = (raw ?: 25).coerceIn(1, 1440)

/**
 * `duration_minutes between 5 and 1440` (migration 001), so a 2-minute task
 * would otherwise mint a block the server refuses. A task keeps a 1-4 minute
 * estimate; its block floors at 5. The ONE rule for a block's length:
 * recurrence minting, WriteThrough and the outbox flush all use it (parity
 * with iOS build 81, audit 2026-09-22 C4).
 */
fun clampDurationMin(raw: Int?, fallback: Int = 25): Int = (raw ?: fallback).coerceIn(5, 1440)
