package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.ShareViewMode
import tech.csalliance.unstuck.core.logic.UNASSIGNED_AREA
import tech.csalliance.unstuck.core.logic.shareVisibleIn
import tech.csalliance.unstuck.core.logic.visibleShares
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.core.model.SharedWithMe
import tech.csalliance.unstuck.core.time.Time

// 1:1 with lib/shared-task-visibility.test.ts — a completed SHARED task follows the
// same rules as the user's own completed tasks (Today hides it, All keeps today's
// win, Completed collects them all) — extended for migration 052: an OPEN share is
// placed by the owner's next block (Today / Upcoming / Backlog). Tests run with
// -Duser.timezone=UTC.
class SharedTaskVisibilityTest {

    // 2026-08-02T18:00 local — same instant the web test pins. A Sunday.
    private val now = Time.civil(2026, 8, 2) + 18L * 60 * 60 * 1000
    private val today = "2026-08-02"

    private fun share(
        id: String, done: Boolean, completedAt: String? = null,
        nextDate: String? = null, nextStartTime: String? = null, lifeArea: String? = null,
        nextDone: Boolean? = null, later: Boolean = false,
    ) = SharedWithMe(
        shareId = id, taskId = id, ownerName = "sam@example.com",
        level = ShareLevel.PARTNER, title = id, done = done, completedAt = completedAt,
        nextDate = nextDate, nextStartTime = nextStartTime, nextDurationMinutes = 45, lifeArea = lifeArea,
        nextDone = nextDone, later = later,
    )

    private val open = share("open", done = false)
    private val doneToday = share("done", done = true, completedAt = "2026-08-02T09:30:00")
    private val doneYesterday = share("old", done = true, completedAt = "2026-08-01T09:30:00")
    private val doneUnknownWhen = share("unknown", done = true)

    // ── the pre-052 rules (no schedule → every open share sits in Today) ──

    @Test fun `Today shows open work only - a completed share leaves immediately`() {
        assertTrue(shareVisibleIn(open, ShareViewMode.TODAY, now))
        assertFalse(shareVisibleIn(doneToday, ShareViewMode.TODAY, now))
        assertFalse(shareVisibleIn(doneYesterday, ShareViewMode.TODAY, now))
    }

    @Test fun `All keeps todays win visible but ages older completions out`() {
        assertTrue(shareVisibleIn(open, ShareViewMode.ALL, now))
        assertTrue(shareVisibleIn(doneToday, ShareViewMode.ALL, now))
        assertFalse(shareVisibleIn(doneYesterday, ShareViewMode.ALL, now))
    }

    @Test fun `Completed holds every finished share, however old`() {
        assertTrue(shareVisibleIn(doneYesterday, ShareViewMode.COMPLETED, now))
        assertTrue(shareVisibleIn(doneToday, ShareViewMode.COMPLETED, now))
        assertFalse(shareVisibleIn(open, ShareViewMode.COMPLETED, now))
    }

    @Test fun `without a completion time (pre-049 projection) a done share still leaves the active lists`() {
        assertFalse(shareVisibleIn(doneUnknownWhen, ShareViewMode.TODAY, now))
        assertFalse(shareVisibleIn(doneUnknownWhen, ShareViewMode.ALL, now))
        assertTrue(shareVisibleIn(doneUnknownWhen, ShareViewMode.COMPLETED, now))
    }

    @Test fun `a malformed timestamp never keeps a completed row in the active list`() {
        val bad = share("bad", done = true, completedAt = "not-a-date")
        assertFalse(shareVisibleIn(bad, ShareViewMode.ALL, now))
        assertFalse(shareVisibleIn(bad, ShareViewMode.TODAY, now))
        assertTrue(shareVisibleIn(bad, ShareViewMode.COMPLETED, now))
    }

    @Test fun `visibleShares filters by view and always puts open rows above completed ones`() {
        val items = listOf(
            doneToday,
            share("open1", done = false),
            doneYesterday,
            share("open2", done = false),
        )
        assertEquals(
            listOf("open1", "open2", "done"),
            visibleShares(items, ShareViewMode.ALL, now).map { it.shareId },
        )
        assertEquals(
            listOf("open1", "open2"),
            visibleShares(items, ShareViewMode.TODAY, now).map { it.shareId },
        )
        assertEquals(
            listOf("done", "old"),
            visibleShares(items, ShareViewMode.COMPLETED, now).map { it.shareId },
        )
    }

