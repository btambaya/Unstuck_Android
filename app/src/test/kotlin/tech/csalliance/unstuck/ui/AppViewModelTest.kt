package tech.csalliance.unstuck.ui

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import tech.csalliance.unstuck.AppGraph
import tech.csalliance.unstuck.core.logic.SharedSessionState
import tech.csalliance.unstuck.core.logic.isTaskBlock
import tech.csalliance.unstuck.core.logic.visibleTasks
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.CollectionItem
import tech.csalliance.unstuck.core.model.FocusTreatment
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.core.model.LiveSession
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.core.model.TagRow
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.model.TaskListView
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.OutboxEntity
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.sync.CoFocusControl
import tech.csalliance.unstuck.sync.PreferencesClient
import tech.csalliance.unstuck.sync.WriteThrough
import tech.csalliance.unstuck.core.logic.Moment
import tech.csalliance.unstuck.core.logic.MomentAction
import tech.csalliance.unstuck.core.logic.MomentKind
import tech.csalliance.unstuck.core.logic.MomentRun
import tech.csalliance.unstuck.core.logic.addDaysIso

/**
 * The first unit-test suite for the :app module — exercising the highest-risk
 * orchestration paths on [AppViewModel] (the ~1300-line write surface).
 *
 * SUT instantiation (see the suite report at the bottom of this file):
 *  - A real (in-memory) Room [UnstuckDatabase] → real [LocalStore] → real
 *    [WriteThrough]. So every assertion exercises the TRUE write path (the same
 *    :core mutation rule → LocalStore round-trip → outbox enqueue the production
 *    code uses), not a mock.
 *  - The AppGraph itself is built with no Supabase anon key, so graph.configured
 *    is false and graph.coordinator/provider are null (no network, no realtime,
 *    no auth observer). The WriteThrough is injected via the additive test seam.
 *  - viewModelScope runs on a StandardTestDispatcher (Dispatchers.setMain), so
 *    every launchWrite {} coroutine is driven deterministically by
 *    advanceUntilIdle(). nowProvider injects a fixed clock for focus timing.
 *
 * The AppViewModel's reactive collections (tasks/blocks/...) are
 * SharingStarted.WhileSubscribed StateFlows, so their `.value` only reflects the
 * store WHILE something collects them. [subscribeReads] keeps a background
 * collector alive for the duration of each test; assertions read the LocalStore
 * (the source of truth) directly.
 */
