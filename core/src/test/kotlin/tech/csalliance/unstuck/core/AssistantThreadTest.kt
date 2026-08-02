package tech.csalliance.unstuck.core

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.ASSISTANT_MAX_MODEL_WINDOW
import tech.csalliance.unstuck.core.logic.AssistantWindowTurn
import tech.csalliance.unstuck.core.logic.assistantDayLabel
import tech.csalliance.unstuck.core.logic.assistantModelWindow
import tech.csalliance.unstuck.core.logic.assistantPersistWindow
import tech.csalliance.unstuck.core.logic.fmtHrs
import tech.csalliance.unstuck.core.logic.shouldCheckIn
import tech.csalliance.unstuck.core.logic.usableToday
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind

// The ONE endless thread: display history stays long, the model window stays
// short and always starts at a user turn. Mirrors modelWindow() in
// lib/assistant/use-assistant.ts. Tests run with -Duser.timezone=UTC.
class AssistantThreadTest {

    private data class Turn(override val role: String, override val local: Boolean = false, val tag: String = "") :
        AssistantWindowTurn

    @Test fun `the window drops locally-injected turns entirely`() {
        val h = listOf(
            Turn("user", tag = "u1"),
            Turn("assistant", local = true, tag = "checkin"),
            Turn("assistant", tag = "a1"),
        )
        assertEquals(listOf("u1", "a1"), assistantModelWindow(h).map { it.tag })
    }

    @Test fun `the window is aligned to start at a user turn`() {
        // A tail that begins mid-exchange would resume from a dangling tool turn.
        val h = listOf(
            Turn("assistant", tag = "a0"),
            Turn("tool", tag = "t0"),
            Turn("user", tag = "u1"),
            Turn("assistant", tag = "a1"),
        )
        assertEquals(listOf("u1", "a1"), assistantModelWindow(h, max = 4).map { it.tag })
    }

    @Test fun `an all-assistant history with no user turn is passed through unchanged`() {
        val h = listOf(Turn("assistant", tag = "a0"), Turn("tool", tag = "t0"))
        assertEquals(listOf("a0", "t0"), assistantModelWindow(h).map { it.tag })
    }

    @Test fun `the window is capped and takes the TAIL`() {
        val h = (1..100).map { Turn("user", tag = "u$it") }
        val w = assistantModelWindow(h)
        assertEquals(ASSISTANT_MAX_MODEL_WINDOW, w.size)
        assertEquals("u100", w.last().tag)
        assertEquals("u61", w.first().tag)
    }

    @Test fun `display persistence is far longer than the model window and KEEPS local turns`() {
        val h = (1..500).map { Turn("assistant", local = it % 2 == 0, tag = "m$it") }
        val p = assistantPersistWindow(h)
        assertEquals(200, p.size)
        assertEquals("m500", p.last().tag)
        assertTrue(p.any { it.local })
    }

    @Test fun `day dividers read Today, Yesterday, then a dated label`() {
        val zone = ZoneId.of("UTC")
        val now = java.time.LocalDate.of(2026, 8, 2).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        fun at(y: Int, m: Int, d: Int) =
            java.time.LocalDate.of(y, m, d).atTime(9, 30).atZone(zone).toInstant().toEpochMilli()

        assertEquals("Today", assistantDayLabel(at(2026, 8, 2), now, zone))
        assertEquals("Yesterday", assistantDayLabel(at(2026, 8, 1), now, zone))
        assertEquals("Tue 28 Jul", assistantDayLabel(at(2026, 7, 28), now, zone))
        // Legacy turns without a timestamp get no divider rather than a wrong one.
        assertNull(assistantDayLabel(null, now, zone))
        assertNull(assistantDayLabel(0L, now, zone))
    }

    @Test fun `the check-in fires once per local day`() {
        assertTrue(shouldCheckIn(null, "2026-08-02"))
        assertTrue(shouldCheckIn("2026-08-01", "2026-08-02"))
        assertFalse(shouldCheckIn("2026-08-02", "2026-08-02"))
    }

    // ---- usable time (the context strip's USABLE piece) -------------------

    private fun blk(mins: Int, date: String = "2026-08-02", kind: CalBlockKind? = null, taskId: String? = "t") =
        CalBlock(
            id = "b$mins$date$kind", taskId = taskId, taskName = "x", startTime = "09:00",
            durationMinutes = mins, date = date, kind = kind,
        )

    @Test fun `usable time subtracts meetings and soft placeholders, and only counts today`() {
        val blocks = listOf(
            blk(60),                                        // real work
            blk(30, kind = CalBlockKind.EXTERNAL),          // a meeting
            blk(15, kind = CalBlockKind.PLACEHOLDER),       // a buffer
            blk(120, date = "2026-08-03"),                  // tomorrow — ignored
        )
        val u = usableToday(blocks, "2026-08-02")
        assertEquals(105, u.totalScheduled)
        assertEquals(30, u.meetingMins)
        assertEquals(15, u.bufferedMins)
        assertEquals(60, u.usableMins)
    }

    @Test fun `usable time never goes negative`() {
        val blocks = listOf(blk(30, kind = CalBlockKind.EXTERNAL), blk(30, kind = CalBlockKind.PLACEHOLDER))
        assertEquals(0, usableToday(blocks, "2026-08-02").usableMins)
        assertEquals(0, usableToday(emptyList(), "2026-08-02").usableMins)
    }

    @Test fun `fmtHrs matches the web strings exactly`() {
        assertEquals("45m", fmtHrs(45))
        assertEquals("1h", fmtHrs(60))
        assertEquals("2h 40m", fmtHrs(160))
        assertEquals("0m", fmtHrs(0))
    }
}
