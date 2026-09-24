package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.time.ClockMode
import tech.csalliance.unstuck.core.logic.CallProactivePrefs
import tech.csalliance.unstuck.core.logic.CallProactiveSync
import tech.csalliance.unstuck.core.logic.CallRingNudge
import tech.csalliance.unstuck.core.logic.CallSettings
import tech.csalliance.unstuck.core.logic.CallSettingsLogic
import tech.csalliance.unstuck.core.logic.TestCallLogic
import tech.csalliance.unstuck.core.model.CallRequest
import java.time.LocalDateTime
import java.time.ZoneId

// CallSettings window logic — the iOS CallScriptTests
// testWindowInsideOutsideOvernightAndEqual / testServerWindowInclusive
// vectors, plus the Android defaults the shared contract fixes.
class CallSettingsTest {
    private fun win(now: String, start: String, end: String) =
        CallSettingsLogic.withinWindow(now, start, end)

    @Test fun `defaults are the contract's`() {
        val s = CallSettings()
        assertTrue(s.enabled)
        assertEquals("06:00", s.hoursStart)
        assertEquals("23:00", s.hoursEnd)
        assertEquals(10, s.defaultLeadMin)
        assertEquals(CallSettings.DEFAULTS, s)
        assertEquals(listOf(5, 10, 15, 30), CallSettingsLogic.LEAD_OPTIONS)
    }

    @Test fun `window is start-inclusive end-exclusive, overnight when end is before start, always when equal`() {
        assertTrue(win("08:00", "08:00", "21:00"))
        assertTrue(win("20:59", "08:00", "21:00"))
        assertFalse(win("21:00", "08:00", "21:00"))
        assertFalse(win("07:59", "08:00", "21:00"))
        assertTrue(win("23:30", "22:00", "02:00"))
        assertTrue(win("01:00", "22:00", "02:00"))
        assertFalse(win("12:00", "22:00", "02:00"))
        assertFalse(win("02:00", "22:00", "02:00"), "end exclusive on the overnight window too")
        assertTrue(win("03:00", "09:00", "09:00"))
        assertTrue(win("03:00", "junk", "09:00"), "an unparseable bound never locks the user out")
        assertTrue(win("junk", "08:00", "09:00"))
    }

    @Test fun `withinHours reads the settings`() {
        val s = CallSettings(hoursStart = "08:00", hoursEnd = "21:00")
        assertTrue(CallSettingsLogic.withinHours("08:00", s))
        assertFalse(CallSettingsLogic.withinHours("21:00", s))
        assertTrue(CallSettingsLogic.withinHours("22:59", CallSettings()))
        assertFalse(CallSettingsLogic.withinHours("23:00", CallSettings()))
        assertFalse(CallSettingsLogic.withinHours("05:59", CallSettings()))
    }

    @Test fun `server window is inclusive 06 00 to 23 00`() {
        assertEquals("06:00", CallSettingsLogic.SERVER_WINDOW.start)
        assertEquals("23:00", CallSettingsLogic.SERVER_WINDOW.endInclusive)
        assertTrue(CallSettingsLogic.withinServerWindow("06:00"))
        assertTrue(CallSettingsLogic.withinServerWindow("23:00"))
        assertFalse(CallSettingsLogic.withinServerWindow("05:59"))
        assertFalse(CallSettingsLogic.withinServerWindow("23:01"))
        assertTrue("06:30" in CallSettingsLogic.SERVER_WINDOW)
        assertFalse("23:30" in CallSettingsLogic.SERVER_WINDOW)
    }

    @Test fun `minutesOfDay and hhmm`() {
        assertEquals(0, CallSettingsLogic.minutesOfDay("00:00"))
        assertEquals(23 * 60 + 59, CallSettingsLogic.minutesOfDay("23:59"))
        assertNull(CallSettingsLogic.minutesOfDay("24:00"))
        assertNull(CallSettingsLogic.minutesOfDay("8"))
        assertNull(CallSettingsLogic.minutesOfDay("08:60"))
        assertNull(CallSettingsLogic.minutesOfDay("junk"))
        val london = ZoneId.of("Europe/London")
        val t = LocalDateTime.of(2026, 9, 2, 7, 5).atZone(london).toInstant().toEpochMilli()
        assertEquals("07:05", CallSettingsLogic.hhmm(t, london))
        assertEquals("06:05", CallSettingsLogic.hhmm(t, ZoneId.of("UTC")))
    }

