package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
