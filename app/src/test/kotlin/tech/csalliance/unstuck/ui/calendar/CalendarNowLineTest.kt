package tech.csalliance.unstuck.ui.calendar

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
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
import tech.csalliance.unstuck.core.time.ClockFormat
import tech.csalliance.unstuck.core.time.ClockMode
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.design.theme.UnstuckTheme
import tech.csalliance.unstuck.sync.WriteThrough
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.ViewModelDrain
import tech.csalliance.unstuck.ui.components.LocalClockMode
import kotlin.math.abs

/**
 * The Week view's "now" line (Ahmad 2026-09-26: "on the weekly calendar we don't
 * have that line that indicates the hour of the day like we have on the day
 * calendar"). Composes the real CalendarScreen over an empty in-memory store
 * (CalendarWeekBlockSheetTest's harness) and reads where the line, its dot and
 * the NOW pill land against the grid's own header and hour labels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xhdpi")
class CalendarNowLineTest {
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
    private lateinit var vm: AppViewModel
    private val drain = ViewModelDrain()

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, UnstuckDatabase::class.java).allowMainThreadQueries().build()
        val store = LocalStore(db)
        val graph = AppGraph(ctx, configured = false, storeOverride = store, uidOverride = { "me" })
        graph.onboarded = true
        runCatching { androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build()) }
        vm = AppViewModel(graph = graph, writeOverride = WriteThrough(store), currentUidProvider = { "me" })
    }

    @After fun teardown() {
        drain.track(vm)
        drain.drain()
        db.close()
    }

    private fun calendar(view: String, clock: ClockMode = ClockMode.H24) {
        compose.setContent {
            CompositionLocalProvider(LocalClockMode provides clock) {
                UnstuckTheme(dark = false) {
                    CalendarScreen(
                        vm = vm, onOpen = {}, onOpenShared = {}, onSearch = {}, onMenu = {}, onAvatar = {},
                        onNotifications = {}, notifUnread = 0, avatarInitials = "A", onCreateAt = { _, _ -> },
                        onStartFocus = {}, requestedView = view,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun near(a: Dp, b: Dp, tol: Dp = 1.5.dp) = abs(a.value - b.value) <= tol.value

    @Test fun theWeekViewDrawsTheNowLineAcrossTodaysColumnOnly() {
        val before = java.time.LocalTime.now()
        calendar("Week")
        val after = java.time.LocalTime.now()
        val today = java.time.LocalDate.now()

        // Exactly one line, one dot, one pill: today's column only.
        assertEquals(1, compose.onAllNodes(hasTestTag(NOW_LINE_TAG)).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodes(hasTestTag(NOW_DOT_TAG)).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodes(hasTestTag(NOW_PILL_TAG)).fetchSemanticsNodes().size)

        val line = compose.onNodeWithTag(NOW_LINE_TAG).getUnclippedBoundsInRoot()
        // Today's column: its header day number is centred over it.
        val header = compose.onNode(hasText("${today.dayOfMonth}")).getUnclippedBoundsInRoot()
        val headerMid = (header.left + header.right) / 2
        val lineMid = (line.left + line.right) / 2
        assertTrue("the line is centred on today's column ($lineMid vs header $headerMid)", near(lineMid, headerMid))
        // One column wide: (411 − 2×18 page padding − 26 gutter) / 7.
        val colW = (411.dp - 36.dp - 26.dp) / 7
        assertTrue("the line spans one column (${line.right - line.left} vs $colW)", near(line.right - line.left, colW))
        assertTrue("weight 1.5dp", near(line.bottom - line.top, NOW_LINE_THICKNESS, 0.1.dp))

        // Down the grid at the current minute: measured from the 00:00 label.
        val midnight = compose.onNodeWithText("00:00").getUnclippedBoundsInRoot()
        fun yFor(t: java.time.LocalTime) = 44.dp * ((t.hour * 60 + t.minute) / 60f)
        val y = line.top - midnight.top
        assertTrue("the line sits at now ($y vs ${yFor(before)}…${yFor(after)})", near(y, yFor(before)) || near(y, yFor(after)))

        // The dot sits on the column's leading edge, on the line.
        val dot = compose.onNodeWithTag(NOW_DOT_TAG).getUnclippedBoundsInRoot()
        assertTrue("dot centred on the column's leading edge", near((dot.left + dot.right) / 2, line.left, 0.5.dp))
        assertTrue("dot centred on the line", near((dot.top + dot.bottom) / 2, (line.top + line.bottom) / 2, 0.5.dp))

        // The pill: in the hour gutter, level with the line.
        val pill = compose.onNodeWithTag(NOW_PILL_TAG).getUnclippedBoundsInRoot()
        assertTrue("the pill sits in the gutter, left of today's column (${pill.left}…${pill.right} vs ${line.left})", pill.left < line.left && pill.right <= 18.dp + 26.dp)
        assertTrue("the pill is level with the line", pill.top <= line.bottom && pill.bottom >= line.top)
    }

    /** Opened on this week, the grid starts about an hour before now (as the Day
     *  view does), so the line is on screen — not below midnight's rows. */
    @Test fun theCurrentWeekOpensWithTheNowLineInSight() {
        calendar("Week")
        val line = compose.onNodeWithTag(NOW_LINE_TAG).getUnclippedBoundsInRoot()
        val viewport = compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag(NOW_LINE_TAG))).getUnclippedBoundsInRoot()
        assertTrue("the line (${line.top}) is inside the grid's viewport (${viewport.top}…${viewport.bottom})", line.top >= viewport.top && line.bottom <= viewport.bottom)
    }

    @Test fun anotherWeekHasNoNowLine_andTodayBringsItBack() {
        calendar("Week")
        compose.onNodeWithTag(NOW_LINE_TAG).assertExists()
        compose.onNodeWithText("›").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(NOW_LINE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(NOW_DOT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(NOW_PILL_TAG).assertDoesNotExist()

        compose.onNodeWithText("Today").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(NOW_LINE_TAG).assertExists()

        compose.onNodeWithText("‹").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(NOW_LINE_TAG).assertDoesNotExist()
    }

    /** The pill says the time the phone's way: "Now, 14:30" / "Now, 2:30 PM". */
    private fun assertPillSpeaks(mode: ClockMode) {
        val before = java.time.LocalTime.now()
        calendar("Week", mode)
        val after = java.time.LocalTime.now()
        val spoken = compose.onNodeWithTag(NOW_PILL_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        val expect = setOf(before, after).map { "Now, " + ClockFormat.time(it.hour, it.minute, mode) }
        assertTrue("$mode: \"$spoken\" not in $expect", spoken in expect)
    }

    @Test fun thePillSpeaksTheTimeIn24Hour() {
        assertPillSpeaks(ClockMode.H24)
    }

    @Test fun thePillSpeaksTheTimeIn12Hour() {
        assertPillSpeaks(ClockMode.H12)
    }

    /** The Day view still draws its line and pill — now from the shared component. */
    @Test fun theDayViewKeepsItsNowLine() {
        calendar("Day")
        assertEquals(1, compose.onAllNodes(hasTestTag(NOW_LINE_TAG)).fetchSemanticsNodes().size)
        compose.onNodeWithTag(NOW_PILL_TAG).assertExists()
        // No dot on the Day view: its line starts at the gutter, as before.
        compose.onNodeWithTag(NOW_DOT_TAG).assertDoesNotExist()
        val line = compose.onNodeWithTag(NOW_LINE_TAG).getUnclippedBoundsInRoot()
        assertTrue("from the 64dp gutter to 12dp from the edge: ${line.left}…${line.right}", near(line.left, 64.dp, 0.5.dp) && near(line.right, 411.dp - 12.dp, 0.5.dp))
    }
}