    // ── migration 052: placed by the owner's next block ──

    private val plannedToday = share("today", done = false, nextDate = today, nextStartTime = "14:00")
    private val plannedTomorrow = share("tomorrow", done = false, nextDate = "2026-08-03", nextStartTime = "09:00")
    private val plannedNextMonth = share("later", done = false, nextDate = "2026-09-10", nextStartTime = "09:00")
    private val overdue = share("overdue", done = false, nextDate = "2026-07-30", nextStartTime = "09:00")
    private val doneOverdue = share("done-overdue", done = true, nextDate = "2026-07-30", completedAt = "2026-07-30T10:00:00")

    @Test fun `Today = next block today OR nothing scheduled - never a future or past slot`() {
        assertTrue(shareVisibleIn(plannedToday, ShareViewMode.TODAY, now, today))
        assertTrue(shareVisibleIn(open, ShareViewMode.TODAY, now, today))          // no block → Today
        assertFalse(shareVisibleIn(plannedTomorrow, ShareViewMode.TODAY, now, today))
        assertFalse(shareVisibleIn(plannedNextMonth, ShareViewMode.TODAY, now, today))
        assertFalse(shareVisibleIn(overdue, ShareViewMode.TODAY, now, today))
    }

    @Test fun `Upcoming = next block after today only`() {
        assertTrue(shareVisibleIn(plannedTomorrow, ShareViewMode.UPCOMING, now, today))
        assertTrue(shareVisibleIn(plannedNextMonth, ShareViewMode.UPCOMING, now, today))
        assertFalse(shareVisibleIn(plannedToday, ShareViewMode.UPCOMING, now, today))
        assertFalse(shareVisibleIn(open, ShareViewMode.UPCOMING, now, today))
        assertFalse(shareVisibleIn(overdue, ShareViewMode.UPCOMING, now, today))
        assertFalse(shareVisibleIn(doneToday, ShareViewMode.UPCOMING, now, today))
    }

    @Test fun `Backlog = next block before today and still open`() {
        assertTrue(shareVisibleIn(overdue, ShareViewMode.BACKLOG, now, today))
        assertFalse(shareVisibleIn(doneOverdue, ShareViewMode.BACKLOG, now, today))   // done → Completed, never Backlog
        assertFalse(shareVisibleIn(plannedToday, ShareViewMode.BACKLOG, now, today))
        assertFalse(shareVisibleIn(plannedTomorrow, ShareViewMode.BACKLOG, now, today))
        assertFalse(shareVisibleIn(open, ShareViewMode.BACKLOG, now, today))          // unplanned ≠ overdue
    }

    @Test fun `All keeps every open share whatever its slot, plus todays win`() {
        listOf(open, plannedToday, plannedTomorrow, plannedNextMonth, overdue).forEach {
            assertTrue(it.shareId, shareVisibleIn(it, ShareViewMode.ALL, now, today))
        }
        assertTrue(shareVisibleIn(doneToday, ShareViewMode.ALL, now, today))
        assertFalse(shareVisibleIn(doneOverdue, ShareViewMode.ALL, now, today))
    }

    @Test fun `Completed is unchanged by the schedule`() {
        assertTrue(shareVisibleIn(doneOverdue, ShareViewMode.COMPLETED, now, today))
        assertFalse(shareVisibleIn(overdue, ShareViewMode.COMPLETED, now, today))
    }

    @Test fun `todayIso defaults to the local day containing now`() {
        // No explicit todayIso: the bucket pivots on the day of `now` (2026-08-02 UTC).
        assertTrue(shareVisibleIn(plannedToday, ShareViewMode.TODAY, now))
        assertTrue(shareVisibleIn(plannedTomorrow, ShareViewMode.UPCOMING, now))
        assertTrue(shareVisibleIn(overdue, ShareViewMode.BACKLOG, now))
    }

