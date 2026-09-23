package tech.csalliance.unstuck.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.model.ProfileFact
import tech.csalliance.unstuck.core.model.ProfileFactCategory
import tech.csalliance.unstuck.core.model.ProfileFactSource
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.OutboxEntity
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase

/**
 * Android audit 2026-09-23, A11: the catch-up pages by stamps, and some stamps
 * are the WRITER's clock (a task insert carries the client's `updated_at`,
 * profile_facts has no touch trigger at all). A row created offline on another
 * device — or by one whose clock is off — could sit behind this device's mark
 * for good, and a row stamped in the future dragged the mark past real server
 * edits. Each test is one of the audit's failure scenarios, driven through the
 * real Hydrator / CatchUpPuller / FreshnessOwner over a fake PostgREST that pages
 * the way the server does ((column, id) order, id-keyed ties, id + stamp sweep).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatchUpClientStampTest {

    private lateinit var db: UnstuckDatabase
    private lateinit var store: LocalStore
    private lateinit var remote: Server
    private lateinit var hydrator: Hydrator
    private lateinit var puller: CatchUpPuller
    private val cursors = InMemorySyncCursors()
    private var nowMs = 1_800_000_000_000L   // 2027-01-15T08:00:00Z-ish
    private val uid = "user-1"

    private class Server : SyncRemote {
        val rows: MutableMap<String, MutableList<JsonObject>> = mutableMapOf()
        val fetchAllCalls = mutableListOf<String>()
        fun table(t: String) = rows.getOrPut(t) { mutableListOf() }
        fun put(t: String, row: JsonObject) {
            val id = (row["id"] as? JsonPrimitive)?.content
            table(t).removeAll { (it["id"] as? JsonPrimitive)?.content == id }
            table(t).add(row)
        }
        override suspend fun fetchAll(table: String): List<JsonObject> { fetchAllCalls += table; return rows[table].orEmpty().toList() }
        override suspend fun fetchSince(table: String, column: String, since: String, limit: Int) =
            FakeRemoteSupport.since(rows[table].orEmpty(), column, since, limit)
        override suspend fun fetchTie(table: String, column: String, stamp: String, afterId: String, limit: Int) =
            FakeRemoteSupport.tie(rows[table].orEmpty(), column, stamp, afterId, limit)
        override suspend fun fetchIds(table: String, offset: Int, limit: Int) =
            FakeRemoteSupport.ids(rows[table].orEmpty(), offset, limit)
        override suspend fun fetchIdStamps(table: String, column: String?, offset: Int, limit: Int) =
            FakeRemoteSupport.idStamps(rows[table].orEmpty(), column, offset, limit)
        override suspend fun fetchByIds(table: String, ids: Collection<String>) =
            FakeRemoteSupport.byIds(rows[table].orEmpty(), ids)
        override suspend fun upsert(table: String, row: JsonObject, userId: String) = Unit
        override suspend fun delete(table: String, id: String) = Unit
        override suspend fun rpc(fn: String, params: JsonObject) = Unit
    }

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnstuckDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(java.util.concurrent.Executor { it.run() })
            .setTransactionExecutor(java.util.concurrent.Executor { it.run() })
            .build()
        store = LocalStore(db)
        remote = Server()
        hydrator = Hydrator(remote, store).apply { zoneId = { "Europe/London" } }
        puller = CatchUpPuller(remote, store, cursors, now = { nowMs }, log = {})
    }

    @After fun teardown() = db.close()

    /** Wired as SyncCoordinator wires it. */
    private fun owner(scope: CoroutineScope) = FreshnessOwner(
        scope = scope,
        currentUserId = { uid },
        runFullHydrate = { u -> val max = hydrator.hydrate(u); puller.seedCursors(u, max); max.isNotEmpty() },
        runCatchUp = { u, sweep ->
            val out = puller.catchUp(u)
            hydrator.hydrateNonCursorTables()
            out.copy(deleted = if (sweep) puller.reconcileDeletions(u) else 0)
        },
        needsFullHydrate = { u -> !puller.hasCursors(u) },
        rebuildSubscriptions = {},
        now = { nowMs },
        log = {},
    )

    private fun TestScope.newOwner() = owner(CoroutineScope(StandardTestDispatcher(testScheduler)))

    private fun task(id: String, updatedAt: String, name: String = id) = TaskItem(
        id = id, name = name, estimateMin = 25, createdAt = "2027-01-15T07:00:00.000Z", updatedAt = updatedAt,
    )

    private fun serverTask(id: String, updatedAt: String, name: String = id) =
        remote.put(Tables.TASKS, DbRowCodec.encodeTask(task(id, updatedAt, name)))

    private suspend fun localTask(id: String) = store.tasks().first().firstOrNull { it.id == id }

    /** Migration 064: sessions / captures / reason_logs carry a server-stamped
     *  `updated_at` (default now() on insert, touch trigger on update). */
    private fun withUpdatedAt(row: JsonObject, updatedAt: String) =
        JsonObject(row + ("updated_at" to JsonPrimitive(updatedAt)))

    // ── scenario 1: tasks created offline on another device ─────────────────

    @Test fun aTaskCreatedOfflineElsewhere_arrivesWithoutARelaunch() = runTest {
        val o = newOwner()
        serverTask("a", "2027-01-15T08:00:00.000Z")
        o.requestAndWait(FreshnessTrigger.COLD_START)
        serverTask("b", "2027-01-15T08:10:00.000Z")
        o.requestAndWait(FreshnessTrigger.FOREGROUND)
        advanceUntilIdle()
        assertEquals("2027-01-15T08:10:00Z", cursors.get(uid, Tables.TASKS))

        // The iPhone was offline at 08:05, reconnects and flushes: its INSERT keeps
        // the phone's own updated_at, which is behind this device's mark.
        serverTask("offline", "2027-01-15T08:05:00.000Z", "Made on the train")
        nowMs += 6 * 60_000L
        o.requestAndWait(FreshnessTrigger.FOREGROUND)
        advanceUntilIdle()

        assertEquals("Made on the train", localTask("offline")?.name)
    }

    @Test fun aSessionLoggedOfflineElsewhere_arrivesThroughTheServerStampedCursor() = runTest {
        val o = newOwner()
        val s0 = Session(id = "s0", taskName = "Earlier", actualSec = 600, completedAt = "2027-01-15T08:00:00.000Z")
        remote.put(Tables.SESSIONS, withUpdatedAt(DbRowCodec.encodeSession(s0), "2027-01-15T08:00:00.000000+00:00"))
        o.requestAndWait(FreshnessTrigger.COLD_START)

        // Completed at 07:30 on an offline phone; the server stamps updated_at when
        // the insert finally lands. No sweep is due: only the cursor can carry it.
        val late = Session(id = "s-late", taskName = "Deep work", actualSec = 1500, completedAt = "2027-01-15T07:30:00.000Z")
        remote.put(Tables.SESSIONS, withUpdatedAt(DbRowCodec.encodeSession(late), "2027-01-15T08:01:00.000000+00:00"))
        o.requestAndWait(FreshnessTrigger.FOREGROUND)
        advanceUntilIdle()

        assertNotNull(store.sessions().first().firstOrNull { it.id == "s-late" })
    }

    // ── scenario 2: a capture promoted to a task elsewhere (`at` never moves) ─

    @Test fun aCapturePromotedElsewhere_isLinkedHereToo() = runTest {
        val o = newOwner()
        val c = Capture(id = "c1", tag = CaptureTag.IDEA, body = "call the bank", at = "2027-01-15T07:50:00.000Z")
        remote.put(Tables.CAPTURES, withUpdatedAt(DbRowCodec.encodeCapture(c), "2027-01-15T07:50:00.000000+00:00"))
        o.requestAndWait(FreshnessTrigger.COLD_START)
        assertNull(store.captures().first().single().taskId)

        // The web promotes it: same id, same `at`, task_id set; the touch trigger
        // moves updated_at. This device's realtime was asleep.
        val taskId = "7c9e6679-7425-40de-944b-e07fc1f90ae7"   // the row codec only sends uuid ids
        remote.put(Tables.CAPTURES, withUpdatedAt(DbRowCodec.encodeCapture(c.copy(taskId = taskId)), "2027-01-15T08:02:00.000000+00:00"))
        o.requestAndWait(FreshnessTrigger.FOREGROUND)
        advanceUntilIdle()

        assertEquals(taskId, store.captures().first().single().taskId)
    }

    // ── scenario 3: a fast clock drags the mark into the future ─────────────

    @Test fun aFutureStampedRow_doesNotHideLaterServerEdits() = runTest {
        val o = newOwner()
        serverTask("a", "2027-01-15T08:00:00.000Z", "A")
        o.requestAndWait(FreshnessTrigger.COLD_START)

        // A computer whose clock runs an hour fast inserts a task.
        serverTask("f", "2027-01-15T09:00:00.000Z", "From the fast clock")
        o.requestAndWait(FreshnessTrigger.FOREGROUND)
        advanceUntilIdle()
        assertEquals("2027-01-15T09:00:00Z", cursors.get(uid, Tables.TASKS))

        // A real, server-stamped edit of A at 08:07 — behind that mark.
        serverTask("a", "2027-01-15T08:07:00.000Z", "A-edited-on-the-web")
        nowMs += 6 * 60_000L
        o.requestAndWait(FreshnessTrigger.FOREGROUND)
        advanceUntilIdle()

        assertEquals("A-edited-on-the-web", localTask("a")?.name)
        assertEquals("From the fast clock", localTask("f")?.name)
    }

    // ── scenario 4: a fact forgotten offline elsewhere keeps its old stamp ──

    @Test fun aFactForgottenOfflineElsewhere_isForgottenHereToo() = runTest {
        val o = newOwner()
        fun fact(id: String, updatedAt: String, active: Boolean = true) = ProfileFact(
            id = id, category = ProfileFactCategory.PERSON, fact = "Maleek — son", source = ProfileFactSource.CHAT,
            active = active, createdAt = "2027-01-15T06:00:00.000Z", updatedAt = updatedAt,
        )
        remote.put(Tables.PROFILE_FACTS, DbRowCodec.encodeProfileFact(fact("f1", "2027-01-15T07:00:00.000Z")))
        remote.put(Tables.PROFILE_FACTS, DbRowCodec.encodeProfileFact(fact("f2", "2027-01-15T08:00:00.000Z")))
        o.requestAndWait(FreshnessTrigger.COLD_START)

        // Forgotten at 07:30 on an offline phone; profile_facts has no touch
        // trigger, so the tombstone lands with 07:30 — behind the 08:00 mark.
        remote.put(Tables.PROFILE_FACTS, DbRowCodec.encodeProfileFact(fact("f1", "2027-01-15T07:30:00.000Z", active = false)))
        nowMs += 6 * 60_000L
        o.requestAndWait(FreshnessTrigger.FOREGROUND)
        advanceUntilIdle()

        assertEquals(false, store.profileFacts().first().first { it.id == "f1" }.active)
    }

    // ── scenario 5: more rows share one stamp than fit on a page ────────────

    @Test fun aTieBiggerThanAPage_isTakenWhole() = runTest {
        val o = newOwner()
        serverTask("a", "2027-01-15T08:00:00.000Z")
        o.requestAndWait(FreshnessTrigger.COLD_START)

        // One server statement touches 205 tasks: the trigger gives them all the
        // same now(). No sweep is due, so the cursor alone must carry every one.
        val n = CatchUpPuller.PAGE_SIZE + 5
        for (i in 0 until n) serverTask("bulk-%03d".format(i), "2027-01-15T08:01:00.000Z", "bulk")
        o.requestAndWait(FreshnessTrigger.FOREGROUND)
        advanceUntilIdle()

        assertEquals(n, store.tasks().first().count { it.name == "bulk" })
        assertEquals("2027-01-15T08:01:00Z", cursors.get(uid, Tables.TASKS))
    }

    /** A row sharing the mark's stamp that was held back last pass (its local
     *  delete was queued) is offered again once that delete is abandoned — the
     *  pass starts AT the mark, not strictly after it. */
    @Test fun aRowHeldBackAtTheMarksStamp_isOfferedAgain() = runTest {
        val o = newOwner()
        serverTask("a", "2027-01-15T08:00:00.000Z")
        o.requestAndWait(FreshnessTrigger.COLD_START)
        store.delete(Tables.TASKS, "z")
        val del = store.enqueue(OutboxEntity(op = "delete", recordTable = Tables.TASKS, recordId = "z", payload = null, createdAt = 1L))
        serverTask("y", "2027-01-15T08:02:00.000Z")
        serverTask("z", "2027-01-15T08:02:00.000Z")
        o.requestAndWait(FreshnessTrigger.FOREGROUND)
        advanceUntilIdle()
        assertNull("held back while its delete is queued", localTask("z"))

        store.dequeue(del)   // the delete is abandoned
        o.requestAndWait(FreshnessTrigger.FOREGROUND)
        advanceUntilIdle()
        assertNotNull(localTask("z"))
    }

    // ── once per launch: the full server read iOS does ──────────────────────

    @Test fun eachLaunch_startsWithAFullHydrate_thenCatchesUp() = runTest {
        val first = newOwner()
        serverTask("a", "2027-01-15T08:00:00.000Z")
        first.requestAndWait(FreshnessTrigger.COLD_START)
        val reads = remote.fetchAllCalls.count { it == Tables.TASKS }

        // A new process over the same store + cursors (Android keeps them).
        val relaunched = newOwner()
        relaunched.requestAndWait(FreshnessTrigger.COLD_START)
        assertEquals("the first pull of a launch reads everything", reads + 1, remote.fetchAllCalls.count { it == Tables.TASKS })

        relaunched.requestAndWait(FreshnessTrigger.FOREGROUND)
        assertEquals("then it catches up", reads + 1, remote.fetchAllCalls.count { it == Tables.TASKS })
    }

    /** The boundary row every pass re-reads is not news: a quiet tick applies
     *  nothing and proves nothing about the socket. */
    @Test fun aQuietTick_reportsNothing() = runTest {
        val o = newOwner()
        serverTask("a", "2027-01-15T08:00:00.000Z")
        o.requestAndWait(FreshnessTrigger.COLD_START)
        nowMs += 60_000L
        val out = puller.catchUp(uid)
        assertEquals(0, out.appliedCount)
        assertEquals(0, out.provenMissed)
        assertTrue(out.blocked.isEmpty())
    }
}
