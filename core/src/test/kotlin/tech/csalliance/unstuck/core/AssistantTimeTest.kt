package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.AssistantClock
import tech.csalliance.unstuck.core.logic.FreeWindow
import tech.csalliance.unstuck.core.logic.IsoDate
import tech.csalliance.unstuck.core.logic.doneWhenLabel
import tech.csalliance.unstuck.core.logic.freeWindowsToday
import tech.csalliance.unstuck.core.logic.jsDayOfWeek
import tech.csalliance.unstuck.core.logic.localNowHM
import tech.csalliance.unstuck.core.logic.nowNote
import tech.csalliance.unstuck.core.logic.rejectPastDate
import tech.csalliance.unstuck.core.logic.rejectPastTime
import tech.csalliance.unstuck.core.logic.upcomingDates
import tech.csalliance.unstuck.core.logic.weekdayName
import tech.csalliance.unstuck.core.model.CalBlock

// Ported from lib/assistant/time-guard.test.ts (the time half) plus the
// `upcoming` semantics of buildAssistantContext / rejectPastDate in tools.ts
// (via the iOS AssistantTimeTests). Times are LOCAL; the suite runs under
// TZ=UTC so local == UTC.
class AssistantTimeTest {

    private val TODAY = "2026-09-02"   // a Wednesday

    private data class B(val startTime: String = "10:00", val durationMinutes: Int = 30, val date: String = "2026-09-02", val done: Boolean = false)

    /** The web `api(blocks)` helper: every block defaults to today 10:00 × 30 min. */
    private fun blocks(vararg overrides: B): List<CalBlock> = overrides.mapIndexed { i, b ->
        CalBlock(id = "b$i", taskId = "t$i", taskName = "x", startTime = b.startTime, durationMinutes = b.durationMinutes, date = b.date, done = b.done)
    }

    // ---- time cognisance (tester: "it suggested 10am at 3pm", 2026-09-02)

    @Test fun `localNowHM is the local wall clock HH-MM`() {
        assertEquals("15:07", localNowHM(localMillis(2026, 9, 2, 15, 7)))
        assertEquals("09:00", localNowHM(localMillis(2026, 9, 2, 9, 0)))
    }

    @Test fun `the injectable clock reads today and now from the same instant`() {
        val clock = AssistantClock.fixed(localMillis(2026, 9, 2, 15, 7))
        assertEquals("2026-09-02", clock.todayIso())
        assertEquals("15:07", clock.nowHM())
    }

    @Test fun `free windows start at the next quarter hour after now and skip blocks`() {
        assertEquals(
            listOf(FreeWindow("15:15", "16:00"), FreeWindow("17:00", "21:00")),
            freeWindowsToday(blocks(B("16:00", durationMinutes = 60)), TODAY, "15:07"),
        )
    }

    @Test fun `ignores done, skipped, other-day blocks and yields nothing late at night`() {
        assertEquals(
            listOf(FreeWindow("15:15", "21:00")),
            freeWindowsToday(blocks(B("16:00", done = true), B("17:00", date = "2026-09-03")), TODAY, "15:07"),
        )
        assertEquals(emptyList<FreeWindow>(), freeWindowsToday(emptyList(), TODAY, "20:50"))
    }

    @Test fun `at most four windows and only gaps of twenty minutes or more`() {
        val busy = blocks(B("09:30"), B("10:30"), B("11:30"), B("12:30"), B("13:30"), B("14:10", durationMinutes = 10))
        val w = freeWindowsToday(busy, TODAY, "08:00")
        assertEquals(4, w.size)
        assertEquals(FreeWindow("08:15", "09:30"), w.first())
        // 14:00–14:10 is only ten minutes — never offered.
        assertFalse(w.any { it.from == "14:00" })
    }

    @Test fun `refuses a time today that has already passed with what is free`() {
        val err = rejectPastTime(blocks(B("16:00")), TODAY, TODAY, "10:00", "15:07")
        assertNotNull(err)
        assertTrue(err!!, err.startsWith("error: 10:00 today is already past (it's 15:07 now)"))
        assertTrue(err, err.contains("free today: 15:15–16:00, 16:30–21:00"))
        assertEquals("error: 10:00 today is already past (it's 15:07 now). Ask for a later time or another day — free today: 15:15–16:00, 16:30–21:00.", err)
    }

    @Test fun `allows a later time today, any time another day, and no time at all`() {
        assertNull(rejectPastTime(emptyList(), TODAY, TODAY, "15:30", "15:07"))
        assertNull(rejectPastTime(emptyList(), TODAY, "2026-09-03", "10:00", "15:07"))
        assertNull(rejectPastTime(emptyList(), TODAY, TODAY, null, "15:07"))
        assertNull(rejectPastTime(emptyList(), TODAY, TODAY, "", "15:07"))
    }

    @Test fun `exactly now counts as past`() {
        assertNotNull(rejectPastTime(emptyList(), TODAY, TODAY, "15:07", "15:07"))
    }

    @Test fun `says nothing is left when the evening is gone`() {
        val err = rejectPastTime(emptyList(), TODAY, TODAY, "20:00", "20:50")
        assertTrue(err?.contains("nothing usable is left today") ?: false)
        assertEquals("error: 20:00 today is already past (it's 20:50 now). Ask for a later time or another day — nothing usable is left today — offer tomorrow.", err)
    }

    // ---- past dates ("Monday" → last Monday, 2026-09-02)

