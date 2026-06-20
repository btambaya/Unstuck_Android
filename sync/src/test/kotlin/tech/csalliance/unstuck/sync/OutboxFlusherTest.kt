package tech.csalliance.unstuck.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.csalliance.unstuck.data.db.OutboxEntity
import tech.csalliance.unstuck.data.db.Tables

// The dependsOn gate: an op is held while its parent rowId has a pending op OR
// the parent row doesn't exist in local records yet. Guards the capture-during-
// live-session case — the sessions row is only written at session END, so the
// capture must NOT flush early (it would FK-fail every drain and poison-drop).
class OutboxFlusherTest {

    private var seq = 0L
    private fun op(table: String, id: String, dependsOn: String? = null, op: String = "upsert") =
        OutboxEntity(seq = ++seq, op = op, recordTable = table, recordId = id, payload = "{}", dependsOn = dependsOn, createdAt = 0L)

    private suspend fun flushableIds(all: List<OutboxEntity>, local: Map<String, Set<String>>): List<String> =
        OutboxFlusher.flushableOps(all) { table -> local[table].orEmpty() }.map { it.recordId }

    @Test fun captureDuringLiveSessionIsHeld_noPendingOpAndNoLocalRow() = runTest {
        // Mid-session: no sessions op queued yet AND no sessions row locally —
        // the old gate (pending-only) would have flushed this and FK-failed.
        val all = listOf(op(Tables.CAPTURES, "c1", dependsOn = "s1"))
        assertEquals(emptyList<String>(), flushableIds(all, mapOf(Tables.SESSIONS to emptySet())))
    }

    @Test fun captureHeldWhileParentSessionOpIsPending() = runTest {
        // Session ended offline: its op is queued — the capture waits for it even
        // though the session row now exists locally (FIFO parent-first).
        val all = listOf(
            op(Tables.SESSIONS, "s1"),
            op(Tables.CAPTURES, "c1", dependsOn = "s1"),
        )
        assertEquals(listOf("s1"), flushableIds(all, mapOf(Tables.SESSIONS to setOf("s1"))))
    }

    @Test fun captureFlushableOnceParentSessionFlushed() = runTest {
        // Parent op dequeued, session row in local records → FK satisfied server-side.
        val all = listOf(op(Tables.CAPTURES, "c1", dependsOn = "s1"))
        assertEquals(listOf("c1"), flushableIds(all, mapOf(Tables.SESSIONS to setOf("s1"))))
    }

    @Test fun calBlockHeldUntilParentTaskExistsLocally() = runTest {
        val all = listOf(op(Tables.CAL_BLOCKS, "b1", dependsOn = "t1"))
        assertEquals(emptyList<String>(), flushableIds(all, mapOf(Tables.TASKS to emptySet())))
        assertEquals(listOf("b1"), flushableIds(all, mapOf(Tables.TASKS to setOf("t1"))))
    }

    @Test fun opsWithoutDependsOnAreAlwaysFlushable() = runTest {
        val all = listOf(
            op(Tables.TASKS, "t1"),
            op(Tables.REASON_LOGS, "r1"),
            op(Tables.CAL_BLOCKS, "b1", op = "delete"),
        )
        assertEquals(listOf("t1", "r1", "b1"), flushableIds(all, emptyMap()))
    }

    @Test fun unknownChildTableWithDependsOnPassesThrough() = runTest {
        // No FK parent mapping → don't hold forever; behave like the old gate.
        val all = listOf(op(Tables.TAGS, "x1", dependsOn = "whatever"))
        assertEquals(listOf("x1"), flushableIds(all, emptyMap()))
    }

    @Test fun dependsOnParentTableMapping() {
        assertEquals(Tables.TASKS, OutboxFlusher.dependsOnParentTable(Tables.CAL_BLOCKS))
        assertEquals(Tables.SESSIONS, OutboxFlusher.dependsOnParentTable(Tables.CAPTURES))
        assertEquals(null, OutboxFlusher.dependsOnParentTable(Tables.TASKS))
    }

    // --- per-row upsert coalescing (bug #2) ---

    @Test fun coalescing_keepsOnlyLatestUpsertPerRow() {
        // Two whole-row upserts for the SAME cal_block — the older (seq 1) is
        // superseded by the newer (seq 2); only the older is dropped.
        val a = op(Tables.CAL_BLOCKS, "b1")   // seq 1
        val b = op(Tables.CAL_BLOCKS, "b1")   // seq 2
        assertEquals(setOf(a.seq), OutboxFlusher.supersededUpsertSeqs(listOf(a, b)))
    }

    @Test fun coalescing_threeUpsertsDropAllButNewest() {
        val a = op(Tables.SESSIONS, "s1") // 1
        val b = op(Tables.SESSIONS, "s1") // 2
        val c = op(Tables.SESSIONS, "s1") // 3
        assertEquals(setOf(a.seq, b.seq), OutboxFlusher.supersededUpsertSeqs(listOf(a, b, c)))
    }

    @Test fun coalescing_distinctRowsAreIndependent() {
        val a = op(Tables.CAPTURES, "c1")
        val b = op(Tables.CAPTURES, "c2")
        assertEquals(emptySet<Long>(), OutboxFlusher.supersededUpsertSeqs(listOf(a, b)))
    }

    @Test fun coalescing_neverSpansADelete() {
        // upsert → delete → upsert for the same row: the trailing upsert is a
        // genuine re-create, NOT a duplicate of the first, so nothing is dropped.
        val up1 = op(Tables.TASKS, "t1")               // 1
        val del = op(Tables.TASKS, "t1", op = "delete") // 2
        val up2 = op(Tables.TASKS, "t1")               // 3
        assertEquals(emptySet<Long>(), OutboxFlusher.supersededUpsertSeqs(listOf(up1, del, up2)))
    }

    @Test fun coalescing_deletesAreNeverCollapsed() {
        val d1 = op(Tables.TASKS, "t1", op = "delete")
        val d2 = op(Tables.TASKS, "t1", op = "delete")
        assertEquals(emptySet<Long>(), OutboxFlusher.supersededUpsertSeqs(listOf(d1, d2)))
    }
}
