package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.DAY_MS
import tech.csalliance.unstuck.core.time.Time

// Port of lib/task-bucket.ts. "Is this task in today's bucket?" — the Today
// list + /tasks "All" filter agree about when a completed task drops off.

fun isCompletedToday(task: TaskItem, now: Long): Boolean {
    val t = task.completedAt?.let { Time.parseMillis(it) } ?: return false
    val start = Time.startOfDayMillis(now)
    return t >= start && t < start + DAY_MS
}

/** True if the task was created during today's local-midnight window — so
 *  freshly-created tasks (no cal_block yet) still surface in Today. */
fun isCreatedToday(task: TaskItem, now: Long): Boolean {
    val t = Time.parseMillis(task.createdAt) ?: return false
    val start = Time.startOfDayMillis(now)
    return t >= start && t < start + DAY_MS
}
