package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.Priority
import tech.csalliance.unstuck.core.model.TaskItem

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
): TaskItem? =
    tasks
        // recurrence == null: skip recurring TEMPLATES (hidden definitions);
        // their per-day occurrences surface in Today on their own.
        .filter { !it.done && it.later != true && it.recurrence == null && it.id != liveTaskId }
        .filter { matchesArea(it.lifeArea, areaFilter) }
        .sortedWith(ranker)
        .firstOrNull()

fun pickUpNext(
    tasks: List<TaskItem>,
    blocks: List<CalBlock>,
    liveTaskId: String?,
    startNextId: String?,
    limit: Int = 3,
): List<TaskItem> {
    val skip = setOfNotNull(liveTaskId, startNextId)
    return tasks
        .filter { !it.done && it.later != true && it.recurrence == null && it.id !in skip }
        .sortedWith(ranker)
        .take(limit)
}
