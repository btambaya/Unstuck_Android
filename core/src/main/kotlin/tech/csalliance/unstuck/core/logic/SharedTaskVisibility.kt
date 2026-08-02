package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.SharedWithMe
import tech.csalliance.unstuck.core.time.DAY_MS
import tech.csalliance.unstuck.core.time.Time

// Port of lib/shared-task-visibility.ts. Where a shared task shows up once it's
// completed — the same rules the user's OWN tasks follow ([visibleTasks]): Today
// hides completed work, "All" keeps today's wins visible (struck, last) and ages
// older ones out, and Completed is where finished things live. Applies whoever
// ticked it — owner or an assign/partner recipient (Ahmad, 2026-08-02: completed
// shared tasks should move to Completed "for all, like all other tasks").
//
// Pure + unit-tested; SharedWithMe.completedAt is OPTIONAL so the client behaves
// correctly against an RPC that hasn't been migrated yet (migration 049) —
// without a timestamp a completed share simply leaves the active lists.

/** Which of the recipient's own list views a shared row is being rendered into.
 *  Mirrors the web ShareViewMode union ('today' | 'all' | 'completed'). */
enum class ShareViewMode { TODAY, ALL, COMPLETED }

/** True when this shared row belongs in the given view. */
fun shareVisibleIn(item: SharedWithMe, mode: ShareViewMode, now: Long): Boolean {
    if (mode == ShareViewMode.COMPLETED) return item.done
    if (!item.done) return true
    // Completed rows: only "finished today" lingers, and only in All — mirroring
    // isCompletedToday() for the user's own tasks. A missing OR unparseable
    // timestamp resolves to "not today", so a done row never lingers by accident.
    if (mode == ShareViewMode.TODAY) return false
    val t = item.completedAt?.let { Time.parseMillis(it) } ?: return false
    val start = Time.startOfDayMillis(now)
    return t >= start && t < start + DAY_MS
}

/** Filter + order a shared list for a view: open first, completed last (never park
 *  struck-through rows above the next thing to do). Order within each bucket is
 *  preserved, matching [visibleTasks]. */
fun visibleShares(
    items: List<SharedWithMe>,
    mode: ShareViewMode,
    now: Long = System.currentTimeMillis(),
): List<SharedWithMe> {
    val kept = items.filter { shareVisibleIn(it, mode, now) }
    return kept.filter { !it.done } + kept.filter { it.done }
}
