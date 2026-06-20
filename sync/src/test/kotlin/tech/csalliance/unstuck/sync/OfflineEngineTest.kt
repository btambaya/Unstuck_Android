package tech.csalliance.unstuck.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.OutboxEntity
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase

// End-to-end offline-engine tests against a real (in-memory) LocalStore and a
// fake SyncRemote. Cover the SYNC DATA-LOSS fixes:
//  - bug #1: a stale incoming realtime UPDATE must not clobber a newer local row
//  - bug #3: a max-tries (poison) op is QUARANTINED, not dropped, so the next
//            hydrate still preserves the user's local row
//  - bug #4: pruneStaleTaskOps compares timestamps as instants, not strings
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineEngineTest {

    private lateinit var db: UnstuckDatabase
    private lateinit var store: LocalStore

    /** Configurable fake remote: fetchAll returns the seeded server rows for a
     *  table; upsert/delete are recorded and can be made to fail (poison path). */
    private class FakeRemote : SyncRemote {
        var serverRows: MutableMap<String, List<JsonObject>> = mutableMapOf()
        var failUpsert = false
        val upserts = mutableListOf<Pair<String, String>>()   // (table, id-via-payload)
        val deletes = mutableListOf<Pair<String, String>>()
        override suspend fun fetchAll(table: String): List<JsonObject> = serverRows[table].orEmpty()
        override suspend fun upsert(table: String, row: JsonObject, userId: String) {
            if (failUpsert) throw RuntimeException("simulated server reject")
            upserts.add(table to row.toString())
        }
        override suspend fun delete(table: String, id: String) { deletes.add(table to id) }
    }

    private fun task(id: String, updatedAt: String, name: String = "T") = TaskItem(
        id = id, name = name, estimateMin = 25,
        createdAt = "2026-05-21T10:00:00.000Z", updatedAt = updatedAt,
    )

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnstuckDatabase::class.java)
            .allowMainThreadQueries().build()
        store = LocalStore(db)
    }

    @After fun teardown() = db.close()

    // --- bug #1: realtime stale-UPDATE guard via LocalStore.upsertIfNewer ---

    @Test fun upsertIfNewer_skipsStaleIncomingUpdate() = runTest {
        // Local row freshly edited at 12:00; a delayed remote echo carries 11:00.
        val local = task("t1", updatedAt = "2026-05-21T12:00:00.000Z", name = "LocalNew")
        store.upsert(Tables.TASKS, local, TaskItem.serializer(), local.id, local.updatedAt)
        val stale = task("t1", updatedAt = "2026-05-21T11:00:00.000Z", name = "RemoteStale")
        val applied = store.upsertIfNewer(Tables.TASKS, stale, TaskItem.serializer(), stale.id, stale.updatedAt)
        assertFalse("stale incoming update must be skipped", applied)
        assertEquals("LocalNew", store.tasks().first().single().name)
    }

    @Test fun upsertIfNewer_appliesNewerIncomingUpdate() = runTest {
        val local = task("t1", updatedAt = "2026-05-21T11:00:00.000Z", name = "LocalOld")
        store.upsert(Tables.TASKS, local, TaskItem.serializer(), local.id, local.updatedAt)
        val newer = task("t1", updatedAt = "2026-05-21T12:00:00.000Z", name = "RemoteNew")
        val applied = store.upsertIfNewer(Tables.TASKS, newer, TaskItem.serializer(), newer.id, newer.updatedAt)
        assertTrue(applied)
        assertEquals("RemoteNew", store.tasks().first().single().name)
    }

    @Test fun upsertIfNewer_appliesWhenNoLocalRowOrNoTimestamp() = runTest {
        // No local row → can't be stale → apply.
        val incoming = task("t1", updatedAt = "2026-05-21T11:00:00.000Z", name = "Fresh")
        assertTrue(store.upsertIfNewer(Tables.TASKS, incoming, TaskItem.serializer(), incoming.id, incoming.updatedAt))
        // Null incoming timestamp → can't prove staleness → apply (prior behaviour).
        val noTs = task("t2", updatedAt = "2026-05-21T10:00:00.000Z", name = "NoTs")
        assertTrue(store.upsertIfNewer(Tables.TASKS, noTs, TaskItem.serializer(), noTs.id, null))
    }

    @Test fun upsertIfNewer_usesInstantNotStringCompare() = runTest {
        // Local "…T12:00:00.500Z" is genuinely LATER than incoming "…T12:00:00Z",
        // yet sorts EARLIER as a string: at the shared "…00" both diverge on the
        // next char — '.' (0x2E) for the local vs 'Z' (0x5A) for the incoming —
        // so a STRING compare reads the local as the smaller/older value and would
        // wrongly apply the stale incoming. An instant compare correctly skips it.
        val local = task("t1", updatedAt = "2026-05-21T12:00:00.500Z", name = "LocalNew")
        store.upsert(Tables.TASKS, local, TaskItem.serializer(), local.id, local.updatedAt)
        val stale = task("t1", updatedAt = "2026-05-21T12:00:00Z", name = "RemoteStale")
        // sanity: the strings really do disagree with chronological order
        assertTrue(local.updatedAt < stale.updatedAt)   // string order: local "older"
        assertFalse(store.upsertIfNewer(Tables.TASKS, stale, TaskItem.serializer(), stale.id, stale.updatedAt))
        assertEquals("LocalNew", store.tasks().first().single().name)
    }

    // --- bug #3: poison op is quarantined, not dropped (local row survives hydrate) ---

    @Test fun poisonOp_isQuarantinedAndLocalRowSurvivesHydrate() = runTest {
        val remote = FakeRemote().apply { failUpsert = true }   // every upsert rejects
        val flusher = OutboxFlusher(remote, store)
        val hydrator = Hydrator(remote, store)

        // Optimistic local write + queued outbox upsert (the only copy of this row).
        val t = task("t1", updatedAt = "2026-05-21T10:00:00.000Z")
        store.upsert(Tables.TASKS, t, TaskItem.serializer(), t.id, t.updatedAt)
        store.enqueue(OutboxEntity(op = "upsert", recordTable = Tables.TASKS, recordId = "t1", payload = DbRowCodec.encodeTask(t).toString(), createdAt = 1L))

        // Flush enough times to exceed FAIL_CAP (5). Each flush adds 1 failure.
        repeat(7) { flusher.flush("u1") }

        // Op must NOT have been dropped — it's still in the outbox (quarantined),
        // so the server still never got it but the local row is preserved.
        assertEquals("op stays queued (quarantined, not dropped)", 1, store.pending().size)

        // Server has no rows; the hydrate replace would normally wipe t1 — but the
        // still-pending outbox op makes pendingLocalRows preserve it. THE FIX.
        remote.serverRows[Tables.TASKS] = emptyList()
        hydrator.hydrate("u1")
        assertEquals("local row must survive hydrate", listOf("t1"), store.tasks().first().map { it.id })
    }

    // --- forward-compat: one un-decodable server row must not abort the whole table ---

    @Test fun hydrate_perRowTolerant_oneBadRowKeepsTheRest() = runTest {
        val remote = FakeRemote()
        val hydrator = Hydrator(remote, store)
        // A valid task row + a garbage row missing required fields (un-decodable).
        // The eager `.map(decode)` would have thrown on the bad row and wiped the
        // whole table; mapNotNull { runCatching {...} } drops only the bad one.
        val good = serverRow(task("good", updatedAt = "2026-05-21T10:00:00.000Z"))
        val bad = Json.parseToJsonElement("""{"id":"bad","whoops":true}""") as JsonObject
        remote.serverRows[Tables.TASKS] = listOf(good, bad)
        hydrator.hydrate("u1")
        assertEquals("good row survives, bad row dropped", listOf("good"), store.tasks().first().map { it.id })
    }

    // --- bug #4: pruneStaleTaskOps instant-based compare ---

    @Test fun pruneStaleTaskOps_dropsOpWhenServerStrictlyNewer_instantCompare() = runTest {
        val remote = FakeRemote()
        val hydrator = Hydrator(remote, store)

        // Queued local op is OLDER (10:00) than the server row (12:00) → prune it.
        val localOp = task("t1", updatedAt = "2026-05-21T10:00:00.000Z")
        store.enqueue(OutboxEntity(op = "upsert", recordTable = Tables.TASKS, recordId = "t1", payload = DbRowCodec.encodeTask(localOp).toString(), createdAt = 1L))
        val server = task("t1", updatedAt = "2026-05-21T12:00:00.000Z")
        remote.serverRows[Tables.TASKS] = listOf(serverRow(server))

        hydrator.pruneStaleTaskOps()
        assertTrue("stale op pruned", store.pending().isEmpty())
    }

    @Test fun pruneStaleTaskOps_keepsGenuineOfflineEdit() = runTest {
        val remote = FakeRemote()
        val hydrator = Hydrator(remote, store)
        // Local op is NEWER than server → a genuine offline edit, must survive.
        val localOp = task("t1", updatedAt = "2026-05-21T12:00:00.000Z")
        store.enqueue(OutboxEntity(op = "upsert", recordTable = Tables.TASKS, recordId = "t1", payload = DbRowCodec.encodeTask(localOp).toString(), createdAt = 1L))
        val server = task("t1", updatedAt = "2026-05-21T10:00:00.000Z")
        remote.serverRows[Tables.TASKS] = listOf(serverRow(server))
        hydrator.pruneStaleTaskOps()
        assertEquals(listOf("t1"), store.pending().map { it.recordId })
    }

    @Test fun pruneStaleTaskOps_instantCompareNotStringCompare() = runTest {
        val remote = FakeRemote()
        val hydrator = Hydrator(remote, store)
        // Local op "…T12:00:00.500Z" is genuinely NEWER than the server's
        // "…T12:00:00Z", yet string-compares as EARLIER ('.' < 'Z' after "…00").
        // The OLD `serverTime > localTime` String compare reads server as newer
        // and would wrongly PRUNE this valid offline edit. Instant compare keeps it.
        val localOp = task("t1", updatedAt = "2026-05-21T12:00:00.500Z")
        val server = task("t1", updatedAt = "2026-05-21T12:00:00Z")
        // String order: localOp ('.' …) sorts BEFORE server ('Z'), so the old
        // `serverTime > localTime` string compare is true → it would wrongly prune.
        assertTrue(localOp.updatedAt < server.updatedAt)
        store.enqueue(OutboxEntity(op = "upsert", recordTable = Tables.TASKS, recordId = "t1", payload = DbRowCodec.encodeTask(localOp).toString(), createdAt = 1L))
        remote.serverRows[Tables.TASKS] = listOf(serverRow(server))
        hydrator.pruneStaleTaskOps()
        assertEquals("valid newer offline edit must survive", listOf("t1"), store.pending().map { it.recordId })
    }

    // Build a server-shaped row JsonObject (DbRowCodec encodes the row; decodeTask
    // reads updated_at back out — which is what pruneStaleTaskOps relies on).
    private fun serverRow(t: TaskItem): JsonObject =
        Json.parseToJsonElement(DbRowCodec.encodeTask(t).toString()) as JsonObject
}
