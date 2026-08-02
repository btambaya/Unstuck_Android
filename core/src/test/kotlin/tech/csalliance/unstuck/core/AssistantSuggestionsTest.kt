package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.buildSuggestions
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.TaskItem

// 1:1 with lib/assistant/suggestions.test.ts. Every chip must derive from the
// user's real data and only appear when applicable — the panel never lies.
class AssistantSuggestionsTest {

    private val today = "2026-08-02"
    private var seq = 0

    private fun task(
        name: String = "A task",
        id: String? = null,
        estimateMin: Int = 25,
        done: Boolean = false,
        later: Boolean? = null,
        lifeArea: String? = null,
        firstPhysicalAction: String? = null,
        moveCount: Int? = null,
        recurrence: Recurrence? = null,
    ) = TaskItem(
        id = id ?: "gen${seq++}", name = name, estimateMin = estimateMin, done = done,
        later = later, lifeArea = lifeArea, firstPhysicalAction = firstPhysicalAction,
        moveCount = moveCount, recurrence = recurrence,
        createdAt = "2026-08-01T10:00:00Z", updatedAt = "2026-08-01T10:00:00Z",
    )

    private fun block(taskId: String? = "t", date: String = today, done: Boolean = false) = CalBlock(
        id = "b${seq++}", taskId = taskId, taskName = "A task",
        startTime = "10:00", durationMinutes = 30, date = date, done = done,
    )

    private fun list(name: String, archived: Boolean? = null) = ItemCollection(
        id = "c${seq++}", name = name, color = "indigo", items = emptyList(), sortOrder = 0,
        archived = archived,
    )

    @Test fun `empty account - no chips at all (the panel never lies)`() {
        val g = buildSuggestions(emptyList(), emptyList(), emptyList(), today)
        assertEquals(emptyList<Any>(), g.gettingStarted)
        assertEquals(emptyList<Any>(), g.planAndSchedule)
        assertEquals(emptyList<Any>(), g.refine)
        assertTrue(g.isEmpty)
    }

    @Test fun `open tasks light up next - an empty day offers to block it out`() {
        val g = buildSuggestions(listOf(task()), emptyList(), emptyList(), today)
        assertEquals(listOf("What should I work on next?"), g.gettingStarted.map { it.label })
        assertTrue(g.planAndSchedule.map { it.label }.contains("Block out my day"))
    }

    @Test fun `a loaded today earns the overwhelm and realistic chips`() {
        val t = task(id = "t1")
        val blocks = listOf(block(taskId = "t1"), block(taskId = "t1"), block(taskId = "t1"))
        val labels = buildSuggestions(listOf(t), blocks, emptyList(), today).gettingStarted.map { it.label }
        assertTrue(labels.contains("I’m overwhelmed"))
        assertTrue(labels.contains("What’s realistic today?"))
        assertTrue(labels.contains("What should I work on next?"))
    }

    @Test fun `break-down targets the BIGGEST chunky task lacking a first action, with its real name`() {
        val small = task(name = "Quick email", estimateMin = 15)
        val big = task(name = "Write the quarterly investor report", estimateMin = 90)
        val decomposed = task(name = "Prep deck", estimateMin = 120, firstPhysicalAction = "Open slides")
        val g = buildSuggestions(listOf(small, big, decomposed), emptyList(), emptyList(), today)
        val chip = g.refine.firstOrNull { it.label.startsWith("Break down") }
        assertNotNull(chip)
        assertTrue(chip!!.label.contains("Write the quarterly"))
        assertTrue(chip.message.contains("\"Write the quarterly investor report\""))
    }

    @Test fun `archived lists never surface`() {
        val g = buildSuggestions(emptyList(), emptyList(), listOf(list("Groceries", archived = true)), today)
        assertEquals(emptyList<Any>(), g.refine)
    }

    @Test fun `recurring templates and deferred tasks never drive break-down`() {
        val template = task(name = "Weekly review", estimateMin = 60, recurrence = Recurrence.Weekly(listOf(1)))
        val later = task(name = "Someday thing", estimateMin = 90, later = true)
        val g = buildSuggestions(listOf(template, later), emptyList(), emptyList(), today)
        assertNull(g.refine.firstOrNull { it.label.startsWith("Break down") })
    }

    @Test fun `long names truncate in the label but stay full in the message`() {
        val t = task(name = "Reorganize the entire garage storage system before winter", estimateMin = 60)
        val chip = buildSuggestions(listOf(t), emptyList(), emptyList(), today)
            .refine.first { it.label.startsWith("Break down") }
        assertTrue(chip.label.length < 45)
        assertTrue(chip.label.contains("…"))
        assertTrue(chip.message.contains("Reorganize the entire garage storage system before winter"))
    }

