package tech.csalliance.unstuck.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tech.csalliance.unstuck.core.logic.AssistantHarness
import tech.csalliance.unstuck.core.logic.AssistantHarnessRules
import tech.csalliance.unstuck.core.logic.HarnessAsk
import tech.csalliance.unstuck.core.logic.HarnessAskFailed
import tech.csalliance.unstuck.core.logic.HarnessFallback
import tech.csalliance.unstuck.core.logic.HarnessMessage
import tech.csalliance.unstuck.core.logic.HarnessReply
import tech.csalliance.unstuck.core.logic.HarnessToolCall
import tech.csalliance.unstuck.core.logic.HarnessToolRunner
import tech.csalliance.unstuck.core.logic.HarnessTurn
import tech.csalliance.unstuck.core.logic.ReceiptUndo
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

// The harness contract (docs/assistant-tool-contract.md §"Harness contract"),
// ported from the iOS AssistantHarnessTests + the web use-assistant battery:
// the fabrication guard bounces a tool-less claim ONCE with the exact hidden
// corrective, nothing is ever synthesised as "Done.", a cut-off reply gets the
// split hint, truncated tool JSON gets the retry hint, and every fallback is
// earned by what actually ran. The fakes never suspend, so the suspend loop
// runs to completion synchronously on a stdlib continuation (no
// kotlinx.coroutines in :core).
class AssistantHarnessTest {

    private fun <T> runSync(block: suspend () -> T): T {
        var out: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
        return (out ?: error("the block suspended")).getOrThrow()
    }

    private class ScriptedAsk(vararg replies: HarnessReply) : HarnessAsk {
        private val replies = replies.toMutableList()
        val asks = mutableListOf<List<HarnessMessage>>()
        override suspend fun ask(messages: List<HarnessMessage>): HarnessReply {
            asks += messages
            return replies.removeFirstOrNull() ?: throw IllegalStateException("upstream")
        }
    }

    /** A tiny executor speaking the contract's result strings. */
    private class FakeRunner : HarnessToolRunner {
        val tasks = mutableListOf<String>()
        val calls = mutableListOf<HarnessToolCall>()
        var staged = 0
        private var seq = 0
        override suspend fun run(call: HarnessToolCall): String {
            calls += call
            val args = runCatching { Json.parseToJsonElement(call.argumentsJson).jsonObject }.getOrNull() ?: return "error: bad json"
            return when (call.name) {
                "create_task" -> {
                    val name = args["name"]?.jsonPrimitive?.content ?: return "error: name required"
                    seq += 1; tasks += name
                    "ok: created task id=t$seq name=\"$name\""
                }
                "create_tasks" -> if (args["tasks"] == null) "error: tasks required" else "ok: created 2 tasks ids=a,b — \"a\", \"b\""
                "get_schedule" -> "ok:\nMonday 2026-09-07 (TODAY): —"
                "get_period_review" ->
                    if (args["period"]?.jsonPrimitive?.content == "fortnight") "error: unknown period \"fortnight\" — use today, …"
                    else "ok: review of last week (Mon 14 Sep – Sun 20 Sep).\nDone: 1 task — \"Draft chapter 3\"."
                "share_task" -> { staged += 1; "ok: staged" }
                "open_screen" -> "ok: opened ${args["screen"]?.jsonPrimitive?.content}"
                "boom" -> throw IllegalStateException("kaboom")
                else -> "error: unknown tool"
            }
        }
    }

    private fun text(s: String?) = HarnessReply(s)
    private fun call(name: String, args: String, id: String = "c1", finishReason: String? = null, content: String? = null) =
        HarnessReply(content, listOf(HarnessToolCall(id, name, args)), finishReason)

    private val runner = FakeRunner()
    private val observed = mutableListOf<Pair<String, String>>()

    private fun run(userText: String, ask: ScriptedAsk, history: List<HarnessMessage> = emptyList()): HarnessTurn =
        runSync { AssistantHarness(ask, runner) { c, r -> observed += c.name to r }.turn(history, userText) }

