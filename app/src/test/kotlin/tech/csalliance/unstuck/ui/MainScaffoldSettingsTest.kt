package tech.csalliance.unstuck.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

/**
 * The slim Settings, driven through the REAL [MainScaffold] (offline, like
 * [MainScaffoldFabTest]): the hub's rows, every `unstuck://settings` form
 * landing where plan §4 says, and Areas & tags opening from the Tasks tab's
 * Edit pill instead of Settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xhdpi")
class MainScaffoldSettingsTest {

    private val compose = createComposeRule()

    /** See MainScaffoldFabTest: the compose host activity is registered with
     *  Robolectric here so no test activity reaches the shipping manifest. */
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
    private lateinit var graph: AppGraph
    private lateinit var vm: AppViewModel
    private val drain = ViewModelDrain()

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, UnstuckDatabase::class.java).allowMainThreadQueries().build()
        val store = LocalStore(db)
        graph = AppGraph(ctx, configured = false, storeOverride = store, uidOverride = { "u-settings" })
        graph.onboarded = true
        runCatching { androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build()) }
        vm = AppViewModel(graph = graph, writeOverride = WriteThrough(store), currentUidProvider = { "u-settings" })
    }

    @After fun teardown() {
        drain.track(vm)
        drain.drain()
    }

    private fun shell() {
        compose.setContent { UnstuckTheme(dark = false) { MainScaffold(vm) } }
        compose.waitForIdle()
    }

    private fun link(url: String) {
        compose.runOnIdle { vm.openDeepLink(url) }
        compose.waitForIdle()
    }

    @Test
    fun theBareLinkOpensTheHubWithItsRowsActionsAndFooter() {
        shell()
        link("unstuck://settings")
        for (tag in listOf("settings-row-account", "settings-row-notifications", "settings-row-assistant", "settings-row-people", "settings-row-appearance")) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
        compose.onNodeWithTag("settings-row-feedback").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-row-tour").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-footer-terms").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-footer-privacy").performScrollTo().assertIsDisplayed()
        // People is one tap from the hub — the server's bare invite link relies on it.
        compose.onNodeWithTag("settings-row-people").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("People you share with").assertIsDisplayed()
    }

    @Test
    fun theQueryFormOpensTheSectionOverTheHub() {
        shell()
        link("unstuck://settings?section=People")
        compose.onNodeWithText("People you share with").assertIsDisplayed()
    }

    @Test
    fun oldSectionNamesStillLand() {
        shell()
        link("unstuck://settings?section=interface")
        compose.onNodeWithText("How it looks.").assertIsDisplayed()
        compose.onNodeWithText("Text size").assertIsDisplayed()
        link("unstuck://settings?section=calls")
        compose.onNodeWithText("How much Unstuck checks in").assertIsDisplayed()
        link("unstuck://settings?section=memory")
        compose.onNodeWithText("What the AI can see.").assertIsDisplayed()
    }

    @Test
    fun notificationsAndCallsHoldTheLevelTheLeadAndCalls() {
        shell()
        link("unstuck://settings?section=Notifications")
        compose.onNodeWithText("How much Unstuck checks in").assertIsDisplayed()
        compose.onNodeWithTag("settings-level-calm").assertIsDisplayed()
        compose.onNodeWithTag("settings-level-coach").assertIsDisplayed()
        compose.onNodeWithText("Remind me before a task").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-calls-switch").performScrollTo().assertIsDisplayed()
        // Picking a level is the setting.
        compose.onNodeWithTag("settings-level-calm").performClick()
        compose.waitForIdle()
        compose.runOnIdle { org.junit.Assert.assertEquals(tech.csalliance.unstuck.NotificationLevel.CALM, vm.settings.value.notificationLevel) }
    }

    @Test
    fun withTheAssistantOffCallsAreOneLine() {
        shell()
        compose.runOnIdle { vm.updateSettings { it.copy(assistantEnabled = false) } }
        link("unstuck://settings?section=Notifications")
        compose.onNodeWithTag("settings-calls-need-assistant").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-calls-switch").assertDoesNotExist()
    }

    @Test
    fun areasLinksOpenTheAreasAndTagsSheetOnTasks() {
        shell()
        link("unstuck://settings/areas")
        compose.onNodeWithTag("areas-tags-sheet").assertIsDisplayed()
        compose.onNodeWithText("Areas & tags").assertIsDisplayed()
    }

    @Test
    fun theTasksEditPillOpensAreasAndTags() {
        shell()
        compose.onNode(hasText("Tasks") and isSelectable()).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tasks-edit-areas").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(hasTestTag("areas-tags-sheet")).assertIsDisplayed()
    }

    @Test
    fun theAssistantScreenKeepsItsControlsWithTheAiOff() {
        shell()
        compose.runOnIdle { vm.updateSettings { it.copy(assistantEnabled = false) } }
        link("unstuck://settings?section=Assistant")
        compose.onNodeWithTag("settings-ai-assistant").assertIsDisplayed()
        compose.onNodeWithTag("settings-row-memory").assertIsDisplayed()
        compose.onNodeWithTag("settings-delete-history").assertIsDisplayed()
    }

    @Test
    fun accountEndsWithDeleteMyAccount() {
        shell()
        link("unstuck://settings?section=account")
        compose.onNodeWithTag("settings-export").assertIsDisplayed()
        compose.onNodeWithTag("settings-sign-out").assertIsDisplayed()
        compose.onNodeWithTag("settings-delete-account").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun replayTheTourIsLockedWhileATourRuns() {
        shell()
        link("unstuck://settings")
        compose.runOnIdle { tech.csalliance.unstuck.ui.tour.TourEvents.running = true }
        try {
            compose.onNodeWithTag("settings-row-tour").performScrollTo().assertIsNotEnabled()
        } finally {
            compose.runOnIdle { tech.csalliance.unstuck.ui.tour.TourEvents.running = false }
        }
    }
}
