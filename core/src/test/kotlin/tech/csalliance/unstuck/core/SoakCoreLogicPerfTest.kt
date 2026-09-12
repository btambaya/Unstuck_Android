package tech.csalliance.unstuck.core

import org.junit.Test
import tech.csalliance.unstuck.core.logic.MomentRituals
import tech.csalliance.unstuck.core.logic.MomentState
import tech.csalliance.unstuck.core.logic.MomentTone
import tech.csalliance.unstuck.core.logic.composeBrief
import tech.csalliance.unstuck.core.logic.derivePatterns
import tech.csalliance.unstuck.core.logic.freeWindowsToday
import tech.csalliance.unstuck.core.logic.isCompletedToday
import tech.csalliance.unstuck.core.logic.isCreatedToday
import tech.csalliance.unstuck.core.logic.isSlipping
import tech.csalliance.unstuck.core.logic.isTaskBlock
import tech.csalliance.unstuck.core.logic.isTemplate
import tech.csalliance.unstuck.core.logic.matchesArea
import tech.csalliance.unstuck.core.logic.overdueOccurrenceLabel
import tech.csalliance.unstuck.core.logic.overdueOccurrenceLabels
import tech.csalliance.unstuck.core.logic.goldenHours
import tech.csalliance.unstuck.core.logic.occurrenceBlockFor
import tech.csalliance.unstuck.core.logic.patternGaps
import tech.csalliance.unstuck.core.logic.pickMoment
import tech.csalliance.unstuck.core.logic.pickTodayHero
import tech.csalliance.unstuck.core.logic.projectOccurrences
import tech.csalliance.unstuck.core.logic.projectOverdueOccurrences
import tech.csalliance.unstuck.core.logic.usableToday
import tech.csalliance.unstuck.core.logic.visibleTasks
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.model.TaskListView
import tech.csalliance.unstuck.core.time.Clock

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

    /**
     * SAME-RUN A/B for the perf-soak fixes (2026-09-12). Cross-run comparison of
     * these medians is too noisy to trust (the untouched micro-benches move
     * +-40% between JVM runs), so each optimised path is timed here against the
     * exact expression it replaced, interleaved in one process.
     *   BEFORE = the pre-optimisation body (kept verbatim in
     *   VisibleTasksParityTest / OccurrencesTest, which also assert the two
     *   produce identical results).
     */
    @Test fun soak_ab_optimisations() {
        val backlog = visibleTasks(TaskListView.BACKLOG, tasks, blocks, now, null, null, false)
        println("PERF | A/B backlog rows = ${backlog.size}")

        // 1. Backlog overdue badges: per-row scan vs one indexed pass.
        Bench.run("A/B overdue labels BEFORE: per-row x ALL ${backlog.size} rows") {
            backlog.forEach { overdueOccurrenceLabel(it.id, tasks, blocks, today) }
        }
        Bench.run("A/B overdue labels AFTER : one pass for ALL ${backlog.size} rows") {
            overdueOccurrenceLabels(backlog.map { it.id }, tasks, blocks, today)
        }
        Bench.run("A/B overdue labels BEFORE: per-row x 30-row screen") {
            backlog.take(30).forEach { overdueOccurrenceLabel(it.id, tasks, blocks, today) }
        }

        // 2. visibleTasks: six block scans vs one.
        for (view in listOf(TaskListView.TODAY, TaskListView.BACKLOG, TaskListView.ALL, TaskListView.UPCOMING)) {
            Bench.run("A/B visibleTasks($view) BEFORE") { legacyVisibleTasks(view, tasks, blocks, now, null, null, false) }
            Bench.run("A/B visibleTasks($view) AFTER ") { visibleTasks(view, tasks, blocks, now, null, null, false) }
        }

        // 3. pickTodayHero re-bucketing TODAY that the caller already computed.
        val todayRows = visibleTasks(TaskListView.TODAY, tasks, blocks, now, null, null, false)
        Bench.run("A/B pickTodayHero BEFORE: re-buckets TODAY itself") { pickTodayHero(tasks, blocks, now, null, null, emptySet()) }
        Bench.run("A/B pickTodayHero AFTER : given the caller's rows") { pickTodayHero(tasks, blocks, now, null, null, emptySet(), todayRows) }
        Bench.run("A/B Today derivation BEFORE: visibleTasks + hero") {
            val r = legacyVisibleTasks(TaskListView.TODAY, tasks, blocks, now, null, null, false)
            pickTodayHero(tasks, blocks, now, null, null, emptySet()) to r
        }
        Bench.run("A/B Today derivation AFTER : visibleTasks + hero") {
            val r = visibleTasks(TaskListView.TODAY, tasks, blocks, now, null, null, false)
            pickTodayHero(tasks, blocks, now, null, null, emptySet(), r) to r
        }
    }

    /** The pre-optimisation visibleTasks body, verbatim (see
     *  VisibleTasksParityTest, which asserts it agrees with the current one). */
    @Suppress("CyclomaticComplexMethod")
    private fun legacyVisibleTasks(
        view: TaskListView,
        tasks: List<TaskItem>,
        blocks: List<CalBlock>,
        now: Long,
        activeArea: String?,
        activeTag: String? = null,
        slipMode: Boolean,
    ): List<TaskItem> {
        val today = Clock.todayIso()
        val nonTemplates = tasks.filter { !isTemplate(it) }
        val templateIds = tasks.filter { it.recurrence != null }.map { it.id }.toSet()
        val occBlocks = blocks.filter { isTaskBlock(it) && !it.skipped && it.taskId in templateIds && it.date >= today }
        val todayOccIds = occBlocks.filter { it.date == today }.map { it.id }.toSet()
        val nextPerTemplate = HashMap<String, CalBlock>()
        for (b in occBlocks) {
            if (b.date <= today) continue
            val tid = b.taskId ?: continue
            val cur = nextPerTemplate[tid]
            if (cur != null && cur.date <= b.date) continue
            nextPerTemplate[tid] = b
        }
        val nextUpcomingOccIds = nextPerTemplate.values.map { it.id }.toSet()
        val projected = projectOccurrences(tasks, blocks, today)
        val todayOccurrences = projected.filter { it.id in todayOccIds }
        val upcomingOccurrences = projected.filter { it.id in nextUpcomingOccIds }
        val overdueOccurrences = projectOverdueOccurrences(tasks, blocks, today)
        val taskBlocks = blocks.filter { isTaskBlock(it) && it.taskId !in templateIds }
        val todayTaskIds = taskBlocks.filter { it.date == today }.mapNotNull { it.taskId }.toSet()
        val upcomingTaskIds = taskBlocks.filter { it.date > today }.mapNotNull { it.taskId }.toSet()
        val scheduledTaskIds = taskBlocks.mapNotNull { it.taskId }.toSet()
        val pastOnlyTaskIds = scheduledTaskIds.filter { it !in todayTaskIds && it !in upcomingTaskIds }.toSet()
        val byView = when (view) {
            TaskListView.RECURRING -> tasks.filter { isTemplate(it) }
            TaskListView.TODAY -> {
                val nt = nonTemplates.filter { t ->
                    !t.done && t.later != true && (t.id in todayTaskIds || (isCreatedToday(t, now) && t.id !in upcomingTaskIds))
                }
                nt + todayOccurrences.filter { !it.done }
            }
            TaskListView.BACKLOG ->
                nonTemplates.filter { t ->
                    !t.done && t.later != true && !isCreatedToday(t, now) &&
                        (t.id !in scheduledTaskIds || t.id in pastOnlyTaskIds)
                } + overdueOccurrences
            TaskListView.UPCOMING -> {
                val nt = nonTemplates.filter { t -> !t.done && t.id in upcomingTaskIds && t.id !in todayTaskIds }
                nt + upcomingOccurrences.filter { !it.done }
            }
            TaskListView.LATER -> nonTemplates.filter { !it.done && it.later == true }
            TaskListView.COMPLETED -> nonTemplates.filter { it.done }
            TaskListView.ALL -> nonTemplates.filter { !it.done || isCompletedToday(it, now) }
        }
        val afterArea = if (view == TaskListView.TODAY) byView else byView.filter { matchesArea(it.lifeArea, activeArea) }
        val afterTag = if (!activeTag.isNullOrEmpty()) {
            afterArea.filter { (it.tags ?: emptyList()).any { n -> n.lowercase() == activeTag.lowercase() } }
        } else {
            afterArea
        }
        val afterSlip = if (slipMode) afterTag.filter { isSlipping(it, now) } else afterTag
        return afterSlip.filter { !it.done } + afterSlip.filter { it.done }
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
