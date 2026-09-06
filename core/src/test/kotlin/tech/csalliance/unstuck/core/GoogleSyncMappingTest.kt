package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.blockToIsoRange
import tech.csalliance.unstuck.core.logic.diffMinutes
import tech.csalliance.unstuck.core.logic.externalEventToBlock
import tech.csalliance.unstuck.core.logic.incomingEventsToMirror
import tech.csalliance.unstuck.core.logic.isAllDayEvent
import tech.csalliance.unstuck.core.logic.staleExternalBlockIds
import tech.csalliance.unstuck.core.logic.isoToLocalHHMM
import tech.csalliance.unstuck.core.logic.isoToLocalYmd
import tech.csalliance.unstuck.core.model.CalBlock
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

    // --- pull filters (2026-09 round 2) -----------------------------------------

    @Test fun allDay_flagFromTheServerIsHonoured_evenWhenStartCarriesT00() {
        // The server normalises an all-day `date` into "YYYY-MM-DDT00:00:00", so the
        // old `contains('T')` filter was dead code — the explicit flag decides.
        val allDay = ev.copy(start = "2026-05-21T00:00:00", end = "2026-05-22T00:00:00", allDay = true)
        assertTrue(isAllDayEvent(allDay))
        assertTrue("a bare date start (older server) still counts", isAllDayEvent(ev.copy(start = "2026-05-21", allDay = null)))
        assertTrue("a timed event is not all-day", !isAllDayEvent(ev))
        assertEquals(emptyList<ExternalEvent>(), incomingEventsToMirror(listOf(allDay), emptySet()))
    }

    @Test fun incomingEvents_skipOurOwnPushedEvents() {
        val mine = ev.copy(id = "pushed-1")
        assertEquals(listOf(ev), incomingEventsToMirror(listOf(ev, mine), setOf("pushed-1")))
    }

    @Test fun staleReconcile_keepsBlocksOfAFailedConnection() {
        val ok = CalBlock(id = "g_a", taskId = null, taskName = "A", startTime = "09:00", durationMinutes = 30, date = "2026-05-21", externalEventId = "a", externalConnectionId = "conn-ok", kind = CalBlockKind.EXTERNAL)
        val failed = ok.copy(id = "g_b", externalEventId = "b", externalConnectionId = "conn-bad")
        val outside = ok.copy(id = "g_c", externalEventId = "c", date = "2026-09-01")
        val taskBlock = CalBlock(id = "t-b", taskId = "t", taskName = "T", startTime = "10:00", durationMinutes = 25, date = "2026-05-21")
        val stale = staleExternalBlockIds(
            local = listOf(ok, failed, outside, taskBlock), keepIds = emptySet(),
            fromYmd = "2026-05-14", toYmd = "2026-06-20", failedConnectionIds = setOf("conn-bad"),
        )
        assertEquals("only the healthy connection's missing block is reconciled away", listOf("g_a"), stale)
    }

    @Test fun staleReconcile_withNoFailures_dropsEveryMissingInWindowExternal() {
        val a = CalBlock(id = "g_a", taskId = null, taskName = "A", startTime = "09:00", durationMinutes = 30, date = "2026-05-21", externalEventId = "a", externalConnectionId = "conn", kind = CalBlockKind.EXTERNAL)
        val b = a.copy(id = "g_b", externalEventId = "b")
        assertEquals(listOf("g_b"), staleExternalBlockIds(listOf(a, b), setOf("g_a"), "2026-05-14", "2026-06-20", emptySet()))
    }
}