    @Test fun `open rows sort chronologically by the owners slot, unscheduled last, done last`() {
        val items = listOf(
            share("b-later", done = false, nextDate = today, nextStartTime = "16:00"),
            share("unplanned", done = false),
            doneToday,
            share("a-early", done = false, nextDate = today, nextStartTime = "08:00"),
        )
        assertEquals(
            listOf("a-early", "b-later", "unplanned", "done"),
            visibleShares(items, ShareViewMode.ALL, now, today).map { it.shareId },
        )
        // Across dates the date wins over the time.
        assertEquals(
            listOf("tomorrow", "later"),
            visibleShares(listOf(plannedNextMonth, plannedTomorrow), ShareViewMode.UPCOMING, now, today).map { it.shareId },
        )
    }

    // ── migration 053 / the 2026-09 cross-platform rules ──

    private val finishedPast = share("finished", done = false, nextDate = "2026-07-30", nextStartTime = "09:00", nextDone = true)
    private val parkedLater = share("parked", done = false, nextDate = today, later = true)

    @Test fun `a share whose only block is a FINISHED past block shows in All, never in Today or Backlog`() {
        assertFalse(shareVisibleIn(finishedPast, ShareViewMode.TODAY, now, today))
        assertFalse(shareVisibleIn(finishedPast, ShareViewMode.BACKLOG, now, today))
        assertFalse(shareVisibleIn(finishedPast, ShareViewMode.UPCOMING, now, today))
        assertTrue(shareVisibleIn(finishedPast, ShareViewMode.ALL, now, today))
        assertFalse(shareVisibleIn(finishedPast, ShareViewMode.COMPLETED, now, today))   // the task itself is still open
        // An UNFINISHED past block is still overdue (unchanged).
        assertTrue(shareVisibleIn(overdue, ShareViewMode.BACKLOG, now, today))
    }

    @Test fun `the owners Later flag keeps a share out of Today Upcoming and Backlog - Later and All hold it`() {
        assertFalse(shareVisibleIn(parkedLater, ShareViewMode.TODAY, now, today))
        assertFalse(shareVisibleIn(parkedLater, ShareViewMode.UPCOMING, now, today))
        assertFalse(shareVisibleIn(parkedLater, ShareViewMode.BACKLOG, now, today))
        assertTrue(shareVisibleIn(parkedLater, ShareViewMode.LATER, now, today))
        assertTrue(shareVisibleIn(parkedLater, ShareViewMode.ALL, now, today))
        assertFalse(shareVisibleIn(plannedToday, ShareViewMode.LATER, now, today))
        assertFalse(shareVisibleIn(open, ShareViewMode.LATER, now, today))
        // A completed Later share is Completed, not Later.
        val doneLater = share("done-later", done = true, later = true, completedAt = "2026-08-02T09:30:00")
        assertFalse(shareVisibleIn(doneLater, ShareViewMode.LATER, now, today))
        assertTrue(shareVisibleIn(doneLater, ShareViewMode.COMPLETED, now, today))
    }

    @Test fun `All orders open rows chronologically with unscheduled and later-ish rows sinking by slot`() {
        val items = listOf(finishedPast, parkedLater, share("unplanned", done = false), plannedTomorrow, plannedToday)
        // finishedPast (Jul 30) < plannedToday (Aug 2, 14:00) = parkedLater (Aug 2, no time → "" sorts first) < tomorrow < unscheduled.
        assertEquals(
            listOf("finished", "parked", "today", "tomorrow", "unplanned"),
            visibleShares(items, ShareViewMode.ALL, now, today).map { it.shareId },
        )
    }

    @Test fun `the active life-area filter narrows the group but never hides an area-less share`() {
        val work = share("work", done = false, lifeArea = "Work")
        val home = share("home", done = false, lifeArea = "Home")
        val none = share("none", done = false)
        val items = listOf(work, home, none)
        assertEquals(listOf("work", "home", "none"), visibleShares(items, ShareViewMode.TODAY, now, today, activeArea = null).map { it.shareId })
        assertEquals(listOf("work", "none"), visibleShares(items, ShareViewMode.TODAY, now, today, activeArea = "Work").map { it.shareId })
        assertEquals(listOf("none"), visibleShares(items, ShareViewMode.TODAY, now, today, activeArea = UNASSIGNED_AREA).map { it.shareId })
    }
}
