package tech.csalliance.unstuck.ui.components

import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.core.time.ClockMode
import tech.csalliance.unstuck.surface.ReminderReceiver
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Ahmad, 2026-09-24: "On the calendar we need to be consistent — either 12
 * hour or 24h, not both." His phone was on 24-hour ("14:02" in the status bar)
 * while Today said "THURSDAY · 2:02 PM". The accessor reads the phone's own
 * setting, and the sites that used to hard-code a format now follow it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeviceClockTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun `the accessor follows the phone's 24-hour switch`() {
        Settings.System.putString(app.contentResolver, Settings.System.TIME_12_24, "24")
        assertEquals(ClockMode.H24, DeviceClock.mode(app))
        Settings.System.putString(app.contentResolver, Settings.System.TIME_12_24, "12")
        assertEquals(ClockMode.H12, DeviceClock.mode(app))
    }

    private val thursday1402: Long =
        ZonedDateTime.of(2026, 9, 24, 14, 2, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()

    @Test fun `the Today eyebrow reads the time the phone's way`() {
        val utc = ZoneId.of("UTC")
        assertEquals("THURSDAY · 14:02", dateEyebrow(thursday1402, ClockMode.H24, utc, Locale.US))
        assertEquals("THURSDAY · 2:02 PM", dateEyebrow(thursday1402, ClockMode.H12, utc, Locale.US))
        val nine = ZonedDateTime.of(2026, 9, 24, 9, 5, 0, 0, utc).toInstant().toEpochMilli()
        assertEquals("THURSDAY · 09:05", dateEyebrow(nine, ClockMode.H24, utc, Locale.US))
    }

    @Test fun `a late reminder names the start time the phone's way`() {
        val start = thursday1402
        val later = start + 10 * 60_000L
        fun copy(mode: ClockMode) = ReminderReceiver.leadCopy("Gym", 10, start, later, startNowCovers = false) { at ->
            tech.csalliance.unstuck.core.time.ClockFormat.time(at, mode, ZoneId.of("UTC"), Locale.US)
        }
        assertEquals("Time to start" to "Gym was set for 14:02.", copy(ClockMode.H24))
        assertEquals("Time to start" to "Gym was set for 2:02 PM.", copy(ClockMode.H12))
    }
}