    @Test fun `stored values are validated with a fallback`() {
        assertEquals("08:30", CallSettingsLogic.validHM(" 08:30 "))
        assertNull(CallSettingsLogic.validHM("junk"))
        assertNull(CallSettingsLogic.validHM(null))
        assertEquals(15, CallSettingsLogic.validLead(15))
        assertEquals(10, CallSettingsLogic.validLead(0))
        assertEquals(10, CallSettingsLogic.validLead(null))
    }

    private fun assertTrue(v: Boolean, message: String) = assertTrue(message, v)
    private fun assertFalse(v: Boolean, message: String) = assertFalse(message, v)

    // ── calls build-out 2026-09-20 ──

    @Test fun `spokenTime is the way people say it`() {
        assertEquals("2:05pm", CallSettingsLogic.spokenTime("14:05"))
        assertEquals("9am", CallSettingsLogic.spokenTime("09:00"))
        assertEquals("12:30pm", CallSettingsLogic.spokenTime("12:30"))
        assertEquals("12:15am", CallSettingsLogic.spokenTime("00:15"))
        assertEquals("12am", CallSettingsLogic.spokenTime("00:00"))
        assertEquals("11:59pm", CallSettingsLogic.spokenTime("23:59"))
        assertEquals("soon", CallSettingsLogic.spokenTime("soon"))
    }

    @Test fun `proactive prefs default to all off at 08-30 and 18-00, and survive JSON`() {
        val d = CallProactivePrefs.DEFAULTS
        assertFalse(d.morningEnabled); assertFalse(d.eveningEnabled); assertFalse(d.afterBlockEnabled)
        assertEquals("08:30", d.morningTime)
        assertEquals("18:00", d.eveningTime)
        val on = CallProactivePrefs(morningEnabled = true, morningTime = "07:15", eveningEnabled = true, eveningTime = "21:00", afterBlockEnabled = true)
        assertEquals(on, CallProactivePrefs.fromJson(on.toJson()))
        assertEquals(d, CallProactivePrefs.fromJson(null))
        assertEquals(d, CallProactivePrefs.fromJson(""))
        assertEquals(d, CallProactivePrefs.fromJson("{nope"))
        // A stored time that doesn't parse falls back to its default, field by field.
        val bad = CallProactivePrefs.fromJson("""{"morningEnabled":true,"morningTime":"25:00","eveningTime":"garbage","future":1}""")
        assertTrue(bad.morningEnabled)
        assertEquals("08:30", bad.morningTime)
        assertEquals("18:00", bad.eveningTime)
    }

    @Test fun `hhmm normalises a Postgres time`() {
        assertEquals("08:30", CallProactivePrefs.hhmm("08:30:00"))
        assertEquals("08:30", CallProactivePrefs.hhmm("08:30:00.000"))
        assertEquals("08:30", CallProactivePrefs.hhmm("08:30"))
        assertEquals("18:05", CallProactivePrefs.hhmm(" 18:05 "))
        assertNull(CallProactivePrefs.hhmm("8:30"))
        assertNull(CallProactivePrefs.hhmm("24:00"))
        assertNull(CallProactivePrefs.hhmm(null))
        assertNull(CallProactivePrefs.hhmm(""))
    }

    @Test fun `a pending local toggle is never pulled over by the server, otherwise the server wins`() {
        val local = CallProactivePrefs(morningEnabled = true)
        val server = CallProactivePrefs(eveningEnabled = true, eveningTime = "19:00")
        assertEquals(local, CallProactiveSync.resolve(local, server, pendingPush = true))
        assertEquals(server, CallProactiveSync.resolve(local, server, pendingPush = false))
        assertEquals("no server row yet keeps the cache", local, CallProactiveSync.resolve(local, null, pendingPush = false))
        assertTrue(CallProactiveSync.shouldPush(true))
        assertFalse(CallProactiveSync.shouldPush(false))
    }

    @Test fun `the ring nudge shows only while the phone cannot ring and it wasn't dismissed`() {
        assertTrue(CallRingNudge.shouldShow(canRing = false, dismissed = false))
        assertFalse(CallRingNudge.shouldShow(canRing = true, dismissed = false))
        assertFalse(CallRingNudge.shouldShow(canRing = false, dismissed = true))
        assertFalse(CallRingNudge.shouldShow(canRing = true, dismissed = true))
    }

