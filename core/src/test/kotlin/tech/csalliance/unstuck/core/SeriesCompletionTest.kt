package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.ReceiptArgs
import tech.csalliance.unstuck.core.logic.ReceiptIcon
import tech.csalliance.unstuck.core.logic.ReceiptUndo
import tech.csalliance.unstuck.core.logic.addDaysIso
import tech.csalliance.unstuck.core.logic.deriveReceipt
import tech.csalliance.unstuck.core.logic.exactTaskLink
import tech.csalliance.unstuck.core.logic.isExactTaskLink
import tech.csalliance.unstuck.core.logic.occurrencesCarryingTaskDone
import tech.csalliance.unstuck.core.logic.planReceiptUndo
import tech.csalliance.unstuck.core.logic.projectOccurrences
import tech.csalliance.unstuck.core.logic.projectOverdueOccurrences
import tech.csalliance.unstuck.core.logic.taskAfterSettingRecurrence
import tech.csalliance.unstuck.core.logic.taskLinkRowForId
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.core.model.SharedWithMe
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.model.isRecurringSeriesRefusal
import tech.csalliance.unstuck.core.model.shareCanTickDone
import tech.csalliance.unstuck.core.model.shareTickErrorText

// A repeating series is acted on through its OCCURRENCE, never its template
// (parity with iOS build 81 — RecurringSurfacesTests, RecurrenceTests,
// ShareLevelsTests, AssistantReceiptsTests; audit 2026-09-22 C3).
class SeriesCompletionTest {

    // ── taskLinkRowForId ────────────────────────────────────────────────────
    //
    // A reminder tap opens unstuck://task/<templateId>; the template's editor
    // offered "Mark done" on the series itself, ending it. The link opens the
    // day's occurrence instead — picked for a reminder tapped LATE.

    private val today = "2026-09-22"
    private val tomorrow = addDaysIso(today, 1)
    private val yesterday = addDaysIso(today, -1)
    private val lastWeek = addDaysIso(today, -7)

    private fun template() = mkTask(id = "tpl", name = "Meds").copy(recurrence = Recurrence.Daily())
    private fun occ(id: String, date: String, done: Boolean = false, skipped: Boolean = false) =
        mkBlock(id = id, taskId = "tpl", taskName = "Meds", date = date).copy(done = done, skipped = skipped)

    @Test fun aTaskLinkOpensTodaysOpenOccurrence() {
        val row = taskLinkRowForId("tpl", listOf(template()), listOf(occ("b-today", today), occ("b-tomorrow", tomorrow)), today)
        assertEquals("b-today", row?.id)
        assertNull("an occurrence row, never the series", row?.recurrence)
        assertEquals(false, row?.done)
    }

    /** A stale reminder tapped after the day was ticked shows TODAY ticked, never
     *  tomorrow's row (whose Mark done would tick the wrong day). */
    @Test fun aTaskLinkShowsTodayTickedRatherThanOfferingTomorrow() {
        val row = taskLinkRowForId("tpl", listOf(template()), listOf(occ("b-today", today, done = true), occ("b-tomorrow", tomorrow)), today)
        assertEquals("b-today", row?.id)
        assertEquals(true, row?.done)
    }

    /** Nothing today: last Friday's reminder tapped on Saturday opens the missed
     *  Friday (the row Backlog shows as overdue), not next Friday. */
    @Test fun aTaskLinkWithNothingTodayOpensTheOpenOverdueDay() {
        assertEquals("b-past", taskLinkRowForId("tpl", listOf(template()), listOf(occ("b-past", yesterday), occ("b-tomorrow", tomorrow)), today)?.id)
    }

