package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.Pattern
import tech.csalliance.unstuck.core.logic.derivePatterns
import tech.csalliance.unstuck.core.logic.patternGaps
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.TaskItem
import java.time.LocalDate

// 1:1 with lib/assistant/patterns.test.ts (+ PatternsTests.swift).
//
// Saturday 29 Aug 2026 → current week starts Monday 2026-08-24; the history
// window is [2026-07-25, 2026-08-24). Recent Wednesdays (dow 3), newest
// first: 08-19, 08-12, 08-05, 07-29 (in window) and 07-22 (too old).
class PatternsTest {

    private val TODAY = "2026-08-29"
    private val WEDS = listOf("2026-08-19", "2026-08-12", "2026-08-05", "2026-07-29")
    private var seq = 0

    private fun task(id: String = "gym", name: String = "Gym") = TaskItem(
        id = id, name = name, estimateMin = 60, totalFocused = 0, done = false,
        createdAt = "2026-07-01T10:00:00Z", updatedAt = "2026-07-01T10:00:00Z",
    )

    private fun block(date: String, taskId: String? = "gym", startTime: String = "07:00", done: Boolean = false) = CalBlock(
        id = "id${++seq}", taskId = taskId, taskName = "Gym", startTime = startTime, durationMinutes = 60, date = date, done = done,
    )

    // ── derivePatterns — same task, same weekday, ≥3 distinct history weeks ──

    @Test fun `three distinct weeks make a pattern, two are just noise`() {
        val three = derivePatterns(listOf(task()), WEDS.take(3).map { block(it) }, TODAY)
        assertEquals(1, three.size)
        assertEquals("gym", three[0].taskId)
        assertEquals("Gym", three[0].taskName)
        assertEquals(3, three[0].dow)
        assertEquals("07:00", three[0].time)
        assertEquals(3, three[0].weeksSeen)
        assertEquals("Gym most Wednesdays at 07:00 (3 of the last 4 weeks)", three[0].label)

        assertEquals(emptyList<Pattern>(), derivePatterns(listOf(task()), WEDS.take(2).map { block(it) }, TODAY))
    }

    @Test fun `a block with an unparseable date is skipped, never thrown`() {
        // An external / corrupt row must not take down the card: java.time throws
        // on a date that does not exist, where the web's Date silently NaNs.
        val blocks = WEDS.map { block(it) } + listOf(block("2026-02-30"), block("not-a-date"))
        val p = derivePatterns(listOf(task()), blocks, TODAY)
        assertEquals(1, p.size)
        assertEquals(4, p[0].weeksSeen)
    }

    @Test fun `four weeks read exactly like the spec example`() {
        val p = derivePatterns(listOf(task()), WEDS.map { block(it) }, TODAY)
        assertEquals("Gym most Wednesdays at 07:00 (4 of the last 4 weeks)", p[0].label)
        assertEquals(4, p[0].weeksSeen)
    }

    @Test fun `current-week blocks are the plan, not history - they never count`() {
        // Two history Wednesdays + this week's Wednesday (26 Aug) = still only 2.
        val blocks = WEDS.take(2).map { block(it) } + block("2026-08-26")
        assertEquals(emptyList<Pattern>(), derivePatterns(listOf(task()), blocks, TODAY))
    }

    @Test fun `blocks older than 35 days fall out of the window`() {
        // 22 Jul is a Wednesday but predates today-35 (25 Jul).
        val blocks = WEDS.take(2).map { block(it) } + block("2026-07-22")
        assertEquals(emptyList<Pattern>(), derivePatterns(listOf(task()), blocks, TODAY))
        // …whereas 29 Jul (in window) completes the trio.
        val ok = WEDS.take(2).map { block(it) } + block("2026-07-29")
        assertEquals(1, derivePatterns(listOf(task()), ok, TODAY).size)
    }

    @Test fun `done occurrences still count - they are evidence the habit happened`() {
        val blocks = WEDS.take(3).map { block(it, done = true) }
        assertEquals(1, derivePatterns(listOf(task()), blocks, TODAY).size)
    }

    @Test fun `two blocks in the same week count as one week`() {
        val blocks = listOf(block("2026-08-19"), block("2026-08-19"), block("2026-08-12"))
        assertEquals(emptyList<Pattern>(), derivePatterns(listOf(task()), blocks, TODAY))
    }

