package tech.csalliance.unstuck.calls

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.AppGraph
import tech.csalliance.unstuck.core.logic.CallCoordinatorLogic
import tech.csalliance.unstuck.core.logic.CallDecision
import tech.csalliance.unstuck.core.logic.CallEnv
import tech.csalliance.unstuck.core.logic.IncomingCallPayload
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.OutboxEntity
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase

/**
 * The receipt-time anchor check (Android audit 2026-09-23, A6). A task or block
 * made on the web / the iPhone shortly before its call is usually not in this
 * phone's store yet (no realtime in the background, a 30 min sync period): that
 * must ring — send-call already checked the anchor against the database — and
 * never be reported `stale` (terminal and silent). Only what THIS device knows
 * retires a call: the row is here and done / skipped, or its delete is queued.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AppCallEnvironmentTest {

    private lateinit var store: LocalStore
    private lateinit var graph: AppGraph

    @Before fun setup() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnstuckDatabase::class.java)
            .allowMainThreadQueries().build()
        store = LocalStore(db)
        graph = AppGraph(ApplicationProvider.getApplicationContext(), configured = false, storeOverride = store)
    }

    private val payload = IncomingCallPayload(
        callId = "0b8a7e60-1111-4222-8333-444455556666", label = "Call the dentist", notes = listOf("ask about Friday"),
        taskId = "t-web", blockId = "b-web", taskName = "Call the dentist",
    )

    private fun task(done: Boolean = false) = TaskItem(
        id = "t-web", name = "Call the dentist", estimateMin = 15, done = done,
        createdAt = "2026-09-23T09:50:00.000Z", updatedAt = "2026-09-23T09:50:00.000Z",
    )
    private fun block(done: Boolean = false, skipped: Boolean = false) = CalBlock(
        id = "b-web", taskId = "t-web", taskName = "Call the dentist", startTime = "10:00", durationMinutes = 15,
        date = "2026-09-23", kind = CalBlockKind.TASK, done = done, skipped = skipped,
    )
    private fun seed(t: TaskItem? = null, b: CalBlock? = null) = runBlocking {
        t?.let { store.upsert(Tables.TASKS, it, TaskItem.serializer(), it.id) }
        b?.let { store.upsert(Tables.CAL_BLOCKS, it, CalBlock.serializer(), it.id) }
    }
    private fun queueDelete(table: String, id: String) = runBlocking {
        store.enqueue(OutboxEntity(op = "delete", recordTable = table, recordId = id, payload = null, createdAt = 0L))
    }

    private fun decide(p: IncomingCallPayload = payload): CallDecision = CallCoordinatorLogic.decide(
        p, CallEnv(signedIn = true, assistantEnabled = true, withinHours = true, focusLive = false,
            anchorExists = AppCallEnvironment.anchorExists(graph, p), aiConsent = true),
    )

    @Test fun `a task made on another device and not synced here yet rings, never stale`() {
        // Nothing in the store: the web created it minutes ago.
        assertNull("unknown, not gone", AppCallEnvironment.anchorExists(graph, payload))
        assertEquals(CallDecision.Ring, decide())
        // A task-only call ("Call me about this" with no block) the same.
        assertEquals(CallDecision.Ring, decide(payload.copy(blockId = null)))
    }

    @Test fun `the task is here but its block has not synced yet - rings`() {
        seed(t = task())
        assertNull(AppCallEnvironment.anchorExists(graph, payload))
        assertEquals(CallDecision.Ring, decide())
    }

    @Test fun `a live task and block this phone holds ring`() {
        seed(t = task(), b = block())
        assertEquals(true, AppCallEnvironment.anchorExists(graph, payload))
        assertEquals(true, AppCallEnvironment.anchorExists(graph, payload.copy(blockId = null)))
        assertEquals(CallDecision.Ring, decide())
    }

    @Test fun `what this phone knows is over is still stale - done task, done or skipped block`() {
        seed(t = task(done = true), b = block())
        assertEquals(CallDecision.Stale, decide())
        seed(t = task(), b = block(done = true))
        assertEquals(CallDecision.Stale, decide())
        seed(t = task(), b = block(skipped = true))
        assertEquals(CallDecision.Stale, decide())
    }

    @Test fun `a task or block deleted here whose delete is still queued is stale`() {
        queueDelete(Tables.TASKS, "t-web")
        assertEquals(CallDecision.Stale, decide())
        // Once that delete has landed the server stops calling about it; a queued
        // block delete under a task that is still here retires the call the same way.
        runBlocking { store.dequeue(store.pending().single().seq) }
        seed(t = task())
        queueDelete(Tables.CAL_BLOCKS, "b-web")
        assertEquals(CallDecision.Stale, decide())
    }

    @Test fun `a call with no task anchor has nothing to check`() {
        assertNull(AppCallEnvironment.anchorExists(graph, payload.copy(taskId = null, blockId = null)))
    }
}
