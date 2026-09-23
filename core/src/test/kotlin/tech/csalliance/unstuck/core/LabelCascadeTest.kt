package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.areaFilterFollowing
import tech.csalliance.unstuck.core.logic.labelNameTaken
import tech.csalliance.unstuck.core.logic.relabelingArea
import tech.csalliance.unstuck.core.logic.renamingTag
import tech.csalliance.unstuck.core.logic.strippingTag
import tech.csalliance.unstuck.core.model.LifeArea

// Ports LabelCascadeTests (iOS TaskMutationsTests.swift, build 81): tasks key areas and
// tags by NAME, so a rename/delete of the vocabulary row rewrites the tasks that carry
// it (audit 2026-09-22 C19).
class LabelCascadeTest {
    private val now = "2026-09-22T12:00:00.000Z"

    @Test fun areaRenameMovesOnlyAnExactMatch() {
        val moved = relabelingArea(mkTask(lifeArea = "Personal"), from = "Personal", to = "Life", nowIso = now)
        assertEquals("Life", moved?.lifeArea)
        assertEquals(now, moved?.updatedAt)
        assertNull(relabelingArea(mkTask(lifeArea = "Work"), "Personal", "Life", now))
        assertNull(relabelingArea(mkTask(lifeArea = null), "Personal", "Life", now))
        assertNull("exact match, like the web cascade and the Today filter", relabelingArea(mkTask(lifeArea = "personal"), "Personal", "Life", now))
    }

    @Test fun areaDeleteClearsTheLabel() {
        val cleared = relabelingArea(mkTask(lifeArea = "Work"), from = "Work", to = null, nowIso = now)
        assertNotNull(cleared)
        assertNull(cleared?.lifeArea)
        assertEquals(now, cleared?.updatedAt)
    }

    @Test fun tagRenameIsCaseInsensitiveAndNeverDuplicates() {
        val renamed = renamingTag(mkTask(tags = listOf("DEEP", "x")), from = "deep", to = "Focus", nowIso = now)
        assertEquals(listOf("Focus", "x"), renamed?.tags)
        assertEquals(now, renamed?.updatedAt)
        assertEquals("a task already carrying the new name keeps one copy", listOf("focus"), renamingTag(mkTask(tags = listOf("deep", "focus")), "deep", "focus", now)?.tags)
        assertEquals("the de-dupe ignores case (Settings kept [y, Y])", listOf("y"), renamingTag(mkTask(tags = listOf("x", "Y")), "x", "y", now)?.tags)
        assertNull(renamingTag(mkTask(tags = listOf("x")), "deep", "Focus", now))
        assertNull(renamingTag(mkTask(tags = null), "deep", "Focus", now))
    }

    @Test fun tagDeleteStripsEveryCaseAndEmptiesToNull() {
        val stripped = strippingTag(mkTask(tags = listOf("Deep", "x", "deep")), name = "deep", nowIso = now)
        assertEquals(listOf("x"), stripped?.tags)
        assertEquals(now, stripped?.updatedAt)
        val emptied = strippingTag(mkTask(tags = listOf("deep")), "DEEP", now)
        assertNotNull(emptied)
        assertNull("an emptied list is null, not []", emptied?.tags)
        assertNull(strippingTag(mkTask(tags = listOf("x")), "deep", now))
        assertNull(strippingTag(mkTask(tags = null), "deep", now))
    }

    @Test fun labelNameTakenIgnoresCase() {
        assertTrue(labelNameTaken("home", listOf("Home", "Work")))
        assertFalse(labelNameTaken("Garden", listOf("Home", "Work")))
        assertFalse(labelNameTaken("Home", emptyList()))
    }

    @Test fun anAreaFilterFollowsARenameAndFallsBackToAllOnADelete() {
        val work = LifeArea("a1", "Work", "indigo", 0)
        val personal = LifeArea("a2", "Personal", "green", 1)
        val life = personal.copy(name = "Life")
        assertEquals("a renamed area keeps its pill selected under the new name", "Life", areaFilterFollowing("Personal", listOf(work, personal), listOf(work, life)))
        assertNull("a deleted area falls back to All", areaFilterFollowing("Personal", listOf(work, personal), listOf(work)))
        assertEquals("an unrelated change keeps the filter", "Work", areaFilterFollowing("Work", listOf(work, personal), listOf(work, life)))
        assertNull(areaFilterFollowing(null, listOf(work, personal), listOf(work, life)))
        val twin = work.copy(id = "a3")
        val office = twin.copy(name = "Office")
        assertEquals("an area still named Work keeps the filter when its twin is renamed", "Work", areaFilterFollowing("Work", listOf(work, twin), listOf(work, office)))
        // vm.lifeAreas starts as [] before the store's first emission: a filter that
        // names a real area survives that first load.
        assertEquals("Work", areaFilterFollowing("Work", emptyList(), listOf(work, personal)))
    }
}
