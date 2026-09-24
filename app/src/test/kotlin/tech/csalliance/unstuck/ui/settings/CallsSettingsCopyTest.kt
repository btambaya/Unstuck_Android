package tech.csalliance.unstuck.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.csalliance.unstuck.core.logic.CallSettings
import tech.csalliance.unstuck.core.time.ClockMode
import tech.csalliance.unstuck.ui.TEST_CALL_CALLS_OFF
import tech.csalliance.unstuck.ui.TEST_CALL_LABEL
import tech.csalliance.unstuck.ui.TEST_CALL_NOTE
import tech.csalliance.unstuck.ui.TEST_CALL_OUTSIDE_HOURS

/** Settings → "Calls from Unstuck": the "Test call now" state mapping and the
 *  copy, verbatim from iOS CallSettingsView. */
class CallsSettingsCopyTest {

    @Test fun `a booked test call reports the time the row landed on`() {
        assertEquals(TestCallState.Booked("14:31"), testCallStateFrom("ok: call booked 2026-09-07 14:31 \"Test call\" (1 note) id=c9", ClockMode.H24))
        assertEquals("Booked — ringing at 14:31. Lock your phone and wait.", testCallBookedLine("14:31", ClockMode.H24))
    }

    @Test fun `a refused test call shows the guard's sentence`() {
        assertEquals(
            TestCallState.Failed("Calls can only be booked between 06:00 and 23:00 — suggest a time inside that window."),
            testCallStateFrom("error: calls can only be booked between 06:00 and 23:00 — suggest a time inside that window", ClockMode.H24),
        )
        assertEquals(TestCallState.Failed("Couldn't book the call — check your connection and try again."), testCallStateFrom("error: couldn't reach the server — try again", ClockMode.H24))
        val s = CallSettings(hoursStart = "08:00", hoursEnd = "21:00")
        assertEquals(
            TestCallState.Failed("23:10 is outside your allowed hours (08:00–21:00) — the phone would decline it quietly. Widen the hours above to try it now."),
            testCallStateFrom(TEST_CALL_OUTSIDE_HOURS("23:10", s), ClockMode.H24),
        )
        assertEquals(TestCallState.Failed("Calls are off on this phone — switch them on above to try it."), testCallStateFrom(TEST_CALL_CALLS_OFF, ClockMode.H24))
        assertEquals(
            "the end minute names the last one that rings (C12)",
            TestCallState.Failed("21:00 is outside your allowed hours (08:00–21:00; the latest it rings is 20:59) — the phone would decline it quietly. Widen the hours above to try it now."),
            testCallStateFrom(TEST_CALL_OUTSIDE_HOURS("21:00", s), ClockMode.H24),
        )
    }

