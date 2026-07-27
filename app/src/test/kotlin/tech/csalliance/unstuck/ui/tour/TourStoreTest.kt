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
            chipDismissed = true,
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
                chipDismissed = true,
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

    /* ── round-2 #5: pause confirm → chip → resume, persisted ───────────── */

    @Test
    fun confirmedPauseArmsTheResumeChip() {
        val store = TourStateStore(context)
        // Mid-run: the store alone is chip-ELIGIBLE (it's also the process-
        // death boot state) — but the chip renders only in the host's
        // DISMISSED phase, so a live RUNNING tour never shows it.
        store.save(TourState(started = true, mode = TourMode.ESSENTIAL, index = 4))
        assertTrue(showResumeChip(store.load()))
        // Confirmed pause (the host's pause() patch): the chip is the PRIMARY
        // re-entry across screens…
        store.patch { it.copy(paused = true, index = 4, mode = TourMode.ESSENTIAL) }
        assertTrue(showResumeChip(store.load()))
        // …and while it shows, the quiet-Today MODAL stays dormant — it must
        // never re-pop every 5s to fight the chip. The card becomes the
        // SECONDARY path only after the chip's ✕…
        assertNull(dormantResurface(store.load()))
        assertEquals(TourEntryPhase.PAUSED, dormantResurface(store.load().copy(chipDismissed = true)))
        // …and even then it is one-shot per process.
        assertNull(dormantResurface(store.load().copy(chipDismissed = true), pausedResurfaced = true))
        // Chip tap resumes at the saved step.
        store.patch { it.copy(paused = false) }
        assertEquals(4, store.load().index)
        // A LATER pause offers the chip again (it wasn't ✕-dismissed).
        store.patch { it.copy(paused = true) }
        assertTrue(showResumeChip(store.load()))
    }

    @Test
    fun processDeathMidRunBootsIntoTheChip() {
        val store = TourStateStore(context)
        // RUNNING at step 5 when the process dies: paused was never persisted.
        store.save(TourState(started = true, mode = TourMode.FULL, index = 5))
        val s = TourStateStore(context).load()
        // Boot: no card ambush (HIDDEN → the host lands in DISMISSED)…
        assertEquals(TourEntryPhase.HIDDEN, initialPhase(s))
        assertNull(dormantResurface(s))
        // …but the chip shows, and a tap resumes at the stranded index.
        assertTrue(showResumeChip(s))
        assertEquals(ResumeDecision.Running(5), resumeDecision(s))
        // Unless the chip was ✕-dismissed in the previous life.
        assertFalse(showResumeChip(s.copy(chipDismissed = true)))
    }

    @Test
    fun freshRunReEarnsTheChipAfterADismiss() {
        val store = TourStateStore(context)
        store.save(TourState(started = true, paused = true, mode = TourMode.ESSENTIAL, index = 2, chipDismissed = true))
        assertFalse(showResumeChip(store.load()))
        // begin() / Start-over reset chipDismissed — a FRESH run re-earns its
        // chip (the host's begin/onStartOver patches).
        store.patch { it.copy(mode = TourMode.ESSENTIAL, started = true, index = 0, paused = false, chipDismissed = false) }
        store.patch { it.copy(paused = true, index = 1) }
        assertTrue(showResumeChip(store.load()))
    }

    @Test
    fun chipDismissIsForeverAcrossRestartsButSettingsPathRemains() {
        val store = TourStateStore(context)
        store.save(TourState(started = true, paused = true, mode = TourMode.ESSENTIAL, index = 2))
        assertTrue(showResumeChip(store.load()))
        // ✕ on the chip: persisted — the chip never comes back…
        store.patch { it.copy(chipDismissed = true) }
        assertFalse(showResumeChip(TourStateStore(context).load()))   // fresh instance: persisted
        // …even across pause/resume cycles…
        store.patch { it.copy(paused = false) }
        store.patch { it.copy(paused = true, index = 5) }
        assertFalse(showResumeChip(store.load()))
        // …but the Settings → Product tour path still rescues the run.
        assertEquals(ResumeDecision.Running(5), resumeDecision(store.load()))
        // Sign-out clear resets it for the next account.
        store.clear()
        assertFalse(store.load().chipDismissed)
    }
}