    /** …and once that latest past day is handled, the next open future one — an
     *  older miss is never dredged up. */
    @Test fun aTaskLinkWithTheLatestPastHandledOpensTheNextOpenDay() {
        val ticked = listOf(occ("b-old", lastWeek), occ("b-past", yesterday, done = true), occ("b-tomorrow", tomorrow))
        assertEquals("b-tomorrow", taskLinkRowForId("tpl", listOf(template()), ticked, today)?.id)
        val skipped = listOf(occ("b-past", yesterday, skipped = true), occ("b-tomorrow", tomorrow))
        assertEquals("b-tomorrow", taskLinkRowForId("tpl", listOf(template()), skipped, today)?.id)
    }

    @Test fun aTaskLinkNeverPicksASkippedDay() {
        val blocks = listOf(occ("b-today", today, skipped = true), occ("b-tomorrow", tomorrow, skipped = true), occ("b-later", addDaysIso(today, 2)))
        assertEquals("b-later", taskLinkRowForId("tpl", listOf(template()), blocks, today)?.id)
    }

    @Test fun aTaskLinkForASeriesWithNoOccurrenceOpensTheSeries() {
        assertEquals("tpl", taskLinkRowForId("tpl", listOf(template()), emptyList(), today)?.id)
        assertEquals("tpl", taskLinkRowForId("tpl", listOf(template()), listOf(occ("b-past", yesterday, done = true)), today)?.id)
    }

    /** A block id opens THAT day, even a future one. */
    @Test fun aTaskLinkBlockIdOpensThatExactDay() {
        val row = taskLinkRowForId("b-tomorrow", listOf(template()), listOf(occ("b-today", today), occ("b-tomorrow", tomorrow)), today)
        assertEquals("b-tomorrow", row?.id)
        assertNull(row?.recurrence)
    }

    @Test fun aTaskLinkForPlainAndUnknownIds() {
        val t = mkTask(id = "t1")
        val b = mkBlock(id = "b1", taskId = "t1", date = today)
        assertEquals("t1", taskLinkRowForId("t1", listOf(t), listOf(b), today)?.id)
        assertEquals("t1", taskLinkRowForId("b1", listOf(t), listOf(b), today)?.id)
        assertNull(taskLinkRowForId("nope", listOf(t), emptyList(), today))
        assertNull(taskLinkRowForId("", listOf(t), emptyList(), today))
    }

    @Test fun anExactLinkIsMarkedAndRecognised() {
        assertEquals("unstuck://task/tpl?exact", exactTaskLink("tpl"))
        assertTrue(isExactTaskLink(exactTaskLink("tpl")))
        assertFalse(isExactTaskLink("unstuck://task/tpl"))
    }

    // ── taskAfterSettingRecurrence ──────────────────────────────────────────
    //
    // "Never" on a ticked day used to bring today back unticked, and Daily →
    // Never → Daily must not leave a DONE template (an ended series).

    private val now = "2026-09-22T18:00:00.000Z"

    private fun series(done: Boolean = false, completedAt: String? = null) =
        mkTask(id = "tpl", name = "Meditate", done = done, completedAt = completedAt).copy(recurrence = Recurrence.Daily())

    private fun day(id: String, date: String, done: Boolean = false, skipped: Boolean = false, completedAt: String? = null, at: String = "07:00") =
        CalBlock(id = id, taskId = "tpl", taskName = "Meditate", startTime = at, durationMinutes = 20, date = date,
            kind = CalBlockKind.TASK, done = done, skipped = skipped, completedAt = completedAt)

    @Test fun neverOnATickedDayCarriesTheTickOntoTheTask() {
        val blocks = listOf(
            day("b0", "2026-09-21", done = true, completedAt = "2026-09-21T07:20:00.000Z"),
            day("b1", today, done = true, completedAt = "2026-09-22T07:20:00.000Z"),
            day("b2", "2026-09-23"),
        )
        val out = taskAfterSettingRecurrence(series(), null, blocks, today, now)
        assertNull(out.recurrence)
        assertTrue(out.done)
        assertEquals("the day's own completion time", "2026-09-22T07:20:00.000Z", out.completedAt)
    }

