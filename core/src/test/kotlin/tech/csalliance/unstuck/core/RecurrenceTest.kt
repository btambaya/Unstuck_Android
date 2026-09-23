package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.ChosenDateAction
import tech.csalliance.unstuck.core.logic.IsoDate
import tech.csalliance.unstuck.core.logic.MaterializedOccurrence
import tech.csalliance.unstuck.core.logic.RECURRENCE_HORIZON_DAYS
import tech.csalliance.unstuck.core.logic.RecurrenceStart
import tech.csalliance.unstuck.core.logic.RegenPlan
import tech.csalliance.unstuck.core.logic.findFreeSlotsForDate
import tech.csalliance.unstuck.core.logic.materializeOccurrences
import tech.csalliance.unstuck.core.logic.newTaskNeedsTime
import tech.csalliance.unstuck.core.logic.recurrenceAnchor
import tech.csalliance.unstuck.core.logic.recurrenceChosenDateAction
import tech.csalliance.unstuck.core.logic.recurrenceEditStart
import tech.csalliance.unstuck.core.logic.recurrenceLabel
import tech.csalliance.unstuck.core.logic.recurrenceSeriesTime
import tech.csalliance.unstuck.core.logic.regenerateForTask
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Time

// Ported 1:1 from RecurrenceTests.swift / lib/recurrence.test.ts.
class RecurrenceTest {
    private val start = Time.civil(2026, 5, 21) // Thu May 21 2026

    @Test fun dailyOnePerDayAcrossHorizon() {
        val occ = materializeOccurrences(Recurrence.Daily(), start, "09:00", 14)
        assertEquals(14, occ.size)
        assertEquals(MaterializedOccurrence("2026-05-21", "09:00"), occ[0])
        assertEquals(MaterializedOccurrence("2026-06-03", "09:00"), occ[13])
    }

    @Test fun weeklyMonWedFri() {
        val occ = materializeOccurrences(Recurrence.Weekly(listOf(1, 3, 5)), start, "09:00", 14)
        assertEquals(
            listOf("2026-05-22", "2026-05-25", "2026-05-27", "2026-05-29", "2026-06-01", "2026-06-03"),
            occ.map { it.date },
        )
    }

    @Test fun monthlySameDayOfMonth() {
        val occ = materializeOccurrences(Recurrence.Monthly(), start, "09:00", 93)
        assertEquals(listOf("2026-05-21", "2026-06-21", "2026-07-21", "2026-08-21"), occ.map { it.date })
    }

    // A day-31 monthly start clamps to each month's last day (Feb 28 in a non-leap
    // year), then RECOVERS to 31 in long months — it doesn't drift down to the 28th.
    @Test fun monthlyDay31ClampsToShortMonthEnd() {
        val jan31 = Time.civil(2026, 1, 31)
        val occ = materializeOccurrences(Recurrence.Monthly(), jan31, "09:00", 95)
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30"), occ.map { it.date })
    }

    // Same start in a leap year clamps Feb to the 29th.
    @Test fun monthlyDay31ClampsToLeapFeb() {
        val jan31 = Time.civil(2024, 1, 31)
        val occ = materializeOccurrences(Recurrence.Monthly(), jan31, "09:00", 95)
        assertEquals(listOf("2024-01-31", "2024-02-29", "2024-03-31", "2024-04-30"), occ.map { it.date })
    }

    @Test fun defaultHorizonIs8Weeks() {
        assertEquals(RECURRENCE_HORIZON_DAYS, materializeOccurrences(Recurrence.Daily(), start, "09:00").size)
    }

    @Test fun untilStopsInclusive() {
        val occ = materializeOccurrences(Recurrence.Daily("2026-05-25"), start, "09:00", 56)
        assertEquals(listOf("2026-05-21", "2026-05-22", "2026-05-23", "2026-05-24", "2026-05-25"), occ.map { it.date })
    }

    @Test fun nullUntilUsesHorizon() {
        assertEquals(7, materializeOccurrences(Recurrence.Daily(), start, "09:00", 7).size)
    }

    @Test fun weeklyWithUntilSkipsOutOfRange() {
        val occ = materializeOccurrences(Recurrence.Weekly(listOf(1), "2026-06-15"), start, "09:00", 56)
        assertEquals(listOf("2026-05-25", "2026-06-01", "2026-06-08", "2026-06-15"), occ.map { it.date })
    }

