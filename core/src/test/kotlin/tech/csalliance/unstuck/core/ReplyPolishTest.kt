package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.PolishOptions
import tech.csalliance.unstuck.core.logic.polishReply
import tech.csalliance.unstuck.core.logic.spokenDate
import tech.csalliance.unstuck.core.logic.spokenTime
import tech.csalliance.unstuck.core.time.Time
import java.time.ZoneId
import java.time.ZonedDateTime

// Ported 1:1 from lib/assistant/polish.test.ts — the deterministic polish
// layer over the model's FINAL chat text. "before" texts marked (battery)
// are verbatim live replies from the naturalness diagnosis; "after" is what
// the deterministic layer can reach (the prose rewrites need the model).
class ReplyPolishTest {

    // Pinned "now" for the this-year rule (a Sunday in September 2026, UTC).
    private val opts = PolishOptions(nowMs = Time.parseMillis("2026-09-06T12:00:00Z")!!, zone = ZoneId.of("UTC"))

    private fun polish(s: String) = polishReply(s, opts)

    // (name, before, after)
    private val vectors: List<Triple<String, String, String>> = listOf(
        // --- 1. openers
        Triple("battery 3: Done — dash", "Done — skipped gym for today.", "Skipped gym for today."),
        Triple("battery 4: Done — long", "Done — removed the dentist appointment from Thursday's schedule, but kept the task active.", "Removed the dentist appointment from Thursday's schedule, but kept the task active."),
        Triple("battery 9: Done — reminders", "Done — reminders set to 15 minutes before each task.", "Reminders set to 15 minutes before each task."),
        Triple("battery 1: capture (quoted name kept)", "Done — added capture \"ask Sam about the deck\" to Project check-in.", "Added capture \"ask Sam about the deck\" to Project check-in."),
        Triple("battery 10: blocked (quoted name kept)", "Done — blocked \"School play\" for 1 hour tomorrow at 6pm.", "Blocked \"School play\" for 1 hour tomorrow at 6pm."),
        Triple("battery 8: opener before a quote — no capitalising inside the quote", "Done — \"Milk\" is now checked off your Groceries list.", "\"Milk\" is now checked off your Groceries list."),
        Triple("Done. full stop", "Done. Moved the report to Friday.", "Moved the report to Friday."),
        Triple("Sure thing —", "Sure thing — booked it.", "Booked it."),
        Triple("case-insensitive opener with comma", "okay, moved it to 4pm.", "Moved it to 4pm."),
        Triple("stacked openers", "Sure — done — moved it.", "Moved it."),
        Triple("Got it. with a question", "Got it. Who's Maleek?", "Who's Maleek?"),
        Triple("\"Great question\" is not an opener", "Great question — the report is Friday.", "Great question — the report is Friday."),
        // --- 2. closers
        Triple("battery 7: opener + generic closer", "Done — your week is open. Let me know if you'd like help adjusting anything on it!", "Your week is open."),
        Triple("battery 11: closer without a weekday", "Done — set to 4 hours on weekdays. Let me know if you'd like to adjust weekend time too.", "Set to 4 hours on weekdays."),
        Triple("battery 5: multi-item list kept, closer dropped", "Your backlog has two tasks:\n\n- Project check-in (30 minutes, Work)\n- Write the report (50 minutes, Work)\n\nLet me know if you'd like to schedule either of these or move them into active work!", "Your backlog has two tasks:\n\n- Project check-in (30 minutes, Work)\n- Write the report (50 minutes, Work)"),
        Triple("generic \"Anything else?\" dropped", "Moved it to 4pm. Anything else?", "Moved it to 4pm."),
        Triple("\"Hope that helps!\" dropped", "The report is Friday at 2pm. Hope that helps!", "The report is Friday at 2pm."),
        Triple("closer-only reply stays", "Let me know if you need anything else!", "Let me know if you need anything else!"),
        Triple("specific offer with a weekday kept", "Moved it to 4pm. Want me to move the report to tomorrow?", "Moved it to 4pm. Want me to move the report to tomorrow?"),
        Triple("specific offer with a real object kept", "Two things — the project check-in and the report. Want either on the calendar?", "Two things — the project check-in and the report. Want either on the calendar?"),
        Triple("closer naming a quoted task kept", "Booked Thursday. Let me know if \"Report\" should move too.", "Booked Thursday. Let me know if \"Report\" should move too."),
        Triple("two stacked closers both dropped", "Booked Thursday 2pm. Feel free to change it. Hope this helps!", "Booked Thursday 2pm."),
        // --- 3. exclamation restraint
        Triple("battery 2: confirmation cheer", "Focus session started on \"Write the report\" for 50 minutes — you're all set!", "Focus session started on \"Write the report\" for 50 minutes — you're all set."),
        Triple("greeting keeps its \"!\", confirmation loses it", "Hey Maya! Added the report for tomorrow!", "Hey Maya! Added the report for tomorrow."),
        Triple("no confirmation verb → \"!\" stays", "Happy birthday!", "Happy birthday!"),
        Triple("\"!\" inside quotes untouched", "Added \"Call mum!\" for tonight!", "Added \"Call mum!\" for tonight."),
        // --- 4. dates and times
        Triple("voice bonus: ISO date + 24h time", "Done — scheduled Dentist on 2026-09-04 at 14:00.", "Scheduled Dentist on Fri 4 Sep at 2pm."),
        Triple("half hour, midnight, noon, leading zero", "Slots: 14:30, 00:30, 12:00 and 09:05.", "Slots: 2:30pm, 12:30am, 12pm and 9:05am."),
        Triple("other year keeps the year", "Booked for 2027-01-03.", "Booked for Sun 3 Jan 2027."),
        Triple("time range", "Blocked 14:00-15:00 tomorrow.", "Blocked 2pm-3pm tomorrow."),
        Triple("invalid date, 1-digit hour, explicit pm, seconds untouched", "Not 2026-13-45, nor 9:05, nor 10:30 pm, nor 14:00:00.", "Not 2026-13-45, nor 9:05, nor 10:30 pm, nor 14:00:00."),
        Triple("id= token protected, prose converted", "Created task id=2026-09-05-14:00 for 2026-09-05 at 14:00.", "Created task id=2026-09-05-14:00 for Sat 5 Sep at 2pm."),
        Triple("URL protected", "See https://unstucknow.io/t/2026-09-05?at=14:00 — booked for 2026-09-05.", "See https://unstucknow.io/t/2026-09-05?at=14:00 — booked for Sat 5 Sep."),
        Triple("backticks and quotes protected", "Renamed `2026-09-05 14:00` to \"Done — 2026-09-05 14:00\".", "Renamed `2026-09-05 14:00` to \"Done — 2026-09-05 14:00\"."),
        Triple("ISO datetime is not a standalone token", "Synced at 2026-09-05T14:00:00Z.", "Synced at 2026-09-05T14:00:00Z."),
        // --- 5. markdown residue
        Triple("bold collapsed", "**Milk** is ticked off.", "Milk is ticked off."),
        Triple("single-item bullet becomes a sentence", "One thing in your backlog:\n- Write the report (50 min)", "One thing in your backlog: Write the report (50 min)."),
        Triple("multi-item list untouched", "Today:\n- Gym at 4pm\n- Dentist at 5pm", "Today:\n- Gym at 4pm\n- Dentist at 5pm"),
        // --- 6. whitespace
        Triple("doubled spaces collapsed, trimmed", "  Booked  it  for Friday.  ", "Booked it for Friday."),
        // --- unchanged / guards
        Triple("battery 12: destructive confirmation untouched", "I'll help you delete your Health area. Before I do that, I need to confirm this action since it will remove the area but keep all tasks associated with it.\n\nAre you sure you want to delete the Health area?", "I'll help you delete your Health area. Before I do that, I need to confirm this action since it will remove the area but keep all tasks associated with it.\n\nAre you sure you want to delete the Health area?"),
        Triple("battery 6: insights read-back untouched", "You focused for 3h 20m across 5 sessions this week — median session was 40 minutes. You hit your estimates 60% of the time.", "You focused for 3h 20m across 5 sessions this week — median session was 40 minutes. You hit your estimates 60% of the time."),
        Triple("empty-guard: \"Done.\" alone stays", "Done.", "Done."),
        Triple("empty-guard: \"Done —\" alone stays", "Done —", "Done —"),
        Triple("error reply untouched", "Sorry — that didn't go through. Which day?", "Sorry — that didn't go through. Which day?"),
    )

