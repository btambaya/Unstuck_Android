package tech.csalliance.unstuck.ui.sharing

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.core.logic.SharePick
import tech.csalliance.unstuck.core.logic.shareWithSummary
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.design.theme.UnstuckTheme

/**
 * The New task sheet's one "Share with…" row and the Share screen it opens in
 * PRE-CREATE mode, composed directly and driven through the semantics tree
 * (what a TalkBack user gets). NewTaskShareRowTest drives the real sheet.
 *
 * SDK 33, not the suite's 34, for its own Robolectric sandbox — every Compose
 * test late in the shared 34 sandbox hangs ("Compose did not get idle"); the
 * why is in StartRepeatingPromptTest's header.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class, qualifiers = "w411dp-h1400dp-xhdpi")
class ShareWithRowTest {

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

    private val roster = listOf(
        preCreateMember("c1", "u1", "Maya Chen", "Coach"),
        preCreateMember("c2", "u2", "James Wilson"),
        preCreateMember("c3", "u3", "Anna Okafor"),
    )

    // ── the row ─────────────────────────────────────────────────────────────

    @Test
    fun theRowIsOneButtonThatAnnouncesItsSummary() {
        var opened = 0
        compose.setContent { UnstuckTheme(dark = false) { ShareWithRow(shareWithSummary(emptyList())) { opened++ } } }
        compose.onNodeWithContentDescription("Share with, Only you")
            .assertIsDisplayed()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, opened)
    }

    @Test
    fun theRowReadsBackWhoIsPicked() {
        val picks = listOf(SharePick("James Wilson", ShareLevel.PARTNER), SharePick("Anna Okafor", ShareLevel.VIEW))
        compose.setContent { UnstuckTheme(dark = true) { ShareWithRow(shareWithSummary(picks)) {} } }
        compose.onNodeWithContentDescription("Share with, James · edit, Anna · view").assertIsDisplayed()
    }

    // ── the Share screen it opens, in pre-create mode ───────────────────────

    private fun screen(picks: Map<String, ShareLevel>): ShareScreenModel {
        val m = ShareScreenModel(ShareTarget.NewTask("Plan the Lisbon trip"), transport = PreCreateFakeTransport(roster), initialPicks = picks)
        runBlocking { m.load() }
        compose.setContent {
            UnstuckTheme(dark = false) {
                val s by m.state.collectAsState()
                ShareScreenBody(m, s, onDone = {}, onChoose = {}, onReport = {}, onBlock = {})
            }
        }
        return m
    }

    @Test
    fun preCreateHidesWhatNeedsATaskAndKeepsTheInvite() {
        screen(emptyMap())
        compose.onNodeWithText("Anyone you pick gets this task in their “Shared with you” once you add it.").assertIsDisplayed()
        compose.onNodeWithText("Can edit").assertIsDisplayed()
        compose.onNodeWithText("Can view").assertIsDisplayed()
        compose.onNodeWithContentDescription("Choose someone, 3 people. Opens a searchable list").assertHasClickAction()
        // "Someone new" is the circle invite; a blank field makes a link.
        compose.onNodeWithText("Get link").assertIsDisplayed()
        compose.onNodeWithText("SHARE A LINK").assertDoesNotExist()
        compose.onNodeWithText("Share a link").assertDoesNotExist()
    }

    @Test
    fun aPickedPersonsMenuOffersHandOverNotReportOrBlock() {
        val m = screen(mapOf("u2" to ShareLevel.PARTNER))
        compose.onNodeWithContentDescription("James Wilson, Can edit. Change access").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        // The grade switch's "Can view" + the menu's.
        compose.onAllNodesWithText("Can view").assertCountEquals(2)
        compose.onNodeWithText("Remove").assertExists()
        compose.onNodeWithText("Report…").assertDoesNotExist()
        compose.onNodeWithText("Block James Wilson…").assertDoesNotExist()
        compose.onNodeWithText("Hand over").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(mapOf("u2" to ShareLevel.ASSIGN), m.state.value.picks)
        compose.onNodeWithText("✓ James gets it as their task once you add it — you keep view.").assertIsDisplayed()
    }
}
