package tech.csalliance.unstuck.ui.tasks

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.robolectric.shadows.ShadowDialog
import tech.csalliance.unstuck.AppGraph
import tech.csalliance.unstuck.core.logic.materializeOccurrences
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Time
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.design.theme.UnstuckTheme
import tech.csalliance.unstuck.sync.WriteThrough
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.ViewModelDrain

/**
 * A repeat set on a task with no timed block: the real task screen, offline
 * (MainScaffoldFabTest's harness). The platform date and time pickers never
 * show a title (their Material dialog theme sets showTitle=false), so the
 * editor has to say "Start repeating" itself before it opens them (parity with
 * iOS build 81, audit 2026-09-22 C7).
 *
 * SDK 33, not the suite's 34, so Robolectric gives this class its own sandbox
 * and its own copy of Compose's statics. In the shared 34 sandbox,
 * MainScaffoldFabTest starts GlobalSnapshotManager on AndroidUiDispatcher.Main;
 * a later non-Compose test that writes snapshot state (VoiceSessionHolderTest)
 * makes that dispatcher schedule a dispatch, Robolectric's end-of-test looper
 * reset drops it, and the dispatcher never schedules again. Every Compose test
 * after that hangs ("Compose did not get idle"), whichever class runs second.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xhdpi")
class StartRepeatingPromptTest {

    private val compose = createComposeRule()

    // Declares the compose rule's host activity first (see MainScaffoldFabTest).
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
    private lateinit var store: LocalStore
    private lateinit var vm: AppViewModel
    private val drain = ViewModelDrain()

    private val task = TaskItem(
        id = "t1", name = "Stretch", estimateMin = 20,
        createdAt = "2026-05-21T10:00:00.000Z", updatedAt = "2026-05-21T10:00:00.000Z",
    )

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, UnstuckDatabase::class.java).allowMainThreadQueries().build()
        store = LocalStore(db)
        runBlocking { store.upsert(Tables.TASKS, task, TaskItem.serializer(), task.id, task.updatedAt) }
        val graph = AppGraph(ctx, configured = false, storeOverride = store, uidOverride = { "me" })
        runCatching { androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build()) }
        vm = drain.track(AppViewModel(graph = graph, writeOverride = WriteThrough(store), currentUidProvider = { "me" }))
        compose.setContent { UnstuckTheme(dark = false) { TaskDetailScreen(vm, task, onBack = {}, onStartFocus = {}) } }
        compose.waitForIdle()
    }

    @After fun teardown() { drain.drain(); db.close() }

    private fun tap(text: String) {
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    @Test fun weeklyOnAnUnscheduledTask_saysWhyThenOpensThePickerOnTheFirstMatchingDay() {
        tap("Weekly")
        compose.onNodeWithText("Start repeating").assertIsDisplayed()
        compose.onNodeWithText("A repeating task needs a day and a time. Pick when it starts.").assertIsDisplayed()

        tap("Pick day and time")
        compose.onNodeWithText("Start repeating").assertDoesNotExist()
        val picker = ShadowDialog.getLatestDialog() as android.app.DatePickerDialog
        assertTrue(picker.isShowing)
        // Weekly defaults to Monday: seeded on the first Monday from today, not on
        // today whatever day it is.
        val first = materializeOccurrences(Recurrence.Weekly(listOf(1)), Time.startOfDayMillis(System.currentTimeMillis()), "00:00", 35).first().date
        val p = picker.datePicker
        assertEquals(first, "%04d-%02d-%02d".format(p.year, p.month + 1, p.dayOfMonth))
    }

    @Test fun cancellingThePrompt_abandonsTheRepeat() {
        tap("Daily")
        tap("Cancel")
        compose.onNodeWithText("Start repeating").assertDoesNotExist()
        assertTrue(ShadowDialog.getShownDialogs().none { it is android.app.DatePickerDialog })
        assertNull(runBlocking { store.tasks().first().first { it.id == "t1" }.recurrence })
        assertTrue(runBlocking { store.blocks().first() }.none { it.taskId == "t1" })
    }
}
