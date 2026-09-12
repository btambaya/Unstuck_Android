package tech.csalliance.unstuck.core

import org.junit.Test
import tech.csalliance.unstuck.core.logic.MomentRituals
import tech.csalliance.unstuck.core.logic.MomentState
import tech.csalliance.unstuck.core.logic.MomentTone
import tech.csalliance.unstuck.core.logic.composeBrief
import tech.csalliance.unstuck.core.logic.derivePatterns
import tech.csalliance.unstuck.core.logic.freeWindowsToday
import tech.csalliance.unstuck.core.logic.goldenHours
import tech.csalliance.unstuck.core.logic.occurrenceBlockFor
import tech.csalliance.unstuck.core.logic.patternGaps
import tech.csalliance.unstuck.core.logic.pickMoment
import tech.csalliance.unstuck.core.logic.pickTodayHero
import tech.csalliance.unstuck.core.logic.projectOccurrences
import tech.csalliance.unstuck.core.logic.projectOverdueOccurrences
import tech.csalliance.unstuck.core.logic.usableToday
import tech.csalliance.unstuck.core.logic.visibleTasks
import tech.csalliance.unstuck.core.model.TaskListView

/**
 * SOAK — :core derivations against the heavy account ([SoakSeed]). Pure JVM,
 * System.nanoTime. Measurement only; nothing here asserts a budget (the point
 * is the printed PERF lines).
 */
class SoakCoreLogicPerfTest {

    private val tasks = SoakSeed.tasks()
    private val blocks = SoakSeed.blocks(tasks)
    private val sessions = SoakSeed.sessions(tasks)
    private val today = SoakSeed.today
    private val now = System.currentTimeMillis()

    @Test fun soak_core_derivations() {
        println("PERF | seed: tasks=${tasks.size} blocks=${blocks.size} sessions=${sessions.size}")

        Bench.run("visibleTasks(TODAY)") { visibleTasks(TaskListView.TODAY, tasks, blocks, now, null, null, false) }
        Bench.run("visibleTasks(BACKLOG)") { visibleTasks(TaskListView.BACKLOG, tasks, blocks, now, null, null, false) }
        Bench.run("visibleTasks(ALL)") { visibleTasks(TaskListView.ALL, tasks, blocks, now, null, null, false) }
        Bench.run("visibleTasks(UPCOMING)") { visibleTasks(TaskListView.UPCOMING, tasks, blocks, now, null, null, false) }
        Bench.run("projectOccurrences") { projectOccurrences(tasks, blocks, today) }
        Bench.run("projectOverdueOccurrences") { projectOverdueOccurrences(tasks, blocks, today) }
        Bench.run("pickTodayHero") { pickTodayHero(tasks, blocks, now, null, null, emptySet()) }
        Bench.run("usableToday") { usableToday(blocks, today) }
        Bench.run("freeWindowsToday") { freeWindowsToday(blocks, today, "09:30") }
        Bench.run("composeBrief") { composeBrief(tasks, blocks, today, now, 90) }
        Bench.run("derivePatterns") { derivePatterns(tasks, blocks, today) }
        val pats = derivePatterns(tasks, blocks, today)
        Bench.run("patternGaps (patterns=${pats.size})") { patternGaps(pats, blocks, today) }
        Bench.run("goldenHours") { goldenHours(sessions, now) }

        val state = MomentState(
            tasks = tasks, blocks = blocks, sessions = sessions, reasons = emptyList(),
            facts = emptyList(), struggles = listOf("Starting", "Switching"),
            todayIso = today, now = now,
        )
        Bench.run("pickMoment (full state)") { pickMoment(state, MomentRituals(morning = true, evening = true, friday = true, sunday = true), MomentTone.GENTLE) }

        // The per-ROW helper the Backlog list calls once per visible row.
        val backlog = visibleTasks(TaskListView.BACKLOG, tasks, blocks, now, null, null, false)
        println("PERF | backlog rows = ${backlog.size}")
        Bench.run("occurrenceBlockFor x1 (single row)") { occurrenceBlockFor(backlog.first().id, tasks, blocks) }
        Bench.run("occurrenceBlockFor x ALL backlog rows") { backlog.forEach { occurrenceBlockFor(it.id, tasks, blocks) } }
        Bench.run("occurrenceBlockFor x 30 rows (one screen)") { backlog.take(30).forEach { occurrenceBlockFor(it.id, tasks, blocks) } }
    }

    /** What Compose's `remember(tasks, blocks, now)` costs on EVERY recomposition
     *  before the body ever runs: structural equality over the key lists. */
    @Test fun soak_list_equality_keys() {
        val tasksCopy = ArrayList(tasks)
        val blocksCopy = ArrayList(blocks)
        Bench.run("List<TaskItem>.equals (800, equal)", iters = 200) { tasks == tasksCopy }
        Bench.run("List<CalBlock>.equals (4000, equal)", iters = 200) { blocks == blocksCopy }
        Bench.run("both key lists (one remember block)", iters = 200) { (tasks == tasksCopy) && (blocks == blocksCopy) }
    }
}