    private fun visible(turn: HarnessTurn): List<String> {
        val hidden = AssistantHarnessRules.hiddenIndices(turn.messages)
        return turn.messages.withIndex()
            .filter { (i, m) -> (m.role == "user" || m.role == "assistant") && i !in hidden && !m.content.isNullOrBlank() }
            .map { it.value.content!! }
    }

    // ---- fabrication guard

    @Test fun `a claim with no tool is hidden and bounced once with the exact corrective`() {
        val ask = ScriptedAsk(text("Done — added \"Milk\" to your list."), text("Which list should it go on?"))
        val turn = run("add milk", ask)
        assertEquals("Which list should it go on?", turn.text)
        assertTrue(turn.bounced)
        assertNull(turn.fallback)
        assertEquals(2, ask.asks.size)
        // The second request carries the hidden claim + the hidden corrective, verbatim.
        val second = ask.asks[1]
        assertEquals(listOf("user", "assistant", "user"), second.map { it.role })
        assertEquals("Done — added \"Milk\" to your list.", second[1].content)
        assertEquals(AssistantHarnessRules.CORRECTIVE, second[2].content)
        // The 2026-09-20 wording (docs/assistant-tooling-rules.md §3), verbatim on
        // every platform: it never asserts "nothing was done" — that invited the
        // model to redo an EARLIER turn's action when the bounced line was a recap.
        assertEquals(
            "(from the app, not the user: you described an action, but no tool ran THIS turn. If it is still needed, call the right tool now and then say in a few words what happened; if you were describing something from an earlier turn, answer plainly without claiming it again. Never claim an action without its tool result.)",
            second[2].content,
        )
        // The user never sees the claim or the check.
        assertEquals(listOf("add milk", "Which list should it go on?"), visible(turn))
        assertEquals(2, AssistantHarnessRules.hiddenIndices(turn.messages).size)
    }

    // ---- the period-review recap (week-review-spec §5.4)

    private val recapReply = "You finished \"Draft chapter 3\" and skipped \"Stretch\" once. Before that you did less, and that's fine."

    @Test fun `a review reply after an ok get_period_review is not bounced`() {
        val ask = ScriptedAsk(call("get_period_review", """{"period":"last_week"}"""), text(recapReply))
        val turn = run("how was last week?", ask)
        assertFalse(turn.bounced)
        assertEquals(2, ask.asks.size)
        assertEquals(recapReply, turn.text)
    }

    @Test fun `the same reply is bounced when the review failed or never ran`() {
        val failed = ScriptedAsk(call("get_period_review", """{"period":"fortnight"}"""), text(recapReply), text("Which week did you mean?"))
        assertTrue(run("how was my fortnight?", failed).bounced)
        val noReview = ScriptedAsk(call("get_schedule", """{"range":"week"}"""), text(recapReply), text("Here's the week."))
        assertTrue(run("how was last week?", noReview).bounced)
        val none = ScriptedAsk(text(recapReply), text("Let me look."))
        assertTrue(run("how was last week?", none).bounced)
    }

    @Test fun `a real claim is still bounced after a review`() {
        val ask = ScriptedAsk(call("get_period_review", """{"period":"last_week"}"""), text("You finished \"Draft chapter 3\". I moved \"Tax return\" to Friday."), text("Want me to move it?"))
        assertTrue(run("how was last week?", ask).bounced)
    }

    @Test fun `the guard bounces only once per turn`() {
        val ask = ScriptedAsk(text("Done — added it."), text("I've added it for you."))
        val turn = run("add milk", ask)
        assertEquals("I've added it for you.", turn.text)
        assertEquals(2, ask.asks.size)
        assertEquals("I've added it for you.", visible(turn).last())
    }

