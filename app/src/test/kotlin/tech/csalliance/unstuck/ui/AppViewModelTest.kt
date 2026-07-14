package tech.csalliance.unstuck.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
import tech.csalliance.unstuck.core.logic.isTaskBlock
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
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.OutboxEntity
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.sync.WriteThrough

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
    }

    @After fun teardown() {
        // Intentionally do NOT close the in-memory db here. The VM exposes its
        // collections as SharingStarted.WhileSubscribed StateFlows whose upstream
        // Room flow runs on a REAL thread (LocalStore's flowOn(Default)) and lingers
        // briefly after runTest cancels its collectors. Closing the db would race
        // that lingering read → "connection pool closed". The in-memory db is
        // per-builder and GC'd with the test; Robolectric sandboxes each test.
        Dispatchers.resetMain()
    }

    private fun vm(): AppViewModel = AppViewModel(
        graph = graph,
        writeOverride = write,
        currentUidProvider = { uid },
        currentNameProvider = { displayName },
        nowProvider = { nowMs },
    )

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
        assertTrue(store.pending().any { it.recordTable == Tables.TASKS && it.recordId == "t1" && it.op == "upsert" })
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

        // View is read-only company — no focus session starts.
        awaitLiveSession { it == null }
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

        val blocks = awaitBlocks { l -> l.count { it.taskId == "tpl" } > 5 }.filter { it.taskId == "tpl" }
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
        assertTrue(
            "solo path enqueues an outbox collections upsert",
            store.pending().any { it.recordTable == Tables.COLLECTIONS && it.recordId == "c1" && it.op == "upsert" },
        )
    }

    @Test fun addCollectionItem_sharedList_optimisticLocalWriteNoOutbox() = runTest(dispatcher) {
        // Shared list (owned by someone else) → an item edit takes the
        // mutateCollectionItem path: an OPTIMISTIC local write (so the UI updates
        // immediately) with NO outbox op — the server write is the atomic item RPC
        // (CollectionShareClient), which is fire-and-forget here (no coordinator in
        // a unit test). The regression this locks: a shared-list item edit must NOT
        // ship the whole items JSONB through the outbox (which would clobber a
        // concurrent member edit).
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
        assertFalse(
            "shared item edit must NOT enqueue an outbox op (the item RPC is the server write)",
            store.pending().any { it.recordTable == Tables.COLLECTIONS && it.recordId == "c2" },
        )
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
        assertTrue(store.pending().any { it.recordTable == Tables.COLLECTIONS && it.recordId == "c1" && it.op == "upsert" })
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
        val item = CollectionItem(id = "i1", body = "Done already", at = "2026-05-21T10:00:00.000Z", promoted = true, promotedDone = false)
        val c = ItemCollection(id = "c1", name = "Home", color = "indigo", items = listOf(item), sortOrder = 0, ownerId = "me")
        seedCollection(c)
        val vm = vm()
        subscribeReads(vm, vm.collections, vm.tasks, vm.blocks)

        vm.moveItemToTask(c, item, AppViewModel.PromoteMode.SELF)
        advanceUntilIdle()

        assertTrue("no duplicate task minted for an in-flight promotion", store.tasks().first().none { it.name == "Done already" })
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
}
