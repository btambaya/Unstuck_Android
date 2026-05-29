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