    @Test fun aTickWithNoStampFallsBackToNow() {
        val out = taskAfterSettingRecurrence(series(), null, listOf(day("b1", today, done = true)), today, now)
        assertTrue(out.done)
        assertEquals(now, out.completedAt)
    }

    /** Owner decision: an open or absent today leaves the task OPEN. */
    @Test fun neverWithTodayOpenOrAbsentLeavesTheTaskOpen() {
        val open = taskAfterSettingRecurrence(series(), null, listOf(day("b1", today)), today, now)
        assertFalse(open.done)
        assertNull(open.completedAt)
        val twins = listOf(day("b1", today, done = true, completedAt = now), day("b2", today, at = "19:00"))
        assertFalse("one of two occurrences today is still open", taskAfterSettingRecurrence(series(), null, twins, today, now).done)
        assertFalse(taskAfterSettingRecurrence(series(), null, listOf(day("b1", today, done = true, skipped = true)), today, now).done)
        val historyOnly = listOf(day("b0", "2026-09-21", done = true, completedAt = "2026-09-21T07:20:00.000Z"))
        assertFalse(taskAfterSettingRecurrence(series(), null, historyOnly, today, now).done)
    }

    @Test fun aSeriesAlreadyDoneKeepsItsDone() {
        val t0 = "2026-09-17T09:00:00.000Z"
        val out = taskAfterSettingRecurrence(series(done = true, completedAt = t0), null, listOf(day("b1", today)), today, now)
        assertTrue(out.done)
        assertEquals(t0, out.completedAt)
    }

    /** Daily → Never (ticked today, so done) → Daily must give an OPEN series. */
    @Test fun turningARepeatBackOnReopensTheTask() {
        val blocks = listOf(day("b1", today, done = true, completedAt = "2026-09-22T07:20:00.000Z"))
        val off = taskAfterSettingRecurrence(series(), null, blocks, today, now)
        assertTrue(off.done)
        val on = taskAfterSettingRecurrence(off, Recurrence.Daily(), blocks, today, now)
        assertEquals(Recurrence.Daily(), on.recurrence)
        assertFalse("a done template is an ended series", on.done)
        assertNull(on.completedAt)
    }

    @Test fun otherChangesOnlySetTheRecurrence() {
        val on = taskAfterSettingRecurrence(mkTask(id = "tpl", name = "Meditate"), Recurrence.Weekly(listOf(1)), emptyList(), today, now)
        assertEquals(Recurrence.Weekly(listOf(1)), on.recurrence)
        assertFalse(on.done)
        val ended = series(done = true, completedAt = "2026-09-17T09:00:00.000Z")
        assertTrue("one rule for another leaves the done state as it is",
            taskAfterSettingRecurrence(ended, Recurrence.Weekly(listOf(2)), emptyList(), today, now).done)
        val plainDone = mkTask(id = "p", name = "Plain", done = true, completedAt = "2026-09-20T09:00:00.000Z")
        assertEquals(plainDone, taskAfterSettingRecurrence(plainDone, null, emptyList(), today, now))
    }

    // ── occurrencesCarryingTaskDone — a done task made to repeat keeps its tick ──
    //
    // Stamps sit at 11:00Z; the core tests run in UTC (build.gradle.kts), so the
    // local day is the literal's.

    private fun plain(done: Boolean = true, completedAt: String? = "2026-09-22T11:00:00.000Z") =
        mkTask(id = "tpl", name = "Meditate", done = done, completedAt = completedAt)

