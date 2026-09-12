package tech.csalliance.unstuck.soak

import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.OutboxEntity
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.sync.DbRowCodec
import tech.csalliance.unstuck.sync.Hydrator
import tech.csalliance.unstuck.sync.OutboxFlusher
import tech.csalliance.unstuck.sync.RpcRejected
import tech.csalliance.unstuck.sync.SyncRemote

/**
 * SOAK — cold-start hydrate, the Room-backed store reads and the outbox flush,
 * against the heavy account ([SoakSeed]). Robolectric gives a REAL (in-memory)
 * SQLite through Room; the remote is a fake (no network, no production data).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoakSyncPerfTest {

    private lateinit var db: UnstuckDatabase
    private lateinit var store: LocalStore

    private val tasks = SoakSeed.tasks()
    private val blocks = SoakSeed.blocks(tasks)
    private val sessions = SoakSeed.sessions(tasks)
    private val captures = SoakSeed.captures()
    private val collections = SoakSeed.collections()

    /** Fake server that returns the seeded rows, PostgREST-shaped. */
    private class FakeRemote(val rows: Map<String, List<JsonObject>>) : SyncRemote {
        var upserts = 0
        var deletes = 0
        override suspend fun fetchAll(table: String): List<JsonObject> = rows[table].orEmpty()
        override suspend fun upsert(table: String, row: JsonObject, userId: String) { upserts++ }
        override suspend fun delete(table: String, id: String) { deletes++ }
        override suspend fun rpc(fn: String, params: JsonObject) = Unit
        override suspend fun fetchSince(table: String, column: String, since: String, limit: Int) =
            tech.csalliance.unstuck.sync.FakeRemoteSupport.since(rows[table].orEmpty(), column, since, limit)
        override suspend fun fetchIds(table: String, offset: Int, limit: Int) =
            tech.csalliance.unstuck.sync.FakeRemoteSupport.ids(rows[table].orEmpty(), offset, limit)
    }

    private fun serverRows(): Map<String, List<JsonObject>> = mapOf(
        Tables.TASKS to tasks.map { DbRowCodec.encodeTask(it) },
        Tables.CAL_BLOCKS to blocks.map { DbRowCodec.encodeCalBlock(it) },
        Tables.SESSIONS to sessions.map { DbRowCodec.encodeSession(it) },
        Tables.CAPTURES to captures.map { DbRowCodec.encodeCapture(it) },
        Tables.COLLECTIONS to collections.map { DbRowCodec.encodeCollection(it) },
        Tables.TAGS to SoakSeed.tags().map { DbRowCodec.encodeTag(it) },
        Tables.LIFE_AREAS to SoakSeed.areas().map { DbRowCodec.encodeLifeArea(it) },
    )

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnstuckDatabase::class.java)
            .allowMainThreadQueries().build()
        store = LocalStore(db)
    }

    @After fun teardown() = db.close()

    /** Cold-start pull: decode every server row + replace every table. */
    @Test fun soak_hydrate_cold_start(): Unit = runBlocking {
        val rows = serverRows()
        println("PERF | server rows: " + rows.entries.joinToString { "${it.key}=${it.value.size}" })
        val remote = FakeRemote(rows)
        val hydrator = Hydrator(remote, store)
        Bench.run("Hydrator.hydrate() FULL (cold start pull)", warmup = 1, iters = 5) {
            runBlocking { hydrator.hydrate("me") }
        }
        // Per-table breakdown (decode + replace), largest tables only.
        Bench.run("hydrate: decode 4000 cal_blocks (DbRowCodec)", warmup = 2, iters = 10) {
            rows[Tables.CAL_BLOCKS]!!.mapNotNull { runCatching { DbRowCodec.decodeCalBlock(it) }.getOrNull() }
        }
        Bench.run("hydrate: decode 800 tasks (DbRowCodec)", warmup = 2, iters = 10) {
            rows[Tables.TASKS]!!.mapNotNull { runCatching { DbRowCodec.decodeTask(it) }.getOrNull() }
        }
        Bench.run("hydrate: store.replace(cal_blocks, 4000)", warmup = 1, iters = 5) {
            runBlocking { store.replace(Tables.CAL_BLOCKS, blocks, CalBlock.serializer(), { it.id }, { null }, "g_", true) }
        }
        Bench.run("hydrate: store.replace(tasks, 800)", warmup = 1, iters = 5) {
            runBlocking { store.replace(Tables.TASKS, tasks, TaskItem.serializer(), { it.id }, { it.updatedAt }, null, true) }
        }
        Bench.run("hydrate: store.replace(sessions, 1500)", warmup = 1, iters = 5) {
            runBlocking { store.replace(Tables.SESSIONS, sessions, Session.serializer(), { it.id }, { it.completedAt }, null, true) }
        }
        Bench.run("hydrate: store.replace(collections, 40x30)", warmup = 1, iters = 5) {
            runBlocking { store.replace(Tables.COLLECTIONS, collections, ItemCollection.serializer(), { it.id }, { null }, null, true) }
        }
    }

    /** What the UI pays to READ the store once it's full (the Room query + the
     *  per-row JSON decode in LocalStore.observe / snapshot). */
    @Test fun soak_store_reads(): Unit = runBlocking {
        store.replace(Tables.TASKS, tasks, TaskItem.serializer(), { it.id })
        store.replace(Tables.CAL_BLOCKS, blocks, CalBlock.serializer(), { it.id })
        store.replace(Tables.SESSIONS, sessions, Session.serializer(), { it.id })
        store.replace(Tables.CAPTURES, captures, Capture.serializer(), { it.id })
        store.replace(Tables.COLLECTIONS, collections, ItemCollection.serializer(), { it.id })

        Bench.run("store.tasks().first() (query + decode 800)", warmup = 2, iters = 10) { runBlocking { store.tasks().first().size } }
        Bench.run("store.blocks().first() (query + decode 4000)", warmup = 2, iters = 10) { runBlocking { store.blocks().first().size } }
        Bench.run("store.sessions().first() (query + decode 1500)", warmup = 2, iters = 10) { runBlocking { store.sessions().first().size } }
        Bench.run("store.collections().first() (query + decode 40x30)", warmup = 2, iters = 10) { runBlocking { store.collections().first().size } }
        Bench.run("ALL five collections (one cold-start fan-out)", warmup = 1, iters = 5) {
            runBlocking {
                store.tasks().first().size + store.blocks().first().size + store.sessions().first().size +
                    store.captures().first().size + store.collections().first().size
            }
        }
        Bench.run("store.getOne(tasks, id) (per-write base capture)", warmup = 5, iters = 50) {
            runBlocking { store.getOne(Tables.TASKS, "t0400", TaskItem.serializer()) }
        }
        Bench.run("store.snapshot(cal_blocks) (4000)", warmup = 2, iters = 10) {
            runBlocking { store.snapshot(Tables.CAL_BLOCKS, CalBlock.serializer()).size }
        }
    }

    /** One user write: LocalStore upsert of a single task with the store full. */
    @Test fun soak_single_write(): Unit = runBlocking {
        store.replace(Tables.TASKS, tasks, TaskItem.serializer(), { it.id })
        store.replace(Tables.CAL_BLOCKS, blocks, CalBlock.serializer(), { it.id })
        var i = 0
        Bench.run("store.upsert(one task) with 4800 rows resident", warmup = 5, iters = 50) {
            runBlocking {
                val t = tasks[(i++) % tasks.size]
                store.upsert(Tables.TASKS, t.copy(name = t.name + "!"), TaskItem.serializer(), t.id, t.updatedAt)
            }
        }
    }

    /** Outbox: 200 queued ops drained end to end (server calls are no-ops, so
     *  this isolates the LOCAL cost of the drain: pending() re-reads, coalescing,
     *  dependency gating and the per-op dequeue). */
    @Test fun soak_outbox_flush_200(): Unit = runBlocking {
        val rows = serverRows()
        val remote = FakeRemote(rows)
        store.replace(Tables.TASKS, tasks, TaskItem.serializer(), { it.id })
        store.replace(Tables.CAL_BLOCKS, blocks, CalBlock.serializer(), { it.id })
        store.replace(Tables.SESSIONS, sessions, Session.serializer(), { it.id })

        suspend fun queue200(distinctRows: Boolean) {
            for (i in 0 until 200) {
                val t = tasks[if (distinctRows) i else i % 20]
                val payload = DbRowCodec.encodeTask(t.copy(name = t.name + " v$i")).toString()
                store.enqueue(
                    OutboxEntity(
                        op = "upsert", recordTable = Tables.TASKS, recordId = t.id,
                        payload = payload, createdAt = System.currentTimeMillis(), base = null,
                    ),
                )
            }
        }

        val flusher = OutboxFlusher(remote, store)
        // Time the FLUSH only (the enqueue of the 200 ops is set-up, not drain cost).
        fun drainOnly(label: String, distinct: Boolean, runs: Int = 6) {
            val samples = ArrayList<Double>()
            repeat(runs) { r ->
                runBlocking { queue200(distinct) }
                val t0 = System.nanoTime()
                runBlocking { flusher.flush("me") }
                val t1 = System.nanoTime()
                if (r > 0) samples += (t1 - t0) / 1_000_000.0
            }
            samples.sort()
            println("PERF | %-52s | n=%3d | median %8.3f ms | mean %8.3f | min %8.3f | max %8.3f"
                .format(label, samples.size, samples[samples.size / 2], samples.average(), samples.first(), samples.last()))
        }
        drainOnly("outbox DRAIN only: 200 ops / 200 distinct rows", true)
        drainOnly("outbox DRAIN only: 200 ops / 20 rows (coalescing)", false)
        Bench.run("outbox: enqueue 200 ops (set-up cost)", warmup = 1, iters = 5) {
            runBlocking { queue200(true) }
        }
        runBlocking { flusher.flush("me") }
        println("PERF | fake server upserts seen = ${remote.upserts}")
    }

    /** The enqueue side: what a single write costs in the outbox path. */
    @Test fun soak_outbox_enqueue(): Unit = runBlocking {
        var i = 0
        Bench.run("store.enqueue(one op)", warmup = 10, iters = 100) {
            runBlocking {
                store.enqueue(
                    OutboxEntity(
                        op = "upsert", recordTable = Tables.TASKS, recordId = "t%04d".format(i++ % 800),
                        payload = "{}", createdAt = 1L,
                    ),
                )
            }
        }
        Bench.run("store.pending() with 100+ ops queued", warmup = 5, iters = 30) { runBlocking { store.pending().size } }
    }


    /** The per-write invalidation cascade. Every synced row lives in ONE `records`
     *  table, so ANY write invalidates EVERY observe() query: each of the ~10
     *  collectors re-runs its SELECT and then compares the raw rows
     *  (distinctUntilChanged on List<RecordEntity>) before it can skip the decode. */
    @Test fun soak_invalidation_cascade(): Unit = runBlocking {
        store.replace(Tables.TASKS, tasks, TaskItem.serializer(), { it.id })
        store.replace(Tables.CAL_BLOCKS, blocks, CalBlock.serializer(), { it.id })
        store.replace(Tables.SESSIONS, sessions, Session.serializer(), { it.id })
        store.replace(Tables.CAPTURES, captures, Capture.serializer(), { it.id })
        store.replace(Tables.COLLECTIONS, collections, ItemCollection.serializer(), { it.id })
        val dao = db.records()
        Bench.run("raw SELECT records(cal_blocks) 4000 (no decode)", warmup = 2, iters = 15) {
            runBlocking { dao.get(Tables.CAL_BLOCKS).size }
        }
        Bench.run("raw SELECT records(tasks) 800 (no decode)", warmup = 2, iters = 15) {
            runBlocking { dao.get(Tables.TASKS).size }
        }
        Bench.run("raw SELECT records(sessions) 1500 (no decode)", warmup = 2, iters = 15) {
            runBlocking { dao.get(Tables.SESSIONS).size }
        }
        val a = dao.get(Tables.CAL_BLOCKS)
        val b = dao.get(Tables.CAL_BLOCKS)
        Bench.run("List<RecordEntity>.equals 4000 (distinctUntilChanged)", warmup = 10, iters = 100) { a == b }
        Bench.run("ONE unrelated write's re-query fan-out (5 tables)", warmup = 2, iters = 10) {
            runBlocking {
                dao.get(Tables.TASKS).size + dao.get(Tables.CAL_BLOCKS).size + dao.get(Tables.SESSIONS).size +
                    dao.get(Tables.CAPTURES).size + dao.get(Tables.COLLECTIONS).size
            }
        }
    }

    /**
     * END-TO-END invalidation cost with LIVE collectors mounted on all five big
     * collections, comparing the two invalidation models on the SAME database in
     * the SAME run:
     *   • LEGACY — a Room Flow over the shared `records` table: any write wakes
     *     every collector, each re-runs its own SELECT, and distinctUntilChanged
     *     then throws the identical rows away. ([legacyObserve] below reproduces
     *     the chain LocalStore had, via Room's InvalidationTracker on `records`.)
     *   • CURRENT — LocalStore's per-logical-table version counters: a tasks
     *     write only wakes the tasks collector.
     * The workload is a batch of single-row task writes (a user ticking rows off)
     * settled to the last value the tasks collector sees.
     */
    @Test fun soak_invalidation_live_collectors(): Unit = runBlocking {
        store.replace(Tables.TASKS, tasks, TaskItem.serializer(), { it.id })
        store.replace(Tables.CAL_BLOCKS, blocks, CalBlock.serializer(), { it.id })
        store.replace(Tables.SESSIONS, sessions, Session.serializer(), { it.id })
        store.replace(Tables.CAPTURES, captures, Capture.serializer(), { it.id })
        store.replace(Tables.COLLECTIONS, collections, ItemCollection.serializer(), { it.id })
        val subject = tasks.first { !it.done }

        suspend fun batch(latest: () -> String?, write: suspend (String) -> Unit, n: Int) {
            var name = ""
            for (i in 0 until n) {
                name = "soak-write-$i-${System.nanoTime()}"
                write(name)
            }
            withTimeout(30_000) { while (latest() != name) delay(1) }
        }

        for (mode in listOf("LEGACY (Room flow on shared `records`)", "CURRENT (per-table version)")) {
            val scope = CoroutineScope(Dispatchers.Default + Job())
            val latestTask = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val emissions = java.util.concurrent.atomic.AtomicInteger(0)
            val legacy = mode.startsWith("LEGACY")
            fun <T> flowFor(table: String, ser: kotlinx.serialization.KSerializer<T>) =
                if (legacy) legacyObserve(table, ser) else store.observeTable(table, ser)
            scope.launch {
                flowFor(Tables.TASKS, TaskItem.serializer()).collect { rows ->
                    emissions.incrementAndGet()
                    latestTask.set(rows.firstOrNull { it.id == subject.id }?.name)
                }
            }
            scope.launch { flowFor(Tables.CAL_BLOCKS, CalBlock.serializer()).collect { } }
            scope.launch { flowFor(Tables.SESSIONS, Session.serializer()).collect { } }
            scope.launch { flowFor(Tables.CAPTURES, Capture.serializer()).collect { } }
            scope.launch { flowFor(Tables.COLLECTIONS, ItemCollection.serializer()).collect { } }
            withTimeout(30_000) { while (latestTask.get() == null) delay(1) }

            val write: suspend (String) -> Unit = { n ->
                store.upsert(Tables.TASKS, subject.copy(name = n), TaskItem.serializer(), subject.id)
            }
            repeat(2) { batch({ latestTask.get() }, write, 10) }   // warm up
            val before = emissions.get()
            val r = Bench.run("live collectors: 25 task writes settled — $mode", warmup = 0, iters = 5) {
                runBlocking { batch({ latestTask.get() }, write, 25) }
            }
            println("PERF | $mode tasks emissions during bench = ${emissions.get() - before} (r=${r.medianMs})")
            scope.cancel()
        }
    }

    /** The chain LocalStore used before per-table invalidation: re-query on ANY
     *  `records` write, then drop the identical rows. Test-only, for the A/B above. */
    private fun <T> legacyObserve(table: String, ser: kotlinx.serialization.KSerializer<T>): Flow<List<T>> =
        callbackFlow {
            val obs = object : InvalidationTracker.Observer(arrayOf("records")) {
                override fun onInvalidated(tables: Set<String>) { trySend(Unit) }
            }
            db.invalidationTracker.addObserver(obs)
            trySend(Unit)
            awaitClose { db.invalidationTracker.removeObserver(obs) }
        }
            .map { db.records().get(table) }
            .distinctUntilChanged()
            .map { rows -> rows.mapNotNull { runCatching { legacyJson.decodeFromString(ser, it.data) }.getOrNull() } }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    private val legacyJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

    private fun unusedJson() = Json
    private fun unusedRejected() = RpcRejected(400, "x")
}
