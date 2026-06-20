package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.blockToIsoRange
import tech.csalliance.unstuck.core.logic.diffMinutes
import tech.csalliance.unstuck.core.logic.externalEventToBlock
import tech.csalliance.unstuck.core.logic.isoToLocalHHMM
import tech.csalliance.unstuck.core.logic.isoToLocalYmd
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.ExternalEvent

// Ported 1:1 from GoogleSyncMappingTests.swift / lib/sync/google-sync.test.ts.
class GoogleSyncMappingTest {
    private val ev = ExternalEvent(
        id = "goog_abc123", connectionId = "conn_1", calendarId = "primary",
        summary = "Standup", start = "2026-05-21T09:00:00.000Z", end = "2026-05-21T09:30:00.000Z",
    )

    @Test fun isoToLocalYmdAnchors() {
        assertTrue(Regex("^2026-05-2[01]$").matches(isoToLocalYmd("2026-05-21T12:00:00.000Z")))
    }

    @Test fun isoToLocalHHMMZeroPads() {
        assertTrue(Regex("^\\d{2}:\\d{2}$").matches(isoToLocalHHMM("2026-05-21T01:05:00.000Z")))
    }

    @Test fun diffMinutesClampsToMin15() {
        assertEquals(15, diffMinutes("2026-05-21T10:00:00.000Z", "2026-05-21T10:05:00.000Z"))
        assertEquals(90, diffMinutes("2026-05-21T10:00:00.000Z", "2026-05-21T11:30:00.000Z"))
    }

    @Test fun externalEventMarksExternalAndCarriesIds() {
        val b = externalEventToBlock(ev, "primary")
        assertEquals(CalBlockKind.EXTERNAL, b.kind)
        assertEquals("goog_abc123", b.externalEventId)
        assertEquals("conn_1", b.externalConnectionId)
        assertNull(b.taskId)
        assertEquals("Standup", b.taskName)
    }

    @Test fun externalEventStableGPrefixedId() {
        val a = externalEventToBlock(ev, "primary")
        val b = externalEventToBlock(ev, "primary")
        assertEquals(a.id, b.id)
        assertTrue(a.id.startsWith("g_"))
    }

    @Test fun externalEventUntitledFallback() {
        val empty = ev.copy(summary = "")
        assertEquals("(untitled)", externalEventToBlock(empty, "primary").taskName)
    }

    @Test fun externalEventDurationFloor() {
        val short = ev.copy(end = ev.start)
        assertEquals(15, externalEventToBlock(short, "primary").durationMinutes)
    }

    @Test fun blockToIsoRangeBuildsUtcRange() {
        val b = mkBlock(taskId = "t", startTime = "09:00", durationMinutes = 90, date = "2026-05-21")
        val (start, end) = blockToIsoRange(b)
        assertEquals("2026-05-21T09:00:00.000Z", start)
        assertEquals("2026-05-21T10:30:00.000Z", end)
    }

    @Test fun blockToIsoRangeClampsOutOfRangeTimeNoThrow() {
        // A corrupt stored time ("24:00" / "25:70") must not throw DateTimeException on
        // the Google push path — clamp the hour/minute into a valid clock instead.
        val b = mkBlock(taskId = "t", startTime = "25:70", durationMinutes = 30, date = "2026-05-21")
        val (start, end) = blockToIsoRange(b)
        // 23:59 (clamped) + 30 min spills into the next UTC day; just assert valid ISO.
        assertTrue(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$").matches(start))
        assertTrue(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$").matches(end))
    }

    @Test fun blockToIsoRangeInvalidDateFallsBackNoThrow() {
        // Month 13 is unrepresentable — must fall back to the raw date, not throw.
        val b = mkBlock(taskId = "t", startTime = "09:00", durationMinutes = 30, date = "2026-13-40")
        val (start, end) = blockToIsoRange(b)
        assertEquals("2026-13-40", start)
        assertEquals("2026-13-40", end)
    }
}
