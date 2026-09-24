package tech.csalliance.unstuck.ui.insights

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.AppGraph
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.design.theme.UnstuckTheme
import tech.csalliance.unstuck.sync.WriteThrough
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.ViewModelDrain
import java.time.Instant

/**
 * The Insights page composed for real over an in-memory store (analytics
 * build, 2026-09-24): the headline, daily rhythm, got-unstuck, plan and
 * repeating cards render from the seeded rows, the ‹ stepper reaches last
 * week, and the Deep dive draws the trend / done-by-area / heatmap without
 * crashing. Offline by construction, like MainScaffoldFabTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xhdpi")
class InsightsScreenTest {
    private val compose = createComposeRule()
    private val hostActivity = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
                shadowOf(ctx.packageManager).addActivityIfNotPresent(ComponentName(ctx, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(hostActivity).around(compose)

    private lateinit var db: UnstuckDatabase
    private lateinit var graph: AppGraph
    private lateinit var write: WriteThrough
    private lateinit var vm: AppViewModel
    private val drain = ViewModelDrain()

    // Thu 24 Sep 2026, mid-afternoon; every stamp is midday UTC so any test zone
    // puts it on the same local day.
    private val now = Instant.parse("2026-09-24T15:30:00Z").toEpochMilli()
    private val at = "2026-09-01T12:00:00.000Z"

    private fun task(id: String, name: String, done: Boolean = false, completedAt: String? = null, createdAt: String = at,
                     recurrence: Recurrence? = null, lifeArea: String? = null) =
        TaskItem(id = id, name = name, estimateMin = 25, done = done, completedAt = completedAt, recurrence = recurrence,
            lifeArea = lifeArea, createdAt = createdAt, updatedAt = createdAt)

    private fun block(id: String, taskId: String, date: String, done: Boolean = false, skipped: Boolean = false, completedAt: String? = null) =
        CalBlock(id = id, taskId = taskId, taskName = taskId, startTime = "09:00", durationMinutes = 30, date = date,
            kind = CalBlockKind.TASK, done = done, skipped = skipped, completedAt = completedAt)

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, UnstuckDatabase::class.java).allowMainThreadQueries().build()
        val store = LocalStore(db)
        graph = AppGraph(ctx, configured = false, storeOverride = store, uidOverride = { "me" })
        graph.onboarded = true
        runCatching { androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build()) }
        write = WriteThrough(store)
        vm = AppViewModel(graph = graph, writeOverride = write, currentUidProvider = { "me" }, nowProvider = { now })
        runBlocking {
            write.upsertLifeArea(LifeArea("a1", "Health", "green", 0))
            // This week (Mon 21 – Thu 24).
            write.upsertTask(task("t1", "Draft chapter", done = true, completedAt = "2026-09-22T12:00:00.000Z", createdAt = "2026-09-10T12:00:00.000Z"))
            write.upsertTask(task("t2", "Call the bank", done = true, completedAt = "2026-09-23T12:00:00.000Z", createdAt = "2026-09-22T12:00:00.000Z"))
            write.upsertTask(task("t3", "Tax return"))
            write.upsertTask(task("t4", "Stretch", recurrence = Recurrence.Daily(), lifeArea = "Health"))
            write.upsertCalBlock(block("b3", "t3", "2026-09-22"))
            write.upsertCalBlock(block("s21", "t4", "2026-09-21", done = true, completedAt = "2026-09-21T12:00:00.000Z"))
            write.upsertCalBlock(block("s22", "t4", "2026-09-22", skipped = true))
            write.upsertCalBlock(block("s23", "t4", "2026-09-23"))
            write.upsertCalBlock(block("s24", "t4", "2026-09-24"))
            write.upsertSession(Session(id = "x1", taskId = "t1", taskName = "Draft chapter", estimateMin = 25, actualSec = 50 * 60, completedAt = "2026-09-22T12:00:00.000Z"))
            write.upsertSession(Session(id = "x2", taskId = "t1", taskName = "Draft chapter", estimateMin = 25, actualSec = 10, completedAt = "2026-09-23T12:00:00.000Z"))
            // Last week.
            write.upsertTask(task("t5", "Book dentist", done = true, completedAt = "2026-09-16T12:00:00.000Z"))
            write.upsertSession(Session(id = "x3", taskId = "t5", taskName = "Book dentist", estimateMin = 25, actualSec = 40 * 60, completedAt = "2026-09-16T12:00:00.000Z"))
        }
    }

    @After fun teardown() {
        drain.track(vm)
        drain.drain()
        db.close()
    }

    private var deep by mutableStateOf(false)

    private fun screen(model: AppViewModel = vm) {
        compose.setContent { UnstuckTheme(dark = false) { InsightsScreen(model, deep = deep, onBack = {}, onToggleDeep = { deep = it }) } }
        compose.waitForIdle()
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
    }

    @Test fun reportTellsThisWeeksStory() {
        screen()
        compose.onNodeWithText("This week").assertExists()
        // Headline: 3 done (2 tasks + 1 repeating), 50m focused (the 10-second start doesn't count), 3 of 4 days.
        compose.onNodeWithContentDescription("Done 3, 2 tasks · 1 repeating · added 1, +2 vs before").assertExists()
        compose.onNodeWithContentDescription("Focused 50m, 1 session, +10m vs before").assertExists()
        compose.onNodeWithText("3 of 4").assertExists()
        scrollTo("Got unstuck")
        compose.onNodeWithText("waited 12 days").assertExists()
        scrollTo("Plan vs followed through")
        compose.onNodeWithText("Followed through on 1 of 3 planned.").assertExists()
        scrollTo("Repeating tasks")
        compose.onNodeWithText("kept 1 of 3 so far").assertExists()
    }

    @Test fun stepperReachesLastWeek() {
        screen()
        compose.onNodeWithContentDescription("Previous week").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Last week").assertExists()
        compose.onNodeWithContentDescription("Done 1, 1 task, +1 vs before").assertExists()
        compose.onNodeWithContentDescription("Next week").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("This week").assertExists()
    }

    @Test fun deepDiveDrawsTheTrendAndPatterns() {
        deep = true
        screen()
        compose.onNodeWithText("8 weeks").assertExists()
        scrollTo("Done by area")
        compose.onAllNodesWithText("Health").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        scrollTo("Hour × day")
        compose.onNodeWithText("Hour × day").assertExists()
    }

    /** Monday morning with nothing yet: never "No focus sessions yet" to someone
     *  with history — say so honestly and offer last week (cross-check P0-8). */
    @Test fun mondayMorningPointsAtLastWeek() {
        val monday = Instant.parse("2026-09-21T06:00:00Z").toEpochMilli()
        val early = AppViewModel(graph = graph, writeOverride = write, currentUidProvider = { "me" }, nowProvider = { monday })
        try {
            screen(early)
            compose.onNodeWithText("Nothing logged this week yet.").assertExists()
            compose.onNodeWithText("Last week: 1 done · 40m focused →").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Last week").assertExists()
            compose.onNodeWithText("Nothing logged this week yet.").assertDoesNotExist()
        } finally {
            drain.track(early)
        }
    }

    @Test fun monthAndAllTimeRender() {
        screen()
        compose.onNodeWithText("Month").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("This month").assertExists()
        compose.onNodeWithText("All time").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Every week so far").assertExists()
    }
}
