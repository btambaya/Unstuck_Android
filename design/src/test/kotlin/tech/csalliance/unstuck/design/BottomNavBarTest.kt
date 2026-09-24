package tech.csalliance.unstuck.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getAlignmentLinePosition
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.design.component.BottomNavBar
import tech.csalliance.unstuck.design.component.NavSpec
import tech.csalliance.unstuck.design.theme.UnstuckTheme

/**
 * The bottom chrome's BEHAVIOUR — composed, not inspected.
 *
 * The rest of this module's suite is colour + token maths, which never renders
 * anything, so the bar's `fabLabel` plumbing (added when the + became
 * surface-aware) had no coverage at all: nothing proved the label reaches the
 * button's `contentDescription`, and TalkBack is the ONLY place the + 's
 * per-surface meaning is ever announced. These tests compose the real bar on
 * the JVM (Robolectric) and read the semantics tree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BottomNavBarTest {

    @get:Rule val compose = createComposeRule()

    private val items = listOf(
        NavSpec("today", "Today", Icons.Outlined.Schedule),
        NavSpec("tasks", "Tasks", Icons.Outlined.Inbox),
        NavSpec("calendar", "Calendar", Icons.Outlined.CalendarMonth),
        NavSpec("lists", "Collections", Icons.Outlined.Layers),
    )

    private fun bar(
        activeKey: String = "today",
        fabLabel: String = "New",
        onFab: () -> Unit = {},
        onSelect: (String) -> Unit = {},
    ) = compose.setContent {
        UnstuckTheme(dark = false) {
            BottomNavBar(items, activeKey, onSelect = onSelect, onFab = onFab, fabLabel = fabLabel)
        }
    }

    /** The label passed in is what the + announces — the whole point of the param. */
    @Test
    fun fabAnnouncesTheLabelItIsGiven() {
        bar(fabLabel = "New collection")
        compose.onNodeWithContentDescription("New collection").assertIsDisplayed()
    }

    /** ...and a different label really does change it (not a hardcoded string). */
    @Test
    fun aDifferentLabelReallyChangesTheAnnouncement() {
        bar(fabLabel = "New task")
        compose.onNodeWithContentDescription("New task").assertIsDisplayed()
        compose.onNodeWithContentDescription("New collection").assertDoesNotExist()
    }

    /** Back-compat: callers that don't pass a label still get a usable button. */
    @Test
    fun fabLabelDefaultsToNew() {
        bar()
        compose.onNodeWithContentDescription("New").assertIsDisplayed()
    }

    /** The announced button is the one that fires — label and action are one node. */
    @Test
    fun clickingTheAnnouncedFabCallsOnFab() {
        var fired = 0
        bar(fabLabel = "New collection", onFab = { fired++ })
        compose.onNodeWithContentDescription("New collection").assertHasClickAction().performClick()
        assertEquals(1, fired)
    }

    /** All four tabs render and report their key on selection. */
    @Test
    fun tabsSelectByKey() {
        var picked: String? = null
        bar(onSelect = { picked = it })
        items.forEach { compose.onNodeWithText(it.label).assertIsDisplayed() }
        compose.onNodeWithText("Collections").performClick()
        assertEquals("lists", picked)
    }

    // ── Layout: the + is on the same line as the tabs ────────────────────────

    private fun DpRect.centreX() = (left.value + right.value) / 2
    private fun DpRect.centreY() = (top.value + bottom.value) / 2
    private fun cell(label: String) = compose.onNode(hasText(label) and isSelectable()).getUnclippedBoundsInRoot()

    /**
     * The + sits IN the row — inside the bar, vertically centred on the tab
     * cells' full height (pill + label) — as the middle of five equal slots. The
     * old + was lifted 28dp above the bar, over the list's last row.
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun plusSitsInTheRowCentredOnTheTabs() {
        bar(fabLabel = "New task")
        val plus = compose.onNodeWithContentDescription("New task").getUnclippedBoundsInRoot()
        val cells = items.map { cell(it.label) }

        assertEquals(44f, plus.width.value, 0.5f)
        assertEquals(44f, plus.height.value, 0.5f)
        cells.forEach { assertEquals("+ centred on ${it}", it.centreY(), plus.centreY(), 0.5f) }
        assertTrue("+ not lifted above the tabs", plus.top >= cells[0].top && plus.bottom <= cells[0].bottom)

        // Five equal slots: four equal tabs, the gap between Tasks and Calendar
        // is exactly one slot wide, and the + is centred in it.
        val slot = cells[0].width.value
        cells.forEach { assertEquals(slot, it.width.value, 0.5f) }
        assertEquals(slot, cells[2].left.value - cells[1].right.value, 0.5f)
        assertEquals((cells[1].right.value + cells[2].left.value) / 2, plus.centreX(), 0.5f)
    }

    /** One line: every tab cell has the same top and bottom, and every label
     *  shares one baseline — active (SemiBold) or not, whichever tab is active. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun tabsShareOneLineAndOneBaseline() {
        bar(activeKey = "lists")
        val cells = items.map { cell(it.label) }
        cells.forEach {
            assertEquals(cells[0].top.value, it.top.value, 0.01f)
            assertEquals(cells[0].bottom.value, it.bottom.value, 0.01f)
        }
        val baselines = items.map {
            val label = compose.onNodeWithText(it.label, useUnmergedTree = true)
            label.getUnclippedBoundsInRoot().top.value + label.getAlignmentLinePosition(FirstBaseline).value
        }
        baselines.forEach { assertEquals(baselines[0], it, 0.01f) }
    }
}
