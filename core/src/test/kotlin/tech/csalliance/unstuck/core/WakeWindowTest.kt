package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.csalliance.unstuck.core.logic.wakeWindowSample
import tech.csalliance.unstuck.core.time.Time
import java.time.ZoneId

// wake_window_history sample shape: local date, local HH:MM, weekday 0=Sun … 6=Sat.
class WakeWindowTest {
    @Test fun sampleIsLocalToTheZone_withSundayAsZero() {
        // 2026-07-19 is a Sunday. 23:30 UTC on Saturday the 18th is 08:30 Sunday in Tokyo.
        val ms = Time.parseMillis("2026-07-18T23:30:00.000Z")!!
        val s = wakeWindowSample(ms, ZoneId.of("Asia/Tokyo"))
        assertEquals("2026-07-19", s.localDate)
        assertEquals("08:30", s.firstInputLocal)
        assertEquals(0, s.weekday)
        val utc = wakeWindowSample(ms, ZoneId.of("UTC"))
        assertEquals("2026-07-18", utc.localDate)
        assertEquals("23:30", utc.firstInputLocal)
        assertEquals("Saturday = 6", 6, utc.weekday)
    }

    @Test fun mondayIsOne() {
        val ms = Time.parseMillis("2026-07-20T06:05:00.000Z")!!   // Monday
        assertEquals(1, wakeWindowSample(ms, ZoneId.of("UTC")).weekday)
        assertEquals("06:05", wakeWindowSample(ms, ZoneId.of("UTC")).firstInputLocal)
    }
}
