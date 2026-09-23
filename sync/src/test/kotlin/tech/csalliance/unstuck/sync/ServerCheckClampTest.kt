package tech.csalliance.unstuck.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.OutboxEntity
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase

// The server's CHECKs (migration 001: tasks.estimate_min 1…1440,
// cal_blocks.duration_minutes 5…1440) held at the write choke point AND on the
// way out of the outbox, so no writer can strand a row the server refuses and an
// op queued before the clamp existed heals on the next drain (audit 2026-09-22,
// C4; iOS WriteThroughTests.testEstimatesAndDurationsAreClampedToTheServerChecksOnTheRowAndTheOp).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerCheckClampTest {

    private lateinit var db: UnstuckDatabase
    private lateinit var store: LocalStore
    private lateinit var write: WriteThrough

    private val now = "2026-05-21T10:00:00.000Z"
    private val tid = "11111111-1111-4111-8111-111111111111"
    private val bid = "22222222-2222-4222-8222-222222222222"

    /** Records every upsert the drain sends, as the server would receive it. */
    private class RecordingRemote : SyncRemote {
        val upserts = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun fetchAll(table: String): List<JsonObject> = emptyList()
        override suspend fun upsert(table: String, row: JsonObject, userId: String) { upserts.add(table to row) }
        override suspend fun delete(table: String, id: String) {}
        override suspend fun rpc(fn: String, params: JsonObject) {}
        override suspend fun fetchSince(table: String, column: String, since: String, limit: Int) = emptyList<JsonObject>()
        override suspend fun fetchIds(table: String, offset: Int, limit: Int) = emptyList<String>()
    }

    private fun task(id: String, estimateMin: Int) = TaskItem(id = id, name = "T", estimateMin = estimateMin, createdAt = now, updatedAt = now)
    private fun block(id: String, durationMinutes: Int, taskId: String? = tid, kind: CalBlockKind? = CalBlockKind.TASK) =
        CalBlock(id = id, taskId = taskId, taskName = "Meds", startTime = "08:00", durationMinutes = durationMinutes, date = "2026-05-21", kind = kind)

    private fun intField(payload: String?, key: String): Int = Json.parseToJsonElement(payload!!).jsonObject[key]!!.jsonPrimitive.int

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnstuckDatabase::class.java)
            .allowMainThreadQueries().build()
        store = LocalStore(db)
        write = WriteThrough(store)
    }

    @After fun teardown() = db.close()

    @Test fun estimatesAreClampedOnTheRowAndTheOp() = runTest {
        write.upsertTask(task(tid, 2000))
        assertEquals(1440, store.getOne(Tables.TASKS, tid, TaskItem.serializer())!!.estimateMin)
        assertEquals(1440, intField(store.pending().last().payload, "estimate_min"))
        write.upsertTask(task("t0", 0))
        assertEquals(1, store.getOne(Tables.TASKS, "t0", TaskItem.serializer())!!.estimateMin)
        write.upsertTask(task("t2", 2))
        assertEquals("a 1-4 minute estimate is legal", 2, store.getOne(Tables.TASKS, "t2", TaskItem.serializer())!!.estimateMin)
    }

    @Test fun durationsAreClampedOnTheRowAndTheOp() = runTest {
        write.upsertCalBlock(block(bid, 2))
        assertEquals(5, store.getOne(Tables.CAL_BLOCKS, bid, CalBlock.serializer())!!.durationMinutes)
        val op = store.pending().last()
        assertEquals(tid, op.dependsOn)
        assertEquals(5, intField(op.payload, "duration_minutes"))
        write.upsertCalBlock(block(bid, 5000))
        assertEquals(1440, store.getOne(Tables.CAL_BLOCKS, bid, CalBlock.serializer())!!.durationMinutes)
        write.upsertCalBlock(block(bid, 25))
        assertEquals(25, store.getOne(Tables.CAL_BLOCKS, bid, CalBlock.serializer())!!.durationMinutes)
    }

    @Test fun aGoogleMirrorKeepsItsRealLengthAndIsNeverEnqueued() = runTest {
        val before = store.pending().size
        write.upsertCalBlock(block("g_evt", 2, taskId = null, kind = CalBlockKind.EXTERNAL))
        assertEquals(2, store.getOne(Tables.CAL_BLOCKS, "g_evt", CalBlock.serializer())!!.durationMinutes)
        assertEquals(before, store.pending().size)
    }

    /** An op queued by a build without the clamp is sent as stored — it used to be
     *  refused on every launch for ever. The drain now sends what the server accepts. */
    @Test fun alreadyQueuedOutOfRangeOpsHealOnTheNextDrain() = runTest {
        val staleTask = DbRowCodec.encodeTask(task(tid, 5000)).toString()
        val staleBlock = DbRowCodec.encodeCalBlock(block(bid, 2)).toString()
        store.enqueue(OutboxEntity(op = "upsert", recordTable = Tables.TASKS, recordId = tid, payload = staleTask, createdAt = 1L))
        store.enqueue(OutboxEntity(op = "upsert", recordTable = Tables.CAL_BLOCKS, recordId = bid, payload = staleBlock, createdAt = 2L))
        val remote = RecordingRemote()
        OutboxFlusher(remote, store).flush("me")
        assertEquals(listOf(Tables.TASKS, Tables.CAL_BLOCKS), remote.upserts.map { it.first })
        assertEquals(1440, remote.upserts[0].second["estimate_min"]!!.jsonPrimitive.int)
        assertEquals(5, remote.upserts[1].second["duration_minutes"]!!.jsonPrimitive.int)
        assertEquals("both ops landed and left the queue", 0, store.pending().size)
    }

    @Test fun inRangeRowsAndOtherTablesPassThroughUntouched() {
        val row = JsonObject(mapOf("id" to JsonPrimitive("x"), "estimate_min" to JsonPrimitive(25)))
        assertSame(row, OutboxFlusher.clampServerChecks(Tables.TASKS, row))
        val session = JsonObject(mapOf("id" to JsonPrimitive("s"), "duration_minutes" to JsonPrimitive(2)))
        assertSame(session, OutboxFlusher.clampServerChecks(Tables.SESSIONS, session))
        val block = JsonObject(mapOf("id" to JsonPrimitive("b"), "duration_minutes" to JsonPrimitive(0)))
        assertEquals(5, OutboxFlusher.clampServerChecks(Tables.CAL_BLOCKS, block)["duration_minutes"]!!.jsonPrimitive.int)
    }
}
