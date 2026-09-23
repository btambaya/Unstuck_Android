package tech.csalliance.unstuck.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
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
import tech.csalliance.unstuck.core.logic.IsoDate
import tech.csalliance.unstuck.core.logic.occurrenceId
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.sync.RecurrenceTopUpGate.Verdict

/**
 * Stage 2 step 4 on Android — the tail-only horizon top-up, serialised, after a
 * good cal_blocks pull ("same id for same day", Ahmad 2026-09-23;
 * deterministic-occurrence-ids.md §3c, §f, §4.1's two-device twin test). Two
 * devices = two in-memory Room stores, each with its own WriteThrough /
 * OutboxFlusher / Hydrator, over ONE fake PostgREST with ON CONFLICT DO NOTHING
 * and filtered-PATCH semantics. Mirrors iOS build 85's CatchUpConvergenceTests
 * twin cases and web's twin-occurrence.test.ts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecurrenceTopUpTest {
    private val uid = "user-1"
    private val today = "2026-09-23"
    private val taskId = "3f1c2a9e-5b7d-4c21-9a0e-7d2b1c4e8f60"
    private val series = TaskItem(
        id = taskId, name = "Stretch", estimateMin = 25, recurrence = Recurrence.Daily(),
        createdAt = "2026-08-01T08:00:00Z", updatedAt = "2026-08-01T08:00:00Z",
    )
    private lateinit var server: FakeInsertServer
    private val dbs = mutableListOf<UnstuckDatabase>()

    /** One device: its store and engine, all over the shared [server]. */
    private inner class Device(scope: TestScope, var day: String = today) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnstuckDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
            .also { dbs += it }
        val store = LocalStore(db)
        val write = WriteThrough(store, scope)
        val flusher = OutboxFlusher(server, store, write.mirrorGate)
        val hydrator = Hydrator(server, store).apply { zoneId = { "Europe/London" } }
        var signedIn: String? = uid
        val topUp = RecurrenceHorizonTopUp(
            store, write, server, pull = { hydrator.calBlocksPull }, pulledAfter = { hydrator.seqBeforeLatestPull },
            currentUserId = { signedIn }, today = { day }, timeZone = { "Europe/London" }, log = {},
        )

        /** What the device's pull does (as SyncCoordinator's): note the start,
         *  flush, then take the server's cal_blocks. */
        suspend fun pull() {
            hydrator.notePullStart()
            flusher.flush(uid)
            val t = server.table(Tables.TASKS).values.map { DbRowCodec.decodeTask(it) }
            store.replace(Tables.TASKS, t, TaskItem.serializer(), { it.id }, { it.updatedAt }, keepPendingUpserts = true)
            hydrator.hydrateNonCursorTables()
        }
        suspend fun blocks(): List<CalBlock> = store.snapshot(Tables.CAL_BLOCKS, CalBlock.serializer())
    }

    @Before fun setup() {
        server = FakeInsertServer()
        server.put(Tables.TASKS, DbRowCodec.encodeTask(series))
    }

    @After fun teardown() { dbs.forEach { it.close() } }

    private fun day(n: Int) = IsoDate.addDays(today, n)
    private fun occ(date: String, on: String? = null, time: String = "07:00") = CalBlock(
        id = occurrenceId(taskId, date), taskId = taskId, taskName = "Stretch", startTime = time, durationMinutes = 25,
        date = on ?: date, kind = CalBlockKind.TASK,
    )

    /** A daily series whose frontier is 3 days short of the horizon (+52 of +55). */
    private fun seedServerSeries(through: Int = 52) = (1..through).forEach { server.putBlock(occ(day(it))) }

    private fun serverDays(): Map<String, Int> = server.blocks().groupingBy { it.date }.eachCount()

    // ── the gate ─────────────────────────────────────────────────────────────

    @Test fun theGateRunsOncePerDayAfterAGoodCompletePull() {
        val g = RecurrenceTopUpGate()
        val p1 = Hydrator.CalBlocksPull(1, 10)
        assertEquals(Verdict.NO_PULL, g.verdict(null, uid, today, "Europe/London"))
        assertEquals(Verdict.TRUNCATED, g.verdict(Hydrator.CalBlocksPull(1, 1000), uid, today, "Europe/London"))
        assertEquals(Verdict.RUN, g.verdict(p1, uid, today, "Europe/London"))
        g.recordRun(p1, uid, today, "Europe/London")
        assertEquals(Verdict.PULL_NOT_ADVANCED, g.verdict(p1, uid, day(1), "Europe/London"))
        val p2 = Hydrator.CalBlocksPull(2, 10)
        assertEquals(Verdict.ALREADY_RAN_TODAY, g.verdict(p2, uid, today, "Europe/London"))
        assertEquals("a new day", Verdict.RUN, g.verdict(p2, uid, day(1), "Europe/London"))
        assertEquals("a time-zone change", Verdict.RUN, g.verdict(p2, uid, today, "America/New_York"))
        assertEquals("another account", Verdict.RUN, g.verdict(p1, "user-2", today, "Europe/London"))
        g.reset()
        assertNull(g.lastRun)
    }

    /** iOS's `pulledAfter`: the pull the app asks after must have moved the stamp
     *  itself — an earlier good read never stands in for one that failed. */
    @Test fun theGateNeedsTheLatestPullToHaveMovedTheStamp() {
        val g = RecurrenceTopUpGate()
        val p1 = Hydrator.CalBlocksPull(1, 10)
        assertEquals(Verdict.PULL_NOT_ADVANCED, g.verdict(p1, uid, today, "Europe/London", pulledAfter = 1))
        assertEquals(Verdict.RUN, g.verdict(p1, uid, today, "Europe/London", pulledAfter = 0))
        g.recordRun(p1, uid, today, "Europe/London")
        val p2 = Hydrator.CalBlocksPull(2, 10)
        assertEquals("a new day, but the latest read failed", Verdict.PULL_NOT_ADVANCED, g.verdict(p2, uid, day(1), "Europe/London", pulledAfter = 2))
        assertEquals(Verdict.RUN, g.verdict(p2, uid, day(1), "Europe/London", pulledAfter = 1))
    }

    /** The reviewer's midnight case: yesterday's run, a good read late in the day,
     *  then the new day's first pull whose cal_blocks read fails — no top-up from
     *  that older store; the next good pull runs it. */
    @Test fun aNewDaysFirstPullWhoseReadFailedDoesNotTopUp() = runTest {
        seedServerSeries()
        val a = Device(this, day = day(-1))
        a.pull()
        a.topUp.request(uid)
        assertEquals("yesterday's run minted to its horizon", 2, a.topUp.lastMinted)
        a.flusher.flush(uid)
        a.pull()                                  // a good read late in the day
        a.day = today
        server.failReadOnce += Tables.CAL_BLOCKS
        a.pull()                                  // today's first pull: that read fails
        a.topUp.request(uid)
        assertTrue("nothing minted from the older store", a.store.pending().isEmpty())
        a.pull()
        a.topUp.request(uid)
        assertEquals(1, a.topUp.lastMinted)
        assertEquals(listOf(occurrenceId(taskId, day(55))), a.store.pending().map { it.recordId })
    }

    // ── the run ──────────────────────────────────────────────────────────────

    @Test fun theTopUpMintsTheTailOnceADayAsPlainInserts() = runTest {
        seedServerSeries()
        val a = Device(this)
        a.pull()
        a.topUp.request(uid)
        assertEquals(3, a.topUp.lastMinted)
        val ops = a.store.pending()
        assertEquals(listOf(day(53), day(54), day(55)).map { occurrenceId(taskId, it) }, ops.map { it.recordId })
        assertTrue("maintenance: no rule H", ops.all { it.op == OutboxFlusher.OP_INSERT })
        // Again the same day, even after another good pull: nothing.
        a.pull()
        a.topUp.request(uid)
        assertEquals(0, a.store.pending().size)
        assertEquals(55, serverDays().size)
        assertTrue(serverDays().values.all { it == 1 })
    }

    @Test fun noGoodPullNoTopUp() = runTest {
        seedServerSeries()
        val a = Device(this)
        // The series is in the store, but no cal_blocks read has succeeded.
        a.store.upsert(Tables.TASKS, series, TaskItem.serializer(), series.id, series.updatedAt)
        (1..52).forEach { a.store.upsert(Tables.CAL_BLOCKS, occ(day(it)), CalBlock.serializer(), occurrenceId(taskId, day(it))) }
        a.topUp.request(uid)
        assertTrue(a.store.pending().isEmpty())
        a.topUp.request("someone-else")
        assertTrue(a.store.pending().isEmpty())
    }

    /** A series stopped on another device (its task row now says so) is never
     *  revived from this device's stale tasks store — the web stage-2 review. */
    @Test fun aSeriesTheServerSaysStoppedIsNotRevived() = runTest {
        seedServerSeries()
        val a = Device(this)
        a.pull()
        server.put(Tables.TASKS, DbRowCodec.encodeTask(series.copy(recurrence = null, updatedAt = "2026-09-23T09:00:00Z")))
        a.topUp.request(uid)
        assertTrue(a.store.pending().isEmpty())
        assertTrue(RecurrenceHorizonTopUp.serverAgrees(series, series.copy(recurrence = Recurrence.Daily())))
        assertFalse(RecurrenceHorizonTopUp.serverAgrees(series, series.copy(done = true)))
        assertFalse(RecurrenceHorizonTopUp.serverAgrees(series, null))
        assertTrue(RecurrenceHorizonTopUp.serverAgrees(series.copy(recurrence = Recurrence.Weekly(listOf(1, 3))), series.copy(recurrence = Recurrence.Weekly(listOf(3, 1)))))
    }

    /** A failed server check mints nothing and does not use the day up. */
    @Test fun aFailedSeriesCheckWaitsForTheNextPull() = runTest {
        seedServerSeries()
        val a = Device(this)
        a.pull()
        var offline = true
        val flaky = object : SyncRemote by server {
            override suspend fun fetchByIds(table: String, ids: Collection<String>): List<JsonObject> =
                if (offline) throw java.io.IOException("offline") else server.fetchByIds(table, ids)
        }
        val topUp = RecurrenceHorizonTopUp(
            a.store, a.write, flaky, pull = { a.hydrator.calBlocksPull }, pulledAfter = { a.hydrator.seqBeforeLatestPull },
            currentUserId = { uid }, today = { today }, timeZone = { "Europe/London" }, log = {},
        )
        topUp.request(uid)
        assertTrue(a.store.pending().isEmpty())
        // The same pull, the same day: the failed check did not use the day up.
        offline = false
        topUp.request(uid)
        assertEquals(3, a.store.pending().size)
    }

    @Test fun aTruncatedReadIsNeverToppedUp() = runTest {
        seedServerSeries()
        // Pad the table to the row cap with another series' rows.
        (0 until 1000 - 52).forEach { server.putBlock(CalBlock(id = "pad-$it", taskId = "other", taskName = "x", startTime = "08:00", durationMinutes = 25, date = "2025-01-01", kind = CalBlockKind.TASK)) }
        val a = Device(this)
        a.pull()
        assertTrue(a.hydrator.calBlocksPull!!.mayBeTruncated)
        a.topUp.request(uid)
        assertTrue(a.store.pending().isEmpty())
    }

    // ── two devices, one server (§4.1's twin test) ───────────────────────────

    /** Both devices hold the same stale store and top up before either flushes:
     *  the server ends with exactly one row per day, each with its deterministic id. */
    @Test fun twoDevicesToppingUpTheSameTailLandOnOneRowPerDay() = runTest {
        seedServerSeries()
        val a = Device(this); val b = Device(this)
        a.pull(); b.pull()
        a.topUp.request(uid); b.topUp.request(uid)
        a.flusher.flush(uid); b.flusher.flush(uid)
        assertEquals(55, serverDays().size)
        assertTrue("one row per day", serverDays().values.all { it == 1 })
        assertTrue(server.blocks().all { it.id == occurrenceId(taskId, it.date) })
        assertEquals(3, server.log.count { it.endsWith("ignored") })
        b.pull()
        assertEquals(server.blocks().map { it.id }.toSet(), b.blocks().map { it.id }.toSet())
    }

    /** A stale mint never pulls a moved occurrence back: A moved id(+54) to +60;
     *  B, stale, mints +54 — the server keeps A's row, and B converges on it. */
    @Test fun aStaleMintNeverPullsAMovedOccurrenceBack() = runTest {
        seedServerSeries(through = 55)
        val a = Device(this); val b = Device(this)
        a.pull(); b.pull()
        // B's store is stale: it never saw +53..+55.
        (53..55).forEach { b.store.delete(Tables.CAL_BLOCKS, occurrenceId(taskId, day(it))) }
        val id54 = occurrenceId(taskId, day(54))
        a.write.upsertCalBlock(a.store.getOne(Tables.CAL_BLOCKS, id54, CalBlock.serializer())!!.copy(date = day(60), startTime = "18:00"))
        a.flusher.flush(uid)
        b.write.insertCalBlockIfAbsent(occ(day(54)), retimeIfTaken = false)
        b.flusher.flush(uid)
        assertEquals("A's move stands", day(60), server.block(id54)!!.date)
        b.pull()
        val mine = b.blocks()
        assertEquals(day(60), mine.single { it.id == id54 }.date)
        assertTrue("B has no second +54 block", mine.none { it.date == day(54) })
    }

    /** Rule H: B, stale, re-plans the series at 16:00 over tail days A minted at
     *  07:00 — those open days move to 16:00; one A moved away stays where it is. */
    @Test fun aStaleUserRePlanRetimesTheOpenDaysButNeverAMovedOne() = runTest {
        seedServerSeries()
        val a = Device(this); val b = Device(this)
        a.pull(); b.pull()
        a.topUp.request(uid)
        a.flusher.flush(uid)   // A minted +53..+55 at 07:00
        val id55 = occurrenceId(taskId, day(55))
        a.write.upsertCalBlock(a.store.getOne(Tables.CAL_BLOCKS, id55, CalBlock.serializer())!!.copy(date = day(58)))
        a.flusher.flush(uid)   // …and moved +55 to +58
        // B never saw any of it: its re-plan mints +53..+55 at 16:00 (insert_or_retime).
        (53..55).forEach { b.write.insertCalBlockIfAbsent(occ(day(it), time = "16:00"), retimeIfTaken = true) }
        b.flusher.flush(uid)
        assertEquals("16:00", server.block(occurrenceId(taskId, day(53)))!!.startTime)
        assertEquals("16:00", server.block(occurrenceId(taskId, day(54)))!!.startTime)
        assertEquals("the moved day keeps the first write", day(58), server.block(id55)!!.date)
        assertEquals("07:00", server.block(id55)!!.startTime)
        assertTrue("one row per day", serverDays().values.all { it == 1 })
    }
}
