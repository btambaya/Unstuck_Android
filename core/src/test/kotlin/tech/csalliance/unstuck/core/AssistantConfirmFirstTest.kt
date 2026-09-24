package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.AssistantHarness
import tech.csalliance.unstuck.core.logic.ConfirmFirstRules
import tech.csalliance.unstuck.core.logic.HarnessAsk
import tech.csalliance.unstuck.core.logic.HarnessMessage
import tech.csalliance.unstuck.core.logic.HarnessReply
import tech.csalliance.unstuck.core.logic.HarnessToolCall
import tech.csalliance.unstuck.core.logic.HarnessToolRunner
import tech.csalliance.unstuck.core.logic.HarnessTurn
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Confirm-first in code (James's TestFlight thread, build 51): a destructive
 * tool runs only when the user's latest message asked for it, or said yes to
 * the assistant's question proposing it.
 */
class AssistantConfirmFirstTest {

    private fun refusal(tool: String, target: String?, user: String, prev: String? = null) = ConfirmFirstRules.refusal(tool, target, user, prev)

    // ── the rule ──

    @Test fun `James's turns - a delete nobody asked for is refused`() {
        val prev = "Park run is on Saturdays at 8:30am. Want me to schedule \"Pack ski gear checklist\" too?"
        assertNotNull(refusal("delete_task", "Pack ski gear checklist", "Show park run on calendar", prev))
        assertNotNull(refusal("delete_task", "Gym", "Add travel to Skipton for tomorrow at 3pm for 3 hours", prev))
        assertNotNull(refusal("delete_task", "Pack ski gear checklist", "Add travel to Skipton for tomorrow at 3pm for 3 hours", prev))
    }

    @Test fun `the user asking to delete THIS thing runs it`() {
        assertNull(refusal("delete_task", "Gym", "delete gym"))
        assertNull(refusal("delete_task", "Gym", "Please remove the Gym task"))
        assertNull(refusal("delete_task", "Park run", "get rid of park runs"))
        assertNull(refusal("delete_task", "Pack ski gear checklist", "can you delete the ski gear one"))
        assertNull(refusal("delete_task", "Gym", "delete it"))
        assertNull(refusal("delete_task", "Gym", "delete all my done tasks"))
        assertNull(refusal("delete_task", "Gym", "remove them all"))
        assertNull(refusal("delete_list", "Groceries", "delete my groceries list"))
        assertNull(refusal("delete_area", "Volunteering", "remove the volunteering area"))
        assertNull(refusal("delete_tag", "urgent", "delete the urgent tag"))
        assertNull(refusal("leave_list", "Team groceries", "leave the team groceries list"))
        assertNull(refusal("cancel_focus", null, "cancel this session"))
        assertNull(refusal("cancel_focus", null, "stop the timer, don't log it"))
    }

    @Test fun `a delete of a DIFFERENT thing than the one named is refused`() {
        // "delete gym" never deletes the task the previous reply was about.
        val prev = "Want me to schedule \"Pack ski gear checklist\" for Saturday?"
        assertNotNull(refusal("delete_task", "Pack ski gear checklist", "delete gym", prev))
        assertNotNull(refusal("delete_list", "Groceries", "delete the books list"))
        // A skier's trip isn't the ski gear checklist just because they share letters.
        assertNotNull(refusal("delete_task", "Pack ski gear checklist", "delete the Skipton trip"))
    }

    @Test fun `a negated verb is no request`() {
        assertNotNull(refusal("delete_task", "Gym", "don't delete gym, just move it to Friday"))
        assertNotNull(refusal("delete_task", "Gym", "do not remove the gym task"))
        assertNotNull(refusal("cancel_focus", null, "keep going, never cancel it"))
    }

    @Test fun `leave it means keep it, and a bare stop or end is not a cancel`() {
        assertNotNull(refusal("leave_list", "Team groceries", "leave it for now"))
        assertNotNull(refusal("cancel_focus", null, "remind me at the end of the day"))
        assertNotNull(refusal("cancel_focus", null, "add a stop at the bank"))
    }

    @Test fun `a yes to the assistant's question proposing it runs it`() {
        val asked = "Delete \"Gym\"? It has 3 slots on the calendar."
        assertNull(refusal("delete_task", "Gym", "yes", asked))
        assertNull(refusal("delete_task", "Gym", "Yep, go ahead", asked))
        assertNull(refusal("delete_task", "Gym", "sure do it", asked))
        assertNull(refusal("delete_task", "Gym", "just gym", "Which should I delete — \"Gym\" or \"Park run\"?"))
        assertNull(refusal("delete_task", "Gym", "yes", "Should I remove all 3 done tasks?"))
        assertNull(refusal("cancel_focus", null, "yes", "Cancel this session without logging it?"))
        assertNull(refusal("leave_list", "Team", "yes", "Leave \"Team\"? The owner keeps it."))
    }

    @Test fun `a yes to something else, or to a statement, runs nothing`() {
        // The question proposed a DIFFERENT task.
        assertNotNull(refusal("delete_task", "Pack ski gear checklist", "yes", "Delete \"Gym\"?"))
        // Not a question — "I removed it from Later" is a report, not a proposal.
        assertNotNull(refusal("delete_task", "Gym", "ok", "I removed \"Gym\" from Later."))
        // A question that proposes something else.
        assertNotNull(refusal("delete_task", "Gym", "yes", "Want me to schedule \"Gym\" for Friday?"))
        // A long "ok, …" is a new request, not the yes.
        assertNotNull(refusal("delete_task", "Gym", "ok, add milk to my shopping list", "Delete \"Gym\"?"))
        assertNotNull(refusal("delete_task", "Gym", "no, keep it", "Delete \"Gym\"?"))
    }

    @Test fun `tools outside the confirm-first set are never checked`() {
        assertNull(refusal("create_task", null, "hello"))
        assertNull(refusal("complete_task", "Gym", "Show park run on calendar"))
    }

    @Test fun `the refusal says nothing changed and tells the model to ask`() {
        assertEquals(
            "error: not confirmed — the user's latest message didn't ask to delete \"Gym\", so nothing was changed. " +
                "Don't call delete_task unless they ask for it: if you think they want it, ask ONE short question (\"Delete \"Gym\"?\") and call it only after they say yes.",
            refusal("delete_task", "Gym", "Show park run on calendar"),
        )
        val cancel = refusal("cancel_focus", null, "how long have I been going?")!!
        assertTrue(cancel, cancel.startsWith("error: not confirmed — the user's latest message didn't ask to cancel the focus session, so nothing was changed."))
        assertTrue(cancel, cancel.contains("finish_focus"))
        assertTrue(refusal("leave_list", "Team", "what's on it?")!!.contains("didn't ask to leave \"Team\""))
    }

    // ── in the harness ──

    private fun <T> runSync(block: suspend () -> T): T {
        var out: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
        return (out ?: error("the block suspended")).getOrThrow()
    }

    private class Scripted(vararg replies: HarnessReply) : HarnessAsk {
        private val replies = replies.toMutableList()
        val asks = mutableListOf<List<HarnessMessage>>()
        override suspend fun ask(messages: List<HarnessMessage>): HarnessReply { asks += messages; return replies.removeAt(0) }
    }

    private class Runner : HarnessToolRunner {
        val ran = mutableListOf<String>()
        override suspend fun run(call: HarnessToolCall): String { ran += call.name; return "ok: deleted \"Gym\"" }
    }

    private val names = mapOf("t-gym" to "Gym", "t-ski" to "Pack ski gear checklist")

    private fun turn(history: List<HarnessMessage>, user: String, runner: Runner, vararg replies: HarnessReply): HarnessTurn =
        runSync {
            AssistantHarness(Scripted(*replies), runner, confirmTarget = { call -> names[Regex("t-\\w+").find(call.argumentsJson)?.value] })
                .turn(history, user)
        }

    private fun deleteCall(id: String) = HarnessReply(null, listOf(HarnessToolCall("c1", "delete_task", "{\"taskId\":\"$id\"}")), "tool_calls")

    @Test fun `the harness never runs an unasked delete and hands the model the refusal`() {
        val runner = Runner()
        val out = turn(emptyList(), "Add travel to Skipton for tomorrow at 3pm for 3 hours", runner,
            deleteCall("t-ski"), HarnessReply("Did you mean to remove anything? Say which one."))
        assertTrue("the executor never ran", runner.ran.isEmpty())
        val (_, result) = out.toolResults.single()
        assertTrue(result, result.startsWith("error: not confirmed — the user's latest message didn't ask to delete \"Pack ski gear checklist\""))
        // No receipt can come from it, and the model saw the refusal.
        assertTrue(out.messages.any { it.role == "tool" && it.content == result })
    }

    @Test fun `the harness runs the delete the user asked for, and the one they said yes to`() {
        val runner = Runner()
        turn(emptyList(), "delete gym please", runner, deleteCall("t-gym"), HarnessReply("Deleted Gym."))
        assertEquals(listOf("delete_task"), runner.ran)
        // Two turns: the assistant asked, the user said yes.
        val history = listOf(HarnessMessage("user", "I never go to the gym"), HarnessMessage("assistant", "Delete \"Gym\"? It has 3 slots on the calendar."))
        val yes = Runner()
        turn(history, "yes", yes, deleteCall("t-gym"), HarnessReply("Deleted Gym."))
        assertEquals(listOf("delete_task"), yes.ran)
        // …but that yes never reaches a different task.
        val other = Runner()
        turn(history, "yes", other, deleteCall("t-ski"), HarnessReply("Okay."))
        assertTrue(other.ran.isEmpty())
    }
}