    @Test fun `a new test call replaces every live test row - by kind, or by the old label`() {
        fun r(id: String, status: String = "scheduled", kind: String = "requested", label: String = "speak to James") =
            CallRequest(id = id, callAt = "2026-09-02T14:45:00.000Z", label = label, status = status, kind = kind)
        val live = listOf(
            r("k", kind = "test", label = "whatever"),
            r("l", label = "Test call"),
            r("lc", label = " test CALL "),
            r("done", status = "done", kind = "test"),
            r("other"),
            r("m", kind = "morning", label = "Morning plan"),
        )
        assertEquals(listOf("k", "l", "lc"), TestCallLogic.previousTestCalls(live).map { it.id })
        assertEquals("Test call", TestCallLogic.LABEL)
        assertEquals("This is what a call from Unstuck sounds like", TestCallLogic.NOTE)
        assertEquals("test", TestCallLogic.KIND)
    }

    // ── will it ring here? (parity with iOS build 81, audit 2026-09-22 C12) ──
    // Vectors from iOS CallScriptTests, with "iPhone" → "phone".

    private val london = ZoneId.of("Europe/London")
    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 9, 2, h, m).atZone(london).toInstant().toEpochMilli()

    @Test fun `deviceGuard refuses outside the phone's hours and when calls are off`() {
        fun guardAt(h: Int, m: Int, enabled: Boolean = true, start: String = "08:00", end: String = "21:00") =
            CallSettingsLogic.deviceGuard(at(h, m), CallSettings(enabled = enabled, hoursStart = start, hoursEnd = end), london)
        assertNull(guardAt(20, 59))
        assertEquals(
            "error: 21:00 is outside this phone's call hours (08:00–21:00; the latest it rings is 20:59), so it would decline this call — ask them for a time inside those hours, or tell them they can widen them in Settings › Calls",
            guardAt(21, 0),
        )
        assertEquals(
            "error: 07:59 is outside this phone's call hours (08:00–21:00), so it would decline this call — ask them for a time inside those hours, or tell them they can widen them in Settings › Calls",
            guardAt(7, 59),
        )
        assertNull(guardAt(8, 0))
        assertNull("overnight window", guardAt(23, 30, start = "22:00", end = "02:00"))
        assertNull("start == end → always", guardAt(3, 0, start = "09:00", end = "09:00"))
        // The switch is checked before the hours.
        assertEquals(
            "error: calls are off on this phone, so it would decline this call — tell them to switch Calls on in Settings › Calls first",
            guardAt(12, 0, enabled = false),
        )
        assertEquals(guardAt(12, 0, enabled = false), guardAt(22, 0, enabled = false))
        // The default hours: the server's window, end exclusive on the phone.
        assertNull(guardAt(6, 0, start = "06:00", end = "23:00"))
        assertNull(guardAt(22, 59, start = "06:00", end = "23:00"))
        assertEquals(
            "the server takes 23:00, the phone doesn't — never '23:00 is outside 06:00–23:00' alone",
            "error: 23:00 is outside this phone's call hours (06:00–23:00; the latest it rings is 22:59), so it would decline this call — ask them for a time inside those hours, or tell them they can widen them in Settings › Calls",
            guardAt(23, 0, start = "06:00", end = "23:00"),
        )
    }

    @Test fun `hoursLabel names the last minute only when the end itself is refused`() {
        assertEquals("06:00–23:00; the latest it rings is 22:59", CallSettingsLogic.hoursLabel("06:00", "23:00", 23 * 60, ClockMode.H24))
        assertEquals("06:00–23:00", CallSettingsLogic.hoursLabel("06:00", "23:00", 23 * 60 + 30, ClockMode.H24))
        assertEquals("06:00–23:00", CallSettingsLogic.hoursLabel("06:00", "23:00", 5 * 60 + 59, ClockMode.H24))
        assertEquals("overnight", "22:00–02:00; the latest it rings is 01:59", CallSettingsLogic.hoursLabel("22:00", "02:00", 2 * 60, ClockMode.H24))
        assertEquals("an end at midnight", "08:00–00:00; the latest it rings is 23:59", CallSettingsLogic.hoursLabel("08:00", "00:00", 0, ClockMode.H24))
        assertEquals("08:00–junk", CallSettingsLogic.hoursLabel("08:00", "junk", 0, ClockMode.H24))
    }

    /** The Settings / task screens show the hours the phone's way (Ahmad,
     *  2026-09-24); the assistant's refusals stay 24-hour. */
    @Test fun `the call-hours copy follows a 12-hour phone`() {
        val prev = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.US)
            assertEquals("6:00 AM–11:00 PM; the latest it rings is 10:59 PM", CallSettingsLogic.hoursLabel("06:00", "23:00", 23 * 60, ClockMode.H12))
            assertEquals("8:00–11:00 AM", CallSettingsLogic.hoursLabel("08:00", "11:00", 0, ClockMode.H12))
            assertEquals(
                "Unstuck only calls between 6:00 AM and 11:00 PM, so a call at 5:45 AM never rings.",
                CallSettingsLogic.proactiveTimeWarning("05:45", true, "08:00", "21:00", ClockMode.H12),
            )
            assertEquals(
                "Unstuck rings this call at about 7:30 AM, outside this phone's allowed hours (8:00 AM–9:00 PM), so it's declined here — widen the hours above or pick another time.",
                CallSettingsLogic.proactiveTimeWarning("07:30", true, "08:00", "21:00", ClockMode.H12),
            )
            assertEquals(
                "This phone only takes calls 8:00 AM–9:00 PM, so a check-in after a block that ends outside those hours is declined here.",
                CallSettingsLogic.afterBlockWarning(true, "08:00", "21:00", ClockMode.H12),
            )
            // The model's refusal keeps its 'HH:MM' contract whatever the phone shows.
            assertTrue(
                CallSettingsLogic.snoozeRefusal(120, at(20, 30), CallSettings(hoursStart = "08:00", hoursEnd = "21:00"), london)!!
                    .contains("ring at 22:30, outside this phone's call hours (08:00–21:00)"),
            )
        } finally {
            java.util.Locale.setDefault(prev)
        }
        assertTrue(CallSettingsLogic.withinWindow(1259, "08:00", "21:00"))
        assertFalse(CallSettingsLogic.withinWindow(1260, "08:00", "21:00"))
        assertTrue(CallSettingsLogic.withinWindow(60, "22:00", "02:00"))
        assertTrue(CallSettingsLogic.withinWindow(180, "09:00", "09:00"))
    }

    /** dispatch_proactive_calls (072) books at the first 5-minute tick in
     *  [time, time+10) inside 06:00–23:00 inclusive. */
    @Test fun `proactiveRingMinute is the dispatcher's tick`() {
        assertEquals(8 * 60 + 30, CallSettingsLogic.proactiveRingMinute(8 * 60 + 30))
        assertEquals(7 * 60 + 35, CallSettingsLogic.proactiveRingMinute(7 * 60 + 32))
        assertEquals("booked at the 06:00 tick", 6 * 60, CallSettingsLogic.proactiveRingMinute(5 * 60 + 51))
        assertEquals(6 * 60, CallSettingsLogic.proactiveRingMinute(5 * 60 + 55))
        assertNull("05:50 and 05:55 ticks are both before 06:00", CallSettingsLogic.proactiveRingMinute(5 * 60 + 50))
        assertNull(CallSettingsLogic.proactiveRingMinute(5 * 60 + 45))
        assertEquals(23 * 60, CallSettingsLogic.proactiveRingMinute(22 * 60 + 56))
        assertEquals("inclusive", 23 * 60, CallSettingsLogic.proactiveRingMinute(23 * 60))
        assertNull(CallSettingsLogic.proactiveRingMinute(23 * 60 + 1))
        assertNull(CallSettingsLogic.proactiveRingMinute(23 * 60 + 59))
    }

    @Test fun `proactiveTimeWarning covers the server window, the phone's hours and the switch`() {
        fun warn(t: String, enabled: Boolean = true, start: String = "08:00", end: String = "21:00") =
            CallSettingsLogic.proactiveTimeWarning(t, enabled, start, end, ClockMode.H24)
        // Never booked at all — even with Calls off, that's the first thing to say.
        assertEquals("Unstuck only calls between 06:00 and 23:00, so a call at 05:45 never rings.", warn("05:45"))
        assertEquals(warn("05:45"), warn("05:45", enabled = false))
        assertEquals("Unstuck only calls between 06:00 and 23:00, so a call at 23:15 never rings.", warn("23:15"))
        assertEquals("Unstuck only calls between 06:00 and 23:00, so a call at 23:01 never rings.", warn("23:01"))
        // Booked by the server — judged at the minute it really rings.
        assertNull(warn("23:00", start = "00:00", end = "00:00"))
        assertNull(warn("22:58", start = "00:00", end = "00:00"))
        assertNull(warn("06:00", start = "00:00", end = "00:00"))
        assertEquals(
            "booked at 06:00, not 'never'",
            "Unstuck rings this call at about 06:00, outside this phone's allowed hours (08:00–21:00), so it's declined here — widen the hours above or pick another time.",
            warn("05:55"),
        )
        assertNull(warn("05:55", start = "06:00", end = "23:00"))
        assertEquals(
            "Unstuck rings this call at about 07:30, outside this phone's allowed hours (08:00–21:00), so it's declined here — widen the hours above or pick another time.",
            warn("07:30"),
        )
        assertNull("rings at the 08:00 tick", warn("07:58"))
        assertEquals(
            "booked at the 21:00 tick, declined every day",
            "Unstuck rings this call at about 21:00, outside this phone's allowed hours (08:00–21:00; the latest it rings is 20:59), so it's declined here — widen the hours above or pick another time.",
            warn("20:58"),
        )
        assertTrue(warn("21:30")?.contains("at about 21:30") == true)
        assertTrue(
            "call-dispatch may ring a minute later",
            warn("20:55", end = "20:56")?.contains("at about 20:56, outside this phone's allowed hours (08:00–20:56; the latest it rings is 20:55)") == true,
        )
        assertEquals(
            "the default end is exclusive",
            "Unstuck rings this call at about 23:00, outside this phone's allowed hours (06:00–23:00; the latest it rings is 22:59), so it's declined here — widen the hours above or pick another time.",
            warn("23:00", start = "06:00", end = "23:00"),
        )
        assertNull(warn("08:30"))
        assertNull(warn("18:00"))
        assertNull(warn("07:30", start = "07:00"))
        assertEquals("Calls are off on this phone, so this call is declined here — switch them on above.", warn("08:30", enabled = false))
        assertNull(warn("junk"))
    }

    @Test fun `afterBlockWarning when calls are off or the hours are narrower`() {
        assertEquals(
            "Calls are off on this phone, so these check-ins are declined here — switch them on above.",
            CallSettingsLogic.afterBlockWarning(false, "06:00", "23:00", ClockMode.H24),
        )
        assertNull("the defaults", CallSettingsLogic.afterBlockWarning(true, "06:00", "23:00", ClockMode.H24))
        assertNull(CallSettingsLogic.afterBlockWarning(true, "05:00", "23:30", ClockMode.H24))
        assertNull(CallSettingsLogic.afterBlockWarning(true, "00:00", "00:00", ClockMode.H24))
        assertEquals(
            "This phone only takes calls 08:00–21:00, so a check-in after a block that ends outside those hours is declined here.",
            CallSettingsLogic.afterBlockWarning(true, "08:00", "21:00", ClockMode.H24),
        )
        assertTrue("overnight misses the day", CallSettingsLogic.afterBlockWarning(true, "22:00", "07:00", ClockMode.H24) != null)
    }

    /** "call me back in two hours" at 20:30 used to be answered ok, then
     *  declined on receipt at 22:30 by the hours (iOS snoozeRefusal). */
    @Test fun `a call-back outside the phone's hours is refused, one inside is not`() {
        val s = CallSettings(hoursStart = "08:00", hoursEnd = "21:00")
        assertEquals(
            "error: a call-back in 120 minutes would ring at 22:30, outside this phone's call hours (08:00–21:00), so it would be declined — ask them for a shorter wait, or for a time inside those hours to book with request_call",
            CallSettingsLogic.snoozeRefusal(120, at(20, 30), s, london),
        )
        assertEquals(
            "the end minute names the last one that rings",
            "error: a call-back in 30 minutes would ring at 21:00, outside this phone's call hours (08:00–21:00; the latest it rings is 20:59), so it would be declined — ask them for a shorter wait, or for a time inside those hours to book with request_call",
            CallSettingsLogic.snoozeRefusal(30, at(20, 30), s, london),
        )
        assertNull(CallSettingsLogic.snoozeRefusal(10, at(20, 30), s, london))
        assertNull("defaults: 22:50 still rings", CallSettingsLogic.snoozeRefusal(10, at(22, 40), CallSettings(), london))
        assertTrue("clamped to 180", CallSettingsLogic.snoozeRefusal(999, at(19, 0), s, london)!!.startsWith("error: a call-back in 180 minutes would ring at 22:00"))
    }
}
