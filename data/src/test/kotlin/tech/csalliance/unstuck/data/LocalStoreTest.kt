package tech.csalliance.unstuck.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.FocusTreatment
import tech.csalliance.unstuck.core.model.LiveSession
import tech.csalliance.unstuck.core.model.Objective
import tech.csalliance.unstuck.core.model.Priority
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.data.db.OutboxEntity
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase

// Room + LocalStore round-trips, exercised on the JVM via Robolectric:
// JSONB-shaped models survive store→load (incl. recurrence.daysOfWeek +
// objectives), replace-per-table preserves external g_ blocks, outbox FIFO,
// and the live-session single row.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalStoreTest {
    private lateinit var db: UnstuckDatabase
    private lateinit var store: LocalStore

    private fun task(id: String, name: String = "T") = TaskItem(
        id = id, name = name, estimateMin = 25,
        createdAt = "2026-05-21T10:00:00.000Z", updatedAt = "2026-05-21T10:00:00.000Z",
    )

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnstuckDatabase::class.java)
            .allowMainThreadQueries().build()
        store = LocalStore(db)
    }

    @After fun teardown() = db.close()

    @Test fun replaceAndObserveTasks() = runTest {
        store.replace(Tables.TASKS, listOf(task("a"), task("b")), TaskItem.serializer(), { it.id })
        assertEquals(listOf("a", "b"), store.tasks().first().map { it.id }.sorted())
    }

    @Test fun jsonbShapeSurvivesRoundTrip() = runTest {
        val t = task("a").copy(
            priority = Priority.URGENT,
            tags = listOf("deep-work"),
            objectives = listOf(Objective("ship it", done = true, minutes = 30)),
            recurrence = Recurrence.Weekly(listOf(1, 3, 5), until = "2026-08-01"),
        )
        store.upsert(Tables.TASKS, t, TaskItem.serializer(), t.id)
        val loaded = store.tasks().first().single()
        assertEquals(Priority.URGENT, loaded.priority)
        assertEquals(listOf("deep-work"), loaded.tags)
        assertEquals(Objective("ship it", true, 30), loaded.objectives?.single())
        assertEquals(Recurrence.Weekly(listOf(1, 3, 5), "2026-08-01"), loaded.recurrence)
    }

    @Test fun replacePreservesExternalGBlocks() = runTest {
        val external = CalBlock(id = "g_evt1", taskId = null, taskName = "Standup", startTime = "09:00", durationMinutes = 30, date = "2026-05-21", externalEventId = "evt1", kind = CalBlockKind.EXTERNAL)
        store.upsert(Tables.CAL_BLOCKS, external, CalBlock.serializer(), external.id)
        // Server hydrate replaces task blocks but must keep the local g_ block.
        val taskBlock = CalBlock(id = "blk1", taskId = "a", taskName = "T", startTime = "10:00", durationMinutes = 25, date = "2026-05-21", kind = CalBlockKind.TASK)
        store.replace(Tables.CAL_BLOCKS, listOf(taskBlock), CalBlock.serializer(), { it.id }, preservePrefix = "g_")
        val ids = store.blocks().first().map { it.id }.sorted()
        assertEquals(listOf("blk1", "g_evt1"), ids)
    }

    @Test fun outboxFifo() = runTest {
        store.enqueue(OutboxEntity(op = "upsert", recordTable = Tables.TASKS, recordId = "a", payload = "{}", createdAt = 1))
        store.enqueue(OutboxEntity(op = "delete", recordTable = Tables.TASKS, recordId = "b", payload = null, createdAt = 2))
        val pending = store.pending()
        assertEquals(listOf("a", "b"), pending.map { it.recordId })
        store.dequeue(pending.first().seq)
        assertEquals(listOf("b"), store.pending().map { it.recordId })
    }

    @Test fun liveSessionSingleRow() = runTest {
        assertNull(store.getLiveSession())
        val live = LiveSession(id = "s1", taskId = "a", sessionStart = 1000L, sessionEstimateMin = 25, treatment = FocusTreatment.AMBIENT)
        store.setLiveSession(live)
        assertEquals("s1", store.getLiveSession()?.id)
        store.setLiveSession(null)
        assertNull(store.getLiveSession())
    }

    @Test fun clearAllWipesEverything() = runTest {
        store.upsert(Tables.TASKS, task("a"), TaskItem.serializer(), "a")
        store.enqueue(OutboxEntity(op = "upsert", recordTable = Tables.TASKS, recordId = "a", payload = "{}", createdAt = 1))
        store.clearAll()
        assertTrue(store.tasks().first().isEmpty())
        assertTrue(store.pending().isEmpty())
    }
}