    // --- regenerateForTask ---

    private val task = mkTask(id = "task-1", name = "A")
    private val today = "2026-05-21"

    @Test fun nullRecurrenceDeletesFutureKeepsHistory() {
        val blocks = listOf(
            mkBlock(id = "past", taskId = "task-1", date = "2026-05-10"),
            mkBlock(id = "today", taskId = "task-1", date = today),
            mkBlock(id = "future1", taskId = "task-1", date = "2026-05-22"),
            mkBlock(id = "future2", taskId = "task-1", date = "2026-05-23"),
        )
        val plan = regenerateForTask(task, null, blocks, today, "09:00", start)
        assertEquals(emptyList<Any>(), plan.toUpsert)
        assertEquals(listOf("future1", "future2"), plan.toDelete.sorted())
    }

    @Test fun weeklyAddsMissingDeletesMismatched() {
        val blocks = listOf(mkBlock(id = "stray", taskId = "task-1", date = "2026-05-26")) // Tue
        val plan = regenerateForTask(task, Recurrence.Weekly(listOf(1)), blocks, today, "09:00", start, 14)
        assertEquals(listOf("stray"), plan.toDelete)
        assertEquals(listOf("2026-05-25", "2026-06-01"), plan.toUpsert.map { it.date })
        assertTrue(plan.toUpsert.all { it.taskId == "task-1" })
    }

    @Test fun preservesMatchingFutureBlocks() {
        val blocks = listOf(mkBlock(id = "kept", taskId = "task-1", startTime = "09:00", date = "2026-05-25"))
        val plan = regenerateForTask(task, Recurrence.Weekly(listOf(1)), blocks, today, "09:00", start, 14)
        assertEquals(emptyList<String>(), plan.toDelete)
        assertEquals(listOf("2026-06-01"), plan.toUpsert.map { it.date })
    }

    // --- daysOfWeek normalisation (out-of-range / convention-mismatch rows) ---

    // A row stored with JS getDay()'s 7 for Sunday (vs our 0=Sun) would never match
    // dayOfWeekJs (0..6) and silently produce ZERO occurrences. [7] folds to 0 (Sun)
    // and generates the same occurrences as [0]. start = Thu May 21 2026; Sundays are
    // May 24/31, Jun 7.
    @Test fun weeklyDay7TreatedAsSunday() {
        val from7 = materializeOccurrences(Recurrence.Weekly(listOf(7)), start, "09:00", 21)
        val from0 = materializeOccurrences(Recurrence.Weekly(listOf(0)), start, "09:00", 21)
        assertEquals(listOf("2026-05-24", "2026-05-31", "2026-06-07"), from7.map { it.date })
        assertEquals(from0.map { it.date }, from7.map { it.date })
    }

    // A normal weekly set is unaffected by normalisation.
    @Test fun weeklyValidSetStillGenerates() {
        val occ = materializeOccurrences(Recurrence.Weekly(listOf(1, 3, 5)), start, "09:00", 14)
        assertEquals(
            listOf("2026-05-22", "2026-05-25", "2026-05-27", "2026-05-29", "2026-06-01", "2026-06-03"),
            occ.map { it.date },
        )
    }

    // An EMPTY daysOfWeek (corrupt/legacy row) must NOT regenerate-to-nothing and wipe
    // every future block of the series — regenerateForTask leaves existing blocks intact.
    @Test fun weeklyEmptyDaysDoesNotWipeExistingBlocks() {
        val blocks = listOf(
            mkBlock(id = "future1", taskId = "task-1", date = "2026-05-22"),
            mkBlock(id = "future2", taskId = "task-1", date = "2026-05-23"),
        )
        val plan = regenerateForTask(task, Recurrence.Weekly(emptyList()), blocks, today, "09:00", start)
        assertEquals(emptyList<String>(), plan.toDelete)
        assertEquals(emptyList<Any>(), plan.toUpsert)
    }

    // --- recurrenceLabel ---

    @Test fun labelNilIsEmpty() = assertEquals("", recurrenceLabel(null))

    @Test fun labelAppendsUntil() {
        assertEquals("Repeats daily until Jun 15, 2026", recurrenceLabel(Recurrence.Daily("2026-06-15")))
        assertEquals("Repeats Mon/Wed/Fri until Aug 1, 2026", recurrenceLabel(Recurrence.Weekly(listOf(1, 3, 5), "2026-08-01")))
    }

