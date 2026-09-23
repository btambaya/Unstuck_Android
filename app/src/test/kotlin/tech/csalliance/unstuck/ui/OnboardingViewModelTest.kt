package tech.csalliance.unstuck.ui

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.AppGraph
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.sync.WriteThrough

/**
 * The onboarding gate and the area seed, through the real AppViewModel over a real
 * in-memory store + WriteThrough (Android audit 2026-09-23, A8 + A9). Offline by
 * construction (no Supabase client), so the server's answer is handed to
 * [AppViewModel.applyOnboardingAnswer] — the same entry the post-pull reconcile uses.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OnboardingViewModelTest {

    private lateinit var store: LocalStore
    private lateinit var graph: AppGraph
    private val dispatcher = StandardTestDispatcher()
    private val drain = ViewModelDrain(dispatcher.scheduler)

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = Room.inMemoryDatabaseBuilder(ctx, UnstuckDatabase::class.java).allowMainThreadQueries().build()
        store = LocalStore(db)
        graph = AppGraph(ctx, configured = false, storeOverride = store, uidOverride = { UID })
        runCatching { androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build()) }
    }

    @After fun teardown() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        drain.drain()
        Dispatchers.resetMain()
    }

    private fun TestScope.vm(): AppViewModel = AppViewModel(
        graph = graph, writeOverride = WriteThrough(store), currentUidProvider = { UID },
    ).also { created ->
        drain.track(created)
        backgroundScope.coroutineContext.job.invokeOnCompletion { runCatching { created.viewModelScope.cancel() } }
    }

    /** What a brand-new account's first pull brings: the server's signup seed. */
    private suspend fun seedServerAreas() = listOf("Work", "Personal", "Volunteering", "Home", "Health").forEachIndexed { i, n ->
        val a = LifeArea("server-$i", n, "indigo", i)
        store.upsert(Tables.LIFE_AREAS, a, LifeArea.serializer(), a.id)
    }

    // ── A9: the gate ──────────────────────────────────────────────────────────

    @Test fun newAccount_serverSeededAreasNeverMarkItOnboarded_andTheStepsSurviveARecreation() = runTest(dispatcher) {
        seedServerAreas()
        val first = vm()
        runCurrent()
        assertNull("splash until the server answers", first.showOnboarding.value)
        first.applyOnboardingAnswer(UID, serverStruggles = emptyList(), interviewDoneAt = null, afterPull = true)
        runCurrent()
        assertEquals("a brand-new account gets the steps", true, first.showOnboarding.value)
        assertFalse("five server-seeded areas are not 'onboarded elsewhere'", graph.onboarded)

        // Rotation / dark-mode flip / process death: a fresh read must not drop the
        // user into the app mid-setup.
        val recreated = vm()
        runCurrent()
        assertNotEquals(false, recreated.showOnboarding.value)
        recreated.applyOnboardingAnswer(UID, emptyList(), null, afterPull = true)
        runCurrent()
        assertEquals(true, recreated.showOnboarding.value)
    }

    @Test fun existingAccount_leavesTheSplashForTheApp_whenTheServerAnswers() = runTest(dispatcher) {
        val vm = vm()
        runCurrent()
        assertNull("never the steps before the answer", vm.showOnboarding.value)
        // The early single-row read (before the first pull) finds struggles saved on iOS.
        vm.applyOnboardingAnswer(UID, serverStruggles = listOf("Distraction"), interviewDoneAt = null, afterPull = false)
        runCurrent()
        assertEquals("the same gate flips — no recomposition-time read", false, vm.showOnboarding.value)
        assertTrue(graph.onboarded)
    }

    @Test fun webAccountWithTasksButNoStruggles_isNotOnboardedAgain() = runTest(dispatcher) {
        seedServerAreas()
        val t = TaskItem(id = "t1", name = "My first Unstuck task", estimateMin = 15, createdAt = "2026-09-01T10:00:00.000Z", updatedAt = "2026-09-01T10:00:00.000Z")
        store.upsert(Tables.TASKS, t, TaskItem.serializer(), t.id, t.updatedAt)
        val vm = vm()
        vm.applyOnboardingAnswer(UID, serverStruggles = emptyList(), interviewDoneAt = null, afterPull = true)
        runCurrent()
        assertEquals(false, vm.showOnboarding.value)
    }

    @Test fun unknownAnswer_theDeadlineLetsTheLocalFlagDecide() = runTest(dispatcher) {
        val vm = vm()
        advanceTimeBy(AppViewModel.ONBOARDING_RESOLVE_DEADLINE_MS - 1)
        assertNull(vm.showOnboarding.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(true, vm.showOnboarding.value)
    }

    @Test fun anAccountOnboardedHere_neverSeesTheSplash() = runTest(dispatcher) {
        graph.onboarded = true
        assertEquals(false, vm().showOnboarding.value)
    }

    // ── A8: the seed ──────────────────────────────────────────────────────────

    @Test fun completeOnboarding_leavesAtOnce_andAddsOnlyPicksTheAccountLacks() = runTest(dispatcher) {
        seedServerAreas()
        val vm = vm()
        vm.applyOnboardingAnswer(UID, emptyList(), null, afterPull = true)
        runCurrent()
        assertEquals(true, vm.showOnboarding.value)

        vm.completeOnboarding(struggles = emptyList(), areas = listOf("Work", "Personal", "Home", "Family"))
        runCurrent()
        assertEquals("before any write lands", false, vm.showOnboarding.value)
        assertEquals(listOf("Family", "Health", "Home", "Personal", "Volunteering", "Work"), store.lifeAreas().first { it.size == 6 }.map { it.name }.sorted())
        assertEquals("one write, for the one area the server doesn't have", 1, lifeAreaOps().size)
    }

    @Test fun completeOnboarding_beforeThePull_seedsNoneOfTheServersDefaults() = runTest(dispatcher) {
        val vm = vm()
        // Family is the sentinel: once it lands, the seed is done.
        vm.completeOnboarding(struggles = emptyList(), areas = listOf("Work", "Personal", "Home", "Family"))
        val seeded = store.lifeAreas().first { it.isNotEmpty() }
        assertEquals(listOf("Family"), seeded.map { it.name })
        assertEquals("no life_areas op the server's unique(user_id, name) would refuse forever",
            seeded.map { it.id }, lifeAreaOps().map { it.recordId })
    }

    /** The queued life_areas writes, once there is at least one (WriteThrough saves the
     *  row, THEN enqueues — reading the outbox right after the row lands is a race). */
    private suspend fun lifeAreaOps(): List<tech.csalliance.unstuck.data.db.OutboxEntity> {
        var ops = emptyList<tech.csalliance.unstuck.data.db.OutboxEntity>()
        store.pendingCount().first { ops = store.pending().filter { it.recordTable == Tables.LIFE_AREAS }; ops.isNotEmpty() }
        return ops
    }

    private companion object { const val UID = "me" }
}
