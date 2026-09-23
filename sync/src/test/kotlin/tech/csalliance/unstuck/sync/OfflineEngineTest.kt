package tech.csalliance.unstuck.sync

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.model.CollectionItem
import tech.csalliance.unstuck.core.model.ItemCollection
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
        /** Runs while a table's read is "in flight" — lets a test land a write
         *  between a read and what the engine does with it. */
        var onFetchAll: (suspend (String) -> Unit)? = null
        /** Tables whose read fails (a flaky link between two back-to-back GETs). */
        val failFetchAll = mutableSetOf<String>()
        override suspend fun fetchAll(table: String): List<JsonObject> {
            onFetchAll?.invoke(table)
            if (table in failFetchAll) throw RuntimeException("simulated timeout")
            return serverRows[table].orEmpty()
        }
        override suspend fun upsert(table: String, row: JsonObject, userId: String) {
            if (failUpsert) throw RuntimeException("simulated server reject")
            upserts.add(table to row.toString())
        }
        override suspend fun delete(table: String, id: String) { deletes.add(table to id) }
        // rpc: record calls; `rejectRpc` throws RpcRejected (terminal), `failRpc` a
        // transient RuntimeException (retried).
        var rejectRpc: Int? = null
        var failRpc = false
        val rpcs = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun rpc(fn: String, params: JsonObject) {
            rejectRpc?.let { throw RpcRejected(it, "refused") }
            if (failRpc) throw RuntimeException("simulated 5xx")
            rpcs.add(fn to params)
        }
        override suspend fun fetchSince(table: String, column: String, since: String, limit: Int) =
            FakeRemoteSupport.since(serverRows[table].orEmpty(), column, since, limit)
        override suspend fun fetchIds(table: String, offset: Int, limit: Int) =
            FakeRemoteSupport.ids(serverRows[table].orEmpty(), offset, limit)
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

    // A capture taken during focus waits (dependsOn) for its session's row. A session
    // that ended WITHOUT one (cancel_focus, its task deleted, a task shared with me)
    // left it held for ever; detaching re-queues it without the session so it syncs
    // (Android audit 2026-09-23, A14).
    @Test fun detachCapturesFromSession_releasesACaptureHeldOnASessionThatWillNeverExist() = runTest {
        val remote = FakeRemote()
        val write = WriteThrough(store)
        val flusher = OutboxFlusher(remote, store)
        val ended = java.util.UUID.randomUUID().toString()
        val stillLive = java.util.UUID.randomUUID().toString()
        val note = Capture(id = java.util.UUID.randomUUID().toString(), sessionId = ended, tag = CaptureTag.FOLLOW_UP, body = "call the bank", at = "2026-05-21T10:00:00.000Z")
        val other = Capture(id = java.util.UUID.randomUUID().toString(), sessionId = stillLive, tag = CaptureTag.IDEA, body = "later", at = "2026-05-21T10:01:00.000Z")
        write.upsertCapture(note)
        write.upsertCapture(other)

        flusher.flush("u1")
        assertTrue("both wait for a session row", remote.upserts.isEmpty())

        write.detachCapturesFromSession(ended)
        flusher.flush("u1")

        val sent = Json.parseToJsonElement(remote.upserts.single().second).jsonObject
        assertEquals(note.id, (sent["id"] as JsonPrimitive).content)
        assertEquals("sent without the session that never came", kotlinx.serialization.json.JsonNull, sent["session_id"])
        assertNull(store.captures().first().single { it.id == note.id }.sessionId)
        assertEquals("a live session's capture still waits for its row", listOf(other.id), store.pending().map { it.recordId })
    }

    // The task was deleted on another device mid-session: the Session goes up without
    // it, and so must the captures filed on it. captures.task_id references tasks(id),
    // so one still naming the dead task was refused and quarantined on every launch
    // (Android audit 2026-09-23, A14).
    @Test fun unlinkCapturesFromTask_sendsTheCapturesOfADeletedTaskWithoutIt() = runTest {
        val remote = FakeRemote()
        val write = WriteThrough(store)
        val flusher = OutboxFlusher(remote, store)
        fun uuid() = java.util.UUID.randomUUID().toString()
        val gone = uuid(); val kept = uuid(); val sid = uuid()
        store.upsert(Tables.TASKS, task(kept, "2026-05-21T10:00:00.000Z"), TaskItem.serializer(), kept)
        val note = Capture(id = uuid(), taskId = gone, sessionId = sid, tag = CaptureTag.FOLLOW_UP, body = "call the bank", at = "2026-05-21T10:00:00.000Z")
        val other = Capture(id = uuid(), taskId = kept, sessionId = sid, tag = CaptureTag.IDEA, body = "later", at = "2026-05-21T10:01:00.000Z")
        write.upsertCapture(note)
        write.upsertCapture(other)

        write.unlinkCapturesFromTask(gone)
        write.unlinkCapturesFromTask(kept)   // still stored here: never unlinked
        flusher.flush("u1")
        assertTrue("both still wait for their session's row", remote.upserts.isEmpty())

        write.upsertSession(tech.csalliance.unstuck.core.model.Session(id = sid, taskName = "Gone", actualSec = 60, completedAt = "2026-05-21T10:30:00.000Z"))
        flusher.flush("u1")

        val sent = remote.upserts.filter { it.first == Tables.CAPTURES }.map { Json.parseToJsonElement(it.second).jsonObject }
        val sentNote = sent.single { (it["id"] as JsonPrimitive).content == note.id }
        assertEquals("sent without the dead task", kotlinx.serialization.json.JsonNull, sentNote["task_id"])
        assertEquals("still joined to its session", sid, (sentNote["session_id"] as JsonPrimitive).content)
        assertEquals(kept, (sent.single { (it["id"] as JsonPrimitive).content == other.id }["task_id"] as JsonPrimitive).content)
        assertNull(store.captures().first().single { it.id == note.id }.taskId)
        assertTrue(store.pending().isEmpty())
    }

    // Captures an earlier build stranded: filed on a repeating task's DAY (its
    // cal_block id, which captures_task_id_fkey refuses), or held behind a session
    // that ended without a Session row. The heal re-files the first on the series and
    // releases the second, and leaves alone anything that could still resolve
    // (Android audit 2026-09-23, A14).
    @Test fun healStrandedCaptures_refilesADaysCaptureOnItsSeries_andReleasesOnlyNeverWrittenSessions() = runTest {
        val remote = FakeRemote()
        val write = WriteThrough(store)
        val flusher = OutboxFlusher(remote, store)
        fun uuid() = java.util.UUID.randomUUID().toString()
        val tpl = uuid(); val day = uuid()
        store.upsert(Tables.TASKS, task(tpl, "2026-05-21T10:00:00.000Z").copy(recurrence = tech.csalliance.unstuck.core.model.Recurrence.Daily()), TaskItem.serializer(), tpl)
        store.upsert(Tables.CAL_BLOCKS, tech.csalliance.unstuck.core.model.CalBlock(id = day, taskId = tpl, taskName = "Stretch", startTime = "09:00", durationMinutes = 25, date = "2026-05-21", kind = tech.csalliance.unstuck.core.model.CalBlockKind.TASK), tech.csalliance.unstuck.core.model.CalBlock.serializer(), day)
        val written = uuid(); val neverWritten = uuid(); val live = uuid(); val ending = uuid(); val newer = uuid()
        store.upsert(Tables.SESSIONS, tech.csalliance.unstuck.core.model.Session(id = written, taskId = tpl, taskName = "Stretch", actualSec = 60, completedAt = "2026-05-21T09:30:00.000Z"), tech.csalliance.unstuck.core.model.Session.serializer(), written)
        store.setLiveSession(tech.csalliance.unstuck.core.model.LiveSession(id = live, taskId = tpl, sessionStart = 1L, sessionEstimateMin = 25, treatment = tech.csalliance.unstuck.core.model.FocusTreatment.AMBIENT))
        fun cap(taskId: String?, sessionId: String?) = Capture(id = uuid(), taskId = taskId, sessionId = sessionId, tag = CaptureTag.FOLLOW_UP, body = "note", at = "2026-05-21T10:00:00.000Z")

        write.nowMillis = { 1_000L }   // queued by the earlier build
        val onTheDay = cap(day, written).also { write.upsertCapture(it) }
        val stranded = cap(null, neverWritten).also { write.upsertCapture(it) }
        val onLive = cap(null, live).also { write.upsertCapture(it) }
        val onEnding = cap(null, ending).also { write.upsertCapture(it) }
        val endingRow = tech.csalliance.unstuck.core.model.Session(id = ending, taskName = "Ending", actualSec = 60, completedAt = "2026-05-21T09:40:00.000Z")
        write.upsertSession(endingRow)
        store.delete(Tables.SESSIONS, ending)   // only its queued op shows it is coming
        // A parked op restored at sign-in comes back without its local row.
        val parked = cap(null, neverWritten)
        store.enqueue(OutboxEntity(op = "upsert", recordTable = Tables.CAPTURES, recordId = parked.id, payload = DbRowCodec.encodeCapture(parked).toString(), dependsOn = neverWritten, createdAt = 1_000L))
        write.nowMillis = { 9_000L }   // this run
        val thisRun = cap(null, newer).also { write.upsertCapture(it) }

        assertEquals(2, write.healStrandedCaptures(queuedBefore = 5_000L))
        assertEquals("idempotent", 0, write.healStrandedCaptures(queuedBefore = 5_000L))

        val rows = store.captures().first().associateBy { it.id }
        assertEquals("a day's capture is filed on its series", tpl, rows.getValue(onTheDay.id).taskId)
        assertEquals(written, rows.getValue(onTheDay.id).sessionId)
        assertNull("a session that was never written is dropped", rows.getValue(stranded.id).sessionId)
        assertEquals("the live session's row is still to come", live, rows.getValue(onLive.id).sessionId)
        assertEquals("a queued Session row is still to come", ending, rows.getValue(onEnding.id).sessionId)
        assertEquals("a capture from this run is not judged", newer, rows.getValue(thisRun.id).sessionId)
        assertTrue("a restored parked op is left alone", store.pending().any { it.recordId == parked.id && it.dependsOn == neverWritten })

        store.upsert(Tables.SESSIONS, endingRow, tech.csalliance.unstuck.core.model.Session.serializer(), ending)
        flusher.flush("u1")
        val sent = remote.upserts.filter { it.first == Tables.CAPTURES }.map { Json.parseToJsonElement(it.second).jsonObject }.associateBy { (it["id"] as JsonPrimitive).content }
        assertEquals(tpl, (sent.getValue(onTheDay.id)["task_id"] as JsonPrimitive).content)
        assertEquals(kotlinx.serialization.json.JsonNull, sent.getValue(stranded.id)["session_id"])
        assertTrue("its session's row landed first, then it went", onEnding.id in sent)
        assertFalse(onLive.id in sent)
        assertFalse(thisRun.id in sent)
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

    // --- 2026-09 sync sweep: flush-on-enqueue / push-then-pull / merge fixes ---

    // THE CRITICAL SCENARIO. A user renames a task; the op is still queued when the
    // ~60s foreground pull (or a resume hydrate) runs. The server still has the OLD
    // name AND the same id, so the old `id !in serverIds` preservation didn't apply
    // and the pull wrote the stale server row back over the edit ("reverts until the
    // next flush"). The local row must survive the pull.
    @Test fun hydrate_keepsPendingEditEvenWhenServerHasTheSameId() = runTest {
        val remote = FakeRemote()
        val hydrator = Hydrator(remote, store)
        val write = WriteThrough(store)
        val original = task("t1", updatedAt = "2026-05-21T10:00:00.000Z", name = "Draft")
        store.upsert(Tables.TASKS, original, TaskItem.serializer(), original.id, original.updatedAt)
        remote.serverRows[Tables.TASKS] = listOf(serverRow(original))
        // The local edit (WriteThrough: Room updated + op enqueued, nothing pushed).
        write.upsertTask(original.copy(name = "Draft v2", updatedAt = "2026-05-21T10:05:00.000Z"))
        assertEquals(1, store.pending().size)
        hydrator.hydrate("u1")
        assertEquals("the unflushed rename must NOT revert to the stale server copy", "Draft v2", store.tasks().first().single().name)
        assertEquals("the op is still queued for the next drain", 1, store.pending().size)
    }

    @Test fun writeThrough_capturesTheMergeBase_andCarriesItAcrossStackedEdits() = runTest {
        val write = WriteThrough(store)
        // A brand-new row: no prior local state → base null (a create stays a create).
        val created = task("t1", updatedAt = "2026-05-21T10:00:00.000Z", name = "New")
        write.upsertTask(created)
        assertNull(store.pending().single().base)
        store.dequeue(store.pending().single().seq)   // "flushed"
        // First edit of a synced row: base = the row as it was before the edit.
        write.upsertTask(created.copy(name = "Edit 1", updatedAt = "2026-05-21T10:01:00.000Z"))
        val first = store.pending().single()
        val base1 = Json.parseToJsonElement(first.base!!) as JsonObject
        assertEquals("New", (base1["name"] as JsonPrimitive).content)
        // A second edit while the first is still queued: the base stays the last SYNCED
        // state (the flusher coalesces the older op away; the merge must still measure
        // both edits against what the server had).
        write.upsertTask(created.copy(name = "Edit 2", updatedAt = "2026-05-21T10:02:00.000Z"))
        val latest = store.latestPendingUpsert(Tables.TASKS, "t1")!!
        assertEquals("Edit 2", (Json.parseToJsonElement(latest.payload!!).jsonObject["name"] as JsonPrimitive).content)
        assertEquals(first.base, latest.base)
    }

    @Test fun onEnqueueHook_firesOnEveryQueuedOp_andNeverBreaksTheWrite() = runTest {
        val write = WriteThrough(store)
        var fired = 0
        write.onEnqueue = { fired++; throw IllegalStateException("hook blew up") }
        write.upsertTask(task("t1", updatedAt = "2026-05-21T10:00:00.000Z"))
        write.deleteTask("t2")   // a different row: deleting t1 would (rightly) cancel its queued upsert
        assertEquals("one hook call per op (upsert + delete)", 2, fired)
        assertEquals("the local write + enqueue survive a throwing hook", listOf("t1", "t2"), store.pending().map { it.recordId })
    }

    // Row-level LWW used to drop the WHOLE local op when the server row was newer, so
    // an unrelated field edited on the phone (first_physical_action) was lost when the
    // web ticked the same task done. With a base, the two edits merge per field.
    @Test fun pruneStaleTaskOps_threeWayMergesAnOlderLocalEditAgainstANewerServerRow() = runTest {
        val remote = FakeRemote()
        val hydrator = Hydrator(remote, store).apply { nowIso = { "2026-05-21T12:00:00.000Z" } }
        val write = WriteThrough(store)
        val synced = task("t1", updatedAt = "2026-05-21T09:00:00.000Z", name = "Dentist")
        store.upsert(Tables.TASKS, synced, TaskItem.serializer(), synced.id, synced.updatedAt)
        // Phone (10:00, unflushed): sets the first physical action.
        write.upsertTask(synced.copy(firstPhysicalAction = "Find the insurance card", updatedAt = "2026-05-21T10:00:00.000Z"))
        // Web (10:05): ticks it done.
        remote.serverRows[Tables.TASKS] = listOf(serverRow(synced.copy(done = true, completedAt = "2026-05-21T10:05:00.000Z", updatedAt = "2026-05-21T10:05:00.000Z")))

        hydrator.pruneStaleTaskOps()

        val op = store.pending().single()
        val merged = Json.parseToJsonElement(op.payload!!).jsonObject
        assertEquals("the server's completion is absorbed", true, (merged["done"] as JsonPrimitive).content.toBoolean())
        assertEquals("the phone's unrelated edit SURVIVES", "Find the insurance card", (merged["first_physical_action"] as JsonPrimitive).content)
        assertEquals("re-stamped newer than both so other devices accept it", "2026-05-21T12:00:00.000Z", (merged["updated_at"] as JsonPrimitive).content)
        assertEquals("the base now IS the server row we absorbed", serverRow(synced.copy(done = true, completedAt = "2026-05-21T10:05:00.000Z", updatedAt = "2026-05-21T10:05:00.000Z")).toString(), op.base)
        val local = store.tasks().first().single()
        assertTrue("the UI shows the merged row at once", local.done)
        assertEquals("Find the insurance card", local.firstPhysicalAction)
    }

    @Test fun mergeTaskRow_fieldRules_localWinsUnchangedServer_serverWinsUnchangedLocal_skewOnConflicts() {
        fun row(name: String, done: Boolean, est: Int, at: String) = serverRow(task("t1", updatedAt = at, name = name).copy(done = done, estimateMin = est))
        val base = row("A", false, 25, "2026-05-21T09:00:00.000Z")
        val local = row("Local", false, 25, "2026-05-21T10:00:00.000Z")          // changed: name
        val server = row("Server", true, 40, "2026-05-21T10:00:01.000Z")         // changed: name (conflict), done, estimate
        val m = Hydrator.mergeTaskRow(base, local, server, localMs = Hydrator.updatedAtMs(local)!!, serverMs = Hydrator.updatedAtMs(server)!!, nowIso = "2026-05-21T12:00:00.000Z")
        assertEquals("server is newer by only 1 s (inside the skew margin) → the local edit wins the conflict", "Local", (m["name"] as JsonPrimitive).content)
        assertEquals("a field only the server changed takes the server value", "true", (m["done"] as JsonPrimitive).content)
        assertEquals("40", (m["estimate_min"] as JsonPrimitive).content)
        val serverMuchNewer = row("Server", true, 40, "2026-05-21T10:00:05.000Z")
        val m2 = Hydrator.mergeTaskRow(base, local, serverMuchNewer, localMs = Hydrator.updatedAtMs(local)!!, serverMs = Hydrator.updatedAtMs(serverMuchNewer)!!, nowIso = "x")
        assertEquals("beyond the skew margin the newer writer (server) wins the conflict", "Server", (m2["name"] as JsonPrimitive).content)
        assertFalse("user_id is never carried (the gateway re-attaches it)", m2.containsKey("user_id"))
    }

    // PostgREST hands timestamps back as "…+00:00"; the phone writes "…Z". A base
    // taken from the phone's own payload (the op queued before it, or the one that
    // just landed) holds the phone's text, so the server's copy of the SAME instant
    // read as a server change, and the two-sided "conflict" went to the server's
    // stamp: an Undo under a slow commit kept the old completion time.
    @Test fun mergeTaskRow_aTimestampTheServerOnlyReformattedIsNotAServerChange() {
        val done = task("t1", updatedAt = "2026-05-21T09:10:00.000Z").copy(done = true, completedAt = "2026-05-21T09:10:00.000Z")
        val base = serverRow(done)
        val undo = serverRow(done.copy(done = false, completedAt = null, updatedAt = "2026-05-21T09:10:00.500Z"))
        // Mark done committed 3 s after the Undo tap (past the skew margin).
        val server = serverRow(done.copy(completedAt = "2026-05-21T09:10:00+00:00", updatedAt = "2026-05-21T09:10:03.500000+00:00"))
        val m = Hydrator.mergeTaskRow(base, undo, server, localMs = Hydrator.updatedAtMs(undo)!!, serverMs = Hydrator.updatedAtMs(server)!!, nowIso = "x")
        assertEquals(false, m.bool("done"))
        assertNull("the Undo's cleared completion time stands", m.str("completed_at"))
        // A genuinely different time is still a server change (newer, so it wins).
        val redone = serverRow(done.copy(completedAt = "2026-05-21T09:20:00+00:00", updatedAt = "2026-05-21T09:20:00.000000+00:00"))
        val m2 = Hydrator.mergeTaskRow(base, undo, redone, localMs = Hydrator.updatedAtMs(undo)!!, serverMs = Hydrator.updatedAtMs(redone)!!, nowIso = "x")
        assertEquals("2026-05-21T09:20:00+00:00", m2.str("completed_at"))
    }

    @Test fun pruneStaleTaskOps_withoutBase_keepsAnOpInsideTheSkewMargin_dropsBeyondIt() = runTest {
        val remote = FakeRemote()
        val hydrator = Hydrator(remote, store)
        val local = task("t1", updatedAt = "2026-05-21T10:00:00.000Z")
        store.enqueue(OutboxEntity(op = "upsert", recordTable = Tables.TASKS, recordId = "t1", payload = DbRowCodec.encodeTask(local).toString(), createdAt = 1L))
        remote.serverRows[Tables.TASKS] = listOf(serverRow(task("t1", updatedAt = "2026-05-21T10:00:01.500Z")))   // 1.5 s newer: clocks disagree, not conclusive
        hydrator.pruneStaleTaskOps()
        assertEquals("inside the skew margin the local op is kept", 1, store.pending().size)
        remote.serverRows[Tables.TASKS] = listOf(serverRow(task("t1", updatedAt = "2026-05-21T10:00:03.000Z")))   // 3 s newer: genuinely superseded
        hydrator.pruneStaleTaskOps()
        assertTrue("beyond it the base-less op is pruned (row-level LWW)", store.pending().isEmpty())
    }

    @Test fun pruneStaleTaskOps_withBase_leavesTheOpAloneWhenTheServerRowIsUnchanged() = runTest {
        val remote = FakeRemote()
        val hydrator = Hydrator(remote, store)
        val write = WriteThrough(store)
        val synced = task("t1", updatedAt = "2026-05-21T09:00:00.000Z", name = "Dentist")
        store.upsert(Tables.TASKS, synced, TaskItem.serializer(), synced.id, synced.updatedAt)
        remote.serverRows[Tables.TASKS] = listOf(serverRow(synced))
        write.upsertTask(synced.copy(name = "Dentist (moved)", updatedAt = "2026-05-21T10:00:00.000Z"))
        val before = store.pending().single()
        hydrator.pruneStaleTaskOps()
        val after = store.pending().single()
        assertEquals("nothing to merge: payload untouched", before.payload, after.payload)
        assertEquals(before.base, after.base)
    }

    // --- two or more queued edits to one task (audit 2026-09-22 C9) ---
    // Every later edit of a row carries the FIRST edit's base (WriteThrough), so
    // the prune judges a row's queued edits as ONE chain by its oldest op. These
    // enqueue through the real WriteThrough, as the app does. Ported from the iOS
    // HydratorPruneTests (build 81).

    private val ser = TaskItem.serializer()

    private fun synced(updatedAt: String) = TaskItem(
        id = "t1", name = "Call mom", estimateMin = 25, createdAt = "2026-05-21T08:00:00.000Z", updatedAt = updatedAt,
    )

    private fun payload(op: OutboxEntity): JsonObject = Json.parseToJsonElement(op.payload!!).jsonObject
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
    private fun FakeRemote.sentTasks() = upserts.filter { it.first == Tables.TASKS }.map { Json.parseToJsonElement(it.second).jsonObject }

    // Case A: two offline edits, THEN the web completes the task. Already safe on
    // Android (the carried base); a guard that the chain keeps it so.
    @Test fun chain_twoOfflineEditsBothSurviveAWebCompletionThatLandsAfterThem() = runTest {
        val remote = FakeRemote()
        val hydrator = Hydrator(remote, store).apply { nowIso = { "2026-05-21T12:00:00.000Z" } }
        val write = WriteThrough(store)
        val s0 = synced("2026-05-21T09:00:00.000000+00:00")
        store.upsert(Tables.TASKS, s0, ser, s0.id, s0.updatedAt)
        val e1 = s0.copy(name = "Call mom re: birthday", updatedAt = "2026-05-21T09:10:00.000Z")
        write.upsertTask(e1)
        write.upsertTask(e1.copy(estimateMin = 10, updatedAt = "2026-05-21T09:11:00.000Z"))
        val s1 = s0.copy(done = true, completedAt = "2026-05-21T09:30:00.000Z", updatedAt = "2026-05-21T09:30:00.000000+00:00")
        remote.serverRows[Tables.TASKS] = listOf(serverRow(s1))

        hydrator.pruneStaleTaskOps()

        val ops = store.pending()
        assertEquals("both edits stay queued", 2, ops.size)
        val op1 = payload(ops[0])
        val op2 = payload(ops[1])
        assertEquals("Call mom re: birthday", op1.str("name"))
        assertEquals(25, op1.int("estimate_min"))
        assertEquals("the web completion is kept in the first op", true, op1.bool("done"))
        assertEquals("the second op must not revert the rename", "Call mom re: birthday", op2.str("name"))
        assertEquals(10, op2.int("estimate_min"))
        assertEquals(true, op2.bool("done"))
        assertEquals("2026-05-21T09:30:00.000Z", op2.str("completed_at"))
        assertEquals("every op is re-based on the server row", listOf(serverRow(s1).toString(), serverRow(s1).toString()), ops.map { it.base })
        val local = store.tasks().first().single()
        assertEquals("Call mom re: birthday", local.name)
        assertEquals(10, local.estimateMin)
        assertTrue(local.done)

        OutboxFlusher(remote, store).flush("u1")
        val sent = remote.sentTasks().single()
        assertEquals("only the tail is sent, and it carries both edits and the completion", "Call mom re: birthday", sent.str("name"))
        assertEquals(10, sent.int("estimate_min"))
        assertEquals(true, sent.bool("done"))
    }

    // Case B: the web completed the task BEFORE the phone's two offline edits.
    @Test fun chain_twoOfflineEditsDoNotReopenAnEarlierWebCompletion() = runTest {
        val remote = FakeRemote()
        val hydrator = Hydrator(remote, store).apply { nowIso = { "2026-05-21T12:00:00.000Z" } }
        val write = WriteThrough(store)
        val s0 = synced("2026-05-21T08:00:00.000000+00:00")
        store.upsert(Tables.TASKS, s0, ser, s0.id, s0.updatedAt)
        val e1 = s0.copy(name = "Call mom re: birthday", updatedAt = "2026-05-21T09:00:00.000Z")
        write.upsertTask(e1)
        write.upsertTask(e1.copy(estimateMin = 10, updatedAt = "2026-05-21T09:01:00.000Z"))
        val s1 = s0.copy(done = true, completedAt = "2026-05-21T08:50:00.000Z", updatedAt = "2026-05-21T08:50:00.000000+00:00")
        remote.serverRows[Tables.TASKS] = listOf(serverRow(s1))

        hydrator.pruneStaleTaskOps()

        val last = payload(store.pending().last())
        assertEquals("the op that lands must not re-open the web completion", true, last.bool("done"))
        assertEquals("2026-05-21T08:50:00.000Z", last.str("completed_at"))
        assertEquals("Call mom re: birthday", last.str("name"))
        assertEquals(10, last.int("estimate_min"))
        assertTrue(store.tasks().first().single().done)
    }

    // A slow device clock with an UNCHANGED server row: the content judge says
    // "keep" for the whole chain, whatever the stamps say.
    @Test fun chain_aSlowClockLeavesTheChainAloneWhenTheServerRowIsUnchanged() = runTest {
        val remote = FakeRemote()
        val hydrator = Hydrator(remote, store)
        val write = WriteThrough(store)
        val s0 = synced("2026-05-21T10:00:00.000000+00:00")
        store.upsert(Tables.TASKS, s0, ser, s0.id, s0.updatedAt)
        val e1 = s0.copy(name = "Call mom re: birthday", updatedAt = "2026-05-21T09:57:00.000Z")
        write.upsertTask(e1)
        write.upsertTask(e1.copy(estimateMin = 10, updatedAt = "2026-05-21T09:57:30.000Z"))
        val before = store.pending()
        remote.serverRows[Tables.TASKS] = listOf(serverRow(s0))

        hydrator.pruneStaleTaskOps()

        assertEquals("an unmoved server row leaves the whole chain untouched", before, store.pending())
        val local = store.tasks().first().single()
        assertEquals("Call mom re: birthday", local.name)
        assertEquals(10, local.estimateMin)
        OutboxFlusher(remote, store).flush("u1")
        assertEquals(listOf("Call mom re: birthday"), remote.sentTasks().map { it.str("name") })
    }

    // Mark done is SENT, lands on the server, but the client sees a failure (a
    // timeout), so it stays queued; Undo queues behind it with the same carried
    // base. Merged on its own against that base, the Undo's done=false read as
    // "unchanged" and took the server's done=true: the Undo was lost and the row
    // flipped back to done.
    @Test fun chain_anUndoBehindAMarkDoneThatLandedButReportedAFailureSurvives() = runTest {
        val remote = FakeRemote()
        val hydrator = Hydrator(remote, store).apply { nowIso = { "2026-05-21T12:00:00.000Z" } }
        val write = WriteThrough(store)
        val s0 = synced("2026-05-21T09:00:00.000000+00:00")
        store.upsert(Tables.TASKS, s0, ser, s0.id, s0.updatedAt)
        val done = s0.copy(done = true, completedAt = "2026-05-21T09:10:00.000Z", updatedAt = "2026-05-21T09:10:00.000Z")
        write.upsertTask(done)
        // The server committed it (stamped by its own clock); the client never heard.
        remote.serverRows[Tables.TASKS] = listOf(serverRow(done.copy(updatedAt = "2026-05-21T09:10:01.000000+00:00")))
        write.upsertTask(done.copy(done = false, completedAt = null, updatedAt = "2026-05-21T09:11:00.000Z"))

        hydrator.pruneStaleTaskOps()

        val undo = payload(store.pending().last())
        assertEquals("the Undo must not be lost to the completion that landed", false, undo.bool("done"))
        assertNull(undo.str("completed_at"))
        assertFalse("the local row stays undone", store.tasks().first().single().done)
        OutboxFlusher(remote, store).flush("u1")
        assertEquals(listOf(false), remote.sentTasks().map { it.bool("done") })
    }

    // Mark done is in flight when Undo is queued (it carries Mark done's base);
    // Mark done then lands cleanly, but Undo's own send fails. Undo's base still
    // said "not done", so the next prune read its done=false as unchanged and took
    // the server's done=true. Once an op lands, the ops queued behind it measure
    // against what landed.
    @Test fun chain_anUndoQueuedWhileMarkDoneWasInFlightSurvivesItsOwnFailedSend() = runTest {
        val write = WriteThrough(store)
        val s0 = synced("2026-05-21T09:00:00.000000+00:00")
        store.upsert(Tables.TASKS, s0, ser, s0.id, s0.updatedAt)
        val done = s0.copy(done = true, completedAt = "2026-05-21T09:10:00.000Z", updatedAt = "2026-05-21T09:10:00.000Z")
        write.upsertTask(done)
        val remote = object : SyncRemote {
            var landed: JsonObject? = null
            override suspend fun fetchAll(table: String): List<JsonObject> = listOfNotNull(landed)
            override suspend fun upsert(table: String, row: JsonObject, userId: String) {
                if (landed != null) throw RuntimeException("offline")   // Undo's send fails
                // Undo is tapped while Mark done is on the wire; then it lands.
                write.upsertTask(done.copy(done = false, completedAt = null, updatedAt = "2026-05-21T09:10:02.000Z"))
                landed = JsonObject(row + ("updated_at" to JsonPrimitive("2026-05-21T09:10:03.000000+00:00")))
            }
            override suspend fun delete(table: String, id: String) {}
            override suspend fun rpc(fn: String, params: JsonObject) {}
            override suspend fun fetchSince(table: String, column: String, since: String, limit: Int): List<JsonObject> = emptyList()
            override suspend fun fetchIds(table: String, offset: Int, limit: Int): List<String> = emptyList()
        }
        OutboxFlusher(remote, store).flush("u1")
        assertEquals("Undo is still queued", listOf(false), store.pending().map { payload(it).bool("done") })

        Hydrator(remote, store).apply { nowIso = { "2026-05-21T12:00:00.000Z" } }.pruneStaleTaskOps()

        assertEquals("the Undo must survive the next prune", false, payload(store.pending().single()).bool("done"))
        assertFalse(store.tasks().first().single().done)
    }

    // The same two Undo paths on a slow link: Mark done commits 3 s after the Undo
    // tap, and the server hands its completion time back as "…+00:00". The Undo
    // must clear the completion time too, or the next completion keeps the stale
    // one (stampCompletion preserves a prior completed_at).
    @Test fun chain_anUndoBehindAMarkDoneThatCommittedSlowlyClearsTheCompletionTime() = runTest {
        val remote = FakeRemote()
        val hydrator = Hydrator(remote, store).apply { nowIso = { "2026-05-21T12:00:00.000Z" } }
        val write = WriteThrough(store)
        val s0 = synced("2026-05-21T09:00:00.000000+00:00")
        store.upsert(Tables.TASKS, s0, ser, s0.id, s0.updatedAt)
        val done = s0.copy(done = true, completedAt = "2026-05-21T09:10:00.000Z", updatedAt = "2026-05-21T09:10:00.000Z")
        write.upsertTask(done)
        write.upsertTask(done.copy(done = false, completedAt = null, updatedAt = "2026-05-21T09:10:00.500Z"))
        remote.serverRows[Tables.TASKS] = listOf(serverRow(done.copy(completedAt = "2026-05-21T09:10:00+00:00", updatedAt = "2026-05-21T09:10:03.500000+00:00")))

        hydrator.pruneStaleTaskOps()

        val undo = payload(store.pending().last())
        assertEquals(false, undo.bool("done"))
        assertNull(undo.str("completed_at"))
        val local = store.tasks().first().single()
        assertFalse(local.done)
        assertNull(local.completedAt)
    }

    @Test fun chain_anUndoQueuedBehindASlowlyCommittedMarkDoneClearsTheCompletionTime() = runTest {
        val write = WriteThrough(store)
        val s0 = synced("2026-05-21T09:00:00.000000+00:00")
        store.upsert(Tables.TASKS, s0, ser, s0.id, s0.updatedAt)
        val done = s0.copy(done = true, completedAt = "2026-05-21T09:10:00.000Z", updatedAt = "2026-05-21T09:10:00.000Z")
        write.upsertTask(done)
        val remote = object : SyncRemote {
            var landed: JsonObject? = null
            override suspend fun fetchAll(table: String): List<JsonObject> = listOfNotNull(landed)
            override suspend fun upsert(table: String, row: JsonObject, userId: String) {
                if (landed != null) throw RuntimeException("offline")   // Undo's send fails
                write.upsertTask(done.copy(done = false, completedAt = null, updatedAt = "2026-05-21T09:10:00.500Z"))
                landed = JsonObject(
                    row + mapOf(
                        "completed_at" to JsonPrimitive("2026-05-21T09:10:00+00:00"),
                        "updated_at" to JsonPrimitive("2026-05-21T09:10:03.500000+00:00"),
                    ),
                )
            }
            override suspend fun delete(table: String, id: String) {}
            override suspend fun rpc(fn: String, params: JsonObject) {}
            override suspend fun fetchSince(table: String, column: String, since: String, limit: Int): List<JsonObject> = emptyList()
            override suspend fun fetchIds(table: String, offset: Int, limit: Int): List<String> = emptyList()
        }
        OutboxFlusher(remote, store).flush("u1")

        Hydrator(remote, store).apply { nowIso = { "2026-05-21T12:00:00.000Z" } }.pruneStaleTaskOps()

        val undo = payload(store.pending().single())
        assertEquals(false, undo.bool("done"))
        assertNull(undo.str("completed_at"))
        assertNull(store.tasks().first().single().completedAt)
    }

    // Android has no per-row savepoint (a nested Room transaction that fails rolls
    // the outer one back), so a write that fails partway through a chain must not
    // commit the writes before it: a head merged onto the server row with its tail
    // still unmerged flushes the tail and re-opens the completion the head took in.
    @Test fun chain_aWriteThatFailsMidChainLeavesTheWholeChainAsQueued() = runTest {
        val remote = FakeRemote()
        val hydrator = Hydrator(remote, store).apply { nowIso = { "2026-05-21T12:00:00.000Z" } }
        val write = WriteThrough(store)
        val s0 = synced("2026-05-21T09:00:00.000000+00:00")
        store.upsert(Tables.TASKS, s0, ser, s0.id, s0.updatedAt)
        val e1 = s0.copy(name = "Call mom re: birthday", updatedAt = "2026-05-21T09:10:00.000Z")
        write.upsertTask(e1)
        write.upsertTask(e1.copy(estimateMin = 10, updatedAt = "2026-05-21T09:11:00.000Z"))
        remote.serverRows[Tables.TASKS] = listOf(serverRow(s0.copy(done = true, completedAt = "2026-05-21T09:30:00.000Z", updatedAt = "2026-05-21T09:30:00.000000+00:00")))
        val queued = store.pending()
        // The second op's rewrite fails (a full disk, an I/O error).
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_rewrite BEFORE UPDATE ON outbox WHEN OLD.seq = ${queued[1].seq} " +
                "BEGIN SELECT RAISE(ABORT, 'disk I/O error'); END",
        )

        val pruned = runCatching { hydrator.pruneStaleTaskOps() }

        assertTrue("the failure aborts the prune, and with it the flush after it", pruned.isFailure)
        assertEquals("neither op was rewritten", queued, store.pending())
        assertFalse("the local row is untouched", store.tasks().first().single().done)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_rewrite")
        hydrator.pruneStaleTaskOps()
        assertEquals("the next prune merges the whole chain", listOf(true, true), store.pending().map { payload(it).bool("done") })
    }

    // Mark done, then Undo, while the web renamed the task: both edits merge onto
    // the rename, and the Undo is what reaches the server.
    @Test fun chain_markDoneThenUndoWhileTheWebRenamedLandsTheRenameUndone() = runTest {
        val remote = FakeRemote()
        val hydrator = Hydrator(remote, store).apply { nowIso = { "2026-05-21T12:00:00.000Z" } }
        val write = WriteThrough(store)
        val s0 = synced("2026-05-21T09:00:00.000000+00:00")
        store.upsert(Tables.TASKS, s0, ser, s0.id, s0.updatedAt)
        val done = s0.copy(done = true, completedAt = "2026-05-21T09:10:00.000Z", updatedAt = "2026-05-21T09:10:00.000Z")
        write.upsertTask(done)
        write.upsertTask(done.copy(done = false, completedAt = null, updatedAt = "2026-05-21T09:11:00.000Z"))
        remote.serverRows[Tables.TASKS] = listOf(serverRow(s0.copy(name = "Web name", updatedAt = "2026-05-21T09:30:00.000000+00:00")))

        hydrator.pruneStaleTaskOps()

        val ops = store.pending().map { payload(it) }
        assertEquals(listOf("Web name", "Web name"), ops.map { it.str("name") })
        assertEquals(listOf(true, false), ops.map { it.bool("done") })
        val local = store.tasks().first().single()
        assertEquals("Web name", local.name)
        assertFalse(local.done)
        OutboxFlusher(remote, store).flush("u1")
        val sent = remote.sentTasks().single()
        assertEquals("Web name", sent.str("name"))
        assertEquals(false, sent.bool("done"))
    }

    // An edit queued WHILE the prune's server read is in flight. The prune read
    // the ops before the fetch and saved its merged row after it, so the new
    // edit's local row was overwritten and its op flushed unmerged (re-opening
    // the web completion).
    @Test fun chain_anEditQueuedWhileThePruneIsFetchingJoinsTheChain() = runTest {
        val remote = FakeRemote()
        val hydrator = Hydrator(remote, store).apply { nowIso = { "2026-05-21T12:00:00.000Z" } }
        val write = WriteThrough(store)
        val s0 = synced("2026-05-21T09:00:00.000000+00:00")
        store.upsert(Tables.TASKS, s0, ser, s0.id, s0.updatedAt)
        val e1 = s0.copy(name = "Call mom re: birthday", updatedAt = "2026-05-21T09:10:00.000Z")
        write.upsertTask(e1)
        remote.serverRows[Tables.TASKS] = listOf(serverRow(s0.copy(done = true, completedAt = "2026-05-21T09:30:00.000Z", updatedAt = "2026-05-21T09:30:00.000000+00:00")))
        remote.onFetchAll = { table ->
            if (table == Tables.TASKS) write.upsertTask(e1.copy(estimateMin = 10, updatedAt = "2026-05-21T09:31:00.000Z"))
        }

        hydrator.pruneStaleTaskOps()

        val ops = store.pending()
        assertEquals(2, ops.size)
        val op2 = payload(ops.last())
        assertEquals("the edit queued mid-fetch is merged too", true, op2.bool("done"))
        assertEquals("Call mom re: birthday", op2.str("name"))
        assertEquals(10, op2.int("estimate_min"))
        val local = store.tasks().first().single()
        assertEquals("the local row follows the LAST op, not the first op's merge", 10, local.estimateMin)
        assertEquals("Call mom re: birthday", local.name)
        assertTrue(local.done)
    }

    // A task deleted while the prune's read is in flight stays deleted: its
    // upserts were cancelled, so there is no chain left to save a row for.
    @Test fun chain_aTaskDeletedWhileThePruneIsFetchingIsNotResurrected() = runTest {
        val remote = FakeRemote()
        val hydrator = Hydrator(remote, store)
        val write = WriteThrough(store)
        val s0 = synced("2026-05-21T09:00:00.000000+00:00")
        store.upsert(Tables.TASKS, s0, ser, s0.id, s0.updatedAt)
        write.upsertTask(s0.copy(name = "Call mom re: birthday", updatedAt = "2026-05-21T09:10:00.000Z"))
        remote.serverRows[Tables.TASKS] = listOf(serverRow(s0.copy(done = true, updatedAt = "2026-05-21T09:30:00.000000+00:00")))
        remote.onFetchAll = { table -> if (table == Tables.TASKS) write.deleteTask("t1") }

        hydrator.pruneStaleTaskOps()

        assertTrue(store.tasks().first().isEmpty())
        assertEquals(listOf("delete"), store.pending().map { it.op })
    }

    // Android-only: a realtime echo (a newer server row) replaced a task that had
    // a queued edit; the next edit, built on the echo, carried the server's old
    // name, and the prune then merged the rename away.
    @Test fun realtimeEcho_doesNotReplaceATaskWithAQueuedEdit() = runTest {
        val remote = FakeRemote()
        val hydrator = Hydrator(remote, store).apply { nowIso = { "2026-05-21T12:00:00.000Z" } }
        val write = WriteThrough(store)
        val s0 = synced("2026-05-21T09:00:00.000000+00:00")
        store.upsert(Tables.TASKS, s0, ser, s0.id, s0.updatedAt)
        write.upsertTask(s0.copy(name = "Call mom re: birthday", updatedAt = "2026-05-21T09:10:00.000Z"))
        val webDone = serverRow(s0.copy(done = true, completedAt = "2026-05-21T09:10:05.000Z", updatedAt = "2026-05-21T09:10:05.000000+00:00"))

        assertFalse("the echo is seen but not applied", RowApply.apply(Tables.TASKS, webDone, store, "u1"))
        assertEquals("Call mom re: birthday", store.tasks().first().single().name)

        // The next edit is built on the local row; the prune then brings the
        // completion in, and both edits survive.
        write.upsertTask(store.tasks().first().single().copy(estimateMin = 10, updatedAt = "2026-05-21T09:11:00.000Z"))
        remote.serverRows[Tables.TASKS] = listOf(webDone)
        hydrator.pruneStaleTaskOps()
        val local = store.tasks().first().single()
        assertEquals("Call mom re: birthday", local.name)
        assertEquals(10, local.estimateMin)
        assertTrue(local.done)
        // Once the edit has flushed, the same echo applies again.
        store.pending().forEach { store.dequeue(it.seq) }
        val later = serverRow(s0.copy(name = "Web", updatedAt = "2026-05-21T13:00:00.000000+00:00"))
        assertTrue(RowApply.apply(Tables.TASKS, later, store, "u1"))
        assertEquals("Web", store.tasks().first().single().name)
    }

    // --- a write landing between two statements (audit 2026-09-22 C9) ---
    // Room runs its query callback on the calling thread just before each
    // statement, so the gate below can hold one statement that runs OUTSIDE a
    // transaction while the test commits another write in the gap. Inside a
    // transaction it never holds: there the other write lands wholly before or
    // after.

    private class StatementGate : RoomDatabase.QueryCallback {
        lateinit var db: RoomDatabase
        private val armed = AtomicReference<((String) -> Boolean)?>(null)
        private val released = CountDownLatch(1)
        val reached = CompletableDeferred<Unit>()
        fun holdNext(match: (String) -> Boolean) = armed.set(match)
        fun disarm() = armed.set(null)
        fun release() = released.countDown()
        override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
            val match = armed.get() ?: return
            if (!match(sqlQuery) || db.inTransaction() || !armed.compareAndSet(match, null)) return
            reached.complete(Unit)
            released.await(10, TimeUnit.SECONDS)
        }
    }

    private fun gatedDb(gate: StatementGate): UnstuckDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnstuckDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback(gate, Executor { it.run() })
            .build()
            .also { gate.db = it }

    /** Until [gate] holds a statement, or [job] finished without reaching one. */
    private suspend fun awaitGateOr(gate: StatementGate, job: Deferred<*>) =
        withTimeout(5_000) { while (!gate.reached.isCompleted && !job.isCompleted) delay(5) }

    // The realtime guard read "is an edit of this task queued?" and wrote the echo
    // as two statements. With the phone's clock behind the server's, a rename
    // committed between them was stamped EARLIER than the web's echo, and the echo
    // overwrote it; the next edit, built on that row, carried the old name and the
    // prune merged the rename away.
    @Test fun realtimeEcho_cannotLandBetweenTheQueuedEditCheckAndItsWrite() = runBlocking {
        val gate = StatementGate()
        val gdb = gatedDb(gate)
        try {
            val gstore = LocalStore(gdb)
            val write = WriteThrough(gstore)
            val s0 = synced("2026-05-21T09:00:00.000000+00:00")
            gstore.upsert(Tables.TASKS, s0, ser, s0.id, s0.updatedAt)
            val webDone = serverRow(s0.copy(done = true, completedAt = "2026-05-21T09:10:05.000Z", updatedAt = "2026-05-21T09:10:05.000000+00:00"))
            // Hold the echo at its read of the local row, the step after the check.
            gate.holdNext { it.startsWith("SELECT * FROM records WHERE tableName = ? AND id = ?") }
            val echo = async(Dispatchers.IO) { RowApply.apply(Tables.TASKS, webDone, gstore, "u1") }
            awaitGateOr(gate, echo)
            gate.disarm()

            write.upsertTask(s0.copy(name = "Call mom re: birthday", updatedAt = "2026-05-21T09:10:04.000Z"))
            gate.release()
            echo.await()

            assertEquals("the echo must not overwrite the edit queued in between", "Call mom re: birthday", gstore.tasks().first().single().name)
        } finally {
            gate.release()
            gdb.close()
        }
    }

    // A task deleted while the prune reconciles it. The delete was three
    // statements (drop the row, cancel its queued edits, queue the delete); the
    // prune's transaction landed after the first, found the edits still queued and
    // saved its merged row back over the delete: a task the server no longer has.
    @Test fun chain_thePruneCannotLandInsideATaskDelete() = runBlocking {
        val gate = StatementGate()
        val gdb = gatedDb(gate)
        try {
            val gstore = LocalStore(gdb)
            val remote = FakeRemote()
            val write = WriteThrough(gstore)
            val s0 = synced("2026-05-21T09:00:00.000000+00:00")
            gstore.upsert(Tables.TASKS, s0, ser, s0.id, s0.updatedAt)
            write.upsertTask(s0.copy(name = "Call mom re: birthday", updatedAt = "2026-05-21T09:10:00.000Z"))
            remote.serverRows[Tables.TASKS] = listOf(serverRow(s0.copy(done = true, updatedAt = "2026-05-21T09:30:00.000000+00:00")))
            // Hold the delete at its read of the queued ops, after the row is gone.
            gate.holdNext { it.startsWith("SELECT * FROM outbox ORDER BY seq ASC") }
            val delete = async(Dispatchers.IO) { write.deleteTask("t1") }
            awaitGateOr(gate, delete)
            gate.disarm()

            Hydrator(remote, gstore).pruneStaleTaskOps()
            gate.release()
            delete.await()

            assertTrue("the deleted task stays deleted", gstore.tasks().first().isEmpty())
            assertEquals(listOf("delete"), gstore.pending().map { it.op })
        } finally {
            gate.release()
            gdb.close()
        }
    }

    // The sign-out drain's 5 s timeout cancels the prune's read. The prune used to
    // swallow that and return normally, so the flush after it started with the
    // ops unpruned; it now rethrows and nothing after it runs.
    @Test fun prune_rethrowsTheCancellationOfItsRead() = runBlocking {
        val remote = FakeRemote().apply { onFetchAll = { awaitCancellation() } }
        val hydrator = Hydrator(remote, store)
        WriteThrough(store).upsertTask(synced("2026-05-21T09:00:00.000Z"))
        var flushed = false
        withTimeoutOrNull(500) {
            hydrator.pruneStaleTaskOps()
            flushed = true   // what flushUnlocked does next
        }
        assertFalse("nothing after a cancelled prune may run", flushed)
    }

    // --- shared-collection item RPCs through the OUTBOX (2026-09 round 2) -------
    // Item writes on SHARED lists used to be fire-and-forget: a failed RPC (offline,
    // 5xx, RLS no-op) left the optimistic row locally and the next echo/hydrate
    // silently deleted it. They now queue as idempotent `rpc` ops.

    @Test fun collectionRpc_isQueuedThroughTheOutbox_andFlushedViaTheGateway() = runTest {
        val remote = FakeRemote()
        val write = WriteThrough(store)
        val rpc = CollectionRpcs.addItem("c1", "i1", "Milk", "2026-05-21T10:00:00.000Z")
        write.enqueueCollectionRpc("c1", rpc.fn, rpc.params)
        val op = store.pending().single()
        assertEquals(OutboxFlusher.OP_RPC, op.op)
        assertEquals(Tables.COLLECTIONS, op.recordTable)
        assertEquals("c1", op.recordId)
        OutboxFlusher(remote, store).flush("me")
        assertTrue("op dequeued after a successful rpc", store.pending().isEmpty())
        val (fn, params) = remote.rpcs.single()
        assertEquals("collection_add_item", fn)
        // Migration 056 shape: UPSERT-BY-ID on p_item.id (a replay is idempotent).
        val item = params["p_item"] as JsonObject
        assertEquals("\"i1\"", item["id"].toString())
        assertEquals("\"Milk\"", item["body"].toString())
    }

    @Test fun collectionRpc_addItem_fallsBackToTheLegacySignatureOnAPre056Server() = runTest {
        val remote = object : SyncRemote {
            val calls = mutableListOf<Pair<String, JsonObject>>()
            override suspend fun fetchAll(table: String): List<JsonObject> = emptyList()
            override suspend fun upsert(table: String, row: JsonObject, userId: String) {}
            override suspend fun delete(table: String, id: String) {}
            override suspend fun fetchSince(table: String, column: String, since: String, limit: Int): List<JsonObject> = emptyList()
            override suspend fun fetchIds(table: String, offset: Int, limit: Int): List<String> = emptyList()
            override suspend fun rpc(fn: String, params: JsonObject) {
                calls.add(fn to params)
                if ("p_item" in params) throw RpcRejected(404, "PGRST202: Could not find the function public.collection_add_item(p_collection_id, p_item)")
            }
        }
        val write = WriteThrough(store)
        write.enqueueCollectionRpc("c1", CollectionRpcs.addItem("c1", "i1", "Milk", "2026-05-21T10:00:00.000Z"))
        var rolledBack = false
        OutboxFlusher(remote, store).apply { onRpcRejected = { _, _ -> rolledBack = true } }.flush("me")
        assertEquals(2, remote.calls.size)
        assertEquals("\"i1\"", remote.calls[1].second["p_id"].toString())
        assertTrue("landed via the legacy signature — dequeued, no rollback", store.pending().isEmpty())
        assertFalse(rolledBack)
    }

    @Test fun collectionRpc_transientFailure_staysQueuedForRetry() = runTest {
        val remote = FakeRemote().apply { failRpc = true }
        val write = WriteThrough(store)
        val rpc = CollectionRpcs.setItemFlag("c1", "i1", "done", true)
        write.enqueueCollectionRpc("c1", rpc.fn, rpc.params)
        val flusher = OutboxFlusher(remote, store)
        flusher.flush("me")
        assertEquals("a 5xx / offline failure keeps the op for the next drain", 1, store.pending().size)
        remote.failRpc = false
        flusher.flush("me")
        assertTrue(store.pending().isEmpty())
        assertEquals("collection_set_item_flag", remote.rpcs.single().first)
    }

    @Test fun collectionRpc_serverRefusal_isTerminal_dequeuedAndRolledBackViaHook() = runTest {
        val remote = FakeRemote().apply { rejectRpc = 403 }
        val write = WriteThrough(store)
        val rpc = CollectionRpcs.removeItem("c1", "i1")
        write.enqueueCollectionRpc("c1", rpc.fn, rpc.params)
        // A later op on the SAME list must still run (each RPC is an independent step).
        remote.rejectRpc = null
        val rpc2 = CollectionRpcs.updateItem("c1", "i2", "Eggs")
        write.enqueueCollectionRpc("c1", rpc2.fn, rpc2.params)
        remote.rejectRpc = 403
        val rolledBack = mutableListOf<Pair<String, Int>>()
        val flusher = OutboxFlusher(remote, store).apply {
            onRpcRejected = { op, err ->
                rolledBack.add(op.recordId to err.status)
                remote.rejectRpc = null   // the second op is then accepted
            }
        }
        flusher.flush("me")
        assertEquals(listOf("c1" to 403), rolledBack)
        assertTrue("both ops gone: the refused one dropped, the next one landed", store.pending().isEmpty())
        assertEquals("collection_update_item", remote.rpcs.single().first)
    }

    @Test fun deleteCollection_cancelsItsQueuedRpcs() = runTest {
        val write = WriteThrough(store)
        val rpc = CollectionRpcs.addItem("c1", "i1", "Milk", "2026-05-21T10:00:00.000Z")
        write.enqueueCollectionRpc("c1", rpc.fn, rpc.params)
        write.deleteCollection("c1")
        val ops = store.pending()
        assertEquals(listOf("delete"), ops.map { it.op })
    }

    @Test fun rpcPayload_roundTrips() {
        val params = JsonObject(mapOf("p_collection_id" to JsonPrimitive("c1"), "p_value" to JsonPrimitive(true)))
        val decoded = OutboxFlusher.decodeRpc(OutboxFlusher.encodeRpc("collection_set_item_flag", params))
        assertEquals("collection_set_item_flag", decoded?.first)
        assertEquals(params, decoded?.second)
        assertNull(OutboxFlusher.decodeRpc("not json"))
    }

    // --- Google push stamping (2026-09 round 2) ---------------------------------

    @Test fun writeThrough_persistsTheConnectionStampFromAPush_andEnqueuesTheStampedRow() = runTest {
        val write = WriteThrough(store)
        // The coordinator's push returns the block re-stamped with BOTH ids on INSERT.
        // (a real connection id is a uuid — the row codec nulls anything else, since the
        // server column is `uuid`)
        val connId = "8b1f2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        write.pushCalBlock = { b -> b.copy(externalEventId = "evt-1", externalConnectionId = connId) }
        val t = task("t1", updatedAt = "2026-05-21T10:00:00.000Z")
        store.upsert(Tables.TASKS, t, TaskItem.serializer(), t.id, t.updatedAt)
        write.upsertCalBlock(tech.csalliance.unstuck.core.model.CalBlock(id = "b1", taskId = "t1", taskName = "T", startTime = "09:00", durationMinutes = 25, date = "2026-05-21"))
        // The push runs on the Google worker, after the local write (stage 2).
        write.googleMirror.awaitIdle()
        val stored = store.blocks().first().single()
        assertEquals("evt-1", stored.externalEventId)
        assertEquals(connId, stored.externalConnectionId)
        val last = store.pending().last { it.recordTable == Tables.CAL_BLOCKS }
        assertTrue("the queued row carries the connection id", last.payload!!.contains("\"external_connection_id\":\"$connId\""))
    }

    @Test fun writeThrough_noRestampWhenThePushReturnsNull() = runTest {
        val write = WriteThrough(store)
        write.pushCalBlock = { null }
        write.upsertCalBlock(tech.csalliance.unstuck.core.model.CalBlock(id = "b1", taskId = "t1", taskName = "T", startTime = "09:00", durationMinutes = 25, date = "2026-05-21", externalEventId = "evt-1"))
        write.googleMirror.awaitIdle()
        assertEquals("one upsert only — nothing changed", 1, store.pending().count { it.recordTable == Tables.CAL_BLOCKS })
    }

    // --- collections hydrate: a failed membership select keeps local membership ---

    private fun collectionServerRow(c: ItemCollection, ownerId: String): JsonObject =
        JsonObject(DbRowCodec.encodeCollection(c) + ("user_id" to JsonPrimitive(ownerId)))

    @Test fun hydrateCollections_membersSelectFails_keepsLocalMembersAndRole() = runTest {
        // Two shared lists cached locally with their membership: one I own (shared
        // with u2) and one shared WITH me (I'm an editor). The collections select
        // succeeds but the collection_members select throws (timeout / 5xx).
        val mine = ItemCollection(id = "c1", name = "Trip", color = "teal", items = emptyList(), sortOrder = 0, ownerId = "me", members = listOf("u2"), myRole = "owner")
        val theirs = ItemCollection(id = "c2", name = "Home", color = "indigo", items = emptyList(), sortOrder = 1, ownerId = "u3", members = listOf("me"), myRole = "editor")
        store.upsert(Tables.COLLECTIONS, mine, ItemCollection.serializer(), mine.id)
        store.upsert(Tables.COLLECTIONS, theirs, ItemCollection.serializer(), theirs.id)
        val remote = object : SyncRemote {
            override suspend fun fetchAll(table: String): List<JsonObject> = when (table) {
                Tables.COLLECTIONS -> listOf(collectionServerRow(mine.copy(name = "Trip 2026"), "me"), collectionServerRow(theirs, "u3"))
                "collection_members" -> throw RuntimeException("simulated timeout")
                else -> emptyList()
            }
            override suspend fun upsert(table: String, row: JsonObject, userId: String) {}
            override suspend fun delete(table: String, id: String) {}
            override suspend fun rpc(fn: String, params: JsonObject) {}
            override suspend fun fetchSince(table: String, column: String, since: String, limit: Int): List<JsonObject> = emptyList()
            override suspend fun fetchIds(table: String, offset: Int, limit: Int): List<String> = emptyList()
        }
        Hydrator(remote, store).hydrateCollections("me")
        val after = store.collections().first().associateBy { it.id }
        // The server's row content still lands…
        assertEquals("Trip 2026", after["c1"]!!.name)
        // …but the membership is carried over, not stripped (the old code flipped
        // both lists back to "solo": the owner resumed whole-row upserts over
        // members' atomic edits; the member lost its editor role).
        assertEquals(listOf("u2"), after["c1"]!!.members)
        assertEquals("owner", after["c1"]!!.myRole)
        assertEquals(listOf("me"), after["c2"]!!.members)
        assertEquals("editor", after["c2"]!!.myRole)
    }

    @Test fun hydrateCollections_membersSelectSucceeds_isAuthoritative() = runTest {
        // Control: a SUCCESSFUL (empty) membership select really does clear the
        // membership — the carry-over only applies to a failed select.
        val mine = ItemCollection(id = "c1", name = "Trip", color = "teal", items = emptyList(), sortOrder = 0, ownerId = "me", members = listOf("u2"), myRole = "owner")
        store.upsert(Tables.COLLECTIONS, mine, ItemCollection.serializer(), mine.id)
        val remote = FakeRemote().apply { serverRows[Tables.COLLECTIONS] = listOf(collectionServerRow(mine, "me")) }
        Hydrator(remote, store).hydrateCollections("me")
        val c1 = store.collections().first().single()
        assertEquals(emptyList<String>(), c1.members)
        assertEquals("owner", c1.myRole)
    }

    // --- collections hydrate vs queued list ops (audit 2026-09-22 C8) ---
    // It now runs on every membership event, not only after a flush, so what is
    // still queued must survive it. Ported from the iOS
    // HydratorPendingPreservationTests (build 81).

    private fun memberRow(collectionId: String, userId: String, role: String = "editor") = JsonObject(
        mapOf(
            "id" to JsonPrimitive("$collectionId-$userId"), "collection_id" to JsonPrimitive(collectionId),
            "user_id" to JsonPrimitive(userId), "role" to JsonPrimitive(role),
        ),
    )

    private fun list(id: String, members: List<String> = emptyList(), items: List<CollectionItem> = emptyList()) =
        ItemCollection(id = id, name = id, color = "indigo", items = items, sortOrder = 0, ownerId = "me", members = members, myRole = "owner")

    @Test fun hydrateCollections_aQueuedListKeepsItsContentButTakesFreshMembership() = runTest {
        // The owner's own edit is queued (1.5 s debounce) when a partner's join
        // fires the members hydrate. Keeping the whole local row kept members = []
        // too, so the list still read as unshared and nothing re-read it.
        val eggs = CollectionItem(id = "i-local", body = "eggs", at = "2026-05-21T10:01:00.000Z")
        WriteThrough(store).upsertCollection(list("c1", items = listOf(eggs)))
        val remote = FakeRemote().apply {
            serverRows[Tables.COLLECTIONS] = listOf(collectionServerRow(list("c1"), "me"))
            serverRows["collection_members"] = listOf(memberRow("c1", "p1"))
        }

        Hydrator(remote, store).hydrateCollections("me")

        val c1 = store.collections().first().single()
        assertEquals("the queued edit's content is kept", listOf("i-local"), c1.items.map { it.id })
        assertEquals("but membership is server truth, not a local edit", listOf("p1"), c1.members)
        assertEquals("owner", c1.myRole)
    }

    @Test fun hydrateCollections_aQueuedItemRpcKeepsItsOptimisticItem() = runTest {
        val milk = CollectionItem(id = "i1", body = "Milk", at = "2026-05-21T10:00:00.000Z")
        store.upsert(Tables.COLLECTIONS, list("c1", members = listOf("p1"), items = listOf(milk)), ItemCollection.serializer(), "c1")
        WriteThrough(store).enqueueCollectionRpc("c1", CollectionRpcs.addItem("c1", "i1", "Milk", milk.at))
        val remote = FakeRemote().apply {
            serverRows[Tables.COLLECTIONS] = listOf(collectionServerRow(list("c1"), "me"))
            serverRows["collection_members"] = listOf(memberRow("c1", "p1"))
        }

        Hydrator(remote, store).hydrateCollections("me")

        val c1 = store.collections().first().single()
        assertEquals("the item still waiting on its rpc is not reverted", listOf("i1"), c1.items.map { it.id })
        assertEquals(listOf("p1"), c1.members)
    }

    @Test fun hydrateCollections_aListWithAQueuedDeleteIsNotResurrected() = runTest {
        store.upsert(Tables.COLLECTIONS, list("c1"), ItemCollection.serializer(), "c1")
        WriteThrough(store).deleteCollection("c1")
        val remote = FakeRemote().apply { serverRows[Tables.COLLECTIONS] = listOf(collectionServerRow(list("c1"), "me")) }

        Hydrator(remote, store).hydrateCollections("me")

        assertTrue(store.collections().first().isEmpty())
    }

    // The debounced flush acks a list edit and a list delete BETWEEN the hydrate's
    // collections read and its replace: the snapshot predates both and nothing is
    // queued any more. The edit must not revert (the owner's next whole-row
    // upsert would be built on the reverted row and delete it on the server), and
    // the list must not come back.
    @Test fun hydrateCollections_aListWriteAckedWhileTheHydrateReadsIsNotReverted() = runTest {
        val milk = CollectionItem(id = "i-milk", body = "milk", at = "2026-05-21T10:01:00.000Z")
        store.upsert(Tables.COLLECTIONS, list("c2"), ItemCollection.serializer(), "c2")
        val write = WriteThrough(store)
        write.upsertCollection(list("c1", items = listOf(milk)))
        write.deleteCollection("c2")
        val remote = FakeRemote().apply {
            serverRows[Tables.COLLECTIONS] = listOf(collectionServerRow(list("c1"), "me"), collectionServerRow(list("c2"), "me"))
            onFetchAll = { table -> if (table == "collection_members") store.pending().forEach { store.dequeue(it.seq) } }
        }

        Hydrator(remote, store).hydrateCollections("me")

        assertTrue("both ops were acked mid-hydrate", store.pending().isEmpty())
        val byId = store.collections().first().associateBy { it.id }
        assertEquals("an edit acked mid-read is not reverted to the older snapshot", listOf("i-milk"), byId["c1"]?.items?.map { it.id })
        assertNull("a delete acked mid-read stays deleted", byId["c2"])
    }

    // Build a server-shaped row JsonObject (DbRowCodec encodes the row; decodeTask
    // reads updated_at back out — which is what pruneStaleTaskOps relies on).
    private fun serverRow(t: TaskItem): JsonObject =
        Json.parseToJsonElement(DbRowCodec.encodeTask(t).toString()) as JsonObject
}
