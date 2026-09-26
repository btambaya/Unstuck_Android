package tech.csalliance.unstuck.ui

import android.content.ComponentName
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import tech.csalliance.unstuck.core.model.CollectionItem
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.design.theme.UnstuckTheme
import tech.csalliance.unstuck.sync.WriteThrough

/**
 * The on-screen keyboard over the real [MainScaffold] (Ahmad 2026-09-24, iOS
 * build 97: "I am trying to edit an addition at the bottom of the list, but the
 * keyboard is covered in it and I can't scroll").
 *
 * The keyboard is simulated the way the platform reports it to an edge-to-edge
 * app: an IME inset dispatched to the Compose view. MainActivity calls
 * enableEdgeToEdge(), so `adjustResize` does NOT shrink the window — the IME
 * inset is the only thing that moves anything, and what moves is up to the
 * layout. That is exactly what these tests pin:
 *
 *  - a field being typed into sits ABOVE the keyboard (the edited list item,
 *    the collection's add field — also after adding a few — and the task
 *    detail's capture field at the bottom of its scroll) — and so does the card
 *    or pill drawn around it, not just its line of text (keepInViewWhileTyping);
 *  - that holds in a long list and in landscape, and when the keyboard goes the
 *    list gets its full height back;
 *  - the bottom bar does NOT ride up on top of the keyboard.
 *
 * Offline, MainScaffoldFabTest's harness. SDK 33 for its own Compose sandbox
 * (StartRepeatingPromptTest explains why).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xhdpi")
class KeyboardInsetsTest {

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
    private lateinit var composeView: View
    private val drain = ViewModelDrain()

    /** Twelve items: the list fits the screen with the keyboard DOWN, so its last
     *  rows and the add field sit in the bottom third — right where it comes up. */
    private val items = (1..12).map { n ->
        CollectionItem(id = "i$n", body = "Sync item $n", at = "2026-09-24T09:%02d:00.000Z".format(java.util.Locale.ROOT, n))
    }
    private val list = ItemCollection(id = "c1", name = "Zubair/Ahmad sync up", color = "indigo", items = items, sortOrder = 0, ownerId = UID)
    /** A list far taller than the screen. */
    private val longItems = (1..40).map { n ->
        CollectionItem(id = "L$n", body = "Long item $n", at = "2026-09-24T10:%02d:00.000Z".format(java.util.Locale.ROOT, n))
    }
    private val longList = ItemCollection(id = "c2", name = "Long list", color = "green", items = longItems, sortOrder = 1, ownerId = UID)

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, UnstuckDatabase::class.java).allowMainThreadQueries().build()
        store = LocalStore(db)
        runBlocking {
            store.upsert(Tables.COLLECTIONS, list, ItemCollection.serializer(), list.id)
            store.upsert(Tables.COLLECTIONS, longList, ItemCollection.serializer(), longList.id)
        }
        val graph = AppGraph(ctx, configured = false, storeOverride = store, uidOverride = { UID })
        graph.onboarded = true
        runCatching { androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build()) }
        vm = drain.track(AppViewModel(graph = graph, writeOverride = WriteThrough(store), currentUidProvider = { UID }))
        compose.setContent {
            composeView = LocalView.current
            UnstuckTheme(dark = false) { MainScaffold(vm) }
        }
        compose.waitForIdle()
    }

    @After fun teardown() { drain.drain(); db.close() }

    // ── the keyboard ─────────────────────────────────────────────────────────

    /** The keyboard height last reported (0 = hidden). */
    private var keyboardNow: Dp = 0.dp

    /** Report an IME of [height] (0 = hidden) to the Compose view, as the
     *  platform does for an edge-to-edge window. */
    private fun keyboard(height: Dp) {
        keyboardNow = height
        compose.runOnIdle {
            val px = (height.value * composeView.resources.displayMetrics.density).toInt()
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, px))
                .setVisible(WindowInsetsCompat.Type.ime(), px > 0)
                .build()
            ViewCompat.dispatchApplyWindowInsets(composeView, insets)
        }
        compose.waitForIdle()
    }

    private fun rootBottom(): Dp = compose.onRoot().getUnclippedBoundsInRoot().bottom
    /** Where the keyboard's top edge is, in root coordinates. */
    private fun keyboardTop(): Dp = rootBottom() - keyboardNow

    private fun openTheList(col: ItemCollection = list) {
        vm.openDeepLink("unstuck://collections/${col.id}")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(INLINE_ADD)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /** [below]: how far the field's card / pill reaches below its line of text —
     *  that must clear the keyboard too, not be cut in half by its edge. */
    private fun assertAboveTheKeyboard(what: String, node: androidx.compose.ui.test.SemanticsNodeInteraction, below: Dp = 0.dp) {
        val b = node.getUnclippedBoundsInRoot()
        val top = keyboardTop()
        assertTrue("$what must sit above the keyboard: its bottom (+$below of its container) is ${b.bottom + below}, the keyboard's top is $top", b.bottom + below <= top + 0.5.dp)
        assertTrue("$what must still be on screen: its top is ${b.top}", b.top >= 0.dp)
    }

    /** The bottom edge of the scroll [node] is seen through. */
    private fun viewportBottom(node: androidx.compose.ui.test.SemanticsNodeInteraction): Dp {
        var n: androidx.compose.ui.semantics.SemanticsNode? = node.fetchSemanticsNode().parent
        while (n != null && !n.config.contains(androidx.compose.ui.semantics.SemanticsActions.ScrollBy)) n = n.parent
        val px = n?.boundsInRoot?.bottom ?: error("$node is not in a scroll")
        return (px / composeView.resources.displayMetrics.density).dp
    }

    /** Hold the lowest on-screen item a keyboard of [kb] would cover; returns its editor. */
    private fun holdTheLowestCoveredItem(of: List<CollectionItem>, kb: Dp): androidx.compose.ui.test.SemanticsNodeInteraction {
        val bottom = rootBottom()
        val target = of.reversed().firstOrNull { item ->
            compose.onAllNodes(hasContentDescription(item.body)).fetchSemanticsNodes().isNotEmpty() &&
                compose.onNodeWithContentDescription(item.body).getUnclippedBoundsInRoot().let { b -> b.top > bottom - kb && b.bottom < bottom }
        } ?: error("precondition: no item sits where the keyboard comes up")
        compose.onNodeWithContentDescription(target.body).performTouchInput { longClick() }
        compose.waitForIdle()
        return compose.onNode(hasSetTextAction() and hasText(target.body)).also { it.assertExists() }
    }

    // ── a collection ─────────────────────────────────────────────────────────

    /** THE report: hold an item near the bottom to edit it, the keyboard comes
     *  up — the field being edited must end up above it, not under it. */
    @Test fun anItemHeldForEditingNearTheBottomEndsUpAboveTheKeyboard() {
        openTheList()
        keyboard(0.dp)
        val field = holdTheLowestCoveredItem(items, KEYBOARD)
        keyboard(KEYBOARD)
        assertAboveTheKeyboard("the item being edited", field, below = CARD_BELOW)
    }

    /** Opening a list puts the cursor in its add field (at the BOTTOM of the
     *  list); the keyboard that brings must not hide the field it opened for. */
    @Test fun theAddFieldIsNotLeftUnderTheKeyboard() {
        openTheList()
        keyboard(KEYBOARD)
        assertAboveTheKeyboard("the add field", compose.onNode(hasSetTextAction() and hasText(INLINE_ADD)), below = PILL_BELOW)
    }

    /** "…or adding a new one at the bottom": each new item lands right above the
     *  add field and pushes it down — it must stay above the keyboard. */
    @Test fun addingSeveralItemsKeepsTheAddFieldAboveTheKeyboard() {
        openTheList()
        keyboard(KEYBOARD)
        repeat(3) { n ->
            val body = "Added $n"
            compose.onNode(hasSetTextAction() and hasText(INLINE_ADD)).performTextInput(body)
            // Typed into, the field no longer shows its placeholder: find it by what it holds.
            compose.onNode(hasSetTextAction() and hasText(body)).performImeAction()
            compose.waitUntil(WAIT_MS) {
                shadowOf(Looper.getMainLooper()).idle()
                vm.collections.value.firstOrNull { it.id == list.id }?.items?.any { it.body == body } == true
            }
            compose.waitForIdle()
            assertAboveTheKeyboard("the add field after adding \"$body\"", compose.onNode(hasSetTextAction() and hasText(INLINE_ADD)), below = PILL_BELOW)
            // The list's own scroll back to the field is not a drag: the keyboard stays.
            compose.onNode(hasSetTextAction() and hasText(INLINE_ADD)).assertIsFocused()
            compose.onNodeWithContentDescription(body).assertExists()
        }
    }

    /** "I can't scroll to the top": with the keyboard up the whole list is
     *  still reachable — its first item scrolls back into view, above the keyboard. */
    @Test fun withTheKeyboardUpTheListStillScrollsToItsFirstItem() {
        openTheList()
        keyboard(KEYBOARD)
        val first = compose.onNodeWithContentDescription(items.first().body)
        first.performScrollTo()
        compose.waitForIdle()
        assertAboveTheKeyboard("the first item", first)
    }

    /** A list far taller than the screen: the add pill it opens on and a card
     *  held to edit near the bottom both clear the keyboard; and when the keyboard
     *  goes, the list gets its full height back with the edited card still shown. */
    @Test fun aLongListKeepsTheFieldAboveTheKeyboardAndGetsItsHeightBack() {
        openTheList(longList)
        keyboard(KEYBOARD)
        assertAboveTheKeyboard("the long list's add field", compose.onNode(hasSetTextAction() and hasText(INLINE_ADD)), below = PILL_BELOW)

        keyboard(0.dp)
        val field = holdTheLowestCoveredItem(longItems, KEYBOARD)
        keyboard(KEYBOARD)
        assertAboveTheKeyboard("the long list's item being edited", field, below = CARD_BELOW)

        keyboard(0.dp)
        assertTrue("with the keyboard gone the list runs to the bottom again: ${viewportBottom(field)} vs ${rootBottom()}", viewportBottom(field) == rootBottom())
        assertAboveTheKeyboard("the edited item once the keyboard is gone", field, below = CARD_BELOW)
    }

    /** Turned on its side the keyboard takes most of the screen: the card being
     *  edited still ends up in the strip above it. */
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    @Test fun inLandscapeTheEditedItemStillClearsTheKeyboard() {
        openTheList()
        keyboard(0.dp)
        val field = holdTheLowestCoveredItem(items, LANDSCAPE_KEYBOARD)
        keyboard(LANDSCAPE_KEYBOARD)
        assertAboveTheKeyboard("the item being edited in landscape", field, below = CARD_BELOW)
    }

    // ── dragging the list with the keyboard up ───────────────────────────────

    /** The list's vertical scroll (the one the [INLINE_ADD] field sits in). */
    private fun theListScroll() = compose.onNode(hasScrollAction() and hasAnyDescendant(hasSetTextAction() and hasText(INLINE_ADD)))

    /** THE report (Ahmad 2026-09-26): the list auto-scrolled to the add field
     *  with the keyboard up; dragging it back up to the top must let the keyboard
     *  go. It used to stay, with the add field scrolled away out of sight. And
     *  once the keyboard is gone the list stays where the finger took it: it is
     *  not yanked back down to the add field. */
    @Test fun draggingTheListBackUpLetsTheKeyboardGo() {
        openTheList(longList)
        keyboard(KEYBOARD)
        val add = compose.onNode(hasSetTextAction() and hasText(INLINE_ADD))
        add.assertIsFocused()

        theListScroll().performTouchInput { swipeDown() }
        compose.waitForIdle()
        add.assertIsNotFocused()

        // Letting go of the field is what takes the keyboard down.
        keyboard(0.dp)
        val addTop = add.getUnclippedBoundsInRoot().top
        assertTrue("the list stays where it was dragged, the add field below the screen: its top is $addTop, the screen ends at ${rootBottom()}", addTop >= rootBottom())
    }

    /** Dragging DOWN the list (toward the add field) lets the keyboard go too. */
    @Test fun draggingTheListEitherWayLetsTheKeyboardGo() {
        openTheList(longList)
        keyboard(KEYBOARD)
        val add = compose.onNode(hasSetTextAction() and hasText(INLINE_ADD))
        theListScroll().performTouchInput { swipeUp() }
        compose.waitForIdle()
        add.assertIsNotFocused()
    }

    /** An item held to edit: dragging the list lets the keyboard go, but the
     *  editor stays open with what was typed — nothing is saved or lost. */
    @Test fun draggingTheListWhileEditingAnItemKeepsTheEdit() {
        openTheList(longList)
        keyboard(0.dp)
        val field = holdTheLowestCoveredItem(longItems, KEYBOARD)
        keyboard(KEYBOARD)
        field.assertIsFocused()
        field.performTextInput(" and more")
        val typed = compose.onNode(hasSetTextAction() and hasText(" and more", substring = true))
        typed.assertIsFocused()

        theListScroll().performTouchInput { swipeDown() }
        compose.waitForIdle()
        typed.assertIsNotFocused()
        typed.assertExists()
        compose.onNodeWithContentDescription("Save").assertExists()
    }

    /** What still keeps the keyboard: a row's sideways swipe (its actions), and
     *  the list's own scroll to the add field after each add (focus stays). */
    @Test fun aSidewaysSwipeOnARowKeepsTheKeyboard() {
        openTheList()
        keyboard(KEYBOARD)
        val add = compose.onNode(hasSetTextAction() and hasText(INLINE_ADD))
        add.assertIsFocused()
        compose.onNodeWithContentDescription(items[10].body).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        add.assertIsFocused()
    }

    /** Same shape on the task screen: dragging it lets the capture field's keyboard go. */
    @Test fun draggingTheTaskScreenLetsTheCaptureKeyboardGo() {
        val capture = openATaskAndFocusTheCaptureField()
        keyboard(KEYBOARD)
        capture.assertIsFocused()
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasSetTextAction() and hasText(CAPTURE))).performTouchInput { swipeDown() }
        compose.waitForIdle()
        capture.assertIsNotFocused()
    }

    /** And on the Collections tab: dragging the grid lets the search keyboard go. */
    @Test fun draggingTheCollectionsGridLetsTheSearchKeyboardGo() {
        compose.onNode(hasText("Collections") and isSelectable()).performClick()
        compose.waitForIdle()
        val search = compose.onNode(hasSetTextAction() and hasText("Search collections"))
        search.performClick()
        compose.waitForIdle()
        keyboard(KEYBOARD)
        search.assertIsFocused()
        compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
        compose.waitForIdle()
        search.assertIsNotFocused()
    }

    // ── a task ───────────────────────────────────────────────────────────────

    private fun openATaskAndFocusTheCaptureField(): androidx.compose.ui.test.SemanticsNodeInteraction {
        vm.addTask("Write the sync notes")
        compose.waitUntil(WAIT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            vm.tasks.value.any { it.name == "Write the sync notes" }
        }
        vm.openDeepLink("unstuck://task/${vm.tasks.value.first { it.name == "Write the sync notes" }.id}")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(CAPTURE)).fetchSemanticsNodes().isNotEmpty() }
        keyboard(0.dp)
        val capture = compose.onNode(hasSetTextAction() and hasText(CAPTURE))
        capture.performScrollTo()
        capture.performClick()
        compose.waitForIdle()
        return capture
    }

    /** Same shape on the task screen: "Capture a thought…" is the last field in
     *  its scroll. Scrolled to and tapped, the keyboard must not cover it. */
    @Test fun theTaskCaptureFieldAtTheBottomEndsUpAboveTheKeyboard() {
        val capture = openATaskAndFocusTheCaptureField()

        keyboard(KEYBOARD)
        // The row it sits in (the field + Add) clears the keyboard too.
        assertAboveTheKeyboard("the task's capture field", capture, below = 8.dp)
    }

    // ── the bottom bar ───────────────────────────────────────────────────────

    /** The bar stays at the bottom of the screen — UNDER the keyboard — while
     *  typing on a tab screen (the Collections search); it must never be lifted
     *  on top of the keyboard, eating the space the user is typing into. */
    @Test fun theBottomBarStaysUnderTheKeyboard() {
        compose.onNode(hasText("Collections") and isSelectable()).performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("Search collections")).performClick()
        compose.waitForIdle()
        val before = compose.onNode(hasText("Calendar") and isSelectable()).getUnclippedBoundsInRoot()

        keyboard(KEYBOARD)
        val after = compose.onNode(hasText("Calendar") and isSelectable()).getUnclippedBoundsInRoot()
        assertTrue("the bar must not move when the keyboard comes up: ${before.top} -> ${after.top}", after.top == before.top)
        assertTrue("the bar must sit under the keyboard, not above it: its top is ${after.top}, the keyboard's ${keyboardTop()}", after.top >= keyboardTop())
    }

    /** Searching the Collections grid: the keyboard covers its lower part (the bar
     *  stays down under it), yet every card — the last one too — can still be
     *  scrolled up above the keyboard. */
    @Test fun searchingCollectionsEveryCardStillScrollsAboveTheKeyboard() {
        compose.onNode(hasText("Collections") and isSelectable()).performClick()
        compose.waitForIdle()
        runBlocking {
            (3..14).forEach { n ->
                val shelf = ItemCollection(id = "g$n", name = "Shelf $n", color = "blue", items = emptyList(), sortOrder = n, ownerId = UID)
                store.upsert(Tables.COLLECTIONS, shelf, ItemCollection.serializer(), shelf.id)
            }
        }
        compose.waitUntil(WAIT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            vm.collections.value.size == 14
        }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("Search collections")).performClick()
        compose.waitForIdle()
        keyboard(KEYBOARD)
        // The grid's header, then 14 cards: as far down as it goes.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(14)
        compose.waitForIdle()
        assertAboveTheKeyboard("the last collection card", compose.onNodeWithText("Shelf 14"))
    }

    private companion object {
        const val UID = "me"
        const val WAIT_MS = 5_000L
        const val INLINE_ADD = "Add to this collection…"
        const val CAPTURE = "Capture a thought…"
        /** A typical phone keyboard. */
        val KEYBOARD = 320.dp
        val LANDSCAPE_KEYBOARD = 200.dp
        /** An item's card reaches this far below its line of text (its padding). */
        val CARD_BELOW = 10.dp
        /** The add pill reaches this far below its line of text. */
        val PILL_BELOW = 12.dp
    }
}
