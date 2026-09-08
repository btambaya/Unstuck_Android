package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.MONTH_BUSY_FLOOR_MIN
import tech.csalliance.unstuck.core.logic.blockSlotText
import tech.csalliance.unstuck.core.logic.busyMinutesByDay
import tech.csalliance.unstuck.core.logic.busyScaleMax
import tech.csalliance.unstuck.core.logic.dayPeek
import tech.csalliance.unstuck.core.logic.peekDayTitle
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.core.model.SharedBlock

// The Month grid's pure pieces: the "how busy" heat scale and the day peek's
// three sections (1:1 with iOS MonthView / MonthDayPeekSheet, bba2e92).
class MonthPeekTest {

    private fun own(
        id: String, date: String, start: String = "09:00", min: Int = 45,
        kind: CalBlockKind? = CalBlockKind.TASK, taskId: String? = "t1", skipped: Boolean = false, done: Boolean = false,
    ) = CalBlock(
        id = id, taskId = taskId, taskName = "Block $id", startTime = start, durationMinutes = min,
        date = date, kind = kind, done = done, skipped = skipped,
    )

    private fun shared(
        id: String, date: String, start: String = "10:00", min: Int = 30,
        skipped: Boolean = false, kind: String = "task",
    ) = SharedBlock(
        blockId = id, taskId = "st1", shareId = "s1", level = ShareLevel.PARTNER, ownerName = "anna@example.com",
        title = "Shared $id", date = date, startTime = start, durationMinutes = min, done = false,
        skipped = skipped, kind = kind,
    )

    // ── Heat ────────────────────────────────────────────────────────────────

    @Test fun busyMinutesSumsOwnAndSharedPerDay() {
        val by = busyMinutesByDay(
            listOf(own("a", "2026-09-08", min = 45), own("b", "2026-09-08", min = 60), own("c", "2026-09-09", min = 25)),
            listOf(shared("s", "2026-09-08", min = 30)),
        )
        assertEquals(135, by["2026-09-08"])
        assertEquals(25, by["2026-09-09"])
    }

    @Test fun busyMinutesCountsEveryKindOfOwnBlockNotJustTasks() {
        // An external meeting eats the day just as much as a planned focus block.
        val by = busyMinutesByDay(
            listOf(own("m", "2026-09-08", min = 60, kind = CalBlockKind.EXTERNAL, taskId = null)),
            emptyList(),
        )
        assertEquals(60, by["2026-09-08"])
    }

    @Test fun busyMinutesDropsSkippedAndEmptyDays() {
        val by = busyMinutesByDay(
            listOf(own("a", "2026-09-08", skipped = true), own("z", "2026-09-10", min = 0)),
            listOf(shared("s", "2026-09-09", skipped = true)),
        )
        assertTrue(by.isEmpty())
    }

    @Test fun busyScaleHasAThreeHourFloorSoOneLongDayDoesNotFlattenTheMonth() {
        assertEquals(MONTH_BUSY_FLOOR_MIN, busyScaleMax(emptyMap()))
        assertEquals(180, busyScaleMax(mapOf("2026-09-08" to 45)))
        assertEquals(480, busyScaleMax(mapOf("2026-09-08" to 45, "2026-09-09" to 480)))
    }

    // ── Day peek ────────────────────────────────────────────────────────────

    @Test fun dayPeekSplitsPlannedSharedAndCalendarForThatDayOnly() {
        val blocks = listOf(
            own("p2", "2026-09-08", start = "14:00"),
            own("p1", "2026-09-08", start = "09:00"),
            own("ev", "2026-09-08", start = "11:00", kind = CalBlockKind.EXTERNAL, taskId = null),
            own("ph", "2026-09-08", start = "16:00", kind = CalBlockKind.PLACEHOLDER, taskId = null),
            own("other", "2026-09-09"),
        )
        val peek = dayPeek("2026-09-08", blocks, listOf(shared("s1", "2026-09-08"), shared("s2", "2026-09-09")))
        assertEquals(listOf("p1", "p2"), peek.planned.map { it.id })   // sorted by start
        assertEquals(listOf("ev", "ph"), peek.events.map { it.id })
        assertEquals(listOf("s1"), peek.shared.map { it.blockId })
        assertFalse(peek.isEmpty)
    }

    @Test fun dayPeekHidesSkippedOccurrencesOnBothLayers() {
        val peek = dayPeek(
            "2026-09-08",
            listOf(own("a", "2026-09-08", skipped = true)),
            listOf(shared("s", "2026-09-08", skipped = true)),
        )
        assertTrue(peek.isEmpty)
    }

    @Test fun dayPeekOnAnEmptyDayIsEmpty() {
        assertTrue(dayPeek("2026-09-08", emptyList(), emptyList()).isEmpty)
    }

    // ── Labels ──────────────────────────────────────────────────────────────

    @Test fun slotTextReadsTimeThenDuration() {
        assertEquals("09:00 · 45m", blockSlotText("09:00", 45))
        assertEquals("09:00 · 1h", blockSlotText("09:00", 60))
        assertEquals("09:00 · 1h 30m", blockSlotText("09:00", 90))
        assertEquals("09:00 · 0m", blockSlotText("09:00", 0))
    }

    @Test fun peekTitleIsWeekdayMonthDay() {
        assertEquals("Tue, Sep 8", peekDayTitle("2026-09-08"))
        assertEquals("Thu, Jan 1", peekDayTitle("2026-01-01"))
        assertEquals("nonsense", peekDayTitle("nonsense"))
    }
}
