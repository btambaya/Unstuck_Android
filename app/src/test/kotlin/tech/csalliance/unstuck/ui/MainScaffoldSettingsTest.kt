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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.assert
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
    fun backFromASectionLandsOnTheHub() {
        shell()
        link("unstuck://settings?section=People")
        compose.onNodeWithText("People you share with").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("settings-row-people").assertIsDisplayed()
        compose.onNodeWithText("How Unstuck behaves.").assertIsDisplayed()
    }

    /** Send feedback is a hub action, and `?section=feedback` opens the same sheet. */
    @Test
    fun sendFeedbackOpensTheSheetFromTheRowAndTheLink() {
        shell()
        link("unstuck://settings")
        compose.onNodeWithTag("settings-row-feedback").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Bugs, ideas, anything — straight to the team.").assertIsDisplayed()
    }

    @Test
    fun theFeedbackLinkOpensTheSheetOverTheHub() {
        shell()
        link("unstuck://settings?section=feedback")
        compose.onNodeWithText("Bugs, ideas, anything — straight to the team.").assertIsDisplayed()
    }

    /** Focus options live on the Focus screen: with no session running the
     *  old names land on the hub, never a dead end. */
    @Test
    fun focusAndSoundWithoutASessionLandOnTheHub() {
        shell()
        link("unstuck://settings?section=focus")
        compose.onNodeWithTag("settings-row-notifications").assertIsDisplayed()
        link("unstuck://settings?section=Sound")
        compose.onNodeWithTag("settings-row-appearance").assertIsDisplayed()
    }

    /** The assistant's open_screen targets land where plan §4 says. */
    @Test
    fun openScreenTargetsLandOnTheirNewHomes() {
        shell()
        link(tech.csalliance.unstuck.ui.assistant.assistantScreenLink("notifications", null))
        compose.onNodeWithText("How much Unstuck checks in").assertIsDisplayed()
        link(tech.csalliance.unstuck.ui.assistant.assistantScreenLink("people", null))
        compose.onNodeWithText("People you share with").assertIsDisplayed()
        link(tech.csalliance.unstuck.ui.assistant.assistantScreenLink("areas", null))
        compose.onNodeWithTag("areas-tags-sheet").assertIsDisplayed()
    }

    /** Delete my account (App Store 5.1.1(v), Play): Account's last row, live
     *  and tappable. (Its confirm dialog can't be opened here — a text field in
     *  a Dialog never reports idle under Robolectric — so the confirm rule is
     *  pinned in SlimSettingsTest.) */
    @Test
    fun deleteMyAccountIsReachableAndLive() {
        shell()
        link("unstuck://settings?section=account")
        compose.onNodeWithTag("settings-delete-account").performScrollTo().assertIsDisplayed().assertIsEnabled().assert(hasClickAction())
    }

    /** Terms and Privacy: one tap from the hub, the published pages (the stores ask). */
    @Test
    fun termsAndPrivacyOpenThePublishedPages() {
        shell()
        link("unstuck://settings")
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        compose.onNodeWithTag("settings-footer-terms").performScrollTo().performClick()
        compose.waitForIdle()
        val terms = shadowOf(app).nextStartedActivity
        org.junit.Assert.assertEquals(android.content.Intent.ACTION_VIEW, terms.action)
        org.junit.Assert.assertEquals("https://unstucknow.io/terms", terms.dataString)
        compose.onNodeWithTag("settings-footer-privacy").performScrollTo().performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals("https://unstucknow.io/privacy", shadowOf(app).nextStartedActivity.dataString)
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