    @Test fun labelOmitsUntilWhenUnset() = assertEquals("Repeats daily", recurrenceLabel(Recurrence.Daily()))

    @Test fun labelDailyMonthly() {
        assertEquals("Repeats daily", recurrenceLabel(Recurrence.Daily()))
        assertEquals("Repeats monthly", recurrenceLabel(Recurrence.Monthly()))
    }

    @Test fun labelWeeklyWeekdays() = assertEquals("Repeats weekdays", recurrenceLabel(Recurrence.Weekly(listOf(1, 2, 3, 4, 5))))

    @Test fun labelWeeklyWeekends() = assertEquals("Repeats weekends", recurrenceLabel(Recurrence.Weekly(listOf(0, 6))))

    @Test fun labelWeeklyAllSevenCollapsesToDaily() =
        assertEquals("Repeats daily", recurrenceLabel(Recurrence.Weekly(listOf(0, 1, 2, 3, 4, 5, 6))))

    @Test fun labelWeeklyMixedLists() = assertEquals("Repeats Mon/Wed/Fri", recurrenceLabel(Recurrence.Weekly(listOf(1, 3, 5))))
}

/** Local midnight of a 'YYYY-MM-DD' — the startDate regenerateForTask takes. */
private fun midnight(iso: String): Long = IsoDate.parse(iso)!!.let { Time.civil(it.year, it.monthValue, it.dayOfMonth) }

private fun CalBlock.done(): CalBlock = copy(done = true)
private fun CalBlock.skipped(): CalBlock = copy(skipped = true)

// The anchor (audit 2026-09-21, iOS build 79): regenerateForTask deletes every
// future block that doesn't match the anchor's date|time, so the anchor decides
// what survives. Ported from iOS RegenerateForTaskTests.
class RecurrenceAnchorTest {
    private val t = mkTask(id = "task-1", name = "A")
    private val today = "2026-05-21"

    @Test fun anchorIsTheEarliestLiveBlockNotAnArbitraryOne() {
        val blocks = listOf(
            mkBlock(id = "old", taskId = "task-1", startTime = "09:15", date = "2026-05-04").done(), // history, wrong time
            mkBlock(id = "next", taskId = "task-1", startTime = "11:00", date = "2026-05-25"),       // what the user set
            mkBlock(id = "later", taskId = "task-1", startTime = "11:00", date = "2026-06-01"),
            mkBlock(id = "other", taskId = "task-2", startTime = "07:00", date = "2026-05-22"),
        )
        val anchor = recurrenceAnchor("task-1", blocks, today)
        assertEquals("next", anchor?.id)
        assertEquals("the series keeps the time the user chose", "11:00", anchor?.startTime)
        // The old behaviour — the earliest block of any kind — anchored on 09:15
        // and deleted both 11:00 occurrences.
        val plan = regenerateForTask(t, Recurrence.Weekly(listOf(1)), blocks, today, anchor!!.startTime, Time.civil(2026, 5, 25), 14)
        assertEquals("nothing the user placed is removed", emptyList<String>(), plan.toDelete)
        assertEquals("both Mondays already exist at 11:00", emptyList<CalBlock>(), plan.toUpsert)
    }

    @Test fun anchorFallsBackToHistoryAndIgnoresTimelessBlocks() {
        // Only history: keep its time of day rather than snapping to 09:00.
        val past = mkBlock(id = "past", taskId = "task-1", startTime = "07:30", date = "2026-05-04").done()
        assertEquals("past", recurrenceAnchor("task-1", listOf(past), today)?.id)
        // A block with no time can't anchor a series; a real one alongside it wins.
        val timeless = mkBlock(id = "none", taskId = "task-1", startTime = "", date = "2026-05-22")
        val timed = mkBlock(id = "timed", taskId = "task-1", startTime = "11:00", date = "2026-05-25")
        assertEquals("timed", recurrenceAnchor("task-1", listOf(timeless, timed), today)?.id)
        assertNull(recurrenceAnchor("task-1", listOf(timeless), today))
        assertNull(recurrenceAnchor("task-1", emptyList(), today))
        // Skipped and done occurrences are not "live".
        val skipped = mkBlock(id = "skip", taskId = "task-1", startTime = "08:00", date = "2026-05-25").skipped()
        val live = mkBlock(id = "live", taskId = "task-1", startTime = "11:00", date = "2026-05-26")
        assertEquals("live", recurrenceAnchor("task-1", listOf(skipped, live), today)?.id)
    }