    @Test fun `a read-only tool does not disarm the guard`() {
        val ask = ScriptedAsk(call("get_schedule", """{"range":"today"}"""), text("Done — moved it to Friday."), text("Which time on Friday?"))
        val turn = run("move alpha to friday", ask)
        assertEquals("Which time on Friday?", turn.text)
        assertEquals(3, ask.asks.size)
        assertTrue(ask.asks[2].any { it.content == AssistantHarnessRules.CORRECTIVE })
        assertFalse(AssistantHarnessRules.writeToolSucceeded(turn.toolResults))
    }

    @Test fun `after a bounce the retry's tool runs and its self-correction is stripped`() {
        val ask = ScriptedAsk(
            text("Done — added \"Milk\"."),
            call("create_task", """{"name":"Milk"}"""),
            text("Sorry, I said I added it but I didn't. Added it now."),
        )
        val turn = run("add milk", ask)
        assertEquals("Added it now.", turn.text)
        assertTrue(turn.bounced)
        assertEquals(listOf("Milk"), runner.tasks)
        assertEquals(1, turn.toolResults.size)
        val receipts = AssistantHarnessRules.receiptsOf(turn.toolResults)
        assertEquals(listOf("Created “Milk”"), receipts.map { it.label })
        assertEquals(ReceiptUndo.deleteTask("t1"), receipts.first().undo)
    }

    @Test fun `a truthful claim after a write tool is not bounced, and the closing text is polished`() {
        val ask = ScriptedAsk(call("create_task", """{"name":"Milk"}"""), text("Done — added \"Milk\"."))
        val turn = run("add milk", ask)
        // Not bounced (the tool ran) — the "Done —" tic goes, the quoted name is untouched.
        assertEquals("Added \"Milk\".", turn.text)
        assertFalse(turn.bounced)
        assertEquals(2, ask.asks.size)
        assertEquals("Added \"Milk\".", turn.messages.last().content)
    }

    // ---- honest fallbacks — never a synthesised "Done."

    @Test fun `an empty final reply with nothing done says nothing was changed`() {
        val turn = run("hello", ScriptedAsk(text("   ")))
        assertEquals(AssistantHarnessRules.LOST_THREAD, turn.text)
        assertEquals(HarnessFallback.LOST_THREAD, turn.fallback)
        assertEquals("Hmm, I lost my thread there — nothing was changed. Try me again?", visible(turn).last())
        assertFalse(turn.messages.any { it.content == "Done." })
    }

    @Test fun `an empty final reply after a write falls back to the first receipt label`() {
        val turn = run("add milk", ScriptedAsk(call("create_task", """{"name":"Milk"}"""), text("")))
        assertEquals("Created “Milk”.", turn.text)
        assertEquals(HarnessFallback.PARTWAY, turn.fallback)
        val two = run("dump", ScriptedAsk(call("create_task", """{"name":"A"}""", id = "c1"), call("create_task", """{"name":"B"}""", id = "c2"), text(null)))
        assertEquals("Created “A” — and 1 more below.", two.text)
    }

    @Test fun `a receipt-less write falls back to the staged line`() {
        val turn = run("share alpha with zubair", ScriptedAsk(call("share_task", """{"taskId":"a","person":"Zubair"}"""), text("")))
        assertEquals(AssistantHarnessRules.STAGED_READY, turn.text)
        assertEquals(1, runner.staged)
    }

    @Test fun `a navigation with an empty reply names the screen and never disarms the guard`() {
        val turn = run("open today", ScriptedAsk(call("open_screen", """{"screen":"today"}"""), text("")))
        assertEquals("Opened today.", turn.text)
        assertEquals(HarnessFallback.LOST_THREAD, turn.fallback)
        assertFalse(AssistantHarnessRules.writeToolSucceeded(turn.toolResults))
        val bounced = run("open today", ScriptedAsk(call("open_screen", """{"screen":"today"}"""), text("Done — moved it."), text("Which task?")))
        assertTrue(bounced.bounced)
    }

