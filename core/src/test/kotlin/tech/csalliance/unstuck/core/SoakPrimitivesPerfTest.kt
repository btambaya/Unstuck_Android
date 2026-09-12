package tech.csalliance.unstuck.core

import org.junit.Test
import tech.csalliance.unstuck.core.logic.isCompletedToday
import tech.csalliance.unstuck.core.logic.isCreatedToday
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.time.Time

/** SOAK — the per-row time primitives the bucketing calls once per task/block. */
class SoakPrimitivesPerfTest {

    private val tasks = SoakSeed.tasks()
    private val blocks = SoakSeed.blocks(tasks)
    private val now = System.currentTimeMillis()

    @Test fun soak_time_primitives() {
        Bench.run("Time.parseMillis x800 (task.createdAt)", iters = 30) {
            var n = 0L
            for (t in tasks) n += Time.parseMillis(t.createdAt) ?: 0
            n
        }
        Bench.run("Time.startOfDayMillis x800", iters = 30) {
            var n = 0L
            for (@Suppress("UNUSED_PARAMETER") t in tasks) n += Time.startOfDayMillis(now)
            n
        }
        Bench.run("isCreatedToday x800", iters = 30) { tasks.count { isCreatedToday(it, now) } }
        Bench.run("isCompletedToday x800", iters = 30) { tasks.count { isCompletedToday(it, now) } }
        Bench.run("Clock.todayIso() x1", iters = 200) { Clock.todayIso() }
        Bench.run("blocks.filter{date==today} x4000", iters = 100) { blocks.count { it.date == SoakSeed.today } }
    }

    /** Equality of two lists whose ELEMENTS are distinct instances (what a fresh
     *  Room decode produces) — the case data-class identity short-circuit misses. */
    @Test fun soak_list_equality_distinct_instances() {
        val tasksDeep = tasks.map { it.copy() }
        val blocksDeep = blocks.map { it.copy() }
        Bench.run("List<TaskItem>.equals (800, distinct instances)", iters = 200) { tasks == tasksDeep }
        Bench.run("List<CalBlock>.equals (4000, distinct instances)", iters = 200) { blocks == blocksDeep }
        val blocksDiff = blocks.toMutableList().also { it[0] = it[0].copy(startTime = "23:59") }
        Bench.run("List<CalBlock>.equals (4000, first elem differs)", iters = 200) { blocks == blocksDiff }
        val blocksDiffLast = blocks.toMutableList().also { it[it.size - 1] = it.last().copy(startTime = "23:59") }
        Bench.run("List<CalBlock>.equals (4000, last elem differs)", iters = 200) { blocks == blocksDiffLast }
    }
}