    /** Run again with nothing changed and the plan adds nothing; run it when
     *  the horizon has moved on and it only ADDS. */
    @Test fun regenerateIsIdempotentAndOnlyExtendsWhenTheHorizonMoves() {
        val anchorDate = Time.civil(2026, 5, 25)
        val weekly = Recurrence.Weekly(listOf(1))
        val first = regenerateForTask(t, weekly, emptyList(), today, "11:00", anchorDate, 14)
        assertEquals(listOf("2026-05-25", "2026-06-01"), first.toUpsert.map { it.date })
        val again = regenerateForTask(t, weekly, first.toUpsert, today, "11:00", anchorDate, 14)
        assertEquals("nothing to add the second time", emptyList<CalBlock>(), again.toUpsert)
        assertEquals("and nothing to remove", emptyList<String>(), again.toDelete)
        val wider = regenerateForTask(t, weekly, first.toUpsert, today, "11:00", anchorDate, 28)
        assertEquals("the horizon moved: add only", listOf("2026-06-08", "2026-06-15"), wider.toUpsert.map { it.date })
        assertEquals(emptyList<String>(), wider.toDelete)
    }

    /** A 2-minute task mints occurrences the server accepts (audit 2026-09-22, C4). */
    @Test fun mintedOccurrencesAreClampedToTheServerCheck() {
        val tiny = regenerateForTask(mkTask(id = "task-1", estimateMin = 2), Recurrence.Daily(), emptyList(), today, "07:00", Time.civil(2026, 5, 21), 7)
        assertTrue(tiny.toUpsert.isNotEmpty())
        assertTrue(tiny.toUpsert.all { it.durationMinutes == 5 })
        val huge = regenerateForTask(mkTask(id = "task-1", estimateMin = 5000), Recurrence.Daily(), emptyList(), today, "07:00", Time.civil(2026, 5, 21), 7)
        assertTrue(huge.toUpsert.all { it.durationMinutes == 1440 })
    }
}

// Where a recurrence EDIT regenerates from (audit 2026-09-22, C1): the series'
// own time and day, never a one-off moved occurrence's. Ported from iOS
// RecurrenceEditStartTests + testSeriesTimeIsTheCommonTimeNotTheNextOpenOne.
class RecurrenceEditStartTest {
    private val today = "2026-05-21"
    private fun day(offset: Int) = IsoDate.addDays(today, offset)
    private fun occ(offset: Int, time: String = "07:00") =
        mkBlock(id = "g$offset", taskId = "gym", taskName = "Gym", startTime = time, date = day(offset))
    private fun series(offsets: IntRange, time: String = "07:00") = offsets.map { occ(it, time) }

    @Test fun seriesTimeIsTheCommonTimeNotTheNextOpenOne() {
        val moved = series(0..55).toMutableList()
        moved[0] = moved[0].copy(startTime = "09:15")
        assertEquals("07:00", recurrenceSeriesTime("gym", moved, day(55)))
        // A whole-series re-plan to 08:00 isn't outvoted by older history.
        val replanned = series(-30..-1) + series(1..55, "08:00")
        assertEquals("08:00", recurrenceSeriesTime("gym", replanned, day(55)))
        // Monthly: one occurrence in the window each side of a move — history breaks the tie.
        val monthly = listOf(occ(-66), occ(-35), occ(-5), occ(25, "09:00"))
        assertEquals("07:00", recurrenceSeriesTime("gym", monthly, day(25)))
        assertNull("timeless only", recurrenceSeriesTime("gym", listOf(occ(1, "")), day(1)))
    }

    @Test fun aSingleLiveOccurrenceStillSetsTheTimeOverHistory() {
        // The build-79 "Office every Monday at 11" fix must hold.
        val doneOld = mkBlock(id = "old", taskId = "task-1", startTime = "09:15", date = "2026-05-04").done()
        val next = mkBlock(id = "next", taskId = "task-1", startTime = "11:00", date = "2026-05-25")
        assertEquals(
            RecurrenceStart("2026-05-25", "11:00", 56),
            recurrenceEditStart("task-1", Recurrence.Weekly(listOf(1)), listOf(doneOld, next), today),
        )
        assertNull(recurrenceEditStart("task-1", Recurrence.Daily(), emptyList(), today))
    }