    @Test fun `rejectPastDate names the coming weekday verbatim`() {
        // Mon 31 Aug from Wed 2 Sept → next Monday is 7 Sept.
        assertEquals(
            "error: 2026-08-31 is in the PAST (today is 2026-09-02). If the user meant the coming Monday, use 2026-09-07 — see context.upcoming. Never schedule into the past.",
            rejectPastDate(TODAY, "2026-08-31"),
        )
        // Same weekday last week → a full week ahead, never "today".
        assertEquals(
            "error: 2026-08-26 is in the PAST (today is 2026-09-02). If the user meant the coming Wednesday, use 2026-09-09 — see context.upcoming. Never schedule into the past.",
            rejectPastDate(TODAY, "2026-08-26"),
        )
    }

    @Test fun `rejectPastDate allows today and the future and refuses bad shapes`() {
        assertNull(rejectPastDate(TODAY, TODAY))
        assertNull(rejectPastDate(TODAY, "2026-12-25"))
        assertEquals("error: date must be YYYY-MM-DD (got \"Monday\")", rejectPastDate(TODAY, "Monday"))
        assertEquals("error: date must be YYYY-MM-DD (got \"2026-9-2\")", rejectPastDate(TODAY, "2026-9-2"))
    }

    // ---- upcoming — the dates the model must COPY

    @Test fun `upcoming dates from a Wednesday`() {
        val u = upcomingDates(TODAY)
        assertEquals("2026-09-03", u["tomorrow"])
        assertEquals("2026-09-03", u["thursday"])
        assertEquals("2026-09-04", u["friday"])
        assertEquals("2026-09-05", u["saturday"])
        assertEquals("2026-09-06", u["sunday"])
        assertEquals("2026-09-07", u["monday"])
        assertEquals("2026-09-08", u["tuesday"])
        // Today's own weekday resolves to NEXT week, never today.
        assertEquals("2026-09-09", u["wednesday"])
        assertEquals("2026-09-07", u["next_week_monday"])
        assertEquals(9, u.size)
    }

    @Test fun `upcoming from a Sunday rolls next_week_monday correctly`() {
        val u = upcomingDates("2026-09-06")
        assertEquals("2026-09-07", u["monday"])
        assertEquals("2026-09-07", u["next_week_monday"])   // Sunday's week started Mon 31 Aug
        assertEquals("2026-09-13", u["sunday"])
    }

    @Test fun `weekdayName and nowNote`() {
        assertEquals("wednesday", weekdayName(TODAY))
        assertEquals("sunday", weekdayName("2026-09-06"))
        assertEquals("it is 15:07 on wednesday — times earlier than this today are already gone", nowNote(TODAY, "15:07"))
    }

    // ---- IsoDate plumbing

    @Test fun `IsoDate helpers never drift through UTC`() {
        assertEquals("2026-09-01", IsoDate.addDays("2026-08-31", 1))
        assertEquals("2026-02-28", IsoDate.addDays("2026-03-01", -1))
        assertEquals("2026-08-31", IsoDate.mondayOf("2026-09-06"))   // Sunday → its Monday
        assertEquals("2026-08-31", IsoDate.mondayOf("2026-08-31"))
        assertEquals(3, IsoDate.dayOfWeek("2026-09-02"))
        assertEquals(3, jsDayOfWeek("2026-09-02"))
        assertEquals(0, jsDayOfWeek("nope"))
        assertEquals(5, IsoDate.daysUntil("2026-08-24", "2026-08-29"))
        assertEquals("2026-08-23", IsoDate.dateOfStamp("2026-08-23T18:00:00"))
        assertEquals("2026-08-23", IsoDate.dateOfStamp("2026-08-23T18:00:00.000Z"))
        assertNull(IsoDate.dateOfStamp("nope"))
        assertNull(IsoDate.dateOfStamp(null))
        assertNull(IsoDate.parse("2026-02-30"))
    }

    // ---- doneWhenLabel (iOS build 75, f125845): when a get_tasks line happened

    @Test fun `doneWhenLabel says today, yesterday, or a short weekday date`() {
        assertEquals("today", doneWhenLabel("2026-09-02T12:00:00.000Z", TODAY))
        assertEquals("yesterday", doneWhenLabel("2026-09-01T12:00:00.000Z", TODAY))
        assertEquals("Mon 5 Jan", doneWhenLabel("2026-01-05T12:00:00.000Z", TODAY))
        assertEquals("Sat 19 Sep", doneWhenLabel("2026-09-19T08:00:00Z", TODAY))
        assertNull(doneWhenLabel(null, TODAY))
        assertNull(doneWhenLabel("", TODAY))
        assertNull(doneWhenLabel("nope", TODAY))
    }

    @Test fun `doneWhenLabel reads a server stamp and judges the LOCAL day`() {
        // PostgREST's shape (+00:00, microseconds) parses like the app's own Z form.
        assertEquals("today", doneWhenLabel("2026-09-02T09:30:00.123456+00:00", TODAY))
        // 23:30 UTC on the 1st is already the 2nd in Lagos (UTC+1).
        val lagos = java.time.ZoneId.of("Africa/Lagos")
        assertEquals("today", doneWhenLabel("2026-09-01T23:30:00.000Z", TODAY, lagos))
        assertEquals("yesterday", doneWhenLabel("2026-09-01T23:30:00.000Z", TODAY))
    }
}
