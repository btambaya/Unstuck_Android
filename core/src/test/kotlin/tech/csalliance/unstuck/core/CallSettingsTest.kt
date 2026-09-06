package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.CallSettings
import tech.csalliance.unstuck.core.logic.CallSettingsLogic
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
}
