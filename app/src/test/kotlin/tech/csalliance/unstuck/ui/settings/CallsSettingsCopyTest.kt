package tech.csalliance.unstuck.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.CallSettings
import tech.csalliance.unstuck.ui.TEST_CALL_CALLS_OFF
import tech.csalliance.unstuck.ui.TEST_CALL_LABEL
import tech.csalliance.unstuck.ui.TEST_CALL_NOTE
import tech.csalliance.unstuck.ui.TEST_CALL_OUTSIDE_HOURS

/** Settings → Notifications & calls → Calls: the test call's state mapping,
 *  the fix-it lines' rules and the plain copy (slim settings, 2026-09-24). */
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
        assertEquals(
            "the end minute names the last one that rings (C12)",
            TestCallState.Failed("21:00 is outside your allowed hours (08:00–21:00; the latest it rings is 20:59) — the phone would decline it quietly. Widen the hours above to try it now."),
            testCallStateFrom(TEST_CALL_OUTSIDE_HOURS("21:00", s)),
        )
    }

    @Test fun `the test-call row says where it is`() {
        assertEquals("We'll ring you in about a minute.", testCallLine(TestCallState.Idle))
        assertEquals("Booking…", testCallLine(TestCallState.Booking))
        assertEquals("Booked — ringing at 09:05. Lock your phone and wait.", testCallLine(TestCallState.Booked("09:05")))
        assertEquals("Nope.", testCallLine(TestCallState.Failed("Nope.")))
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
