package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.MaterializedOccurrence
import tech.csalliance.unstuck.core.logic.RECURRENCE_HORIZON_DAYS
import tech.csalliance.unstuck.core.logic.materializeOccurrences
import tech.csalliance.unstuck.core.logic.recurrenceLabel
import tech.csalliance.unstuck.core.logic.regenerateForTask
import tech.csalliance.unstuck.core.model.Recurrence
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