    @Test fun editingTheEndDateNeverRebuildsTheSeriesAtAMovedTime() {
        val t = mkTask(id = "task-1", name = "Gym")
        val blocks = (1..55).map { mkBlock(id = "b$it", taskId = "task-1", startTime = "07:00", date = day(it)) }.toMutableList()
        blocks[0] = blocks[0].copy(startTime = "18:00")   // tomorrow's moved by hand
        val rec = Recurrence.Daily(day(90))
        val start = recurrenceEditStart("task-1", rec, blocks, today)
        assertNotNull(start)
        assertEquals("the series' time, not the moved one's", "07:00", start!!.startTime)
        val plan = regenerateForTask(t, rec, blocks, today, start.startTime, midnight(start.date), start.horizonDays)
        val deleted = plan.toDelete.toSet()
        assertFalse("nothing at 07:00 is deleted", blocks.any { it.startTime == "07:00" && it.id in deleted })
        assertFalse(plan.toUpsert.any { it.startTime != "07:00" })
    }

    /** Android-only shape of the anchor bug: the old anchor was the task's
     *  OLDEST block, and a series whose first block was 56+ days old
     *  materialised nothing after today — so ANY repeat edit (a weekday chip,
     *  the end date, the assistant) deleted every future occurrence and added
     *  none. */
    @Test fun anEditOnASeriesStartedLongAgoKeepsItsFutureOccurrences() {
        val t = mkTask(id = "task-1", name = "Gym")
        val history = (60 downTo 1).map { mkBlock(id = "h$it", taskId = "task-1", startTime = "07:00", date = day(-it)).done() }
        val upcoming = (0..20).map { mkBlock(id = "u$it", taskId = "task-1", startTime = "07:00", date = day(it)) }
        val blocks = history + upcoming
        val rec = Recurrence.Daily(day(90))
        val start = recurrenceEditStart("task-1", rec, blocks, today)!!
        assertEquals(today, start.date)
        val plan = regenerateForTask(t, rec, blocks, today, start.startTime, midnight(start.date), start.horizonDays)
        assertEquals("no future occurrence is deleted", emptyList<String>(), plan.toDelete)
        assertEquals("only the tail past +20 is added", (21..55).map(::day), plan.toUpsert.map { it.date })
    }

    @Test fun monthlyEditKeepsTheSeriesDayButAKindSwitchKeepsTheAnchors() {
        val history = listOf("2026-03-15", "2026-04-15", "2026-05-15").map {
            mkBlock(id = it, taskId = "task-1", startTime = "07:00", date = it).done()
        }
        val carried = mkBlock(id = "carried", taskId = "task-1", startTime = "07:00", date = "2026-06-16")
        val next = mkBlock(id = "next", taskId = "task-1", startTime = "07:00", date = "2026-07-15")
        val start = recurrenceEditStart("task-1", Recurrence.Monthly("2026-12-31"), history + listOf(carried, next), today)
        assertEquals("the 15th, not the carried occurrence's 16th", "2026-06-15", start?.date)
        assertEquals("the horizon still ends 8 weeks after the anchor", 57, start?.horizonDays)
        // Weekly Mondays switched to monthly: no day has two votes → the anchor's.
        val mondays = listOf("2026-05-25", "2026-06-01", "2026-06-08").map {
            mkBlock(id = it, taskId = "task-1", startTime = "09:00", date = it)
        }
        assertEquals("2026-05-25", recurrenceEditStart("task-1", Recurrence.Monthly(), mondays, today)?.date)
    }

    /** The plan an edit applies: regenerate from the start, minus `keepId`. */
    private fun editPlan(rec: Recurrence, blocks: List<CalBlock>, today: String): RegenPlan {
        val t: TaskItem = mkTask(id = "task-1", name = "Rent").copy(recurrence = rec)
        val start = recurrenceEditStart("task-1", rec, blocks, today)!!
        val plan = regenerateForTask(t, rec, blocks, today, start.startTime, midnight(start.date), start.horizonDays)
        return plan.copy(toDelete = plan.toDelete.filter { it != start.keepId })
    }
    private fun rent(date: String, time: String = "07:00", done: Boolean = false) =
        mkBlock(id = date, taskId = "task-1", startTime = time, date = date).copy(done = done)

