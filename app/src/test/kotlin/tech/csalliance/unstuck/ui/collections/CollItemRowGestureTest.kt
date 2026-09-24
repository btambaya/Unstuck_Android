package tech.csalliance.unstuck.ui.collections

import android.content.ComponentName
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import tech.csalliance.unstuck.core.model.CollectionItem
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.design.theme.UnstuckTheme
import tech.csalliance.unstuck.sync.WriteThrough
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.ViewModelDrain

/**
 * The list-item row's gestures on the real collection screen, offline
 * (MainScaffoldFabTest's harness): real touches, a real ViewModel, a real
 * in-memory store (Ahmad 2026-09-23, parity with iOS b84 CollItemRow; the timing
 * and editor fixes come from the review of that port).
 *
 * A 3x screen, so the card's offset rounds to whole pixels a third of a dp
 * apart — a sub-pixel swing past the middle is a visible -1 px here.
 *
 * SDK 33, the Compose-only sandbox (see StartRepeatingPromptTest for why).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class CollItemRowGestureTest {

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

    private val milk = CollectionItem(id = "i1", body = "Buy milk", at = "2026-09-23T10:00:00.000Z")
    private val list = ItemCollection(id = "c1", name = "Groceries", color = "indigo", items = listOf(milk), sortOrder = 0, ownerId = "me")

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, UnstuckDatabase::class.java).allowMainThreadQueries().build()
        store = LocalStore(db)
        runBlocking { store.upsert(Tables.COLLECTIONS, list, ItemCollection.serializer(), list.id) }
        val graph = AppGraph(ctx, configured = false, storeOverride = store, uidOverride = { "me" })
        runCatching { androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build()) }
        vm = drain.track(AppViewModel(graph = graph, writeOverride = WriteThrough(store), currentUidProvider = { "me" }))
        compose.setContent { UnstuckTheme(dark = false) { CollectionDetailScreen(vm, list.id, onBack = {}) } }
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("Groceries")).fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun teardown() { drain.drain(); db.close() }

    /** The row's card: its accessibility node carries the body. */
    private fun row() = compose.onNodeWithContentDescription("Buy milk")
    /** Unclipped: the row clips the card, so clipped bounds would pin a card that
     *  has slid past the left edge to "at rest". */
    private fun cardLeft() = row().getUnclippedBoundsInRoot().left
    private fun stored(): CollectionItem =
        runBlocking { store.collections().first().first { it.id == list.id }.items.first { it.id == milk.id } }

    /** Wait for the stored item to match. Pumps the main looper each poll: the
     *  ViewModel's writes resume on it, and waitUntil by itself only advances the
     *  Compose clock. */
    private fun waitForStored(what: String, cond: (CollectionItem) -> Boolean) =
        compose.waitUntil(what, WAIT_MS) { shadowOf(Looper.getMainLooper()).idle(); cond(stored()) }

    /** Swipe right and let it settle: Pin + To task showing. */
    private fun openPinSide() {
        row().performTouchInput { swipeRight() }
        compose.waitForIdle()
    }

    /** Wait out any item write already started. Item writes run one at a time
     *  (collectionMutex) and a click's write takes the lock before the click
     *  returns, so once this pin — queued behind it — has landed, so has anything
     *  the click wrote. Without it, "wrote nothing" could pass on a write that
     *  simply hadn't landed yet. */
    private fun afterPendingWrites() {
        compose.runOnIdle { vm.toggleCollectionItemPin(list, milk.id) }
        waitForStored("the sentinel pin landed") { it.pinned == true }
    }

    /** Swipe right, tap to close, tap again the moment it looks shut: the second
     *  tap strikes it out. The spring only reaches 0 about half a second after the
     *  row looks shut; with "open" read off the moving card, that tap re-ran the
     *  close and the strike-out was lost. */
    @Test fun aTapTheMomentAClosingRowLooksShutStrikesItOut() {
        val rest = cardLeft()
        openPinSide()
        assertEquals("open on Pin + To task (2 × 74 dp)", 148f, (cardLeft() - rest).value, 1f)

        compose.mainClock.autoAdvance = false
        row().performTouchInput { click() }            // an open row: this only closes it
        compose.mainClock.advanceTimeBy(350)
        assertEquals("looks shut (within a pixel)", 0f, (cardLeft() - rest).value, 0.34f)
        row().performTouchInput { click() }            // so this one is a strike-out
        compose.mainClock.autoAdvance = true

        waitForStored("the second tap struck it out") { it.done == true }
    }

    /** Closing from the Pin side, frame by frame: the card never crosses to the
     *  Delete side. Left free, the spring's swing past the middle rounded it to
     *  -1 px here and drew the red Delete tile under it — a red line flashing at
     *  the row's right edge. */
    @Test fun aClosingRowNeverSwingsOntoTheOtherSide() {
        val rest = cardLeft()
        openPinSide()

        compose.mainClock.autoAdvance = false
        row().performTouchInput { click() }
        val shifts = (1..40).map { compose.mainClock.advanceTimeByFrame(); (cardLeft() - rest).value }
        compose.mainClock.autoAdvance = true

        assertTrue("went past the middle to ${shifts.min()} dp: $shifts", shifts.all { it >= 0f })
        assertEquals("shut", 0f, shifts.last(), 0f)
        afterPendingWrites()
        assertTrue("a tap on an open row doesn't strike it out", stored().done != true)
    }

    /** Hold edits; ✕ drops the draft, keeps the body and writes nothing. */
    @Test fun holdToEditThenCancelKeepsTheBody() {
        row().performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("Buy milk")).performTextReplacement("Buy oat milk")

        compose.onNodeWithContentDescription("Cancel").performClick()
        compose.waitForIdle()

        compose.onNode(hasSetTextAction() and hasText("Buy oat milk")).assertDoesNotExist()
        row().assertExists()                           // the row again, not the editor
        afterPendingWrites()
        assertEquals("Buy milk", stored().body)
    }

    /** Another member edits the item while the editor is open and nothing has been
     *  typed: the field follows their edit, and ✓ writes nothing — it used to save
     *  the body the hold started from over theirs. */
    @Test fun anUntouchedDraftFollowsAnotherMembersEditAndSaveWritesNothing() {
        row().performTouchInput { longClick() }
        compose.waitForIdle()

        runBlocking {
            store.upsert(Tables.COLLECTIONS, list.copy(items = listOf(milk.copy(body = "Buy almond milk"))), ItemCollection.serializer(), list.id)
        }
        compose.waitUntil("the open field shows their edit", WAIT_MS) {
            compose.onAllNodes(hasSetTextAction() and hasText("Buy almond milk")).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithContentDescription("Save").performClick()
        compose.waitForIdle()
        afterPendingWrites()
        assertEquals("Buy almond milk", stored().body)
    }

    /** ...and a real edit still saves. */
    @Test fun aTypedEditSaves() {
        row().performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("Buy milk")).performTextReplacement("Buy oat milk")

        compose.onNodeWithContentDescription("Save").performClick()
        waitForStored("saved") { it.body == "Buy oat milk" }
        // Stored is not yet shown: the row re-reads the store through Room's own
        // query thread, so the screen catches up a beat later. Asserting in the
        // same instant raced it (caught failing with the row there a moment on).
        compose.waitUntil("the row shows the edit", WAIT_MS) {
            compose.onAllNodes(hasContentDescription("Buy oat milk")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
