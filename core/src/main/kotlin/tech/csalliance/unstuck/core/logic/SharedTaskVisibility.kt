package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.SharedWithMe
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.time.DAY_MS
import tech.csalliance.unstuck.core.time.Time

// Port of lib/shared-task-visibility.ts. Where a shared task shows up — the same
// rules the user's OWN tasks follow ([visibleTasks]), placed by the OWNER's next
// live block (migration 052, [shareBucket]):
//   Today     — the owner's next block is today, OR nothing is scheduled (a share
//               that arrived without a plan sits with today's work, like a fresh
//               own task). Completed rows leave immediately.
//   Upcoming  — next block after today.
//   Backlog   — next block before today and still open (overdue).
//   Later     — the owner parked it in Later (migration 053) — theirs to un-park.
//   All       — everything open (incl. Later + a task whose latest block already
//               ran and finished); a completed row lingers only if finished today.
//   Completed — every finished share, however old.
// Dates are the recipient's OWN zone (the sync client resolves `next_start_at`).
// Applies whoever ticked it — owner or an assign/partner recipient (Ahmad,
// 2026-08-02: completed shared tasks should move to Completed "for all, like all
// other tasks").
//
// Pure + unit-tested; SharedWithMe.completedAt / next* are OPTIONAL so the client
// behaves correctly against an RPC that hasn't been migrated yet (049 / 052):
// without a timestamp a completed share simply leaves the active lists, and
// without a schedule every open share lands in Today (the pre-052 behaviour).

/** Which of the recipient's own list views a shared row is being rendered into.
 *  Extends the web ShareViewMode union ('today' | 'all' | 'completed') with the
 *  two date buckets the schedule projection unlocks. */
enum class ShareViewMode { TODAY, UPCOMING, BACKLOG, LATER, ALL, COMPLETED }

/** True when this shared row belongs in the given view. [todayIso] is the LOCAL
 *  'YYYY-MM-DD' the date buckets pivot on (defaults to the day containing [now]). */
fun shareVisibleIn(
    item: SharedWithMe,
    mode: ShareViewMode,
    now: Long,
    todayIso: String = Clock.dateIso(now),
): Boolean {
    if (mode == ShareViewMode.COMPLETED) return item.done
    if (item.done) {
        // Completed rows: only "finished today" lingers, and only in All — mirroring
        // isCompletedToday() for the user's own tasks. A missing OR unparseable
        // timestamp resolves to "not today", so a done row never lingers by accident.
        if (mode != ShareViewMode.ALL) return false
        val t = item.completedAt?.let { Time.parseMillis(it) } ?: return false
        val start = Time.startOfDayMillis(now)
        return t >= start && t < start + DAY_MS
    }
    return when (mode) {
        ShareViewMode.ALL -> true
        ShareViewMode.TODAY -> when (shareBucket(item, todayIso)) {
            ShareBucket.TODAY, ShareBucket.UNSCHEDULED -> true
            else -> false
        }
        ShareViewMode.UPCOMING -> shareBucket(item, todayIso) == ShareBucket.UPCOMING
        ShareViewMode.BACKLOG -> shareBucket(item, todayIso) == ShareBucket.OVERDUE
        ShareViewMode.LATER -> shareBucket(item, todayIso) == ShareBucket.LATER
        ShareViewMode.COMPLETED -> false   // handled above; keeps the `when` exhaustive
    }
}

/** Filter + order a shared list for a view: open first (chronological by the
 *  owner's slot, unscheduled last — a stable sort, so equal slots keep the
 *  projection's order), completed last (never park struck-through rows above the
 *  next thing to do). [activeArea] narrows by life area via [shareMatchesArea]
 *  (null = no filter). */
fun visibleShares(
    items: List<SharedWithMe>,
    mode: ShareViewMode,
    now: Long = System.currentTimeMillis(),
    todayIso: String = Clock.dateIso(now),
    activeArea: String? = null,
): List<SharedWithMe> {
    val kept = items.filter { shareVisibleIn(it, mode, now, todayIso) && shareMatchesArea(it.lifeArea, activeArea) }
    return kept.filter { !it.done }.sortedWith(shareSlotComparator) + kept.filter { it.done }
}
