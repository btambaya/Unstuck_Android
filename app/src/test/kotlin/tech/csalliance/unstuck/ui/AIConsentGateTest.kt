package tech.csalliance.unstuck.ui

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import tech.csalliance.unstuck.AIConsentStore
import tech.csalliance.unstuck.AIConsentSync
import tech.csalliance.unstuck.AppGraph
import tech.csalliance.unstuck.calls.AppCallEnvironment
import tech.csalliance.unstuck.core.logic.AIConsent
import tech.csalliance.unstuck.core.logic.AIConsent.Action
import tech.csalliance.unstuck.core.logic.CallProactivePrefs
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.sync.AIConsentSnapshot
import tech.csalliance.unstuck.sync.WriteThrough
import tech.csalliance.unstuck.ui.assistant.AIConsentHost

/**
 * The AI data-sharing gate on Android (core AIConsent; iOS AIConsentGateTests):
 * what runs with and without the account's OK, what "Agree" and "Not now" do,
 * an ask that never came up, turning sharing off, and app open's one look at
 * Calls. Offline: an in-memory store, no Supabase client.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AIConsentGateTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var graph: AppGraph
    private val drain = ViewModelDrain(dispatcher.scheduler)
    private val granted = AIConsent.Record("2026-09-24T10:00:00.000Z", AIConsent.VERSION)

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnstuckDatabase::class.java)
            .allowMainThreadQueries().build()
        graph = AppGraph(ApplicationProvider.getApplicationContext(), configured = false, storeOverride = LocalStore(db), uidOverride = { "u1" })
        graph.onboarded = true
        graph.aiConsent.clear()
        runCatching { androidx.work.WorkManager.initialize(ApplicationProvider.getApplicationContext(), androidx.work.Configuration.Builder().build()) }
    }

    @After fun teardown() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        drain.drain()
        Dispatchers.resetMain()
    }

    private fun TestScope.vm(): AppViewModel = AppViewModel(
        graph = graph, writeOverride = WriteThrough(graph.store), currentUidProvider = { "u1" },
    ).also { created ->
        drain.track(created)
        backgroundScope.coroutineContext.job.invokeOnCompletion { runCatching { created.viewModelScope.cancel() } }
    }

    private fun grant() = graph.aiConsent.set(AIConsent.Cache("u1", granted, pending = false))

    // ── the gate ──

    @Test fun `with the OK the action runs at once and nothing asks`() = runTest(dispatcher) {
        grant()
        val vm = vm()
        var ran = 0
        vm.withAIConsent(Action.CHAT, AIConsentHost.ASSISTANT) { ran++ }
        assertEquals(1, ran)
        assertNull(vm.aiConsentAsk.value)
    }

    @Test fun `without it the sheet asks, and Agree runs the action and records the OK`() = runTest(dispatcher) {
        val vm = vm()
        var ran = 0
        vm.withAIConsent(Action.TALK, AIConsentHost.TODAY) { ran++ }
        advanceTimeBy(10)
        val ask = vm.aiConsentAsk.value
        assertNotNull(ask)
        assertEquals(Action.TALK, ask!!.action)
        assertEquals(AIConsentHost.TODAY, ask.host)
        assertEquals(0, ran)
        vm.aiConsentSheetShown(ask.id)
        vm.agreeAIConsent()
        assertEquals(1, ran)
        assertNull(vm.aiConsentAsk.value)
        assertTrue(vm.aiConsentGranted)
        // Recorded here at once (offline it holds), pending until the account takes it.
        assertEquals(AIConsent.VERSION, graph.aiConsent.value!!.record.version)
        assertTrue(graph.aiConsent.value!!.pending)
    }

    @Test fun `Not now runs nothing, says why on the surface that asked, and leaves calls alone`() = runTest(dispatcher) {
        val vm = vm()
        vm.updateCallSettings { it.copy(enabled = true) }
        var ran = 0
        var declined = 0
        vm.withAIConsent(Action.CHAT, AIConsentHost.ASSISTANT, onDecline = { declined++ }) { ran++ }
        advanceTimeBy(10)
        vm.aiConsentSheetShown(vm.aiConsentAsk.value!!.id)
        vm.declineAIConsent()
        assertEquals(0, ran)
        assertEquals(1, declined)
        assertEquals(AIConsentHost.ASSISTANT, vm.aiConsentNote.value!!.host)
        assertEquals(AIConsent.decline(Action.CHAT).note, vm.aiConsentNote.value!!.text)
        assertFalse(vm.aiConsentGranted)
        assertTrue("only app open's Not now turns calls off", vm.callSettings.value.enabled)
        // The next gate from that surface clears its old line.
        grant()
        vm.withAIConsent(Action.CHAT, AIConsentHost.ASSISTANT) {}
        assertNull(vm.aiConsentNote.value)
    }

    @Test fun `one sheet at a time — a second gate while one is up does nothing`() = runTest(dispatcher) {
        val vm = vm()
        vm.withAIConsent(Action.CHAT, AIConsentHost.ASSISTANT) {}
        advanceTimeBy(10)
        val first = vm.aiConsentAsk.value!!
        vm.aiConsentSheetShown(first.id)
        vm.withAIConsent(Action.CALLS_ON, AIConsentHost.CALL_SETTINGS) {}
        advanceTimeBy(10)
        assertEquals(first.id, vm.aiConsentAsk.value!!.id)
    }

    @Test fun `an ask whose sheet never came up is dropped so the next gate can ask`() = runTest(dispatcher) {
        val vm = vm()
        vm.aiConsentShowGraceMs = 50
        var ran = 0
        vm.withAIConsent(Action.TALK, AIConsentHost.TODAY) { ran++ }
        advanceTimeBy(10)
        assertNotNull(vm.aiConsentAsk.value)
        advanceUntilIdle()
        assertNull(vm.aiConsentAsk.value)
        assertEquals(0, ran)
        assertNull("nobody saw it — nothing to explain", vm.aiConsentNote.value)
        vm.withAIConsent(Action.CHAT, AIConsentHost.ASSISTANT) {}
        advanceTimeBy(10)
        assertEquals(AIConsentHost.ASSISTANT, vm.aiConsentAsk.value?.host)
    }

    @Test fun `an ask on screen is left alone`() = runTest(dispatcher) {
        val vm = vm()
        vm.aiConsentShowGraceMs = 50
        vm.withAIConsent(Action.CHAT, AIConsentHost.ASSISTANT) {}
        advanceTimeBy(10)
        vm.aiConsentSheetShown(vm.aiConsentAsk.value!!.id)
        advanceUntilIdle()
        assertEquals(AIConsentHost.ASSISTANT, vm.aiConsentAsk.value?.host)
    }

    @Test fun `the sheet torn down under its host counts as Not now`() = runTest(dispatcher) {
        val vm = vm()
        var ran = 0
        var declined = 0
        vm.withAIConsent(Action.CHAT, AIConsentHost.ASSISTANT, onDecline = { declined++ }) { ran++ }
        advanceTimeBy(10)
        val id = vm.aiConsentAsk.value!!.id
        vm.aiConsentSheetShown(id)
        vm.aiConsentSheetGone(id)
        assertNull(vm.aiConsentAsk.value)
        assertEquals(0, ran)
        assertEquals(1, declined)
        assertEquals(AIConsentHost.ASSISTANT, vm.aiConsentNote.value?.host)
        // …but going AFTER an answer changes nothing.
        vm.aiConsentSheetGone(id)
        assertEquals(1, declined)
    }

    // ── Settings → AI data sharing → off ──

    @Test fun `turning sharing off clears the OK, turns calls off and says so`() = runTest(dispatcher) {
        grant()
        val vm = vm()
        vm.updateCallSettings { it.copy(enabled = true) }
        vm.setCallProactivePrefs(CallProactivePrefs.DEFAULTS.copy(morningEnabled = true, afterBlockEnabled = true))
        vm.revokeAIConsent()
        assertFalse(vm.aiConsentGranted)
        assertNull(graph.aiConsent.value!!.record.at)
        assertTrue("pending until the account takes it", graph.aiConsent.value!!.pending)
        assertFalse(vm.callSettings.value.enabled)
        val p = vm.callProactivePrefs.value
        assertFalse(p.morningEnabled || p.eveningEnabled || p.afterBlockEnabled)
        assertEquals(AIConsentHost.SETTINGS, vm.aiConsentNote.value!!.host)
        assertEquals(AIConsent.REVOKED_NOTE, vm.aiConsentNote.value!!.text)
    }

    // ── app open ──

    @Test fun `app open asks once when calls are on without the OK, and Not now turns them off`() = runTest(dispatcher) {
        val vm = vm()
        vm.updateCallSettings { it.copy(enabled = true) }
        vm.setCallProactivePrefs(CallProactivePrefs.DEFAULTS.copy(eveningEnabled = true))
        // Nothing asks before this launch has read the account.
        vm.askAboutCallsOnOpenIfNeeded(idle = true)
        advanceUntilIdle()
        assertNull(vm.aiConsentAsk.value)
        vm.refreshAIConsent(force = true)
        advanceUntilIdle()
        // Never over something else on screen.
        vm.askAboutCallsOnOpenIfNeeded(idle = false)
        advanceUntilIdle()
        assertNull(vm.aiConsentAsk.value)
        vm.askAboutCallsOnOpenIfNeeded(idle = true)
        // The booked-calls read is a real Room query: wait for the ask it leads to.
        val ask = kotlinx.coroutines.withTimeout(5_000) { vm.aiConsentAsk.first { it != null } }!!
        assertEquals(Action.CALLS_ON_OPEN, ask.action)
        assertEquals(AIConsentHost.ROOT, ask.host)
        vm.aiConsentSheetShown(ask.id)
        vm.declineAIConsent()
        assertFalse(vm.callSettings.value.enabled)
        assertFalse(vm.callProactivePrefs.value.eveningEnabled)
        assertEquals(AIConsentHost.ROOT, vm.aiConsentNote.value!!.host)
        assertEquals(AIConsent.CALLS_TURNED_OFF_NOTE, vm.aiConsentNote.value!!.text)
        // Once per launch.
        vm.clearAIConsentNote()
        vm.updateCallSettings { it.copy(enabled = true) }
        vm.setCallProactivePrefs(CallProactivePrefs.DEFAULTS.copy(eveningEnabled = true))
        vm.askAboutCallsOnOpenIfNeeded(idle = true)
        advanceUntilIdle()
        assertNull(vm.aiConsentAsk.value)
    }

    @Test fun `calls count as on only when something can ring, and with the OK app open never asks`() = runTest(dispatcher) {
        val vm = vm()
        vm.refreshAIConsent(force = true)
        advanceUntilIdle()
        // The switch alone (on by default) rings nothing.
        vm.updateCallSettings { it.copy(enabled = true) }
        assertFalse(vm.callsAreOnForAIConsent())
        vm.setCallProactivePrefs(CallProactivePrefs.DEFAULTS.copy(morningEnabled = true))
        assertTrue(vm.callsAreOnForAIConsent())
        // Off on this phone: every call is declined anyway.
        vm.updateCallSettings { it.copy(enabled = false) }
        assertFalse(vm.callsAreOnForAIConsent())
        // With the OK: nothing to ask (decided before any read).
        vm.updateCallSettings { it.copy(enabled = true) }
        grant()
        vm.askAboutCallsOnOpenIfNeeded(idle = true)
        assertNull(vm.aiConsentAsk.value)
    }

    // ── the call path reads the same copy ──

    @Test fun `the ring reads this phone's copy for the signed-in account`() {
        assertFalse(AppCallEnvironment.hasAIConsent(graph, "u1"))
        grant()
        assertTrue(AppCallEnvironment.hasAIConsent(graph, "u1"))
        assertFalse("another account's copy never counts", AppCallEnvironment.hasAIConsent(graph, "u2"))
        assertTrue("before the session is known, the last account's copy answers", AppCallEnvironment.hasAIConsent(graph, null))
        assertFalse("no graph: fail closed", AppCallEnvironment.hasAIConsent(null, "u1"))
    }

    // ── the device copy (AIConsentStore / AIConsentSync) ──

    @Test fun `the device copy persists and a sign-out wipe clears it`() {
        grant()
        assertEquals(granted, AIConsentStore(ApplicationProvider.getApplicationContext()).value?.record)
        graph.aiConsent.clear()
        assertNull(AIConsentStore(ApplicationProvider.getApplicationContext()).value)
    }

    @Test fun `a change made here goes up, and one that didn't land is sent again instead of read over`() = runTest {
        val store = AIConsentStore(ApplicationProvider.getApplicationContext()).apply { clear() }
        var online = false
        val writes = mutableListOf<AIConsent.Record>()
        var fetches = 0
        val sync = AIConsentSync(
            store, auth = { null }, uid = { "u1" },
            fetch = { fetches++; AIConsentSnapshot("u1", AIConsent.Record.NONE) },
            write = { r -> writes += r; if (online) r else null },
        )
        sync.set(granted)
        assertTrue("offline: it holds here, pending", store.value!!.pending)
        assertTrue(sync.granted)
        // The account's older "no" must not undo it: the refresh re-sends instead of reading.
        online = true
        sync.refresh(force = true)
        assertEquals(0, fetches)
        assertEquals(2, writes.size)
        assertFalse(store.value!!.pending)
        assertTrue(store.value!!.record.isGranted)
    }

    @Test fun `a fresh read follows the account, at most once a minute unless forced`() = runTest {
        val store = AIConsentStore(ApplicationProvider.getApplicationContext()).apply { clear() }
        var now = 1_000_000L
        var server = granted
        var fetches = 0
        val sync = AIConsentSync(
            store, auth = { null }, uid = { "u1" }, nowMs = { now },
            fetch = { fetches++; AIConsentSnapshot("u1", server) }, write = { it },
        )
        sync.refresh(force = false)
        assertTrue(sync.granted)
        // Turned off on the web: the next read turns it off here too — once the minute is up.
        server = AIConsent.revoked(granted)
        sync.refresh(force = false)
        assertEquals(1, fetches)
        assertTrue(sync.granted)
        now += AIConsentSync.MIN_FETCH_GAP_MS
        sync.refresh(force = false)
        assertEquals(2, fetches)
        assertFalse(sync.granted)
        sync.refresh(force = true)
        assertEquals(3, fetches)
    }

    @Test fun `signed out there is nothing to read`() = runTest {
        val store = AIConsentStore(ApplicationProvider.getApplicationContext()).apply { clear() }
        var fetches = 0
        val sync = AIConsentSync(store, auth = { null }, uid = { null }, fetch = { fetches++; null }, write = { it })
        assertFalse(sync.refresh(force = true))
        assertEquals(0, fetches)
        assertFalse(sync.granted)
    }
}
