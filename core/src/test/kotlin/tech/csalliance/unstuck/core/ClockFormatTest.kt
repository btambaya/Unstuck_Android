package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.csalliance.unstuck.core.time.ClockFormat
import tech.csalliance.unstuck.core.time.ClockMode
import tech.csalliance.unstuck.core.time.ClockMode.H12
import tech.csalliance.unstuck.core.time.ClockMode.H24
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

// The ONE formatter for a clock time the user sees (Ahmad, 2026-09-24: "either
// 12 hour or 24h, not both"). Both modes, every entry point; the locale is
// explicit so the AM/PM symbols don't depend on the machine running the tests.
class ClockFormatTest {
    private val us = Locale.US

    @Test fun `time in both modes`() {
        assertEquals("14:30", ClockFormat.time(14, 30, H24, us))
        assertEquals("2:30 PM", ClockFormat.time(14, 30, H12, us))
        assertEquals("09:05", ClockFormat.time(9, 5, H24, us))
        assertEquals("9:05 AM", ClockFormat.time(9, 5, H12, us))
        assertEquals("midnight", "00:00", ClockFormat.time(0, 0, H24, us))
        assertEquals("midnight", "12:00 AM", ClockFormat.time(0, 0, H12, us))
        assertEquals("noon", "12:00", ClockFormat.time(12, 0, H24, us))
        assertEquals("noon", "12:00 PM", ClockFormat.time(12, 0, H12, us))
        assertEquals("12:15 AM", ClockFormat.time(0, 15, H12, us))
        assertEquals("11:59 PM", ClockFormat.time(23, 59, H12, us))
        assertEquals("23:59", ClockFormat.time(23, 59, H24, us))
    }

    @Test fun `a wire time string, and anything else kept as given`() {
        assertEquals("14:02", ClockFormat.time("14:02", H24, us))
        assertEquals("2:02 PM", ClockFormat.time("14:02", H12, us))
        assertEquals("seconds are ignored", "8:30 AM", ClockFormat.time("08:30:00", H12, us))
        assertEquals("an unpadded hour", "07:15", ClockFormat.time("7:15", H24, us))
        assertEquals("junk", ClockFormat.time("junk", H12, us))
        assertEquals("", ClockFormat.time("", H12, us))
        assertEquals("25:00", ClockFormat.time("25:00", H24, us))
    }

    @Test fun `an instant in a zone`() {
        val ms = Instant.parse("2026-09-24T13:02:00Z").toEpochMilli()
        val lagos = ZoneId.of("Africa/Lagos")   // UTC+1
        assertEquals("14:02", ClockFormat.time(ms, H24, lagos, us))
        assertEquals("2:02 PM", ClockFormat.time(ms, H12, lagos, us))
    }

    @Test fun `minutes of the day wrap past midnight`() {
        assertEquals("01:00", ClockFormat.ofMinutes(25 * 60, H24, us))
        assertEquals("11:59 PM", ClockFormat.ofMinutes(-1, H12, us))
    }

    @Test fun `whole hours for the grid labels are never a bare number`() {
        assertEquals("14:00", ClockFormat.hour(14, H24, us))
        assertEquals("2 PM", ClockFormat.hour(14, H12, us))
        assertEquals("00:00", ClockFormat.hour(0, H24, us))
        assertEquals("12 AM", ClockFormat.hour(0, H12, us))
        assertEquals("12 PM", ClockFormat.hour(12, H12, us))
        assertEquals("the end of a day reads as midnight", "00:00", ClockFormat.hour(24, H24, us))
    }

    @Test fun `compact hours keep the heatmap's old 12-hour shape`() {
        assertEquals("6am", ClockFormat.compactHour(6, H12, us))
        assertEquals("12pm", ClockFormat.compactHour(12, H12, us))
        assertEquals("12am", ClockFormat.compactHour(0, H12, us))
        assertEquals("06:00", ClockFormat.compactHour(6, H24, us))
        assertEquals("18:00", ClockFormat.compactHour(18, H24, us))
    }

    @Test fun `ranges use one meridiem when both ends share it`() {
        assertEquals("14:00–15:30", ClockFormat.range("14:00", "15:30", H24, us))
        assertEquals("2:00–3:30 PM", ClockFormat.range("14:00", "15:30", H12, us))
        assertEquals("11:30 AM–1:00 PM", ClockFormat.range("11:30", "13:00", H12, us))
        assertEquals("overnight", "10:00 PM–2:00 AM", ClockFormat.range("22:00", "02:00", H12, us))
        assertEquals("overnight", "22:00–02:00", ClockFormat.range("22:00", "02:00", H24, us))
        assertEquals("an end that isn't a time", "8:00 AM–junk", ClockFormat.range("08:00", "junk", H12, us))
        assertEquals("by minutes, past midnight", "23:30–00:30", ClockFormat.range(23 * 60 + 30, 24 * 60 + 30, H24, us))
    }

