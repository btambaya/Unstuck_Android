package tech.csalliance.unstuck

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Two-accounts-one-device: the onboarded flag is PER ACCOUNT, and the pre-2026-09
// device-global key migrates only to the account that was signed in at upgrade.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OnboardedFlagTest {
    private val prefs get() = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("test.onboarded", Context.MODE_PRIVATE)

    @Test fun `a second account on the same device is NOT onboarded because the first one was`() {
        val p = prefs
        OnboardedFlag.set(p, "userA", true)
        assertTrue(OnboardedFlag.get(p, "userA", legacyOwnerUid = "userA"))
        assertFalse("B must onboard itself", OnboardedFlag.get(p, "userB", legacyOwnerUid = null))
        OnboardedFlag.set(p, "userB", true)
        assertTrue(OnboardedFlag.get(p, "userB", legacyOwnerUid = null))
        assertTrue("A's flag is untouched by B's", OnboardedFlag.get(p, "userA", legacyOwnerUid = null))
    }

    @Test fun `the legacy device-global flag migrates ONLY to the account that was signed in, once`() {
        val p = prefs
        p.edit().putBoolean(OnboardedFlag.LEGACY_KEY, true).apply()
        assertFalse("a fresh account on a signed-out phone never inherits it", OnboardedFlag.get(p, "newcomer", legacyOwnerUid = null))
        assertFalse(OnboardedFlag.get(p, "newcomer", legacyOwnerUid = "owner"))
        assertTrue("the owner of the session at upgrade does", OnboardedFlag.get(p, "owner", legacyOwnerUid = "owner"))
        assertFalse("and the legacy key is consumed", p.contains(OnboardedFlag.LEGACY_KEY))
        assertTrue(p.getBoolean(OnboardedFlag.key("owner"), false))
        assertTrue("stays true after the migration", OnboardedFlag.get(p, "owner", legacyOwnerUid = null))
    }

    @Test fun `no session means nothing is granted and nothing is written`() {
        val p = prefs
        assertFalse(OnboardedFlag.get(p, null, legacyOwnerUid = null))
        OnboardedFlag.set(p, null, true)
        assertEquals(emptySet<String>(), p.all.keys)
    }

    // Offline cold start with an expired token: supabase-kt reports no user during
    // RefreshFailure, so the flag read false and a long-time user got onboarding
    // (Android audit 2026-09-23, A9).
    @Test fun `a refresh failure still reads the flag of the account whose session is stored`() {
        val p = prefs
        OnboardedFlag.set(p, "userA", true)
        val uid = OnboardedFlag.accountFor(currentUid = null, refreshFailing = true, lastSignedIn = "userA")
        assertEquals("userA", uid)
        assertTrue(OnboardedFlag.get(p, uid, legacyOwnerUid = "userA"))
    }

    @Test fun `the live session wins, and signed out or loading there is no account`() {
        assertEquals("userB", OnboardedFlag.accountFor(currentUid = "userB", refreshFailing = false, lastSignedIn = "userA"))
        assertEquals(null, OnboardedFlag.accountFor(currentUid = null, refreshFailing = false, lastSignedIn = "userA"))
        assertEquals(null, OnboardedFlag.accountFor(currentUid = null, refreshFailing = true, lastSignedIn = null))
    }

    @Test fun `clearLegacy drops the device-global key but never a per-account one`() {
        val p = prefs
        p.edit().putBoolean(OnboardedFlag.LEGACY_KEY, true).apply()
        OnboardedFlag.set(p, "userA", true)
        OnboardedFlag.clearLegacy(p)
        assertFalse(p.contains(OnboardedFlag.LEGACY_KEY))
        assertTrue(OnboardedFlag.get(p, "userA", legacyOwnerUid = null))
        assertFalse(OnboardedFlag.get(p, "userB", legacyOwnerUid = "userB"))
    }
}
