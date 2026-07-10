package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.Priority
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.model.TaskListView
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.time.Time

// Port of lib/pick-start-next.ts. Deterministic "what should I work on next?"
// ranker — powers Start Next, Up Next, the /tasks NEXT badge, and the
// focus-mode UP NEXT panel, so every surface agrees.
//
// Rules: exclude done + Later + currently-focused tasks, honour the active
// area filter, then rank by priority desc → estimateMin asc → createdAt asc.

private fun priorityRank(p: Priority?): Int = when (p ?: Priority.LOW) {
    Priority.URGENT -> 4
    Priority.HIGH -> 3
    Priority.MEDIUM -> 2
    Priority.LOW -> 1
}

// ISO createdAt strings sort lexicographically == chronologically, matching
// the web's localeCompare tiebreak. Kotlin's sortedWith is stable.
private val ranker = Comparator<TaskItem> { a, b ->
    val ar = priorityRank(a.priority)
    val br = priorityRank(b.priority)
    if (ar != br) return@Comparator br - ar
    if (a.estimateMin != b.estimateMin) return@Comparator a.estimateMin - b.estimateMin
    a.createdAt.compareTo(b.createdAt)
}

fun pickStartNext(
    tasks: List<TaskItem>,
    blocks: List<CalBlock>,
    liveTaskId: String?,
    areaFilter: String? = null,
    // excludeIds: tasks you've assigned away — they're someone else's now.
    excludeIds: Set<String>? = null,
): TaskItem? =
    tasks
        // recurrence == null: skip recurring TEMPLATES (hidden definitions);
        // their per-day occurrences surface in Today on their own.
        .filter { !it.done && it.later != true && it.recurrence == null && it.id != liveTaskId && it.id !in (excludeIds ?: emptySet()) }
        .filter { matchesArea(it.lifeArea, areaFilter) }
        .sortedWith(ranker)
        .firstOrNull()

fun pickUpNext(
    tasks: List<TaskItem>,
    blocks: List<CalBlock>,
    liveTaskId: String?,
    startNextId: String?,
    limit: Int = 3,
    // excludeIds: tasks you've assigned away — no longer your work to queue up.
    excludeIds: Set<String>? = null,
): List<TaskItem> {
    val skip = setOfNotNull(liveTaskId, startNextId)
    return tasks
        .filter { !it.done && it.later != true && it.recurrence == null && it.id !in skip && it.id !in (excludeIds ?: emptySet()) }
        .sortedWith(ranker)
        .take(limit)
}

/**
 * The Today "Start Next" hero pick — scoped to TODAY, never the backlog:
 *  1. If any task is SCHEDULED today (a cal_block dated today, incl. a recurring
 *     occurrence), return the NEXT by start time — the soonest start ≥ the
 *     current time, else the earliest of the day if all of today's are past.
 *  2. Else, among today's UNscheduled tasks (created today, no block today),
 *     return the lowest-friction one — the shortest estimate.
 *  3. Else null — the caller shows a "check your Backlog" pointer instead of
 *     pulling a backlog task into the hero.
 */
fun pickTodayHero(
    tasks: List<TaskItem>,
    blocks: List<CalBlock>,
    now: Long,
    liveTaskId: String? = null,
    areaFilter: String? = null,
    // excludeIds: tasks you've assigned away — never surface them in the hero.
    excludeIds: Set<String>? = null,
): TaskItem? {
    // Today's open rows (non-template today tasks + today's occurrences), minus the
    // live-focused task and anything assigned away, narrowed by the active area.
    val rows = visibleTasks(TaskListView.TODAY, tasks, blocks, now, activeArea = null, slipMode = false)
        .filter { it.id != liveTaskId && it.id !in (excludeIds ?: emptySet()) && matchesArea(it.lifeArea, areaFilter) }
    if (rows.isEmpty()) return null

    val today = Clock.todayIso()
    val todayBlocks = blocks.filter { isTaskBlock(it) && it.date == today && !it.skipped }
    // A row's today start time: an occurrence row's id IS its block id; a normal
    // task matches by taskId (its earliest block today).
    fun startToday(row: TaskItem): String? {
        todayBlocks.firstOrNull { it.id == row.id }?.let { return it.startTime }
        return todayBlocks.filter { it.taskId == row.id }.minOfOrNull { it.startTime }
    }
    val scheduled = rows.mapNotNull { row -> startToday(row)?.let { row to it } }

    if (scheduled.isNotEmpty()) {
        val nowHhmm = currentLocalHhmm(now)
        val upcoming = scheduled.filter { it.second >= nowHhmm }   // still ahead today
        val pool = upcoming.ifEmpty { scheduled }                  // all past → earliest of the day
        return pool.minWithOrNull(
            compareBy<Pair<TaskItem, String>> { it.second }.thenComparing({ it.first }, ranker),
        )?.first
    }
    // No scheduled-today: lowest friction = shortest estimate (shared tiebreak after).
    return rows.minWithOrNull(compareBy<TaskItem> { it.estimateMin }.thenComparing(ranker))
}

/** Current LOCAL time as "HH:MM" — for comparing against cal_block start times
 *  (which are stored in the device's local timezone). */
private fun currentLocalHhmm(now: Long): String =
    "%02d:%02d".format(Time.hourOf(now), Time.minuteOf(now))
