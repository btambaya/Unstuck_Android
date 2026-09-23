package tech.csalliance.unstuck.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
import tech.csalliance.unstuck.core.logic.occurrenceId
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.OutboxEntity
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.sync.WriteThrough.MintOutcome

/**
 * Stage 2 step 1 on Android — the insert path, rules G and H, the cal_blocks pull
 * signal (deterministic-occurrence-ids.md §3, §4.3; "same id for same day", Ahmad
 * 2026-09-23). Real LocalStore (in-memory Room, synchronous executors), real
 * WriteThrough / OutboxFlusher / Hydrator, and a fake PostgREST with the prod
 * semantics of the two new requests (FakeInsertServer). Mirrors iOS build 85's
 * WriteThroughTests / OutboxFlusherTests / HydratorPendingPreservationTests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InsertPathTest {
    private lateinit var db: UnstuckDatabase
    private lateinit var store: LocalStore
    private lateinit var server: FakeInsertServer
    private val uid = "user-1"
    private val taskId = "3f1c2a9e-5b7d-4c21-9a0e-7d2b1c4e8f60"

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnstuckDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        store = LocalStore(db)
        server = FakeInsertServer()
    }

    @After fun teardown() = db.close()

    private suspend fun seedTask() {
        val t = TaskItem(id = taskId, name = "Stretch", estimateMin = 25, createdAt = "2026-09-20T08:00:00Z", updatedAt = "2026-09-20T08:00:00Z")
        store.upsert(Tables.TASKS, t, TaskItem.serializer(), t.id, t.updatedAt)
    }

    private fun occ(date: String, start: String = "07:00", id: String = occurrenceId(taskId, date), done: Boolean = false, skipped: Boolean = false, event: String? = null) =
        CalBlock(id = id, taskId = taskId, taskName = "Stretch", startTime = start, durationMinutes = 25, date = date,
            kind = CalBlockKind.TASK, done = done, skipped = skipped, externalEventId = event)

    private suspend fun local(id: String) = store.getOne(Tables.CAL_BLOCKS, id, CalBlock.serializer())
    private suspend fun ops(): List<OutboxEntity> = store.pending()
    private fun payload(op: OutboxEntity): JsonObject = Json.parseToJsonElement(op.payload!!).jsonObject

    // The Google worker runs in the test's own scope: advanceUntilIdle runs it (it
    // skips backgroundScope work), and runTest waits for it to drain.
    private fun TestScope.write(): WriteThrough = WriteThrough(store, this)
    private fun flusher(w: WriteThrough) = OutboxFlusher(server, store, w.mirrorGate)

    // ── WriteThrough.insertCalBlockIfAbsent (rule A's backstop) ─────────────────

    @Test fun aMintOverAnExistingRowWritesNothing() = runTest {
        val w = write()
        val moved = occ("2026-09-24").copy(date = "2026-09-26")   // id(24th), moved to the 26th
        store.upsert(Tables.CAL_BLOCKS, moved, CalBlock.serializer(), moved.id)
        assertEquals(MintOutcome.HELD, w.insertCalBlockIfAbsent(occ("2026-09-24"), retimeIfTaken = false))
        assertEquals(MintOutcome.HELD, w.insertCalBlockIfAbsent(occ("2026-09-24"), retimeIfTaken = true))
        assertEquals("2026-09-26", local(moved.id)!!.date)
        assertTrue(ops().isEmpty())
    }

    @Test fun aMintQueuesTheRequestedKind_clampedAndWaitingOnItsTask() = runTest {
        val w = write()
        assertEquals(MintOutcome.INSERTED, w.insertCalBlockIfAbsent(occ("2026-09-24").copy(durationMinutes = 2), retimeIfTaken = false))
        assertEquals(MintOutcome.INSERTED, w.insertCalBlockIfAbsent(occ("2026-09-25"), retimeIfTaken = true))
        val (a, b) = ops()
        assertEquals(OutboxFlusher.OP_INSERT, a.op)
        assertEquals(OutboxFlusher.OP_INSERT_OR_RETIME, b.op)
        assertEquals(taskId, a.dependsOn)
        assertEquals(5, (payload(a)["duration_minutes"] as JsonPrimitive).content.toInt())
        assertEquals(5, local(occurrenceId(taskId, "2026-09-24"))!!.durationMinutes)
    }

    /** Rule H locally (the iOS build 85 review fix): a USER's mint whose id is already
     *  that day's open occurrence moves it — start and length only — and queues
     *  insert_or_retime so the server does the same. */
    @Test fun aUserMintOverTheDaysOpenOccurrenceRetimesIt() = runTest {
        val w = write()
        val open = occ("2026-09-24", start = "07:00", event = "evt-9")
        store.upsert(Tables.CAL_BLOCKS, open, CalBlock.serializer(), open.id)
        assertEquals(MintOutcome.HELD, w.insertCalBlockIfAbsent(occ("2026-09-24", start = "09:00"), retimeIfTaken = false))
        assertEquals(MintOutcome.RETIMED, w.insertCalBlockIfAbsent(occ("2026-09-24", start = "09:00").copy(durationMinutes = 40), retimeIfTaken = true))
        val row = local(open.id)!!
        assertEquals("09:00", row.startTime)
        assertEquals(40, row.durationMinutes)
        assertEquals("the mapping stays", "evt-9", row.externalEventId)
        assertEquals(OutboxFlusher.OP_INSERT_OR_RETIME, ops().single().op)
        assertEquals(MintOutcome.ALREADY_THERE, w.insertCalBlockIfAbsent(occ("2026-09-24", start = "09:00").copy(durationMinutes = 40), retimeIfTaken = true))
        assertEquals(1, ops().size)
    }

    @Test fun aUserMintNeverRetimesADoneOrSkippedDay() = runTest {
        val w = write()
        val done = occ("2026-09-24", done = true)
        val skipped = occ("2026-09-25", skipped = true)
        store.upsert(Tables.CAL_BLOCKS, done, CalBlock.serializer(), done.id)
        store.upsert(Tables.CAL_BLOCKS, skipped, CalBlock.serializer(), skipped.id)
        assertEquals(MintOutcome.HELD, w.insertCalBlockIfAbsent(occ("2026-09-24", start = "09:00"), retimeIfTaken = true))
        assertEquals(MintOutcome.HELD, w.insertCalBlockIfAbsent(occ("2026-09-25", start = "09:00"), retimeIfTaken = true))
        assertTrue(ops().isEmpty())
    }

    /** Two top-ups that read the store before either wrote: one row, one op. */
    @Test fun twoConcurrentMintsOfOneIdQueueOneOp() = runTest {
        val w = write()
        val a = async { w.insertCalBlockIfAbsent(occ("2026-09-24"), retimeIfTaken = false) }
        val b = async { w.insertCalBlockIfAbsent(occ("2026-09-24"), retimeIfTaken = false) }
        val outcomes = listOf(a.await(), b.await())
        assertEquals(1, outcomes.count { it == MintOutcome.INSERTED })
        assertEquals(1, outcomes.count { it == MintOutcome.HELD })
        assertEquals(1, ops().size)
    }

    @Test fun aDeleteCancelsAQueuedMint() = runTest {
        val w = write()
        w.insertCalBlockIfAbsent(occ("2026-09-24"), retimeIfTaken = true)
        w.deleteCalBlock(occurrenceId(taskId, "2026-09-24"))
        assertEquals(listOf("delete"), ops().map { it.op })
        assertFalse(w.mirrorGate.isMirrorWanted(occurrenceId(taskId, "2026-09-24")))
    }

    /** Hazard d: "Never" then "Daily" — the delete goes out before the re-mint, which
     *  then inserts (the server row is gone by then). */
    @Test fun aDeleteThenAReMintKeepOrderAndTheDayComesBack() = runTest {
        seedTask()
        val w = write()
        val x = occ("2026-09-24")
        store.upsert(Tables.CAL_BLOCKS, x, CalBlock.serializer(), x.id)
        server.putBlock(x)
        w.deleteCalBlock(x.id)
        assertEquals(MintOutcome.INSERTED, w.insertCalBlockIfAbsent(occ("2026-09-24", start = "09:00"), retimeIfTaken = true))
        assertEquals(listOf("delete", OutboxFlusher.OP_INSERT_OR_RETIME), ops().map { it.op })
        flusher(w).flush(uid)
        assertEquals(listOf("delete ${x.id}", "insert ${x.id} inserted"), server.log)
        assertEquals("09:00", server.block(x.id)!!.startTime)
    }

    // ── pending semantics ────────────────────────────────────────────────────

    @Test fun aQueuedMintIsAPendingWriteEverywhere() = runTest {
        val w = write()
        val x = occ("2026-09-24", start = "09:00")
        w.insertCalBlockIfAbsent(x, retimeIfTaken = true)
        // The server has an older copy of the id, and nothing else.
        server.putBlock(x.copy(startTime = "07:00"))
        Hydrator(server, store).hydrateNonCursorTables()
        assertEquals("the hydrate keeps the minted row", "09:00", local(x.id)!!.startTime)
        assertEquals(0, store.retainIds(Tables.CAL_BLOCKS, emptySet()))
        assertNotNull("the catch-up sees a pending write", store.latestPendingUpsert(Tables.CAL_BLOCKS, x.id))
    }

    // ── OutboxFlusher: the insert family ─────────────────────────────────────

    @Test fun aMintGoesOutAsInsertIfAbsent() = runTest {
        seedTask()
        val w = write()
        w.insertCalBlockIfAbsent(occ("2026-09-24"), retimeIfTaken = false)
        flusher(w).flush(uid)
        assertEquals(listOf("insert ${occurrenceId(taskId, "2026-09-24")} inserted"), server.log)
        assertTrue(ops().isEmpty())
    }

    /** Rule H on the server: the other device's open occurrence of that day moves to
     *  this device's time — and keeps ITS Google mapping, which the local copy takes. */
    @Test fun anIgnoredUserMintSendsTheConditionalRetimeAndShowsTheServerRow() = runTest {
        seedTask()
        val w = write()
        server.putBlock(occ("2026-09-24", start = "07:00", event = "their-evt"))
        w.insertCalBlockIfAbsent(occ("2026-09-24", start = "16:00").copy(durationMinutes = 45), retimeIfTaken = true)
        val resolved = mutableListOf<InsertResolution>()
        val f = flusher(w).apply { onInsertResolved = { resolved += it } }
        f.flush(uid)
        val id = occurrenceId(taskId, "2026-09-24")
        assertEquals(listOf("insert $id ignored", "retime $id retimed"), server.log)
        assertEquals("16:00", server.block(id)!!.startTime)
        assertEquals(45, server.block(id)!!.durationMinutes)
        assertEquals("their-evt", server.block(id)!!.externalEventId)
        assertEquals("their-evt", local(id)!!.externalEventId)
        assertEquals(InsertOutcome.RETIMED, resolved.single().outcome)
    }

    @Test fun anIgnoredPlainInsertNeverRetimes() = runTest {
        seedTask()
        val w = write()
        server.putBlock(occ("2026-09-24", start = "07:00"))
        w.insertCalBlockIfAbsent(occ("2026-09-24", start = "16:00"), retimeIfTaken = false)
        val resolved = mutableListOf<InsertResolution>()
        flusher(w).apply { onInsertResolved = { resolved += it } }.flush(uid)
        assertEquals(listOf("insert ${occurrenceId(taskId, "2026-09-24")} ignored"), server.log)
        assertEquals("07:00", server.block(occurrenceId(taskId, "2026-09-24"))!!.startTime)
        assertEquals(InsertOutcome.IGNORED, resolved.single().outcome)
    }

    /** First write wins where the day's occurrence moved or finished elsewhere. */
    @Test fun theRetimeNeverMovesAMovedOrFinishedRow() = runTest {
        seedTask()
        val w = write()
        server.putBlock(occ("2026-09-24").copy(date = "2026-09-26"))
        server.putBlock(occ("2026-09-25", done = true))
        w.insertCalBlockIfAbsent(occ("2026-09-24", start = "16:00"), retimeIfTaken = true)
        w.insertCalBlockIfAbsent(occ("2026-09-25", start = "16:00"), retimeIfTaken = true)
        val resolved = mutableListOf<InsertResolution>()
        flusher(w).apply { onInsertResolved = { resolved += it } }.flush(uid)
        assertEquals(listOf(InsertOutcome.IGNORED, InsertOutcome.IGNORED), resolved.map { it.outcome })
        assertEquals("2026-09-26", server.block(occurrenceId(taskId, "2026-09-24"))!!.date)
        assertEquals("07:00", server.block(occurrenceId(taskId, "2026-09-25"))!!.startTime)
    }

    /** A mint an older build queued heals like an upsert: clamped, ASCII digits. */
    @Test fun aQueuedMintIsClampedAndAsciiOnTheWire() = runTest {
        seedTask()
        val id = occurrenceId(taskId, "2026-09-24")
        val row = DbRowCodec.encodeCalBlock(occ("2026-09-24").copy(durationMinutes = 2, date = "٢٠٢٦-٠٩-٢٤", startTime = "٠٧:٠٠"))
        store.enqueue(OutboxEntity(op = OutboxFlusher.OP_INSERT, recordTable = Tables.CAL_BLOCKS, recordId = id, payload = row.toString(), dependsOn = taskId, createdAt = 0))
        OutboxFlusher(server, store).flush(uid)
        val sent = server.block(id)!!
        assertEquals(5, sent.durationMinutes)
        assertEquals("2026-09-24", sent.date)
        assertEquals("07:00", sent.startTime)
    }

    @Test fun anInsertIsNeverCoalescedWithAnUpsert() {
        fun op(seq: Long, kind: String) = OutboxEntity(seq = seq, op = kind, recordTable = Tables.CAL_BLOCKS, recordId = "x", payload = "{}", createdAt = 0)
        assertTrue(OutboxFlusher.supersededUpsertSeqs(listOf(op(1, "delete"), op(2, OutboxFlusher.OP_INSERT))).isEmpty())
        assertTrue(OutboxFlusher.supersededUpsertSeqs(listOf(op(1, OutboxFlusher.OP_INSERT), op(2, "upsert"))).isEmpty())
        assertTrue(OutboxFlusher.supersededUpsertSeqs(listOf(op(1, "upsert"), op(2, OutboxFlusher.OP_INSERT_OR_RETIME))).isEmpty())
        assertTrue(OutboxFlusher.supersededUpsertSeqs(listOf(op(1, "upsert"), op(2, OutboxFlusher.OP_INSERT), op(3, "upsert"))).isEmpty())
    }

    /** A delete that fails holds the re-mint of its id in that pass. */
    @Test fun aReMintNeverOvertakesItsDelete() = runTest {
        seedTask()
        val w = write()
        val x = occ("2026-09-24")
        store.upsert(Tables.CAL_BLOCKS, x, CalBlock.serializer(), x.id)
        server.putBlock(x)
        w.deleteCalBlock(x.id)
        w.insertCalBlockIfAbsent(occ("2026-09-24", start = "09:00"), retimeIfTaken = true)
        server.failOnce += "delete ${x.id}"
        val f = flusher(w)
        f.flush(uid)
        assertTrue("nothing went out", server.log.isEmpty())
        assertEquals(2, ops().size)
        f.flush(uid)
        assertEquals(listOf("delete ${x.id}", "insert ${x.id} inserted"), server.log)
        assertEquals("09:00", server.block(x.id)!!.startTime)
    }

    /** The hook fires after the drain let go of its lock: a listener that flushes
     *  again does not deadlock. */
    @Test fun theResolvedHookMayFlushAgain() = runTest {
        seedTask()
        val w = write()
        val f = flusher(w)
        val again = CompletableDeferred<Unit>()
        f.onInsertResolved = { launch { f.flush(uid); again.complete(Unit) } }
        w.insertCalBlockIfAbsent(occ("2026-09-24"), retimeIfTaken = false)
        f.flush(uid)
        advanceUntilIdle()
        assertTrue(again.isCompleted)
    }

    @Test fun theDefaultInsertNeverFallsBackToAnUpsert() = runTest {
        seedTask()
        val upserts = mutableListOf<String>()
        val bare = object : SyncRemote {
            override suspend fun fetchAll(table: String) = emptyList<JsonObject>()
            override suspend fun upsert(table: String, row: JsonObject, userId: String) { upserts += table }
            override suspend fun delete(table: String, id: String) = Unit
            override suspend fun rpc(fn: String, params: JsonObject) = Unit
            override suspend fun fetchSince(table: String, column: String, since: String, limit: Int) = emptyList<JsonObject>()
            override suspend fun fetchIds(table: String, offset: Int, limit: Int) = emptyList<String>()
        }
        assertTrue(runCatching { bare.insertIfAbsent(Tables.CAL_BLOCKS, JsonObject(emptyMap()), uid) }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { bare.retimeIfOpen(Tables.CAL_BLOCKS, "x", "2026-09-24", "09:00", 25) }.exceptionOrNull() is UnsupportedOperationException)
        val w = write()
        w.insertCalBlockIfAbsent(occ("2026-09-24"), retimeIfTaken = true)
        OutboxFlusher(bare, store).flush(uid)
        assertTrue("never sent as an upsert", upserts.isEmpty())
        assertEquals("the mint stays queued", 1, ops().size)
    }

    // ── rule G ───────────────────────────────────────────────────────────────

    /** The fake Google: records every call; INSERT mints evt-<n>. */
    private class Google {
        val calls = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        private var n = 0
        suspend fun push(b: CalBlock): CalBlock? {
            gate?.await()
            if (b.externalEventId != null) { calls += "patch ${b.externalEventId} ${b.startTime}"; return null }
            val id = "evt-${++n}"
            calls += "insert $id ${b.startTime}"
            return b.copy(externalEventId = id, externalConnectionId = "8b1f2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d")
        }
        /** As SyncCoordinator.pushBlockDelete: a block never pushed has no event. */
        suspend fun delete(b: CalBlock) { b.externalEventId?.let { calls += "delete $it" } }
    }

    private fun wireGoogle(w: WriteThrough, g: Google, f: OutboxFlusher) {
        w.pushCalBlock = { g.push(it) }
        w.pushCalBlockDelete = { g.delete(it) }
        f.onInsertResolved = { r -> if (r.mirrorWanted) w.queueConfirmedMirror(r.rowId) }
        w.mirrorGate.onAwaitedRowLanded = { w.googleMirror.queueLanded(it) }
    }

    @Test fun aMintIsMirroredOnceItsInsertIsConfirmed_fromTheFreshRow() = runTest {
        seedTask()
        val w = write(); val f = flusher(w); val g = Google(); wireGoogle(w, g, f)
        val id = occurrenceId(taskId, "2026-09-24")
        w.insertCalBlockIfAbsent(occ("2026-09-24", start = "09:00"), retimeIfTaken = true)
        // An edit before the flush: its push waits on the insert too.
        w.upsertCalBlock(local(id)!!.copy(startTime = "10:00"))
        advanceUntilIdle()
        assertTrue("nothing reaches Google before the server confirms", g.calls.isEmpty())
        assertTrue(w.mirrorGate.isMirrorWanted(id))
        f.flush(uid)
        w.googleMirror.awaitIdle()
        assertEquals(listOf("insert evt-1 10:00"), g.calls)
        assertEquals("evt-1", local(id)!!.externalEventId)
        assertEquals("10:00", local(id)!!.startTime)
    }

    @Test fun anIgnoredMintIsNeverMirrored() = runTest {
        seedTask()
        val w = write(); val f = flusher(w); val g = Google(); wireGoogle(w, g, f)
        val id = occurrenceId(taskId, "2026-09-24")
        server.putBlock(occ("2026-09-24", event = "their-evt"))
        w.insertCalBlockIfAbsent(occ("2026-09-24"), retimeIfTaken = false)
        f.flush(uid)
        w.googleMirror.awaitIdle()
        assertTrue(g.calls.isEmpty())
        assertFalse(w.mirrorGate.isMirrorWanted(id))
    }

    /** The stamp writes the two mapping columns onto the row as it is AFTER the
     *  Google call: an edit made meanwhile survives, and the queued row has both. */
    @Test fun theStampLandsOnTheFreshRow() = runTest {
        val w = write(); val g = Google()
        w.pushCalBlock = { g.push(it) }
        val x = occ("2026-09-24").copy(id = "b1")
        g.gate = CompletableDeferred()
        w.upsertCalBlock(x)
        advanceUntilIdle()
        w.upsertCalBlock(local("b1")!!.copy(startTime = "11:30"))
        g.gate!!.complete(Unit)
        w.googleMirror.awaitIdle()
        val row = local("b1")!!
        assertEquals("11:30", row.startTime)
        assertEquals("evt-1", row.externalEventId)
        val last = payload(ops().last())
        assertEquals("11:30", (last["start_time"] as JsonPrimitive).content)
        assertEquals("evt-1", (last["external_event_id"] as JsonPrimitive).content)
    }

    /** A push whose row went during the Google call removes the event it made —
     *  the next Google pull would show it as a meeting (the iOS build 85 fix). */
    @Test fun aStampRefusedForAGoneRowDeletesTheNewEvent() = runTest {
        val w = write(); val g = Google()
        w.pushCalBlock = { g.push(it) }
        w.pushCalBlockDelete = { g.delete(it) }
        g.gate = CompletableDeferred()
        w.upsertCalBlock(occ("2026-09-24").copy(id = "b1"))
        advanceUntilIdle()
        w.deleteCalBlock("b1")
        g.gate!!.complete(Unit)
        w.googleMirror.awaitIdle()
        assertEquals(listOf("insert evt-1 07:00", "delete evt-1"), g.calls)
        assertNull(local("b1"))
    }

    /** Every save but the stamp carries the row's CURRENT mapping: a rewrite built
     *  from a snapshot taken before the stamp no longer nulls it. */
    @Test fun aSnapshotRewriteKeepsTheStampedMapping() = runTest {
        val w = write()
        val stamped = occ("2026-09-24", event = "evt-7").copy(id = "b1", externalConnectionId = "8b1f2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d")
        store.upsert(Tables.CAL_BLOCKS, stamped, CalBlock.serializer(), "b1")
        w.upsertCalBlock(stamped.copy(externalEventId = null, externalConnectionId = null, startTime = "08:00"))
        assertEquals("evt-7", local("b1")!!.externalEventId)
        assertEquals("evt-7", (payload(ops().single())["external_event_id"] as JsonPrimitive).content)
    }

    /** Hazard d's echo race: the confirmed push finds its row missing (its own
     *  delete's echo landed after the re-mint) and goes out once the row is back. */
    @Test fun aConfirmedPushWaitsForItsRowToComeBack() = runTest {
        seedTask()
        val w = write(); val f = flusher(w); val g = Google(); wireGoogle(w, g, f)
        val x = occ("2026-09-24")
        w.insertCalBlockIfAbsent(x, retimeIfTaken = true)
        server.beforeWrite = { verb, id -> if (verb == "insert") store.delete(Tables.CAL_BLOCKS, id) }   // the late DELETE echo
        f.flush(uid)
        w.googleMirror.awaitIdle()
        assertTrue(g.calls.isEmpty())
        assertTrue(w.mirrorGate.isAwaitingRow(x.id))
        store.upsert(Tables.CAL_BLOCKS, x, CalBlock.serializer(), x.id)   // the INSERT echo
        w.mirrorGate.rowLanded(x.id)
        w.googleMirror.awaitIdle()
        assertEquals(listOf("insert evt-1 07:00"), g.calls)
    }

    // ── the cal_blocks pull signal ───────────────────────────────────────────

    @Test fun onlyASuccessfulCalBlocksReadStampsThePull() = runTest {
        val failing = object : SyncRemote by server {
            override suspend fun fetchAll(table: String): List<JsonObject> =
                if (table == Tables.CAL_BLOCKS) throw java.io.IOException("offline") else server.fetchAll(table)
        }
        val h = Hydrator(failing, store)
        h.hydrateNonCursorTables()
        assertNull("a failed read leaves the top-up gate shut", h.calBlocksPull)
        val ok = Hydrator(server, store)
        server.putBlock(occ("2026-09-24"))
        ok.hydrateNonCursorTables()
        assertEquals(1, ok.calBlocksPull!!.rowCount)
        assertFalse(ok.calBlocksPull!!.mayBeTruncated)
        val first = ok.calBlocksPull!!.seq
        ok.hydrateNonCursorTables()
        assertTrue(ok.calBlocksPull!!.seq > first)
        assertTrue(Hydrator.CalBlocksPull(9, 1000).mayBeTruncated)
        ok.resetCalBlocksPull()
        assertNull(ok.calBlocksPull)
    }
}
