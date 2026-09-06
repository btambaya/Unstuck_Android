package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.Gap
import tech.csalliance.unstuck.core.logic.composeBrief
import tech.csalliance.unstuck.core.logic.probeQuestion
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.TaskItem

// 1:1 with lib/assistant/brief.test.ts (+ BriefTests.swift).
class BriefTest {

    private val TODAY = "2026-08-29"
    private var seq = 0

    // Local construction (system zone) — the brief must be DST-proof.
    private fun at(h: Int, min: Int = 0): Long = localMillis(2026, 8, 29, h, min)

    private fun task(id: String? = null, name: String = "A task", done: Boolean = false, recurrence: Recurrence? = null) = TaskItem(
        id = id ?: "id${++seq}", name = name, estimateMin = 25, totalFocused = 0, done = done, recurrence = recurrence,
        createdAt = "2026-08-01T10:00:00Z", updatedAt = "2026-08-01T10:00:00Z",
    )

    private fun block(
        startTime: String, taskId: String? = "t", taskName: String = "A task",
        done: Boolean = false, skipped: Boolean = false,
    ) = CalBlock(
        id = "id${++seq}", taskId = taskId, taskName = taskName, startTime = startTime, durationMinutes = 30,
        date = TODAY, done = done, skipped = skipped,
    )

    private fun brief(tasks: List<TaskItem>, blocks: List<CalBlock>, now: Long, usable: Int? = null): String =
        composeBrief(tasks = tasks, blocks = blocks, todayIso = TODAY, now = now, usableMinutes = usable)

    @Test fun `reads exactly like the spec example, no greeting prefix`() {
        val anchor = task(id = "w", name = "Write the project update")
        val blocks = listOf(block("11:00", taskId = "w"), block("14:00"), block("16:00"))
        assertEquals(
            "Three things scheduled today — ‘Write the project update’ at 11:00 is the anchor.",
            brief(listOf(anchor), blocks, at(9)),
        )
    }

    @Test fun `handles the singular day`() {
        val t = task(id = "w", name = "Deep work")
        assertEquals(
            "One thing scheduled today — ‘Deep work’ at 10:00 is the anchor.",
            brief(listOf(t), listOf(block("10:00", taskId = "w")), at(9)),
        )
    }

    @Test fun `anchors on the first block at-or-after now, skipping the morning behind us`() {
        val t = task(id = "w", name = "Client call")
        val blocks = listOf(block("09:00"), block("14:00", taskId = "w"))
        assertEquals("Two things scheduled today — ‘Client call’ at 14:00 is the anchor.", brief(listOf(t), blocks, at(12)))
    }

    @Test fun `a block starting exactly now still anchors (at-or-after)`() {
        val t = task(id = "w", name = "Standup")
        assertTrue(brief(listOf(t), listOf(block("11:00", taskId = "w")), at(11)).contains("‘Standup’ at 11:00"))
    }

    @Test fun `when everything is behind us, the first block of the day anchors`() {
        val t = task(id = "w", name = "Morning pages")
        val blocks = listOf(block("09:00", taskId = "w"), block("11:00"))
        assertTrue(brief(listOf(t), blocks, at(18)).contains("‘Morning pages’ at 09:00 is the anchor"))
    }

    @Test fun `done and skipped blocks are not scheduled today`() {
        val t = task(id = "w", name = "The one live thing")
        val blocks = listOf(block("09:00", done = true), block("10:00", skipped = true), block("15:00", taskId = "w"))
        assertEquals("One thing scheduled today — ‘The one live thing’ at 15:00 is the anchor.", brief(listOf(t), blocks, at(9)))
    }

    @Test fun `an untimed anchor drops the at clause`() {
        val t = task(id = "w", name = "Sometime today")
        assertEquals("One thing scheduled today — ‘Sometime today’ is the anchor.", brief(listOf(t), listOf(block("", taskId = "w")), at(9)))
    }

