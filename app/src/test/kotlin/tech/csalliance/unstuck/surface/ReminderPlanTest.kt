package tech.csalliance.unstuck.surface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.NotificationLevel
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.ui.notifications.upcomingReminders
import java.time.LocalDateTime
import java.time.ZoneId

// A repeating task keeps each day's tick / skip on the BLOCK; the template's
// `done` never flips. A handled day must arm no alarm and leave the bell's
// Upcoming (parity with iOS ReminderPlanTests, build 81 — audit 2026-09-22 C2).
class ReminderPlanTest {
    private val now = LocalDateTime.of(2026, 5, 21, 8, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun task(id: String, recurrence: Recurrence? = null) =
        TaskItem(id = id, name = id, estimateMin = 25, recurrence = recurrence, createdAt = "2026-05-01T00:00:00.000Z", updatedAt = "2026-05-01T00:00:00.000Z")

    private fun block(id: String, taskId: String, date: String, startTime: String, name: String = taskId, done: Boolean = false, skipped: Boolean = false) =
        CalBlock(id = id, taskId = taskId, taskName = name, startTime = startTime, durationMinutes = 25, date = date, kind = CalBlockKind.TASK, done = done, skipped = skipped)

    private fun plan(blocks: List<CalBlock>, tasks: List<TaskItem>, level: NotificationLevel = NotificationLevel.COACH) =
        ReminderScheduler.planReminders(blocks, tasks, level, globalLead = 10, leadOverride = { null }, now = now)
            .map { "${it.kind.tag}:${it.block.id}" }

    @Test fun aDoneOccurrenceArmsNothingButTheNextDayStillArms() {
        val tpl = task("tpl", Recurrence.Daily())
        val b1 = block("b1", "tpl", "2026-05-21", "20:00", done = true)
        val b2 = block("b2", "tpl", "2026-05-22", "20:00")
        assertEquals(listOf("lead:b2", "atstart:b2", "drifted:b2"), plan(listOf(b1, b2), listOf(tpl)))
    }

    @Test fun aSkippedOccurrenceArmsNothing() {
        val tpl = task("tpl", Recurrence.Daily())
        assertTrue(plan(listOf(block("b1", "tpl", "2026-05-21", "20:00", skipped = true)), listOf(tpl)).isEmpty())
    }

    @Test fun aSkippedOneOffBlockArmsNothing() {
        // carry_to_tomorrow's "tomorrow already has it — skip today" and
        // skip_occurrence can set `skipped` on a one-off task's block too.
        val b = block("b1", "t1", "2026-05-21", "09:00", skipped = true)
        assertTrue(plan(listOf(b), listOf(task("t1")), NotificationLevel.BALANCED).isEmpty())
    }

    @Test fun anOpenBlockStillArmsEveryLevelItsOwed() {
        val b = block("b1", "t1", "2026-05-21", "09:00")
        assertEquals(listOf("lead:b1", "atstart:b1"), plan(listOf(b), listOf(task("t1")), NotificationLevel.BALANCED))
        assertEquals(listOf("lead:b1"), plan(listOf(b), listOf(task("t1")), NotificationLevel.CALM))
    }

    /** The re-sync de-dupe key: a tick or skip changes only the block, so without
     *  done / skipped in it the emission was dropped and the armed alarms stayed. */
    @Test fun theSignatureChangesWhenOnlyTheDaysTickOrSkipFlips() {
        val tpl = task("tpl", Recurrence.Daily())
        val open = block("b1", "tpl", "2026-05-21", "20:00")
        val base = ReminderScheduler.alarmSignature(listOf(open), listOf(tpl))
        assertNotEquals(base, ReminderScheduler.alarmSignature(listOf(open.copy(done = true)), listOf(tpl)))
        assertNotEquals(base, ReminderScheduler.alarmSignature(listOf(open.copy(skipped = true)), listOf(tpl)))
        assertEquals(base, ReminderScheduler.alarmSignature(listOf(open.copy(taskName = "Renamed")), listOf(tpl)))
    }

    @Test fun doneAndSkippedOccurrencesAreNotUpcoming() {
        // A skipped twin at the same (task, time) is listed BEFORE the live block:
        // it must not consume the de-dupe key.
        val tpl = task("tpl", Recurrence.Daily())
        val blocks = listOf(
            block("b1", "tpl", "2026-05-21", "09:00", name = "Done day", done = true),
            block("b2", "tpl", "2026-05-21", "10:00", name = "Skipped day", skipped = true),
            block("b3s", "tpl", "2026-05-21", "11:00", name = "Live", skipped = true),
            block("b3", "tpl", "2026-05-21", "11:00", name = "Live"),
        )
        val up = upcomingReminders(blocks, listOf(tpl), now)
        assertEquals(listOf("Live"), up.map { it.name })
    }
}