    @Test fun `exhausted rounds close honestly`() {
        val reads = ScriptedAsk(*Array(5) { call("get_schedule", """{"range":"today"}""") })
        val turn = run("what's on?", reads)
        assertEquals(AssistantHarnessRules.RAN_OUT, turn.text)
        assertEquals(HarnessFallback.RAN_OUT, turn.fallback)
        assertEquals(5, reads.asks.size)
        assertFalse(turn.messages.any { it.content == "Done." })

        val writes = ScriptedAsk(*Array(5) { i -> call("create_task", """{"name":"T$i"}""", id = "c$i") })
        val turn2 = run("brain dump", writes)
        assertEquals(AssistantHarnessRules.partway(5), turn2.text)
        assertEquals("I got partway through — 5 things went through (receipts below). Tell me what's still missing.", turn2.text)
        assertEquals(5, AssistantHarnessRules.receiptsOf(turn2.toolResults).size)

        val staged = run("share", ScriptedAsk(*Array(5) { call("share_task", """{"taskId":"a","person":"Z"}""") }))
        assertEquals(AssistantHarnessRules.PARTWAY_STAGED, staged.text)
    }

    @Test fun `a failed later round keeps what went through, a failed first round keeps nothing`() {
        try {
            run("add milk", ScriptedAsk(call("create_task", """{"name":"Milk"}""")))   // then "upstream"
            fail("expected HarnessAskFailed")
        } catch (e: HarnessAskFailed) {
            val partial = e.partial
            assertNotNull(partial)
            assertEquals(AssistantHarnessRules.DROPPED_MID_REPLY, partial!!.text)
            assertEquals(AssistantHarnessRules.DROPPED_MID_REPLY, partial.messages.last().content)
            assertEquals(1, partial.toolResults.size)
            assertEquals("upstream", e.cause?.message)
        }
        try {
            run("hello", ScriptedAsk())
            fail("expected HarnessAskFailed")
        } catch (e: HarnessAskFailed) {
            assertNull(e.partial)
        }
    }

    // ---- hints

    @Test fun `truncated tool JSON gets the split hint and nothing runs`() {
        val turn = run("dump", ScriptedAsk(call("create_tasks", """{"tasks":[{"name":"a"},{"name":"b"""), text("ok")))
        val toolTurn = turn.messages.first { it.role == "tool" }
        assertEquals(AssistantHarnessRules.TRUNCATED_ARGS_RESULT, toolTurn.content)
        assertEquals("c1", toolTurn.toolCallId)
        assertEquals("create_tasks", toolTurn.name)
        assertEquals(0, runner.tasks.size)
        assertEquals(listOf("create_tasks" to AssistantHarnessRules.TRUNCATED_ARGS_RESULT), observed)
    }

    // ---- tool-call hygiene (DashScope 400s on a replayed non-object `arguments`, 2026-09-06)

    @Test fun `empty or truncated tool arguments are persisted and replayed as an empty object, valid ones verbatim`() {
        val ask = ScriptedAsk(
            call("create_task", "", id = "c1"),
            call("create_tasks", """{"tasks":[{"name":"a"},{"name":"b""", id = "c2", finishReason = "length"),
            call("create_task", """{"name":"A"}""", id = "c3"),
            text("Added A."),
        )
        val turn = run("dump", ask)
        val persisted = turn.messages.filter { it.toolCalls.isNotEmpty() }.map { it.toolCalls.single() }
        assertEquals(listOf("{}", "{}", """{"name":"A"}"""), persisted.map { it.argumentsJson })
        assertEquals(listOf("c1", "c2", "c3"), persisted.map { it.id })
        // The model-facing history on the last round carries the same normalised calls.
        assertEquals(listOf("{}", "{}", """{"name":"A"}"""), ask.asks[3].filter { it.toolCalls.isNotEmpty() }.map { it.toolCalls.single().argumentsJson })
        // Execution saw the RAW strings: the empty call got the runner's own
        // error, the truncated one the split hint, the valid one ran.
        assertEquals(listOf("", """{"tasks":[{"name":"a"},{"name":"b""", """{"name":"A"}"""), runner.calls.map { it.argumentsJson })
        val results = turn.messages.filter { it.role == "tool" }.map { it.content }
        assertEquals("error: bad json", results[0])
        assertEquals(AssistantHarnessRules.TRUNCATED_ARGS_RESULT, results[1])
        assertEquals("ok: created task id=t1 name=\"A\"", results[2])
        assertEquals(listOf("A"), runner.tasks)
        // toolResults (receipts) still carry the raw call, unchanged.
        assertEquals("", turn.toolResults[0].first.argumentsJson)
    }

