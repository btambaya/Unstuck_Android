package tech.csalliance.unstuck.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.core.model.CollectionItem
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.OutboxEntity
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase

/**
 * THE GAP TEST. Realtime goes down, the server changes underneath us, realtime
 * comes back — and the client must converge WITHOUT a relaunch.
 *
 * postgres_changes has no replay and a channel can report SUBSCRIBED while being
 * permanently deaf, so this is the behaviour the whole freshness layer exists to
 * guarantee. Everything here runs against a REAL LocalStore (in-memory Room), the
 * REAL Hydrator / CatchUpPuller / FreshnessOwner, and a fake PostgREST that
 * answers `fetchSince` and `fetchIds` the way the server does (ordered, strictly
 * greater than, bounded, id-only).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatchUpConvergenceTest {

    private lateinit var db: UnstuckDatabase
    private lateinit var store: LocalStore
    private lateinit var remote: FakeServer
    private lateinit var hydrator: Hydrator
    private lateinit var puller: CatchUpPuller
    private val cursors = InMemorySyncCursors()

    /** Wall clock the whole engine reads, so a test can "wait" without waiting. */
    private var nowMs = 1_800_000_000_000L   // 2027-01-15T08:00:00Z-ish

    private val uid = "user-1"

    /** A fake PostgREST over a mutable row set, counting what was asked for so a
     *  test can prove the catch-up pulled a DELTA and not the whole table. */
    private class FakeServer : SyncRemote {
        val rows: MutableMap<String, MutableList<JsonObject>> = mutableMapOf()
        val fetchAllCalls = mutableListOf<String>()
        val fetchSinceCalls = mutableListOf<Triple<String, String, String>>()
        val idCalls = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var failIds = false
        /** The NEXT collections full read waits on this (then it is cleared), so a
         *  test can pile more collections hydrates up behind one in flight. */
        var collectionsGate: CompletableDeferred<Unit>? = null
        /** Full reads of a table that fail before one succeeds. */
        val failNextFetchAll = mutableMapOf<String, Int>()
        /** Runs as a full read of a table starts (a write landing mid-read). */
        var onFetchAll: (suspend (String) -> Unit)? = null

        fun table(t: String) = rows.getOrPut(t) { mutableListOf() }
        fun put(t: String, row: JsonObject) {
            val id = (row["id"] as? JsonPrimitive)?.content
            table(t).removeAll { (it["id"] as? JsonPrimitive)?.content == id }
            table(t).add(row)
        }
        fun remove(t: String, id: String) {
            table(t).removeAll { (it["id"] as? JsonPrimitive)?.content == id }
        }

        override suspend fun fetchAll(table: String): List<JsonObject> {
            fetchAllCalls += table
            onFetchAll?.invoke(table)
            if (table == Tables.COLLECTIONS) collectionsGate?.let { collectionsGate = null; it.await() }
            val left = failNextFetchAll[table] ?: 0
            if (left > 0) {
                failNextFetchAll[table] = left - 1
                throw RuntimeException("simulated timeout")
            }
            return rows[table].orEmpty().toList()
        }
        override suspend fun fetchSince(table: String, column: String, since: String, limit: Int): List<JsonObject> {
            fetchSinceCalls += Triple(table, column, since)
            gate?.await()
            return FakeRemoteSupport.since(rows[table].orEmpty(), column, since, limit)
        }
        override suspend fun fetchIds(table: String, offset: Int, limit: Int): List<String> {
            idCalls += table
            if (failIds) throw RuntimeException("offline")
            return FakeRemoteSupport.ids(rows[table].orEmpty(), offset, limit)
        }
        override suspend fun upsert(table: String, row: JsonObject, userId: String) = Unit
        override suspend fun delete(table: String, id: String) = Unit
        override suspend fun rpc(fn: String, params: JsonObject) = Unit
    }

    @Before fun setup() {
        // Synchronous Room executors: every suspend DAO call completes on the
        // calling thread, so the engine runs entirely on the test scheduler and
        // these tests are deterministic rather than racing a background executor.
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnstuckDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(java.util.concurrent.Executor { it.run() })
            .setTransactionExecutor(java.util.concurrent.Executor { it.run() })
            .build()
        store = LocalStore(db)
        remote = FakeServer()
        hydrator = Hydrator(remote, store).apply { zoneId = { "Europe/London" } }
        puller = CatchUpPuller(remote, store, cursors, now = { nowMs }, log = {})
    }

    @After fun teardown() = db.close()

    // ── harness ─────────────────────────────────────────────────────────────

    private class Owner(val freshness: FreshnessOwner, val rebuilds: () -> Int)

    /** The freshness owner wired exactly as SyncCoordinator wires it (full hydrate
     *  when there are no cursors, cursor catch-up otherwise), minus Supabase. */
    private fun owner(scope: CoroutineScope): Owner {
        var rebuilds = 0
        val f = FreshnessOwner(
            scope = scope,
            currentUserId = { uid },
            runFullHydrate = { u -> val max = hydrator.hydrate(u); puller.seedCursors(u, max); max.isNotEmpty() },
            runCatchUp = { u, sweep ->
                val out = puller.catchUp(u)
                hydrator.hydrateNonCursorTables()
                out.copy(deleted = if (sweep) puller.reconcileDeletions(u) else 0)
            },
            needsFullHydrate = { u -> !puller.hasCursors(u) },
            rebuildSubscriptions = { rebuilds++ },
            now = { nowMs },
            log = {},
        )
        return Owner(f) { rebuilds }
    }

    private fun task(id: String, updatedAt: String, name: String = "T") = TaskItem(
        id = id, name = name, estimateMin = 25,
        createdAt = "2027-01-15T07:00:00.000Z", updatedAt = updatedAt,
    )

    private fun serverTask(id: String, updatedAt: String, name: String = "T") {
        remote.put(Tables.TASKS, DbRowCodec.encodeTask(task(id, updatedAt, name)))
    }

    private suspend fun localTasks(): List<TaskItem> = store.tasks().first().sortedBy { it.id }

    /** The reactive reads hop to Dispatchers.Default (LocalStore.observe), which the
     *  test scheduler does not drive — so a collector assertion settles in real time.
     *  Bounded, and fast in practice (a few ms). */
    private fun kotlinx.coroutines.test.TestScope.settleUntil(timeoutMs: Long = 3_000, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            testScheduler.advanceUntilIdle()
            if (predicate()) return true
            Thread.sleep(5)
        }
        return predicate()
    }

    // ── 1. the gap ──────────────────────────────────────────────────────────

    @Test fun gapWithSubscriptionDown_convergesWithoutRelaunch() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val o = owner(scope)

        // Cold start: no cursors → full hydrate, and the marks are seeded from the
        // SERVER stamps that hydrate saw.
        serverTask("a", "2027-01-15T08:00:00.000Z", "A")
        serverTask("b", "2027-01-15T08:00:00.000Z", "B")
        o.freshness.requestAndWait(FreshnessTrigger.COLD_START)
        assertEquals(listOf("A", "B"), localTasks().map { it.name })
        assertTrue("a full hydrate must seed the cursors", puller.hasCursors(uid))

        // A live collector, started BEFORE the gap: it must see the catch-up's rows
        // without being re-created. This is the per-table version counter (bd3b5a3)
        // — data that lands in Room but never bumps it never reaches the UI.
        var latest: List<TaskItem> = emptyList()
        val watcher = scope.launch { store.tasks().collect { latest = it.sortedBy { t -> t.id } } }
        assertTrue("the collector must see the hydrated rows", settleUntil { latest.map { it.name } == listOf("A", "B") })

        // THE GAP: the subscription is down (nothing calls noteRealtimeEvent), and
        // meanwhile another device edits A, creates C and deletes B.
        nowMs += 6 * 60_000L
        serverTask("a", "2027-01-15T08:03:00.000Z", "A-edited-elsewhere")
        serverTask("c", "2027-01-15T08:04:00.000Z", "C-created-elsewhere")
        remote.remove(Tables.TASKS, "b")

        val fetchAllBefore = remote.fetchAllCalls.count { it == Tables.TASKS }

        // The subscription comes back — which, on its own, backfills NOTHING.
        o.freshness.noteSubscribed()
        advanceUntilIdle()

        // Converged: no relaunch, no new store, no new DB.
        assertEquals(
            "the edit and the creation must have landed, the deletion must be gone",
            listOf("A-edited-elsewhere", "C-created-elsewhere"),
            localTasks().map { it.name },
        )
        assertTrue(
            "the live collector must see it too — a row that lands in Room without bumping the table's version counter never reaches the UI",
            settleUntil { latest.map { it.name } == listOf("A-edited-elsewhere", "C-created-elsewhere") },
        )
        assertEquals("the catch-up must be a DELTA, never a full table re-read", fetchAllBefore, remote.fetchAllCalls.count { it == Tables.TASKS })
        assertTrue("it asked for rows newer than the mark", remote.fetchSinceCalls.any { it.first == Tables.TASKS })
        assertEquals(
            "the mark advanced to the newest row applied",
            "2027-01-15T08:04:00Z",   // normalised: no "+00:00" may reach the query string
            cursors.get(uid, Tables.TASKS),
        )
        watcher.cancel()
    }

    // ── 2. a queued local write is never clobbered, and never swept away ─────

    @Test fun pendingLocalWrite_survivesCatchUpAndDeletionSweep() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val o = owner(scope)
        serverTask("a", "2027-01-15T08:00:00.000Z", "A")
        o.freshness.requestAndWait(FreshnessTrigger.COLD_START)

        // This device edits A offline (optimistic local row + queued outbox upsert)
        // and creates D, which the server has never seen.
        val edited = task("a", "2027-01-15T08:01:00.000Z", "A-mine-unflushed")
        store.upsert(Tables.TASKS, edited, TaskItem.serializer(), edited.id, edited.updatedAt)
        store.enqueue(OutboxEntity(op = "upsert", recordTable = Tables.TASKS, recordId = "a", payload = DbRowCodec.encodeTask(edited).toString(), createdAt = 1L))
        val created = task("d", "2027-01-15T08:01:00.000Z", "D-mine-unflushed")
        store.upsert(Tables.TASKS, created, TaskItem.serializer(), created.id, created.updatedAt)
        store.enqueue(OutboxEntity(op = "upsert", recordTable = Tables.TASKS, recordId = "d", payload = DbRowCodec.encodeTask(created).toString(), createdAt = 2L))

        // Meanwhile the server's copy of A moves on — a catch-up would normally
        // apply it. It must NOT: the queued write wins until it has flushed.
        nowMs += 6 * 60_000L
        serverTask("a", "2027-01-15T08:05:00.000Z", "A-from-the-server")

        o.freshness.requestAndWait(FreshnessTrigger.FOREGROUND)
        advanceUntilIdle()

        assertEquals(
            "a row with a queued write is neither overwritten nor deleted",
            listOf("A-mine-unflushed", "D-mine-unflushed"),
            localTasks().map { it.name },
        )
        assertEquals(
            "the mark must NOT advance past a row we skipped — it is re-offered later",
            "2027-01-15T08:00:00Z",
            cursors.get(uid, Tables.TASKS),
        )

        // The write lands; the server row is then applied on the next pass.
        store.pending().forEach { store.dequeue(it.seq) }
        o.freshness.requestAndWait(FreshnessTrigger.FOREGROUND)
        advanceUntilIdle()
        assertEquals("A-from-the-server", localTasks().first { it.id == "a" }.name)
    }

    // ── 3. deafness is PROVEN, not guessed ──────────────────────────────────

    @Test fun deafChannel_isProvenByThePullAndRebuildsSubscriptions() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val o = owner(scope)
        serverTask("a", "2027-01-15T08:00:00.000Z", "A")
        o.freshness.requestAndWait(FreshnessTrigger.COLD_START)
        // On screen, where the socket is meant to be up: only there is a change that
        // arrived by pull evidence (Android audit 2026-09-23, A2 — second pass). The
        // floor re-arms for ever once visible, so runCurrent, not advanceUntilIdle.
        o.freshness.onVisible()
        runCurrent()

        // A change old enough that a healthy channel would have delivered it, and
        // no realtime event has been reported at all.
        serverTask("a", "2027-01-15T08:02:00.000Z", "A-changed")
        nowMs += 6 * 60_000L
        o.freshness.requestAndWait(FreshnessTrigger.FLOOR)
        runCurrent()

        assertEquals("the deaf channel is recorded, not guessed at", 1, o.freshness.state.value.deafConfirmed)
        assertEquals("and the subscriptions are rebuilt", 1, o.rebuilds())

        // A change the live mirror DID deliver (the mirror applies the row and
        // reports the event) must not be read as deafness.
        serverTask("b", "2027-01-15T08:10:00.000Z", "B-live")
        RowApply.apply(Tables.TASKS, remote.table(Tables.TASKS).first { (it["id"] as JsonPrimitive).content == "b" }, store, uid)
        o.freshness.noteRealtimeEvent()
        nowMs += 6 * 60_000L
        o.freshness.requestAndWait(FreshnessTrigger.FLOOR)
        runCurrent()
        assertEquals("a row the socket already delivered is not evidence", 1, o.freshness.state.value.deafConfirmed)
        o.freshness.onHidden()
    }

    @Test fun visibleSilence_pullsAndRebuilds_thenTheFloorKeepsPulling() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val o = owner(scope)
        serverTask("a", "2027-01-15T08:00:00.000Z", "A")
        o.freshness.requestAndWait(FreshnessTrigger.COLD_START)

        // NB: no advanceUntilIdle past this point — the floor re-arms itself for
        // ever, so only bounded advances make sense once it is running.
        o.freshness.onVisible()
        advanceTimeBy(1)
        val pullsAfterForeground = remote.fetchSinceCalls.size

        // Two floor ticks with the clock moving in step: ordinary pulls, no rebuild.
        repeat(2) {
            nowMs += FreshnessOwner.FLOOR_INTERVAL_MS
            advanceTimeBy(FreshnessOwner.FLOOR_INTERVAL_MS + 1)
        }
        assertTrue("the floor must keep pulling while visible", remote.fetchSinceCalls.size > pullsAfterForeground)
        assertEquals("a quiet account must not churn its channels", 0, o.rebuilds())

        // Now cross the silence threshold with the socket claiming to be fine.
        nowMs += FreshnessOwner.DEAF_SILENCE_MS
        advanceTimeBy(FreshnessOwner.FLOOR_INTERVAL_MS + 1)
        assertEquals("visible silence must make the socket suspect", 1, o.freshness.state.value.deafSuspected)
        assertTrue("and rebuild the subscriptions", o.rebuilds() >= 1)

        // Hidden: the floor stops.
        o.freshness.onHidden()
        val after = remote.fetchSinceCalls.size
        nowMs += FreshnessOwner.FLOOR_INTERVAL_MS * 3
        advanceTimeBy(FreshnessOwner.FLOOR_INTERVAL_MS * 3)
        assertEquals("nothing polls while the app is away", after, remote.fetchSinceCalls.size)
    }

    // ── 4. coalescing ───────────────────────────────────────────────────────

    @Test fun overlappingTriggers_collapseIntoOneInFlightPull() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val o = owner(scope)
        serverTask("a", "2027-01-15T08:00:00.000Z", "A")
        o.freshness.requestAndWait(FreshnessTrigger.COLD_START)

        val gate = CompletableDeferred<Unit>()
        remote.gate = gate
        remote.fetchSinceCalls.clear()

        o.freshness.request(FreshnessTrigger.FOREGROUND)
        advanceUntilIdle()
        // Five more triggers while that one is stuck mid-flight.
        repeat(5) { o.freshness.request(FreshnessTrigger.REALTIME) }
        advanceUntilIdle()
        assertEquals("only ONE table read may be in flight", 1, remote.fetchSinceCalls.size)

        gate.complete(Unit)
        advanceUntilIdle()
        remote.gate = null
        // Five triggers during the pull ⇒ the running pull finishes its tables and
        // exactly ONE follow-up pass runs. A follow-up is required, not optional: a
        // snapshot taken before a trigger cannot be an answer to it (a channel that
        // re-subscribed mid-pull went live after that snapshot). What must NOT
        // happen is five more passes.
        assertEquals(
            "one in-flight pull + exactly one follow-up, however many triggers arrived",
            CatchUpPuller.CURSOR_TABLES.size * 2,
            remote.fetchSinceCalls.size,
        )
    }

    // ── 5. an empty answer must never wipe the device ───────────────────────

    @Test fun wholesaleEmptyIdSweep_refusesToDeleteAnything() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val o = owner(scope)
        serverTask("a", "2027-01-15T08:00:00.000Z", "A")
        remote.put(
            Tables.TAGS,
            DbRowCodec.encodeTag(tech.csalliance.unstuck.core.model.TagRow(id = "tag-1", name = "deep", color = "#fff", sortOrder = 0)),
        )
        o.freshness.requestAndWait(FreshnessTrigger.COLD_START)
        assertEquals(1, localTasks().size)

        // The server suddenly has nothing for ANY table — an RLS-empty /
        // unauthenticated read looks exactly like this, and it has wiped devices
        // before. Local rows must survive.
        remote.rows.clear()
        nowMs += 6 * 60_000L
        val dropped = puller.reconcileDeletions(uid)
        assertEquals("a wholesale empty answer must delete nothing", 0, dropped)
        assertEquals(1, localTasks().size)

        // A single genuine deletion, with other tables still populated, IS applied.
        serverTask("a", "2027-01-15T08:00:00.000Z", "A")
        remote.put(
            Tables.TAGS,
            DbRowCodec.encodeTag(tech.csalliance.unstuck.core.model.TagRow(id = "tag-1", name = "deep", color = "#fff", sortOrder = 0)),
        )
        store.upsert(Tables.TASKS, task("z", "2027-01-15T08:00:00.000Z", "Z"), TaskItem.serializer(), "z", "2027-01-15T08:00:00.000Z")
        assertEquals(2, localTasks().size)
        assertEquals(1, puller.reconcileDeletions(uid))
        assertEquals(listOf("a"), localTasks().map { it.id })
    }

    @Test fun failedIdSweep_leavesEverythingAlone() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val o = owner(scope)
        serverTask("a", "2027-01-15T08:00:00.000Z", "A")
        o.freshness.requestAndWait(FreshnessTrigger.COLD_START)
        store.upsert(Tables.TASKS, task("z", "2027-01-15T08:00:00.000Z", "Z"), TaskItem.serializer(), "z", "2027-01-15T08:00:00.000Z")

        remote.failIds = true
        assertEquals("an offline sweep deletes nothing", 0, puller.reconcileDeletions(uid))
        assertEquals(2, localTasks().size)
    }

    // ── 6. the cursor is the server's value, never this device's clock ──────

    @Test fun cursorSeed_comesFromTheServerNotTheDeviceClock() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val o = owner(scope)
        serverTask("a", "2027-01-15T08:00:00.000Z", "A")
        o.freshness.requestAndWait(FreshnessTrigger.COLD_START)
        assertEquals("2027-01-15T08:00:00Z", cursors.get(uid, Tables.TASKS))

        // A device whose clock runs an hour fast writes a local row: the mark must
        // not move to that fabricated future, or every real server row in between
        // would be skipped for ever.
        val fromTheFuture = task("a", "2027-01-15T09:00:00.000Z", "A-fast-clock")
        store.upsert(Tables.TASKS, fromTheFuture, TaskItem.serializer(), "a", fromTheFuture.updatedAt)
        nowMs += 6 * 60_000L
        serverTask("b", "2027-01-15T08:05:00.000Z", "B-real-server-row")
        o.freshness.requestAndWait(FreshnessTrigger.FOREGROUND)
        advanceUntilIdle()

        assertNotNull("the server row must still arrive", localTasks().firstOrNull { it.id == "b" })
        assertEquals("A-fast-clock", localTasks().first { it.id == "a" }.name)   // LWW kept the newer local row
        assertEquals("2027-01-15T08:05:00Z", cursors.get(uid, Tables.TASKS))
    }

    // ── 6b. the stamp we send back must be a legal timestamptz literal ──────

    @Test fun cursorStamp_isSentBackWithoutThePlusOffset() {
        // What production actually returns (checked live against PostgREST): a
        // `+00:00` offset. In a query-string value a `+` reads as a SPACE, and the
        // server answers `invalid input syntax for type timestamp with time zone`
        // — the catch-up would fail silently for ever. Normalise to `Z`, keeping
        // every digit: rounding to whole ms would re-offer the newest row on every
        // pass instead.
        val normalized = CatchUpPuller.normalizeStamp("2026-09-07T11:56:58.473251+00:00")
        assertEquals("2026-09-07T11:56:58.473251Z", normalized)
        assertFalse("no reserved character may survive into the query", normalized!!.contains("+"))
        assertEquals("a Z-form stamp is already fine", "2026-07-20T08:00:00Z", CatchUpPuller.normalizeStamp("2026-07-20T08:00:00+00:00"))
        assertEquals("2027-01-15T08:00:00Z", CatchUpPuller.normalizeStamp("2027-01-15T08:00:00.000Z"))
    }

    // ── 6c. local intent wins: a queued DELETE is not undone by a pull ──────

    @Test fun catchUp_doesNotResurrectALocallyDeletedRow() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val o = owner(scope)
        serverTask("a", "2027-01-15T08:00:00.000Z", "A")
        o.freshness.requestAndWait(FreshnessTrigger.COLD_START)
        assertEquals(1, localTasks().size)

        // WriteThrough.delete: the row goes locally and the delete is queued. The
        // server still has it (our delete hasn't flushed) AND it was touched
        // elsewhere, so a cursor pull carries it.
        store.delete(Tables.TASKS, "a")
        store.enqueue(OutboxEntity(op = "delete", recordTable = Tables.TASKS, recordId = "a", payload = null, createdAt = 1L))
        nowMs += 6 * 60_000L
        serverTask("a", "2027-01-15T08:05:00.000Z", "A-touched-elsewhere")

        o.freshness.requestAndWait(FreshnessTrigger.FOREGROUND)
        advanceUntilIdle()

        assertEquals("a row we deleted must not come back while the delete is queued", 0, localTasks().size)
        assertEquals(
            "and the mark must not move past it — if that delete is ever abandoned the row is re-offered",
            "2027-01-15T08:00:00Z",
            cursors.get(uid, Tables.TASKS),
        )
    }

    // ── 6d. a row we could NOT apply must not be skipped for ever ───────────

    @Test fun undecodableRow_holdsTheCursorInsteadOfSkippingTheChange() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val o = owner(scope)
        serverTask("a", "2027-01-15T08:00:00.000Z", "A")
        o.freshness.requestAndWait(FreshnessTrigger.COLD_START)

        // A row this build cannot decode (a schema change, a null where the model
        // demands a value). Advancing past it would lose that change permanently —
        // exactly the silent drift this layer exists to prevent.
        nowMs += 6 * 60_000L
        remote.put(
            Tables.TASKS,
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive("poison"),
                    "updated_at" to JsonPrimitive("2027-01-15T08:05:00.000Z"),
                    "estimate_min" to JsonPrimitive("not-a-number"),
                ),
            ),
        )
        o.freshness.requestAndWait(FreshnessTrigger.FOREGROUND)
        advanceUntilIdle()

        assertEquals(
            "the mark must stay behind the row we could not take",
            "2027-01-15T08:00:00Z",
            cursors.get(uid, Tables.TASKS),
        )
        assertTrue("the table is reported as blocked, not silently fine", true)

        // The server fixes the row; the very next pull picks it up — no relaunch,
        // no full hydrate.
        serverTask("poison", "2027-01-15T08:06:00.000Z", "Fixed")
        o.freshness.requestAndWait(FreshnessTrigger.FOREGROUND)
        advanceUntilIdle()
        assertNotNull(localTasks().firstOrNull { it.id == "poison" })
        assertEquals("2027-01-15T08:06:00Z", cursors.get(uid, Tables.TASKS))
    }

    // ── 7. sign-out / user switch forgets the marks ─────────────────────────

    @Test fun clearedCursors_forceAFullHydrateAgain() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val o = owner(scope)
        serverTask("a", "2027-01-15T08:00:00.000Z", "A")
        o.freshness.requestAndWait(FreshnessTrigger.COLD_START)
        val fullPulls = remote.fetchAllCalls.count { it == Tables.TASKS }

        puller.clearCursors(uid)
        assertFalse(puller.hasCursors(uid))
        o.freshness.requestAndWait(FreshnessTrigger.COLD_START)
        advanceUntilIdle()
        assertEquals("no marks ⇒ the next pull is a full hydrate", fullPulls + 1, remote.fetchAllCalls.count { it == Tables.TASKS })
        assertTrue("and it re-seeds them", puller.hasCursors(uid))
    }

    // ── 8. shared-list membership through the catch-up (audit 2026-09-22 C8) ─
    //
    // The owner's phone decides "shared" from the local members[]; with none it
    // ships item edits as whole-row upserts over the members' edits. A join by
    // link / an invite claimed at sign-up produces no row for the owner's own
    // user_id, and realtime is torn down in the background: the ONLY pull-side
    // trace is migration 056 §4 bumping the list's updated_at. These drive the
    // real puller + a real Hydrator with no realtime event at all. Ported from
    // the iOS CatchUpConvergenceTests (build 81).

    private val partner = "user-2"

    private fun listRow(id: String, owner: String, updatedAt: String): JsonObject {
        val c = ItemCollection(id = id, name = "Groceries", color = "indigo", items = emptyList(), sortOrder = 0)
        return JsonObject(DbRowCodec.encodeCollection(c) + mapOf("user_id" to JsonPrimitive(owner), "updated_at" to JsonPrimitive(updatedAt)))
    }

    private fun memberRow(collectionId: String, userId: String, role: String = "editor") = JsonObject(
        mapOf(
            "id" to JsonPrimitive("m-$collectionId-$userId"), "collection_id" to JsonPrimitive(collectionId),
            "user_id" to JsonPrimitive(userId), "role" to JsonPrimitive(role),
        ),
    )

    private suspend fun ownList(id: String = "c1") = store.upsert(
        Tables.COLLECTIONS,
        ItemCollection(id = id, name = "Groceries", color = "indigo", items = emptyList(), sortOrder = 0, ownerId = uid, members = emptyList(), myRole = "owner"),
        ItemCollection.serializer(), id,
    )

    /** The puller wired exactly as SyncCoordinator wires it. */
    private fun membershipPuller(h: Hydrator = hydrator) = CatchUpPuller(
        remote, store, cursors, now = { nowMs }, log = {},
        refreshMembership = { u, changed -> h.refreshCollectionMembership(u, changed) },
    )

    private fun membershipReads() = remote.fetchAllCalls.count { it == "collection_members" }

    private suspend fun list(id: String) = store.collections().first().firstOrNull { it.id == id }

    @Test fun aJoin_reachesTheOwnersList_throughTheCatchUp_withoutARelaunch() = runTest {
        ownList()
        remote.put(Tables.COLLECTIONS, listRow("c1", uid, "2027-01-15T08:00:00.000000+00:00"))
        val p = membershipPuller()
        p.catchUp(uid)   // seeds the cursors
        assertEquals(emptyList<String>(), list("c1")?.members)

        // THE GAP: the partner joins by link. No realtime event reaches the owner;
        // the server only bumps the list's updated_at (056 §4).
        remote.put("collection_members", memberRow("c1", partner))
        remote.put(Tables.COLLECTIONS, listRow("c1", uid, "2027-01-15T08:05:00.000000+00:00"))

        val outcome = p.catchUp(uid)

        assertTrue(outcome.collectionsChanged)
        assertEquals("the owner's list now reads as shared, in the same process", listOf(partner), list("c1")?.members)
        assertEquals("owner", list("c1")?.myRole)
    }

    // Android's cursors persist, so a cold launch with a stored session only ever
    // catches up. Before this, nothing in that path re-read membership: the
    // owner's list stayed unshared across relaunches.
    @Test fun aJoin_whileTheAppWasAway_reachesTheOwnersList_onTheNextLaunch() = runTest {
        ownList()
        remote.put(Tables.COLLECTIONS, listRow("c1", uid, "2027-01-15T08:00:00.000000+00:00"))
        membershipPuller().catchUp(uid)
        remote.put("collection_members", memberRow("c1", partner))
        remote.put(Tables.COLLECTIONS, listRow("c1", uid, "2027-01-15T08:05:00.000000+00:00"))

        // A new process: fresh engine objects over the same store + cursors.
        val relaunched = Hydrator(remote, store)
        assertTrue("a relaunch still takes the cursor path, not a full hydrate", CatchUpPuller(remote, store, cursors).hasCursors(uid))
        membershipPuller(relaunched).catchUp(uid)

        assertEquals(listOf(partner), list("c1")?.members)
    }

    /** Only a collections row the device had NOT seen triggers the re-read; the
     *  60 s floor ticks while visible and must not cost a membership pull. */
    @Test fun aQuietCatchUp_doesNotRereadMembership() = runTest {
        ownList()
        remote.put(Tables.COLLECTIONS, listRow("c1", uid, "2027-01-15T08:00:00.000000+00:00"))
        val p = membershipPuller()
        assertTrue("the seeding pull re-reads once", p.catchUp(uid).collectionsChanged)
        val readsAfterSeed = membershipReads()

        repeat(3) { assertFalse("nothing newer came back", p.catchUp(uid).collectionsChanged) }

        assertEquals("quiet ticks add no membership pull", readsAfterSeed, membershipReads())
    }

    /** A fresh sign-in knows no membership to fall back on. If the members read
     *  fails there AND on the seeding catch-up, the next catch-up retries it even
     *  though nothing changed on the server. */
    @Test fun aFailedMembershipRead_isRetriedByTheNextQuietCatchUp() = runTest {
        remote.put(Tables.COLLECTIONS, listRow("c1", uid, "2027-01-15T08:00:00.000000+00:00"))
        remote.put("collection_members", memberRow("c1", partner))
        remote.failNextFetchAll["collection_members"] = 2
        val p = membershipPuller()

        hydrator.hydrateCollections(uid)   // the sign-in hydrate: the members read fails
        p.catchUp(uid)                     // the seeding refresh: fails again
        assertEquals(emptyList<String>(), list("c1")?.members)

        val quiet = p.catchUp(uid)

        assertFalse("nothing changed on the server", quiet.collectionsChanged)
        assertEquals("the unresolved membership read is retried and fills the list in", listOf(partner), list("c1")?.members)
        val readsAfterFix = membershipReads()
        p.catchUp(uid)
        assertEquals("once resolved, quiet ticks stop re-reading", readsAfterFix, membershipReads())
    }

    /** Android resumes from its persisted cursors, so a relaunch never runs the
     *  full hydrate that re-reads membership on every iOS launch. A members read
     *  that failed before the process died must still be retried on the next
     *  launch, though no list changed meanwhile. */
    @Test fun aFailedMembershipRead_isRetriedAfterARelaunch() = runTest {
        remote.put(Tables.COLLECTIONS, listRow("c1", uid, "2027-01-15T08:00:00.000000+00:00"))
        remote.put("collection_members", memberRow("c1", partner))
        remote.failNextFetchAll["collection_members"] = 2
        hydrator.hydrateCollections(uid)   // the sign-in hydrate: the members read fails
        membershipPuller().catchUp(uid)    // the seeding catch-up: fails again, then the process dies
        assertEquals(emptyList<String>(), list("c1")?.members)

        // A new process: fresh engine objects over the same store + cursors.
        val outcome = membershipPuller(Hydrator(remote, store)).catchUp(uid)

        assertFalse("no list changed", outcome.collectionsChanged)
        assertEquals("the relaunch re-reads membership anyway", listOf(partner), list("c1")?.members)
    }

    /** The re-read also runs after the user's OWN list edits (realtime never moves
     *  the cursor), so it must never touch content: a list edit that lands while
     *  the membership read is in flight is kept. */
    @Test fun theCatchUpMembershipReRead_neverRevertsAListEdit() = runTest {
        ownList()
        remote.put(Tables.COLLECTIONS, listRow("c1", uid, "2027-01-15T08:00:00.000000+00:00"))
        val p = membershipPuller()
        p.catchUp(uid)
        remote.put("collection_members", memberRow("c1", partner))
        remote.put(Tables.COLLECTIONS, listRow("c1", uid, "2027-01-15T08:05:00.000000+00:00"))
        // While the membership read is in flight, the owner's "milk" lands (the
        // flush acked it and the realtime echo wrote it locally).
        remote.onFetchAll = { table ->
            if (table == "collection_members") {
                val c1 = list("c1")!!
                store.upsert(Tables.COLLECTIONS, c1.copy(items = listOf(CollectionItem(id = "i-milk", body = "milk", at = "2027-01-15T08:06:00.000Z"))), ItemCollection.serializer(), "c1")
            }
        }

        p.catchUp(uid)

        assertEquals("the edit that landed mid-read is kept", listOf("i-milk"), list("c1")?.items?.map { it.id })
        assertEquals("and the list now reads as shared", listOf(partner), list("c1")?.members)
        assertEquals("the pull already applied the list: only membership is read", 0, remote.fetchAllCalls.count { it == Tables.COLLECTIONS })
    }

    /** A list shared WITH me arrives through the pull with no role (the server row
     *  carries none); the membership re-read gives it the real one, so a viewer
     *  doesn't get edit controls. */
    @Test fun aListSharedWithMe_getsItsRoleFromTheCatchUp() = runTest {
        ownList()
        remote.put(Tables.COLLECTIONS, listRow("c1", uid, "2027-01-15T08:00:00.000000+00:00"))
        val p = membershipPuller()
        p.catchUp(uid)
        remote.put("collection_members", memberRow("c9", uid, role = "viewer"))
        remote.put(Tables.COLLECTIONS, listRow("c9", partner, "2027-01-15T08:05:00.000000+00:00"))

        p.catchUp(uid)

        assertEquals("viewer", list("c9")?.myRole)
        assertEquals(listOf(uid), list("c9")?.members)
        assertEquals("my own list is untouched", "owner", list("c1")?.myRole)
    }

    /** Overlapping collections hydrates (realtime, a share action, a refused rpc,
     *  the full hydrate) collapse into one run in flight + one trailing run, and a
     *  caller that arrived mid-run returns only after a run that STARTED after
     *  its call. */
    @Test fun overlappingCollectionHydrates_collapseIntoOneTrailingRun() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        fun reads() = remote.fetchAllCalls.count { it == Tables.COLLECTIONS }
        val gate = CompletableDeferred<Unit>()
        remote.collectionsGate = gate
        scope.launch { hydrator.hydrateCollections(uid) }
        advanceUntilIdle()
        assertEquals("the first run is in flight", 1, reads())

        val readsWhenReturned = mutableListOf<Int>()
        repeat(3) { scope.launch { hydrator.hydrateCollections(uid); readsWhenReturned += reads() } }
        advanceUntilIdle()
        assertTrue("they wait behind the run in flight", readsWhenReturned.isEmpty())

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("one run + one trailing run for the whole burst", 2, reads())
        assertEquals("each returned only after the trailing run", listOf(2, 2, 2), readsWhenReturned)
    }

    /** The realtime side: the members channel used to await one hydrate per
     *  event, so five buffered DELETEs (a list deleted with five members) ran five
     *  collections pulls back to back. Driven through the consumer the mirror
     *  builds. */
    @Test fun aBurstOfMembershipEvents_costsOneRunAndOneTrailingRun() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        fun reads() = remote.fetchAllCalls.count { it == Tables.COLLECTIONS }
        val gate = CompletableDeferred<Unit>()
        remote.collectionsGate = gate
        val (signal, consumer) = RealtimeMirror.coalescedSignal(scope) { hydrator.hydrateCollections(uid) }

        signal()
        advanceUntilIdle()
        assertEquals("the first event's hydrate is in flight", 1, reads())
        repeat(4) { signal() }   // four more DELETEs land meanwhile
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("the events that landed mid-run share ONE trailing run", 2, reads())
        consumer.cancel()
    }
}
