package tech.csalliance.unstuck.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.CallSettings
import tech.csalliance.unstuck.core.time.ClockMode
import tech.csalliance.unstuck.ui.TEST_CALL_CALLS_OFF
import tech.csalliance.unstuck.ui.TEST_CALL_LABEL
import tech.csalliance.unstuck.ui.TEST_CALL_NOTE
import tech.csalliance.unstuck.ui.TEST_CALL_OUTSIDE_HOURS

/** Settings → Notifications & calls → Calls: the test call's state mapping,
 *  the fix-it lines' rules and the plain copy (slim settings, 2026-09-24). */
class CallsSettingsCopyTest {

    @Test fun `a booked test call reports the time the row landed on`() {
        assertEquals(TestCallState.Booked("14:31"), testCallStateFrom("ok: call booked 2026-09-07 14:31 \"Test call\" (1 note) id=c9", ClockMode.H24))
        assertEquals("Booked. It rings at 14:31. Lock your phone and wait.", testCallBookedLine("14:31", ClockMode.H24))
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

    @Test fun `the test-call row says where it is`() {
        assertEquals("We'll ring you in about a minute.", testCallLine(TestCallState.Idle, ClockMode.H24))
        assertEquals("Booking…", testCallLine(TestCallState.Booking, ClockMode.H24))
        assertEquals("Booked. It rings at 09:05. Lock your phone and wait.", testCallLine(TestCallState.Booked("09:05"), ClockMode.H24))
        assertEquals("Nope.", testCallLine(TestCallState.Failed("Nope."), ClockMode.H24))
    }

    /** Ahmad, 2026-09-24: one rule app-wide — a 12-hour phone reads every time
     *  on this screen as 12-hour, the refusals it shows included. */
    @Test fun `a 12-hour phone reads the Calls block's times its own way`() {
        val prev = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.US)
            val h12 = ClockMode.H12
            assertEquals("Booked. It rings at 2:31 PM. Lock your phone and wait.", testCallBookedLine("14:31", h12))
            assertEquals("Booked. It rings at 9:05 AM. Lock your phone and wait.", testCallLine(TestCallState.Booked("09:05"), h12))
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
            assertEquals("Calls from 8:00 AM. Change", callsHoursChipA11y(from = true, hhmm = "08:00", clock = h12))
            assertEquals("Calls until 9:00 PM. Change", callsHoursChipA11y(from = false, hhmm = "21:00", clock = h12))
        } finally {
            java.util.Locale.setDefault(prev)
        }
    }

    /** iOS shows the mic line whenever the microphone is denied, from the moment
     *  the screen opens; Android can tell only while it still offers the prompt. */
    @Test fun `the mic line shows a refusal from before the screen opened, and a tap asks while Android still can`() {
        assertEquals(MicHint.NONE, callsMicHint(granted = true, canAskAgain = false, refusedHere = true))
        assertEquals("refused at a ring's Answer, before Settings opened", MicHint.ASK, callsMicHint(granted = false, canAskAgain = true, refusedHere = false))
        assertEquals(MicHint.ASK, callsMicHint(granted = false, canAskAgain = true, refusedHere = true))
        assertEquals("after don't-ask-again: the app's system page", MicHint.OPEN_SETTINGS, callsMicHint(granted = false, canAskAgain = false, refusedHere = true))
        assertEquals("never asked is not a refusal (the ring asks at Answer)", MicHint.NONE, callsMicHint(granted = false, canAskAgain = false, refusedHere = false))
    }

    /** Status lines only when something is wrong: DND silences the ring only
     *  while it is on AND the Calls channel may not break through. */
    @Test fun `the Do Not Disturb line shows only when DND would silence a call`() {
        assertFalse("DND off", callsSilencedByDnd(interruptionFilterAll = true, channelBypassesDnd = false))
        assertFalse("the channel breaks through", callsSilencedByDnd(interruptionFilterAll = false, channelBypassesDnd = true))
        assertTrue(callsSilencedByDnd(interruptionFilterAll = false, channelBypassesDnd = false))
    }

    @Test fun `reminder lead labels round-trip`() {
        assertEquals(listOf("Off", "5 min", "10 min", "15 min"), LEAD_LABELS.map { it.first })
        assertEquals("Off", leadLabel(0))
        assertEquals("10 min", leadLabel(10))
        assertEquals("30 min", leadLabel(30))
    }

    @Test fun `the calls copy is plain`() {
        assertEquals("Let Unstuck call this phone", SettingsCopy.CALLS_SWITCH)
        assertEquals("Only call between", SettingsCopy.CALLS_HOURS)
        assertEquals("Morning call", SettingsCopy.CALLS_MORNING)
        assertEquals("Evening call", SettingsCopy.CALLS_EVENING)
        assertEquals("Call me after a focus block", SettingsCopy.CALLS_AFTER_BLOCK)
        assertEquals("Try a test call", SettingsCopy.CALLS_TEST)
        // The copy canon (web · iOS · Android, 2026-09-24).
        assertEquals(
            "Unstuck can ring your phone to plan, check in or go over your notes. It only rings when you ask, or for the calls you turn on below.",
            SettingsCopy.CALLS_INTRO,
        )
        assertEquals("Off: this phone won't ring. You'll get a notification with the notes instead.", SettingsCopy.CALLS_SWITCH_OFF_SUB)
        assertEquals("Outside these hours it won't ring. You'll get a notification with the notes instead.", SettingsCopy.CALLS_HOURS_SUB)
        assertEquals("Rings to plan the day with you.", SettingsCopy.CALLS_MORNING_SUB)
        assertEquals("Rings to go over what got done and what moves to tomorrow.", SettingsCopy.CALLS_EVENING_SUB)
        assertEquals("Rings when a block ends and its task isn't done yet.", SettingsCopy.CALLS_AFTER_BLOCK_SUB)
        assertEquals("We'll ring you in about a minute.", SettingsCopy.CALLS_TEST_SUB)
        assertEquals("Booking…", SettingsCopy.CALLS_TEST_BOOKING)
        assertEquals("Calls need the Assistant, which is off.", tech.csalliance.unstuck.core.logic.CallsBlockState.needsLine(assistantOn = false, aiSharingOn = true))
        assertEquals("Turn on", SettingsCopy.CALLS_NEED_ASSISTANT_FIX)
        assertEquals("Calls need microphone access — turn it on for Unstuck in Android Settings.", CALLS_TEST_MIC_REFUSED)
        assertEquals("Test call", TEST_CALL_LABEL)
        assertEquals("This is what a call from Unstuck sounds like", TEST_CALL_NOTE)
        assertEquals("unstuck_calls", CALLS_CHANNEL_ID)
        // No plumbing words on the screen any more.
        for (line in listOf(SettingsCopy.CALLS_INTRO, SettingsCopy.CALLS_SWITCH_OFF_SUB, SettingsCopy.CALLS_HOURS_SUB, SettingsCopy.CALLS_TEST_SUB)) {
            assertFalse(line, line.contains("server") || line.contains("push") || line.contains("token"))
        }
    }
}
