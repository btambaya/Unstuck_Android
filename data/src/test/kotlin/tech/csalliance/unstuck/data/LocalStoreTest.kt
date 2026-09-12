package tech.csalliance.unstuck.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
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

    // distinctUntilChanged + flowOn on the observe() chain must preserve value
    // correctness: an identical re-write doesn't change state, and a genuine change
    // is still delivered. Guards the perf optimisation from silently dropping changes.
    // (Run on the real default dispatcher because the chain uses flowOn(Default).)
    @Test fun observeStaysCorrectThroughDistinctChain() = runBlocking {
        store.upsert(Tables.TASKS, task("a", "First"), TaskItem.serializer(), "a")
        assertEquals("First", store.tasks().first().single().name)
        // Identical write (same bytes) — distinctUntilChanged swallows it; value unchanged.
        store.upsert(Tables.TASKS, task("a", "First"), TaskItem.serializer(), "a")
        assertEquals("First", store.tasks().first().single().name)
        // A real change still propagates through the chain.
        store.upsert(Tables.TASKS, task("a", "Renamed"), TaskItem.serializer(), "a")
        val renamed = withTimeoutOrNull(5_000) {
            store.tasks().first { it.singleOrNull()?.name == "Renamed" }
        }
        requireNotNull(renamed) { "renamed change was not delivered through the chain" }
        assertEquals("Renamed", renamed.single().name)
    }

    @Test fun clearAllWipesEverything() = runTest {
        store.upsert(Tables.TASKS, task("a"), TaskItem.serializer(), "a")
        store.enqueue(OutboxEntity(op = "upsert", recordTable = Tables.TASKS, recordId = "a", payload = "{}", createdAt = 1))
        store.clearAll()
        assertTrue(store.tasks().first().isEmpty())
        assertTrue(store.pending().isEmpty())
    }

    // --- schema v2: pending-aware replace + the per-user parked outbox ---

    @Test fun replaceKeepingPending_keepsLocalEditOverServerCopyOfSameId() = runTest {
        // Local "Renamed" with a queued upsert; the server still returns "Server".
        store.upsert(Tables.TASKS, task("a", "Renamed"), TaskItem.serializer(), "a")
        store.enqueue(OutboxEntity(op = "upsert", recordTable = Tables.TASKS, recordId = "a", payload = "{}", createdAt = 1))
        store.replace(Tables.TASKS, listOf(task("a", "Server"), task("b", "New")), TaskItem.serializer(), { it.id }, keepPendingUpserts = true)
        val byId = store.tasks().first().associateBy { it.id }
        assertEquals("Renamed", byId["a"]?.name)   // the pending local edit survives the pull
        assertEquals("New", byId["b"]?.name)       // everything else is server-canonical
    }

    @Test fun replaceKeepingPending_withoutPendingOpsIsPlainReplace() = runTest {
        store.upsert(Tables.TASKS, task("a", "Stale"), TaskItem.serializer(), "a")
        store.replace(Tables.TASKS, listOf(task("a", "Server")), TaskItem.serializer(), { it.id }, keepPendingUpserts = true)
        assertEquals("Server", store.tasks().first().single().name)
    }

    @Test fun replaceKeepingPending_stillPreservesExternalPrefixRows() = runTest {
        val external = CalBlock(id = "g_evt1", taskId = null, taskName = "Standup", startTime = "09:00", durationMinutes = 30, date = "2026-05-21", externalEventId = "evt1", kind = CalBlockKind.EXTERNAL)
        store.upsert(Tables.CAL_BLOCKS, external, CalBlock.serializer(), external.id)
        val local = CalBlock(id = "blk1", taskId = "a", taskName = "Mine", startTime = "10:00", durationMinutes = 25, date = "2026-05-21", kind = CalBlockKind.TASK)
        store.upsert(Tables.CAL_BLOCKS, local, CalBlock.serializer(), local.id)
        store.enqueue(OutboxEntity(op = "upsert", recordTable = Tables.CAL_BLOCKS, recordId = "blk1", payload = "{}", createdAt = 1))
        val server = local.copy(taskName = "ServerName")
        store.replace(Tables.CAL_BLOCKS, listOf(server), CalBlock.serializer(), { it.id }, preservePrefix = "g_", keepPendingUpserts = true)
        val byId = store.blocks().first().associateBy { it.id }
        assertEquals(setOf("blk1", "g_evt1"), byId.keys)
        assertEquals("Mine", byId["blk1"]?.taskName)
    }

    @Test fun parkedOutbox_survivesClearAllAndRestoresForSameUserOnly() = runTest {
        store.enqueue(OutboxEntity(op = "upsert", recordTable = Tables.TASKS, recordId = "a", payload = "{\"id\":\"a\"}", createdAt = 1, base = "{\"id\":\"a\",\"name\":\"old\"}"))
        store.enqueue(OutboxEntity(op = "delete", recordTable = Tables.TAGS, recordId = "t", payload = null, createdAt = 2))
        assertEquals(2, store.parkOutbox("u1"))
        assertTrue("outbox is empty once parked", store.pending().isEmpty())
        store.clearAll()                                   // the sign-out wipe
        assertEquals("parked ops survive the wipe", 2, store.parkedCount("u1"))
        assertEquals("another user gets nothing back", 0, store.restoreParkedOutbox("u2"))
        assertEquals(2, store.parkedCount("u1"))
        assertEquals(2, store.restoreParkedOutbox("u1"))
        val back = store.pending()
        assertEquals(listOf("a", "t"), back.map { it.recordId })          // original order
        assertEquals("{\"id\":\"a\",\"name\":\"old\"}", back[0].base)  // merge base carried
        assertEquals("delete", back[1].op)
        assertEquals(0, store.parkedCount("u1"))
    }

    @Test fun parkOutbox_withNothingQueuedParksNothing() = runTest {
        assertEquals(0, store.parkOutbox("u1"))
        assertEquals(0, store.parkedCount("u1"))
    }

    // --- per-logical-table invalidation (perf: one `records` table, ~10 observers) ---

    // A write to ANOTHER logical table must not wake this table's collector at
    // all (it used to re-run the SELECT and rely on distinctUntilChanged to hide
    // it), and the value it already holds must stay correct.
    @Test fun writeToAnotherTableDoesNotReEmit() = runBlocking {
        store.upsert(Tables.TASKS, task("a", "First"), TaskItem.serializer(), "a")
        val seen = mutableListOf<List<String>>()
        val job = launch(Dispatchers.Default) { store.tasks().collect { seen.add(it.map { t -> t.name }) } }
        withTimeoutOrNull(5_000) { while (seen.isEmpty()) delay(5) }
        assertEquals(listOf(listOf("First")), seen.toList())
        // 20 unrelated cal_block writes — none of them concerns tasks().
        repeat(20) { i ->
            val b = CalBlock(id = "b$i", taskId = "a", taskName = "B$i", startTime = "10:00", durationMinutes = 25, date = "2026-05-21", kind = CalBlockKind.TASK)
            store.upsert(Tables.CAL_BLOCKS, b, CalBlock.serializer(), b.id)
        }
        delay(200)
        assertEquals("an unrelated table's writes must not re-emit tasks", listOf(listOf("First")), seen.toList())
        // ...and a genuine tasks write still lands on the same live collector.
        store.upsert(Tables.TASKS, task("a", "Renamed"), TaskItem.serializer(), "a")
        withTimeoutOrNull(5_000) { while (seen.size < 2) delay(5) }
        assertEquals(listOf(listOf("First"), listOf("Renamed")), seen.toList())
        // The unrelated writes did land — they are just delivered on blocks().
        assertEquals(20, store.blocks().first().size)
        job.cancel()
    }

    // delete() / replace() / clearAll() must each reach a LIVE collector (the
    // version bump has to fire from every write path, not just upsert).
    @Test fun everyWritePathReachesALiveCollector() = runBlocking {
        store.upsert(Tables.TASKS, task("a", "First"), TaskItem.serializer(), "a")
        val seen = mutableListOf<List<String>>()
        val job = launch(Dispatchers.Default) { store.tasks().collect { seen.add(it.map { t -> t.id }) } }
        suspend fun await(n: Int) = withTimeoutOrNull(5_000) { while (seen.size < n) delay(5) }
        await(1)
        store.replace(Tables.TASKS, listOf(task("a"), task("b")), TaskItem.serializer(), { it.id })
        await(2)
        assertEquals(listOf("a", "b"), seen.last().sorted())
        store.delete(Tables.TASKS, "b")
        await(3)
        assertEquals(listOf("a"), seen.last())
        store.upsertIfNewer(Tables.CAPTURES, task("c"), TaskItem.serializer(), "c", null)  // other table: silent
        delay(100)
        assertEquals(3, seen.size)
        store.clearAll()
        await(4)
        assertEquals(emptyList<String>(), seen.last())
        job.cancel()
    }

    @Test fun latestPendingUpsert_andRewrite() = runTest {
        assertNull(store.latestPendingUpsert(Tables.TASKS, "a"))
        store.enqueue(OutboxEntity(op = "upsert", recordTable = Tables.TASKS, recordId = "a", payload = "v1", createdAt = 1, base = "b0"))
        store.enqueue(OutboxEntity(op = "upsert", recordTable = Tables.TASKS, recordId = "a", payload = "v2", createdAt = 2, base = "b0"))
        val latest = store.latestPendingUpsert(Tables.TASKS, "a")
        assertEquals("v2", latest?.payload)
        store.rewriteOutbox(latest!!.seq, "merged", "b1")
        val after = store.pending().last()
        assertEquals("merged", after.payload)
        assertEquals("b1", after.base)
    }
}
