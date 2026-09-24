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
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import tech.csalliance.unstuck.core.logic.NewTaskShares
import tech.csalliance.unstuck.core.logic.SharePick
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
        compose.setContent { UnstuckTheme(dark = false) { ShareWithRow(emptyList()) { opened++ } } }
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
        compose.setContent { UnstuckTheme(dark = true) { ShareWithRow(picks) {} } }
        compose.onNodeWithContentDescription("Share with, James · edit, Anna · view").assertIsDisplayed()
    }

    @Test
    fun monogramsShowOnlyWhenTheyFitBesideTheSummary() {
        // 3 discs: 22 + 18 + 18 = 58dp, + an 8dp gap.
        assertEquals(58.dp, monogramStackWidth(3))
        assertEquals(22.dp, monogramStackWidth(1))
        assertEquals(0.dp, monogramStackWidth(0))
        assertTrue(monogramsFit(3, summaryWidth = 100.dp, room = 166.dp))
        assertFalse("too narrow: the summary wins", monogramsFit(3, summaryWidth = 100.dp, room = 165.dp))
        assertFalse("nobody picked: no discs", monogramsFit(0, summaryWidth = 10.dp, room = 500.dp))
    }

    // ── the Share screen it opens, in pre-create mode ───────────────────────

    private fun screen(picks: Map<String, ShareLevel>): ShareScreenModel {
        val m = ShareScreenModel(ShareTarget.NewTask("Plan the Lisbon trip"), transport = PreCreateFakeTransport(roster), initialShares = NewTaskShares(picks))
        runBlocking { m.load() }
        compose.setContent {
            UnstuckTheme(dark = false) {
                val s by m.state.collectAsState()
                ShareScreenBody(m, s, onDone = {}, onChoose = {}, onReport = {}, onBlock = {}, onManagePeople = {})
            }
        }
        return m
    }

    @Test
    fun preCreateHidesWhatNeedsATaskAndOffersTheInviteLink() {
        screen(emptyMap())
        compose.onNodeWithText("Anyone you pick gets this task in their “Shared with you” once you add it.").assertIsDisplayed()
        compose.onNodeWithText("Can edit").assertIsDisplayed()
        compose.onNodeWithText("Can view").assertIsDisplayed()
        compose.onNodeWithContentDescription("Choose someone, 3 people. Opens a searchable list").assertHasClickAction()
        // "Someone new" holds an address ("Add"); the link is its own row.
        compose.onNodeWithText("Add").assertIsDisplayed()
        compose.onNodeWithText("Get link").assertDoesNotExist()
        compose.onNodeWithText("INVITE WITH A LINK").assertIsDisplayed()
        compose.onNodeWithContentDescription("Invite with a link").assertHasClickAction()
        compose.onNodeWithText("SHARE A LINK").assertDoesNotExist()
        compose.onNodeWithText("Share a link").assertDoesNotExist()
        // Settings › People would leave the unsaved task behind.
        compose.onNodeWithText("Manage people").assertDoesNotExist()
    }

    @Test
    fun aTypedAddressIsHeldAndListedUntilTheTaskIsAdded() {
        val m = screen(emptyMap())
        // The one text field on the pre-create screen: Someone new's address.
        compose.onNode(hasSetTextAction()).performTextInput("maya@example.com")
        compose.onNodeWithText("Add").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(mapOf("maya@example.com" to ShareLevel.PARTNER), m.state.value.shares.emails)
        compose.onNodeWithText("maya@example.com").assertIsDisplayed()
        compose.onNodeWithText("Gets it when you add the task · can edit").assertIsDisplayed()
        compose.onNodeWithText("✓ maya@example.com gets it when you add the task — they can edit.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove maya@example.com").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertTrue(m.state.value.shares.isEmpty)
        compose.onNodeWithText("Gets it when you add the task · can edit").assertDoesNotExist()
    }

    @Test
    fun aRealTasksScreenKeepsManagePeople() {
        val m = ShareScreenModel(ShareTarget.Task("t1", "Plan the Lisbon trip"), transport = PreCreateFakeTransport(roster))
        var managed = 0
        compose.setContent {
            UnstuckTheme(dark = false) {
                val s by m.state.collectAsState()
                ShareScreenBody(m, s, onDone = {}, onChoose = {}, onReport = {}, onBlock = {}, onManagePeople = { managed++ })
            }
        }
        compose.onNodeWithText("Manage people").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, managed)
        compose.onNodeWithText("INVITE WITH A LINK").assertDoesNotExist()
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