    /** Ticked this morning, made daily: today's slot keeps the tick, so it is not
     *  back in Today to do again. Tomorrow's is a new day. */
    @Test fun aDoneTaskMadeToRepeatKeepsTodaysTick() {
        val blocks = listOf(day("b1", today, at = "07:30"), day("b2", "2026-09-23", at = "07:30"))
        val carried = occurrencesCarryingTaskDone(plain(), Recurrence.Daily(), blocks, today, now)
        assertEquals(listOf("b1"), carried.map { it.id })
        assertTrue(carried[0].done)
        assertEquals("the task's own completion time", "2026-09-22T11:00:00.000Z", carried[0].completedAt)
        assertEquals("only the done state changes", "07:30", carried[0].startTime)
        val tpl = taskAfterSettingRecurrence(plain(), Recurrence.Daily(), blocks, today, now)
        val rows = projectOccurrences(listOf(tpl), listOf(carried[0], blocks[1]), today)
        assertEquals(true, rows.first { it.id == "b1" }.done)
        assertEquals(false, rows.first { it.id == "b2" }.done)
    }

    /** The tick lands on the slot it fulfilled — the latest scheduled day on or
     *  before the day it was done — so that slot is never an overdue miss. */
    @Test fun theTickLandsOnTheSlotItFulfilled() {
        val late = occurrencesCarryingTaskDone(plain(), Recurrence.Daily(), listOf(day("b0", "2026-09-21")), today, now)
        assertEquals(listOf("b0"), late.map { it.id })
        val tpl = taskAfterSettingRecurrence(plain(), Recurrence.Daily(), late, today, now)
        assertEquals("left open, the slot showed as a missed day", 1,
            projectOverdueOccurrences(listOf(tpl), listOf(day("b0", "2026-09-21")), today).size)
        assertTrue("no overdue row for a day that was done", projectOverdueOccurrences(listOf(tpl), late, today).isEmpty())
        // Done yesterday on its slot: that slot. Today's is a new day, still open.
        val early = occurrencesCarryingTaskDone(plain(completedAt = "2026-09-21T11:00:00.000Z"), Recurrence.Daily(),
            listOf(day("b0", "2026-09-21"), day("b1", today)), today, now)
        assertEquals(listOf("b0"), early.map { it.id })
        // Done yesterday, scheduled only today: nothing at or before the done day.
        assertTrue(occurrencesCarryingTaskDone(plain(completedAt = "2026-09-21T11:00:00.000Z"), Recurrence.Daily(),
            listOf(day("b1", today)), today, now).isEmpty())
        // Two slots on the day: both; a skipped or already-ticked one is left alone.
        val twins = occurrencesCarryingTaskDone(plain(), Recurrence.Daily(), listOf(
            day("b1", today), day("b2", today, at = "19:00"),
            day("b3", today, done = true, at = "12:00"), day("b4", today, skipped = true, at = "13:00"),
        ), today, now)
        assertEquals(setOf("b1", "b2"), twins.map { it.id }.toSet())
    }

    @Test fun aStamplessDoneCountsAsToday() {
        val carried = occurrencesCarryingTaskDone(plain(completedAt = null), Recurrence.Weekly(listOf(2)), listOf(day("b1", today)), today, now)
        assertEquals(listOf(now), carried.map { it.completedAt })
    }

    @Test fun nothingCarriesForAnyOtherChange() {
        val blocks = listOf(day("b1", today))
        assertTrue("an open task has no tick", occurrencesCarryingTaskDone(plain(done = false), Recurrence.Daily(), blocks, today, now).isEmpty())
        assertTrue("no repeat turned on", occurrencesCarryingTaskDone(plain(), null, blocks, today, now).isEmpty())
        assertTrue("one rule for another", occurrencesCarryingTaskDone(series(done = true, completedAt = "2026-09-22T11:00:00.000Z"),
            Recurrence.Weekly(listOf(1)), blocks, today, now).isEmpty())
        assertTrue("a day already ticked", occurrencesCarryingTaskDone(plain(), Recurrence.Daily(), listOf(day("b1", today, done = true)), today, now).isEmpty())
        assertTrue(occurrencesCarryingTaskDone(plain(), Recurrence.Daily(), listOf(day("x", today).copy(taskId = "someone-else")), today, now).isEmpty())
    }

    // ── shareCanTickDone — a repeating share's row is the owner's TEMPLATE ──

