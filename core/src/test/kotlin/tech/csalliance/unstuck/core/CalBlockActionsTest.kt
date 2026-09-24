package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.CalBlockSheetActions
import tech.csalliance.unstuck.core.logic.SHARED_BLOCK_ID_PREFIX
import tech.csalliance.unstuck.core.logic.blockIsDone
import tech.csalliance.unstuck.core.logic.calBlockSheetActions
import tech.csalliance.unstuck.core.logic.taskAfterSettingRecurrence
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Recurrence

// The calendar Edit-block sheet's task actions (Ahmad 2026-09-24: "Can't
// complete a task from calendar"): which row Mark done / Start focus / Open task
// act on, and when they are offered.
class CalBlockActionsTest {

    private fun block(
        id: String = "b1", taskId: String? = "t1", kind: CalBlockKind? = CalBlockKind.TASK,
        done: Boolean = false, externalEventId: String? = null,
    ) = CalBlock(
        id = id, taskId = taskId, taskName = "Write report", startTime = "09:00", durationMinutes = 45,
        date = "2026-09-24", kind = kind, done = done, externalEventId = externalEventId,
        completedAt = if (done) "2026-09-24T09:40:00.000Z" else null,
    )

    @Test fun aPlainTaskBlockActsOnTheTaskItself() {
        val t = mkTask(id = "t1", name = "Write report")
        val a = calBlockSheetActions(block(), listOf(t))
        assertSame("the task Today shows", t, a.row)
        assertTrue(a.canComplete)
        assertTrue(a.canFocus)
        assertTrue(a.canOpen)
        assertFalse(a.done)
        assertEquals("Mark done", a.completeLabel)
        assertNull(a.assignedTo)
    }

    @Test fun aDoneTaskOffersMarkNotDone() {
        val t = mkTask(id = "t1", done = true, completedAt = "2026-09-24T09:40:00.000Z")
        val a = calBlockSheetActions(block(), listOf(t))
        assertTrue(a.done)
        assertTrue("undo is the same toggle", a.canComplete)
        assertEquals("Mark not done", a.completeLabel)
    }

    @Test fun aSeriesBlockActsOnThatDaysOccurrenceNeverTheTemplate() {
        val tpl = mkTask(id = "tpl", name = "Meds", estimateMin = 10, totalFocused = 300).copy(recurrence = Recurrence.Daily())
        val a = calBlockSheetActions(block(id = "occ1", taskId = "tpl"), listOf(tpl))
        val row = a.row!!
        assertEquals("the occurrence row's id is the block id", "occ1", row.id)
        assertNull("a plain one-day row, not the series", row.recurrence)
        assertEquals("Meds", row.name)
        assertEquals("the block's length", 45, row.estimateMin)
        assertEquals("never the series' lifetime focus", 0, row.totalFocused)
        assertTrue(a.canComplete)
        assertTrue(a.canFocus)
        assertEquals("Mark done", a.completeLabel)
    }

    @Test fun aTickedOccurrenceReadsDoneFromItsBlock() {
        // The template is open (a series has no done of its own); the DAY is ticked.
        val tpl = mkTask(id = "tpl").copy(recurrence = Recurrence.Daily())
        val a = calBlockSheetActions(block(id = "occ1", taskId = "tpl", done = true), listOf(tpl))
        assertTrue(a.done)
        assertEquals("2026-09-24T09:40:00.000Z", a.row!!.completedAt)
        assertEquals("Mark not done", a.completeLabel)
    }

    @Test fun aGoogleEventHasNoTaskActions() {
        val t = mkTask(id = "t1")
        assertEquals(CalBlockSheetActions.NONE, calBlockSheetActions(block(kind = CalBlockKind.EXTERNAL, taskId = null, externalEventId = "g1"), listOf(t)))
        // Legacy rows with no kind: an external event id or a cal- task id still mean Google.
        assertEquals(CalBlockSheetActions.NONE, calBlockSheetActions(block(kind = null, taskId = null, externalEventId = "g1"), listOf(t)))
        assertEquals(CalBlockSheetActions.NONE, calBlockSheetActions(block(kind = null, taskId = "cal-abc"), listOf(t)))
        val none = CalBlockSheetActions.NONE
        assertFalse(none.canComplete)
        assertFalse(none.canFocus)
        assertFalse(none.canOpen)
    }

    @Test fun reservedTimeHasNoTaskActions() {
        assertEquals(CalBlockSheetActions.NONE, calBlockSheetActions(block(kind = CalBlockKind.PLACEHOLDER, taskId = "placeholder"), emptyList()))
    }

    @Test fun aBlockSharedWithMeHasNoTaskActions() {
        // Even if its task id happened to be one of mine.
        val t = mkTask(id = "t1")
        assertEquals(CalBlockSheetActions.NONE, calBlockSheetActions(block(id = SHARED_BLOCK_ID_PREFIX + "b9"), listOf(t)))
    }

    @Test fun aBlockWhoseTaskIsGoneHasNoTaskActions() {
        assertEquals(CalBlockSheetActions.NONE, calBlockSheetActions(block(taskId = "deleted"), listOf(mkTask(id = "t1"))))
    }

