package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.daysSinceCreated
import tech.csalliance.unstuck.core.logic.isSlipping
import tech.csalliance.unstuck.core.logic.visibleTasks
import tech.csalliance.unstuck.core.model.TaskListView
import tech.csalliance.unstuck.core.time.Clock

// Ported 1:1 from VisibleTasksTests.swift / lib/visible-tasks.test.ts.
class VisibleTasksTest {

    private fun ids(list: List<tech.csalliance.unstuck.core.model.TaskItem>) = list.map { it.id }

    // --- area-agnostic Today ---

    @Test fun todayShowsEveryAreaScheduledTodayEvenWithActiveArea() {
        val work = mkTask(id = "t-work", lifeArea = "Work")
        val personal = mkTask(id = "t-personal", lifeArea = "Personal")
        val home = mkTask(id = "t-home", lifeArea = "Home")
        val blocks = listOf(
            mkBlock(id = "b1", taskId = "t-work"),
            mkBlock(id = "b2", taskId = "t-personal"),
            mkBlock(id = "b3", taskId = "t-home", date = todayPlus(1)),
        )
        val out = visibleTasks(TaskListView.TODAY, listOf(work, personal, home), blocks, NOW, "Work", slipMode = false)
        assertEquals(listOf("t-personal", "t-work"), ids(out).sorted())
        assertNull(out.firstOrNull { it.id == "t-home" })
    }

    @Test fun allViewStillHonoursActiveArea() {
        val work = mkTask(id = "t-work", lifeArea = "Work")
        val personal = mkTask(id = "t-personal", lifeArea = "Personal")
        val out = visibleTasks(TaskListView.ALL, listOf(work, personal), emptyList(), NOW, "Work", slipMode = false)
        assertEquals(listOf("t-work"), ids(out))
    }

    @Test fun upcomingHonoursActiveArea() {
        val tomorrow = todayPlus(1)
        val work = mkTask(id = "t-work", lifeArea = "Work")
        val personal = mkTask(id = "t-personal", lifeArea = "Personal")
        val out = visibleTasks(
            TaskListView.UPCOMING, listOf(work, personal),
            listOf(mkBlock(id = "b1", taskId = "t-work", date = tomorrow), mkBlock(id = "b2", taskId = "t-personal", date = tomorrow)),
            NOW, "Personal", slipMode = false,
        )
        assertEquals(listOf("t-personal"), ids(out))
    }

    // --- ordering ---

    @Test fun allViewOpenBeforeCompleted() {
        val completed = mkTask(id = "t-done", name = "Done thing", done = true, completedAt = iso(NOW))
        val open1 = mkTask(id = "t-open-1", name = "Open 1")
        val open2 = mkTask(id = "t-open-2", name = "Open 2")
        val out = visibleTasks(TaskListView.ALL, listOf(completed, open1, open2), emptyList(), NOW, null, slipMode = false)
        assertEquals(listOf("t-open-1", "t-open-2", "t-done"), ids(out))
    }

    @Test fun todayViewOpenFirstCompletedFilteredOut() {
        val today = Clock.todayIso()
        val completed = mkTask(id = "t-done", done = true, completedAt = iso(NOW))
        val open = mkTask(id = "t-open")
        val out = visibleTasks(
            TaskListView.TODAY, listOf(completed, open),
            listOf(mkBlock(id = "b1", taskId = "t-done", date = today), mkBlock(id = "b2", taskId = "t-open", date = today)),
            NOW, null, slipMode = false,
        )
        assertEquals(listOf("t-open"), ids(out))
    }

    @Test fun completedViewKeepsOrder() {
        val d1 = mkTask(id = "d1", done = true, completedAt = iso(NOW))
        val d2 = mkTask(id = "d2", done = true, completedAt = iso(NOW - 60_000))
        val out = visibleTasks(TaskListView.COMPLETED, listOf(d1, d2), emptyList(), NOW, null, slipMode = false)
        assertEquals(listOf("d1", "d2"), ids(out))
    }

    // --- Today strict ---

    @Test fun todayScheduledPlusCreatedTodayButNotOlderUnscheduled() {
        val scheduledToday = mkTask(id = "t-sched", createdAt = "2020-01-01T00:00:00.000Z")
        val freshUnscheduled = mkTask(id = "t-fresh", createdAt = iso(NOW))
        val oldUnscheduled = mkTask(id = "t-old", createdAt = "2020-01-01T00:00:00.000Z")
        val futureScheduled = mkTask(id = "t-future", createdAt = "2020-01-01T00:00:00.000Z")
        val blocks = listOf(
            mkBlock(id = "b1", taskId = "t-sched", date = Clock.todayIso()),
            mkBlock(id = "b2", taskId = "t-future", date = todayPlus(1)),
        )
        val out = visibleTasks(
            TaskListView.TODAY, listOf(scheduledToday, freshUnscheduled, oldUnscheduled, futureScheduled),
            blocks, NOW, null, slipMode = false,
        )
        assertEquals(listOf("t-fresh", "t-sched"), ids(out).sorted())
    }

    @Test fun todayExcludesLaterEvenWhenScheduledToday() {
        val later = mkTask(id = "t-later", later = true)
        val out = visibleTasks(
            TaskListView.TODAY, listOf(later),
            listOf(mkBlock(id = "b1", taskId = "t-later", date = Clock.todayIso())), NOW, null, slipMode = false,
        )
        assertEquals(emptyList<String>(), ids(out))
    }

    @Test fun laterTabShowsOnlyLater() {
        val later = mkTask(id = "t-l", later = true)
        val not = mkTask(id = "t-n")
        val out = visibleTasks(TaskListView.LATER, listOf(later, not), emptyList(), NOW, null, slipMode = false)
        assertEquals(listOf("t-l"), ids(out))
    }