    @Test fun monthlyEditKeepsThisMonthsOccurrenceMovedOffAPassedDay() {
        // Oct 15's rent pushed to Oct 20 at 18:00; on Oct 16 only the end date
        // changes. The start is Oct 15 (passed), so regenerate wanted nothing in
        // October and deleted Oct 20: the month lost its occurrence.
        val blocks = listOf(rent("2026-08-15", done = true), rent("2026-09-15", done = true), rent("2026-10-20", "18:00"), rent("2026-11-15"))
        assertEquals("Oct 20 stays; Nov 15 stays", RegenPlan(emptyList(), emptyList()), editPlan(Recurrence.Monthly("2027-06-30"), blocks, "2026-10-16"))
        // An end date before it ends the series there: nothing is kept.
        assertEquals(setOf("2026-10-20", "2026-11-15"), editPlan(Recurrence.Monthly("2026-10-18"), blocks, "2026-10-16").toDelete.toSet())
    }

    @Test fun monthlyEditStillRealignsNextMonthsOccurrenceMovedEarlier() {
        // Nov 15 dragged to Nov 10 is next month's, not October's: an explicit
        // edit puts it back on the series day, as regenerate does everywhere.
        val blocks = listOf(rent("2026-08-15", done = true), rent("2026-09-15", done = true), rent("2026-10-15", done = true), rent("2026-11-10"))
        val plan = editPlan(Recurrence.Monthly(), blocks, "2026-10-16")
        assertEquals(listOf("2026-11-10"), plan.toDelete)
        assertEquals(listOf("2026-11-15", "2026-12-15"), plan.toUpsert.map { it.date })
    }
}

// The post-plan decision behind scheduleTaskNow's guarantee on the chosen day.
// A block the plan is about to DELETE must NOT count as coverage (else the day
// silently ends up empty); a planned upsert DOES. Ported from iOS
// RecurrenceChosenDateActionTests (audit 2026-09-22, C7).
class RecurrenceChosenDateActionTest {
    private val iso = "2026-05-25"
    private val today = "2026-05-21"
    private val none = RegenPlan(emptyList(), emptyList())

    @Test fun coveredByPlannedUpsert() {
        val plan = RegenPlan(listOf(mkBlock(id = "u1", taskId = "t", date = iso)), emptyList())
        assertEquals(ChosenDateAction.Covered, recurrenceChosenDateAction(emptyList(), plan, iso, "09:00"))
    }

    @Test fun coveredByExistingBlockNotBeingDeleted() {
        val existing = listOf(mkBlock(id = "e1", taskId = "t", date = iso))
        assertEquals(ChosenDateAction.Covered, recurrenceChosenDateAction(existing, none, iso, "09:00"))
    }

    @Test fun existingBlockBeingDeletedDoesNotCount() {
        // The only block on the chosen date is queued for deletion → NOT covered,
        // so the caller must mint a guarantee block (the bug this guards).
        val existing = listOf(mkBlock(id = "e1", taskId = "t", date = iso))
        assertEquals(ChosenDateAction.Mint, recurrenceChosenDateAction(existing, RegenPlan(emptyList(), listOf("e1")), iso, "09:00"))
    }

    @Test fun deletedExistingButPlannedUpsertOnSameDateIsCovered() {
        val existing = listOf(mkBlock(id = "e1", taskId = "t", date = iso))
        val plan = RegenPlan(listOf(mkBlock(id = "u1", taskId = "t", date = iso)), listOf("e1"))
        assertEquals(ChosenDateAction.Covered, recurrenceChosenDateAction(existing, plan, iso, "09:00"))
    }

    @Test fun nothingOnChosenDateIsNotCovered() {
        val existing = listOf(mkBlock(id = "e1", taskId = "t", date = "2026-05-26"))
        val plan = RegenPlan(listOf(mkBlock(id = "u1", taskId = "t", date = "2026-06-01")), emptyList())
        assertEquals(ChosenDateAction.Mint, recurrenceChosenDateAction(existing, plan, iso, "09:00"))
    }

    /** regenerateForTask never touches today, so "schedule Walk today at 4pm"
     *  used to leave today's occurrence at 07:00 (it counted as covering). */
    @Test fun todaysOpenOccurrenceAtAnotherTimeIsRetimed() {
        val t = mkTask(id = "t", name = "Walk")
        val walk = mkBlock(id = "walk", taskId = "t", startTime = "07:00", date = today)
        val plan = regenerateForTask(t, Recurrence.Daily(), listOf(walk), today, "16:00", Time.civil(2026, 5, 21), 7)
        assertFalse("regenerate never touches today", "walk" in plan.toDelete)
        assertFalse(plan.toUpsert.any { it.date == today })
        assertEquals(ChosenDateAction.Retime(walk), recurrenceChosenDateAction(listOf(walk), plan, today, "16:00"))
    }

