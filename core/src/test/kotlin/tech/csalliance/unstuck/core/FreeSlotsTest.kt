package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.blockTimeRange
import tech.csalliance.unstuck.core.logic.findConflicts
import tech.csalliance.unstuck.core.logic.findFreeSlots
import tech.csalliance.unstuck.core.logic.findFreeSlotsForDate
import tech.csalliance.unstuck.core.logic.formatTime

// Ported 1:1 from FreeSlotsTests.swift / lib/free-slots.test.ts. NOW is a
// local datetime; tests run under TZ=UTC.
class FreeSlotsTest {
    private val now = localMillis(2026, 5, 21, 7, 0) // before workday starts

    @Test fun emptyDayStartsAtDayStart() {
        val slots = findFreeSlots(emptyList(), 30, now, startDate = now, daysToScan = 1, limit = 3)
        assertTrue(slots.isNotEmpty())
        assertEquals("08:00", slots[0].startTime)
        assertEquals("2026-05-21", slots[0].date)
    }

    @Test fun skipsTooShortWindows() {
        val blocks = listOf(mkBlock(startTime = "08:15", durationMinutes = 45, date = "2026-05-21")) // 08:15–09:00
        val slots = findFreeSlots(blocks, 30, now, startDate = now, daysToScan = 1, limit = 3)
        assertEquals("09:00", slots[0].startTime)
    }

    @Test fun limitsResults() {
        val slots = findFreeSlots(emptyList(), 15, now, startDate = now, daysToScan = 1, limit = 2)
        assertEquals(2, slots.size)
    }

    @Test fun todaySnapsToNext15MinAfterNowPlus5() {
        val lateNow = localMillis(2026, 5, 21, 8, 7) // 08:07 → next slot 08:15
        val slots = findFreeSlots(emptyList(), 15, lateNow, startDate = lateNow, daysToScan = 1, limit = 1)
        assertEquals("08:15", slots[0].startTime)
    }

    @Test fun futureDateIndependentOfNow() {
        val slots = findFreeSlotsForDate(emptyList(), 25, "2026-06-01", now, limit = 3)
        assertEquals(3, slots.size)
        assertEquals("2026-06-01", slots[0].date)
        assertEquals("08:00", slots[0].startTime)
    }

    @Test fun nonPositiveDurationCoercedToOne() {
        // durationMin <= 0 would make `cursor + durationMin <= gapEnd` perpetually true
        // (garbage zero/negative-length slots). It must behave like a 1-minute duration.
        val zero = findFreeSlots(emptyList(), 0, now, startDate = now, daysToScan = 1, limit = 5)
        val one = findFreeSlots(emptyList(), 1, now, startDate = now, daysToScan = 1, limit = 5)
        assertEquals(one.map { it.startTime }, zero.map { it.startTime })
        assertEquals("08:00", zero[0].startTime)
        // A full-day block leaves NO room for even a 1-min slot.
        val full = listOf(mkBlock(startTime = "08:00", durationMinutes = 600, date = "2026-05-21")) // 08:00–18:00
        assertEquals(emptyList<Any>(), findFreeSlots(full, -10, now, startDate = now, daysToScan = 1, limit = 5))
    }

    @Test fun conflictsReturnsOverlappingBlocks() {
        val blocks = listOf(
            mkBlock(id = "a", startTime = "09:00", durationMinutes = 30, date = "2026-05-21"),
            mkBlock(id = "b", startTime = "09:30", durationMinutes = 60, date = "2026-05-21"),
            mkBlock(id = "c", startTime = "14:00", durationMinutes = 30, date = "2026-05-21"),
        )
        val out = findConflicts("2026-05-21", "09:15", 30, blocks)
        assertEquals(listOf("a", "b"), out.map { it.block.id })
        assertEquals(15, out[0].overlapMin)
    }

    @Test fun conflictsEmptyWhenNone() {
        val blocks = listOf(mkBlock(id = "a", startTime = "09:00", durationMinutes = 30, date = "2026-05-21"))
        assertEquals(emptyList<Any>(), findConflicts("2026-05-21", "10:00", 30, blocks))
    }

    @Test fun conflictsExcludesEditedBlock() {
        val blocks = listOf(mkBlock(id = "a", startTime = "09:00", durationMinutes = 30, date = "2026-05-21"))
        assertEquals(emptyList<Any>(), findConflicts("2026-05-21", "09:00", 30, blocks, excludeBlockId = "a"))
    }

    @Test fun conflictsIgnoresOtherDates() {
        val blocks = listOf(mkBlock(id = "a", startTime = "09:00", durationMinutes = 30, date = "2026-05-20"))
        assertEquals(emptyList<Any>(), findConflicts("2026-05-21", "09:00", 30, blocks))
    }

    @Test fun formatTime12Hour() {
        assertEquals("9:00 AM", formatTime("09:00"))
        assertEquals("2:30 PM", formatTime("14:30"))
        assertEquals("12:15 AM", formatTime("00:15"))
        assertEquals("12:00 PM", formatTime("12:00"))
    }

    @Test fun blockTimeRangeFormats() {
        val b = mkBlock(startTime = "09:00", durationMinutes = 60, date = "2026-05-21")
        assertEquals("9:00 AM–10:00 AM", blockTimeRange(b))
    }
}