    @Test fun `every vector`() {
        for ((name, before, after) in vectors) assertEquals(name, after, polish(before))
    }

    @Test fun `idempotent on every vector`() {
        for ((name, before, _) in vectors) {
            val once = polish(before)
            assertEquals("idempotence: $name", once, polish(once))
        }
    }

    @Test fun `never produces an empty string`() {
        assertEquals("", polish(""))
        assertEquals("   ", polish("   "))
        assertEquals("Sure!", polish("Sure!"))
        assertEquals("Okay —", polish("Okay —  "))
    }

    @Test fun `defaults now to the current year`() {
        val y = ZonedDateTime.now().year
        assertFalse(polishReply("Booked for $y-03-01.").contains(y.toString()))
    }

    @Test fun `spokenDate and spokenTime helpers`() {
        assertEquals("Sat 5 Sep", spokenDate(2026, 9, 5, 2026))
        assertEquals("Thu 29 Feb 2024", spokenDate(2024, 2, 29, 2026))
        assertNull(spokenDate(2026, 2, 29, 2026))
        assertEquals("12am", spokenTime(0, 0))
        assertEquals("12pm", spokenTime(12, 0))
        assertEquals("11:59pm", spokenTime(23, 59))
    }

    /** Ahmad, 2026-09-24: a 24-hour phone keeps the reply's 24-hour time — the
     *  "2:30pm" rewrite made the chat the one 12-hour spot on a 24-hour phone.
     *  Dates are still spoken; a 12-hour phone keeps the reference rewrite. */
    @Test fun `a 24-hour phone keeps the reply's times`() {
        val h24 = opts.copy(clock = tech.csalliance.unstuck.core.time.ClockMode.H24)
        assertEquals("Gym is on Sat 5 Sep at 14:30.", polishReply("Gym is on 2026-09-05 at 14:30.", h24))
        assertEquals("Gym is on Sat 5 Sep at 2:30pm.", polishReply("Gym is on 2026-09-05 at 14:30.", opts))
        assertEquals("the reference default", tech.csalliance.unstuck.core.time.ClockMode.H12, PolishOptions().clock)
    }

    @Test fun `vector count covers the contract`() {
        assertTrue(vectors.size >= 25)
    }
}
