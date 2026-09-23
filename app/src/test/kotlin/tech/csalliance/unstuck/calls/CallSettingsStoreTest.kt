package tech.csalliance.unstuck.calls

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.core.logic.CallProactivePrefs
import tech.csalliance.unstuck.core.logic.CallSettings

/**
 * "Calls from Unstuck" per-account persistence — the phase-1 SharedPreferences
 * pattern (file "unstuck.pa", key "calls.<field>.<uid>") so a second account on
 * the phone never inherits the first one's hours, and the sign-out scrub
 * (clearing the file) wipes these with the rituals.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CallSettingsStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun prefs() = context.getSharedPreferences(CallSettingsStore.FILE, Context.MODE_PRIVATE)

    @Test fun freshLoadIsTheCoreDefaults() {
        assertEquals(CallSettings(), CallSettingsStore.load(context, "u1"))
    }

    @Test fun saveLoadRoundTripsEveryField() {
        val s = CallSettings(enabled = false, hoursStart = "22:00", hoursEnd = "02:30", defaultLeadMin = 30)
        CallSettingsStore.save(context, "u1", s)
        assertEquals(s, CallSettingsStore.load(context, "u1"))
    }

    @Test fun keysArePerUidInTheGatewayPrefsFile() {
        CallSettingsStore.save(context, "u1", CallSettings(enabled = false, hoursStart = "09:00", hoursEnd = "17:00", defaultLeadMin = 5))
        val p = prefs()
        assertFalse(p.getBoolean("calls.enabled.u1", true))
        assertEquals("09:00", p.getString("calls.hoursStart.u1", null))
        assertEquals("17:00", p.getString("calls.hoursEnd.u1", null))
        assertEquals(5, p.getInt("calls.defaultLead.u1", -1))
        // Another account on the same phone sees defaults, not u1's hours.
        assertEquals(CallSettings(), CallSettingsStore.load(context, "u2"))
    }

    @Test fun invalidStoredValuesFallBackToDefaultsFieldByField() {
        prefs().edit()
            .putString("calls.hoursStart.u1", "25:00")
            .putString("calls.hoursEnd.u1", "garbage")
            .putInt("calls.defaultLead.u1", 0)
            .putBoolean("calls.enabled.u1", false)
            .apply()
        val d = CallSettings()
        val s = CallSettingsStore.load(context, "u1")
        assertFalse(s.enabled)
        assertEquals(d.hoursStart, s.hoursStart)
        assertEquals(d.hoursEnd, s.hoursEnd)
        assertEquals(d.defaultLeadMin, s.defaultLeadMin)
    }

    @Test fun clearDropsOnlyThatAccount() {
        val s1 = CallSettings(enabled = false, hoursStart = "07:00", hoursEnd = "20:00", defaultLeadMin = 15)
        val s2 = CallSettings(enabled = true, hoursStart = "10:00", hoursEnd = "22:00", defaultLeadMin = 10)
        CallSettingsStore.save(context, "u1", s1)
        CallSettingsStore.save(context, "u2", s2)
        CallSettingsStore.clear(context, "u1")
        assertEquals(CallSettings(), CallSettingsStore.load(context, "u1"))
        assertEquals(s2, CallSettingsStore.load(context, "u2"))
        assertFalse(prefs().contains("calls.enabled.u1"))
    }

    @Test fun theSignOutScrubOfTheWholeFileWipesTheseToo() {
        CallSettingsStore.save(context, "u1", CallSettings(enabled = false))
        prefs().edit().clear().apply()    // AppViewModel.scrubAssistantUserState
        assertTrue(CallSettingsStore.load(context, "u1").enabled)
    }

    @Test fun validHM() {
        assertEquals("06:00", CallSettingsStore.validHM("06:00"))
        assertEquals("23:59", CallSettingsStore.validHM(" 23:59 "))
        assertNull(CallSettingsStore.validHM("24:00"))
        assertNull(CallSettingsStore.validHM("6:00"))
        assertNull(CallSettingsStore.validHM("06:60"))
        assertNull(CallSettingsStore.validHM(""))
        assertNull(CallSettingsStore.validHM(null))
        // Saved by an older build on an Arabic / Persian phone (Android audit 2026-09-23, A12).
        assertEquals("08:00", CallSettingsStore.validHM("٠٨:٠٠"))
        assertEquals("21:30", CallSettingsStore.validHM("۲۱:۳۰"))
    }

    @Test fun leadOptionsMatchIOS() {
        assertEquals(listOf(5, 10, 15, 30), CallSettingsStore.LEAD_OPTIONS)
    }

    // ── proactive calls (server-backed; the device cache) + the ring nudge ──

    @Test fun proactivePrefsRoundTripPerAccountAndDefaultToOff() {
        assertEquals(CallProactivePrefs.DEFAULTS, CallSettingsStore.loadProactive(context, "u1"))
        val p = CallProactivePrefs(morningEnabled = true, morningTime = "07:45", eveningEnabled = true, eveningTime = "20:30", afterBlockEnabled = true)
        CallSettingsStore.saveProactive(context, "u1", p)
        assertEquals(p, CallSettingsStore.loadProactive(context, "u1"))
        assertEquals("another account sees the defaults", CallProactivePrefs.DEFAULTS, CallSettingsStore.loadProactive(context, "u2"))
        prefs().edit().putString("calls.proactive.u1", "{corrupt").apply()
        assertEquals(CallProactivePrefs.DEFAULTS, CallSettingsStore.loadProactive(context, "u1"))
    }

    @Test fun pendingPushFlagIsPerAccountAndClearsCleanly() {
        assertFalse(CallSettingsStore.pendingProactivePush(context, "u1"))
        CallSettingsStore.setPendingProactivePush(context, "u1", true)
        assertTrue(CallSettingsStore.pendingProactivePush(context, "u1"))
        assertFalse(CallSettingsStore.pendingProactivePush(context, "u2"))
        CallSettingsStore.setPendingProactivePush(context, "u1", false)
        assertFalse(CallSettingsStore.pendingProactivePush(context, "u1"))
        assertFalse("cleared, not written false", prefs().contains("calls.proactivePending.u1"))
    }

    @Test fun ringNudgeDismissalPersistsPerAccount() {
        assertFalse(CallSettingsStore.ringNudgeDismissed(context, "u1"))
        CallSettingsStore.setRingNudgeDismissed(context, "u1", true)
        assertTrue(CallSettingsStore.ringNudgeDismissed(context, "u1"))
        assertFalse(CallSettingsStore.ringNudgeDismissed(context, "u2"))
    }

    @Test fun clearDropsTheProactiveCacheAndTheNudgeToo() {
        CallSettingsStore.saveProactive(context, "u1", CallProactivePrefs(afterBlockEnabled = true))
        CallSettingsStore.setPendingProactivePush(context, "u1", true)
        CallSettingsStore.setRingNudgeDismissed(context, "u1", true)
        CallSettingsStore.clear(context, "u1")
        assertEquals(CallProactivePrefs.DEFAULTS, CallSettingsStore.loadProactive(context, "u1"))
        assertFalse(CallSettingsStore.pendingProactivePush(context, "u1"))
        assertFalse(CallSettingsStore.ringNudgeDismissed(context, "u1"))
    }
}
