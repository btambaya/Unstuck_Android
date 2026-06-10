package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.isTemplate
import tech.csalliance.unstuck.core.logic.occurrenceBlockFor
import tech.csalliance.unstuck.core.logic.projectOccurrences
import tech.csalliance.unstuck.core.logic.taskForBlock
import tech.csalliance.unstuck.core.model.Recurrence

// Parity with lib/occurrences.test.ts.
class OccurrencesTest {
    private val template = mkTask(id = "t1", name = "Water plants", tags = listOf("home"), lifeArea = "Personal")
        .copy(recurrence = Recurrence.Daily())

    @Test fun templateDetection() {
        assertTrue(isTemplate(template))
        assertFalse(isTemplate(mkTask(id = "t2")))
    }

    @Test fun projectsOneRowPerBlockWithTemplateFields() {
        val blocks = listOf(
            mkBlock(id = "b1", taskId = "t1", date = "2026-06-10"),
            mkBlock(id = "b2", taskId = "t1", date = "2026-06-11"),
        )
        val out = projectOccurrences(listOf(template), blocks, "2026-06-10")
        assertEquals(listOf("b1", "b2"), out.map { it.id })
        assertEquals("Water plants", out[0].name)
        assertEquals(listOf("home"), out[0].tags)
        assertNull(out[0].recurrence)
    }

    @Test fun takesDoneAndEstimateFromBlock() {
        val b = mkBlock(id = "b1", taskId = "t1", date = "2026-06-10")
            .copy(done = true, completedAt = "2026-06-10T10:00:00.000Z", durationMinutes = 40)
        val occ = projectOccurrences(listOf(template), listOf(b), "2026-06-10")[0]
        assertTrue(occ.done)
        assertEquals(40, occ.estimateMin)
    }

    @Test fun excludesSkippedAndPast() {
        val blocks = listOf(
            mkBlock(id = "past", taskId = "t1", date = "2026-06-09"),
            mkBlock(id = "skip", taskId = "t1", date = "2026-06-10").copy(skipped = true),
            mkBlock(id = "ok", taskId = "t1", date = "2026-06-10"),
        )
        assertEquals(listOf("ok"), projectOccurrences(listOf(template), blocks, "2026-06-10").map { it.id })
    }

    @Test fun occurrenceBlockForResolvesOnlyTemplateBlocks() {
        val tplBlock = mkBlock(id = "b1", taskId = "t1")
        val normal = mkTask(id = "t2")
        val normalBlock = mkBlock(id = "b2", taskId = "t2")
        assertEquals("b1", occurrenceBlockFor("b1", listOf(template, normal), listOf(tplBlock, normalBlock))?.id)
        assertNull(occurrenceBlockFor("b2", listOf(template, normal), listOf(tplBlock, normalBlock)))
    }

    @Test fun taskForBlockReturnsOccurrenceForTemplate() {
        val occ = taskForBlock(mkBlock(id = "b1", taskId = "t1"), listOf(template))
        assertEquals("b1", occ?.id)
        assertNull(occ?.recurrence)
    }
}