    private fun share(level: ShareLevel, recurring: Boolean) =
        SharedWithMe(shareId = "s", taskId = "t", ownerName = "Anna", level = level, title = "Gym", done = false, recurring = recurring)

    @Test fun aRepeatingShareCanNeverBeTicked() {
        assertTrue(shareCanTickDone(share(ShareLevel.PARTNER, false)))
        assertTrue(shareCanTickDone(share(ShareLevel.ASSIGN, false)))
        assertFalse(shareCanTickDone(share(ShareLevel.PARTNER, true)))
        assertFalse(shareCanTickDone(share(ShareLevel.ASSIGN, true)))
        assertFalse(shareCanTickDone(share(ShareLevel.VIEW, false)))
        assertFalse(shareCanTickDone(share(ShareLevel.VIEW, true)))
    }

    /** The server's refusal in plain words; never its raw text (SC-12). */
    @Test fun aRefusedSharedTickSaysWhy() {
        assertEquals("Only the owner can tick off a repeating task.",
            shareTickErrorText("""{"code":"P0001","message":"recurring_series"}"""))
        assertEquals("Couldn't update this task — try again.", shareTickErrorText("not_allowed"))
        assertEquals("Couldn't update this task — try again.", shareTickErrorText(null))
        assertTrue(isRecurringSeriesRefusal("""{"code":"P0001","message":"recurring_series"}"""))
        assertFalse(isRecurringSeriesRefusal("not_configured"))
        assertFalse(isRecurringSeriesRefusal(null))
    }

    // ── receipts ────────────────────────────────────────────────────────────

    private fun rtask(id: String, name: String, done: Boolean = false, recurrence: Recurrence? = null) =
        TaskItem(id = id, name = name, estimateMin = 25, done = done, recurrence = recurrence,
            createdAt = "2026-08-01T10:00:00Z", updatedAt = "2026-08-01T10:00:00Z")

    /** complete_task on a series ticks TODAY's occurrence: complete_occurrence's
     *  card, no Undo. A plain completion keeps its Undo. */
    @Test fun completeTaskOnASeriesIsADoneForTodayCardWithNoUndo() {
        val sameName = rtask("x", "Meds", done = true)
        val card = deriveReceipt("complete_task", ReceiptArgs(), "ok: marked \"Meds\" done for 2026-09-22 (series continues)", listOf(sameName))
        assertEquals("Done for today: Meds", card?.label)
        assertEquals(ReceiptIcon.CHECK, card?.icon)
        assertNull(card?.undo)
        assertEquals(ReceiptUndo.uncompleteTask("x"), deriveReceipt("complete_task", ReceiptArgs(), "ok: completed \"X\" id=x", listOf(sameName))?.undo)
    }

    /** uncomplete_task / complete_tasks on a series carry no id=, so no Undo
     *  (whose complete would end the series). */
    @Test fun seriesResultsOfferNoUndo() {
        assertNull(deriveReceipt("uncomplete_task", ReceiptArgs(), "ok: reopened \"Meds\" for 2026-09-22 (series continues)", emptyList())?.undo)
        assertNull(deriveReceipt("uncomplete_task", ReceiptArgs(), "ok: reopened \"Meds\" — its repeating series runs again", emptyList())?.undo)
        assertNull(deriveReceipt("complete_tasks", ReceiptArgs(), "ok: completed 1 tasks ids= — \"Meds\" (today — series continues)", emptyList())?.undo)
    }

    /** An older persisted "Reopened" receipt on a template never plans a completion of it. */
    @Test fun theCompleteUndoNeverTargetsASeriesTemplate() {
        assertNull(planReceiptUndo(ReceiptUndo.completeTask("tpl"), listOf(rtask("tpl", "Meds", recurrence = Recurrence.Daily())), "n"))
        assertTrue(planReceiptUndo(ReceiptUndo.completeTask("p"), listOf(rtask("p", "Plain")), "n") != null)
    }
}