    @Test fun `the most common start time wins the label`() {
        val blocks = listOf(
            block("2026-08-19", startTime = "07:00"),
            block("2026-08-12", startTime = "08:30"),
            block("2026-08-05", startTime = "07:00"),
        )
        val p = derivePatterns(listOf(task()), blocks, TODAY)[0]
        assertEquals("07:00", p.time)
        assertTrue(p.label.contains("at 07:00"))
    }

    @Test fun `untimed occurrences yield a null time and a label without one`() {
        val blocks = WEDS.take(3).map { block(it, startTime = "") }
        val p = derivePatterns(listOf(task()), blocks, TODAY)[0]
        assertNull(p.time)
        assertEquals("Gym most Wednesdays (3 of the last 4 weeks)", p.label)
    }

    @Test fun `blocks without a resolvable task (or with no taskId) are ignored`() {
        val orphan = WEDS.take(3).map { block(it, taskId = "ghost") }
        assertEquals(emptyList<Pattern>(), derivePatterns(listOf(task()), orphan, TODAY))
        val untasked = WEDS.take(3).map { block(it, taskId = null) }
        assertEquals(emptyList<Pattern>(), derivePatterns(listOf(task()), untasked, TODAY))
    }

    @Test fun `separate weekdays build separate patterns for the same task`() {
        val mons = listOf("2026-08-17", "2026-08-10", "2026-08-03") // Mondays, dow 1
        val blocks = WEDS.take(3).map { block(it) } + mons.map { block(it, startTime = "18:00") }
        val pats = derivePatterns(listOf(task()), blocks, TODAY)
        assertEquals(listOf(1, 3), pats.map { it.dow }.sorted())
    }

    // ── patternGaps — the next occurrence with nothing on it ──

    private val pattern = Pattern(
        taskId = "gym", taskName = "Gym", dow = 3, time = "07:00", weeksSeen = 4,
        label = "Gym most Wednesdays at 07:00 (4 of the last 4 weeks)",
    )

    @Test fun `rolls to next week when the weekday already passed this week`() {
        // Saturday 29 Aug: this week's Wednesday (26th) is behind us → 2 Sept.
        val gaps = patternGaps(listOf(pattern), emptyList(), TODAY)
        assertEquals(1, gaps.size)
        assertEquals("2026-09-02", gaps[0].dueDate)
        assertEquals("gym", gaps[0].taskId)
        assertEquals("Gym", gaps[0].taskName)
        assertEquals(3, gaps[0].dow)
    }

    @Test fun `lands this week while the weekday is still ahead - and today counts`() {
        assertEquals("2026-08-26", patternGaps(listOf(pattern), emptyList(), "2026-08-24")[0].dueDate) // Monday
        assertEquals("2026-08-26", patternGaps(listOf(pattern), emptyList(), "2026-08-26")[0].dueDate) // Wednesday itself
    }

    @Test fun `a non-done block for the task on the due date suppresses the gap`() {
        assertEquals(emptyList<Any>(), patternGaps(listOf(pattern), listOf(block("2026-09-02")), TODAY))
    }

    @Test fun `a done block does NOT suppress - nothing live is scheduled`() {
        assertEquals(1, patternGaps(listOf(pattern), listOf(block("2026-09-02", done = true)), TODAY).size)
    }

    @Test fun `a block for a different task on the due date does not suppress`() {
        assertEquals(1, patternGaps(listOf(pattern), listOf(block("2026-09-02", taskId = "other")), TODAY).size)
    }

    @Test fun `the due date really falls on the pattern weekday (local parsing, no UTC drift)`() {
        for (dow in 0..6) {
            val gap = patternGaps(listOf(pattern.copy(dow = dow)), emptyList(), TODAY)[0]
            assertEquals(dow, LocalDate.parse(gap.dueDate).dayOfWeek.value % 7)
            assertTrue(gap.dueDate >= TODAY)
        }
    }

    @Test fun `feeds straight from derivePatterns end-to-end`() {
        val history = WEDS.map { block(it, done = true) }
        val pats = derivePatterns(listOf(task()), history, TODAY)
        val gaps = patternGaps(pats, history, TODAY)
        assertEquals(1, gaps.size)
        assertEquals("2026-09-02", gaps[0].dueDate)
        assertEquals("Gym most Wednesdays at 07:00 (4 of the last 4 weeks)", gaps[0].label)
    }
}