    @Test fun `argumentsAsObjectJson keeps a JSON object verbatim and replaces everything else with an empty object`() {
        assertEquals("{}", AssistantHarnessRules.argumentsAsObjectJson(""))
        assertEquals("{}", AssistantHarnessRules.argumentsAsObjectJson("   \n"))
        assertEquals("{}", AssistantHarnessRules.argumentsAsObjectJson("""{"tasks":[{"name":"a"},{"name":"b"""))
        assertEquals("{}", AssistantHarnessRules.argumentsAsObjectJson("[]"))
        assertEquals("{}", AssistantHarnessRules.argumentsAsObjectJson("\"x\""))
        assertEquals("{}", AssistantHarnessRules.argumentsAsObjectJson("null"))
        // Lenient-only JSON (unquoted keys) is NOT an object to a strict upstream.
        assertEquals("{}", AssistantHarnessRules.argumentsAsObjectJson("{name: A}"))
        assertEquals("{}", AssistantHarnessRules.argumentsAsObjectJson("{}"))
        assertEquals("""{"name":"A","when":null}""", AssistantHarnessRules.argumentsAsObjectJson("""{"name":"A","when":null}"""))
        assertEquals(""" {"name":"A"} """, AssistantHarnessRules.argumentsAsObjectJson(""" {"name":"A"} """))
        assertEquals(
            listOf(HarnessToolCall("c1", "x", "{}"), HarnessToolCall("c2", "y", """{"a":1}""")),
            AssistantHarnessRules.normalisedForHistory(listOf(HarnessToolCall("c1", "x", ""), HarnessToolCall("c2", "y", """{"a":1}"""))),
        )
    }

    @Test fun `finish_reason length after a tool round adds the hidden cut-off hint after the tool results`() {
        val ask = ScriptedAsk(call("create_task", """{"name":"A"}""", finishReason = "length"), text("Added A."))
        val turn = run("add a", ask)
        val second = ask.asks[1]
        assertEquals(listOf("user", "assistant", "tool", "user"), second.map { it.role })
        assertEquals(AssistantHarnessRules.CUT_OFF_HINT, second.last().content)
        assertFalse(visible(turn).contains(AssistantHarnessRules.CUT_OFF_HINT))
        assertTrue(AssistantHarnessRules.isHidden(turn.messages, turn.messages.indexOfFirst { it.content == AssistantHarnessRules.CUT_OFF_HINT }))
    }

    @Test fun `a final text reply cut off by length gets no dangling hint`() {
        val turn = run("hi", ScriptedAsk(HarnessReply("Here is the plan: first", finishReason = "length")))
        assertFalse(turn.messages.any { it.content == AssistantHarnessRules.CUT_OFF_HINT })
        assertEquals("Here is the plan: first", turn.text)
    }

    @Test fun `a tool that throws yields an error result, never a crash`() {
        val turn = run("boom", ScriptedAsk(call("boom", """{"x":1}"""), text("That failed.")))
        assertEquals("error: kaboom", turn.toolResults.single().second)
        assertEquals("That failed.", turn.text)
    }

    // ---- thread shape

    @Test fun `history precedes the turn and every tool round is persisted`() {
        val history = listOf(HarnessMessage("user", "hi"), HarnessMessage("assistant", "Hello."))
        val turn = run("add milk", ScriptedAsk(call("create_task", """{"name":"Milk"}"""), text("Added it.")), history)
        assertEquals(listOf("user", "assistant", "user", "assistant", "tool", "assistant"), turn.messages.map { it.role })
        assertEquals("c1", turn.messages[3].toolCalls.single().id)
        assertEquals("c1", turn.messages[4].toolCallId)
    }

