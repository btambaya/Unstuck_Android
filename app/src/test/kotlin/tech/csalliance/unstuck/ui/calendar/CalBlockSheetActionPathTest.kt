package tech.csalliance.unstuck.ui.calendar

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
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
import tech.csalliance.unstuck.core.logic.CalBlockSheetActions
import tech.csalliance.unstuck.core.logic.blockIsDone
import tech.csalliance.unstuck.core.logic.calBlockSheetActions
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.LiveSession
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.sync.WriteThrough
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.ViewModelDrain

/**
 * The calendar Edit-block sheet's task actions, end to end on the model
 * (Ahmad 2026-09-24: "Can't complete a task from calendar"): the sheet resolves
 * the block to the row Today shows ([calBlockSheetActions]) and hands it to the
 * same AppViewModel paths Today and the task screen use — Mark done / Mark not
 * done is `toggleDone`, Start focus is `startFocus`. Asserted here on a real
 * in-memory store + WriteThrough (AppViewModelTest's harness), the store being
 * the source of truth.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CalBlockSheetActionPathTest {

    private lateinit var db: UnstuckDatabase
    private lateinit var store: LocalStore
    private lateinit var write: WriteThrough
    private lateinit var graph: AppGraph
    private val dispatcher = StandardTestDispatcher()
    private val drain = ViewModelDrain(dispatcher.scheduler)

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnstuckDatabase::class.java)
            .allowMainThreadQueries().build()
        store = LocalStore(db)
        graph = AppGraph(ApplicationProvider.getApplicationContext(), configured = false, storeOverride = store)
        write = WriteThrough(graph.store)
        runCatching {
            androidx.work.WorkManager.initialize(
                ApplicationProvider.getApplicationContext(),
                androidx.work.Configuration.Builder().build(),
            )
        }
    }

    // As AppViewModelTest: leave the in-memory db open (a lingering Room read would
    // race a close), run what the ViewModels posted, and finish them before Main
    // is handed back.
    @After fun teardown() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        drain.drain()
        Dispatchers.resetMain()
    }

    private fun TestScope.vm(): AppViewModel = AppViewModel(
        graph = graph,
        writeOverride = write,
        currentUidProvider = { "me" },
        currentNameProvider = { "Ada" },
        nowProvider = { 1_700_000_000_000L },
    ).also { created ->
        drain.track(created)
        backgroundScope.coroutineContext.job.invokeOnCompletion { runCatching { created.viewModelScope.cancel() } }
    }

    /** Keep the WhileSubscribed lists hot and wait until they mirror the store. */
    private suspend fun TestScope.subscribeReads(vm: AppViewModel) {
        listOf<StateFlow<List<*>>>(vm.tasks, vm.blocks).forEach { f -> backgroundScope.launch { f.collect { } } }
        backgroundScope.launch { vm.liveSession.collect { } }
        backgroundScope.launch { vm.assignedOut.collect { } }
        val taskIds = store.tasks().first().map { it.id }.toSet()
        val blockIds = store.blocks().first().map { it.id }.toSet()
        vm.tasks.first { l -> l.map { it.id }.toSet() == taskIds }
        vm.blocks.first { l -> l.map { it.id }.toSet() == blockIds }
    }

    private fun task(id: String, name: String = "Write report", recurrence: Recurrence? = null, done: Boolean = false) = TaskItem(
        id = id, name = name, estimateMin = 25, recurrence = recurrence, done = done,
        completedAt = if (done) "2026-09-24T08:00:00.000Z" else null,
        createdAt = "2026-09-20T10:00:00.000Z", updatedAt = "2026-09-20T10:00:00.000Z",
    )

    private fun block(id: String, taskId: String?, date: String = "2026-09-24", kind: CalBlockKind = CalBlockKind.TASK, done: Boolean = false) = CalBlock(
        id = id, taskId = taskId, taskName = "Write report", startTime = "09:00", durationMinutes = 45, date = date, kind = kind,
        done = done, completedAt = if (done) "2026-09-24T09:40:00.000Z" else null,
    )

    private suspend fun seed(t: TaskItem) = store.upsert(Tables.TASKS, t, TaskItem.serializer(), t.id, t.updatedAt)
    private suspend fun seed(b: CalBlock) = store.upsert(Tables.CAL_BLOCKS, b, CalBlock.serializer(), b.id)

    private suspend fun awaitTask(id: String, p: (TaskItem) -> Boolean): TaskItem =
        store.tasks().first { l -> l.firstOrNull { it.id == id }?.let(p) == true }.first { it.id == id }
    private suspend fun awaitBlock(id: String, p: (CalBlock) -> Boolean): CalBlock =
        store.blocks().first { l -> l.firstOrNull { it.id == id }?.let(p) == true }.first { it.id == id }
    private suspend fun storedTask(id: String): TaskItem = store.tasks().first().first { it.id == id }
    private suspend fun storedBlock(id: String): CalBlock = store.blocks().first().first { it.id == id }
    private suspend fun awaitLive(p: (LiveSession?) -> Boolean): LiveSession? = store.liveSession().first(p)

    /** What the sheet computes for the stored block, from the VM's live lists. */
    private fun actionsFor(vm: AppViewModel, blockId: String): CalBlockSheetActions =
        calBlockSheetActions(vm.blocks.value.first { it.id == blockId }, vm.tasks.value, vm.assignedOut.value)

    @Test fun markDone_onAPlainTasksBlock_completesTheTask() = runTest(dispatcher) {
        seed(task("t1")); seed(block("b1", "t1"))
        val vm = vm()
        subscribeReads(vm)

        val a = actionsFor(vm, "b1")
        assertEquals("Mark done", a.completeLabel)
        assertEquals("t1", a.row!!.id)
        vm.toggleDone(a.row!!)                      // the sheet's Mark done
        advanceUntilIdle()

        val done = awaitTask("t1") { it.done }
        assertNotNull("completion stamped, as Today's tick does", done.completedAt)
        // Queued for sync through the outbox. Gated on the outbox's own flow: the op
        // lands after the row does (see AppViewModelTest.awaitPending).
        store.pendingCount().first { store.pending().any { it.recordTable == Tables.TASKS && it.recordId == "t1" && it.op == "upsert" } }
        assertFalse("the block itself is not what a plain task's done lives on", storedBlock("b1").done)
    }

    @Test fun markDone_onASeriesBlock_ticksThatDayOnly_neverTheSeries() = runTest(dispatcher) {
        seed(task("tpl", name = "Meds", recurrence = Recurrence.Daily()))
        seed(block("occ1", "tpl", date = "2026-09-24"))
        seed(block("occ2", "tpl", date = "2026-09-25"))
        val vm = vm()
        subscribeReads(vm)

        val a = actionsFor(vm, "occ1")
        assertEquals("the day's occurrence row", "occ1", a.row!!.id)
        vm.toggleDone(a.row!!)
        advanceUntilIdle()

        val ticked = awaitBlock("occ1") { it.done }
        assertNotNull(ticked.completedAt)
        assertFalse(ticked.skipped)
        assertFalse("the next day stays open", storedBlock("occ2").done)
        assertFalse("the series is never ended", storedTask("tpl").done)
        assertNull(storedTask("tpl").completedAt)
    }

    @Test fun markNotDone_onADoneTask_reopensIt() = runTest(dispatcher) {
        seed(task("t1", done = true)); seed(block("b1", "t1"))
        val vm = vm()
        subscribeReads(vm)

        val a = actionsFor(vm, "b1")
        assertTrue(a.done)
        assertEquals("Mark not done", a.completeLabel)
        vm.toggleDone(a.row!!)
        advanceUntilIdle()

        val open = awaitTask("t1") { !it.done }
        assertNull("the undo clears completion, as Today's untick does", open.completedAt)
    }

    @Test fun markNotDone_onATickedDay_reopensThatDay() = runTest(dispatcher) {
        seed(task("tpl", name = "Meds", recurrence = Recurrence.Daily()))
        seed(block("occ1", "tpl", done = true))
        val vm = vm()
        subscribeReads(vm)

        val a = actionsFor(vm, "occ1")
        assertEquals("Mark not done", a.completeLabel)
        vm.toggleDone(a.row!!)
        advanceUntilIdle()

        val open = awaitBlock("occ1") { !it.done }
        assertNull(open.completedAt)
        assertFalse(storedTask("tpl").done)
    }

    @Test fun aGoogleEventOffersNothingToComplete() = runTest(dispatcher) {
        seed(task("t1"))
        seed(block("g1", taskId = null, kind = CalBlockKind.EXTERNAL).copy(externalEventId = "evt-1", taskName = "Dentist"))
        val vm = vm()
        subscribeReads(vm)

        val a = actionsFor(vm, "g1")
        assertEquals(CalBlockSheetActions.NONE, a)
        assertNull(a.row)
        assertFalse(a.canComplete)
        assertFalse(a.canFocus)
        assertFalse(a.canOpen)
    }

    @Test fun startFocus_onASeriesBlock_runsOnTheSeriesAndFinishesThatDay() = runTest(dispatcher) {
        seed(task("tpl", name = "Meds", recurrence = Recurrence.Daily()))
        seed(block("occ1", "tpl", date = Clock.todayIso()))
        val vm = vm()
        subscribeReads(vm)

        vm.startFocus(actionsFor(vm, "occ1").row!!)  // what the focus overlay starts with
        advanceUntilIdle()

        val live = awaitLive { it?.sessionStart != null }!!
        assertEquals("time accrues on the series", "tpl", live.taskId)
        assertEquals("Done ticks this day", "occ1", live.occurrenceBlockId)
    }

    @Test fun startFocus_onAPlainTasksBlock_runsOnTheTask() = runTest(dispatcher) {
        seed(task("t1")); seed(block("b1", "t1"))
        val vm = vm()
        subscribeReads(vm)

        vm.startFocus(actionsFor(vm, "b1").row!!)
        advanceUntilIdle()

        val live = awaitLive { it?.sessionStart != null }!!
        assertEquals("t1", live.taskId)
        assertNull(live.occurrenceBlockId)
    }

    /**
     * The reported repro, through the real VM paths: today's day of a repeating
     * task ticked from the sheet, its repeat set to Never (setRecurrence →
     * taskAfterSettingRecurrence carries the tick onto the task; the block keeps
     * done = true), then Mark not done from the calendar. The task reopens and the
     * block keeps its done, so the grids' old `block.done || task.done` kept it
     * struck; [blockIsDone] (the Day / Week grids and Month peek) and the sheet
     * both read it open.
     */
    @Test fun aDayTickedThenRepeatNever_markNotDone_unstrikesTheBlock() = runTest(dispatcher) {
        val today = Clock.todayIso()
        seed(task("tpl", name = "Meds", recurrence = Recurrence.Daily()))
        seed(block("occ1", "tpl", date = today))
        val vm = vm()
        subscribeReads(vm)

        // 1. Mark done on today's block: ticks that day.
        vm.toggleDone(actionsFor(vm, "occ1").row!!)
        advanceUntilIdle()
        awaitBlock("occ1") { it.done }
        vm.blocks.first { l -> l.any { it.id == "occ1" && it.done } }   // setRecurrence reads the VM's list

        // 2. Repeat → Never: the tick carries onto the task, the block keeps done.
        assertTrue(vm.setRecurrence(storedTask("tpl"), null))
        advanceUntilIdle()
        awaitTask("tpl") { it.recurrence == null && it.done }
        vm.tasks.first { l -> l.any { it.id == "tpl" && it.recurrence == null && it.done } }
        assertTrue("the block keeps its tick", storedBlock("occ1").done)
        val before = actionsFor(vm, "occ1")
        assertEquals("the sheet acts on the task now", "tpl", before.row!!.id)
        assertEquals("Mark not done", before.completeLabel)
        assertTrue("struck on the grid", blockIsDone(storedBlock("occ1"), storedTask("tpl")))

        // 3. Mark not done from the calendar.
        vm.toggleDone(before.row!!)
        advanceUntilIdle()
        awaitTask("tpl") { !it.done }
        vm.tasks.first { l -> l.any { it.id == "tpl" && !it.done } }

        val blk = storedBlock("occ1")
        val t = storedTask("tpl")
        assertTrue("the block row itself still says done", blk.done)
        assertTrue("so the old grid rule kept it struck", blk.done || t.done)
        assertFalse("the grids no longer strike it", blockIsDone(blk, t))
        val after = actionsFor(vm, "occ1")
        assertFalse(after.done)
        assertEquals("the sheet agrees", "Mark done", after.completeLabel)
    }
}
