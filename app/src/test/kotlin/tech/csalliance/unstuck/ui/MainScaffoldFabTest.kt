package tech.csalliance.unstuck.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.After
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
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.design.theme.UnstuckTheme
import tech.csalliance.unstuck.sync.WriteThrough

/**
 * What the ONE coral + in the bottom bar ACTUALLY does, per surface.
 *
 * This composes the real [MainScaffold] and drives it through the semantics
 * tree — it does not call [fabAction] itself. That is deliberate: a pure
 * two-branch lookup can be perfectly tested and still be wired to nothing, and
 * that is exactly what happened in review (FabAction.kt left intact, the
 * scaffold's `onFab` reverted to "always New task", suite still green). The
 * assertions here fail on that revert, because they click the button the user
 * clicks and look for the sheet the user would get.
 *
 * Offline by construction: AppGraph(configured = false) → no Supabase client,
 * no coordinator, no realtime, no network; a real in-memory Room DB behind a
 * real LocalStore, exactly as [AppViewModelTest] builds its SUT.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xhdpi")
class MainScaffoldFabTest {

    private val compose = createComposeRule()

    /**
     * Declare the Compose rule's host activity to the package manager BEFORE the
     * rule launches it.
     *
     * `createComposeRule()` hosts the content in `androidx.activity.
     * ComponentActivity`, and Robolectric resolves that through the MERGED
     * MANIFEST of the variant under test — for an application module that is the
     * app's own packaged manifest (`generateXUnitTestConfig` writes
     * `android_merged_manifest=.../packaged_manifests/<variant>/...`), not a
     * manifest synthesized for the test. So the usual
     * `debugImplementation(ui-test-manifest)` only ever declares the activity on
     * ONE variant: `:app:testReleaseUnitTest` failed all 14 tests with
     * "Unable to resolve activity for Intent { … ComponentActivity }"
     * (RoboMonitoringInstrumentation:102), and the only way to make that AAR
     * cover release too would be to merge a test activity into the SHIPPING
     * manifest. Registering it here instead is variant-independent and ships
     * nothing. (A :library module does get its own unit-test manifest merge,
     * which is why :design can keep the AAR — as `testImplementation`.)
     *
     * It must be an OUTER rule: JUnit rules wrap `@Before`, so the compose rule
     * launches the activity before any `@Before` body could register it.
     */
    private val hostActivity = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
                shadowOf(ctx.packageManager)
                    .addActivityIfNotPresent(ComponentName(ctx, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(hostActivity).around(compose)

    private lateinit var db: UnstuckDatabase
    private lateinit var graph: AppGraph
    private lateinit var vm: AppViewModel

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, UnstuckDatabase::class.java).allowMainThreadQueries().build()
        val store = LocalStore(db)
        // uidOverride: the onboarded flag is per-account and there is no auth
        // session offline, so without an account id it can't even be set.
        graph = AppGraph(ctx, configured = false, storeOverride = store, uidOverride = { UID })
        // The scaffold short-circuits to OnboardingScreen and `return`s when the
        // account isn't onboarded — there'd be no bottom bar to test.
        graph.onboarded = true
        runCatching {
            androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build())
        }
        vm = AppViewModel(graph = graph, writeOverride = WriteThrough(store), currentUidProvider = { UID })
    }

    @After fun teardown() {
        // Nothing else clears a ViewModel built by hand: onCleared() only runs
        // under a real ViewModelStore, so without this the VM's WhileSubscribed
        // Room collectors would outlive the test (AppViewModelTest's note).
        // Cancel ASKS; drain() waits until they have actually finished, so this
        // class can't leave coroutines running on Dispatchers.Main for whichever
        // suite swaps it next — that is the shared JVM's order-dependent flake
        // (see ViewModelDrain). No scheduler here: this class never swaps Main,
        // so the main looper is the only pump.
        drain.track(vm)
        drain.drain()
    }

    private val drain = ViewModelDrain()

    private fun shell() {
        compose.setContent { UnstuckTheme(dark = false) { MainScaffold(vm) } }
        compose.waitForIdle()
    }

    /** Tap the bottom bar's + (found by what it announces, which is also what a
     *  TalkBack user gets). */
    private fun tapFab(label: String) {
        compose.onNodeWithContentDescription(label).assertIsDisplayed().performClick()
        compose.waitForIdle()
    }

    /** Tap a bottom-bar tab. Matched on `isSelectable` (NavCell's Role.Tab
     *  node) so a screen that happens to print the same word — "Today" in a
     *  header, say — can't make the match ambiguous. */
    private fun openTab(label: String) {
        compose.onNode(hasText(label) and isSelectable()).performClick()
        compose.waitForIdle()
    }

    // ── Today / Tasks / Calendar: unchanged, the + is New task ───────────────

    @Test
    fun todayFabOpensNewTask() {
        shell()
        tapFab(FAB_TASK)
        compose.onNodeWithText(NEW_TASK_SHEET).assertIsDisplayed()
    }

    @Test
    fun tasksFabOpensNewTask() {
        shell()
        openTab("Tasks")
        tapFab(FAB_TASK)
        compose.onNodeWithText(NEW_TASK_SHEET).assertIsDisplayed()
    }

    @Test
    fun calendarFabOpensNewTask() {
        shell()
        openTab("Calendar")
        tapFab(FAB_TASK)
        compose.onNodeWithText(NEW_TASK_SHEET).assertIsDisplayed()
    }

    // ── Collections grid: the + creates a COLLECTION ─────────────────────────

    /** THE regression test. Reverting MainScaffold's `onFab` to "always New
     *  task" fails here even with FabAction.kt untouched. */
    @Test
    fun collectionsFabOpensNewCollectionNotNewTask() {
        shell()
        openTab("Collections")
        tapFab(FAB_COLLECTION)
        compose.onNodeWithText(NEW_COLLECTION_SHEET).assertIsDisplayed()
        // ...and specifically NOT the task sheet.
        compose.onNodeWithText(NEW_TASK_SHEET).assertDoesNotExist()
    }

    /** The button's LOOK never changes; only what it announces does. A + that
     *  creates a collection while still saying "New task" is unusable blind. */
    @Test
    fun fabLabelFollowsTheSurface() {
        shell()
        compose.onNodeWithContentDescription(FAB_TASK).assertIsDisplayed()
        compose.onNodeWithContentDescription(FAB_COLLECTION).assertDoesNotExist()

        openTab("Collections")
        compose.onNodeWithContentDescription(FAB_COLLECTION).assertIsDisplayed()
        compose.onNodeWithContentDescription(FAB_TASK).assertDoesNotExist()

        // ...and back: the Collections action must not stick to the other tabs.
        openTab("Today")
        compose.onNodeWithContentDescription(FAB_TASK).assertIsDisplayed()
        compose.onNodeWithContentDescription(FAB_COLLECTION).assertDoesNotExist()
    }

    /** Leaving the Collections tab with the + un-tapped must not leave the
     *  collection action armed on the next tab. */
    @Test
    fun leavingCollectionsRestoresNewTask() {
        shell()
        openTab("Collections")
        openTab("Tasks")
        tapFab(FAB_TASK)
        compose.onNodeWithText(NEW_TASK_SHEET).assertIsDisplayed()
        compose.onNodeWithText(NEW_COLLECTION_SHEET).assertDoesNotExist()
    }

    /**
     * Type a name into the New-collection sheet and hit Create.
     *
     * The field is matched by its own label — the Collections grid underneath
     * also has a text field, so "the text field" alone is ambiguous.
     *
     * Create is activated through its semantics OnClick action rather than a
     * synthesized tap: the sheet is a ModalBottomSheet, i.e. its own dialog
     * window, and Robolectric doesn't route injected touches into that window
     * (verified — the same tap on the main window's FAB works). The action is
     * the same one an accessibility click invokes, so it exercises the real
     * `onClick` lambda; only the plumbing that delivers it differs.
     */
    private fun nameTheCollection(name: String) {
        compose.onNode(hasSetTextAction() and hasText(NEW_COLLECTION_SHEET)).performTextInput(name)
        compose.onNodeWithText("Create").performSemanticsAction(SemanticsActions.OnClick)
    }

    // ── create-and-land (the pushed route must actually resolve) ─────────────

    /**
     * Create a collection from the + and END UP INSIDE IT.
     *
     * The old same-frame push raced the async local write and lost roughly a
     * third of the time on a device: the detail screen composed with nothing to
     * resolve, its deleted-while-open guard fired, and the user was bounced back
     * to the grid. Here that revert is DETERMINISTIC, not flaky — the write is
     * queued on the (paused) main looper while the click is still being handled,
     * so a push in the same frame can never resolve, and this test fails.
     */
    @Test
    fun creatingACollectionLandsInsideIt() {
        shell()
        openTab("Collections")
        tapFab(FAB_COLLECTION)
        nameTheCollection("Reading list")
        // Wait for the landing rather than assert on the next frame: the point of
        // the fix is that navigation happens WHEN the row can be resolved.
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(INLINE_ADD).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(INLINE_ADD).assertIsDisplayed()
        // The list itself is there, under its own title — not an empty shell.
        // (onAllNodes: the grid underneath still holds its card for the same name.)
        assertTrue(compose.onAllNodesWithText("Reading list").fetchSemanticsNodes().isNotEmpty())
    }

    /** Backing out of the collection we just created returns to the grid with the
     *  new collection on it — i.e. the row really was written, not just routed to. */
    @Test
    fun theCreatedCollectionIsOnTheGridAfterBackingOut() {
        shell()
        openTab("Collections")
        tapFab(FAB_COLLECTION)
        nameTheCollection("Reading list")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(INLINE_ADD).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(INLINE_ADD).assertDoesNotExist()
        compose.onNodeWithText("Reading list").assertIsDisplayed()
    }

    /**
     * Inside a collection the + must be gone — including for TalkBack.
     *
     * The pushed route's opaque overlay already hides the bar and swallows its
     * taps, and round 1 reasoned from that to "the case cannot arise". Measured
     * on emulator-5554, it could: the + was still in the semantics tree,
     * announced "New collection", and an accessibility click (what a TalkBack
     * double-tap performs) opened the New-collection sheet ON TOP of the open
     * collection. MainScaffold now clears the bar's semantics while a route
     * covers it; this is what keeps that true.
     *
     * If this fails, FabAction.kt's "third rule" is reachable on Android and
     * owes the user real behaviour (focus the inline add field; New collection
     * when view-only) rather than a comment.
     */
    @Test
    fun insideACollectionTheFabLeavesTheAccessibilityTreeToo() {
        shell()
        openTab("Collections")
        tapFab(FAB_COLLECTION)
        nameTheCollection("Reachability")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(INLINE_ADD).fetchSemanticsNodes().isNotEmpty() }

        // Not merely invisible — absent. (assertDoesNotExist is a semantics-tree
        // assertion, which is precisely the tree TalkBack reads.)
        compose.onNodeWithContentDescription(FAB_COLLECTION).assertDoesNotExist()
        compose.onNodeWithContentDescription(FAB_TASK).assertDoesNotExist()
        compose.onNodeWithContentDescription("New").assertDoesNotExist()
        // The tabs underneath go with it — same overlay, same reasoning.
        assertTrue(
            "the bottom bar must not be announceable while a route covers it",
            compose.onAllNodesWithText("Calendar").fetchSemanticsNodes().isEmpty(),
        )

        // ...and it comes back the moment the route does not cover it any more.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(FAB_COLLECTION).assertIsDisplayed()
    }

    /**
     * A collection deleted while it is open drops the user back on the grid —
     * with the + still meaning New collection, not stranded on a dead screen.
     */
    @Test
    fun deletingTheOpenCollectionReturnsToTheGridWithTheCollectionFab() {
        shell()
        openTab("Collections")
        tapFab(FAB_COLLECTION)
        nameTheCollection("Doomed")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(INLINE_ADD).fetchSemanticsNodes().isNotEmpty() }

        vm.deleteCollection(vm.collections.value.first { it.name == "Doomed" }.id)
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(INLINE_ADD).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription(FAB_COLLECTION).assertIsDisplayed()
        compose.onNodeWithText("Doomed").assertDoesNotExist()
    }

    /**
     * Viewing the Archived filter hides the grid's own "+ New" pill (archived
     * lists are not a place to add to), but the bottom bar's + is the one
     * constant in the app — it must still create a collection there, and the
     * deferred navigation must still land.
     */
    @Test
    fun theFabStillCreatesACollectionWhileTheArchivedFilterIsOn() {
        vm.upsertCollection(
            ItemCollection(id = "arch-1", name = "Old stuff", color = "indigo", items = emptyList(), sortOrder = 0, ownerId = UID, archived = true),
        )
        shell()
        openTab("Collections")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("Archived (1)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Archived (1)").performClick()
        compose.waitForIdle()
        // The grid's own pill is gone here; the bar's + is not.
        compose.onNodeWithText("+ New").assertDoesNotExist()

        tapFab(FAB_COLLECTION)
        nameTheCollection("Made while archived")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(INLINE_ADD).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(INLINE_ADD).assertIsDisplayed()
    }

    /**
     * A deep link straight into a collection (`unstuck://collections/<id>`)
     * pushes the route without going through the +.
     *
     * Fired COLD — nothing has subscribed to `collections` yet, which is the
     * real case (a notification tap on a fresh process). `collections` is
     * WhileSubscribed, so the row is not resolvable in the frame the link
     * arrives, and the immediate push used to hand CollectionDetailScreen an id
     * it could not resolve: its deleted-while-open guard fired and the link
     * dumped the user back on the grid. Measured on emulator-5554 before the
     * fix: 10 cold arrivals, the first bounced.
     *
     * While it is open the bar is covered, so the + is gone; backing out lands
     * on the Collections grid, where the + means New collection — a deep link
     * must not leave the + on the wrong action.
     */
    @Test
    fun aColdDeepLinkIntoACollectionLandsInsideItAndLeavesTheFabCorrect() {
        vm.upsertCollection(
            ItemCollection(id = "deep-1", name = "Deep linked", color = "indigo", items = emptyList(), sortOrder = 0, ownerId = UID),
        )
        shell()
        vm.openDeepLink("unstuck://collections/deep-1")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(INLINE_ADD).fetchSemanticsNodes().isNotEmpty() }
        // onAllNodes: the grid underneath still holds its card for the same name.
        assertTrue(compose.onAllNodesWithText("Deep linked").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithContentDescription(FAB_COLLECTION).assertDoesNotExist()
        compose.onNodeWithContentDescription(FAB_TASK).assertDoesNotExist()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(FAB_COLLECTION).assertIsDisplayed()
        compose.onNodeWithContentDescription(FAB_TASK).assertDoesNotExist()
    }

    /**
     * A deep link naming a collection that is NOT there (a stale notification,
     * a list someone deleted) must not hang on the bounded wait and must not
     * strand the user: it leaves them on the Collections grid, + intact.
     */
    @Test
    fun aDeepLinkToAMissingCollectionLandsOnTheGridInsteadOfHanging() {
        shell()
        vm.openDeepLink("unstuck://collections/does-not-exist")
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithContentDescription(FAB_COLLECTION).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(FAB_COLLECTION).assertIsDisplayed()
        compose.onNodeWithText(INLINE_ADD).assertDoesNotExist()
    }

    /**
     * A deep link that CANNOT resolve is consumed anyway, so it can never act a
     * second time.
     *
     * The deep-link effect is keyed on `tasks` and only consumes the link at the
     * very bottom, so anything that SUSPENDS inside it leaves the link pending for
     * the whole wait — and the task list changes on its own (a sync pull, a
     * reminder flipping a row, the user ticking something off), which cancels the
     * effect and re-runs it from the top on the still-pending link. With the wait
     * for a never-readable collection inside the effect, that repeated forever:
     * reproduced on emulator-5554 with the debug APK — fire
     * unstuck://collections/does-not-exist, walk over to Tasks, let the list
     * change, and the dead link yanked the user back to Collections again.
     *
     * Here the task list change is the assertion's trigger, not an accident.
     */
    @Test
    fun aDeadDeepLinkIsConsumedSoALaterTaskListChangeCannotReplayIt() {
        shell()
        vm.openDeepLink("unstuck://collections/does-not-exist")
        // It acts once: the tab switch is immediate, the push never comes.
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithContentDescription(FAB_COLLECTION).fetchSemanticsNodes().isNotEmpty()
        }

        // The user moves on...
        openTab("Tasks")
        compose.onNodeWithContentDescription(FAB_TASK).assertIsDisplayed()

        // ...and the task list changes underneath them, re-keying the effect.
        vm.addTask("Something new")
        // fetchSemanticsNodes() syncs the composition each poll, which is what
        // lets the write land and the re-keyed effect run; the gate itself is on
        // the VM, because whether the Tasks list chooses to RENDER the row is not
        // what this test is about — only that `tasks` changed.
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText("Something new").fetchSemanticsNodes()
            vm.tasks.value.any { it.name == "Something new" }
        }
        compose.waitForIdle()

        // The stale link must not have acted again — the user is still where they
        // put themselves. (With the link left pending, this is where the + flips
        // back to New collection.)
        compose.onNodeWithContentDescription(FAB_TASK).assertIsDisplayed()
        compose.onNodeWithContentDescription(FAB_COLLECTION).assertDoesNotExist()
    }

    /**
     * Process death with a collection open.
     *
     * The route stack is deliberately NOT saveable, while the tab is — so a killed
     * process comes back on the Collections GRID, never on a detail screen whose
     * id it may no longer be able to resolve. What this pins for the + is that the
     * restored shell has a working one: same look, Collections action, and no
     * New-collection sheet left armed by the restore.
     *
     * (StateRestorationTester re-creates the composition from the saved-state
     * registry, which is the same snapshot the activity writes in
     * onSaveInstanceState — i.e. taken BEFORE the ON_STOP reset, exactly as a real
     * process death takes it.)
     */
    @Test
    fun processDeathWithACollectionOpenRestoresToTheGridWithTheCollectionFab() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { UnstuckTheme(dark = false) { MainScaffold(vm) } }
        compose.waitForIdle()
        openTab("Collections")
        tapFab(FAB_COLLECTION)
        nameTheCollection("Survivor")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(INLINE_ADD).fetchSemanticsNodes().isNotEmpty() }

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        compose.onNodeWithText(INLINE_ADD).assertDoesNotExist()
        compose.onNodeWithText(NEW_COLLECTION_SHEET).assertDoesNotExist()
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("Survivor").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(FAB_COLLECTION).assertIsDisplayed()
        compose.onNodeWithContentDescription(FAB_TASK).assertDoesNotExist()
    }

    /**
     * A view-only shared list: the owner's third rule says the + falls back to
     * New collection there. On Android the + is not present inside ANY
     * collection, view-only included — so the fallback has nothing to fall back
     * from. Pinned because it is the other half of FabAction.kt's argument.
     */
    @Test
    fun aViewOnlyCollectionHasNoFabInsideItEither() {
        vm.upsertCollection(
            ItemCollection(id = "viewonly-1", name = "Someone elses list", color = "indigo", items = emptyList(), sortOrder = 0, ownerId = "someone-else", myRole = "viewer"),
        )
        shell()
        openTab("Collections")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("Someone elses list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Someone elses list").performClick()
        compose.waitForIdle()

        // It opened read-only: no inline add field for a viewer...
        compose.onNodeWithText(INLINE_ADD).assertDoesNotExist()
        // ...and no + to offer an alternative.
        compose.onNodeWithContentDescription(FAB_COLLECTION).assertDoesNotExist()
        compose.onNodeWithContentDescription(FAB_TASK).assertDoesNotExist()
    }

    private companion object {
        const val UID = "me"
        const val FAB_TASK = "New task"
        const val FAB_COLLECTION = "New collection"
        /** Distinctive copy from each sheet's body (not its title chrome). */
        const val NEW_TASK_SHEET = "What's on your mind?"
        const val NEW_COLLECTION_SHEET = "What would you like to remember?"
        /** The inline add field that only the collection DETAIL screen renders. */
        const val INLINE_ADD = "Add to this collection…"
        const val WAIT_MS = 5_000L
    }
}
