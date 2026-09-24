package tech.csalliance.unstuck.ui.tasks

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.design.theme.UnstuckTheme
import tech.csalliance.unstuck.sync.WriteThrough
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.ViewModelDrain

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
        // …"Someone new" as the circle invite (a blank field makes a link)…
        compose.onNodeWithText("Get link").assertExists()
        // …and nothing that needs a task that doesn't exist yet.
        compose.onNodeWithText("SHARE A LINK").assertDoesNotExist()
    }

    private companion object { const val UID = "00000000-0000-0000-0000-00000000c0de" }
}