    @Test fun `prefers the task's current name over the block's stale copy, falling back when unlinked`() {
        val renamed = task(id = "w", name = "Fresh name")
        assertTrue(brief(listOf(renamed), listOf(block("10:00", taskId = "w", taskName = "Stale name")), at(9)).contains("‘Fresh name’"))
        assertTrue(brief(emptyList(), listOf(block("10:00", taskId = "ghost", taskName = "Orphan block")), at(9)).contains("‘Orphan block’"))
    }

    // ── empty calendar ──

    @Test fun `offers the open pile`() {
        val open = (0 until 7).map { task() }
        assertEquals("Nothing on the calendar today — 7 open tasks if you want to pull one in.", brief(open, emptyList(), at(9)))
    }

    @Test fun `speaks singular for a single open task`() {
        assertEquals("Nothing on the calendar today — 1 open task if you want to pull one in.", brief(listOf(task()), emptyList(), at(9)))
    }

    @Test fun `done tasks and recurring templates are not open`() {
        val tasks = listOf(task(), task(done = true), task(recurrence = Recurrence.Weekly(daysOfWeek = listOf(1))))
        assertTrue(brief(tasks, emptyList(), at(9)).contains("1 open task"))
    }

    @Test fun `a truly clear day gets the calm line`() {
        assertEquals("A clear day. Add what’s on your mind below.", brief(emptyList(), emptyList(), at(9)))
    }

    // ── usable minutes — the runway sentence ──

    private val deep get() = task(id = "w", name = "Deep work")
    private val eleven get() = listOf(block("11:00", taskId = "w"))

    @Test fun `appends, rounded to the nearest 5`() {
        assertEquals(
            "One thing scheduled today — ‘Deep work’ at 11:00 is the anchor. About 90 usable minutes before it.",
            brief(listOf(deep), eleven, at(9), 92),
        )
        assertTrue(brief(listOf(deep), eleven, at(9), 88).contains("About 90 usable minutes"))
        assertTrue(brief(listOf(deep), eleven, at(9), 87).contains("About 85 usable minutes"))
        assertTrue(brief(listOf(deep), eleven, at(9), 15).contains("About 15 usable minutes"))
    }

    @Test fun `suppressed under 15 minutes - too small to be a runway`() {
        assertFalse(brief(listOf(deep), eleven, at(9), 14).contains("usable"))
    }

    @Test fun `suppressed when the anchor is not strictly ahead of now`() {
        assertFalse(brief(listOf(deep), eleven, at(11), 90).contains("usable"))   // exactly now
        assertFalse(brief(listOf(deep), eleven, at(12), 90).contains("usable"))   // behind us
        assertFalse(brief(listOf(deep), listOf(block("", taskId = "w")), at(9), 90).contains("usable")) // untimed
    }

    @Test fun `suppressed when absent`() {
        assertFalse(brief(listOf(deep), eleven, at(9)).contains("usable"))
        assertFalse(brief(listOf(deep), eleven, at(9), null).contains("usable"))
    }

    // ── probeQuestion — the gentle pattern-gap probe ──

    private fun gap(taskName: String = "Gym", dow: Int = 3, dueDate: String = "2026-09-02") = Gap(
        taskId = "gym", taskName = taskName, dow = dow, time = "07:00", weeksSeen = 4,
        label = "Gym most Wednesdays at 07:00 (4 of the last 4 weeks)", dueDate = dueDate,
    )

    @Test fun `probe reads exactly like the spec example - British Sept, no leading zero`() {
        assertEquals("You usually do ‘Gym’ on Wednesdays — still on for Wednesday 2 Sept?", probeQuestion(gap()))
    }

    @Test fun `probe formats other weekdays and months from the due date, locally`() {
        assertEquals(
            "You usually do ‘Yoga’ on Mondays — still on for Monday 31 Aug?",
            probeQuestion(gap(taskName = "Yoga", dow = 1, dueDate = "2026-08-31")),
        )
    }
}
