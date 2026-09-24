package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.rejectOffSeriesDay
import tech.csalliance.unstuck.core.logic.rejectOffSeriesPlacement
import tech.csalliance.unstuck.core.model.Recurrence

/**
 * James's Park run (TestFlight build 51, Sunday 2026-09-13): he asked for his
 * weekly Saturday run, the model sent 2026-09-20 — a Sunday — and the executor
 * wrote it. The refusal names the right days in plain words.
 */
class OffSeriesDayTest {

    @Test fun `James's vector - a Saturday series asked onto Sunday the 20th`() {
        assertEquals(
            "error: \"Park run\" repeats every Saturday, but 2026-09-20 is a Sunday — nothing was scheduled. " +
                "The nearest Saturdays: Saturday 2026-09-19, Saturday 2026-09-26. " +
                "Call schedule_task again with the day the user meant; only if they asked for Sunday itself (a one-off move off its usual day), call it again with 2026-09-20 unchanged. " +
                "To change the days it repeats on, call set_task_recurrence instead.",
            rejectOffSeriesDay("Park run", Recurrence.Weekly(listOf(6)), "2026-09-20", "2026-09-13"),
        )
    }

    @Test fun `a day in the series, or a series that isn't weekly, is fine`() {
        assertNull(rejectOffSeriesDay("Park run", Recurrence.Weekly(listOf(6)), "2026-09-19", "2026-09-13"))
        assertNull(rejectOffSeriesDay("Gym", Recurrence.Weekly(listOf(1, 3, 5)), "2026-09-23", "2026-09-13"))
        assertNull(rejectOffSeriesDay("Stretch", Recurrence.Daily(), "2026-09-20", "2026-09-13"))
        assertNull(rejectOffSeriesDay("Rent", Recurrence.Monthly(), "2026-09-20", "2026-09-13"))
        assertNull(rejectOffSeriesDay("One-off", null, "2026-09-20", "2026-09-13"))
        // A malformed date is the date checks' to refuse, not this one's.
        assertNull(rejectOffSeriesDay("Park run", Recurrence.Weekly(listOf(6)), "Saturday", "2026-09-13"))
        // No days at all: nothing to compare with.
        assertNull(rejectOffSeriesDay("Odd", Recurrence.Weekly(emptyList()), "2026-09-20", "2026-09-13"))
    }

    @Test fun `several days are named together, and a matching day before today is never offered`() {
        val r = rejectOffSeriesDay("Gym", Recurrence.Weekly(listOf(5, 1, 3)), "2026-09-22", "2026-09-13")!!
        assertTrue(r, r.startsWith("error: \"Gym\" repeats every Monday, Wednesday and Friday, but 2026-09-22 is a Tuesday — nothing was scheduled. "))
        assertTrue(r, r.contains("The nearest matching days: Monday 2026-09-21, Wednesday 2026-09-23. "))
        // Today is Tuesday the 22nd: yesterday's Monday is gone, so only Wednesday.
        val today = rejectOffSeriesDay("Gym", Recurrence.Weekly(listOf(1, 3)), "2026-09-22", "2026-09-22")!!
        assertTrue(today, today.contains("The nearest matching days: Wednesday 2026-09-23. "))
        val two = rejectOffSeriesDay("Swim", Recurrence.Weekly(listOf(6, 0)), "2026-09-23", "2026-09-13")!!
        assertTrue(two, two.contains("repeats every Sunday and Saturday, but 2026-09-23 is a Wednesday"))
        assertTrue(two, two.contains("The nearest matching days: Sunday 2026-09-20, Saturday 2026-09-26. "))
    }

    /** The create-then-repeat variant: created on Sunday the 20th, then "every
     *  Saturday" — the series would start from the Sunday slot. */
    @Test fun `a slot placed this turn on a day the new weekly days leave out is named`() {
        assertEquals(
            "error: \"Park run\" was just put on Sunday 2026-09-20, but weekly on Saturday leaves out Sundays — nothing changed. " +
                "The nearest Saturdays: Saturday 2026-09-19, Saturday 2026-09-26. " +
                "If the user meant one of those, schedule_task \"Park run\" to it first, then call set_task_recurrence again; " +
                "only if the series really starts on Sunday 2026-09-20, call set_task_recurrence again unchanged.",
            rejectOffSeriesPlacement("Park run", "2026-09-20", listOf(6), "2026-09-13"),
        )
        assertNull(rejectOffSeriesPlacement("Park run", "2026-09-19", listOf(6), "2026-09-13"))
        assertNull(rejectOffSeriesPlacement("Gym", "2026-09-21", listOf(3, 1, 5), "2026-09-13"))
        assertNull(rejectOffSeriesPlacement("Odd", "2026-09-20", emptyList(), "2026-09-13"))
        assertNull(rejectOffSeriesPlacement("Odd", "Sunday", listOf(6), "2026-09-13"))
        val many = rejectOffSeriesPlacement("Gym", "2026-09-22", listOf(5, 1, 3), "2026-09-22")!!
        assertTrue(many, many.contains("weekly on Monday, Wednesday and Friday leaves out Tuesdays"))
        assertTrue(many, many.contains("The nearest matching days: Wednesday 2026-09-23. "))
    }
}
