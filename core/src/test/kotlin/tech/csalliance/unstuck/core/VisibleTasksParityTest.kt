package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.UNASSIGNED_AREA
import tech.csalliance.unstuck.core.logic.isCompletedToday
import tech.csalliance.unstuck.core.logic.isCreatedToday
import tech.csalliance.unstuck.core.logic.isSlipping
import tech.csalliance.unstuck.core.logic.isTaskBlock
import tech.csalliance.unstuck.core.logic.isTemplate
import tech.csalliance.unstuck.core.logic.matchesArea
import tech.csalliance.unstuck.core.logic.projectOccurrences
import tech.csalliance.unstuck.core.logic.projectOverdueOccurrences
import tech.csalliance.unstuck.core.logic.visibleTasks
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.model.TaskListView
import tech.csalliance.unstuck.core.time.Clock

/**
 * BEHAVIOUR LOCK for the visibleTasks() single-pass rewrite (perf soak, 2026-09-12).
 *
 * [legacyVisibleTasks] below is the PRE-OPTIMISATION body copied verbatim — six
 * separate scans of `blocks`, `isCreatedToday`/`isCompletedToday` recomputing
 * local midnight per task, the occurrence projections always evaluated. The
 * optimised version must return the SAME rows in the SAME order for every view
 * and every filter combination, over the heavy soak account (800 tasks / 4000
 * blocks, incl. 40 recurring templates, skipped/done occurrences, `g_` external
 * blocks, tasks created today, tasks completed today and slipping tasks).
 *
 * If a future change makes these diverge, one of the two is wrong — decide
 * which, don't delete the test.
 */
class VisibleTasksParityTest {

    private val tasks = SoakSeed.tasks()
    private val blocks = SoakSeed.blocks(tasks)
    private val now = System.currentTimeMillis()

    private fun assertSame(view: TaskListView, area: String?, tag: String?, slip: Boolean) {
        val expected = legacyVisibleTasks(view, tasks, blocks, now, area, tag, slip)
        val actual = visibleTasks(view, tasks, blocks, now, area, tag, slip)
        val label = "$view area=$area tag=$tag slip=$slip"
        assertEquals("$label — row ids + order", expected.map { it.id }, actual.map { it.id })
        assertEquals("$label — whole rows", expected, actual)
    }

    @Test fun everyViewAndFilterMatchesTheLegacyImplementation() {
        val areas = listOf(null, "Work", "Health", UNASSIGNED_AREA, "NoSuchArea")
        val tags = listOf(null, "deep", "QUICK", "nope")
        for (view in TaskListView.entries) {
            for (area in areas) for (tag in tags) for (slip in listOf(false, true)) {
                assertSame(view, area, tag, slip)
            }
        }
    }

    /** The fixture has to actually exercise the interesting branches, or the
     *  parity above would be vacuous. */
    @Test fun fixtureCoversTheBranchesThatMatter() {
        val today = Clock.todayIso()
        assertTrue("recurring templates", tasks.any { it.recurrence != null })
        assertTrue("today occurrences", blocks.any { isTaskBlock(it) && it.date == today })
        assertTrue("skipped occurrences", blocks.any { it.skipped })
        assertTrue("external g_ blocks", blocks.any { it.id.startsWith("g_") })
        assertTrue("past-only scheduled tasks", visibleTasks(TaskListView.BACKLOG, tasks, blocks, now, null, null, false).isNotEmpty())
        assertTrue("today rows", visibleTasks(TaskListView.TODAY, tasks, blocks, now, null, null, false).isNotEmpty())
        assertTrue("upcoming rows", visibleTasks(TaskListView.UPCOMING, tasks, blocks, now, null, null, false).isNotEmpty())
        assertTrue("completed rows", visibleTasks(TaskListView.COMPLETED, tasks, blocks, now, null, null, false).isNotEmpty())
        assertTrue("later rows", visibleTasks(TaskListView.LATER, tasks, blocks, now, null, null, false).isNotEmpty())
        assertTrue("recurring rows", visibleTasks(TaskListView.RECURRING, tasks, blocks, now, null, null, false).isNotEmpty())
        assertTrue("slipping rows", visibleTasks(TaskListView.ALL, tasks, blocks, now, null, null, true).isNotEmpty())
    }

    // ── the pre-optimisation implementation, verbatim ────────────────────────

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
                    !t.done && t.later != true && (
                        t.id in todayTaskIds || (isCreatedToday(t, now) && t.id !in upcomingTaskIds)
                    )
                }
                nt + todayOccurrences.filter { !it.done }
            }
            TaskListView.BACKLOG ->
                nonTemplates.filter { t ->
                    !t.done && t.later != true && !isCreatedToday(t, now) && (
                        t.id !in scheduledTaskIds || t.id in pastOnlyTaskIds
                    )
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
}