    @Test fun `unscheduled work becomes a one-tap scheduling action, named for the count`() {
        val one = buildSuggestions(listOf(task(name = "Call the bank")), emptyList(), emptyList(), today)
        assertEquals("Find time for “Call the bank”", one.planAndSchedule[0].label)
        assertTrue(one.planAndSchedule[0].message.contains("schedule it"))

        val many = buildSuggestions(listOf(task(), task(), task()), emptyList(), emptyList(), today)
        assertEquals("Schedule my 3 unscheduled tasks", many.planAndSchedule[0].label)
    }

    @Test fun `already-scheduled tasks are not offered for scheduling again`() {
        val t = task(id = "sched1")
        val g = buildSuggestions(listOf(t), listOf(block(taskId = "sched1")), emptyList(), today)
        assertTrue(!g.planAndSchedule.joinToString { it.label }.contains("unscheduled"))
        assertTrue(g.planAndSchedule.map { it.label }.contains("Move today’s leftovers to tomorrow"))
    }

    @Test fun `quiet-weekend planning needs at least two light personal or home tasks`() {
        val heavy = listOf(
            task(lifeArea = "Work", estimateMin = 90),
            task(lifeArea = "Work", estimateMin = 60),
        )
        assertTrue(
            !buildSuggestions(heavy, emptyList(), emptyList(), today)
                .planAndSchedule.map { it.label }.contains("Plan a quiet weekend"),
        )

        val light = listOf(
            task(lifeArea = "Home", estimateMin = 30),
            task(lifeArea = "Personal", estimateMin = 20),
        )
        val g = buildSuggestions(light, emptyList(), emptyList(), today)
        assertTrue(g.planAndSchedule.map { it.label }.contains("Plan a quiet weekend"))
        // Real weekend dates, not vague prose — 2026-08-02 is a Sunday, so the
        // coming Saturday is the 8th.
        val msg = g.planAndSchedule.first { it.label == "Plan a quiet weekend" }.message
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2} and \\d{4}-\\d{2}-\\d{2}").containsMatchIn(msg))
        assertTrue(msg.contains("2026-08-08 and 2026-08-09"))
    }

    @Test fun `on a Saturday the weekend starts today`() {
        val light = listOf(
            task(lifeArea = "Home", estimateMin = 30),
            task(lifeArea = "Health", estimateMin = 20),
        )
        // 2026-08-08 is a Saturday.
        val msg = buildSuggestions(light, emptyList(), emptyList(), "2026-08-08")
            .planAndSchedule.first { it.label == "Plan a quiet weekend" }.message
        assertTrue(msg.contains("2026-08-08 and 2026-08-09"))
    }

    @Test fun `bulk first-step refinement appears only with 2 or more stepless tasks`() {
        val single = buildSuggestions(listOf(task(estimateMin = 20)), emptyList(), emptyList(), today)
        assertTrue(!single.refine.joinToString { it.label }.contains("first steps"))

        val g = buildSuggestions(listOf(task(estimateMin = 20), task(estimateMin = 30)), emptyList(), emptyList(), today)
        assertTrue(g.refine.map { it.label }.contains("Add first steps to 2 tasks"))
    }

    @Test fun `the Later pile is offered for triage with its real count`() {
        val later = listOf(task(later = true), task(later = true), task(later = true))
        assertTrue(
            buildSuggestions(later, emptyList(), emptyList(), today)
                .refine.map { it.label }.contains("Tidy my Later pile (3)"),
        )
    }

    @Test fun `a list is offered by its real name (groceries-like or the first list)`() {
        val g1 = buildSuggestions(emptyList(), emptyList(), listOf(list("Groceries")), today)
        assertTrue(g1.refine.map { it.label }.contains("Add to Groceries"))
        assertTrue(g1.refine[0].message.contains("\"Groceries\""))

        val g2 = buildSuggestions(emptyList(), emptyList(), listOf(list("Lisbon trip")), today)
        assertTrue(g2.refine.map { it.label }.contains("Add to “Lisbon trip”"))
    }

    @Test fun `slip radar surfaces only when something actually slipped`() {
        assertTrue(
            !buildSuggestions(listOf(task()), emptyList(), emptyList(), today)
                .refine.map { it.label }.contains("What keeps slipping?"),
        )
        assertTrue(
            buildSuggestions(listOf(task(moveCount = 3)), emptyList(), emptyList(), today)
                .refine.map { it.label }.contains("What keeps slipping?"),
        )
    }

    @Test fun `slip radar - an abandoned past block for an OPEN task counts, a done one does not`() {
        val t = task(id = "open1")
        val left = block(taskId = "open1", date = "2026-07-30")
        assertTrue(
            buildSuggestions(listOf(t), listOf(left), emptyList(), today)
                .refine.map { it.label }.contains("What keeps slipping?"),
        )
        val done = block(taskId = "open1", date = "2026-07-30", done = true)
        assertTrue(
            !buildSuggestions(listOf(t), listOf(done), emptyList(), today)
                .refine.map { it.label }.contains("What keeps slipping?"),
        )
    }
}
