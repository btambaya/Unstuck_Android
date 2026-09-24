package tech.csalliance.unstuck.ui.insights

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Plan card's deadline line. The review counts a deadline as passed when
 * the task wasn't done by it — still open OR finished afterwards — so the page
 * must not call the late-finished ones "still open" (it did: "Due and still
 * open: Gym bag" for a task already ticked off).
 */
class InsightsCardsTest {
    @Test fun `a deadline finished late says so, an open one just gives its due day`() {
        assertEquals(
            "Went past their due dates: Invoice Acme (due Fri 18 Sep), Gym bag (due Thu 17 Sep, done later)",
            deadlineLine(listOf(DeadlineItem("Invoice Acme", "2026-09-18", false), DeadlineItem("Gym bag", "2026-09-17", true)), 2026),
        )
    }

    @Test fun `one deadline, then the first two and a count`() {
        assertEquals("Went past its due date: Tax return (due Tue 15 Sep)", deadlineLine(listOf(DeadlineItem("Tax  return ", "2026-09-15", false)), 2026))
        assertEquals(
            "Went past their due dates: A (due Mon 14 Sep), B (due Tue 15 Sep, done later) +1 more",
            deadlineLine(listOf(DeadlineItem("A", "2026-09-14", false), DeadlineItem("B", "2026-09-15", true), DeadlineItem("C", "2026-09-16", false)), 2026),
        )
        // Another year shows its year; an unreadable due day is left out.
        assertEquals("Went past its due date: Old (due Wed 31 Dec 2025)", deadlineLine(listOf(DeadlineItem("Old", "2025-12-31", false)), 2026))
        assertEquals("Went past its due date: Odd", deadlineLine(listOf(DeadlineItem("Odd", null, false)), 2026))
    }
}
