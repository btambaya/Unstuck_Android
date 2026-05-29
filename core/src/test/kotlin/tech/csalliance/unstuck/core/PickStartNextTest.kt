package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.csalliance.unstuck.core.logic.pickStartNext
import tech.csalliance.unstuck.core.logic.pickUpNext
import tech.csalliance.unstuck.core.model.Priority

// Mirrors PickStartNextTests.swift — the priority desc → estimate asc →
// createdAt asc ranker, exclusions, area filter, Up Next skip/limit.
class PickStartNextTest {

    @Test fun ranksByPriorityThenEstimateThenCreatedAt() {
        val low = mkTask(id = "low", priority = Priority.LOW)
        val urgentBig = mkTask(id = "urgent-big", estimateMin = 60, priority = Priority.URGENT)
        val urgentSmall = mkTask(id = "urgent-small", estimateMin = 10, priority = Priority.URGENT)
        val pick = pickStartNext(listOf(low, urgentBig, urgentSmall), emptyList(), null)
        assertEquals("urgent-small", pick?.id)
    }

    @Test fun estimateTieBrokenByCreatedAt() {
        val earlier = mkTask(id = "earlier", priority = Priority.HIGH, createdAt = "2026-05-01T00:00:00.000Z")
        val later = mkTask(id = "later", priority = Priority.HIGH, createdAt = "2026-05-10T00:00:00.000Z")
        assertEquals("earlier", pickStartNext(listOf(later, earlier), emptyList(), null)?.id)
    }

    @Test fun missingPriorityTreatedAsLow() {
        val none = mkTask(id = "none")
        val medium = mkTask(id = "medium", priority = Priority.MEDIUM)
        assertEquals("medium", pickStartNext(listOf(none, medium), emptyList(), null)?.id)
    }

    @Test fun excludesDoneLaterAndLive() {
        val done = mkTask(id = "done", done = true, priority = Priority.URGENT)
        val later = mkTask(id = "later", priority = Priority.URGENT, later = true)
        val live = mkTask(id = "live", priority = Priority.URGENT)
        val open = mkTask(id = "open", priority = Priority.LOW)
        assertEquals("open", pickStartNext(listOf(done, later, live, open), emptyList(), "live")?.id)
    }

    @Test fun honoursAreaFilter() {
        val work = mkTask(id = "work", priority = Priority.HIGH, lifeArea = "Work")
        val personal = mkTask(id = "personal", priority = Priority.URGENT, lifeArea = "Personal")
        assertEquals("work", pickStartNext(listOf(work, personal), emptyList(), null, "Work")?.id)
    }

    @Test fun returnsNullWhenNoCandidates() {
        assertNull(pickStartNext(listOf(mkTask(id = "later", later = true)), emptyList(), null))
        assertNull(pickStartNext(emptyList(), emptyList(), null))
    }

    @Test fun upNextSkipsLiveAndStartNextAndLimits() {
        val a = mkTask(id = "a", priority = Priority.URGENT)
        val b = mkTask(id = "b", priority = Priority.HIGH)
        val c = mkTask(id = "c", priority = Priority.MEDIUM)
        val d = mkTask(id = "d", priority = Priority.LOW)
        val live = mkTask(id = "live", priority = Priority.URGENT)
        val out = pickUpNext(listOf(a, b, c, d, live), emptyList(), "live", "a", 2)
        assertEquals(listOf("b", "c"), out.map { it.id })
    }

    @Test fun upNextExcludesDoneAndLater() {
        val done = mkTask(id = "done", done = true, priority = Priority.URGENT)
        val later = mkTask(id = "later", priority = Priority.URGENT, later = true)
        val open = mkTask(id = "open", priority = Priority.LOW)
        assertEquals(listOf("open"), pickUpNext(listOf(done, later, open), emptyList(), null, null).map { it.id })
    }
}
