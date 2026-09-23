package tech.csalliance.unstuck.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.OutboxEntity
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase

// A block's date and start time reach the row and the server in ASCII digits,
// and an op an older build queued in the phone's own digits (refused by the
// server: cal_blocks.date is a `date`, start_time has a CHECK, so it was
// quarantined for ever) heals on the next drain (Android audit 2026-09-23, A12).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AsciiBlockDateTimeTest {

    private lateinit var db: UnstuckDatabase
    private lateinit var store: LocalStore
    private lateinit var write: WriteThrough

    private val bid = "22222222-2222-4222-8222-222222222222"

    private class RecordingRemote : SyncRemote {
        val upserts = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun fetchAll(table: String): List<JsonObject> = emptyList()
        override suspend fun upsert(table: String, row: JsonObject, userId: String) { upserts.add(table to row) }
        override suspend fun delete(table: String, id: String) {}
        override suspend fun rpc(fn: String, params: JsonObject) {}
        override suspend fun fetchSince(table: String, column: String, since: String, limit: Int) = emptyList<JsonObject>()
        override suspend fun fetchIds(table: String, offset: Int, limit: Int) = emptyList<String>()
    }

    // taskId null: no dependsOn, so the drain doesn't wait on a parent task row.
    private fun block(date: String, startTime: String, name: String = "Meds") =
        CalBlock(id = bid, taskId = null, taskName = name, startTime = startTime, durationMinutes = 25, date = date, kind = CalBlockKind.TASK)

    private fun field(payload: String?, key: String): String = Json.parseToJsonElement(payload!!).jsonObject[key]!!.jsonPrimitive.content

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnstuckDatabase::class.java)
            .allowMainThreadQueries().build()
        store = LocalStore(db)
        write = WriteThrough(store)
    }

    @After fun teardown() = db.close()

    @Test fun aNativeDigitBlockIsWrittenAsciiOnTheRowAndTheOp() = runTest {
        write.upsertCalBlock(block("٢٠٢٦-٠٩-٢٣", "١٠:٣٠"))
        val row = store.getOne(Tables.CAL_BLOCKS, bid, CalBlock.serializer())!!
        assertEquals("2026-09-23", row.date)
        assertEquals("10:30", row.startTime)
        val op = store.pending().last()
        assertEquals("2026-09-23", field(op.payload, "date"))
        assertEquals("10:30", field(op.payload, "start_time"))
    }

    @Test fun anAlreadyQueuedNativeDigitOpHealsOnTheNextDrain() = runTest {
        val stale = DbRowCodec.encodeCalBlock(block("۲۰۲۶-۰۹-۲۳", "۰۹:۱۵", name = "دارو ۲")).toString()
        store.enqueue(OutboxEntity(op = "upsert", recordTable = Tables.CAL_BLOCKS, recordId = bid, payload = stale, createdAt = 1L))
        val remote = RecordingRemote()
        OutboxFlusher(remote, store).flush("me")
        assertEquals(1, remote.upserts.size)
        val sent = remote.upserts.single().second
        assertEquals("2026-09-23", sent["date"]!!.jsonPrimitive.content)
        assertEquals("09:15", sent["start_time"]!!.jsonPrimitive.content)
        assertEquals("the user's own text keeps its digits", "دارو ۲", sent["task_name"]!!.jsonPrimitive.content)
        assertEquals("the op landed and left the queue", 0, store.pending().size)
    }

    @Test fun aRowAnOlderBuildStoredInNativeDigitsHealsLocallyWithoutANewOp() = runTest {
        // What vc100 left on an Arabic phone: the row and its refused, still-queued
        // op both in native digits. Offline (or behind a stuck parent op) no pull
        // replaces the row, so only a local heal brings it back to Today.
        val old = block("٢٠٢٦-٠٩-٢٣", "١٠:٣٠", name = "دواء ٢")
        store.upsert(Tables.CAL_BLOCKS, old, CalBlock.serializer(), bid)
        val stale = DbRowCodec.encodeCalBlock(old).toString()
        store.enqueue(OutboxEntity(op = "upsert", recordTable = Tables.CAL_BLOCKS, recordId = bid, payload = stale, createdAt = 1L))
        val mirror = CalBlock(id = "g_ev1", taskId = null, taskName = "Dentist", startTime = "۰۹:۰۰", durationMinutes = 30, date = "۲۰۲۶-۰۹-۲۴", kind = CalBlockKind.EXTERNAL)
        store.upsert(Tables.CAL_BLOCKS, mirror, CalBlock.serializer(), mirror.id)

        assertEquals(2, write.healNativeDigitBlocks())
        val row = store.getOne(Tables.CAL_BLOCKS, bid, CalBlock.serializer())!!
        assertEquals("2026-09-23", row.date)
        assertEquals("10:30", row.startTime)
        assertEquals("the user's own text keeps its digits", "دواء ٢", row.taskName)
        val g = store.getOne(Tables.CAL_BLOCKS, mirror.id, CalBlock.serializer())!!
        assertEquals("2026-09-24" to "09:00", g.date to g.startTime)
        // The queued op is left for the drain to heal; nothing new is sent.
        assertEquals(listOf(stale), store.pending().map { it.payload })
        assertEquals("nothing left to heal", 0, write.healNativeDigitBlocks())
    }

    @Test fun asciiRowsAndOtherTablesPassThroughUntouched() {
        val ascii = DbRowCodec.encodeCalBlock(block("2026-09-23", "10:30"))
        assertSame(ascii, OutboxFlusher.asciiBlockDateTime(Tables.CAL_BLOCKS, ascii))
        val task = JsonObject(mapOf("id" to JsonPrimitive("t"), "name" to JsonPrimitive("۲ کار"), "date" to JsonPrimitive("۲")))
        assertSame(task, OutboxFlusher.asciiBlockDateTime(Tables.TASKS, task))
    }
}
