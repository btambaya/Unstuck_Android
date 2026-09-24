package tech.csalliance.unstuck.ui.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import tech.csalliance.unstuck.core.logic.occurrenceBlockFor
import tech.csalliance.unstuck.core.logic.taskForBlock
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.ClockMode

/**
 * The task screen's Schedule cell. Opened on ONE DAY of a repeating task it
 * must show that day: it read "Unscheduled" (the row id is a block id, and the
 * lookup filtered blocks by task_id), and iOS showed the series' oldest block.
 */
class TaskDetailScheduleLabelTest {

    private val today = "2026-09-23"

    private fun template(later: Boolean? = null) = TaskItem(
        id = "tpl", name = "Gym", estimateMin = 25, recurrence = Recurrence.Daily(), later = later,
        createdAt = "2026-05-21T10:00:00.000Z", updatedAt = "2026-05-21T10:00:00.000Z",
    )

    private fun block(id: String, date: String, time: String, taskId: String = "tpl", done: Boolean = false, skipped: Boolean = false) =
        CalBlock(id = id, taskId = taskId, taskName = "Gym", startTime = time, durationMinutes = 25, date = date, kind = CalBlockKind.TASK, done = done, skipped = skipped)

    // Weeks of history (done + skipped) before today, then the live days.
    private val series = listOf(
        block("b-0901", "2026-09-01", "07:00", done = true),
        block("b-0910", "2026-09-10", "07:00", skipped = true),
        block("b-0923", "2026-09-23", "08:30"),
        block("b-0925", "2026-09-25", "08:30"),
    )

    /** The label for the row the Detail route resolves for [blockId] (MainScaffold). */
    private fun labelForDay(blockId: String, tpl: TaskItem = template()): String {
        val tasks = listOf(tpl)
        val row = taskForBlock(series.first { it.id == blockId }, tasks)
        assertNotNull(row)
        val occ = occurrenceBlockFor(row!!.id, tasks, series)
        assertNotNull("the row is recognised as one day of the series", occ)
        return scheduleCellText(row, occ, series, today, ClockMode.H24)
    }

    @Test
    fun oneDayShowsThatDayNotTheSeriesOldest() {
        assertEquals("09-25 08:30", labelForDay("b-0925"))
        assertEquals("09-23 08:30", labelForDay("b-0923"))
    }

    /** A past day (e.g. opened from history / the overdue row) is still its own day. */
    @Test
    fun aPastDayShowsItsOwnDate() {
        assertEquals("09-10 07:00", labelForDay("b-0910"))
    }

    /** taskForBlock copies the template's `later`; a day with a block is on its date. */
    @Test
    fun aDayOfASeriesParkedInLaterStillShowsItsDate() {
        assertEquals("09-25 08:30", labelForDay("b-0925", template(later = true)))
    }

    /** The series itself (Recurring tab) shows its next live day, not weeks-old history. */
    @Test
    fun theSeriesShowsItsNextLiveDay() {
        assertEquals("09-23 08:30", scheduleCellText(template(), null, series, today, ClockMode.H24))
    }

    @Test
    fun aPlainTaskKeepsItsEarliestBlock() {
        val t = template().copy(id = "t1", recurrence = null)
        val blocks = listOf(block("x2", "2026-09-26", "10:00", taskId = "t1"), block("x1", "2026-09-24", "09:15", taskId = "t1"))
        assertEquals("09-24 09:15", scheduleCellText(t, null, blocks + series, today, ClockMode.H24))
        assertEquals("Unscheduled", scheduleCellText(t, null, series, today, ClockMode.H24))
        assertEquals("Later", scheduleCellText(t.copy(later = true), null, blocks, today, ClockMode.H24))
    }
}