    /** Ahmad, 2026-09-24: one rule app-wide — a 12-hour phone reads every time
     *  on this screen as 12-hour, the refusals it shows included. */
    @Test fun `a 12-hour phone reads the Calls screen's times its own way`() {
        val prev = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.US)
            val h12 = ClockMode.H12
            assertEquals("Booked — ringing at 2:31 PM. Lock your phone and wait.", testCallBookedLine("14:31", h12))
            assertEquals(
                "A call outside these hours is declined quietly and you get the notes as a notification instead. Calls can only be booked between 6:00 AM and 11:00 PM.",
                callsHoursHint(h12),
            )
            assertEquals(
                "All off unless you switch them on. Unstuck books them between 6:00 AM and 11:00 PM; this phone still declines one outside the allowed hours above, or while Calls is off.",
                callsProactiveHint(h12),
            )
            val s = CallSettings(hoursStart = "08:00", hoursEnd = "21:00")
            assertEquals(
                TestCallState.Failed("11:10 PM is outside your allowed hours (8:00 AM–9:00 PM) — the phone would decline it quietly. Widen the hours above to try it now."),
                testCallStateFrom(TEST_CALL_OUTSIDE_HOURS("23:10", s), h12),
            )
            assertEquals(
                "the end minute, the phone's way",
                TestCallState.Failed("9:00 PM is outside your allowed hours (8:00 AM–9:00 PM; the latest it rings is 8:59 PM) — the phone would decline it quietly. Widen the hours above to try it now."),
                testCallStateFrom(TEST_CALL_OUTSIDE_HOURS("21:00", s), h12),
            )
            assertEquals(
                "a range inside one half of the day keeps one meridiem",
                TestCallState.Failed("7:30 AM is outside your allowed hours (8:00–11:00 AM) — the phone would decline it quietly. Widen the hours above to try it now."),
                testCallStateFrom(TEST_CALL_OUTSIDE_HOURS("07:30", CallSettings(hoursStart = "08:00", hoursEnd = "11:00")), h12),
            )
            assertEquals(
                "the assistant's refusal shows the phone's clock too",
                TestCallState.Failed("Calls can only be booked between 6:00 AM and 11:00 PM — suggest a time inside that window."),
                testCallStateFrom("error: calls can only be booked between 06:00 and 23:00 — suggest a time inside that window", h12),
            )
            assertEquals("the booked time stays the wire time", TestCallState.Booked("14:31"), testCallStateFrom("ok: call booked 2026-09-07 14:31 \"Test call\" (1 note) id=c9", h12))
        } finally {
            java.util.Locale.setDefault(prev)
        }
    }

    @Test fun `the proactive calls and the ring nudge copy match iOS`() {
        assertEquals("Calls Unstuck can make on its own", CALLS_PROACTIVE_SECTION)
        assertEquals("Morning planning call", CALLS_PROACTIVE_MORNING)
        assertEquals("Rings to walk through the day and plan it with you.", CALLS_PROACTIVE_MORNING_SUB)
        assertEquals("Evening wrap-up call", CALLS_PROACTIVE_EVENING)
        assertEquals("Rings to go over what got done and what moves to tomorrow.", CALLS_PROACTIVE_EVENING_SUB)
        assertEquals("Check in after a block", CALLS_PROACTIVE_AFTER_BLOCK)
        assertEquals("Rings when a block ends without its task marked done — how did it go?", CALLS_PROACTIVE_AFTER_BLOCK_SUB)
        // The old "They ring within your allowed hours" was false (parity with iOS build 81, C12).
        assertEquals("All off unless you switch them on. Unstuck books them between 06:00 and 23:00; this phone still declines one outside the allowed hours above, or while Calls is off.", callsProactiveHint(ClockMode.H24))
        assertEquals("Calls need microphone access — turn it on in Android Settings, or you'll ring but can't be heard.", CALLS_MIC_DENIED_HINT)
        assertEquals("Calls need microphone access — turn it on for Unstuck in Android Settings.", CALLS_TEST_MIC_REFUSED)
        assertEquals("At", CALLS_PROACTIVE_AT)
        assertEquals("Calls need the full-screen permission on this phone — without it a call arrives as a notification you tap instead of a ring.", CALLS_FULL_SCREEN_NUDGE)
        assertEquals("Not now", CALLS_FULL_SCREEN_DISMISS)
        assertEquals("Allow full-screen calls", CALLS_FULL_SCREEN_ROW)
    }

    /** iOS shows the red mic line from the moment the screen opens when the mic
     *  is denied; Android can tell only while it still offers the prompt. */
    @Test fun `the mic line shows a refusal from before the screen opened, and a tap asks while Android still can`() {
        assertEquals(MicHint.NONE, callsMicHint(granted = true, canAskAgain = false, refusedHere = true))
        assertEquals("refused at a ring's Answer, before Settings opened", MicHint.ASK, callsMicHint(granted = false, canAskAgain = true, refusedHere = false))
        assertEquals(MicHint.ASK, callsMicHint(granted = false, canAskAgain = true, refusedHere = true))
        assertEquals("after don't-ask-again: the app's system page", MicHint.OPEN_SETTINGS, callsMicHint(granted = false, canAskAgain = false, refusedHere = true))
        assertEquals("never asked is not a refusal (the ring asks at Answer)", MicHint.NONE, callsMicHint(granted = false, canAskAgain = false, refusedHere = false))
    }

    @Test fun `copy matches iOS`() {
        assertEquals("Calls from Unstuck", CALLS_NAV_TITLE)
        assertEquals("Ask, and Unstuck calls you", CALLS_EXPLAINER_TITLE)
        assertEquals("A call outside these hours is declined quietly and you get the notes as a notification instead. Calls can only be booked between 06:00 and 23:00.", callsHoursHint(ClockMode.H24))
        assertEquals("\"Call me about this\" on a scheduled task rings this many minutes before it starts.", CALLS_LEAD_HINT)
        assertEquals("Book a test call for one minute from now. Lock your phone — it rings through the real path (server → push → call screen).", CALLS_TEST_BODY)
        assertEquals("Test call now", CALLS_TEST_BUTTON)
        assertEquals("Booking…", CALLS_TEST_BOOKING)
        assertEquals("Test call", TEST_CALL_LABEL)
        assertEquals("This is what a call from Unstuck sounds like", TEST_CALL_NOTE)
        assertEquals("unstuck_calls", CALLS_CHANNEL_ID)
    }
}