    @Test fun `the model window drops leading tool turns and aligns to a user turn`() {
        val msgs = listOf(
            HarnessMessage("tool", "ok", toolCallId = "x", name = "create_task"),
            HarnessMessage("assistant", "stale"),
            HarnessMessage("user", "hi"),
            HarnessMessage("assistant", "claim"),
            HarnessMessage("user", AssistantHarnessRules.CORRECTIVE),
        )
        assertEquals(listOf("user", "assistant", "user"), AssistantHarnessRules.modelWindow(msgs).map { it.role })
        val onlyTools = listOf(HarnessMessage("tool", "ok", toolCallId = "x"), HarnessMessage("assistant", "x"))
        assertEquals(listOf("assistant"), AssistantHarnessRules.modelWindow(onlyTools).map { it.role })
        assertEquals(2, AssistantHarnessRules.modelWindow(List(50) { HarnessMessage("user", "u$it") }, max = 2).size)
    }

    @Test fun `the rules expose the registry's tool classes`() {
        // Pinned copies of ToolRegistry.READ_ONLY / NAVIGATION / STAGED (:core can't
        // see the generated file) — ToolRegistryParityTest in :app holds them equal.
        assertEquals(setOf("get_tasks", "find_tasks", "get_schedule", "get_lists", "get_captures", "get_settings", "get_insights", "get_period_review", "get_calls"), AssistantHarnessRules.READ_ONLY_TOOLS)
        assertEquals(setOf("open_screen"), AssistantHarnessRules.NAVIGATION_TOOLS)
        assertEquals(setOf("share_task", "share_list"), AssistantHarnessRules.STAGED_TOOLS)
        assertEquals(5, AssistantHarnessRules.MAX_ROUNDS)
        assertTrue(AssistantHarnessRules.writeToolSucceeded(listOf(HarnessToolCall("1", "share_task", "{}") to "ok: staged")))
        assertTrue(AssistantHarnessRules.writeToolSucceeded(listOf(HarnessToolCall("1", "share_list", "{}") to "ok: prepared a share of list \"Groceries\"")))
        assertFalse(AssistantHarnessRules.writeToolSucceeded(listOf(HarnessToolCall("1", "create_task", "{}") to "error: name required")))
        // "Write succeeded" means the result STARTS WITH `ok:` — a bare "ok", a read
        // tool's ok or a navigation never disarm the guard.
        assertFalse(AssistantHarnessRules.writeToolSucceeded(listOf(HarnessToolCall("1", "set_task_later", "{}") to "ok")))
        assertFalse(AssistantHarnessRules.writeToolSucceeded(listOf(HarnessToolCall("1", "find_tasks", "{}") to "ok: 1 match")))
        assertFalse(AssistantHarnessRules.writeToolSucceeded(listOf(HarnessToolCall("1", "get_settings", "{}") to "ok: settings:")))
        assertFalse(AssistantHarnessRules.writeToolSucceeded(listOf(HarnessToolCall("1", "open_screen", "{}") to "ok: opened today")))
        assertTrue(AssistantHarnessRules.writeToolSucceeded(listOf(HarnessToolCall("1", "pin_list_item", "{}") to "ok: pinned \"Milk\" in \"Groceries\"")))
    }

    @Test fun `a share_list stage earns the staged fallback like share_task`() {
        val results = listOf(HarnessToolCall("1", "share_list", "{}") to "ok: prepared a share of list \"Groceries\" with Sam (viewer).")
        assertEquals(AssistantHarnessRules.STAGED_READY, AssistantHarnessRules.honestFallback(HarnessFallback.PARTWAY, results))
        assertEquals(AssistantHarnessRules.PARTWAY_STAGED, AssistantHarnessRules.honestFallback(HarnessFallback.RAN_OUT, results))
    }
}
