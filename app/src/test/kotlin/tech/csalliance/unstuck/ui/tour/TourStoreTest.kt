package tech.csalliance.unstuck.ui.tour

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

/**
 * TourStateStore persistence — the Android analogue of the web's
 * `unstuck.tour.v1` localStorage blob (same field semantics: started / done /
 * paused / eligible / mode / mediaMode / speed / index).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TourStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun freshStoreLoadsDefaults() {
        val s = TourStateStore(context).load()
        assertEquals(TourState(), s)
        assertFalse(s.started); assertFalse(s.done); assertFalse(s.paused); assertFalse(s.eligible)
        assertNull(s.mode)
        assertEquals(TourMediaMode.READ, s.mediaMode)
        assertEquals(1f, s.speed)
        assertEquals(0, s.index)
    }

    @Test
    fun saveLoadRoundTripsEveryField() {
        val store = TourStateStore(context)
        val state = TourState(
            started = true, done = false, paused = true, eligible = true,
            mode = TourMode.FULL, mediaMode = TourMediaMode.LISTEN, speed = 1.5f, index = 7,
        )
        store.save(state)
        assertEquals(state, TourStateStore(context).load())   // fresh instance, same prefs
    }

    @Test
    fun nullModeRoundTripsAsNull() {
        val store = TourStateStore(context)
        store.save(TourState(mode = TourMode.ESSENTIAL))
        store.save(TourState(mode = null))
        assertNull(store.load().mode)
    }

    @Test
    fun patchMergesOverExistingState() {
        val store = TourStateStore(context)
        store.save(TourState(started = true, mode = TourMode.ESSENTIAL, index = 3, speed = 1.25f))
        val next = store.patch { it.copy(paused = true) }
        assertTrue(next.paused)
        val loaded = store.load()
        assertTrue(loaded.started && loaded.paused)
        assertEquals(TourMode.ESSENTIAL, loaded.mode)
        assertEquals(3, loaded.index)
        assertEquals(1.25f, loaded.speed)
    }

    @Test
    fun corruptEnumStringsDegradeToDefaults() {
        // A hand-edited / future-version blob must never crash the tour.
        context.getSharedPreferences("unstuck.tour", Context.MODE_PRIVATE).edit()
            .putString("mode", "SOMETHING_NEW")
            .putString("mediaMode", "garbage")
            .apply()
        val s = TourStateStore(context).load()
        assertNull(s.mode)
        assertEquals(TourMediaMode.READ, s.mediaMode)
    }

    @Test
    fun eligibleArmingThenBeginMatchesEntryRules() {
        val store = TourStateStore(context)
        // Onboarding finishes → armed → the one-time welcome.
        store.patch { it.copy(eligible = true) }
        assertEquals(TourEntryPhase.WELCOME, initialPhase(store.load()))
        // Begin (or decline) persists started → never offered again…
        store.patch { it.copy(started = true, mode = TourMode.ESSENTIAL, index = 0) }
        assertEquals(TourEntryPhase.HIDDEN, initialPhase(store.load()))
        // …but a paused run resumes.
        store.patch { it.copy(paused = true, index = 4) }
        assertEquals(TourEntryPhase.PAUSED, initialPhase(store.load()))
        assertEquals(ResumeDecision.Running(4), resumeDecision(store.load()))
        // Finishing hides it for good.
        store.patch { it.copy(done = true, paused = false) }
        assertEquals(TourEntryPhase.HIDDEN, initialPhase(store.load()))
    }

    @Test
    fun signOutClearWipesEverythingForTheNextAccount() {
        val store = TourStateStore(context)
        store.save(
            TourState(
                started = true, done = true, paused = true, eligible = true,
                mode = TourMode.FULL, mediaMode = TourMediaMode.LISTEN, speed = 2f, index = 9,
            ),
        )
        // MainActivity's sign-out hook: the next account on this device must
        // start clean — no inherited paused run (ambush) and no pre-consumed
        // `started`/`done` (an eaten one-time offer).
        store.clear()
        assertEquals(TourState(), TourStateStore(context).load())
        assertEquals(TourEntryPhase.HIDDEN, initialPhase(store.load()))   // no ambush either
    }

    @Test
    fun welcomeSoftBackKeepsEligibleUntilExplicitDecline() {
        val store = TourStateStore(context)
        store.patch { it.copy(eligible = true) }
        // The back gesture on the welcome card is a SOFT dismiss: it changes
        // phase only, NEVER the store — so the quiet-Today loop still sees the
        // one-time offer and can re-offer it once.
        assertEquals(TourEntryPhase.WELCOME, dormantResurface(store.load()))
        // 'Not now' (or the second back) declines permanently.
        store.patch { it.copy(done = true, started = true) }
        assertNull(dormantResurface(store.load()))
        assertEquals(TourEntryPhase.HIDDEN, initialPhase(store.load()))
    }

    @Test
    fun restartResetClearsRunFlagsButKeepsPrefs() {
        val store = TourStateStore(context)
        store.save(TourState(started = true, done = true, mode = TourMode.FULL, mediaMode = TourMediaMode.LISTEN, speed = 2f, index = 9))
        // The Settings-row restart patch (TourHost restart collector).
        store.patch { it.copy(done = false, paused = false, started = false) }
        val s = store.load()
        assertEquals(TourEntryPhase.HIDDEN, initialPhase(s))   // not eligible → no ambush; the host shows welcome explicitly
        assertEquals(ResumeDecision.Welcome, resumeDecision(s))
        // Read/Listen + speed preferences survive a restart.
        assertEquals(TourMediaMode.LISTEN, s.mediaMode)
        assertEquals(2f, s.speed)
        assertEquals(TourMode.FULL, s.mode)
    }
}
