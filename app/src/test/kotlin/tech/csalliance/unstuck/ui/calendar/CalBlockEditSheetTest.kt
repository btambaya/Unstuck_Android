package tech.csalliance.unstuck.ui.calendar

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.core.logic.CalBlockSheetActions
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.ClockMode
import tech.csalliance.unstuck.design.theme.UnstuckTheme

/**
 * The calendar Edit-block sheet's task actions on screen (Ahmad 2026-09-24:
 * "Can't complete a task from calendar"): which buttons a block gets, what
 * TalkBack calls them, and that each one fires its own action. The action → model
 * path itself is CalBlockSheetActionPathTest's.
 *
 * SDK 33, the Compose-only sandbox (see StartRepeatingPromptTest for why).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xhdpi")
class CalBlockEditSheetTest {

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

    private val taskRow = TaskItem(
        id = "t1", name = "Write report", estimateMin = 45,
        createdAt = "2026-09-20T10:00:00.000Z", updatedAt = "2026-09-20T10:00:00.000Z",
    )

    private val taps = mutableListOf<String>()

    /** [maxHeight]: the room the sheet gives the body (null = the whole screen). */
    private fun show(actions: CalBlockSheetActions, maxHeight: Dp? = null) {
        compose.setContent {
            UnstuckTheme(dark = false) {
                Box(if (maxHeight != null) Modifier.heightIn(max = maxHeight) else Modifier) {
                    CalBlockEditSheetBody(
                        taskName = "Write report", actions = actions, times = listOf("09:00", "10:30"), startTime = "09:00",
                        durationMinutes = 45, clock = ClockMode.H24,
                        onToggleDone = { taps += "toggle" }, onStartFocus = { taps += "focus" }, onOpenTask = { taps += "open" },
                        onPickTime = { taps += "time $it" }, onPickDuration = { taps += "duration $it" }, onUnschedule = { taps += "unschedule" },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun tap(description: String) {
        compose.onNodeWithContentDescription(description).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    @Test fun anOpenTask_offersStartFocus_markDone_andOpenTask_eachDoingItsOwnThing() {
        show(CalBlockSheetActions(row = taskRow, canComplete = true, canFocus = true))

        compose.onNodeWithText("Start focus").assert(isButton)
        compose.onNodeWithText("Mark done").assert(isButton)
        compose.onNodeWithText("Open task").assert(isButton)

        tap("Start focus on Write report")
        tap("Mark Write report done")
        tap("Open Write report")
        assertEquals(listOf("focus", "toggle", "open"), taps)
    }

    @Test fun aDoneTask_offersMarkNotDone_theSameToggle() {
        show(CalBlockSheetActions(row = taskRow.copy(done = true), canComplete = true, canFocus = true))

        compose.onNodeWithText("Mark done").assertDoesNotExist()
        compose.onNodeWithText("Mark not done").assert(isButton)
        tap("Mark Write report not done")
        assertEquals(listOf("toggle"), taps)
    }

    @Test fun aTaskIAssignedOut_canOnlyBeOpened() {
        show(CalBlockSheetActions(row = taskRow, canComplete = false, canFocus = false, assignedTo = "zubair@example.com"))

        compose.onNodeWithText("Start focus").assertDoesNotExist()
        compose.onNodeWithText("Mark done").assertDoesNotExist()
        compose.onNodeWithText("You assigned this to zubair — view only").assertExists()
        tap("Open Write report")
        assertEquals(listOf("open"), taps)
    }

    @Test fun aBlockWithNoTask_getsNoTaskActions_butKeepsTheBlockControls() {
        show(CalBlockSheetActions.NONE)

        compose.onNodeWithText("Start focus").assertDoesNotExist()
        compose.onNodeWithText("Mark done").assertDoesNotExist()
        compose.onNodeWithText("Mark not done").assertDoesNotExist()
        compose.onNodeWithText("Open task").assertDoesNotExist()
        // Start time, Duration and Unschedule stay as they were.
        compose.onNodeWithText("10:30").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("60m").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Unschedule").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf("time 10:30", "duration 60", "unschedule"), taps)
    }

    @Test fun aSheetShorterThanItsContent_scrollsToUnschedule() {
        // The task actions made the body ~575 dp tall at 200 % text, more than a
        // 640 dp phone's sheet holds; the bottom (Unschedule) must scroll into reach.
        show(CalBlockSheetActions(row = taskRow, canComplete = true, canFocus = true), maxHeight = 200.dp)

        compose.onNodeWithText("Unschedule").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Unschedule").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf("unschedule"), taps)
    }
}
