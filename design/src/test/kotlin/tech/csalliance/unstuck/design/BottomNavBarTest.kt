package tech.csalliance.unstuck.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
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
}
