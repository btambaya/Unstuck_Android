package tech.csalliance.unstuck.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.csalliance.unstuck.core.logic.CallSettings
import tech.csalliance.unstuck.ui.TEST_CALL_CALLS_OFF
import tech.csalliance.unstuck.ui.TEST_CALL_LABEL
import tech.csalliance.unstuck.ui.TEST_CALL_NOTE
import tech.csalliance.unstuck.ui.TEST_CALL_OUTSIDE_HOURS

/** Settings → "Calls from Unstuck": the "Test call now" state mapping and the
 *  copy, verbatim from iOS CallSettingsView. */
class CallsSettingsCopyTest {

    @Test fun `a booked test call reports the time the row landed on`() {
        assertEquals(TestCallState.Booked("14:31"), testCallStateFrom("ok: call booked 2026-09-07 14:31 \"Test call\" (1 note) id=c9"))
        assertEquals("Booked — ringing at 14:31. Lock your phone and wait.", testCallBookedLine("14:31"))
    }

    @Test fun `a refused test call shows the guard's sentence`() {
        assertEquals(
            TestCallState.Failed("Calls can only be booked between 06:00 and 23:00 — suggest a time inside that window."),
            testCallStateFrom("error: calls can only be booked between 06:00 and 23:00 — suggest a time inside that window"),
        )
        assertEquals(TestCallState.Failed("Couldn't book the call — check your connection and try again."), testCallStateFrom("error: couldn't reach the server — try again"))
        val s = CallSettings(hoursStart = "08:00", hoursEnd = "21:00")
        assertEquals(
            TestCallState.Failed("23:10 is outside your allowed hours (08:00–21:00) — the phone would decline it quietly. Widen the hours above to try it now."),
            testCallStateFrom(TEST_CALL_OUTSIDE_HOURS("23:10", s)),
        )
        assertEquals(TestCallState.Failed("Calls are off on this phone — switch them on above to try it."), testCallStateFrom(TEST_CALL_CALLS_OFF))
    }

    @Test fun `the proactive calls and the ring nudge copy match iOS`() {
        assertEquals("Calls Unstuck can make on its own", CALLS_PROACTIVE_SECTION)
        assertEquals("Morning planning call", CALLS_PROACTIVE_MORNING)
        assertEquals("Rings to walk through the day and plan it with you.", CALLS_PROACTIVE_MORNING_SUB)
        assertEquals("Evening wrap-up call", CALLS_PROACTIVE_EVENING)
        assertEquals("Rings to go over what got done and what moves to tomorrow.", CALLS_PROACTIVE_EVENING_SUB)
        assertEquals("Check in after a block", CALLS_PROACTIVE_AFTER_BLOCK)
        assertEquals("Rings when a block ends without its task marked done — how did it go?", CALLS_PROACTIVE_AFTER_BLOCK_SUB)
        assertEquals("All off unless you switch them on. They ring within your allowed hours, on every phone where calls are on.", CALLS_PROACTIVE_HINT)
        assertEquals("At", CALLS_PROACTIVE_AT)
        assertEquals("Calls need the full-screen permission on this phone — without it a call arrives as a notification you tap instead of a ring.", CALLS_FULL_SCREEN_NUDGE)
        assertEquals("Not now", CALLS_FULL_SCREEN_DISMISS)
        assertEquals("Allow full-screen calls", CALLS_FULL_SCREEN_ROW)
    }

    @Test fun `copy matches iOS`() {
        assertEquals("Calls from Unstuck", CALLS_NAV_TITLE)
        assertEquals("Ask, and Unstuck calls you", CALLS_EXPLAINER_TITLE)
        assertEquals("A call outside these hours is declined quietly and you get the notes as a notification instead. Calls can only be booked between 06:00 and 23:00.", callsHoursHint())
        assertEquals("\"Call me about this\" on a scheduled task rings this many minutes before it starts.", CALLS_LEAD_HINT)
        assertEquals("Book a test call for one minute from now. Lock your phone — it rings through the real path (server → push → call screen).", CALLS_TEST_BODY)
        assertEquals("Test call now", CALLS_TEST_BUTTON)
        assertEquals("Booking…", CALLS_TEST_BOOKING)
        assertEquals("Test call", TEST_CALL_LABEL)
        assertEquals("This is what a call from Unstuck sounds like", TEST_CALL_NOTE)
        assertEquals("unstuck_calls", CALLS_CHANNEL_ID)
    }
}
