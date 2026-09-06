package tech.csalliance.unstuck.ui.assistant

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.csalliance.unstuck.core.logic.AssistantHarnessRules
import tech.csalliance.unstuck.sync.ChatMessage
import tech.csalliance.unstuck.sync.ToolCall
import tech.csalliance.unstuck.sync.ToolFunction

/** The sheet's display filter (web lib/assistant/display.ts + iOS
 *  AssistantModel.displayTurns parity): a person sees user bubbles, each
 *  turn's FINAL reply and local lines — never tool turns, hidden bounces, or
 *  the model's per-round narration that rides on a tool_calls round. */
class AssistantSheetDisplayTest {
    private fun user(text: String) = ChatMessage("user", text)
    private fun assistant(text: String?) = ChatMessage("assistant", text)
    private fun narration(text: String?, tool: String = "get_lists") =
        ChatMessage("assistant", text, toolCalls = listOf(ToolCall("c1", "function", ToolFunction(tool, "{}"))))
    private fun tool(result: String) = ChatMessage("tool", result, toolCallId = "c1", name = "get_lists")

    @Test fun `a turn with two tool rounds and a final reply renders ONE assistant bubble`() {
        // The tester's screenshot (iOS 2026-09-06): four assistant bubbles for
        // one question — each round's "I'll get… / Let me try…" narration.
        val thread = listOf(
            user("I would like to review what I have in my collections"),
            narration("I'll get your current collections (lists) for you."),
            tool("error: unknown tool \"get_collections\" — available: …"),
            narration("Let me try the correct tool:"),
            tool("ok: 2 lists:\n- \"ToDo\" [id=l1] — 3 open"),
            assistant("Two lists: ToDo and Shopping."),
        )
        assertEquals(
            listOf("user:I would like to review what I have in my collections", "assistant:Two lists: ToDo and Shopping."),
            visibleAssistantTurns(thread).map { "${it.role}:${it.content}" },
        )
    }

    @Test fun `local lines stay, hidden bounces, empty turns and tool turns go`() {
        val thread = listOf(
            user("hi"),
            ChatMessage("assistant", "Morning, Maya. 3 things on today.", local = true),
            assistant("Added it."),
            user(AssistantHarnessRules.CORRECTIVE),
            narration(null, tool = "create_task"),
            tool("ok: created task id=t1 name=\"x\""),
            assistant("   "),
            user(AssistantHarnessRules.CUT_OFF_HINT),
            assistant("Created \"x\"."),
        )
        assertEquals(listOf("hi", "Morning, Maya. 3 things on today.", "Created \"x\"."), visibleAssistantTurns(thread).map { it.content })
    }
}