// Use the stock Application (NOT the production UnstuckApp, which would build a
// fully-configured AppGraph → a real SupabaseClientProvider → network on a unit
// test). These tests inject a real WriteThrough over an in-memory store instead.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AppViewModelTest {

    private lateinit var db: UnstuckDatabase
    private lateinit var store: LocalStore
    private lateinit var write: WriteThrough
    private lateinit var graph: AppGraph
    private val dispatcher = StandardTestDispatcher()

    // Controllable identity for the shared-collection / shared-task-done paths.
    private var uid: String? = "me"
    private var displayName: String? = "Ada"
    // Fixed clock for deterministic focus-session timing.
    private var nowMs: Long = 1_700_000_000_000L

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnstuckDatabase::class.java)
            .allowMainThreadQueries().build()
        store = LocalStore(db)
        // configured=false → no SupabaseClientProvider/coordinator (offline); the
        // in-memory store is shared by the graph (VM reads) AND the WriteThrough
        // (VM writes), so the VM round-trips the SAME store these tests assert on.
        graph = AppGraph(ApplicationProvider.getApplicationContext(), configured = false, storeOverride = store)
        // Real WriteThrough over that store — the production write path. (Its
        // internal nowMillis seam is :sync-private and only stamps outbox createdAt,
        // which these tests never assert on, so the default clock is fine.)
        write = WriteThrough(graph.store)
        // The remote-resume side effect cancels the paused check-in via
        // WorkManager.getInstance — in prod the manifest initializer has run;
        // under Robolectric we initialize it once (runCatching: the singleton may
        // survive across tests sharing a sandbox, and re-initialize throws).
        runCatching {
            androidx.work.WorkManager.initialize(
                ApplicationProvider.getApplicationContext(),
                androidx.work.Configuration.Builder().build(),
            )
        }
    }

    @After fun teardown() {
        // Intentionally do NOT close the in-memory db here. The VM exposes its
        // collections as SharingStarted.WhileSubscribed StateFlows whose upstream
        // Room flow runs on a REAL thread (LocalStore's flowOn(Default)) and lingers
        // briefly after runTest cancels its collectors. Closing the db would race
        // that lingering read → "connection pool closed". The in-memory db is
        // per-builder and GC'd with the test; Robolectric sandboxes each test.
        //
        // DO drain the main looper. Every AppViewModel built here posts to it
        // (AppViewModel.init → CallVoiceService.bind → main().post { … }) and nothing
        // in these tests ever runs that, so each test used to END with runnables still
        // queued — which is where Robolectric's "Main looper has queued unexecuted
        // runnables" note on every failure in this class came from, and which leaves
        // one test's posted work to fire inside whichever test idles the looper next.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        // ...and FINISH this test's ViewModels before Main is handed back.
        // Cancelling them (below, at the end of the test body) only asks; a child
        // parked in a Room call on a real thread keeps unwinding afterwards and
        // resumes on Dispatchers.Main — which by then belongs to the next test.
        // That was this suite's order-dependent flake, in both of its shapes
        // (see ViewModelDrain). Measured: 17 tests here reached this line with a
        // live viewModelScope job.
        drain.drain()
        Dispatchers.resetMain()
    }

    /** Finishes every ViewModel this test built — see [ViewModelDrain]. */
    private val drain = ViewModelDrain(dispatcher.scheduler)

    /**
     * Build the SUT — and make sure it DIES WITH THE TEST.
     *
     * Nothing else clears these ViewModels: `onCleared()` only runs under a real
     * ViewModelStore, so every AppViewModel a test built used to keep its
     * `viewModelScope` alive for the rest of the JVM — the WhileSubscribed
     * StateFlows still collecting Room on a real Dispatchers.Default thread, the
     * co-focus collectors, the divergence-grace `delay`, any `launchWrite` still in
     * flight. Two things followed, and between them they were this suite's wandering
     * order-dependent flake (a different innocent test failing each run):
     *
     *  - `viewModelScope` is `Dispatchers.Main`, which @Before/@After swap per test.
     *    A leaked Room continuation resuming on its real executor thread would touch
     *    Main exactly as `setMain`/`resetMain` wrote it, and kotlinx-coroutines-test
     *    threw "Dispatchers.Main is used concurrently with setting it" — sometimes at
     *    `teardown`, sometimes inside whichever test was mid-flight.
     *  - Worse, once the next test called `setMain`, a previous test's leftover
     *    coroutines resumed onto the NEW test's scheduler — foreign tasks (and
     *    foreign `delay`s) inside `advanceUntilIdle()`.
     *
     * `runTest` cancels `backgroundScope` after the body and then drains the
     * scheduler, so hooking the cancellation there unwinds the ViewModel while the
     * scheduler is still live — by the time @After resets Main, nothing of this test
     * is left running.
     */
    private fun TestScope.vm(
        coFocus: ((String) -> tech.csalliance.unstuck.sync.CoFocusChannel?)? = null,
    ): AppViewModel = AppViewModel(
        graph = graph,
        writeOverride = write,
        currentUidProvider = { uid },
        currentNameProvider = { displayName },
        nowProvider = { nowMs },
        coFocusChannelFactory = coFocus,
    ).also { created ->
        drain.track(created)
        backgroundScope.coroutineContext.job.invokeOnCompletion {
            runCatching { created.viewModelScope.cancel() }
        }
    }

    /**
     * Keep the WhileSubscribed StateFlows hot for the duration of the test so that
     * `vm.tasks.value` etc. mirror the store inside the method under test, AND
     * block until each flow has actually emitted its seeded value.
     *
     * Why the await is non-trivial: LocalStore.observe() applies
     * `flowOn(Dispatchers.Default)`, so the Room emission + decode runs on a REAL
     * background thread, off the runTest virtual scheduler. `advanceUntilIdle()`
     * alone can't synchronize with it. `flow.first { ... }` subscribes and truly
     * suspends until that real emission lands (runTest only skips `delay`, not
     * genuine suspension), so it's the deterministic gate.
     */
    private suspend fun TestScope.subscribeReads(vm: AppViewModel, vararg flows: StateFlow<List<*>>) {
        flows.forEach { f -> backgroundScope.launch { f.collect { } } }
        backgroundScope.launch { vm.liveSession.collect { } }
        // Await each flow reflecting the store's current contents (by id set).
        flows.forEach { f -> awaitMirror(vm, f) }
    }

    /** Suspend until [flow]'s latest value matches the corresponding LocalStore
     *  table snapshot (by id set), so the VM's `.value` reads are populated. */
    private suspend fun awaitMirror(vm: AppViewModel, flow: StateFlow<List<*>>) {
        val expected: Set<String> = when (flow) {
            vm.tasks -> store.tasks().first().map { it.id }
            vm.blocks -> store.blocks().first().map { it.id }
            vm.collections -> store.collections().first().map { it.id }
            vm.tags -> store.tags().first().map { it.id }
            vm.lifeAreas -> store.lifeAreas().first().map { it.id }
            vm.captures -> store.captures().first().map { it.id }
            else -> error("unmapped flow in awaitMirror")
        }.toSet()
        flow.first { it.mapNotNull { e -> idOf(e) }.toSet() == expected }
    }

    private fun idOf(e: Any?): String? = when (e) {
        is TaskItem -> e.id
        is CalBlock -> e.id
        is ItemCollection -> e.id
        is TagRow -> e.id
        is LifeArea -> e.id
        is Session -> e.id
        is tech.csalliance.unstuck.core.model.Capture -> e.id
        else -> null
    }

    private fun task(
        id: String, name: String = "T", estimateMin: Int = 25,
        recurrence: Recurrence? = null, totalFocused: Int = 0, done: Boolean = false,
        sourceCollectionId: String? = null, sourceItemId: String? = null,
    ) = TaskItem(
        id = id, name = name, estimateMin = estimateMin, recurrence = recurrence,
        totalFocused = totalFocused, done = done,
        sourceCollectionId = sourceCollectionId, sourceItemId = sourceItemId,
        createdAt = "2026-05-21T10:00:00.000Z", updatedAt = "2026-05-21T10:00:00.000Z",
    )

    private suspend fun seedTask(t: TaskItem) = store.upsert(Tables.TASKS, t, TaskItem.serializer(), t.id, t.updatedAt)
    private suspend fun seedBlock(b: CalBlock) = store.upsert(Tables.CAL_BLOCKS, b, CalBlock.serializer(), b.id)
    private suspend fun seedCollection(c: ItemCollection) = store.upsert(Tables.COLLECTIONS, c, ItemCollection.serializer(), c.id)

    private suspend fun loadTask(id: String): TaskItem? = store.tasks().first().firstOrNull { it.id == id }
    private suspend fun loadBlock(id: String): CalBlock? = store.blocks().first().firstOrNull { it.id == id }
    private suspend fun loadCollection(id: String): ItemCollection? = store.collections().first().firstOrNull { it.id == id }

    // Awaiting reads. The VM's writes land on a REAL Room thread (LocalStore's
    // flowOn(Default)), so they aren't visible the instant advanceUntilIdle()
    // returns. `store.xxx().first { predicate }` subscribes and truly suspends
    // until Room re-emits with the write applied — the deterministic post-condition
    // gate. (runTest only fast-forwards `delay`, not genuine suspension.) Each
    // helper returns the matching row once the list-level predicate holds.
    private suspend fun awaitTasks(predicate: (List<TaskItem>) -> Boolean): List<TaskItem> =
        store.tasks().first(predicate)
    private suspend fun awaitTask(id: String, predicate: (TaskItem) -> Boolean): TaskItem =
        awaitTasks { l -> l.firstOrNull { it.id == id }?.let(predicate) == true }.first { it.id == id }
    private suspend fun awaitNoTask(id: String) { store.tasks().first { l -> l.none { it.id == id } } }
    private suspend fun awaitBlocks(predicate: (List<CalBlock>) -> Boolean): List<CalBlock> =
        store.blocks().first(predicate)
    private suspend fun awaitBlock(id: String, predicate: (CalBlock) -> Boolean): CalBlock =
        awaitBlocks { l -> l.firstOrNull { it.id == id }?.let(predicate) == true }.first { it.id == id }
    private suspend fun awaitNoBlock(id: String) { store.blocks().first { l -> l.none { it.id == id } } }
    private suspend fun awaitCollection(id: String, predicate: (ItemCollection) -> Boolean): ItemCollection =
        store.collections().first { l -> l.firstOrNull { it.id == id }?.let(predicate) == true }.first { it.id == id }
    private suspend fun awaitTags(predicate: (List<TagRow>) -> Boolean): List<TagRow> =
        store.tags().first(predicate)
    private suspend fun awaitLifeAreas(predicate: (List<LifeArea>) -> Boolean): List<LifeArea> =
        store.lifeAreas().first(predicate)
    private suspend fun awaitSessions(predicate: (List<Session>) -> Boolean): List<Session> =
        store.sessions().first(predicate)
    private suspend fun awaitCaptures(predicate: (List<tech.csalliance.unstuck.core.model.Capture>) -> Boolean): List<tech.csalliance.unstuck.core.model.Capture> =
        store.captures().first(predicate)
    private suspend fun awaitLiveSession(predicate: (LiveSession?) -> Boolean): LiveSession? =
        store.liveSession().first(predicate)

    /**
     * Suspend until the OUTBOX satisfies [predicate], then return that snapshot.
     *
     * Reading `store.pending()` straight after an `awaitXxx { row }` was a RACE, and
     * the suite's worst flake. A VM write is ONE coroutine that writes the record
     * first and queues its outbox op second (WriteThrough.upsertX / the shared-list
     * `rpc` path), and BOTH hops leave the test scheduler — Room's executor and
     * LocalStore's flowOn(Default). So `advanceUntilIdle()` returns while the write
     * is still in flight, and gating on the record only proves the FIRST half landed:
     * the outbox read that followed found nothing about half the time under load.
     *
     * `pendingCount()` is the outbox table's OWN Room flow, so this gate is
     * event-driven like every other awaitXxx here — no polling, no sleep, no
     * timeout: it re-reads the queue on the initial emission and on every later
     * outbox change, and returns the first snapshot that satisfies [predicate].
     *
     * The outbox op is the LAST durable thing a write does, so after this the rest
     * of the write coroutine is pure in-memory work queued on the test dispatcher —
     * one `advanceUntilIdle()` flushes it (that is how the in-memory assertions that
     * follow, e.g. `momentDone` / a receipt's `undone`, become deterministic too).
     */
    private suspend fun awaitPending(predicate: (List<OutboxEntity>) -> Boolean): List<OutboxEntity> {
        var snapshot: List<OutboxEntity> = emptyList()
        store.pendingCount().first { snapshot = store.pending(); predicate(snapshot) }
        return snapshot
    }

    // -----------------------------------------------------------------------
    // toggleDone: recurring OCCURRENCE vs plain task vs template semantics
    // -----------------------------------------------------------------------

    @Test fun toggleDone_onRecurringOccurrence_completesBlockNotTemplate() = runTest(dispatcher) {
        // A recurring TEMPLATE + one occurrence cal_block. The projected occurrence
        // row's id IS the block id, so toggleDone must flip the BLOCK's done, never
        // end the series by mutating the template.
        val template = task("tpl", name = "Standup", recurrence = Recurrence.Daily())
        val occ = CalBlock(id = "occ1", taskId = "tpl", taskName = "Standup", startTime = "09:00", durationMinutes = 25, date = "2026-05-22", kind = CalBlockKind.TASK)
        seedTask(template); seedBlock(occ)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        // The UI hands toggleDone the PROJECTED occurrence row: id = block id.
        val projectedRow = template.copy(id = occ.id, recurrence = null)
        vm.toggleDone(projectedRow)
        advanceUntilIdle()

        val doneBlock = awaitBlock("occ1") { it.done }
        assertFalse("block stays unskipped", doneBlock.skipped)
        assertNotNull("completion timestamp stamped on the block", doneBlock.completedAt)
        assertFalse("template task must NOT be marked done (series intact)", loadTask("tpl")!!.done)

        // Un-toggle clears done + completedAt on the block.
        vm.toggleDone(projectedRow)
        advanceUntilIdle()
        val undone = awaitBlock("occ1") { !it.done }
        assertNull(undone.completedAt)
    }

    @Test fun toggleDone_onPlainTask_flipsTaskAndStampsCompletion() = runTest(dispatcher) {
        val t = task("t1", name = "Email")
        seedTask(t)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        vm.toggleDone(t)
        advanceUntilIdle()

        val done = awaitTask("t1") { it.done }
        assertNotNull("completedAt stamped on first completion", done.completedAt)
        // The write went through the real WriteThrough → an outbox upsert is queued.
        awaitPending { l -> l.any { it.recordTable == Tables.TASKS && it.recordId == "t1" && it.op == "upsert" } }
    }

    // -----------------------------------------------------------------------
    // toggleDone lands on the STORED row (parity with iOS build 81, audit
    // 2026-09-22 C5) and never flips a series' template done (C3)
    // -----------------------------------------------------------------------

    @Test fun toggleDone_flipsTheStoredRowNotTheCallersSnapshot() = runTest(dispatcher) {
        val snapshot = task("t1", name = "Email")
        // A rename synced in after the caller composed its copy.
        seedTask(snapshot.copy(name = "Email Anna", updatedAt = "2026-05-21T11:00:00.000Z"))
        val vm = vm()

        vm.toggleDoneNow(snapshot)
        val done = loadTask("t1")!!
        assertTrue(done.done)
        assertEquals("the rename is kept", "Email Anna", done.name)
        assertNotNull(done.completedAt)

        // A second tap from the same stale (open) copy is already in its target
        // state: no write, the first completion time stands.
        val ops = store.pending().size
        vm.toggleDoneNow(snapshot)
        assertEquals(done, loadTask("t1"))
        assertEquals(ops, store.pending().size)
    }

    @Test fun toggleDone_doesNothingWhenTheStoredRowIsAlreadyInThatState() = runTest(dispatcher) {
        val stamped = task("t1", done = true).copy(completedAt = "2026-05-21T09:00:00.000Z")
        seedTask(stamped)
        val vm = vm()

        vm.toggleDoneNow(stamped.copy(done = false, completedAt = null))   // ticked elsewhere meanwhile
        assertEquals(stamped, loadTask("t1"))
        assertTrue("nothing queued", store.pending().isEmpty())
    }

    @Test fun toggleDone_neverRecreatesAMissingRow() = runTest(dispatcher) {
        val vm = vm()
        vm.toggleDoneNow(task("ghost", name = "Deleted elsewhere"))
        assertNull(loadTask("ghost"))
        assertTrue(store.pending().isEmpty())
    }

    @Test fun toggleDone_refusesADoneFlipOnASeriesTemplate_butReopensAnEndedOne() = runTest(dispatcher) {
        val template = task("tpl", name = "Meds", recurrence = Recurrence.Daily())
        seedTask(template)
        val vm = vm()

        vm.toggleDoneNow(template)
        assertFalse("the series is never ended", loadTask("tpl")!!.done)
        assertTrue(store.pending().isEmpty())

        // A series the old path ended can still be reopened.
        seedTask(template.copy(done = true, completedAt = "2026-05-21T09:00:00.000Z", updatedAt = "2026-05-21T11:00:00.000Z"))
        vm.toggleDoneNow(template.copy(done = true))
        val reopened = loadTask("tpl")!!
        assertFalse(reopened.done)
        assertNull(reopened.completedAt)
    }

    // -----------------------------------------------------------------------
    // finishFocus lands only its delta on the STORED row (parity with iOS
    // build 81, audit 2026-09-22 C5)
    // -----------------------------------------------------------------------

    private suspend fun startLive(taskId: String) = store.setLiveSession(
        LiveSession(id = "s1", taskId = taskId, sessionStart = nowMs - 600_000L, sessionEstimateMin = 25, treatment = FocusTreatment.AMBIENT),
    )

    @Test fun finishFocus_landsOnlyTheDeltaOnTheStoredRow() = runTest(dispatcher) {
        val snapshot = task("t1", name = "Write report", totalFocused = 120)
        seedTask(snapshot)
        val vm = vm()
        startLive("t1")
        // Renamed and given a first step elsewhere while the session ran.
        seedTask(snapshot.copy(name = "Write the report", firstPhysicalAction = "Open the doc", updatedAt = "2026-05-21T11:00:00.000Z"))

        assertTrue(vm.finishFocusNow(snapshot, markDone = false))
        val after = loadTask("t1")!!
        assertEquals("Write the report", after.name)
        assertEquals("Open the doc", after.firstPhysicalAction)
        assertEquals(720, after.totalFocused)
    }

    @Test fun finishFocus_neverReopensOrReStampsATaskCompletedDuringTheSession() = runTest(dispatcher) {
        val snapshot = task("t1", name = "Ship")
        seedTask(snapshot)
        val vm = vm()
        startLive("t1")
        seedTask(snapshot.copy(done = true, completedAt = "2026-05-21T09:00:00.000Z", updatedAt = "2026-05-21T11:00:00.000Z"))

        assertTrue(vm.finishFocusNow(snapshot, markDone = true))
        val after = loadTask("t1")!!
        assertTrue(after.done)
        assertEquals("the real completion time stands", "2026-05-21T09:00:00.000Z", after.completedAt)
        assertEquals(600, after.totalFocused)
    }

    @Test fun finishFocus_markDoneRespectsARepeatSetDuringTheSession() = runTest(dispatcher) {
        val snapshot = task("t1", name = "Stretch")
        seedTask(snapshot)
        val vm = vm()
        startLive("t1")
        seedTask(snapshot.copy(recurrence = Recurrence.Daily(), updatedAt = "2026-05-21T11:00:00.000Z"))

        assertTrue(vm.finishFocusNow(snapshot, markDone = true))
        val after = loadTask("t1")!!
        assertFalse("a series is never ended by a finish", after.done)
        assertEquals(Recurrence.Daily(), after.recurrence)
        assertEquals(600, after.totalFocused)
    }

    @Test fun finishFocus_doesNotRecreateADeletedTask_andItsSessionCarriesNoTaskId() = runTest(dispatcher) {
        val snapshot = task("t1", name = "Call mum")
        seedTask(snapshot)
        val vm = vm()
        startLive("t1")
        store.delete(Tables.TASKS, "t1")   // deleted on another device mid-session

        assertTrue(vm.finishFocusNow(snapshot, markDone = true))
        assertNull("never re-created from the copy", loadTask("t1"))
        val session = store.sessions().first().single()
        assertNull("sessions.task_id references tasks(id)", session.taskId)
        assertEquals(600, session.actualSec)
        assertNull(store.getLiveSession())
        assertTrue(store.pending().none { it.recordTable == Tables.TASKS })
    }

    // -----------------------------------------------------------------------
    // A refused shared tick is reported, never swallowed (audit 2026-09-22 SC-12)
    // -----------------------------------------------------------------------

    @Test fun completeSharedTask_aTickThatDidNotLand_isReportedForTheSheetsRollback() = runTest(dispatcher) {
        // No sharing client here, so nothing can reach shared_task_set_done: the
        // detail sheet must hear that the tick didn't land (it used to keep a
        // "✓ Completed" that never happened) — in plain words, never raw text.
        val vm = vm()
        var refused: String? = null
        vm.completeSharedTask("owners-task", done = true) { refused = it }
        advanceUntilIdle()
        assertEquals("Couldn't update this task — try again.", refused)
        assertTrue("an unknown share still falls back to the level check", vm.sharedTaskAllowsTick("owners-task"))
    }

    @Test fun finishFocus_aSharedTickThatDidNotLand_isReportedToTheCaller() = runTest(dispatcher) {
        // The assistant words finish_focus off this. The Shared-with-you list is
        // empty here, as it is while no screen collects it (a call with the app in
        // the background), so the pre-check lets the tick through; the RPC not
        // taking it (no sharing client in this harness) must come back as a
        // refusal, never read as a completion (SC-12).
        val vm = vm()
        store.setLiveSession(
            LiveSession(id = "sess1", taskId = "owners-task", sessionStart = nowMs - 300_000L, sessionEstimateMin = 25, treatment = FocusTreatment.AMBIENT, sharedTitle = "Their brief", sharedLevel = "partner"),
        )
        var heard: String? = "unset"
        assertTrue(vm.finishFocusNow(task("owners-task", name = "Their brief"), markDone = true) { heard = it })
        assertEquals("not_configured", heard)
    }

    // -----------------------------------------------------------------------
    // setRecurrence moves the done state across (parity with iOS build 81,
    // audit 2026-09-22 C3)
    // -----------------------------------------------------------------------

    @Test fun setRecurrence_neverOnATickedDay_carriesTheTickOntoTheTask() = runTest(dispatcher) {
        val today = Clock.todayIso()
        val template = task("tpl", name = "Meditate", recurrence = Recurrence.Daily())
        seedTask(template)
        seedBlock(CalBlock(id = "d0", taskId = "tpl", taskName = "Meditate", startTime = "07:00", durationMinutes = 20, date = today,
            kind = CalBlockKind.TASK, done = true, completedAt = "2026-05-21T07:20:00.000Z"))
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        vm.setRecurrence(template, null)
        advanceUntilIdle()

        val after = awaitTask("tpl") { it.recurrence == null }
        assertTrue("today was ticked, so the task is done", after.done)
        assertEquals("2026-05-21T07:20:00.000Z", after.completedAt)
    }

    @Test fun setRecurrence_onATaskDoneToday_reopensItAndKeepsTheDayTicked() = runTest(dispatcher) {
        val today = Clock.todayIso()
        val doneAt = java.time.Instant.now().toString()
        val t = task("t1", name = "Stretch", done = true).copy(completedAt = doneAt)
        seedTask(t)
        seedBlock(CalBlock(id = "s0", taskId = "t1", taskName = "Stretch", startTime = "07:30", durationMinutes = 20, date = today, kind = CalBlockKind.TASK))
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        vm.setRecurrence(t, Recurrence.Daily())
        advanceUntilIdle()

        val after = awaitTask("t1") { it.recurrence != null }
        assertFalse("a done template is an ended series", after.done)
        assertNull(after.completedAt)
        val slot = awaitBlock("s0") { it.done }
        assertEquals("the day keeps the task's own completion time", doneAt, slot.completedAt)
    }

    // -----------------------------------------------------------------------
    // finishFocus / focus-session finalize accumulation
    // -----------------------------------------------------------------------

    @Test fun finishFocus_endForNow_accumulatesFocusedTimeAndWritesSession() = runTest(dispatcher) {
        val t = task("t1", name = "Write report", estimateMin = 50, totalFocused = 120)
        seedTask(t)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        // 10 minutes of focus: sessionStart 600s before the fixed now.
        store.setLiveSession(
            LiveSession(id = "sess1", taskId = "t1", sessionStart = nowMs - 600_000L, sessionEstimateMin = 50, treatment = FocusTreatment.AMBIENT, priorAccumulatedSec = 120),
        )
        advanceUntilIdle()

        vm.finishFocus(t, markDone = false)
        advanceUntilIdle()

        val after = awaitTask("t1") { it.totalFocused == 720 }
        assertFalse("end-for-now keeps the task open", after.done)
        // Session row recorded with the reused live id + this session's elapsed (600s).
        val session = awaitSessions { it.isNotEmpty() }.single()
        assertEquals("sess1", session.id)
        assertEquals(600, session.actualSec)
        assertEquals("t1", session.taskId)
        // Live session cleared.
        awaitLiveSession { it == null }
    }

    @Test fun finishFocus_markDone_completesTaskAndAccumulates() = runTest(dispatcher) {
        val t = task("t1", name = "Ship", estimateMin = 25, totalFocused = 0)
        seedTask(t)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        store.setLiveSession(
            LiveSession(id = "sess1", taskId = "t1", sessionStart = nowMs - 300_000L, sessionEstimateMin = 25, treatment = FocusTreatment.AMBIENT),
        )
        advanceUntilIdle()

        vm.finishFocus(t, markDone = true)
        advanceUntilIdle()

        val after = awaitTask("t1") { it.done && it.totalFocused == 300 }
        assertNotNull("completion stamped", after.completedAt)
    }

    // -----------------------------------------------------------------------
    // Shared focus (T3, Option B): a recipient focuses a task shared WITH them.
    // The task is NOT in their store, so the session carries a shared marker and
    // finalize accrues onto the OWNER via log_shared_focus (a client-side no-op in
    // this harness — no coordinator/network) INSTEAD of writing an own Session /
    // totalFocused. These lock in the routing that guarantees we NEVER mint own-store
    // rows for a task that isn't the recipient's.
    // -----------------------------------------------------------------------

    @Test fun startSharedFocus_partner_setsSharedMarkerAndMintsNoOwnTask() = runTest(dispatcher) {
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        vm.startSharedFocus("owners-task", title = "Their brief", estimateMin = 45, level = ShareLevel.PARTNER)
        advanceUntilIdle()

        val live = awaitLiveSession { it?.taskId == "owners-task" }!!
        assertEquals("Their brief", live.sharedTitle)
        assertEquals("partner", live.sharedLevel)
        assertEquals(45, live.sessionEstimateMin)
        assertTrue("the shared task is the owner's — never materialized in my store", store.tasks().first().none { it.id == "owners-task" })
    }

    @Test fun startSharedFocus_view_isRejected() = runTest(dispatcher) {
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        vm.startSharedFocus("owners-task", title = "Watching only", estimateMin = 25, level = ShareLevel.VIEW)
        advanceUntilIdle()

        // View is read-only company — no focus session starts. Proving that needs a
        // POSITIVE follow-up: the live session begins as null, so the old
        // `awaitLiveSession { it == null }` returned on the very first emission and
        // could not fail however the VIEW start behaved.
        //
        // The same task at PARTNER level is the probe. startSharedFocus keeps the
        // state of a session already running for that task ("already focusing this
        // shared task"), so if the VIEW start HAD taken, this second call would be a
        // no-op and the session we read back would be the read-only one. The level
        // and title we end up with are therefore the proof that only the partner
        // start ever made a session.
        vm.startSharedFocus("owners-task", title = "Their brief", estimateMin = 45, level = ShareLevel.PARTNER)
        advanceUntilIdle()

        val live = awaitLiveSession { it?.taskId == "owners-task" }!!
        assertEquals("the VIEW start made no session for the partner start to inherit", "partner", live.sharedLevel)
        assertEquals("Their brief", live.sharedTitle)
        assertEquals(45, live.sessionEstimateMin)
    }

    @Test fun finishFocus_sharedSession_clearsLiveAndWritesNoOwnRows() = runTest(dispatcher) {
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        // A live shared session (as startSharedFocus would set), 5 min elapsed.
        store.setLiveSession(
            LiveSession(id = "sess1", taskId = "owners-task", sessionStart = nowMs - 300_000L, sessionEstimateMin = 25, treatment = FocusTreatment.AMBIENT, sharedTitle = "Their brief", sharedLevel = "partner"),
        )
        advanceUntilIdle()

        vm.finishFocus(task("owners-task", name = "Their brief"), markDone = false)
        advanceUntilIdle()

        // Live session cleared + a recap surfaced with the shared title.
        awaitLiveSession { it == null }
        assertEquals("Their brief", vm.lastRecap.value?.taskName)
        // Crucially: NO own Session row + NO own task row for the foreign task.
        assertTrue("no own Session row for a foreign task", store.sessions().first().isEmpty())
        assertTrue("no own task row for a foreign task", store.tasks().first().none { it.id == "owners-task" })
    }

    @Test fun startFocus_switchingTasksMidSession_finalizesPriorSession() = runTest(dispatcher) {
        // Mid-session task switch must NOT silently discard the first task's time —
        // it finalizes a Session row + accrues totalFocused for the prior task.
        val a = task("a", name = "First", estimateMin = 25, totalFocused = 0)
        val b = task("b", name = "Second", estimateMin = 25)
        seedTask(a); seedTask(b)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        store.setLiveSession(
            LiveSession(id = "sessA", taskId = "a", sessionStart = nowMs - 480_000L, sessionEstimateMin = 25, treatment = FocusTreatment.AMBIENT),
        )
        advanceUntilIdle()

        vm.startFocus(b)
        advanceUntilIdle()

        // Prior task A got its 480s (8 min) banked + a Session row.
        awaitTask("a") { it.totalFocused == 480 }
        val sessions = awaitSessions { it.isNotEmpty() }
        assertEquals(1, sessions.size)
        assertEquals("a", sessions.single().taskId)
        // Live session is now task B, freshly started.
        val live = awaitLiveSession { it?.taskId == "b" }!!
        assertEquals("b", live.taskId)
    }

    @Test fun finishFocus_onRecurringOccurrence_accruesOnTemplateAndCompletesBlock() = runTest(dispatcher) {
        // Focus on a recurring occurrence: totalFocused accrues on the TEMPLATE,
        // markDone completes only THIS day's block, never the template.
        val template = task("tpl", name = "Run", recurrence = Recurrence.Daily(), totalFocused = 60)
        val occ = CalBlock(id = "occ1", taskId = "tpl", taskName = "Run", startTime = "07:00", durationMinutes = 30, date = "2026-05-22", kind = CalBlockKind.TASK)
        seedTask(template); seedBlock(occ)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        // Live session references the occurrence block (as startFocus would set).
        store.setLiveSession(
            LiveSession(id = "sessR", taskId = "tpl", sessionStart = nowMs - 300_000L, sessionEstimateMin = 30, treatment = FocusTreatment.AMBIENT, priorAccumulatedSec = 60, occurrenceBlockId = "occ1"),
        )
        advanceUntilIdle()

        val projectedRow = template.copy(id = "occ1", recurrence = null)
        vm.finishFocus(projectedRow, markDone = true)
        advanceUntilIdle()

        val tpl = awaitTask("tpl") { it.totalFocused == 360 }
        assertFalse("template never flipped done", tpl.done)
        awaitBlock("occ1") { it.done }
    }

    // -----------------------------------------------------------------------
    // Focus on a repeating task starts from ITS day, not the series' lifetime
    // (Android audit 2026-09-23, A13; web W10)
    // -----------------------------------------------------------------------

    @Test fun startFocus_onARepeatingTasksDay_startsAtZero_notTheSeriesLifetimeTotal() = runTest(dispatcher) {
        // Day 4 of a daily 25-min habit focused 25 min on each of 3 days: the
        // template's totalFocused is 4500 s. Seeded into the ring, Focus opened at
        // 75:00, overrun from the first second.
        val template = task("tpl", name = "Stretch", recurrence = Recurrence.Daily(), totalFocused = 4500)
        val occ = CalBlock(id = "occ4", taskId = "tpl", taskName = "Stretch", startTime = "09:00", durationMinutes = 25, date = Clock.todayIso(), kind = CalBlockKind.TASK)
        seedTask(template); seedBlock(occ)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        vm.startFocus(template.copy(id = "occ4", recurrence = null))   // the day's row, as the detail screen hands it
        advanceUntilIdle()

        val live = awaitLiveSession { it?.sessionStart != null }!!
        assertEquals("tpl", live.taskId)
        assertEquals("occ4", live.occurrenceBlockId)
        assertEquals("no prior from the series' lifetime", 0, live.priorAccumulatedSec ?: 0)
        assertEquals(0, tech.csalliance.unstuck.core.logic.FocusTimer.displayedElapsedSec(live, nowMs))
        assertEquals(
            tech.csalliance.unstuck.core.model.FocusState.RUNNING,
            tech.csalliance.unstuck.core.logic.FocusTimer.deriveState(live, nowMs, 1.0),
        )
    }

    @Test fun startFocus_onASeriesWithNoOpenDay_startsAtZero() = runTest(dispatcher) {
        // No block today (or the blocks not loaded yet on a cold notification
        // Start): the template itself reaches the plain path, with its lifetime total.
        val template = task("tpl", name = "Stretch", recurrence = Recurrence.Daily(), totalFocused = 4500)
        seedTask(template)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        vm.startFocus(template)
        advanceUntilIdle()

        val live = awaitLiveSession { it?.sessionStart != null }!!
        assertEquals("tpl", live.taskId)
        assertEquals(0, live.priorAccumulatedSec ?: 0)
    }

    @Test fun startFocus_onAPlainTask_stillContinuesFromItsFocusedTotal() = runTest(dispatcher) {
        // "End for now" then back: a one-off task's total IS its progress.
        val t = task("t1", name = "Write report", estimateMin = 50, totalFocused = 600)
        seedTask(t)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        vm.startFocus(t)
        advanceUntilIdle()

        assertEquals(600, awaitLiveSession { it?.sessionStart != null }!!.priorAccumulatedSec)
    }

    // -----------------------------------------------------------------------
    // Captures made during focus leave the phone (Android audit 2026-09-23, A14)
    // -----------------------------------------------------------------------

    private fun uuid() = java.util.UUID.randomUUID().toString()
    private fun capture(sessionId: String?, taskId: String? = null) = tech.csalliance.unstuck.core.model.Capture(
        id = uuid(), taskId = taskId, sessionId = sessionId,
        tag = tech.csalliance.unstuck.core.model.CaptureTag.FOLLOW_UP, body = "call the bank", at = "2026-05-21T10:00:00.000Z",
    )

    @Test fun captureAndPauseReason_onARepeatingTasksDay_areFiledOnTheSeries() = runTest(dispatcher) {
        // Focus on a day of a series runs on the day's row, whose id is its
        // cal_block id: captures.task_id references tasks(id), so a capture filed
        // on it was refused on every flush.
        val template = task("tpl", name = "Stretch", recurrence = Recurrence.Daily())
        val plain = task("t1", name = "Write")
        seedTask(template); seedTask(plain)
        seedBlock(CalBlock(id = "occ1", taskId = "tpl", taskName = "Stretch", startTime = "09:00", durationMinutes = 25, date = Clock.todayIso(), kind = CalBlockKind.TASK))
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        vm.saveCapture("occ1", null, tech.csalliance.unstuck.core.model.CaptureTag.FOLLOW_UP, "call the bank")
        vm.saveCapture("t1", null, tech.csalliance.unstuck.core.model.CaptureTag.IDEA, "plain")
        vm.saveReasonLog("occ1", "Drink")
        advanceUntilIdle()

        val caps = awaitCaptures { it.size == 2 }
        assertEquals("tpl", caps.single { it.body == "call the bank" }.taskId)
        assertEquals("a real task id passes through", "t1", caps.single { it.body == "plain" }.taskId)
        assertEquals("tpl", store.reasonLogs().first { it.isNotEmpty() }.single().taskId)
    }

    @Test fun cancelFocus_releasesTheCapturesQueuedBehindItsSession() = runTest(dispatcher) {
        // cancel_focus writes no Session row, so a capture waiting on one never flushed.
        seedTask(task("t1", name = "Write"))
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)
        val sid = uuid()
        store.setLiveSession(LiveSession(id = sid, taskId = "t1", sessionStart = nowMs - 300_000L, sessionEstimateMin = 25, treatment = FocusTreatment.AMBIENT))
        vm.saveCapture("t1", sid, tech.csalliance.unstuck.core.model.CaptureTag.FOLLOW_UP, "call the bank")
        advanceUntilIdle()
        awaitPending { ops -> ops.any { it.recordTable == Tables.CAPTURES && it.dependsOn == sid } }

        assertTrue(vm.cancelFocusNow())

        val ops = awaitPending { ops -> ops.any { it.recordTable == Tables.CAPTURES } && ops.none { it.dependsOn == sid } }
        assertNull(ops.single { it.recordTable == Tables.CAPTURES }.dependsOn)
        assertNull(awaitCaptures { it.isNotEmpty() }.single().sessionId)
        assertTrue("cancel still writes no Session row", store.sessions().first().isEmpty())
    }

    @Test fun displacingASessionWhoseTaskWasDeleted_releasesItsCaptures() = runTest(dispatcher) {
        // The task went away elsewhere mid-session: finalizeDisplaced writes no
        // Session row for it, so its captures must not wait on one.
        seedTask(task("b", name = "Second"))
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)
        val sid = uuid()
        store.setLiveSession(LiveSession(id = sid, taskId = "gone", sessionStart = nowMs - 300_000L, sessionEstimateMin = 25, treatment = FocusTreatment.AMBIENT))
        write.upsertCapture(capture(sid))

        vm.startFocus(task("b", name = "Second"))
        advanceUntilIdle()

        awaitLiveSession { it?.taskId == "b" }
        val ops = awaitPending { ops -> ops.any { it.recordTable == Tables.CAPTURES } && ops.none { it.dependsOn == sid } }
        assertNull(ops.single { it.recordTable == Tables.CAPTURES }.dependsOn)
    }

    @Test fun finishingASessionOnATaskSharedWithMe_releasesItsCaptures() = runTest(dispatcher) {
        // A recipient's session writes no own Session row (the owner's task accrues
        // via log_shared_focus).
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)
        val sid = uuid()
        store.setLiveSession(
            LiveSession(id = sid, taskId = "owners-task", sessionStart = nowMs - 300_000L, sessionEstimateMin = 25, treatment = FocusTreatment.AMBIENT, sharedTitle = "Their brief", sharedLevel = "partner"),
        )
        write.upsertCapture(capture(sid))

        assertTrue(vm.finishFocusNow(task("owners-task", name = "Their brief"), markDone = false))

        val ops = awaitPending { ops -> ops.any { it.recordTable == Tables.CAPTURES } && ops.none { it.dependsOn == sid } }
        assertNull(ops.single { it.recordTable == Tables.CAPTURES }.dependsOn)
        assertTrue(store.sessions().first().isEmpty())
    }

    // -----------------------------------------------------------------------
    // scheduleTask + recurrence regen
    // -----------------------------------------------------------------------

    @Test fun scheduleTask_plainTask_firstPlacement_createsBlockNoMoveCount() = runTest(dispatcher) {
        val t = task("t1", name = "Call", estimateMin = 30)
        seedTask(t)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        val date = Clock.dateIso(nowMs + 3 * 86_400_000L)
        vm.scheduleTask(t, date, "14:00")
        advanceUntilIdle()

        val blocks = awaitBlocks { l -> l.any { it.taskId == "t1" } }.filter { it.taskId == "t1" }
        assertEquals(1, blocks.size)
        assertEquals(date, blocks.single().date)
        assertEquals("14:00", blocks.single().startTime)
        assertEquals(30, blocks.single().durationMinutes)
        // First placement does NOT bump moveCount.
        assertNull(loadTask("t1")!!.moveCount)
    }

    @Test fun scheduleTask_plainTask_move_bumpsMoveCountOnRealChange() = runTest(dispatcher) {
        val t = task("t1", name = "Call", estimateMin = 30)
        val existing = CalBlock(id = "b1", taskId = "t1", taskName = "Call", startTime = "10:00", durationMinutes = 30, date = "2026-05-22", kind = CalBlockKind.TASK)
        seedTask(t); seedBlock(existing)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        // Re-tap at the SAME slot: in-place, no move (no write at all).
        vm.scheduleTask(t, "2026-05-22", "10:00")
        advanceUntilIdle()
        assertNull("re-tapping the same slot must not inflate moveCount", loadTask("t1")!!.moveCount)

        // Move to a new time: in-place update + moveCount bumped.
        vm.scheduleTask(t, "2026-05-22", "11:30")
        advanceUntilIdle()
        assertEquals(1, awaitTask("t1") { it.moveCount == 1 }.moveCount)
        assertEquals("11:30", awaitBlock("b1") { it.startTime == "11:30" }.startTime)
        assertEquals("still a single block (moved in place)", 1, store.blocks().first().count { it.taskId == "t1" })
    }

    @Test fun scheduleTask_recurringTask_regeneratesHorizonAndCoversChosenSlot() = runTest(dispatcher) {
        // Daily recurrence scheduled for a future date → regenerate materializes a
        // horizon of future blocks, and the user's chosen slot is guaranteed present.
        val t = task("tpl", name = "Meditate", estimateMin = 15, recurrence = Recurrence.Daily())
        seedTask(t)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        // Dates must be relative to the REAL clock: regenerateForTask filters on
        // Clock.todayIso() (system time), so a fixed-nowMs date would land in the
        // past and materialize zero future occurrences.
        val chosen = Clock.dateIso(System.currentTimeMillis() + 2 * 86_400_000L)
        vm.scheduleTask(t, chosen, "08:00")
        advanceUntilIdle()

        // Gate on the WHOLE post-condition, not just the count: the horizon lands one
        // block at a time, so `count > 5` was satisfiable before the chosen slot itself
        // had been written, and the next line then read a half-finished regen.
        val blocks = awaitBlocks { l ->
            val mine = l.filter { it.taskId == "tpl" }
            mine.size > 5 && mine.any { it.date == chosen && it.startTime == "08:00" }
        }.filter { it.taskId == "tpl" }
        assertTrue("daily regen materializes many future occurrences", blocks.size > 5)
        assertTrue("the chosen date/time is covered", blocks.any { it.date == chosen && it.startTime == "08:00" })
        blocks.forEach { assertTrue(isTaskBlock(it)) }
    }

    @Test fun setRecurrence_clearing_deletesFutureUncompletedOccurrences() = runTest(dispatcher) {
        // Clearing a recurrence deletes future un-done occurrences but keeps history.
        val t = task("tpl", name = "Daily", recurrence = Recurrence.Daily())
        // Future dates relative to the REAL clock (the regen boundary is the
        // system Clock.todayIso(), not the fixed test nowMs).
        val future1 = CalBlock(id = "f1", taskId = "tpl", taskName = "Daily", startTime = "09:00", durationMinutes = 25, date = Clock.dateIso(System.currentTimeMillis() + 5 * 86_400_000L), kind = CalBlockKind.TASK)
        val future2 = CalBlock(id = "f2", taskId = "tpl", taskName = "Daily", startTime = "09:00", durationMinutes = 25, date = Clock.dateIso(System.currentTimeMillis() + 6 * 86_400_000L), kind = CalBlockKind.TASK)
        val pastDone = CalBlock(id = "p1", taskId = "tpl", taskName = "Daily", startTime = "09:00", durationMinutes = 25, date = "2020-01-01", done = true, completedAt = "2020-01-01T09:30:00.000Z", kind = CalBlockKind.TASK)
        seedTask(t); seedBlock(future1); seedBlock(future2); seedBlock(pastDone)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        vm.setRecurrence(t, null)
        advanceUntilIdle()

        // Await the deletes to land (f1, f2 gone), then assert the kept set.
        val blocks = awaitBlocks { l -> l.none { it.id == "f1" } && l.none { it.id == "f2" } }
        val ids = blocks.map { it.id }.toSet()
        assertFalse("future occurrence deleted", "f1" in ids)
        assertFalse("future occurrence deleted", "f2" in ids)
        assertTrue("historical completed occurrence preserved", "p1" in ids)
        assertNull("recurrence cleared on the task", awaitTask("tpl") { it.recurrence == null }.recurrence)
    }

    // ── repeat edits and the chosen day (audit 2026-09-22, B79.1 / C1 / C7) ──

    private fun occ(id: String, taskId: String, date: String, startTime: String, done: Boolean = false, skipped: Boolean = false) =
        CalBlock(id = id, taskId = taskId, taskName = taskId, startTime = startTime, durationMinutes = 25, date = date, kind = CalBlockKind.TASK, done = done, skipped = skipped)

    @Test fun setRecurrence_onATaskWithNoTimedBlock_refusesAndWritesNothing() = runTest(dispatcher) {
        // A series needs a time of day: the old 09:00 fallback built it from
        // TOMORROW, so the task left Today at a time the user never chose (C7).
        val t = task("t1", name = "Stretch")
        seedTask(t)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        assertFalse(vm.setRecurrence(t, Recurrence.Daily()))
        advanceUntilIdle()
        assertNull("the rule is not saved", loadTask("t1")!!.recurrence)
        assertTrue("no block is minted", store.blocks().first().none { it.taskId == "t1" })
    }

    @Test fun startRepeating_savesTheRuleAndPlacesTheChosenDaysOccurrence() = runTest(dispatcher) {
        // The editor's "Start repeating" answer: the rule first, then the series
        // plus today's occurrence (regenerate alone only fills days after today),
        // and the Later parking ends in the same row (C7).
        val t = task("t1", name = "Stretch").copy(later = true)
        seedTask(t)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)
        val today = Clock.todayIso()

        vm.startRepeating(t, Recurrence.Daily(), today, "19:00")
        advanceUntilIdle()

        val mine = awaitBlocks { l -> l.count { it.taskId == "t1" } == 56 }.filter { it.taskId == "t1" }
        assertTrue("today's occurrence at the chosen time", mine.any { it.date == today && it.startTime == "19:00" })
        assertTrue(mine.all { it.startTime == "19:00" })
        val saved = awaitTask("t1") { it.recurrence != null }
        assertEquals(Recurrence.Daily(), saved.recurrence)
        assertEquals(false, saved.later)
    }

    @Test fun setRecurrence_onASeriesStartedLongAgo_keepsItsFutureOccurrences() = runTest(dispatcher) {
        // The old anchor was the OLDEST block: a series whose first block was 56+
        // days old materialised nothing after today, so any repeat edit deleted
        // every future occurrence and added none (B79.1, Android's worse variant).
        val today = Clock.todayIso()
        val t = task("tpl", name = "Gym", recurrence = Recurrence.Daily())
        seedTask(t)
        (60 downTo 1).forEach { seedBlock(occ("h$it", "tpl", addDaysIso(today, -it), "07:00", done = true)) }
        (0..20).forEach { seedBlock(occ("u$it", "tpl", addDaysIso(today, it), "07:00")) }
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        assertTrue(vm.setRecurrence(t, Recurrence.Daily(addDaysIso(today, 90))))
        advanceUntilIdle()

        val future = awaitBlocks { l -> l.count { it.taskId == "tpl" && it.date > today } == 55 }.filter { it.taskId == "tpl" && it.date > today }
        assertTrue("every upcoming occurrence survives", (1..20).all { i -> future.any { it.id == "u$i" } })
        assertTrue(future.all { it.startTime == "07:00" })
    }

    @Test fun scheduleTask_templateForToday_retimesAndUnskipsTodaysOccurrence() = runTest(dispatcher) {
        // "Any block on the day covers it" left today at 07:00 and a skipped today
        // hidden. Today's occurrence is retimed (and un-skipped), never twinned (C7).
        val today = Clock.todayIso()
        val t = task("tpl", name = "Walk", recurrence = Recurrence.Daily())
        seedTask(t)
        seedBlock(occ("td", "tpl", today, "07:00", skipped = true))
        seedBlock(occ("tm", "tpl", addDaysIso(today, 1), "07:00"))
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        vm.scheduleTask(t, today, "16:00")
        advanceUntilIdle()

        val td = awaitBlock("td") { it.startTime == "16:00" }
        assertFalse(td.skipped)
        assertEquals("one occurrence today", 1, store.blocks().first().count { it.taskId == "tpl" && it.date == today })
    }

    @Test fun scheduleTask_templateAtItsNextOccurrence_doesNotBumpMoveCount() = runTest(dispatcher) {
        // The move check compared with the EARLIEST block — weeks-old history on a
        // template — so every re-schedule, even a no-op, tripped the slip detector
        // (C7). Compared with the series' next occurrence, a no-op writes nothing.
        val today = Clock.todayIso()
        val t = task("tpl", name = "Standup", recurrence = Recurrence.Daily())
        seedTask(t)
        seedBlock(occ("old", "tpl", addDaysIso(today, -30), "07:00", done = true))
        (1..56).forEach { seedBlock(occ("u$it", "tpl", addDaysIso(today, it), "08:00")) }
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        vm.scheduleTask(t, addDaysIso(today, 1), "08:00")
        advanceUntilIdle()
        assertNull(loadTask("tpl")!!.moveCount)
    }

    @Test fun scheduleTask_templateAtTheSheetsSeed_changesNothing() = runTest(dispatcher) {
        // The task sheet's Schedule opened on today at the current minute, so OK
        // without changes rebuilt the whole series at that minute. It now opens on
        // the next occurrence at the series' own time, where OK is a no-op (C7).
        val today = Clock.todayIso()
        val t = task("tpl", name = "Gym", recurrence = Recurrence.Daily())
        val sentinel = task("s", name = "Sentinel")
        seedTask(t); seedTask(sentinel)
        (30 downTo 1).forEach { seedBlock(occ("h$it", "tpl", addDaysIso(today, -it), "06:30", done = true)) }
        (0..55).forEach { seedBlock(occ("u$it", "tpl", addDaysIso(today, it), "07:00")) }
        val before = store.blocks().first().filter { it.taskId == "tpl" }.toSet()
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        val seed = tech.csalliance.unstuck.ui.tasks.seriesScheduleSeed(t, vm.blocks.value, today)!!
        assertEquals(today, seed.date)
        assertEquals("07:00", seed.startTime)
        vm.scheduleTask(t, seed.date, seed.startTime!!)
        // A later write that is awaited, so a stray write from the call above has
        // landed before the series is compared.
        vm.scheduleTask(sentinel, today, "10:00")
        advanceUntilIdle()
        awaitBlocks { l -> l.any { it.taskId == "s" } }

        assertEquals(before, store.blocks().first().filter { it.taskId == "tpl" }.toSet())
        assertNull(loadTask("tpl")!!.moveCount)
    }

    @Test fun skipOccurrence_hidesOneDayWithoutTouchingSeries() = runTest(dispatcher) {
        val template = task("tpl", name = "Daily", recurrence = Recurrence.Daily())
        val occ = CalBlock(id = "occ1", taskId = "tpl", taskName = "Daily", startTime = "09:00", durationMinutes = 25, date = "2026-05-22", kind = CalBlockKind.TASK)
        seedTask(template); seedBlock(occ)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        vm.skipOccurrence("occ1")
        advanceUntilIdle()

        val skipped = awaitBlock("occ1") { it.skipped }
        assertFalse(skipped.done)
        assertNotNull("template intact", loadTask("tpl"))
    }

    // -----------------------------------------------------------------------
    // collection mutate routing: solo vs shared
    // -----------------------------------------------------------------------

    /**
     * Creating a collection is FIRE-AND-FORGET (`launchWrite` → Room → a `flowOn`
     * read flow), so the row is NOT resolvable in the turn the caller gets the id
     * back. This pins that premise, because the round-1 report asserted the
     * opposite ("upsertCollection writes the row locally before onCreated") and
     * used it to argue the create-then-navigate push was safe. It isn't:
     * CollectionDetailScreen treats an id it can't resolve as deleted and backs
     * out, which is how "create a list, land back on the grid" happened.
     *
     * If the create is ever made synchronous, this test fails — and that is the
     * signal that [AppViewModel.awaitCollectionReadable] can be dropped.
     */
    @Test fun creatingACollectionIsAsync_soTheRowIsNotReadableInTheSameTurn() = runTest(dispatcher) {
        uid = "me"
        val vm = vm()
        subscribeReads(vm, vm.collections)
        val c = ItemCollection(id = "new-1", name = "Reading", color = "indigo", items = emptyList(), sortOrder = 0, ownerId = "me")

        vm.upsertCollection(c)
        assertNull("the create has NOT landed in the same turn", vm.collections.value.firstOrNull { it.id == "new-1" })

        // ...and the wait returns exactly when it becomes readable — never before.
        vm.awaitCollectionReadable("new-1")
        assertNotNull("awaitCollectionReadable returned before the row was readable", vm.collections.value.firstOrNull { it.id == "new-1" })
    }

    @Test fun renameCollection_soloList_writesThroughOutbox() = runTest(dispatcher) {
        // Solo list (no members, owned by me) → whole-row upsert via WriteThrough,
        // so an outbox `collections` upsert op is queued.
        uid = "me"
        val c = ItemCollection(id = "c1", name = "Groceries", color = "indigo", items = emptyList(), sortOrder = 0, ownerId = "me")
        seedCollection(c)
        val vm = vm()
        subscribeReads(vm, vm.collections)

        vm.renameCollection(c, "Shopping")
        advanceUntilIdle()

        assertEquals("Shopping", awaitCollection("c1") { it.name == "Shopping" }.name)
        // "solo path enqueues an outbox collections upsert" — awaited, not sampled.
        awaitPending { l -> l.any { it.recordTable == Tables.COLLECTIONS && it.recordId == "c1" && it.op == "upsert" } }
    }

    @Test fun addCollectionItem_sharedList_optimisticLocalWrite_andQueuedItemRpc() = runTest(dispatcher) {
        // Shared list (owned by someone else) → an item edit takes the
        // mutateCollectionItem path: an OPTIMISTIC local write (so the UI updates
        // immediately) plus the atomic item RPC queued through the OUTBOX as an `rpc`
        // op (retried offline / on 5xx; a refusal rolls the row back). Two
        // regressions locked: the edit must NOT ship the whole items JSONB as a
        // whole-row upsert (it would clobber a concurrent member edit), and it must
        // NOT be fire-and-forget (a failed RPC used to leave a phantom row that the
        // next echo silently deleted).
        uid = "me"
        val shared = ItemCollection(id = "c2", name = "Trip", color = "teal", items = emptyList(), sortOrder = 0, ownerId = "someone-else", members = listOf("me"))
        seedCollection(shared)
        val vm = vm()
        subscribeReads(vm, vm.collections)
        assertTrue("precondition: classified as shared", vm.isShared(shared))

        vm.addCollectionItem(shared, "pack sunscreen")
        advanceUntilIdle()

        val items = awaitCollection("c2") { it.items.isNotEmpty() }.items
        assertEquals("optimistic local append applied", "pack sunscreen", items.single().body)
        awaitPending { l -> l.any { it.recordTable == Tables.COLLECTIONS && it.recordId == "c2" } }
        advanceUntilIdle()   // the write is past its last durable hop; flush its tail
        // Re-read AFTER the tail has run: "exactly one op" has to be measured on a
        // finished write, not on the snapshot that first satisfied the gate.
        val ops = store.pending().filter { it.recordTable == Tables.COLLECTIONS && it.recordId == "c2" }
        assertEquals("exactly one queued op, and it is an rpc (never a whole-row upsert)", listOf("rpc"), ops.map { it.op })
        val call = tech.csalliance.unstuck.sync.OutboxFlusher.decodeRpc(ops.single().payload!!)!!
        assertEquals("collection_add_item", call.first)
        val item = call.second["p_item"] as kotlinx.serialization.json.JsonObject
        assertEquals("\"pack sunscreen\"", item["body"].toString())
        assertEquals("\"c2\"", call.second["p_collection_id"].toString())
    }

    @Test fun toggleCollectionItemDone_sharedList_queuesTheFlagRpcWithTheNewValue() = runTest(dispatcher) {
        uid = "me"
        val item = CollectionItem("i1", "Milk", at = "2026-05-21T10:00:00.000Z")
        val shared = ItemCollection(id = "c2", name = "Trip", color = "teal", items = listOf(item), sortOrder = 0, ownerId = "someone-else", members = listOf("me"))
        seedCollection(shared)
        val vm = vm()
        subscribeReads(vm, vm.collections)

        vm.toggleCollectionItemDone(shared, "i1")
        advanceUntilIdle()

        assertTrue(awaitCollection("c2") { it.items.single().done == true }.items.single().done == true)
        val op = awaitPending { l -> l.any { it.recordTable == Tables.COLLECTIONS && it.recordId == "c2" } }
            .single { it.recordTable == Tables.COLLECTIONS && it.recordId == "c2" }
        val call = tech.csalliance.unstuck.sync.OutboxFlusher.decodeRpc(op.payload!!)!!
        assertEquals("collection_set_item_flag", call.first)
        assertEquals("\"done\"", call.second["p_flag"].toString())
        assertEquals("true", call.second["p_value"].toString())
    }

    @Test fun addCollectionItem_soloList_appendsAndEnqueues() = runTest(dispatcher) {
        uid = "me"
        val c = ItemCollection(id = "c1", name = "Todo", color = "indigo", items = emptyList(), sortOrder = 0, ownerId = "me")
        seedCollection(c)
        val vm = vm()
        subscribeReads(vm, vm.collections)

        vm.addCollectionItem(c, "  buy milk  ")
        advanceUntilIdle()

        val items = awaitCollection("c1") { it.items.isNotEmpty() }.items
        assertEquals(1, items.size)
        assertEquals("trimmed body", "buy milk", items.single().body)
        awaitPending { l -> l.any { it.recordTable == Tables.COLLECTIONS && it.recordId == "c1" && it.op == "upsert" } }
    }

    @Test fun isSharedClassification_guardsOnKnownUid() = runTest(dispatcher) {
        // A transiently-null uid must NOT misclassify your OWN list as shared
        // (that would route edits down the RPC-only path → silent loss).
        val ownByOther = ItemCollection(id = "c1", name = "L", color = "indigo", items = emptyList(), sortOrder = 0, ownerId = "other")
        val vm = vm()
        uid = null
        assertFalse("null uid → cannot prove shared by owner mismatch", vm.isShared(ownByOther))
        uid = "me"
        assertTrue("owner != me → shared", vm.isShared(ownByOther))
        val withMembers = ItemCollection(id = "c2", name = "M", color = "indigo", items = emptyList(), sortOrder = 0, members = listOf("x"))
        assertTrue("has members → shared regardless of uid", vm.isShared(withMembers))
    }

    // -----------------------------------------------------------------------
    // moveItemToTask (promote) — solo list local promotion + dedupe guard
    // -----------------------------------------------------------------------

    @Test fun moveItemToTask_soloSelf_createsTaskAndMarksItemPromoted() = runTest(dispatcher) {
        uid = "me"; displayName = "Ada"
        val item = CollectionItem(id = "i1", body = "Fix sink", at = "2026-05-21T10:00:00.000Z")
        val c = ItemCollection(id = "c1", name = "Home", color = "indigo", items = listOf(item), sortOrder = 0, ownerId = "me")
        seedCollection(c)
        val vm = vm()
        subscribeReads(vm, vm.collections, vm.tasks, vm.blocks)

        vm.moveItemToTask(c, item, AppViewModel.PromoteMode.SELF)
        advanceUntilIdle()

        // A standalone task was created from the item body (NOT loop-linked on a solo list).
        val created = awaitTasks { l -> l.any { it.name == "Fix sink" } }.single { it.name == "Fix sink" }
        assertNull("solo promote does not loop-link a source collection", created.sourceCollectionId)
        assertTrue(created.tags?.contains("from-collection") == true)
        // The item is marked promoted locally ("Promoted" chip), done = null (static).
        val promoted = awaitCollection("c1") { it.items.single().promoted == true }.items.single()
        assertTrue("item flagged promoted", promoted.promoted == true)
        assertNull("solo promote = static Promoted (no on-it state)", promoted.promotedDone)
    }

    @Test fun moveItemToTask_alreadyPromotedInFlight_isNoOp() = runTest(dispatcher) {
        uid = "me"
        val inFlight = CollectionItem(id = "i1", body = "Done already", at = "2026-05-21T10:00:00.000Z", promoted = true, promotedDone = false)
        // The CONTROL. On its own, "no task named X exists" is true before the write
        // path has done anything at all, so the assertion could not fail however
        // moveItemToTask behaved. An un-promoted sibling promoted straight after gives
        // a positive post-condition to wait for: the guarded item is refused
        // synchronously (the guard is the first line of moveItemToTaskNow, before any
        // suspension), so by the time the sibling's task is readable the guarded one
        // has had — and lost — its chance to mint one.
        val fresh = CollectionItem(id = "i2", body = "Fresh one", at = "2026-05-21T10:00:00.000Z")
        val c = ItemCollection(id = "c1", name = "Home", color = "indigo", items = listOf(inFlight, fresh), sortOrder = 0, ownerId = "me")
        seedCollection(c)
        val vm = vm()
        subscribeReads(vm, vm.collections, vm.tasks, vm.blocks)

        vm.moveItemToTask(c, inFlight, AppViewModel.PromoteMode.SELF)
        vm.moveItemToTask(c, fresh, AppViewModel.PromoteMode.SELF)
        advanceUntilIdle()

        val tasks = awaitTasks { l -> l.any { it.name == "Fresh one" } }
        assertTrue("no duplicate task minted for an in-flight promotion", tasks.none { it.name == "Done already" })
    }

    // -----------------------------------------------------------------------
    // tag / area rename + delete cascade across tasks
    // -----------------------------------------------------------------------

    @Test fun deleteTag_stripsNameFromEveryTask_caseInsensitive() = runTest(dispatcher) {
        val tag = TagRow(id = "tg1", name = "Deep", color = null, sortOrder = 0)
        val t1 = task("t1").copy(tags = listOf("deep", "urgent"))
        val t2 = task("t2").copy(tags = listOf("Deep"))
        val t3 = task("t3").copy(tags = listOf("other"))
        store.upsert(Tables.TAGS, tag, TagRow.serializer(), tag.id)
        seedTask(t1); seedTask(t2); seedTask(t3)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.tags)

        vm.deleteTag("tg1")
        advanceUntilIdle()

        awaitTags { it.isEmpty() }
        assertEquals("case-insensitive strip leaves the other tag", listOf("urgent"), awaitTask("t1") { it.tags == listOf("urgent") }.tags)
        assertNull("a task left with no tags becomes null", awaitTask("t2") { it.tags == null }.tags)
        assertEquals("untagged task untouched", listOf("other"), loadTask("t3")!!.tags)
    }

    @Test fun renameTag_cascadesAndDedupesAcrossTasks() = runTest(dispatcher) {
        val tag = TagRow(id = "tg1", name = "A", color = null, sortOrder = 0)
        // A task tagged [A, B]: renaming A→B must yield [B] (de-duped), not [B, B].
        val t1 = task("t1").copy(tags = listOf("A", "B"))
        store.upsert(Tables.TAGS, tag, TagRow.serializer(), tag.id)
        seedTask(t1)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.tags)

        vm.renameTag(tag, "B")
        advanceUntilIdle()

        assertEquals("B", awaitTags { it.single().name == "B" }.single().name)
        assertEquals("rename A→B on [A,B] de-dupes to [B]", listOf("B"), awaitTask("t1") { it.tags == listOf("B") }.tags)
    }

    @Test fun renameTag_bailsOnDuplicateName() = runTest(dispatcher) {
        val a = TagRow(id = "a", name = "Work", color = null, sortOrder = 0)
        val b = TagRow(id = "b", name = "Home", color = null, sortOrder = 1)
        store.upsert(Tables.TAGS, a, TagRow.serializer(), a.id)
        store.upsert(Tables.TAGS, b, TagRow.serializer(), b.id)
        val vm = vm()
        subscribeReads(vm, vm.tags)

        vm.renameTag(a, "Home") // collides with b → no-op
        advanceUntilIdle()

        assertEquals("Work", store.tags().first().first { it.id == "a" }.name)
    }

    @Test fun deleteLifeArea_clearsLabelFromTasks() = runTest(dispatcher) {
        val area = LifeArea(id = "la1", name = "Work", color = "indigo", sortOrder = 0)
        val t1 = task("t1").copy(lifeArea = "Work")
        val t2 = task("t2").copy(lifeArea = "Personal")
        store.upsert(Tables.LIFE_AREAS, area, LifeArea.serializer(), area.id)
        seedTask(t1); seedTask(t2)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.lifeAreas)

        vm.deleteLifeArea("la1")
        advanceUntilIdle()

        awaitLifeAreas { it.isEmpty() }
        assertNull("the area label is cleared off its tasks", awaitTask("t1") { it.lifeArea == null }.lifeArea)
        assertEquals("other tasks untouched", "Personal", loadTask("t2")!!.lifeArea)
    }

    @Test fun renameLifeArea_cascadesNewNameOntoTasks() = runTest(dispatcher) {
        val area = LifeArea(id = "la1", name = "Work", color = "indigo", sortOrder = 0)
        val t1 = task("t1").copy(lifeArea = "Work")
        store.upsert(Tables.LIFE_AREAS, area, LifeArea.serializer(), area.id)
        seedTask(t1)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.lifeAreas)

        vm.renameLifeArea(area, "Career")
        advanceUntilIdle()

        assertEquals("Career", awaitLifeAreas { it.single().name == "Career" }.single().name)
        assertEquals("Career", awaitTask("t1") { it.lifeArea == "Career" }.lifeArea)
    }

    // -----------------------------------------------------------------------
    // ONE serialized label cascade for Settings + the assistant (iOS build 81,
    // audit 2026-09-22 C19)
    // -----------------------------------------------------------------------

    private suspend fun seedArea(a: LifeArea) = store.upsert(Tables.LIFE_AREAS, a, LifeArea.serializer(), a.id)
    private suspend fun seedTag(t: TagRow) = store.upsert(Tables.TAGS, t, TagRow.serializer(), t.id)

    @Test fun labelCascade_readsTheStoreNotTheScreensFlows() = runTest(dispatcher) {
        // Nothing collects vm.tasks / vm.lifeAreas here (the assistant in Talk mode with
        // the sheet shut): their .value is []. The cascade still finds every row.
        seedArea(LifeArea("la1", "Work", "indigo", 0))
        seedTask(task("t1").copy(lifeArea = "Work"))
        val vm = vm()
        assertTrue(vm.renameLifeAreaNow("la1", "Career"))
        assertEquals("Career", loadTask("t1")!!.lifeArea)
        assertEquals("Career", store.lifeAreas().first().single().name)
    }

    @Test fun renameTag_dedupesIgnoringCase() = runTest(dispatcher) {
        seedTag(TagRow("tg1", "x", null, 0))
        seedTask(task("t1").copy(tags = listOf("x", "Y")))
        val vm = vm()
        assertTrue(vm.renameTagNow("tg1", "y"))
        assertEquals("[x, Y] with x→y is one tag, not [y, Y]", listOf("y"), loadTask("t1")!!.tags)
    }

    @Test fun renameLifeArea_refusesATakenUnchangedOrBlankNameAndWritesNothing() = runTest(dispatcher) {
        seedArea(LifeArea("la1", "Work", "indigo", 0))
        seedArea(LifeArea("la2", "Home", "green", 1))
        seedTask(task("t1").copy(lifeArea = "Work"))
        val vm = vm()
        assertFalse(vm.renameLifeAreaNow("la1", "home"))
        assertFalse("an unchanged name is not a rename", vm.renameLifeAreaNow("la1", " Work "))
        assertFalse(vm.renameLifeAreaNow("la1", "  "))
        assertFalse("no such area", vm.renameLifeAreaNow("zz", "Office"))
        assertEquals("Work", loadTask("t1")!!.lifeArea)
        assertTrue("nothing queued", store.pending().isEmpty())
        assertTrue("a case-only rename is allowed and carries the tasks", vm.renameLifeAreaNow("la1", "WORK"))
        assertEquals("WORK", loadTask("t1")!!.lifeArea)
    }

    /** The old unchecked assistant rename left some devices with two same-named rows
     *  (the quarantined op keeps the renamed local row alive through every pull).
     *  Tidying the twin up must not move or strip the real row's tasks. */
    @Test fun labelCascade_aTwinLeftByTheOldRenameKeepsTheRealRowsTasks() = runTest(dispatcher) {
        seedArea(LifeArea("la1", "Work", "indigo", 0))
        seedArea(LifeArea("la2", "Work", "amber", 9))
        seedTag(TagRow("tg1", "quick", null, 0))
        seedTag(TagRow("tg2", "Quick", null, 9))
        seedTask(task("t1").copy(lifeArea = "Work", tags = listOf("quick")))
        val vm = vm()
        assertTrue(vm.renameLifeAreaNow("la2", "Errands"))
        assertEquals("the real Work area keeps its tasks", "Work", loadTask("t1")!!.lifeArea)
        seedArea(LifeArea("la3", "Work", "amber", 10))
        assertTrue(vm.deleteLifeAreaNow("la3"))
        assertEquals("Work", loadTask("t1")!!.lifeArea)
        assertTrue(vm.renameTagNow("tg2", "fast"))
        assertEquals(listOf("quick"), loadTask("t1")!!.tags)
        seedTag(TagRow("tg3", "QUICK", null, 10))
        assertTrue(vm.deleteTagNow("tg3"))
        assertEquals("the tag \"quick\" still exists", listOf("quick"), loadTask("t1")!!.tags)
        assertTrue("no task was rewritten", store.pending().none { it.recordTable == Tables.TASKS })
    }

    /** Settings fires and forgets (with the row as the editor saw it), so two renames of
     *  one area can overlap. The cascades run one after the other: the tasks always end
     *  on the area's final name, never on a name no area has. */
    @Test fun labelCascade_overlappingAreaRenamesNeverStrandTasksOnAGoneName() = runTest(dispatcher) {
        val area = LifeArea("la1", "Work", "indigo", 0)
        seedArea(area)
        val ids = (1..12).map { "t$it" }
        ids.forEach { seedTask(task(it).copy(lifeArea = "Work")) }
        val vm = vm()

        vm.renameLifeArea(area, "Day job")
        vm.renameLifeArea(area, "Office")
        advanceUntilIdle()

        assertEquals("Office", awaitLifeAreas { it.single().name == "Office" }.single().name)
        val tasks = awaitTasks { l -> l.size == ids.size && l.all { it.lifeArea == "Office" } }
        assertEquals(ids.toSet(), tasks.map { it.id }.toSet())
    }

    /** rename_area / delete_area / rename_tag / delete_tag go through the same cascade,
     *  refusal included, on the live store. */
    @Test fun assistant_labelToolsGoThroughTheCascadeOnTheLiveStore() = runTest(dispatcher) {
        seedArea(LifeArea("la1", "Work", "indigo", 0))
        seedArea(LifeArea("la2", "Health", "green", 1))
        seedTag(TagRow("tg1", "deep", null, 0))
        seedTask(task("t1").copy(lifeArea = "Work"))
        seedTask(task("t2").copy(tags = listOf("deep", "Focus")))
        val vm = vm()
        fun args(vararg kv: Pair<String, String>) =
            kotlinx.serialization.json.JsonObject(kv.associate { it.first to kotlinx.serialization.json.JsonPrimitive(it.second) })
        suspend fun run(name: String, vararg kv: Pair<String, String>) = vm.runAssistantTool(name, args(*kv), HashMap(), HashMap())

        assertEquals("error: area \"Health\" already exists — nothing changed", run("rename_area", "name" to "Work", "newName" to "health"))
        assertEquals("Work", loadTask("t1")!!.lifeArea)
        assertEquals("ok: renamed area \"Work\" → \"Day job\" (tasks updated)", run("rename_area", "name" to "Work", "newName" to "Day job"))
        assertEquals("Day job", loadTask("t1")!!.lifeArea)
        assertTrue(run("delete_area", "name" to "Day job").startsWith("ok: deleted area"))
        assertNull(loadTask("t1")!!.lifeArea)

        assertEquals("ok: renamed tag \"deep\" → \"focus\"", run("rename_tag", "name" to "deep", "newName" to "focus"))
        assertEquals(listOf("focus"), loadTask("t2")!!.tags)
        assertTrue(run("delete_tag", "name" to "focus").startsWith("ok: deleted tag"))
        assertNull(loadTask("t2")!!.tags)
    }

    // -----------------------------------------------------------------------
    // deleteTask cascade
    // -----------------------------------------------------------------------

    @Test fun deleteTask_cascadesToBlocksAndCaptures() = runTest(dispatcher) {
        val t = task("t1")
        val block = CalBlock(id = "b1", taskId = "t1", taskName = "T", startTime = "09:00", durationMinutes = 25, date = "2026-05-22", kind = CalBlockKind.TASK)
        val orphanBlock = CalBlock(id = "b2", taskId = "other", taskName = "X", startTime = "10:00", durationMinutes = 25, date = "2026-05-22", kind = CalBlockKind.TASK)
        seedTask(t); seedBlock(block); seedBlock(orphanBlock)
        store.upsert(Tables.CAPTURES, tech.csalliance.unstuck.core.model.Capture(id = "cap1", taskId = "t1", tag = tech.csalliance.unstuck.core.model.CaptureTag.IDEA, body = "note", at = "2026-05-21T10:00:00.000Z"), tech.csalliance.unstuck.core.model.Capture.serializer(), "cap1")
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks, vm.captures)

        vm.deleteTask("t1")
        advanceUntilIdle()

        awaitNoTask("t1")
        awaitNoBlock("b1")
        assertNotNull("an unrelated block survives", loadBlock("b2"))
        awaitCaptures { l -> l.none { it.id == "cap1" } }
    }

    // -----------------------------------------------------------------------
    // One-true-shared-session: offline & reconnect convergence
    // (docs/shared-session-spec.md — applyRemoteControl's divergence path)
    // -----------------------------------------------------------------------

    /** A live PARTNER co-focus session blob (recipient-style markers + rev cursors),
     *  as restored after focusing a partner-shared task. */
    private fun partnerLive(
        startMs: Long,
        paused: Boolean = false,
        pausedAtMs: Long? = null,
        rev: Int = 4,
        atMs: Long = nowMs - 60_000,
        appliedRev: Int? = 3,
        appliedAtMs: Long? = nowMs - 120_000,
        diverged: Boolean? = null,
    ) = LiveSession(
        id = "sid-1", taskId = "owners-task", sessionStart = startMs, paused = paused, pausedAt = pausedAtMs,
        sessionEstimateMin = 25, treatment = FocusTreatment.AMBIENT,
        sharedTitle = "Their brief", sharedLevel = "partner",
        sharedSessionRev = rev, sharedSessionAtMs = atMs,
        lastAppliedRev = appliedRev, lastAppliedAtMs = appliedAtMs,
        divergedOffline = diverged,
    )

    private fun sharedState(
        startMs: Long,
        paused: Boolean = false,
        pausedAtMs: Long? = null,
        rev: Int,
        atMs: Long,
        ended: Boolean = false,
    ) = SharedSessionState("sid-1", startMs, paused, pausedAtMs, 25, rev, atMs, ended)

    private fun startedServices(): List<android.content.Intent> {
        val shadow = org.robolectric.Shadows.shadowOf(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
        )
        return generateSequence { shadow.nextStartedService }.toList()
    }

    @Test fun applyRemoteControl_nonDiverged_staleRunningReannounce_cannotUnpause() = runTest(dispatcher) {
        // REGRESSION (spec: "the elapsed comparison NEVER applies to live controls"):
        // a NON-diverged local pause must not be un-paused by a stale running
        // re-announce (a hello replay of the partner's pre-pause state), even though
        // the running state's elapsed is far ahead of the frozen pause. Plain LWW
        // rejects it — divergence convergence must not leak into the live path.
        val vm = vm()
        store.setLiveSession(
            partnerLive(startMs = nowMs - 600_000, paused = true, pausedAtMs = nowMs - 540_000, rev = 5, atMs = nowMs - 540_000),
        )
        advanceUntilIdle()

        vm.applyRemoteControl(
            CoFocusControl("partner-uid", "Sam", sharedState(startMs = nowMs - 600_000, rev = 5, atMs = nowMs - 590_000)),
        )
        advanceUntilIdle()

        val after = store.getLiveSession()!!
        assertTrue("the non-diverged pause survives the stale running re-announce", after.paused)
        assertEquals(nowMs - 540_000, after.pausedAt)
        assertEquals("the LWW cursor did not advance", 3, after.lastAppliedRev)
        assertNull(after.divergedOffline)
    }

    @Test fun applyRemoteControl_diverged_incomingAhead_adoptsWholesaleAndDrivesFgs() = runTest(dispatcher) {
        // Criteria 2+3: I paused OFFLINE (frozen at 60s, diverged — the pause
        // broadcast never delivered); the partner ran on to 600s. Their re-announce
        // is most-ahead → adopt it WHOLESALE (even though my pause out-revs it),
        // clear the flag, and drive the FGS notification back to running.
        val vm = vm()
        store.setLiveSession(
            partnerLive(
                startMs = nowMs - 600_000, paused = true, pausedAtMs = nowMs - 540_000,
                rev = 5, atMs = nowMs - 540_000, diverged = true,
            ),
        )
        advanceUntilIdle()
        startedServices()   // drain anything recorded so far

        val incoming = sharedState(startMs = nowMs - 600_000, rev = 3, atMs = nowMs - 1_000)
        vm.applyRemoteControl(CoFocusControl("partner-uid", "Sam", incoming))
        advanceUntilIdle()

        val after = store.getLiveSession()!!
        assertFalse("adopted the running most-ahead state", after.paused)
        assertNull(after.pausedAt)
        assertEquals(nowMs - 600_000, after.sessionStart)
        assertEquals("cursor = the adopted control", 3, after.lastAppliedRev)
        assertEquals(nowMs - 1_000, after.lastAppliedAtMs)
        // Amendment "Adopt fixes the cursors": the LOCAL stamp pair follows too —
        // the diverged side's INFLATED rev 5 (offline bumps nobody ever saw) must
        // not out-floor the partner's post-convergence controls.
        assertEquals(3, after.sharedSessionRev)
        assertEquals(nowMs - 1_000, after.sharedSessionAtMs)
        assertNull("divergence resolved", after.divergedOffline)
        // Task 6: the convergence drove the FGS notification through the normal
        // remote-control side-effect path (resume → update(paused=false, startMs)).
        val svc = startedServices().firstOrNull {
            it.component?.className == tech.csalliance.unstuck.surface.FocusTimerService::class.java.name
        }
        assertNotNull("FocusTimerService got the adopted running state", svc)
        assertEquals(false, svc!!.getBooleanExtra("paused", true))
        assertEquals(nowMs - 600_000, svc.getLongExtra("start", -1))
    }

    @Test fun applyRemoteControl_diverged_localAhead_keepsLocalAndStampsConvergenceRev() = runTest(dispatcher) {
        // Criterion 3 flipped (and 4, from the partner's seat): my diverged side ran
        // to 600s; the incoming state is a stale pause frozen at 60s — even carrying
        // a HIGHER rev (plain LWW would have paused me). Local is most-ahead → keep
        // it, clear the flag, and stamp rev = max(local, incoming) + 1 so the
        // convergence control beats every rev either side has seen.
        val vm = vm()
        store.setLiveSession(
            partnerLive(startMs = nowMs - 600_000, rev = 4, atMs = nowMs - 60_000, diverged = true),
        )
        advanceUntilIdle()

        val incoming = sharedState(
            startMs = nowMs - 600_000, paused = true, pausedAtMs = nowMs - 540_000, rev = 9, atMs = nowMs - 540_000,
        )
        vm.applyRemoteControl(CoFocusControl("partner-uid", "Sam", incoming))
        advanceUntilIdle()

        val after = store.getLiveSession()!!
        assertFalse("the most-ahead local running state is kept", after.paused)
        assertEquals(nowMs - 600_000, after.sessionStart)
        assertEquals("convergence rev = max(4, 9) + 1", 10, after.sharedSessionRev)
        assertEquals(nowMs, after.sharedSessionAtMs)
        assertEquals("the applied cursor is untouched (nothing was applied)", 3, after.lastAppliedRev)
        assertNull("divergence resolved", after.divergedOffline)
    }

    @Test fun applyRemoteControl_diverged_withinSlack_clearsFlagAndFallsBackToLww() = runTest(dispatcher) {
        // Clocks within slack → the re-exchange resolved the divergence; ordering is
        // decided by plain (rev, atMs) LWW. A NEWER incoming applies…
        val vm = vm()
        store.setLiveSession(
            partnerLive(startMs = nowMs - 100_000, rev = 2, atMs = nowMs - 50_000, appliedRev = 1, diverged = true),
        )
        advanceUntilIdle()

        vm.applyRemoteControl(
            CoFocusControl("partner-uid", "Sam", sharedState(startMs = nowMs - 102_000, rev = 3, atMs = nowMs - 500)),
        )
        advanceUntilIdle()

        val applied = store.getLiveSession()!!
        assertEquals("the newer in-slack control applied via plain LWW", nowMs - 102_000, applied.sessionStart)
        assertEquals(3, applied.lastAppliedRev)
        assertNull("divergence resolved", applied.divergedOffline)

        // …while an OLDER incoming does NOT apply — but still clears the flag.
        store.setLiveSession(
            partnerLive(startMs = nowMs - 100_000, rev = 5, atMs = nowMs - 1_000, appliedRev = 3, diverged = true),
        )
        advanceUntilIdle()
        vm.applyRemoteControl(
            CoFocusControl("partner-uid", "Sam", sharedState(startMs = nowMs - 101_000, rev = 4, atMs = nowMs - 90_000)),
        )
        advanceUntilIdle()

        val kept = store.getLiveSession()!!
        assertEquals("the older in-slack control is a no-op", nowMs - 100_000, kept.sessionStart)
        assertEquals(3, kept.lastAppliedRev)
        assertNull("…but the divergence flag still clears", kept.divergedOffline)
    }

    @Test fun applyRemoteControl_diverged_adoptedRemotePause_lowersCursorsAndClassifiesRemote() = runTest(dispatcher) {
        // Amendment "Adopt fixes the cursors": my diverged side ran only 60s (rev
        // INFLATED to 5 by offline bumps nobody saw); the partner paused long ago,
        // frozen at 600s — most-ahead → adopt WHOLESALE. The adopted cursor pairs
        // must BE the incoming (rev 3, its atMs): remotePaused ties equal pairs to
        // REMOTE, so the pause nag / paused check-in can never arm on this side for
        // the PARTNER's pause — and the inflated local rev must not out-floor the
        // partner's post-convergence controls.
        val vm = vm()
        store.setLiveSession(
            partnerLive(startMs = nowMs - 60_000, rev = 5, atMs = nowMs - 10_000, diverged = true),
        )
        advanceUntilIdle()

        val incoming = sharedState(
            startMs = nowMs - 700_000, paused = true, pausedAtMs = nowMs - 100_000, rev = 3, atMs = nowMs - 99_000,
        )
        vm.applyRemoteControl(CoFocusControl("partner-uid", "Sam", incoming))
        advanceUntilIdle()

        val after = store.getLiveSession()!!
        assertTrue("adopted the frozen-ahead remote pause", after.paused)
        assertEquals(nowMs - 100_000, after.pausedAt)
        assertEquals("local stamp lowered to the adopted control", 3, after.sharedSessionRev)
        assertEquals(nowMs - 99_000, after.sharedSessionAtMs)
        assertEquals(3, after.lastAppliedRev)
        assertEquals(nowMs - 99_000, after.lastAppliedAtMs)
        assertNull(after.divergedOffline)
        assertTrue(
            "an ADOPTED remote pause classifies as remote — the pause nag must not arm",
            tech.csalliance.unstuck.core.logic.remotePaused(
                after.paused, after.sharedSessionRev, after.sharedSessionAtMs,
                after.lastAppliedRev, after.lastAppliedAtMs,
            ),
        )
    }

    // -----------------------------------------------------------------------
    // Offline convergence, channel-visible half (spec amendments 2026-07-17):
    // echo-guard integrity, diverged hello, grace fallback — driven through a
    // fake CoFocusChannel injected via the additive factory seam.
    // -----------------------------------------------------------------------

    private class FakeCoFocusChannel : tech.csalliance.unstuck.sync.CoFocusChannel {
        val sentFlow = kotlinx.coroutines.flow.MutableStateFlow<List<SharedSessionState>>(emptyList())
        val sent: List<SharedSessionState> get() = sentFlow.value
        val hellosFlow = kotlinx.coroutines.flow.MutableStateFlow<List<Boolean>>(emptyList())
        val hellos: List<Boolean> get() = hellosFlow.value
        val suppressedFlow = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)
        @Volatile var deliverResult: Boolean = true
        private var current: SharedSessionState? = null
        private val _peers = kotlinx.coroutines.flow.MutableStateFlow<List<tech.csalliance.unstuck.core.model.CoFocusPeer>>(emptyList())
        override val peers: StateFlow<List<tech.csalliance.unstuck.core.model.CoFocusPeer>> get() = _peers
        fun setPeers(peers: List<tech.csalliance.unstuck.core.model.CoFocusPeer>) { _peers.value = peers }
        private val _controls = kotlinx.coroutines.flow.MutableSharedFlow<CoFocusControl>(replay = 1, extraBufferCapacity = 8)
        override val controls: kotlinx.coroutines.flow.SharedFlow<CoFocusControl> get() = _controls
        private val _rejoins = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 2)
        override val rejoins: kotlinx.coroutines.flow.SharedFlow<Unit> get() = _rejoins
        suspend fun emitRejoin() { _rejoins.emit(Unit) }
        private val _socketDrops = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 2)
        override val socketDrops: kotlinx.coroutines.flow.SharedFlow<Unit> get() = _socketDrops
        suspend fun emitSocketDrop() { _socketDrops.emit(Unit) }
        /** Presence-sync marker (rejoin v2 rule 4). Defaults TRUE — the map is
         *  trusted — so pre-v2 grace scenarios keep their shape; the race tests
         *  flip it false to model a stale pre-drop map. */
        @Volatile var presenceSynced: Boolean = true
        override fun presenceSyncedSinceRejoin(): Boolean = presenceSynced
        override suspend fun broadcastShared(state: SharedSessionState): Boolean {
            current = state
            sentFlow.value = sentFlow.value + state
            return deliverResult
        }
        override suspend fun sendHello(diverged: Boolean) { hellosFlow.value = hellosFlow.value + diverged }
        override suspend fun nudgeSocket() {}
        override fun setSuppressAnnounce(suppress: Boolean) { suppressedFlow.value = suppress }
        override fun setSharedCurrent(state: SharedSessionState?) { current = state }
        override fun sharedCurrent(): SharedSessionState? = current
        override fun latestControl(): CoFocusControl? = null
        override fun close() {}
    }

    @Test fun echoGuard_rolledBackOnFailedSend_offlineExtendStillReachesThePartner() = runTest(dispatcher) {
        // THE finding-1 regression: the echo guard was stamped BEFORE the delivery
        // check and never rolled back, so after a failed EXTEND broadcast the
        // post-convergence catch-up was swallowed as an "echo" — an extend doesn't
        // move elapsed → always within slack → Lww arm → the offline extend was
        // permanently lost. Now: guard rolls back on !delivered, and the Lww arm
        // explicitly re-broadcasts the winning local state at the floor cursor.
        val fake = FakeCoFocusChannel()
        val vm = vm(coFocus = { fake })
        subscribeReads(vm, vm.tasks)
        store.setLiveSession(partnerLive(startMs = nowMs - 100_000, rev = 4, atMs = nowMs - 100_000))
        // First observation announces rev 4 (delivered) and seeds the echo guard.
        fake.sentFlow.first { it.size >= 1 }
        advanceUntilIdle()

        // The send path goes dark; the user EXTENDS offline (25 → 45, stamped in
        // the same write, exactly as mutateLive / FocusCommands do).
        fake.deliverResult = false
        val live = store.getLiveSession()!!
        store.setLiveSession(live.copy(sessionEstimateMin = 45, sharedSessionRev = 5, sharedSessionAtMs = nowMs - 50_000))
        awaitLiveSession { it?.divergedOffline == true }   // the failed rev-5 broadcast marked divergence
        advanceUntilIdle()
        val attemptsBeforeConvergence = fake.sent.size

        // Reconnect: the partner's harmless re-announce arrives — same elapsed
        // (within slack) and an OLDER cursor (they never saw the extend).
        fake.deliverResult = true
        vm.applyRemoteControl(
            CoFocusControl("partner-uid", "Sam", sharedState(startMs = nowMs - 100_000, rev = 4, atMs = nowMs - 100_000)),
        )
        advanceUntilIdle()

        val after = awaitLiveSession { it?.divergedOffline == null }!!
        assertEquals("the offline extend survives the convergence", 45, after.sessionEstimateMin)
        // …and the catch-up broadcast actually SHIPPED it at the local floor cursor
        // (the poisoned guard used to swallow exactly this re-emission).
        val catchUp = fake.sent.drop(attemptsBeforeConvergence)
        assertTrue(
            "the extend reached the channel after convergence: $catchUp",
            catchUp.any { it.rev == 5 && it.estimateMin == 45 && !it.ended },
        )
    }

    @Test fun keepAndBroadcast_failedSend_reMarksDivergence_nextResolutionStillBroadcasts() = runTest(dispatcher) {
        // Finding-1's KeepAndBroadcast half: a FAILED convergence send must roll the
        // echo guard back and RE-MARK the divergence, so the next same-session state
        // resolves again and the convergence control still ships.
        val fake = FakeCoFocusChannel()
        fake.deliverResult = false
        val vm = vm(coFocus = { fake })
        subscribeReads(vm, vm.tasks)
        store.setLiveSession(partnerLive(startMs = nowMs - 600_000, rev = 4, atMs = nowMs - 60_000, diverged = true))
        fake.suppressedFlow.first { it == true }
        advanceUntilIdle()

        // Incoming stale pause frozen at 60s — my diverged runner (600s) is ahead.
        val incoming = sharedState(
            startMs = nowMs - 600_000, paused = true, pausedAtMs = nowMs - 540_000, rev = 9, atMs = nowMs - 540_000,
        )
        vm.applyRemoteControl(CoFocusControl("partner-uid", "Sam", incoming))
        advanceUntilIdle()

        assertTrue("the convergence control was attempted at max+1", fake.sent.any { it.rev == 10 })
        val reDiverged = awaitLiveSession { it?.divergedOffline == true }!!
        assertEquals("the local stamp keeps the convergence rev", 10, reDiverged.sharedSessionRev)
        assertEquals("suppression re-engaged", true, fake.suppressedFlow.value)

        // Delivery restored; the partner's state re-arrives on the next exchange.
        fake.deliverResult = true
        vm.applyRemoteControl(CoFocusControl("partner-uid", "Sam", incoming.copy(atMs = incoming.atMs + 1)))
        advanceUntilIdle()

        val after = awaitLiveSession { it?.divergedOffline == null }!!
        assertFalse("the most-ahead local running state is kept", after.paused)
        assertTrue(
            "the retried convergence control shipped past every seen rev",
            fake.sent.any { it.rev == 11 && !it.paused && !it.ended },
        )
    }

    @Test fun reExchange_whileDiverged_sendsDivergedHelloAndSuppressesTheStateAnnounce() = runTest(dispatcher) {
        // Finding 2 (VM half): a diverged client's re-exchange ASKS with
        // `diverged: true` on the wire (so a focuser answers even while itself
        // diverged — the both-diverged deadlock breaker) and does NOT announce its
        // own stale state.
        val fake = FakeCoFocusChannel()
        val vm = vm(coFocus = { fake })
        subscribeReads(vm, vm.tasks)
        store.setLiveSession(partnerLive(startMs = nowMs - 100_000, rev = 4, atMs = nowMs - 100_000, diverged = true))
        fake.suppressedFlow.first { it == true }
        advanceUntilIdle()

        fake.emitRejoin()
        // Await the hello itself (not advanceUntilIdle — that would fast-forward
        // the 5s grace, whose alone-fallback legitimately announces later).
        fake.hellosFlow.first { it.isNotEmpty() }

        assertEquals("hello carried the diverged flag", listOf(true), fake.hellos)
        assertTrue("a diverged client only ASKS — no state announce on re-exchange", fake.sent.isEmpty())
    }

    @Test fun reExchange_rejoinIsHelloOnly_noStateReannounce_rejoinV2() = runTest(dispatcher) {
        // Rejoin reconciliation v2 rule 1 (SUPERSEDES the vc71 idempotent
        // re-announce, after the live-reproduced iOS↔web failure): a dead socket
        // can go unnoticed for ~2 heartbeats — sends in that window are
        // fire-and-forget "delivered" and an offline control bumps rev UN-flagged,
        // so ANY rejoin-time state is suspect. The re-exchange sends ONLY hello,
        // arms the pending window (announces suppressed), and lets the partner's
        // reply reconcile most-ahead. The genuine FIRST announce (mint) still ships.
        val fake = FakeCoFocusChannel()
        val vm = vm(coFocus = { fake })
        subscribeReads(vm, vm.tasks)
        store.setLiveSession(partnerLive(startMs = nowMs - 100_000, rev = 4, atMs = nowMs - 100_000))
        fake.sentFlow.first { it.size >= 1 }   // the first-subscribe mint announce
        advanceUntilIdle()

        fake.emitRejoin()
        fake.hellosFlow.first { it.isNotEmpty() }

        assertEquals("hello without the diverged flag", listOf(false), fake.hellos)
        assertEquals("NO rev-authoritative state re-announce on a rejoin", 1, fake.sent.size)
        assertEquals("announces suppressed while rejoin-pending", true, fake.suppressedFlow.value)
    }

    @Test fun rejoinPending_unflagged_incomingAheadAdopts_despiteLosingPlainLww() = runTest(dispatcher) {
        // Rejoin v2 rule 2, the iOS↔web failure from the rejoiner's seat: my pause
        // landed in the undetected-dead-socket window (rev bumped to 5, NO diverged
        // flag), the partner ran on. Their reply after my rejoin is behind on rev
        // but AHEAD on the clock — the widened gate (divergedOffline || pending)
        // adopts it; plain LWW would have rejected it and my frozen clock would
        // have stuck (then bulldozed them under the old re-announce).
        val fake = FakeCoFocusChannel()
        val vm = vm(coFocus = { fake })
        subscribeReads(vm, vm.tasks)
        store.setLiveSession(
            partnerLive(startMs = nowMs - 600_000, paused = true, pausedAtMs = nowMs - 540_000, rev = 5, atMs = nowMs - 540_000),
        )
        fake.sentFlow.first { it.size >= 1 }
        advanceUntilIdle()

        fake.emitRejoin()
        fake.hellosFlow.first { it.isNotEmpty() }   // pending armed; grace not yet expired

        vm.applyRemoteControl(
            CoFocusControl("partner-uid", "Sam", sharedState(startMs = nowMs - 600_000, rev = 3, atMs = nowMs - 1_000)),
        )

        val after = awaitLiveSession { it?.paused == false }!!
        assertEquals("adopted wholesale — cursors follow the incoming", 3, after.lastAppliedRev)
        assertEquals(3, after.sharedSessionRev)
        assertNull("never flagged, never persisted", after.divergedOffline)
        assertEquals("pending closed → announces un-suppressed", false, fake.suppressedFlow.value)
    }

    @Test fun rejoinPending_unflagged_localAhead_plainLww_partnerOnlinePauseSurvives() = runTest(dispatcher) {
        // Rejoin v2 rule 3 (the standing guard): a trivial blip — no flag, nothing
        // suspect locally — must NOT give my ahead running clock KeepAndBroadcast
        // rights. The partner's GENUINE online pause (newer rev, clock frozen
        // behind) wins by plain LWW and pauses me; no rev-max+1 convergence
        // control ships against them.
        val fake = FakeCoFocusChannel()
        val vm = vm(coFocus = { fake })
        subscribeReads(vm, vm.tasks)
        store.setLiveSession(partnerLive(startMs = nowMs - 600_000, rev = 4, atMs = nowMs - 60_000))
        fake.sentFlow.first { it.size >= 1 }
        advanceUntilIdle()

        fake.emitRejoin()
        fake.hellosFlow.first { it.isNotEmpty() }

        vm.applyRemoteControl(
            CoFocusControl(
                "partner-uid", "Sam",
                sharedState(startMs = nowMs - 600_000, paused = true, pausedAtMs = nowMs - 540_000, rev = 9, atMs = nowMs - 540_000),
            ),
        )

        val after = awaitLiveSession { it?.paused == true }!!
        assertEquals("their pause applied via plain LWW", nowMs - 540_000, after.pausedAt)
        assertEquals(9, after.lastAppliedRev)
        assertTrue(
            "no KeepAndBroadcast convergence control against the online pause",
            fake.sent.none { it.rev >= 10 },
        )
        assertNull(after.divergedOffline)
    }

    @Test fun rejoinPending_clearedByFirstSameSessionState_gateNarrowsBack() = runTest(dispatcher) {
        // Rejoin v2 rule 2, the window's END: the first same-session state closes
        // pending — a LATER stale running re-announce (ahead on the clock, behind
        // on rev) must fall back to plain LWW and bounce off the local pause, or
        // the widened gate itself would become the next bulldozer.
        val fake = FakeCoFocusChannel()
        val vm = vm(coFocus = { fake })
        subscribeReads(vm, vm.tasks)
        store.setLiveSession(
            partnerLive(startMs = nowMs - 600_000, paused = true, pausedAtMs = nowMs - 540_000, rev = 5, atMs = nowMs - 540_000),
        )
        fake.sentFlow.first { it.size >= 1 }
        advanceUntilIdle()

        fake.emitRejoin()
        fake.hellosFlow.first { it.isNotEmpty() }

        // First exchange: the partner echoes OUR pause back (same fields, same
        // cursor — within slack, rejected by LWW). It still closes the window.
        vm.applyRemoteControl(
            CoFocusControl(
                "partner-uid", "Sam",
                sharedState(startMs = nowMs - 600_000, paused = true, pausedAtMs = nowMs - 540_000, rev = 5, atMs = nowMs - 540_000),
            ),
        )
        assertEquals("first same-session exchange lifts the suppression", false, fake.suppressedFlow.value)

        // Second: a stale running re-announce, far ahead on the clock. With the
        // gate narrowed it must NOT adopt (nonDiverged_staleRunningReannounce's
        // guarantee is restored the moment pending clears).
        vm.applyRemoteControl(
            CoFocusControl("partner-uid", "Sam", sharedState(startMs = nowMs - 600_000, rev = 3, atMs = nowMs - 1_000)),
        )
        advanceUntilIdle()

        val after = store.getLiveSession()!!
        assertTrue("the pause survives — the widened gate closed with the window", after.paused)
        assertEquals(nowMs - 540_000, after.pausedAt)
    }

    @Test fun foregroundReExchange_armsRejoinPending_helloOnly() = runTest(dispatcher) {
        // The foreground signal is the rejoin signal's belt-and-braces twin — v2
        // makes it hello-only too, and it arms the same pending window.
        val fake = FakeCoFocusChannel()
        val vm = vm(coFocus = { fake })
        subscribeReads(vm, vm.tasks)
        store.setLiveSession(partnerLive(startMs = nowMs - 100_000, rev = 4, atMs = nowMs - 100_000))
        fake.sentFlow.first { it.size >= 1 }
        advanceUntilIdle()

        graph.foregrounds.emit(Unit)
        fake.hellosFlow.first { it.isNotEmpty() }

        assertEquals(listOf(false), fake.hellos)
        assertEquals("no state re-announce on the foreground re-exchange", 1, fake.sent.size)
        assertEquals("pending armed → announces suppressed", true, fake.suppressedFlow.value)
    }

    @Test fun socketDrop_marksLivePartnerSessionDiverged_withoutAnyFailedControl() = runTest(dispatcher) {
        // Rejoin v2, undetected-drop belt ("socket-down alone marks divergence"):
        // the channel observed the socket LEAVING connected — no control ever
        // failed, but criterion 3's offline RUNNER must get KeepAndBroadcast
        // rights, or the partner's mid-outage pause would rewind it on rejoin.
        val fake = FakeCoFocusChannel()
        val vm = vm(coFocus = { fake })
        subscribeReads(vm, vm.tasks)
        store.setLiveSession(partnerLive(startMs = nowMs - 100_000, rev = 4, atMs = nowMs - 100_000))
        fake.sentFlow.first { it.size >= 1 }
        advanceUntilIdle()
        assertNull(store.getLiveSession()?.divergedOffline)

        fake.emitSocketDrop()

        val after = awaitLiveSession { it?.divergedOffline == true }!!
        assertEquals("sid-1", after.id)
        assertEquals("announces suppressed while diverged", true, fake.suppressedFlow.value)
    }

    @Test fun divergenceGrace_unsyncedPresence_neverConcludesAlone_reHellosBounded() = runTest(dispatcher) {
        // Rejoin v2 rule 4 (the grace presence RACE): after a rejoin the presence
        // map is stale pre-drop data until ≥1 sync lands — an empty-looking map
        // must count as "focusing peer present" (bounded re-hello), never as
        // "alone" (which would clear the flag and re-announce stale state at a
        // peer the map simply hasn't shown yet).
        val fake = FakeCoFocusChannel()
        fake.presenceSynced = false
        val vm = vm(coFocus = { fake })
        subscribeReads(vm, vm.tasks)
        store.setLiveSession(partnerLive(startMs = nowMs - 100_000, rev = 4, atMs = nowMs - 100_000, diverged = true))
        fake.suppressedFlow.first { it == true }
        advanceUntilIdle()

        fake.emitRejoin()
        // 1 re-exchange hello + 3 bounded grace re-hellos, exactly like the
        // focusing-peer-present case — despite presence being EMPTY.
        fake.hellosFlow.first { it.size >= 4 }
        advanceUntilIdle()

        assertEquals(listOf(true, true, true, true), fake.hellos)
        assertTrue("never announced state off an unsynced map", fake.sent.isEmpty())
        assertEquals("stays diverged past the cap", true, store.getLiveSession()?.divergedOffline)
    }

    @Test fun divergenceGrace_aloneCatchUp_failedSend_reMarksDivergence() = runTest(dispatcher) {
        // Rejoin v2 rule 4, second half: the grace-alone catch-up broadcast must
        // VERIFY delivery — a failed send re-marks the divergence (and rolls the
        // echo guard back) so the next trigger retries instead of silently
        // considering the session announced.
        val fake = FakeCoFocusChannel()
        fake.deliverResult = false
        val vm = vm(coFocus = { fake })
        subscribeReads(vm, vm.tasks)
        store.setLiveSession(
            partnerLive(
                startMs = nowMs - 30_000, rev = 1, atMs = nowMs - 30_000,
                appliedRev = null, appliedAtMs = null, diverged = true,
            ),
        )
        fake.suppressedFlow.first { it == true }
        advanceUntilIdle()

        fake.emitRejoin()
        // Grace expiry (virtual 5s): synced + alone → clear + catch-up… which FAILS.
        // The alone-branch first WRITES the flag-clear, so awaiting the re-marked
        // TRUE here observes the round-trip, not the seed value (the write lands on
        // a real Room thread — advanceUntilIdle can't synchronize with it; a true
        // suspension on the store flow can).
        fake.sentFlow.first { l -> l.any { it.rev == 1 } }
        val after = awaitLiveSession { it?.divergedOffline == true }

        assertEquals("failed catch-up re-marked the divergence", true, after?.divergedOffline)
        fake.suppressedFlow.first { it == true }   // suppression re-engaged
    }

    @Test fun divergenceGrace_focusingPeerPresent_reHellosBounded_staysDiverged() = runTest(dispatcher) {
        // Finding 3: the diverged hello goes unanswered but a FOCUSING peer is
        // visibly present — they hold session state, so re-ask (bounded ≤3) rather
        // than unilaterally re-announcing against it; past the cap, stay diverged
        // (their next control or the next re-exchange trigger resolves it).
        val fake = FakeCoFocusChannel()
        fake.setPeers(
            listOf(tech.csalliance.unstuck.core.model.CoFocusPeer("partner-uid", "Sam", tech.csalliance.unstuck.core.model.CoFocusState.FOCUSING, sinceMs = 0L)),
        )
        val vm = vm(coFocus = { fake })
        subscribeReads(vm, vm.tasks)
        store.setLiveSession(partnerLive(startMs = nowMs - 100_000, rev = 4, atMs = nowMs - 100_000, diverged = true))
        fake.suppressedFlow.first { it == true }
        vm.coFocusPeers.first { it.isNotEmpty() }   // the focusing peer is visible to the VM
        advanceUntilIdle()

        fake.emitRejoin()
        // 1 re-exchange hello + 3 bounded grace re-hellos (virtual 5s cycles run
        // while this await suspends); past the cap no further hello can arrive.
        fake.hellosFlow.first { it.size >= 4 }

        assertEquals(
            "1 re-exchange hello + 3 bounded grace re-hellos, all diverged",
            listOf(true, true, true, true), fake.hellos,
        )
        assertTrue("never announced state against the focusing peer", fake.sent.isEmpty())
        assertEquals("stays diverged past the cap", true, store.getLiveSession()?.divergedOffline)
    }

    @Test fun divergenceGrace_aloneInPresence_clearsFlagAndReannounces_theFailedMintCase() = runTest(dispatcher) {
        // Finding 3, the alone half — covers the failed-MINT broadcast: diverged
        // from t=0, the session INVISIBLE to the partner (fork + double-accrual
        // risk). Presence is empty at grace expiry → nobody holds newer state:
        // clear the flag and re-announce at the local floor.
        val fake = FakeCoFocusChannel()
        val vm = vm(coFocus = { fake })
        subscribeReads(vm, vm.tasks)
        store.setLiveSession(
            partnerLive(
                startMs = nowMs - 30_000, rev = 1, atMs = nowMs - 30_000,
                appliedRev = null, appliedAtMs = null, diverged = true,
            ),
        )
        fake.suppressedFlow.first { it == true }
        advanceUntilIdle()

        fake.emitRejoin()
        // hello (diverged) → 5s grace (virtual, auto-advanced while this await
        // suspends) → the alone fallback clears the flag and re-announces.
        val after = awaitLiveSession { it != null && it.divergedOffline == null }!!
        fake.sentFlow.first { l -> l.any { it.rev == 1 } }

        assertNull("un-diverged without any peer involvement", after.divergedOffline)
        assertEquals(false, fake.suppressedFlow.value)
        assertTrue(
            "the mint finally reached the channel at its stamped floor cursor",
            fake.sent.any { it.rev == 1 && it.sessionId == "sid-1" && !it.ended },
        )
    }

    // -----------------------------------------------------------------------
    // 2026-09 round 2: ledger accrual on a cold start, sign-out finalize,
    // honest promote no-op
    // -----------------------------------------------------------------------

    @Test fun finishFocus_coldStart_emptyBadges_broadcastOwnerSession_accruesViaLedgerOnly() = runTest(dispatcher) {
        // Owner side of a partner co-focus session, relaunched offline: the badge
        // cache is EMPTY (no coordinator here — exactly the cold-start shape) but the
        // live blob carries the rev stamp the announce wrote. The old code routed by
        // the badges alone → direct totalFocused bump; the partner's finalize of the
        // SAME session id also lands in the ledger → the task credited twice.
        val t = task("t1", name = "Brief", estimateMin = 25, totalFocused = 120)
        seedTask(t)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)
        store.setLiveSession(
            LiveSession(
                id = "sess-shared", taskId = "t1", sessionStart = nowMs - 600_000L, sessionEstimateMin = 25,
                treatment = FocusTreatment.AMBIENT, priorAccumulatedSec = 0,
                sharedSessionRev = 1, sharedSessionAtMs = nowMs - 600_000L,   // announced = shared-broadcast
            ),
        )
        advanceUntilIdle()

        vm.finishFocus(t, markDone = false)
        advanceUntilIdle()

        // Session row (insights) still written with the shared id…
        val session = awaitSessions { it.isNotEmpty() }.single()
        assertEquals("sess-shared", session.id)
        assertEquals(600, session.actualSec)
        // finishFocus clears the live session AFTER every row write, so waiting for
        // that is what makes the NEGATIVE assertions below mean anything: sampled
        // mid-write they would have passed simply because nothing had been written yet.
        assertNull("live session cleared", awaitLiveSession { it == null })
        awaitPending { l -> l.any { it.recordTable == Tables.SESSIONS && it.recordId == "sess-shared" } }
        advanceUntilIdle()
        // …but NO direct bump: the total accrues exclusively through the ledger.
        assertEquals("no direct totalFocused bump on a shared-broadcast session", 120, loadTask("t1")!!.totalFocused)
        assertFalse(
            "no whole-row task upsert carrying a bumped total",
            store.pending().any { it.recordTable == Tables.TASKS && it.op == "upsert" && it.payload!!.contains("\"total_focused\":720") },
        )
        // The ledger record is queued durably (no circle client → transient → persisted retry).
        val raw = graph.settings.loadPendingSharedFocusRaw()
        assertNotNull("ledger retry persisted", raw)
        assertTrue(raw!!.contains("sess-shared"))
        assertTrue(raw.contains("\"sec\":600"))
    }

    @Test fun finishFocus_plainOwnSession_noStamps_stillDirectBump() = runTest(dispatcher) {
        // Control: an un-broadcast own session keeps the direct bump (no ledger).
        val t = task("t1", name = "Solo", estimateMin = 25, totalFocused = 0)
        seedTask(t)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)
        store.setLiveSession(LiveSession(id = "s1", taskId = "t1", sessionStart = nowMs - 60_000L, sessionEstimateMin = 25, treatment = FocusTreatment.AMBIENT))
        advanceUntilIdle()
        vm.finishFocus(t, markDone = false)
        advanceUntilIdle()
        assertEquals(60, awaitTask("t1") { it.totalFocused == 60 }.totalFocused)
        assertNull("no ledger record for a plain own session", graph.settings.loadPendingSharedFocusRaw())
    }

    @Test fun signOut_finalizesARunningOwnSession_intoTheOutbox() = runTest(dispatcher) {
        // Sign-out used to wipe the live blob with the cache — elapsed minutes gone.
        // Now an OWN session is finalized first (Session row + total into the outbox,
        // which the coordinator's bounded drain lands or parks under the user).
        val t = task("t1", name = "Draft", estimateMin = 25, totalFocused = 30)
        seedTask(t)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)
        store.setLiveSession(LiveSession(id = "s-out", taskId = "t1", sessionStart = nowMs - 300_000L, sessionEstimateMin = 25, treatment = FocusTreatment.AMBIENT))
        advanceUntilIdle()

        vm.signOut()   // no coordinator in tests → the finalize runs, then auth (null) is skipped
        advanceUntilIdle()

        val session = awaitSessions { it.isNotEmpty() }.single()
        assertEquals("s-out", session.id)
        assertEquals(300, session.actualSec)
        assertEquals(330, awaitTask("t1") { it.totalFocused == 330 }.totalFocused)
        awaitPending { l -> l.any { it.recordTable == Tables.SESSIONS && it.recordId == "s-out" } }
        assertNull("live session cleared before the wipe", awaitLiveSession { it == null })
    }

    @Test fun signOut_leavesAPartnerCoFocusSessionAlone() = runTest(dispatcher) {
        // A partner session is the ONE true shared session: ending it locally would
        // broadcast `ended` and cut the partner off; the partner finalizes it via the
        // ledger. So sign-out must not finalize (no Session row, no ledger record).
        val t = task("t1", name = "Brief", estimateMin = 25, totalFocused = 0)
        seedTask(t)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)
        store.setLiveSession(LiveSession(id = "sid-p", taskId = "t1", sessionStart = nowMs - 300_000L, sessionEstimateMin = 25, treatment = FocusTreatment.AMBIENT, sharedSessionRev = 2, sharedSessionAtMs = nowMs - 300_000L))
        advanceUntilIdle()

        vm.signOut()
        advanceUntilIdle()

        assertTrue(store.sessions().first().isEmpty())
        assertNull(graph.settings.loadPendingSharedFocusRaw())
        assertEquals(0, loadTask("t1")!!.totalFocused)
    }

    @Test fun startFocus_todaysOccurrence_overAPausedSessionOfYesterdaysOccurrence_rePointsAndStaysPaused() = runTest(dispatcher) {
        // Contract (FOCUS b): start(occurrenceBlockId) must attach to THAT occurrence —
        // never silently resume a paused session left over from an earlier occurrence
        // of the same template and later tick the OLD day's block. Android's rule:
        // re-point occurrenceBlockId at today's block, keep the session paused (the
        // user resumes explicitly; opening Focus never resumes).
        val template = task("tpl", name = "Run", recurrence = Recurrence.Daily(), totalFocused = 60)
        val yesterday = CalBlock(id = "occY", taskId = "tpl", taskName = "Run", startTime = "07:00", durationMinutes = 30, date = "2026-05-21", kind = CalBlockKind.TASK)
        val today = CalBlock(id = "occT", taskId = "tpl", taskName = "Run", startTime = "07:00", durationMinutes = 30, date = "2026-05-22", kind = CalBlockKind.TASK)
        seedTask(template); seedBlock(yesterday); seedBlock(today)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)
        store.setLiveSession(
            LiveSession(
                id = "sessY", taskId = "tpl", sessionStart = nowMs - 600_000L, sessionEstimateMin = 30, treatment = FocusTreatment.AMBIENT,
                priorAccumulatedSec = 60, occurrenceBlockId = "occY", paused = true, pausedAt = nowMs - 300_000L,
            ),
        )
        advanceUntilIdle()

        vm.startFocus(template.copy(id = "occT", recurrence = null))   // the projected TODAY row
        advanceUntilIdle()

        val live = awaitLiveSession { it?.occurrenceBlockId == "occT" }!!
        assertEquals("same session — no re-mint", "sessY", live.id)
        assertTrue("stays paused: opening Focus never resumes", live.paused)
        assertTrue("no finalize of the old occurrence", store.sessions().first().isEmpty())

        // Completing now ticks TODAY's block, not yesterday's.
        vm.finishFocus(template.copy(id = "occT", recurrence = null), markDone = true)
        advanceUntilIdle()
        awaitBlock("occT") { it.done }
        assertFalse("yesterday's occurrence untouched", loadBlock("occY")!!.done)
    }

    // -----------------------------------------------------------------------
    // Regressions from the 2026-09-12 core review (web commit 964a7a9)
    // -----------------------------------------------------------------------

    @Test fun startFocus_fromTheReminderNotification_bindsATemplateToTodaysOccurrence() = runTest(dispatcher) {
        // The at-start reminder's "Start" action deep-links unstuck://focus/<taskId>,
        // and a recurring block's task_id is the hidden TEMPLATE — so this entry
        // point hands startFocus the template itself, not the projected occurrence
        // row every in-app Start passes. It used to open a session with NO
        // occurrence attached: the minutes accrued on the series but "Done" flipped
        // the template's own `done` (a row no list shows, which keeps generating)
        // while today's occurrence stayed open.
        val today = Clock.todayIso()
        val template = task("tpl", name = "Run", recurrence = Recurrence.Daily(), totalFocused = 60)
        val occ = CalBlock(id = "occT", taskId = "tpl", taskName = "Run", startTime = "07:00", durationMinutes = 30, date = today, kind = CalBlockKind.TASK)
        seedTask(template); seedBlock(occ)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        vm.startFocus(template)   // exactly what MainScaffold's deep-link branch does
        advanceUntilIdle()

        val live = awaitLiveSession { it?.sessionStart != null }!!
        assertEquals("the session runs on the template (totalFocused accrues on the series)", "tpl", live.taskId)
        assertEquals("…with THIS day's occurrence attached for completion", "occT", live.occurrenceBlockId)

        vm.finishFocus(template, markDone = true)
        advanceUntilIdle()
        awaitBlock("occT") { it.done }
        assertFalse("the hidden template is never flipped done", loadTask("tpl")!!.done)
    }

    @Test fun startFocus_comingBackThroughTheTemplate_keepsTheSessionsOwnDay() = runTest(dispatcher) {
        // Today's live card and the assistant's PAUSED chip hand startFocus the
        // TEMPLATE (live.taskId). A session started on yesterday's overdue day was
        // re-pointed at today's first open block, so Done ticked today and left
        // yesterday overdue (parity with iOS build 81 reopenLiveFocus, audit
        // 2026-09-22 C3).
        val today = Clock.todayIso()
        val template = task("tpl", name = "Meds", recurrence = Recurrence.Daily(), totalFocused = 60)
        seedTask(template)
        seedBlock(CalBlock(id = "occY", taskId = "tpl", taskName = "Meds", startTime = "08:00", durationMinutes = 10, date = addDaysIso(today, -1), kind = CalBlockKind.TASK))
        seedBlock(CalBlock(id = "occT", taskId = "tpl", taskName = "Meds", startTime = "20:00", durationMinutes = 10, date = today, kind = CalBlockKind.TASK))
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)
        store.setLiveSession(
            LiveSession(
                id = "sessY", taskId = "tpl", sessionStart = nowMs - 600_000L, sessionEstimateMin = 10, treatment = FocusTreatment.AMBIENT,
                priorAccumulatedSec = 60, occurrenceBlockId = "occY", paused = true, pausedAt = nowMs - 300_000L,
            ),
        )

        assertTrue(vm.startFocusNow(template))   // the live card's row
        val live = store.getLiveSession()!!
        assertEquals("same session", "sessY", live.id)
        assertEquals("still bound to the day it was started on", "occY", live.occurrenceBlockId)
        assertTrue("stays paused", live.paused)

        assertTrue(vm.finishFocusNow(template, markDone = true))
        assertTrue(loadBlock("occY")!!.done)
        assertFalse("today's day is left for today", loadBlock("occT")!!.done)
    }

    @Test fun startFocus_throughTheTemplate_rePointsASessionWhoseDayIsGone() = runTest(dispatcher) {
        // The session's own block was deleted (the series re-planned): today's open
        // block takes over, as before.
        val today = Clock.todayIso()
        val template = task("tpl", name = "Meds", recurrence = Recurrence.Daily())
        seedTask(template)
        seedBlock(CalBlock(id = "occT", taskId = "tpl", taskName = "Meds", startTime = "20:00", durationMinutes = 10, date = today, kind = CalBlockKind.TASK))
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)
        store.setLiveSession(
            LiveSession(id = "sessY", taskId = "tpl", sessionStart = nowMs - 600_000L, sessionEstimateMin = 10, treatment = FocusTreatment.AMBIENT, occurrenceBlockId = "gone"),
        )

        assertTrue(vm.startFocusNow(template))
        assertEquals("occT", store.getLiveSession()!!.occurrenceBlockId)
    }

    @Test fun finishFocus_backstopsALiveSessionThatCarriesOnlyTheTemplate() = runTest(dispatcher) {
        // A session minted BEFORE that fix (or persisted across the upgrade) has no
        // occurrenceBlockId and a template taskId. Finishing it must still tick the
        // day's block rather than the template.
        val today = Clock.todayIso()
        val template = task("tpl", name = "Run", recurrence = Recurrence.Daily(), totalFocused = 60)
        val occ = CalBlock(id = "occT", taskId = "tpl", taskName = "Run", startTime = "07:00", durationMinutes = 30, date = today, kind = CalBlockKind.TASK)
        seedTask(template); seedBlock(occ)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)
        store.setLiveSession(
            LiveSession(id = "sessLegacy", taskId = "tpl", sessionStart = nowMs - 300_000L, sessionEstimateMin = 30, treatment = FocusTreatment.AMBIENT, priorAccumulatedSec = 60),
        )
        advanceUntilIdle()

        vm.finishFocus(template, markDone = true)
        advanceUntilIdle()

        awaitBlock("occT") { it.done }
        assertFalse("the hidden template is never flipped done", loadTask("tpl")!!.done)
    }

    @Test fun leaveCollection_keepsTheListWhenTheServerDidNotConfirm() = runTest(dispatcher) {
        // Revoking access must never be REPORTED as done when the server didn't do
        // it. The three revoke calls used to swallow every outcome, so a refusal
        // (403 / 5xx / offline — here: no share client at all) read as success and
        // Leave dropped the list from this device while the membership stood; it
        // came straight back on the next hydrate.
        val shared = ItemCollection(id = "c1", name = "Team reads", color = "indigo", items = emptyList(), sortOrder = 0, ownerId = "grace", myRole = "editor")
        seedCollection(shared)
        val vm = vm()
        subscribeReads(vm, vm.collections)

        assertFalse("no confirmation → not left", vm.leaveCollection("c1"))
        advanceUntilIdle()
        assertNotNull("the list is still here, so the screen can say so", loadCollection("c1"))
        assertFalse("…and neither revoke claims success either", vm.unshareCollection("c1", "someone"))
        assertFalse(vm.cancelCollectionInvite("c1", "someone@example.com"))
    }

    @Test fun scheduleTask_clearsTheLaterParking_andTheMoveBumpCannotBringItBack() = runTest(dispatcher) {
        // Giving a parked task a real slot ends "Later" — otherwise it sat on the
        // calendar at the chosen time while Today, Backlog and Start-next all
        // filtered it out as deferred. Only the task-detail sheet used to do this,
        // at its own call site; the calendar, the create sheet and the assistant
        // did not.
        val parked = task("t1", name = "Call").copy(later = true)
        seedTask(parked)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        val date = Clock.dateIso(nowMs + 3 * 86_400_000L)
        vm.scheduleTask(parked, date, "14:00")
        advanceUntilIdle()
        assertEquals(false, awaitTask("t1") { it.later == false }.later)
        // Gate on the VM's OWN blocks snapshot before scheduling again: the second
        // call reads `blocks.value` to find the anchor to move, and that mirror
        // lands on a real Room thread — without this the move could read an empty
        // list, create a second block instead of moving one, and never bump.
        val placed = awaitBlocks { l -> l.any { it.taskId == "t1" } }.first { it.taskId == "t1" }
        vm.blocks.first { l -> l.any { it.id == placed.id } }

        // Re-schedule with the STALE row the caller is still holding (later = true):
        // the move-count bump is a WHOLE-ROW upsert, so it must be built from the
        // cleared row, not the caller's snapshot, or it writes later=true back.
        vm.scheduleTask(parked, date, "16:30")
        advanceUntilIdle()
        val moved = awaitTask("t1") { it.moveCount == 1 }
        assertEquals("the move bump must not resurrect the Later flag", false, moved.later)
    }

    @Test fun scheduleTask_writesOntoTheStoredRow_notTheCallersCopy() = runTest(dispatcher) {
        // The task sheet hands over the row it had when the date dialog opened. An
        // edit synced in while the pickers were up (a rename, focus minutes) was
        // reverted by the move-count bump's whole-row write (parity with iOS build
        // 81, audit 2026-09-22 C5).
        val snapshot = task("t1", name = "Call mom")
        seedTask(snapshot.copy(name = "Call Mum", totalFocused = 300))
        seedBlock(CalBlock(id = "b1", taskId = "t1", taskName = "Call mom", startTime = "09:00", durationMinutes = 25, date = Clock.dateIso(nowMs + 86_400_000L), kind = CalBlockKind.TASK))
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        vm.scheduleTask(snapshot, Clock.dateIso(nowMs + 2 * 86_400_000L), "10:00")
        advanceUntilIdle()

        val moved = awaitTask("t1") { it.moveCount == 1 }
        assertEquals("the rename made meanwhile stands", "Call Mum", moved.name)
        assertEquals("…and so do the focus minutes", 300, moved.totalFocused)
    }

    @Test fun scheduleTask_leavesARecurringTemplatesLaterFlagAlone() = runTest(dispatcher) {
        // A template's occurrence blocks are generated horizon fill, not a
        // per-task scheduling decision (and projected rows are later=false already).
        val template = task("tpl", name = "Meditate", recurrence = Recurrence.Daily()).copy(later = true)
        seedTask(template)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        val chosen = Clock.dateIso(System.currentTimeMillis() + 2 * 86_400_000L)
        vm.scheduleTask(template, chosen, "08:00")
        advanceUntilIdle()
        awaitBlocks { l -> l.any { it.taskId == "tpl" && it.date == chosen } }
        assertEquals(true, loadTask("tpl")!!.later)
    }

    // ── verifier pass, 2026-09-12: holes the first port left open ────────────

    @Test fun moveBlock_onTheCalendar_alsoEndsTheLaterParking() = runTest(dispatcher) {
        // Dragging a block to a new slot IS scheduling, but it bypasses
        // scheduleTaskNow — so a parked task dragged on the calendar stayed
        // parked: on the grid at the chosen time, filtered out of Today,
        // Backlog and Start-next as deferred. The move-count bump is a
        // WHOLE-ROW upsert, so it has to carry the cleared flag too.
        val parked = task("t1", name = "Call the bank").copy(later = true)
        val today = Clock.todayIso()
        val block = CalBlock(id = "b1", taskId = "t1", taskName = "Call the bank", startTime = "09:00", durationMinutes = 25, date = today, kind = CalBlockKind.TASK)
        seedTask(parked); seedBlock(block)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        vm.moveBlock(block, today, "15:30")
        advanceUntilIdle()

        awaitBlock("b1") { it.startTime == "15:30" }
        val moved = awaitTask("t1") { it.moveCount == 1 }
        assertEquals("the drag ends the Later parking", false, moved.later)
        // …and the task is now offered by the active lists instead of hidden.
        assertTrue(
            visibleTasks(TaskListView.TODAY, listOf(moved), listOf(block.copy(startTime = "15:30")), System.currentTimeMillis(), null, slipMode = false)
                .any { it.id == "t1" },
        )
    }

    @Test fun moveBlock_leavesAnUnparkedTaskExactlyAsBefore() = runTest(dispatcher) {
        // The un-park must not invent a write for a task that was never parked.
        val plain = task("t1", name = "Write memo")
        val today = Clock.todayIso()
        val block = CalBlock(id = "b1", taskId = "t1", taskName = "Write memo", startTime = "09:00", durationMinutes = 25, date = today, kind = CalBlockKind.TASK)
        seedTask(plain); seedBlock(block)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)

        vm.moveBlock(block, today, "11:00")
        advanceUntilIdle()
        val moved = awaitTask("t1") { it.moveCount == 1 }
        assertNull("no Later flag is invented", moved.later)
    }

    @Test fun finishFocus_withDone_neverEndsARecurringSeries() = runTest(dispatcher) {
        // The notification's "Start" hands focus the hidden TEMPLATE. When today's
        // occurrence was ticked or skipped between the notification and the tap,
        // NO block resolves — and "Done" then flipped the template's own `done`,
        // which stops the series generating and hides it from every list. The
        // session's minutes must still accrue; only the completion is withheld.
        val today = Clock.todayIso()
        val template = task("tpl", name = "Run", recurrence = Recurrence.Daily(), totalFocused = 60)
        val alreadyDone = CalBlock(id = "occT", taskId = "tpl", taskName = "Run", startTime = "07:00", durationMinutes = 30, date = today, kind = CalBlockKind.TASK, done = true, completedAt = "2026-05-21T08:00:00.000Z")
        seedTask(template); seedBlock(alreadyDone)
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)
        store.setLiveSession(
            LiveSession(id = "sessX", taskId = "tpl", sessionStart = nowMs - 600_000L, sessionEstimateMin = 30, treatment = FocusTreatment.AMBIENT, priorAccumulatedSec = 60),
        )
        advanceUntilIdle()

        vm.finishFocus(template, markDone = true)
        advanceUntilIdle()

        val after = awaitTask("tpl") { it.totalFocused > 60 }
        assertFalse("the series survives — the template is never flipped done", after.done)
        assertNull(after.completedAt)
        assertTrue("the session's time still accrues on the series", after.totalFocused > 60)
        // The day that was already ticked is untouched, and the session is recorded.
        assertTrue(loadBlock("occT")!!.done)
        awaitSessions { l -> l.any { it.taskId == "tpl" } }
    }

    @Test fun assistant_promoteItemToTask_refusesAnInFlightPromotion() = runTest(dispatcher) {
        // Web/iOS parity: the tool must not answer "ok: promoted" (and mint a receipt)
        // when moveItemToTask's guard silently no-ops on an already-promoted, not-done item.
        uid = "me"
        val inFlight = CollectionItem("i1", "Buy milk", at = "2026-05-21T10:00:00.000Z", promoted = true, promotedDone = false, assignee = "Ada")
        val col = ItemCollection(id = "c1", name = "Home", color = "indigo", items = listOf(inFlight), sortOrder = 0, ownerId = "me")
        seedCollection(col)
        val vm = vm()
        subscribeReads(vm, vm.collections, vm.tasks)

        val args = kotlinx.serialization.json.JsonObject(
            mapOf(
                "listId" to kotlinx.serialization.json.JsonPrimitive("c1"),
                "itemId" to kotlinx.serialization.json.JsonPrimitive("i1"),
                "mode" to kotlinx.serialization.json.JsonPrimitive("self"),
            ),
        )
        val result = vm.runAssistantTool("promote_item_to_task", args, HashMap(), HashMap())
        advanceUntilIdle()

        assertEquals("error: \"Buy milk\" is already promoted \u2014 its task is still in flight", result)
        assertTrue("no task was created", store.tasks().first().isEmpty())
    }

    @Test fun assistant_promoteItemToTask_promotesAFreshItem() = runTest(dispatcher) {
        uid = "me"
        val item = CollectionItem("i1", "Buy milk", at = "2026-05-21T10:00:00.000Z")
        val col = ItemCollection(id = "c1", name = "Home", color = "indigo", items = listOf(item), sortOrder = 0, ownerId = "me")
        seedCollection(col)
        val vm = vm()
        subscribeReads(vm, vm.collections, vm.tasks)
        val args = kotlinx.serialization.json.JsonObject(
            mapOf("listId" to kotlinx.serialization.json.JsonPrimitive("c1"), "itemId" to kotlinx.serialization.json.JsonPrimitive("i1")),
        )
        val result = vm.runAssistantTool("promote_item_to_task", args, HashMap(), HashMap())
        advanceUntilIdle()
        // 2026-09-20: the result carries the new task's id and says which mode applied.
        assertTrue(result, result.startsWith("ok: promoted \"Buy milk\" to a task id="))
        assertTrue(result, result.endsWith("(just theirs)"))
        assertEquals("Buy milk", awaitTasks { it.isNotEmpty() }.single().name)
    }

    @Test fun assistant_undoReceipt_revertsABulkCompletion() = runTest(dispatcher) {
        // complete_tasks / create_tasks receipts carry their targets in
        // ReceiptUndo.ids (`id` stays empty). Reading only `id` made Undo a
        // silent no-op — the tasks stayed done while the receipt still offered it.
        seedTask(task("a", "Call mum", done = true))
        seedTask(task("b", "Bins", done = true))
        val vm = vm()
        subscribeReads(vm, vm.tasks)
        vm.assistantHistory.add(
            tech.csalliance.unstuck.sync.ChatMessage(
                role = "assistant", content = "Completed 2 tasks", id = "m1",
                receipts = listOf(
                    tech.csalliance.unstuck.core.logic.Receipt(
                        tech.csalliance.unstuck.core.logic.ReceiptIcon.CHECK, "Completed 2 tasks",
                        tech.csalliance.unstuck.core.logic.ReceiptUndo.uncompleteTasks(listOf("a", "b")),
                    ),
                ),
            ),
        )

        vm.undoAssistantReceipt("m1", 0)
        advanceUntilIdle()

        awaitTasks { l -> l.size == 2 && l.none { it.done } }
        // Both un-completions ride the outbox — the undo's LAST durable step. The
        // receipt's `undone` flag is set after that, on the test dispatcher, so gate
        // on the queue and then flush the tail; asserting `undone` straight off the
        // task rows raced the second half of the same coroutine.
        awaitPending { l -> l.count { it.recordTable == Tables.TASKS && it.op == "upsert" } >= 2 }
        advanceUntilIdle()
        assertTrue("receipt marked used", vm.assistantHistory.first().receipts!![0].undone)
    }

    @Test fun assistant_undoReopened_onATaskReTickedByHand_isUsedWithoutAWrite() = runTest(dispatcher) {
        // "Reopened: Call mum" → the user ticked it again by hand → Undo. The target
        // state already holds: the receipt is used up, and the real completion time
        // is not re-stamped to now (parity with iOS build 81, audit 2026-09-22 C6).
        val doneAt = "2026-05-21T09:00:00.000Z"
        seedTask(task("a", "Call mum", done = true).copy(completedAt = doneAt))
        val vm = vm()
        subscribeReads(vm, vm.tasks)
        vm.assistantHistory.add(
            tech.csalliance.unstuck.sync.ChatMessage(
                role = "assistant", content = "Reopened it", id = "m1",
                receipts = listOf(
                    tech.csalliance.unstuck.core.logic.Receipt(
                        tech.csalliance.unstuck.core.logic.ReceiptIcon.CHECK, "Reopened: Call mum",
                        tech.csalliance.unstuck.core.logic.ReceiptUndo.completeTask("a"),
                    ),
                ),
            ),
        )

        vm.undoAssistantReceipt("m1", 0)
        awaitGateway { vm.assistantHistory.first().receipts!![0].undone }

        assertEquals(doneAt, loadTask("a")!!.completedAt)
        assertTrue("no write queued", store.pending().none { it.recordTable == Tables.TASKS })
    }

    // -----------------------------------------------------------------------
    // Gateway (the AI card on Today) — A2. Moment actions must write through the
    // SAME seam the assistant's tools use (AssistantApi → WriteThrough → Room +
    // outbox), the composer hand-off must open the sheet AND queue the message,
    // and the interview must never open by itself before the account's server
    // flag has been applied (plan F9).
    // -----------------------------------------------------------------------

    private fun moment(run: MomentRun, id: String = "m1") =
        Moment(id = id, kind = MomentKind.RITUAL, priority = 2, salience = 1, text = "moment", actions = listOf(MomentAction("Do it", run)))

    private fun taskBlock(id: String, taskId: String, date: String, time: String = "10:00") =
        CalBlock(id = id, taskId = taskId, taskName = "T", startTime = time, durationMinutes = 30, date = date, kind = CalBlockKind.TASK)

    /** A gateway action whose ONLY effect is bookkeeping (no store write to
     *  await) still suspends on a Room READ on a real thread before it settles —
     *  poll the virtual scheduler until [cond] holds. */
    private fun TestScope.awaitGateway(cond: () -> Boolean) {
        repeat(300) {
            advanceUntilIdle()
            if (cond()) return
            Thread.sleep(10)
        }
        error("gateway action never settled")
    }

    /** Drive an assistant turn to rest (the style-preference save awaits Room on a
     *  real executor thread the virtual scheduler can't advance through). */
    private fun TestScope.settleAssistantTurn(vm: AppViewModel) {
        repeat(300) {
            advanceUntilIdle()
            if (!vm.assistantSending.value) { Thread.sleep(25); advanceUntilIdle(); return }
            Thread.sleep(10)
        }
        error("assistant turn never settled")
    }

    @Test fun gateway_carryTasksMoment_movesTodaysBlockThroughTheAssistantApi() = runTest(dispatcher) {
        val today = Clock.dateIso(nowMs)
        val tomorrow = addDaysIso(today, 1)
        seedTask(task("a", "Gym"))
        seedBlock(taskBlock("b1", "a", today))
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)
        val m = moment(MomentRun.CarryTasks(listOf("a")))

        vm.runMomentAction(m, m.actions[0])
        advanceUntilIdle()

        // The same rows the carry_to_tomorrow tool would write: block moved, an
        // honest slip counter, and the change queued for the account (outbox).
        awaitBlock("b1") { it.date == tomorrow && !it.skipped }
        assertEquals(1, awaitTask("a") { it.moveCount == 1 }.moveCount)
        awaitPending { l ->
            l.any { it.recordTable == Tables.CAL_BLOCKS && it.recordId == "b1" } &&
                l.any { it.recordTable == Tables.TASKS && it.recordId == "a" }
        }
        advanceUntilIdle()   // past the last durable hop; the moment's in-memory tail runs now
        assertTrue("the moment is settled", vm.isMomentDismissed("m1"))
        assertEquals("Carried 1 to tomorrow.", vm.momentDone.value)
        vm.clearMomentDone("someone else's ✓")
        assertEquals("only its own confirmation clears", "Carried 1 to tomorrow.", vm.momentDone.value)
        vm.clearMomentDone("Carried 1 to tomorrow.")
        assertNull(vm.momentDone.value)
    }

    @Test fun gateway_carryTasksMoment_withNothingOnTodayWritesNothingAndStaysUp() = runTest(dispatcher) {
        seedTask(task("a", "Gym"))
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)
        val m = moment(MomentRun.CarryTasks(listOf("a")))
        vm.runMomentAction(m, m.actions[0])
        // Let the action's Room reads + the (empty) reduce fully settle first.
        repeat(5) { advanceUntilIdle(); Thread.sleep(10) }
        advanceUntilIdle()
        assertFalse("not acted on → not dismissed", vm.isMomentDismissed("m1"))
        assertNull(vm.momentDone.value)
        assertTrue(store.blocks().first().isEmpty())
        assertNull(loadTask("a")!!.moveCount)
    }

    @Test fun gateway_scheduleMoment_createsTheBlockThroughTheAssistantApi() = runTest(dispatcher) {
        seedTask(task("a", "Gym", estimateMin = 45))
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)
        val m = moment(MomentRun.Schedule("a", "2031-01-07", "18:30"))
        vm.runMomentAction(m, m.actions[0])
        advanceUntilIdle()
        val b = awaitBlocks { l -> l.any { it.taskId == "a" } }.single { it.taskId == "a" }
        assertEquals("2031-01-07", b.date)
        assertEquals("18:30", b.startTime)
        assertEquals(45, b.durationMinutes)
        assertEquals(CalBlockKind.TASK, b.kind)
        awaitPending { l -> l.any { it.recordTable == Tables.CAL_BLOCKS && it.recordId == b.id } }
        advanceUntilIdle()   // past the last durable hop; the moment's in-memory tail runs now
        assertNull("booking a habit gap isn't a slip", loadTask("a")!!.moveCount)
        assertTrue(vm.isMomentDismissed("m1"))
        assertEquals("Blocked — Gym, 2031-01-07 18:30.", vm.momentDone.value)
    }

    @Test fun gateway_scheduleMoment_forAVanishedTaskWritesNothingButRetiresTheMoment() = runTest(dispatcher) {
        val vm = vm()
        subscribeReads(vm, vm.tasks, vm.blocks)
        val m = moment(MomentRun.Schedule("ghost", "2031-01-07", null))
        vm.runMomentAction(m, m.actions[0])
        awaitGateway { vm.isMomentDismissed("m1") }
        assertTrue(store.blocks().first().isEmpty())
        assertNull(vm.momentDone.value)
    }

    @Test fun gateway_createTaskMoment_addsTheTaskThroughTheAssistantApi() = runTest(dispatcher) {
        val vm = vm()
        subscribeReads(vm, vm.tasks)
        val m = moment(MomentRun.CreateTask("Call mum", null))
        vm.runMomentAction(m, m.actions[0])
        advanceUntilIdle()
        val t = awaitTasks { it.isNotEmpty() }.single()
        assertEquals("Call mum", t.name)
        assertEquals(25, t.estimateMin)
        assertFalse(t.done)
        awaitPending { l -> l.any { it.recordTable == Tables.TASKS && it.recordId == t.id } }
        advanceUntilIdle()   // past the last durable hop; the moment's in-memory tail runs now
        assertEquals("Added “Call mum”.", vm.momentDone.value)
        assertTrue(vm.isMomentDismissed("m1"))
    }

    @Test fun gateway_dismissMoment_onlyRecordsTheDismissal() = runTest(dispatcher) {
        val vm = vm()
        val m = moment(MomentRun.Dismiss)
        vm.runMomentAction(m, m.actions[0])
        advanceUntilIdle()
        assertTrue(vm.isMomentDismissed("m1"))
        assertNull(vm.momentDone.value)
        assertTrue(store.tasks().first().isEmpty())
    }

    @Test fun gateway_chatMoment_settlesThenHandsTheMessageToTheAssistant() = runTest(dispatcher) {
        val vm = vm()
        var opens = 0
        backgroundScope.launch { vm.assistantOpenRequests.collect { opens += 1 } }
        advanceUntilIdle()
        val m = moment(MomentRun.Chat("How did the call go?"))

        vm.runMomentAction(m, m.actions[0])
        settleAssistantTurn(vm)

        assertTrue(vm.isMomentDismissed("m1"))
        assertEquals("the sheet was asked to open", 1, opens)
        // The message took the SAME path as a typed one: it's the user turn of
        // the thread (the turn itself fails offline — no coordinator).
        assertEquals("How did the call go?", vm.assistantHistory.first { it.role == "user" }.content)
        assertEquals("not_configured", vm.assistantError.value)
    }

    @Test fun gateway_openAssistantWith_opensTheSheetAndQueuesThroughSendAssistant() = runTest(dispatcher) {
        val vm = vm()
        var opens = 0
        backgroundScope.launch { vm.assistantOpenRequests.collect { opens += 1 } }
        advanceUntilIdle()

        vm.openAssistantWith("   ")
        advanceUntilIdle()
        assertEquals("blank is a no-op", 0, opens)
        assertTrue(vm.assistantHistory.isEmpty())

        vm.openAssistantWith("Plan my day — what should I start with and what order makes sense?")
        settleAssistantTurn(vm)
        assertEquals(1, opens)
        assertEquals("Plan my day — what should I start with and what order makes sense?", vm.assistantHistory.first { it.role == "user" }.content)
    }

    @Test fun voice_finishInterview_isAnsweredBeforeTheExecutorAndMarksTheAccountDone() = runTest(dispatcher) {
        // The talk-level tool (voice-only): the opening primer's intro is over.
        val vm = vm()
        vm.setInterviewStep(3)
        assertFalse(vm.interviewDone.value)
        val out = vm.runVoiceTool(tech.csalliance.unstuck.ui.assistant.FinishInterviewTool.NAME, kotlinx.serialization.json.JsonObject(emptyMap()))
        assertEquals(tech.csalliance.unstuck.ui.assistant.FinishInterviewTool.OK, out)
        assertTrue("local flag", vm.interviewDone.value)
        assertNull("resume step dropped", vm.interviewParkedStep(7))
        assertFalse("the assistant API seam sees it", tech.csalliance.unstuck.ui.assistant.AppViewModelAssistantApi(vm).interviewPending())
        // The Talk schema advertises it; the executor never sees an unknown tool.
        assertTrue(vm.voiceTools().any { kotlinx.serialization.json.JsonObject::class.java.cast(it)["name"].toString().contains("finish_interview") })
    }

    @Test fun assistant_openAssistant_asksForTheComposerWithoutSendingAnything() = runTest(dispatcher) {
        val vm = vm()
        val requests = mutableListOf<AppViewModel.AssistantOpenRequest>()
        // The request flow has no replay: subscribe synchronously (UNDISPATCHED
        // runs the collector up to its first suspension) before emitting.
        backgroundScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { vm.assistantOpenRequests.collect { requests += it } }
        vm.openAssistant(focusComposer = true)
        settleUntil { requests.isNotEmpty() }
        assertEquals(listOf(AppViewModel.AssistantOpenRequest(handoff = false, focusComposer = true)), requests)
        assertTrue("nothing is typed on Today", vm.assistantHistory.isEmpty())
        vm.openAssistantWith("Plan my day")
        settleAssistantTurn(vm)
        assertEquals(AppViewModel.AssistantOpenRequest(handoff = true, focusComposer = false), requests.last())
        assertEquals(2, requests.size)
    }

    /** Advance (with real-thread hops) until [ready] — the same loop [settleAssistantTurn] runs. */
    private fun TestScope.settleUntil(ready: () -> Boolean) {
        repeat(300) {
            advanceUntilIdle()
            if (ready()) return
            Thread.sleep(10)
        }
        error("condition never settled")
    }

    @Test fun assistant_localTurns_carryIdsAndAUserEcho_neverEnterTheModelWindow() = runTest(dispatcher) {
        val vm = vm()
        val id = vm.appendLocalAssistant("When’s your head clearest?")
        assertNotNull(id)
        assertNull(vm.appendLocalAssistant("   "))
        vm.appendLocalUser("Morning")
        assertEquals(2, vm.assistantHistory.size)
        assertEquals(id, vm.assistantHistory[0].id)
        assertTrue(vm.assistantHistory.all { it.local })
        assertEquals("user", vm.assistantHistory[1].role)
        assertEquals("Morning", vm.assistantHistory[1].content)
        assertTrue(tech.csalliance.unstuck.core.logic.assistantModelWindow(vm.assistantHistory).isEmpty())
    }

    @Test fun gateway_strugglesFromTheServerAreCanonicalisedCachedAndReadByTheAssistantApi() = runTest(dispatcher) {
        val vm = vm()
        assertTrue(vm.struggles.value.isEmpty())
        vm.completeAssistantHydrate("me", PreferencesClient.ServerUserPrefs(adhd_struggles = listOf("Getting started", "Distraction", "nonsense")))
        assertEquals(listOf("Starting", "Sustaining"), vm.struggles.value)
        assertEquals("the context builder's read", listOf("Starting", "Sustaining"), vm.assistantApi.getStruggles())
        // A fresh ViewModel for the same account reads the cache back (offline launch).
        assertEquals(listOf("Starting", "Sustaining"), vm().struggles.value)
        // Sign-out scrub: the next account on this phone never inherits them.
        vm.scrubAssistantUserState()
        assertTrue(vm.struggles.value.isEmpty())
        assertTrue(vm().struggles.value.isEmpty())
    }
}
