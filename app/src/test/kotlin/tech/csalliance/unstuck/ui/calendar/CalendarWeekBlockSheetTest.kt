package tech.csalliance.unstuck.ui.calendar

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.design.theme.UnstuckTheme
import tech.csalliance.unstuck.sync.WriteThrough
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.ViewModelDrain

/**
 * The Week view's block tap (cross-platform check, 2026-09-24): a task block
 * opens the Day view's Edit-block sheet (Start focus, Mark done, Open task),
 * as iOS and web do. It used to go straight to the task screen, so Week had no
 * way to finish a task from its block. A Google event keeps its old behaviour
 * (no sheet). Composes the real CalendarScreen over an in-memory store, offline
 * (InsightsScreenTest's harness).
 *
 * SDK 33, the Compose-only sandbox (see StartRepeatingPromptTest for why): in
 * the shared 34 sandbox it hung on "Compose did not get idle" once the sheet
 * closed, but only in the full suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xhdpi")
class CalendarWeekBlockSheetTest {
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
    private lateinit var write: WriteThrough
    private lateinit var vm: AppViewModel
    private val drain = ViewModelDrain()

    // The Week view shows the current week (LocalDate.now()), so the blocks sit today.
    private val today = java.time.LocalDate.now().toString()
    private val at = "2026-09-01T12:00:00.000Z"

    /** The sheet's SectionLabel("Edit block"), which renders upper-case. */
    private val SHEET_TITLE = "EDIT BLOCK"

    private var opened: TaskItem? = null
    private var focused: TaskItem? = null

    private fun task(id: String, name: String, recurrence: Recurrence? = null) =
        TaskItem(id = id, name = name, estimateMin = 25, recurrence = recurrence, createdAt = at, updatedAt = at)

    private fun block(id: String, taskId: String?, name: String, start: String, kind: CalBlockKind = CalBlockKind.TASK, externalEventId: String? = null) =
        CalBlock(id = id, taskId = taskId, taskName = name, startTime = start, durationMinutes = 60, date = today, kind = kind, externalEventId = externalEventId)

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, UnstuckDatabase::class.java).allowMainThreadQueries().build()
        val store = LocalStore(db)
        val graph = AppGraph(ctx, configured = false, storeOverride = store, uidOverride = { "me" })
        graph.onboarded = true
        runCatching { androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build()) }
        write = WriteThrough(store)
        vm = AppViewModel(graph = graph, writeOverride = write, currentUidProvider = { "me" })
        runBlocking {
            write.upsertTask(task("t1", "Write report"))
            write.upsertTask(task("tpl", "Stretch", recurrence = Recurrence.Daily()))
            // Early hours, so every block sits in the grid's first screenful.
            write.upsertCalBlock(block("b1", "t1", "Write report", "01:00"))
            write.upsertCalBlock(block("occ1", "tpl", "Stretch", "03:00"))
            write.upsertCalBlock(block("g1", null, "Dentist", "05:00", kind = CalBlockKind.EXTERNAL, externalEventId = "evt-1"))
        }
    }

    @After fun teardown() {
        drain.track(vm)
        drain.drain()
        db.close()
    }

    private fun week() {
        compose.setContent {
            UnstuckTheme(dark = false) {
                CalendarScreen(
                    vm = vm, onOpen = { opened = it }, onOpenShared = {}, onSearch = {}, onMenu = {}, onAvatar = {},
                    onNotifications = {}, notifUnread = 0, avatarInitials = "A", onCreateAt = { _, _ -> },
                    onStartFocus = { focused = it }, requestedView = "Week",
                )
            }
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Write report").assertExists() }.isSuccess }
    }

    @Test fun tappingATaskBlockOpensTheEditBlockSheet_notTheTaskScreen() {
        week()
        compose.onNodeWithText("Write report").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(SHEET_TITLE).assertExists()
        compose.onNodeWithText("Mark done").assertExists()
        compose.onNodeWithText("Start focus").assertExists()
        assertNull("no longer straight to the task screen", opened)

        // The sheet's Open task is how Week reaches the task screen now. The sheet
        // is its own dialog window: activate through the semantics action
        // (MainScaffoldFabTest's note).
        compose.onNodeWithText("Open task").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals("t1", opened?.id)
    }

    @Test fun aRepeatingTasksBlockActsOnThatDay() {
        week()
        compose.onNodeWithText("Stretch").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(SHEET_TITLE).assertExists()
        compose.onNodeWithText("Start focus").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals("the day's occurrence row, as the Day view hands over", "occ1", focused?.id)
    }

    @Test fun aGoogleEventOpensNoSheet() {
        week()
        compose.onNodeWithText("Dentist").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(SHEET_TITLE).assertDoesNotExist()
        assertEquals("no sheet window opened", 1, compose.onAllNodes(isRoot()).fetchSemanticsNodes().size)
        assertNull(opened)
    }
}
