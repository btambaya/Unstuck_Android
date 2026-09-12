package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.DAY_MS
import tech.csalliance.unstuck.core.time.Time

// Port of lib/task-bucket.ts. "Is this task in today's bucket?" — the Today
// list + /tasks "All" filter agree about when a completed task drops off.

fun isCompletedToday(task: TaskItem, now: Long): Boolean =
    isCompletedTodayIn(task, Time.startOfDayMillis(now))

/** True if the task was created during today's local-midnight window — so
 *  freshly-created tasks (no cal_block yet) still surface in Today. */
fun isCreatedToday(task: TaskItem, now: Long): Boolean =
    isCreatedTodayIn(task, Time.startOfDayMillis(now))

// Same two predicates against an ALREADY-COMPUTED local midnight. Callers that
// test hundreds of tasks against one `now` (visibleTasks) resolve the zone and
// the day boundary once instead of once per task; the result is identical.

fun isCompletedTodayIn(task: TaskItem, dayStartMs: Long): Boolean {
    val t = task.completedAt?.let { Time.parseMillis(it) } ?: return false
    return t >= dayStartMs && t < dayStartMs + DAY_MS
}

fun isCreatedTodayIn(task: TaskItem, dayStartMs: Long): Boolean {
    val t = Time.parseMillis(task.createdAt) ?: return false
    return t >= dayStartMs && t < dayStartMs + DAY_MS
}
