package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.ShareViewMode
import tech.csalliance.unstuck.core.logic.shareVisibleIn
import tech.csalliance.unstuck.core.logic.visibleShares
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.core.model.SharedWithMe
import tech.csalliance.unstuck.core.time.Time

// 1:1 with lib/shared-task-visibility.test.ts — a completed SHARED task follows the
// same rules as the user's own completed tasks (Today hides it, All keeps today's
// win, Completed collects them all). Tests run with -Duser.timezone=UTC.
class SharedTaskVisibilityTest {

    // 2026-08-02T18:00 local — same instant the web test pins.
    private val now = Time.civil(2026, 8, 2) + 18L * 60 * 60 * 1000

    private fun share(id: String, done: Boolean, completedAt: String? = null) = SharedWithMe(
        shareId = id, taskId = id, ownerName = "sam@example.com",
        level = ShareLevel.PARTNER, title = id, done = done, completedAt = completedAt,
    )

    private val open = share("open", done = false)
    private val doneToday = share("done", done = true, completedAt = "2026-08-02T09:30:00")
    private val doneYesterday = share("old", done = true, completedAt = "2026-08-01T09:30:00")
    private val doneUnknownWhen = share("unknown", done = true)

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
}