    @Test fun `a block's span is its start plus its length`() {
        assertEquals("09:00–09:45", ClockFormat.span("09:00", 45, H24, us))
        assertEquals("9:00–9:45 AM", ClockFormat.span("09:00", 45, H12, us))
        assertEquals("11:30 AM–12:15 PM", ClockFormat.span("11:30", 45, H12, us))
    }

    @Test fun `the locale's own AM and PM symbols`() {
        val uk = Locale.UK
        val (am, pm) = ClockFormat.amPm(uk)
        assertEquals("2:30 $pm", ClockFormat.time(14, 30, H12, uk))
        assertEquals("9:00 $am", ClockFormat.time(9, 0, H12, uk))
        assertEquals("2${pm.lowercase(uk)}", ClockFormat.compactHour(14, H12, uk))
        assertEquals("AM" to "PM", ClockFormat.amPm(us))
    }

    @Test fun `wire times inside machine text follow the mode`() {
        val refusal = "calls can only be booked between 06:00 and 23:00 — suggest a time inside that window"
        assertEquals("24-hour is the identity", refusal, ClockFormat.localizeTimes(refusal, H24, us))
        assertEquals(
            "calls can only be booked between 6:00 AM and 11:00 PM — suggest a time inside that window",
            ClockFormat.localizeTimes(refusal, H12, us),
        )
        assertEquals("booked 2026-09-07 2:31 PM", ClockFormat.localizeTimes("booked 2026-09-07 14:31", H12, us))
        assertEquals("an ISO stamp is left alone", "at 2026-09-07T14:31:00Z", ClockFormat.localizeTimes("at 2026-09-07T14:31:00Z", H12, us))
        assertEquals("a minute-precision ISO stamp too", "at 2026-09-07T14:31Z", ClockFormat.localizeTimes("at 2026-09-07T14:31Z", H12, us))
        assertEquals("a ratio or score is left alone", "ran 3:1 and 99:99", ClockFormat.localizeTimes("ran 3:1 and 99:99", H12, us))
        assertEquals(
            "ranges go through range()",
            "outside this phone's call hours (8:00 AM–9:00 PM; the latest it rings is 8:59 PM) or (8:00–11:00 AM)",
            ClockFormat.localizeTimes("outside this phone's call hours (08:00–21:00; the latest it rings is 20:59) or (08:00-11:00)", H12, us),
        )
        val shown = "11:10 PM is outside (10:00–11:00 PM)"
        assertEquals("text already in 12-hour form is never converted twice", shown, ClockFormat.localizeTimes(shown, H12, us))
        assertEquals("14:00 and 15:00", ClockFormat.localizeTimes("14:00 and 15:00", H24, us))
        assertEquals("2:00 PM and 3:00 PM", ClockFormat.localizeTimes("14:00 and 15:00", H12, us))
    }

    @Test fun `digits stay ASCII on a phone that writes its own`() {
        val prev = Locale.getDefault()
        try {
            for (tag in listOf("ar-EG", "fa-IR", "bn-BD")) {
                val l = Locale.forLanguageTag(tag)
                Locale.setDefault(l)
                assertEquals(tag, "14:05", ClockFormat.time(14, 5, H24))
                val h12 = ClockFormat.time(14, 5, H12)
                assertEquals(tag, "2:05", h12.substringBefore(' '))
            }
        } finally {
            Locale.setDefault(prev)
        }
    }

    @Test fun `minutesOf reads the wire shapes and refuses the rest`() {
        assertEquals(14 * 60 + 2, ClockFormat.minutesOf("14:02"))
        assertEquals(8 * 60 + 30, ClockFormat.minutesOf("08:30:00.000".substringBefore('.')))
        assertEquals(7 * 60 + 15, ClockFormat.minutesOf(" 7:15 "))
        assertNull(ClockFormat.minutesOf("24:00"))
        assertNull(ClockFormat.minutesOf("14:5"))
        assertNull(ClockFormat.minutesOf("anytime"))
        assertNull(ClockFormat.minutesOf(""))
    }

    @Test fun `both modes are exactly two`() {
        assertEquals(listOf(H12, H24), ClockMode.entries.toList())
    }
}