    /** A skipped occurrence used to count as covering the day, so the day just
     *  scheduled stayed hidden. It is retimed; the caller un-skips it. */
    @Test fun aSkippedOccurrenceNoLongerCoversItsDay() {
        val skipped = mkBlock(id = "skip", taskId = "t", startTime = "07:00", date = today).skipped()
        assertEquals(ChosenDateAction.Retime(skipped), recurrenceChosenDateAction(listOf(skipped), none, today, "16:00"))
        // A future skipped block at the chosen time is still retimed, i.e. un-skipped.
        val future = mkBlock(id = "fs", taskId = "t", startTime = "16:00", date = iso).skipped()
        assertEquals(ChosenDateAction.Retime(future), recurrenceChosenDateAction(listOf(future), none, iso, "16:00"))
    }

    /** A done occurrence covers its day — never an open second copy — but an
     *  open one beside it is the one that moves. */
    @Test fun aDoneOccurrenceCoversTheDay() {
        val done = mkBlock(id = "done", taskId = "t", startTime = "07:00", date = today).done()
        assertEquals(ChosenDateAction.Covered, recurrenceChosenDateAction(listOf(done), none, today, "16:00"))
        val early = mkBlock(id = "early", taskId = "t", startTime = "06:00", date = today).done()
        val open = mkBlock(id = "open", taskId = "t", startTime = "07:00", date = today)
        assertEquals(ChosenDateAction.Retime(open), recurrenceChosenDateAction(listOf(early, open), none, today, "16:00"))
    }

    /** Starting a series on a blockless task places today's occurrence: the
     *  plan only fills days after today. */
    @Test fun startingASeriesTodayMintsTodaysOccurrence() {
        val t = mkTask(id = "t", name = "Stretch")
        val plan = regenerateForTask(t, Recurrence.Daily(), emptyList(), today, "19:00", Time.civil(2026, 5, 21))
        assertTrue(plan.toUpsert.all { it.date > today })
        assertEquals(ChosenDateAction.Mint, recurrenceChosenDateAction(emptyList(), plan, today, "19:00"))
        // A timeless block on the day gets the chosen time instead of a twin.
        val timeless = mkBlock(id = "none", taskId = "t", startTime = "", date = today)
        assertEquals(ChosenDateAction.Retime(timeless), recurrenceChosenDateAction(listOf(timeless), plan, today, "19:00"))
    }
}

// The create sheet's gate (audit 2026-09-22, C7): a series saved without a time
// had zero occurrences and showed only in Tasks → Recurring. Ported from iOS
// NewTaskNeedsTimeTests.
class NewTaskNeedsTimeTest {
    private val today = "2026-05-21"
    private val tomorrow = "2026-05-22"

    @Test fun repeatingTasksNeedADayAndATime() {
        assertTrue(newTaskNeedsTime(repeats = true, date = today, todayIso = today, pickedTime = null))
        assertFalse(newTaskNeedsTime(repeats = true, date = today, todayIso = today, pickedTime = "19:00"))
        assertTrue("Later + Repeat", newTaskNeedsTime(repeats = true, date = null, todayIso = today, pickedTime = null))
    }

    @Test fun aOneOffNeedsATimeOnlyForALaterDay() {
        assertFalse("still added without a time", newTaskNeedsTime(repeats = false, date = today, todayIso = today, pickedTime = null))
        assertTrue(newTaskNeedsTime(repeats = false, date = tomorrow, todayIso = today, pickedTime = null))
        assertFalse(newTaskNeedsTime(repeats = false, date = tomorrow, todayIso = today, pickedTime = "10:00"))
        assertFalse("Later", newTaskNeedsTime(repeats = false, date = null, todayIso = today, pickedTime = null))
    }

    /** Why the evening reaches the gate: the free-slot finder stops at 18:00. */
    @Test fun theEveningHasNoFreeSlotToAutoPick() {
        val evening = Time.civil(2026, 5, 21) + (18.5 * 3_600_000).toLong()
        assertTrue(findFreeSlotsForDate(emptyList(), 25, today, evening).isEmpty())
    }
}
