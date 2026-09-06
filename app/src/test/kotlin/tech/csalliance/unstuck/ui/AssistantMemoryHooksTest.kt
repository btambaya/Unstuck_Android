package tech.csalliance.unstuck.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.AppGraph
import tech.csalliance.unstuck.core.logic.RitualKey
import tech.csalliance.unstuck.core.logic.RitualPrefs
import tech.csalliance.unstuck.core.model.ProfileFact
import tech.csalliance.unstuck.core.model.ProfileFactCategory
import tech.csalliance.unstuck.core.model.ProfileFactSource
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.sync.PreferencesClient
import tech.csalliance.unstuck.sync.WriteThrough

/**
 * The gateway A0 hooks on [AppViewModel]: the deterministic style-preference save
 * before a message reaches the model, the per-account rituals / dismissals /
 * interview-flag caches (SharedPreferences, `<key>.<uid>`), how a pull pins them
 * from the server (`user_preferences`), and the sign-out scrub. Same SUT shape as
 * AppViewModelTest: a real in-memory Room store + WriteThrough, no coordinator
 * (graph.configured=false), viewModelScope on a StandardTestDispatcher.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AssistantMemoryHooksTest {

    private lateinit var db: UnstuckDatabase
    private lateinit var store: LocalStore
    private lateinit var write: WriteThrough
    private lateinit var graph: AppGraph
    private val dispatcher = StandardTestDispatcher()
    private var uid: String? = "me"

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnstuckDatabase::class.java)
            .allowMainThreadQueries().build()
        store = LocalStore(db)
        graph = AppGraph(ApplicationProvider.getApplicationContext(), configured = false, storeOverride = store)
        write = WriteThrough(graph.store)
    }

    @After fun teardown() { Dispatchers.resetMain() }

    private fun vm() = AppViewModel(graph = graph, writeOverride = write, currentUidProvider = { uid }, currentNameProvider = { "Ada" })

    /** Drive the VM's coroutines until the turn has settled. The style save awaits
     *  Room suspend calls on a REAL executor thread, which the virtual scheduler
     *  can't advance through — so alternate advanceUntilIdle with a short real
     *  wait until [assistantSending] has flipped back (the turn's `finally`). */
    private fun kotlinx.coroutines.test.TestScope.settleTurn(vm: AppViewModel) {
        repeat(300) {
            advanceUntilIdle()
            if (!vm.assistantSending.value) {
                // The flag flips INSIDE the turn's `finally`, so the job's own
                // completion is still resuming from a real Dispatchers.Default
                // thread. Give it a beat and drain again, or teardown's
                // resetMain() races it ("Main is used concurrently with setting it").
                Thread.sleep(25)
                advanceUntilIdle()
                return
            }
            Thread.sleep(10)
        }
        error("assistant turn never settled")
    }

    private suspend fun facts(): List<ProfileFact> = store.profileFacts().first()
    private suspend fun pendingFactOps() = store.pending().filter { it.recordTable == Tables.PROFILE_FACTS }
    private fun serverPrefs(doneAt: String? = null, rituals: String? = null) = PreferencesClient.ServerUserPrefs(
        assistant_interview_done_at = doneAt,
        pa_rituals = rituals?.let { Json.parseToJsonElement(it).jsonObject },
    )

    // ── style preference before the model sees the message ─────────────────

    @Test fun sendAssistant_savesANoNamePreferenceDeterministicallyBeforeTheTurn() = runTest {
        val vm = vm()
        vm.sendAssistant("please stop saying my name")
        settleTurn(vm)
        // No coordinator → the turn itself fails "not_configured", but the app
        // has already remembered the preference (web + iOS parity).
        assertEquals("not_configured", vm.assistantError.value)
        val f = facts().single()
        assertEquals(ProfileFactCategory.PREFERENCE, f.category)
        assertEquals("Don't use their name in replies", f.fact)
        assertEquals(ProfileFactSource.CHAT, f.source)
        assertTrue(f.active)
        assertEquals("queued for the account", 1, pendingFactOps().size)
        // The live projection + the derived readers see it.
        backgroundScope.launch { vm.profileFacts.collect { } }
        vm.profileFacts.first { it.isNotEmpty() }
        assertTrue(vm.noNamePreference())
        assertNull(vm.preferredName())
        // Saying it again refines the same fact — no duplicate row.
        vm.sendAssistant("stop saying my name")
        settleTurn(vm)
        assertEquals(1, facts().size)
    }

    @Test fun sendAssistant_savesACallMePreferenceAndSkipsTaskishSentences() = runTest {
        val vm = vm()
        vm.sendAssistant("add a task: call me Sam at 5")
        settleTurn(vm)
        assertTrue("a task-ish sentence is not a preference", facts().isEmpty())
        vm.sendAssistant("Call me Chief from now on")
        settleTurn(vm)
        assertEquals("Call them Chief", facts().single().fact)
        backgroundScope.launch { vm.profileFacts.collect { } }
        vm.profileFacts.first { it.isNotEmpty() }
        assertEquals("Chief", vm.preferredName())
    }

    // ── rituals: per-account cache, pending push, server wins on pull ──────

    @Test fun rituals_defaultThenCachePerAccountAndFlagPending() = runTest {
        val vm = vm()
        assertEquals(RitualPrefs.DEFAULTS, vm.rituals.value)
        vm.setRitual(RitualKey.FRIDAY, true)
        assertEquals(RitualPrefs(friday = true), vm.rituals.value)
        assertTrue("no coordinator to push to — stays pending for the next pull", vm.ritualsPendingPush("me"))
        // A different account on the same phone starts on defaults …
        uid = "other"
        vm.reloadAssistantUserState()
        assertEquals(RitualPrefs.DEFAULTS, vm.rituals.value)
        assertFalse(vm.ritualsPendingPush("other"))
        // … and the first account gets its own cache back.
        uid = "me"
        vm.reloadAssistantUserState()
        assertEquals(RitualPrefs(friday = true), vm.rituals.value)
        // A fresh ViewModel reads the same cache at construction.
        assertEquals(RitualPrefs(friday = true), vm().rituals.value)
    }

    @Test fun pull_serverRitualsWinUnlessALocalToggleIsStillPending() = runTest {
        val vm = vm()
        // Nothing pending: the account's value replaces the cache, missing keys default.
        vm.applyServerAssistantPrefs("me", serverPrefs(rituals = """{"sunday":true,"morning":false}"""))
        assertEquals(RitualPrefs(morning = false, sunday = true), vm.rituals.value)
        assertFalse(vm.ritualsPendingPush("me"))
        // A null column (never set anywhere) leaves the cache alone.
        vm.applyServerAssistantPrefs("me", serverPrefs(rituals = null))
        assertEquals(RitualPrefs(morning = false, sunday = true), vm.rituals.value)
        // A toggle made here that hasn't landed must NOT be pulled over.
        vm.setRitual(RitualKey.FRIDAY, true)
        assertTrue(vm.ritualsPendingPush("me"))
        vm.applyServerAssistantPrefs("me", serverPrefs(rituals = """{"friday":false}"""))
        assertEquals(RitualPrefs(morning = false, sunday = true, friday = true), vm.rituals.value)
        assertTrue("still pending: no coordinator could land it", vm.ritualsPendingPush("me"))
    }

    // ── interview flag: server pins local, local pushes up ──────────────────

    @Test fun pull_serverDoneFlagPinsLocalAndDropsAHalfWayResumeStep() = runTest {
        val vm = vm()
        assertFalse(vm.interviewDone.value)
        vm.setInterviewStep(3)
        assertTrue(vm.hasInterviewResumeStep())
        assertEquals(3, vm.interviewParkedStep(7))
        // Unknown / not done: nothing changes.
        vm.applyServerAssistantPrefs("me", null)
        vm.applyServerAssistantPrefs("me", serverPrefs(doneAt = "garbage"))
        assertFalse(vm.interviewDone.value)
        assertEquals(3, vm.interviewParkedStep(7))
        // The account finished elsewhere (PostgREST timestamp shape).
        vm.applyServerAssistantPrefs("me", serverPrefs(doneAt = "2026-09-05T10:00:00.123456+00:00"))
        assertTrue(vm.interviewDone.value)
        assertFalse("they finished elsewhere — no stale resume step", vm.hasInterviewResumeStep())
        assertNull(vm.interviewParkedStep(7))
        // Persisted per account: a fresh ViewModel sees it, another account doesn't.
        assertTrue(vm().interviewDone.value)
        uid = "other"
        assertFalse(vm().interviewDone.value)
    }

    @Test fun markInterviewDone_setsTheLocalFlagAndClearsTheStep() = runTest {
        val vm = vm()
        vm.setInterviewStep(2)
        vm.markInterviewDone()
        assertTrue(vm.interviewDone.value)
        assertFalse(vm.hasInterviewResumeStep())
        assertTrue(vm().interviewDone.value)
        // A pull that says "not done" never un-does a local done (it pushes instead).
        vm.applyServerAssistantPrefs("me", serverPrefs(doneAt = null))
        assertTrue(vm.interviewDone.value)
    }

    @Test fun interviewStep_parsesAValidIndexOrRestarts() = runTest {
        val vm = vm()
        assertNull("nothing parked", vm.interviewParkedStep(7))
        vm.setInterviewStep(9)
        assertEquals("out of range → restart at the greeting", 0, vm.interviewParkedStep(7))
        vm.clearInterviewStep()
        assertNull(vm.interviewParkedStep(7))
        assertFalse(vm.hasInterviewResumeStep())
    }

    // ── moment dismissals: device-local, idempotent, persisted ──────────────

    @Test fun dismissMoment_isIdempotentAndPersistedPerAccount() = runTest {
        val vm = vm()
        vm.dismissMoment("evening-sweep:2026-08-29")
        vm.dismissMoment("evening-sweep:2026-08-29")
        assertEquals(listOf("evening-sweep:2026-08-29"), vm.dismissedMoments.value)
        assertTrue(vm.isMomentDismissed("evening-sweep:2026-08-29"))
        assertFalse(vm.isMomentDismissed("quiet-win:2026-08-29"))
        assertEquals(listOf("evening-sweep:2026-08-29"), vm().dismissedMoments.value)
        uid = "other"
        assertEquals(emptyList<String>(), vm().dismissedMoments.value)
    }

    // ── the hydrated flag + the sign-out scrub ──────────────────────────────

    @Test fun scrub_wipesLocalFactsAndEveryGatewayCache() = runTest {
        val vm = vm()
        assertNotNull(vm.saveProfileFact(ProfileFactCategory.PERSON, "Maleek — son", ProfileFactSource.INTERVIEW))
        vm.setRitual(RitualKey.SUNDAY, true)
        vm.dismissMoment("m1")
        vm.markInterviewDone()
        assertEquals(1, facts().size)
        vm.scrubAssistantUserState()
        assertEquals("the local memory is gone (the server keeps the account's facts)", 0, facts().size)
        assertEquals("the queued push keeps its own payload", 1, pendingFactOps().size)
        assertEquals(RitualPrefs.DEFAULTS, vm.rituals.value)
        assertEquals(emptyList<String>(), vm.dismissedMoments.value)
        assertFalse(vm.interviewDone.value)
        assertFalse(vm.profileFactsHydrated.value)
        // The file is cleared too: the next account (or the same one, re-signing in)
        // starts on defaults until its pull re-applies the server's word.
        vm.reloadAssistantUserState()
        assertEquals(RitualPrefs.DEFAULTS, vm.rituals.value)
        assertFalse(vm.interviewDone.value)
        assertFalse(vm.ritualsPendingPush("me"))
    }

    @Test fun forgetProfileFact_tombstonesAndForgetAllClears() = runTest {
        val vm = vm()
        val a = vm.saveProfileFact(ProfileFactCategory.PERSON, "A", ProfileFactSource.SETTINGS)!!
        vm.saveProfileFact(ProfileFactCategory.RHYTHM, "B", ProfileFactSource.SETTINGS)
        assertTrue(vm.forgetProfileFact(a.id))
        assertFalse(vm.forgetProfileFact(a.id))
        assertEquals(1, facts().count { it.active })
        vm.forgetAllProfileFacts()
        assertEquals(0, facts().count { it.active })
        assertEquals("tombstones stay so the server learns", 2, facts().size)
    }
}
