package tech.csalliance.unstuck.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Where the calendar's "now" line goes (NowLine.kt), in any week, any zone. */
class NowLineLogicTest {

    /** The Monday-anchored week the Week view shows for [monday]. */
    private fun week(monday: String) = LocalDate.parse(monday).let { m -> (0..6).map { m.plusDays(it.toLong()) } }

    @Test fun `the minute down a full-day grid`() {
        assertEquals(0, nowGridMinute(LocalTime.of(0, 0), 0, 24))
        assertEquals(14 * 60 + 37, nowGridMinute(LocalTime.of(14, 37, 59), 0, 24))
        assertEquals(23 * 60 + 59, nowGridMinute(LocalTime.of(23, 59), 0, 24))
    }

    @Test fun `outside a narrower grid there is no line`() {
        assertNull(nowGridMinute(LocalTime.of(5, 59), 6, 23))
        assertEquals(0, nowGridMinute(LocalTime.of(6, 0), 6, 23))
        assertEquals(17 * 60, nowGridMinute(LocalTime.of(23, 0), 6, 23))
        assertNull(nowGridMinute(LocalTime.of(23, 1), 6, 23))
    }

    @Test fun `today's column in the visible week`() {
        val days = week("2026-09-21")
        assertEquals(WeekNowMark(0, 9 * 60), weekNowMark(LocalDateTime.of(2026, 9, 21, 9, 0), days, 0, 24))
        assertEquals(WeekNowMark(5, 14 * 60 + 30), weekNowMark(LocalDateTime.of(2026, 9, 26, 14, 30), days, 0, 24))
        assertEquals(WeekNowMark(6, 23 * 60 + 59), weekNowMark(LocalDateTime.of(2026, 9, 27, 23, 59), days, 0, 24))
    }

    @Test fun `no line when today is not in the visible week`() {
        val now = LocalDateTime.of(2026, 9, 26, 14, 30)
        assertNull("next week", weekNowMark(now, week("2026-09-28"), 0, 24))
        assertNull("last week", weekNowMark(now, week("2026-09-14"), 0, 24))
    }

    /** One instant, two phones: the line follows each phone's own wall clock —
     *  a different day column and a different minute. */
    @Test fun `every time zone reads its own wall clock`() {
        val instant = Instant.parse("2026-09-27T23:30:00Z").toEpochMilli()
        // Tokyo (UTC+9): Monday 28 Sep, 08:30 — the first column of the NEXT week.
        val tokyo = nowIn(instant, ZoneId.of("Asia/Tokyo"))
        assertEquals(WeekNowMark(0, 8 * 60 + 30), weekNowMark(tokyo, week("2026-09-28"), 0, 24))
        assertNull(weekNowMark(tokyo, week("2026-09-21"), 0, 24))
        // Los Angeles (UTC−7 in September): Sunday 27 Sep, 16:30 — the last column.
        val la = nowIn(instant, ZoneId.of("America/Los_Angeles"))
        assertEquals(WeekNowMark(6, 16 * 60 + 30), weekNowMark(la, week("2026-09-21"), 0, 24))
        // Kathmandu (UTC+5:45): a quarter-hour offset still lands on its own minute.
        val ktm = nowIn(instant, ZoneId.of("Asia/Kathmandu"))
        assertEquals(WeekNowMark(0, 5 * 60 + 15), weekNowMark(ktm, week("2026-09-28"), 0, 24))
    }

    /** The clocks going back: the repeated hour is drawn at its wall-clock time both times. */
    @Test fun `across a daylight-saving change the line follows the wall clock`() {
        val london = ZoneId.of("Europe/London")
        val first = nowIn(Instant.parse("2026-10-25T00:30:00Z").toEpochMilli(), london)   // 01:30 BST
        val second = nowIn(Instant.parse("2026-10-25T01:30:00Z").toEpochMilli(), london)  // 01:30 GMT
        val days = week("2026-10-19")
        assertEquals(WeekNowMark(6, 90), weekNowMark(first, days, 0, 24))
        assertEquals(WeekNowMark(6, 90), weekNowMark(second, days, 0, 24))
    }

    @Test fun `the ticker waits for the next minute to begin`() {
        val minute = Instant.parse("2026-09-26T14:30:00Z").toEpochMilli()
        assertEquals(60_000L, millisToNextMinute(minute))
        assertEquals(1L, millisToNextMinute(minute + 59_999))
        assertEquals(30_000L, millisToNextMinute(minute + 30_000))
    }
}
