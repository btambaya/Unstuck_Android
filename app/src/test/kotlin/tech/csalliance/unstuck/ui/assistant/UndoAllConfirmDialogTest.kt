package tech.csalliance.unstuck.ui.assistant

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.core.logic.Receipt
import tech.csalliance.unstuck.core.logic.ReceiptIcon
import tech.csalliance.unstuck.core.logic.ReceiptUndo
import tech.csalliance.unstuck.design.theme.UnstuckTheme
import tech.csalliance.unstuck.sync.ChatMessage

/**
 * "Undo all"'s confirmation stays pinned to the turn it was opened for. A
 * plain open flag outlived its turn: when the 15-minute window lapsed, or a
 * call ending in the background landed its receipts, the dialog re-targeted
 * under the user's finger or popped up later unasked (Android audit
 * 2026-09-23, A17). Confirming reverts exactly what it named.
 *
 * SDK 33, the Compose-only sandbox (see StartRepeatingPromptTest for why).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class UndoAllConfirmDialogTest {

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

    private fun created(name: String, id: String) = Receipt(ReceiptIcon.PLUS, "Created “$name”", ReceiptUndo.deleteTask(id))
    private fun turn(id: String, vararg receipts: Receipt) = ChatMessage("assistant", "Done.", id = id, at = 0L, receipts = receipts.toList())

    @Test fun `closes when its turn changes or lapses, and never reopens for another`() {
        var target by mutableStateOf<ChatMessage?>(turn("m1", created("Call mum", "a"), created("Bins", "b")))
        var openFor by mutableStateOf<String?>(null)
        compose.setContent {
            UnstuckTheme(dark = false) {
                UndoAllConfirmDialog(openFor, target, emptySet(), onDismiss = { openFor = null }) { _, _ -> openFor = null }
            }
        }
        compose.runOnIdle { openFor = "m1" }
        compose.onNodeWithText("Undo these 2 changes?").assertIsDisplayed()

        // A call ends in the background and lands its receipts: another turn.
        compose.runOnIdle { target = turn("m2", created("Renew the passport", "c")) }
        compose.waitForIdle()
        compose.onNodeWithText("Undo this change?").assertDoesNotExist()
        compose.onNodeWithText("Undo these 2 changes?").assertDoesNotExist()
        compose.runOnIdle { assertNull("closed, not re-targeted", openFor) }

        // Opened for m2, then the 15-minute window lapses: closed for good —
        // the next turn with receipts gets no dialog nobody asked for.
        compose.runOnIdle { openFor = "m2" }
        compose.onNodeWithText("Undo this change?").assertIsDisplayed()
        compose.runOnIdle { target = null }
        compose.waitForIdle()
        compose.runOnIdle { assertNull(openFor) }
        compose.runOnIdle { target = turn("m3", created("Water the plants", "d")) }
        compose.waitForIdle()
        compose.onNodeWithText("Undo this change?").assertDoesNotExist()
    }

    @Test fun `confirming reverts exactly the changes it named`() {
        val t = turn("m1", created("Call mum", "a"), created("Bins", "b"))
        var confirmed: Pair<String, List<Int>>? = null
        compose.setContent {
            UnstuckTheme(dark = false) {
                // Receipt 0's Undo was refused earlier: not named, not reverted.
                UndoAllConfirmDialog("m1", t, setOf(receiptUndoKey("m1", 0)), onDismiss = {}) { id, indices -> confirmed = id to indices }
            }
        }
        compose.onNodeWithText("• Created “Bins”").assertIsDisplayed()
        compose.onNodeWithText("• Created “Call mum”").assertDoesNotExist()
        compose.onNodeWithText("Undo").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals("m1" to listOf(1), confirmed) }
    }
}
