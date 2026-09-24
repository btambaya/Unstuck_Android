package tech.csalliance.unstuck.ui.tasks

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.design.theme.UnstuckTheme
import tech.csalliance.unstuck.sync.WriteThrough
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.ViewModelDrain
import tech.csalliance.unstuck.ui.sharing.LocalShareTransport
import tech.csalliance.unstuck.ui.sharing.PreCreateFakeTransport
import tech.csalliance.unstuck.ui.sharing.preCreateMember

/**
 * The New task sheet's share section is ONE "Share with…" row that opens the
 * Share screen in its pre-create mode (Ahmad, 2026-09-24: the per-person
 * Off / View / Partner / Assign cards were "terrible" → "One row + picker").
 *
 * Composes the REAL sheet over an offline AppViewModel (AppGraph configured =
 * false, in-memory Room — MainScaffoldFabTest's set-up) and drives it through
 * the semantics tree, the way a TalkBack user would.
 *
 * SDK 33, not the suite's 34, for its own Robolectric sandbox — every Compose
 * test late in the shared 34 sandbox hangs ("Compose did not get idle"); the
 * why is in StartRepeatingPromptTest's header.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xhdpi")
class NewTaskShareRowTest {

    private val compose = createComposeRule()

    /** The compose host activity, declared before the rule launches it (see
     *  MainScaffoldFabTest.hostActivity for why this can't be a manifest AAR). */
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
        val graph = AppGraph(ctx, configured = false, storeOverride = store, uidOverride = { UID })
        runCatching { androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build()) }
        vm = AppViewModel(graph = graph, writeOverride = WriteThrough(store), currentUidProvider = { UID })
    }

    @After fun teardown() {
        drain.track(vm)
        drain.drain()
        db.close()
    }

    private fun openMoreOptions() {
        compose.setContent { UnstuckTheme(dark = false) { NewTaskSheet(vm, onDismiss = {}) } }
        compose.waitForIdle()
        // The semantics OnClick (what TalkBack's double-tap sends): the sheet may
        // still be settling, and a synthetic touch can land mid-animation.
        compose.onNodeWithText("More options ▾").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    @Test
    fun shareIsOneRowThatAnnouncesItsSummaryAsAButton() {
        openMoreOptions()
        compose.onNodeWithContentDescription("Share with, Only you")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        // The old stack of per-person cards and the inline invite panel are gone.
        compose.onNodeWithText("SHARE OR ASSIGN").assertDoesNotExist()
        compose.onAllNodesWithText("Partner").assertCountEquals(0)
        compose.onNodeWithText("Add someone").assertDoesNotExist()
        // Nothing else in More options moved.
        compose.onNodeWithText("TAGS").assertExists()
    }

    @Test
    fun theRowOpensTheShareScreenInItsPreCreateMode() {
        openMoreOptions()
        compose.onNodeWithContentDescription("Share with, Only you").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("Anyone you pick gets this task in their “Shared with you” once you add it.").assertIsDisplayed()
        // The grade switch, with Can edit the default…
        compose.onNodeWithText("Can edit").assertIsDisplayed()
        compose.onNodeWithText("Can view").assertIsDisplayed()
        // …"Someone new" holds an address for this task ("Add"), the link is
        // the connect-only "Invite with a link"…
        compose.onNodeWithText("Add").assertExists()
        compose.onNodeWithText("Get link").assertDoesNotExist()
        compose.onNodeWithContentDescription("Invite with a link").assertExists()
        // …and nothing that needs a task that doesn't exist yet.
        compose.onNodeWithText("SHARE A LINK").assertDoesNotExist()
        compose.onNodeWithText("Manage people").assertDoesNotExist()
    }

    /** The glue the model tests can't see: a pick made on the pre-create
     *  Share screen reaches the sheet's pending shares (what "Add task" hands
     *  to addTask(shares=)) the moment it's made, survives closing the screen,
     *  and comes back pinned with its grade when the row is opened again. The
     *  fake transport fails the test on any share RPC. */
    @Test
    fun aPickComesBackToTheRowAndSurvivesReopening() {
        val fake = PreCreateFakeTransport(
            listOf(preCreateMember("c2", "u2", "James Wilson"), preCreateMember("c3", "u3", "Anna Okafor")),
        )
        compose.setContent {
            CompositionLocalProvider(LocalShareTransport provides fake) {
                UnstuckTheme(dark = false) { NewTaskSheet(vm, onDismiss = {}) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("More options ▾").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Share with, Only you").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        // Choose someone → the searchable picker → one tap picks at Can edit.
        compose.onNodeWithContentDescription("Choose someone, 2 people. Opens a searchable list").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Share with James Wilson").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("✓ James can edit once you add the task.").assertIsDisplayed()
        compose.onNodeWithText("Done").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        // Back on the sheet: the row reads the pick back. (This offline view
        // model has no roster of its own, so the name is the "Someone" fallback;
        // the grade is what the screen picked.)
        compose.onNodeWithText("Done").assertDoesNotExist()
        compose.onNodeWithContentDescription("Share with, Someone · can edit").performScrollTo().assertIsDisplayed()

        // Reopened: James sits first with his grade and his menu.
        compose.onNodeWithContentDescription("Share with, Someone · can edit").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("James Wilson, Can edit. Change access").assertExists()
        compose.onNodeWithContentDescription("Choose someone, 1 people. Opens a searchable list").assertExists()

        // Someone new: a typed address is held and counted on the row too.
        compose.onNode(hasSetTextAction() and hasContentDescription("Email address")).performTextInput("maya@example.com")
        compose.onNodeWithText("Add").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("Gets it when you add the task · can edit").assertExists()
        compose.onNodeWithText("Done").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Share with, Someone, maya · can edit").performScrollTo().assertIsDisplayed()
        assertTrue("nothing was sent before Add task", fake.invites.isEmpty())
    }

    private companion object { const val UID = "00000000-0000-0000-0000-00000000c0de" }
}
