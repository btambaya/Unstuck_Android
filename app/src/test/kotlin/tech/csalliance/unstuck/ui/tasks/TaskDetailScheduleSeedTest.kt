package tech.csalliance.unstuck.ui.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.ChosenDateAction
import tech.csalliance.unstuck.core.logic.IsoDate
import tech.csalliance.unstuck.core.logic.recurrenceAnchor
import tech.csalliance.unstuck.core.logic.recurrenceChosenDateAction
import tech.csalliance.unstuck.core.logic.regenerateForTask
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Time

/**
 * Where the task sheet's Schedule dialogs open on a repeating task (iOS
 * TaskEditor.openSchedule, audit 2026-09-22 C7): the series' next occurrence at
 * the series' own time, so OK without changes re-plans nothing.
 */
class TaskDetailScheduleSeedTest {

    private val today = "2026-09-23"
    private fun day(n: Int) = IsoDate.addDays(today, n)

    private fun template(recurrence: Recurrence? = Recurrence.Daily()) = TaskItem(
        id = "tpl", name = "Gym", estimateMin = 25, recurrence = recurrence,
        createdAt = "2026-05-21T10:00:00.000Z", updatedAt = "2026-05-21T10:00:00.000Z",
    )

    private fun occ(id: String, date: String, time: String, done: Boolean = false, skipped: Boolean = false) =
        CalBlock(id = id, taskId = "tpl", taskName = "Gym", startTime = time, durationMinutes = 25, date = date, kind = CalBlockKind.TASK, done = done, skipped = skipped)

    /** Every write AppViewModel.scheduleTaskNow makes for a series picked on
     *  [date] at [time]: the regen plan, the chosen day's fix-up, the move count. */
    private fun writesOnOk(task: TaskItem, blocks: List<CalBlock>, date: String, time: String): List<String> {
        val p = date.split("-").map { it.toInt() }
        val plan = regenerateForTask(task, task.recurrence, blocks, today, time, Time.civil(p[0], p[1], p[2]))
        val action = recurrenceChosenDateAction(blocks, plan, date, time)
        val anchor = recurrenceAnchor(task.id, blocks, today)
        return plan.toDelete.map { "delete $it" } +
            plan.toUpsert.map { "mint ${it.date} ${it.startTime}" } +
            listOfNotNull(
                (action as? ChosenDateAction.Retime)?.let { "retime ${it.block.id}" },
                "mint chosen day".takeIf { action == ChosenDateAction.Mint },
                "moveCount".takeIf { anchor != null && (anchor.date != date || anchor.startTime != time) },
            )
    }

    @Test fun `a series opens on its next occurrence at its own time, and OK changes nothing`() {
        val t = template()
        val blocks = (30 downTo 1).map { occ("h$it", day(-it), "06:30", done = true) } +
            (0..55).map { occ("u$it", day(it), "07:00") }

        val seed = seriesScheduleSeed(t, blocks, today)!!
        assertEquals(ScheduleSeed(today, "07:00"), seed)
        assertEquals(emptyList<String>(), writesOnOk(t, blocks, seed.date, seed.startTime!!))

        // The old seed — today at the current minute — rebuilt the whole series.
        val old = writesOnOk(t, blocks, today, "14:37")
        assertTrue(old.containsAll(listOf("delete u1", "delete u55", "retime u0", "moveCount")))
    }

    @Test fun `a finished today opens on the next occurrence`() {
        val t = template()
        val blocks = listOf(occ("u0", today, "07:00", done = true)) + (1..55).map { occ("u$it", day(it), "07:00") }

        val seed = seriesScheduleSeed(t, blocks, today)!!
        assertEquals(ScheduleSeed(day(1), "07:00"), seed)
        // Only the horizon's tail is new; nothing is deleted, retimed or counted.
        assertEquals(listOf("mint ${day(56)} 07:00"), writesOnOk(t, blocks, seed.date, seed.startTime!!))
    }

    @Test fun `a hand-moved next occurrence keeps the series time, not the moved one`() {
        val t = template()
        val blocks = listOf(occ("u1", day(1), "16:00")) + (2..55).map { occ("u$it", day(it), "07:00") }

        assertEquals(ScheduleSeed(day(1), "07:00"), seriesScheduleSeed(t, blocks, today))
    }

    @Test fun `a lapsed series opens today at the time it last ran`() {
        val t = template(Recurrence.Weekly(listOf(1, 3)))
        val blocks = (90 downTo 35 step 7).map { occ("h$it", day(-it), "08:15", done = it % 2 == 0) }

        assertEquals(ScheduleSeed(today, "08:15"), seriesScheduleSeed(t, blocks, today))
    }

    @Test fun `a series with no timed block opens today at the current time`() {
        val t = template()
        assertEquals(ScheduleSeed(today, null), seriesScheduleSeed(t, emptyList(), today))
        assertEquals(ScheduleSeed(today, null), seriesScheduleSeed(t, listOf(occ("x", day(3), "")), today))
    }

    @Test fun `a one-off keeps opening on today and now`() {
        assertNull(seriesScheduleSeed(template(recurrence = null), listOf(occ("b", day(2), "10:00")), today))
    }
}
