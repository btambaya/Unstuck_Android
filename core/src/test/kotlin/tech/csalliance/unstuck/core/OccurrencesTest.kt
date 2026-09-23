package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.isTemplate
import tech.csalliance.unstuck.core.logic.liveOccurrenceBlockForTemplate
import tech.csalliance.unstuck.core.logic.occurrenceBlockFor
import tech.csalliance.unstuck.core.logic.overdueOccurrenceLabel
import tech.csalliance.unstuck.core.logic.overdueOccurrenceLabels
import tech.csalliance.unstuck.core.logic.projectOccurrences
import tech.csalliance.unstuck.core.logic.projectOverdueOccurrences
import tech.csalliance.unstuck.core.logic.taskForBlock
import tech.csalliance.unstuck.core.logic.visibleTasks
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.TaskListView
import tech.csalliance.unstuck.core.time.Clock

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

    // The template's totalFocused is the series' LIFETIME focus; a day's row that
    // inherited it read as "In progress" untouched and seeded that day's focus ring
    // (Android audit 2026-09-23, A13; web W10).
    @Test fun aDaysRowCarriesNoneOfTheSeriesLifetimeFocus() {
        val series = template.copy(totalFocused = 4500)
        val today = mkBlock(id = "b1", taskId = "t1", date = "2026-06-10")
        val missed = mkBlock(id = "b0", taskId = "t1", date = "2026-06-08")
        assertEquals(0, projectOccurrences(listOf(series), listOf(today), "2026-06-10").single().totalFocused)
        assertEquals(0, projectOverdueOccurrences(listOf(series), listOf(missed), "2026-06-10").single().totalFocused)
        assertEquals(0, taskForBlock(today, listOf(series))?.totalFocused)
        assertEquals("a plain task keeps its own total", 900, taskForBlock(mkBlock(id = "b2", taskId = "t2"), listOf(mkTask(id = "t2", totalFocused = 900)))?.totalFocused)
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

    @Test fun recurringViewReturnsTemplatesOnly() {
        val blocks = listOf(mkBlock(id = "b1", taskId = "t1", date = todayPlus(0)), mkBlock(id = "b2", taskId = "t1", date = todayPlus(1)))
        val recurring = visibleTasks(TaskListView.RECURRING, listOf(template), blocks, NOW, null, slipMode = false)
        assertEquals(listOf("t1"), recurring.map { it.id })
        // Today shows the occurrence, NOT the template.
        val today = visibleTasks(TaskListView.TODAY, listOf(template), blocks, NOW, null, slipMode = false)
        assertTrue(today.any { it.id == "b1" })
        assertFalse(today.any { it.id == "t1" })
    }

    // Recurring occurrences must NOT flood All (a Friday task once per horizon).
    @Test fun recurringAbsentFromAll() {
        val blocks = listOf(
            mkBlock(id = "b0", taskId = "t1", date = todayPlus(0)),
            mkBlock(id = "b1", taskId = "t1", date = todayPlus(1)),
            mkBlock(id = "b2", taskId = "t1", date = todayPlus(2)),
        )
        val all = visibleTasks(TaskListView.ALL, listOf(template), blocks, NOW, null, slipMode = false)
        assertTrue("neither the template nor its occurrences belong in All", all.isEmpty())
        // A normal task still appears in All alongside the recurring series.
        val normal = mkTask(id = "n1", createdAt = "2026-05-21T10:00:00.000Z")
        val all2 = visibleTasks(TaskListView.ALL, listOf(template, normal), blocks, NOW, null, slipMode = false)
        assertEquals(listOf("n1"), all2.map { it.id })
    }

    // Upcoming shows only the SINGLE next occurrence per series, not the horizon.
    @Test fun upcomingShowsOnlyNextOccurrence() {
        val blocks = listOf(
            mkBlock(id = "b1", taskId = "t1", date = todayPlus(1)),
            mkBlock(id = "b2", taskId = "t1", date = todayPlus(2)),
            mkBlock(id = "b3", taskId = "t1", date = todayPlus(3)),
        )
        val up = visibleTasks(TaskListView.UPCOMING, listOf(template), blocks, NOW, null, slipMode = false)
        assertEquals(listOf("b1"), up.map { it.id })
    }

    // ── projectOverdueOccurrences — missed recurring surfaces once (parity with
    //    lib/occurrences.test.ts "projectOverdueOccurrences"). ──────────────────
    private val TODAY = "2026-06-12"
    private val weekly = mkTask(id = "t1", name = "Call mom")
        .copy(recurrence = Recurrence.Weekly(daysOfWeek = listOf(5)))

    @Test fun overdueSurfacesOneRowForMostRecentMiss() {
        val blocks = listOf(
            mkBlock(id = "b1", taskId = "t1", date = "2026-06-05"),   // older miss
            mkBlock(id = "b2", taskId = "t1", date = "2026-06-11"),   // most-recent miss
        )
        val out = projectOverdueOccurrences(listOf(weekly), blocks, TODAY)
        assertEquals(1, out.size)
        assertEquals("b2", out[0].id)
        assertEquals("Call mom", out[0].name)
        assertFalse(out[0].done)
        assertNull(out[0].recurrence)
        assertNull(out[0].completedAt)
    }

    @Test fun overdueNeverStacksAcrossMultipleMisses() {
        val blocks = listOf(
            mkBlock(id = "b1", taskId = "t1", date = "2026-05-29"),
            mkBlock(id = "b2", taskId = "t1", date = "2026-06-05"),
            mkBlock(id = "b3", taskId = "t1", date = "2026-06-11"),
        )
        val out = projectOverdueOccurrences(listOf(weekly), blocks, TODAY)
        assertEquals(1, out.size)
        assertEquals("b3", out[0].id)   // only the most-recent
    }

    @Test fun overdueSupersededByTodayOccurrence() {
        val blocks = listOf(
            mkBlock(id = "b1", taskId = "t1", date = "2026-06-11"),   // missed
            mkBlock(id = "b2", taskId = "t1", date = TODAY),          // today's occurrence takes over
        )
        assertTrue(projectOverdueOccurrences(listOf(weekly), blocks, TODAY).isEmpty())
    }

    @Test fun overdueClearsWhenMostRecentDone() {
        val blocks = listOf(
            mkBlock(id = "b1", taskId = "t1", date = "2026-06-05"),                  // older, still undone
            mkBlock(id = "b2", taskId = "t1", date = "2026-06-11").copy(done = true), // most-recent, done
        )
        assertTrue(projectOverdueOccurrences(listOf(weekly), blocks, TODAY).isEmpty())
    }

    @Test fun overdueClearsWhenMostRecentSkipped() {
        val blocks = listOf(mkBlock(id = "b2", taskId = "t1", date = "2026-06-11").copy(skipped = true))
        assertTrue(projectOverdueOccurrences(listOf(weekly), blocks, TODAY).isEmpty())
    }

    @Test fun overdueNoneForFutureOnly() {
        val blocks = listOf(mkBlock(id = "b1", taskId = "t1", date = "2026-06-19"))
        assertTrue(projectOverdueOccurrences(listOf(weekly), blocks, TODAY).isEmpty())
    }

    @Test fun overdueNoneForNonRecurring() {
        val normal = mkTask(id = "t1")   // no recurrence
        val blocks = listOf(mkBlock(id = "b1", taskId = "t1", date = "2026-06-11"))
        assertTrue(projectOverdueOccurrences(listOf(normal), blocks, TODAY).isEmpty())
    }

    // The overdue row surfaces in BACKLOG (not Today) via visibleTasks. Use
    // todayPlus(-N) so the missed date is genuinely in the past relative to the
    // runtime "today" visibleTasks computes via Clock.todayIso().
    @Test fun overdueRowSurfacesInBacklogOnly() {
        val blocks = listOf(mkBlock(id = "miss", taskId = "t1", date = todayPlus(-3)))
        val backlog = visibleTasks(TaskListView.BACKLOG, listOf(weekly), blocks, NOW, null, slipMode = false)
        assertTrue("missed occurrence shows in Backlog", backlog.any { it.id == "miss" })
        val today = visibleTasks(TaskListView.TODAY, listOf(weekly), blocks, NOW, null, slipMode = false)
        assertFalse("missed occurrence must NOT show in Today", today.any { it.id == "miss" })
    }

    // The Backlog indicator label is "Overdue · <weekday-of-missed-date>".
    @Test fun overdueLabelShowsMissedWeekday() {
        // 2026-06-11 is a Thursday.
        val blocks = listOf(mkBlock(id = "b2", taskId = "t1", date = "2026-06-11"))
        assertEquals("Overdue · Thu", overdueOccurrenceLabel("b2", listOf(weekly), blocks, TODAY))
        // A non-occurrence / non-past row gets no label.
        val future = listOf(mkBlock(id = "fut", taskId = "t1", date = "2026-06-19"))
        assertNull(overdueOccurrenceLabel("fut", listOf(weekly), future, TODAY))
        assertNull(overdueOccurrenceLabel("nope", listOf(weekly), blocks, TODAY))
    }

    // The batched form (used by the Backlog list so the label isn't recomputed
    // per row per frame) must agree with the per-row form on EVERY id — the
    // small hand-built cases here and the heavy soak account below.
    @Test fun batchedLabelsMatchPerRowLabels_smallCases() {
        val weekly = template.copy(recurrence = Recurrence.Weekly(listOf(5)))
        val plain = mkTask(id = "t2", name = "One-off")
        val blocks = listOf(
            mkBlock(id = "b-missed", taskId = "t1", date = "2026-06-12"),
            mkBlock(id = "b-today", taskId = "t1", date = TODAY),
            mkBlock(id = "b-future", taskId = "t1", date = "2026-06-26"),
            mkBlock(id = "b-plain", taskId = "t2", date = "2026-06-12"),
        )
        val tasks = listOf(weekly, plain)
        val ids = listOf("b-missed", "b-today", "b-future", "b-plain", "t2", "absent")
        val batched = overdueOccurrenceLabels(ids, tasks, blocks, TODAY)
        for (id in ids) assertEquals(id, overdueOccurrenceLabel(id, tasks, blocks, TODAY), batched[id])
        assertEquals("no recurring tasks -> no labels", emptyMap<String, String>(), overdueOccurrenceLabels(ids, listOf(plain), blocks, TODAY))
        assertEquals("no rows -> no labels", emptyMap<String, String>(), overdueOccurrenceLabels(emptyList(), tasks, blocks, TODAY))
    }

    @Test fun batchedLabelsMatchPerRowLabels_heavyAccount() {
        val tasks = SoakSeed.tasks()
        val blocks = SoakSeed.blocks(tasks)
        val today = Clock.todayIso()
        val rows = visibleTasks(TaskListView.BACKLOG, tasks, blocks, System.currentTimeMillis(), null, null, false)
        assertTrue("fixture must have backlog rows", rows.size > 100)
        val ids = rows.map { it.id }
        val batched = overdueOccurrenceLabels(ids, tasks, blocks, today)
        var labelled = 0
        for (id in ids) {
            val one = overdueOccurrenceLabel(id, tasks, blocks, today)
            assertEquals(id, one, batched[id])
            if (one != null) labelled++
        }
        assertTrue("fixture must produce some overdue labels", labelled > 0)
    }

    // ── regressions from the 2026-09-12 core review (web commit 964a7a9) ──────

    /** Upcoming must ADVANCE to the next open day when a future occurrence is
     *  ticked, not drop the whole series: the next-per-series pick used to take
     *  the earliest future block and only then discard it for being done, so
     *  completing tomorrow's occurrence hid a daily task from Upcoming entirely. */
    @Test fun upcomingAdvancesToTheNextOpenOccurrenceWhenAFutureOneIsDone() {
        val blocks = listOf(
            mkBlock(id = "b1", taskId = "t1", date = todayPlus(1)).copy(done = true, completedAt = iso(NOW)),
            mkBlock(id = "b2", taskId = "t1", date = todayPlus(2)),
            mkBlock(id = "b3", taskId = "t1", date = todayPlus(3)),
        )
        val up = visibleTasks(TaskListView.UPCOMING, listOf(template), blocks, NOW, null, slipMode = false)
        assertEquals(listOf("b2"), up.map { it.id })
    }

    /** A recurring occurrence completed TODAY has to live somewhere: it stays in
     *  Today as the day's win (where it can be un-ticked) and it is listed under
     *  Completed. Before the fix it appeared in NO Tasks view at all. */
    @Test fun anOccurrenceCompletedTodayStaysInTodayAndAppearsInCompleted() {
        val now = System.currentTimeMillis()
        val blocks = listOf(
            mkBlock(id = "b-today", taskId = "t1", date = todayPlus(0)).copy(done = true, completedAt = iso(now)),
        )
        val today = visibleTasks(TaskListView.TODAY, listOf(template), blocks, now, null, slipMode = false)
        assertEquals(listOf("b-today"), today.map { it.id })
        val completed = visibleTasks(TaskListView.COMPLETED, listOf(template), blocks, now, null, slipMode = false)
        assertEquals(listOf("b-today"), completed.map { it.id })
    }

    /** …and an OPEN occurrence is still only in Today. */
    @Test fun anOpenOccurrenceIsNotListedUnderCompleted() {
        val blocks = listOf(mkBlock(id = "b-today", taskId = "t1", date = todayPlus(0)))
        val now = System.currentTimeMillis()
        assertEquals(listOf("b-today"), visibleTasks(TaskListView.TODAY, listOf(template), blocks, now, null, slipMode = false).map { it.id })
        assertTrue(visibleTasks(TaskListView.COMPLETED, listOf(template), blocks, now, null, slipMode = false).isEmpty())
    }

    // liveOccurrenceBlockForTemplate — the reminder notification's "Start" action
    // deep-links the block's task_id (the hidden TEMPLATE), so focus started from
    // the shade has to find the day's block itself or "Done" ticks the template.
    @Test fun liveOccurrenceForTemplateFindsTodaysOpenBlock() {
        val blocks = listOf(
            mkBlock(id = "b-yesterday", taskId = "t1", date = "2026-06-09", startTime = "07:00"),
            mkBlock(id = "b-today-late", taskId = "t1", date = "2026-06-10", startTime = "18:00"),
            mkBlock(id = "b-today", taskId = "t1", date = "2026-06-10", startTime = "07:00"),
            mkBlock(id = "b-tomorrow", taskId = "t1", date = "2026-06-11", startTime = "07:00"),
        )
        // Earliest start time on TODAY wins; other days are never touched.
        assertEquals("b-today", liveOccurrenceBlockForTemplate("t1", listOf(template), blocks, "2026-06-10")?.id)
    }

    @Test fun liveOccurrenceForTemplateIgnoresDoneSkippedAndNonTemplates() {
        val doneToday = mkBlock(id = "b1", taskId = "t1", date = "2026-06-10").copy(done = true)
        val skippedToday = mkBlock(id = "b2", taskId = "t1", date = "2026-06-10", startTime = "10:00").copy(skipped = true)
        assertNull("a finished / cancelled day is never re-ticked by a later session",
            liveOccurrenceBlockForTemplate("t1", listOf(template), listOf(doneToday, skippedToday), "2026-06-10"))
        // A plain task's id resolves to nothing — only recurring templates do.
        val plain = mkTask(id = "t2")
        val plainBlock = mkBlock(id = "b3", taskId = "t2", date = "2026-06-10")
        assertNull(liveOccurrenceBlockForTemplate("t2", listOf(template, plain), listOf(plainBlock), "2026-06-10"))
        // And an occurrence ROW id (a block id) is not a template id either.
        assertNull(liveOccurrenceBlockForTemplate("b3", listOf(template, plain), listOf(plainBlock), "2026-06-10"))
    }
}
