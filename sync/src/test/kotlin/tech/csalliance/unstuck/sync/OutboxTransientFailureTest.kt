package tech.csalliance.unstuck.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.OutboxEntity
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase

// Android audit 2026-09-23, A10: a phone left open with no signal ran every
// queued op into FAIL_CAP on network errors alone, dead-lettered it (and the
// blocks waiting on it) for the rest of the process, and the edits never reached
// the server when signal came back. Only a definite server refusal may count
// toward the cap (parity with iOS SyncDecision.classifyFlushFailure).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxTransientFailureTest {

    private lateinit var db: UnstuckDatabase
    private lateinit var store: LocalStore

    /** Every send fails with [failWith] until it is cleared. */
    private class FlakyRemote : SyncRemote {
        var failWith: (() -> Throwable)? = null
        var attempts = 0
        val sent = mutableListOf<Pair<String, String>>()   // (table, id)
        override suspend fun fetchAll(table: String) = emptyList<JsonObject>()
        override suspend fun upsert(table: String, row: JsonObject, userId: String) {
            attempts++
            failWith?.let { throw it() }
            sent += table to (row["id"] as JsonPrimitive).content
        }
        override suspend fun delete(table: String, id: String) {
            attempts++
            failWith?.let { throw it() }
            sent += table to id
        }
        override suspend fun rpc(fn: String, params: JsonObject) = Unit
        override suspend fun fetchSince(table: String, column: String, since: String, limit: Int) = emptyList<JsonObject>()
        override suspend fun fetchIds(table: String, offset: Int, limit: Int) = emptyList<String>()
    }

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnstuckDatabase::class.java)
            .allowMainThreadQueries().build()
        store = LocalStore(db)
    }

    @After fun teardown() = db.close()

    private suspend fun queueTask(id: String) {
        val t = TaskItem(id = id, name = "Tick me", estimateMin = 25, createdAt = "2026-09-23T07:00:00.000Z", updatedAt = "2026-09-23T07:00:00.000Z")
        store.upsert(Tables.TASKS, t, TaskItem.serializer(), t.id, t.updatedAt)
        store.enqueue(OutboxEntity(op = "upsert", recordTable = Tables.TASKS, recordId = id, payload = DbRowCodec.encodeTask(t).toString(), createdAt = 1L))
    }

    private suspend fun queueBlock(id: String, taskId: String) {
        val b = CalBlock(id = id, taskId = taskId, taskName = "Tick me", startTime = "09:00", durationMinutes = 30, date = "2026-09-23")
        store.upsert(Tables.CAL_BLOCKS, b, CalBlock.serializer(), b.id)
        store.enqueue(OutboxEntity(op = "upsert", recordTable = Tables.CAL_BLOCKS, recordId = id, payload = DbRowCodec.encodeCalBlock(b).toString(), dependsOn = taskId, createdAt = 2L))
    }

    private fun offline(): Throwable = HttpRequestException("Unable to resolve host \"x.supabase.co\"", HttpRequestBuilder())

    @Test fun aTrainWithNoSignal_neverQuarantines_andTheEditsLandWhenSignalReturns() = runTest {
        val remote = FlakyRemote().apply { failWith = { offline() } }
        val flusher = OutboxFlusher(remote, store)
        queueTask("t1")
        queueBlock("b1", "t1")

        // Minutes of no signal: every edit's drain and every 60 s floor pull fail.
        repeat(8) { flusher.flush("u1") }
        assertEquals("both writes are still queued", 2, store.pending().size)

        // Signal returns: the NETWORK trigger drains, and both writes land.
        remote.failWith = null
        flusher.flush("u1")
        assertEquals(listOf(Tables.TASKS to "t1", Tables.CAL_BLOCKS to "b1"), remote.sent)
        assertTrue("the outbox is empty", store.pending().isEmpty())
    }

    @Test fun timeouts_5xx_authRefresh_andRateLimits_areTransientToo() = runTest {
        val kinds: List<() -> Throwable> = listOf(
            { HttpRequestTimeoutException("https://x.supabase.co/rest/v1/tasks", 10_000L, null) },
            { java.net.SocketTimeoutException("timeout") },
            { RestException("upstream error", null, 503, "503 Service Unavailable") },
            { RestException("JWT expired", null, 401, "401 Unauthorized") },
            { RestException("too many requests", null, 429, "429") },
            { RestException("timeout", null, 408, "408") },
        )
        for ((i, kind) in kinds.withIndex()) {
            val remote = FlakyRemote().apply { failWith = kind }
            val flusher = OutboxFlusher(remote, store)
            queueTask("t$i")
            repeat(7) { flusher.flush("u1") }
            remote.failWith = null
            flusher.flush("u1")
            assertEquals("${kind()} must not quarantine the write", listOf(Tables.TASKS to "t$i"), remote.sent)
        }
    }

    @Test fun aServerRefusal_isQuarantinedAtTheCap_andRetriedWhenTheNetworkReturns() = runTest {
        val remote = FlakyRemote().apply { failWith = { RestException("violates check constraint", null, 400, "400 Bad Request") } }
        val flusher = OutboxFlusher(remote, store)
        queueTask("t1")

        repeat(5) { flusher.flush("u1") }
        assertEquals("five refusals reach the cap", 5, remote.attempts)
        flusher.flush("u1")
        assertEquals("quarantined: not re-sent on every drain", 5, remote.attempts)
        assertEquals("kept, never dropped", 1, store.pending().size)

        // Connectivity comes back (a captive portal's 4xx, a parent row that has
        // landed since): the quarantine is released, so the op gets another round.
        remote.failWith = null
        flusher.releaseQuarantine()
        flusher.flush("u1")
        assertEquals(listOf(Tables.TASKS to "t1"), remote.sent)
        assertTrue(store.pending().isEmpty())
    }

    @Test fun classification_onlyADefiniteRefusalCounts() {
        val transient = listOf(
            offline(),
            HttpRequestTimeoutException("https://x", 10_000L, null),
            java.io.IOException("connection reset"),
            RestException("e", null, 401, "m"),
            RestException("e", null, 403, "m"),
            RestException("e", null, 408, "m"),
            RestException("e", null, 425, "m"),
            RestException("e", null, 429, "m"),
            RestException("e", null, 500, "m"),
            RestException("e", null, 503, "m"),
            RuntimeException("something unrecognised"),
        )
        for (e in transient) assertEquals("$e", OutboxFlusher.FlushFailure.TRANSIENT, OutboxFlusher.classifyFailure(e))
        val refused = listOf(
            RestException("bad request", null, 400, "m"),
            RestException("not found", null, 404, "m"),
            RestException("conflict", null, 409, "m"),
            RestException("unprocessable", null, 422, "m"),
            SerializationException("a payload that can never parse"),
        )
        for (e in refused) assertEquals("$e", OutboxFlusher.FlushFailure.REJECTED, OutboxFlusher.classifyFailure(e))
    }
}