    // --- Backlog ---

    @Test fun backlogIncludesUnscheduledOlderThanToday() {
        val unscheduled = mkTask(id = "t-u", createdAt = "2020-01-01T00:00:00.000Z")
        val out = visibleTasks(TaskListView.BACKLOG, listOf(unscheduled), emptyList(), NOW, null, slipMode = false)
        assertEquals(listOf("t-u"), ids(out))
    }

    @Test fun backlogIncludesPastOnlyOverdue() {
        val overdue = mkTask(id = "t-old", createdAt = "2020-01-01T00:00:00.000Z")
        val out = visibleTasks(
            TaskListView.BACKLOG, listOf(overdue),
            listOf(mkBlock(id = "b1", taskId = "t-old", date = todayPlus(-1))), NOW, null, slipMode = false,
        )
        assertEquals(listOf("t-old"), ids(out))
    }

    @Test fun backlogExcludesCreatedTodayEvenIfUnscheduled() {
        val fresh = mkTask(id = "t-fresh", createdAt = iso(NOW))
        val out = visibleTasks(TaskListView.BACKLOG, listOf(fresh), emptyList(), NOW, null, slipMode = false)
        assertEquals(emptyList<String>(), ids(out))
    }

    @Test fun backlogExcludesTodayScheduled() {
        val scheduled = mkTask(id = "t-sched")
        val out = visibleTasks(
            TaskListView.BACKLOG, listOf(scheduled),
            listOf(mkBlock(id = "b1", taskId = "t-sched", date = Clock.todayIso())), NOW, null, slipMode = false,
        )
        assertEquals(emptyList<String>(), ids(out))
    }

    @Test fun backlogExcludesFutureScheduled() {
        val future = mkTask(id = "t-fut")
        val out = visibleTasks(
            TaskListView.BACKLOG, listOf(future),
            listOf(mkBlock(id = "b1", taskId = "t-fut", date = todayPlus(1))), NOW, null, slipMode = false,
        )
        assertEquals(emptyList<String>(), ids(out))
    }

    @Test fun backlogExcludesLaterAndDone() {
        val later = mkTask(id = "t-l", later = true)
        val done = mkTask(id = "t-d", done = true)
        assertEquals(emptyList<String>(), ids(visibleTasks(TaskListView.BACKLOG, listOf(later), emptyList(), NOW, null, slipMode = false)))
        assertEquals(emptyList<String>(), ids(visibleTasks(TaskListView.BACKLOG, listOf(done), emptyList(), NOW, null, slipMode = false)))
    }

    @Test fun backlogPastAndTodayBlockCountsAsTodayNotBacklog() {
        val mixed = mkTask(id = "t-mix")
        val out = visibleTasks(
            TaskListView.BACKLOG, listOf(mixed),
            listOf(mkBlock(id = "b1", taskId = "t-mix", date = todayPlus(-1)), mkBlock(id = "b2", taskId = "t-mix", date = Clock.todayIso())),
            NOW, null, slipMode = false,
        )
        assertEquals(emptyList<String>(), ids(out))
    }

    // --- tag filter ---

    @Test fun filtersByTagCaseInsensitive() {
        val a = mkTask(id = "t-a", name = "a", tags = listOf("deep-work"))
        val b = mkTask(id = "t-b", name = "b", tags = listOf("phone-call"))
        val c = mkTask(id = "t-c", name = "c", tags = listOf("deep-work", "client-x"))
        val out = visibleTasks(TaskListView.ALL, listOf(a, b, c), emptyList(), NOW, null, activeTag = "Deep-Work", slipMode = false)
        assertEquals(listOf("t-a", "t-c"), ids(out).sorted())
    }

    @Test fun andCombinesTagAndArea() {
        val a = mkTask(id = "t-a", lifeArea = "Work", tags = listOf("urgent"))
        val b = mkTask(id = "t-b", lifeArea = "Personal", tags = listOf("urgent"))
        val out = visibleTasks(TaskListView.ALL, listOf(a, b), emptyList(), NOW, "Work", activeTag = "urgent", slipMode = false)
        assertEquals(listOf("t-a"), ids(out))
    }

    @Test fun emptyWhenNoTaskCarriesTag() {
        val a = mkTask(id = "t-a", tags = listOf("foo"))
        val out = visibleTasks(TaskListView.ALL, listOf(a), emptyList(), NOW, null, activeTag = "bar", slipMode = false)
        assertEquals(emptyList<String>(), ids(out))
    }

    @Test fun nilTagIsNoOp() {
        val a = mkTask(id = "t-a", tags = listOf("foo"))
        val out = visibleTasks(TaskListView.ALL, listOf(a), emptyList(), NOW, null, activeTag = null, slipMode = false)
        assertEquals(listOf("t-a"), ids(out))
    }

    // --- isSlipping / daysSinceCreated ---

    @Test fun isSlippingFlagsMoved3Plus() {
        assertTrue(isSlipping(mkTask(moveCount = 3), NOW))
        assertFalse(isSlipping(mkTask(moveCount = 2), NOW))
    }

    @Test fun isSlippingFlagsOlderThan21Days() {
        assertTrue(isSlipping(mkTask(createdAt = "2026-04-01T00:00:00.000Z"), NOW))
    }

    @Test fun isSlippingSkipsDone() {
        assertFalse(isSlipping(mkTask(done = true, moveCount = 9), NOW))
    }

    @Test fun daysSinceCreatedCounts() {
        assertEquals(0, daysSinceCreated(mkTask(createdAt = iso(NOW)), NOW))
        assertEquals(3, daysSinceCreated(mkTask(createdAt = iso(NOW - 3 * 24 * 60 * 60 * 1000L)), NOW))
    }
}