    @Test fun aTaskIAssignedOutIsViewOnly() {
        val t = mkTask(id = "t1")
        val a = calBlockSheetActions(block(), listOf(t), assignedOut = mapOf("t1" to "zubair@example.com"))
        assertFalse("its recipient completes it now", a.canComplete)
        assertFalse(a.canFocus)
        assertTrue("it can still be opened", a.canOpen)
        assertEquals("zubair@example.com", a.assignedTo)
    }

    @Test fun anAssignmentOfAnotherTaskChangesNothing() {
        val a = calBlockSheetActions(block(), listOf(mkTask(id = "t1")), assignedOut = mapOf("t2" to "zubair@example.com"))
        assertTrue(a.canComplete)
        assertTrue(a.canFocus)
    }

    // ── blockIsDone: the strike rule of the Day / Week grids and the Month peek
    //    (web lib/occurrences blockIsDone, iOS Occurrences.swift blockIsDone) ──

    @Test fun blockIsDone_aRepeatingDayIsItsOwnBlock() {
        val tpl = mkTask(id = "tpl").copy(recurrence = Recurrence.Daily())
        assertTrue(blockIsDone(block(id = "occ", taskId = "tpl", done = true), tpl))
        assertFalse(blockIsDone(block(id = "occ", taskId = "tpl"), tpl))
        // A series the old path ENDED (template done): its days read their own block.
        assertFalse(
            "an ended series' flag never strikes its days",
            blockIsDone(block(id = "occ", taskId = "tpl"), tpl.copy(done = true)),
        )
    }

    @Test fun blockIsDone_aOneOffFollowsItsTask() {
        assertTrue(blockIsDone(block(), mkTask(id = "t1", done = true)))
        assertFalse(
            "a stale done left on a one-off's block never strikes it",
            blockIsDone(block(done = true), mkTask(id = "t1")),
        )
    }

    @Test fun blockIsDone_noTaskIsNotDone() {
        assertFalse(blockIsDone(block(taskId = "gone", done = true), null))
        assertFalse(blockIsDone(block(kind = CalBlockKind.EXTERNAL, taskId = null, externalEventId = "g1", done = true), null))
    }

    @Test fun anEndedSeriesDayOffersMarkDone() {
        val a = calBlockSheetActions(block(id = "occ", taskId = "tpl"), listOf(mkTask(id = "tpl", done = true).copy(recurrence = Recurrence.Daily())))
        assertEquals("occ", a.row!!.id)
        assertFalse(a.done)
        assertEquals("Mark done", a.completeLabel)
    }

    /** The sheet's done and the grid's strike are one rule. */
    @Test fun theSheetAndTheGridAgree() {
        val tpl = mkTask(id = "tpl", done = true).copy(recurrence = Recurrence.Daily())
        val cases = listOf(
            block(id = "occ-a", taskId = "tpl") to tpl,
            block(id = "occ-b", taskId = "tpl", done = true) to tpl,
            block(id = "occ-c", taskId = "tpl", done = true) to tpl.copy(done = false),
            block(id = "b1", taskId = "t1", done = true) to mkTask(id = "t1"),
            block(id = "b2", taskId = "t2") to mkTask(id = "t2", done = true),
            block(id = "b3", taskId = "t3", done = true) to mkTask(id = "t3", done = true),
        )
        for ((b, t) in cases) assertEquals(b.id, blockIsDone(b, t), calBlockSheetActions(b, listOf(t)).done)
    }

    /**
     * The reported repro: today's day of a repeating task ticked, then its repeat
     * set to Never ([taskAfterSettingRecurrence] carries the tick onto the task;
     * the block keeps done = true), then Mark not done from the calendar. The old
     * grid rule (`block.done || task.done`) kept the block struck; blockIsDone
     * follows the task, like the sheet.
     */
    @Test fun aDayTickedThenRepeatNeverThenMarkNotDone_unstrikesTheBlock() {
        val today = "2026-09-24"
        val tpl = mkTask(id = "tpl", name = "Meds").copy(recurrence = Recurrence.Daily())
        val ticked = block(id = "occ", taskId = "tpl", done = true)
        assertTrue("the ticked day is struck", blockIsDone(ticked, tpl))

        val oneOff = taskAfterSettingRecurrence(tpl, null, listOf(ticked), today, "2026-09-24T10:00:00.000Z")
        assertNull(oneOff.recurrence)
        assertTrue("the tick carries onto the task", oneOff.done)
        assertTrue("still struck, now by its task", blockIsDone(ticked, oneOff))
        val before = calBlockSheetActions(ticked, listOf(oneOff))
        assertEquals("the sheet acts on the task now", "tpl", before.row!!.id)
        assertEquals("Mark not done", before.completeLabel)

        // Mark not done flips the task (AppViewModel.toggleDone); the block keeps its done.
        val reopened = oneOff.copy(done = false, completedAt = null)
        assertTrue("the block row is untouched", ticked.done)
        assertTrue("the old rule kept it struck", ticked.done || reopened.done)
        assertFalse("the grid un-strikes it", blockIsDone(ticked, reopened))
        val after = calBlockSheetActions(ticked, listOf(reopened))
        assertFalse(after.done)
        assertEquals("Mark done", after.completeLabel)
    }
}
