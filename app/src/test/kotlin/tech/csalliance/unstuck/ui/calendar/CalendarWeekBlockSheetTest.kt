package tech.csalliance.unstuck.ui.calendar

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * way to finish a task from its block. A Google event opens no sheet and, as in
 * the Day view and on iOS, its tap stops there (it used to fall through to the
 * grid and open New task at the event's time). Also the strike on the real
 * Week grid, Day grid and Month peek: blockIsDone, including the reported
 * repro's end state (a one-off whose block kept a stale done). Composes the
 * real CalendarScreen over an in-memory store, offline (InsightsScreenTest's
 * harness).
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
    /** onCreateAt's (date, time): a tap that fell through a block to the grid. */
    private var created: Pair<String, String>? = null

    private fun task(id: String, name: String, recurrence: Recurrence? = null) =
        TaskItem(id = id, name = name, estimateMin = 25, recurrence = recurrence, createdAt = at, updatedAt = at)

    private fun block(id: String, taskId: String?, name: String, start: String, kind: CalBlockKind = CalBlockKind.TASK, externalEventId: String? = null, done: Boolean = false) =
        CalBlock(id = id, taskId = taskId, taskName = name, startTime = start, durationMinutes = 60, date = today, kind = kind, externalEventId = externalEventId, done = done)

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
            // Early hours. The Week grid opens about an hour before NOW on the
            // current week (so its now line is in sight), so a tap scrolls the
            // block into view first (tapBlock).
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

    private fun week(view: String = "Week") {
        compose.setContent {
            UnstuckTheme(dark = false) {
                CalendarScreen(
                    vm = vm, onOpen = { opened = it }, onOpenShared = {}, onSearch = {}, onMenu = {}, onAvatar = {},
                    onNotifications = {}, notifUnread = 0, avatarInitials = "A", onCreateAt = { d, t -> created = d to t },
                    onStartFocus = { focused = it }, requestedView = view,
                )
            }
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Write report").assertExists() }.isSuccess }
    }

    /** Scroll the grid to the block, then tap it, as a finger would. */
    private fun tapBlock(name: String) {
        compose.onNodeWithText(name).performScrollTo().performClick()
    }

    @Test fun tappingATaskBlockOpensTheEditBlockSheet_notTheTaskScreen() {
        week()
        tapBlock("Write report")
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
        tapBlock("Stretch")
        compose.waitForIdle()

        compose.onNodeWithText(SHEET_TITLE).assertExists()
        compose.onNodeWithText("Start focus").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals("the day's occurrence row, as the Day view hands over", "occ1", focused?.id)
    }

    @Test fun aGoogleEventOpensNoSheet() {
        week()
        tapBlock("Dentist")
        compose.waitForIdle()

        compose.onNodeWithText(SHEET_TITLE).assertDoesNotExist()
        assertEquals("no sheet window opened", 1, compose.onAllNodes(isRoot()).fetchSemanticsNodes().size)
        assertNull(opened)
        // View only, as in the Day view and on iOS: the tap never falls through
        // to the grid's create-a-task-here (ANDROID-TEST-CHECKLIST: "grid behind
        // not tappable").
        assertNull("a Google event's tap must not create a task at its time", created)
        assertEquals("the Day view's hint", "From Google Calendar — view only here.", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
    }

    /** Whether the grid draws [text] struck through (its Text's own style). */
    private fun struck(text: String): Boolean {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val out = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.single().layoutInput.style.textDecoration == androidx.compose.ui.text.style.TextDecoration.LineThrough
    }

    /** The four cases where blockIsDone and the old `block.done || task.done` part
     *  ways or must agree, seeded as blocks today. */
    private fun seedStrikeCases() = runBlocking {
        // A one-off with a stale done on its block: the reported repro's end state
        // (a day ticked while it repeated, repeat → Never, Mark not done).
        write.upsertTask(task("t2", "Reopened"))
        write.upsertCalBlock(block("b2", "t2", "Reopened", "07:00", done = true))
        // A series the old path ended (template done): its open day is not done.
        write.upsertTask(task("t3", "Ended series", recurrence = Recurrence.Daily()).copy(done = true, completedAt = at))
        write.upsertCalBlock(block("occ3", "t3", "Ended series", "09:00"))
        // A one-off done on its task: struck.
        write.upsertTask(task("t4", "Finished").copy(done = true, completedAt = at))
        write.upsertCalBlock(block("b4", "t4", "Finished", "11:00"))
        // A repeating task's ticked day: struck by its own block.
        write.upsertTask(task("t5", "Walk", recurrence = Recurrence.Daily()))
        write.upsertCalBlock(block("occ5", "t5", "Walk", "13:00", done = true))
    }

    private fun assertStrikesByBlockIsDone() {
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Walk", useUnmergedTree = true).assertExists() }.isSuccess }
        assertFalse("a one-off's stale block done is not struck (the repro)", struck("Reopened"))
        assertFalse("an ended series' open day is not struck", struck("Ended series"))
        assertTrue("a done one-off is struck", struck("Finished"))
        assertTrue("a ticked repeating day is struck", struck("Walk"))
        assertFalse("an open one-off is not struck", struck("Write report"))
    }

    @Test fun theWeekGridStrikesByBlockIsDone() {
        seedStrikeCases()
        week()
        assertStrikesByBlockIsDone()
    }

    @Test fun theDayGridStrikesByBlockIsDone() {
        seedStrikeCases()
        week(view = "Day")
        assertStrikesByBlockIsDone()
    }

    @Test fun theMonthPeekStrikesByBlockIsDone() {
        seedStrikeCases()
        compose.setContent {
            UnstuckTheme(dark = false) {
                MonthDayPeekSheet(vm = vm, iso = today, onOpen = {}, onOpenShared = {}, onOpenDay = {}, onDismiss = {})
            }
        }
        compose.waitForIdle()
        assertStrikesByBlockIsDone()
    }
}
