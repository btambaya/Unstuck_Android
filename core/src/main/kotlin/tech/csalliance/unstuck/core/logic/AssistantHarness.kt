package tech.csalliance.unstuck.core.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import tech.csalliance.unstuck.core.time.ClockMode
import java.util.concurrent.CancellationException

// The agentic turn loop — the harness contract from
// docs/assistant-tool-contract.md, 1:1 with lib/assistant/use-assistant.ts and
// App/Features/AssistantHarness.swift:
//
//  • ask → run every tool call → append `tool` turns → ask again (≤5 rounds);
//  • FABRICATION GUARD: a reply with no tool calls, no write tool succeeded
//    this turn, and text that CLAIMS an action is hidden and bounced ONCE with
//    the hidden corrective; if the retry's tool ran, its self-correction is
//    stripped (the user never saw the claim);
//  • finish_reason=length after a tool round → the hidden "cut off" hint
//    (placed AFTER the tool results so every tool message stays adjacent to
//    its tool_calls parent — the orphaned-tool shape the upstream 400s on);
//  • truncated tool-call JSON → tell the model to split the call;
//  • CONFIRM-FIRST: a destructive call the user didn't ask for is refused
//    before it runs (AssistantConfirmFirst.kt);
//  • honest fallbacks only; NEVER a synthesised "Done." (Android defect F2).
//
// Pure with respect to the store: the loop talks to a [HarnessAsk] and a
// [HarnessToolRunner] and hands the whole working thread back in the
// [HarnessTurn], so it runs under JUnit against fakes. The app owns the
// context/profile (in the system/context message it builds around `ask`),
// receipts (derived from [HarnessTurn.toolResults] via [deriveReceipt]),
// persistence, the send queue and the cancel epoch.

data class HarnessToolCall(val id: String, val name: String, val argumentsJson: String)

data class HarnessMessage(
    /** system | user | assistant | tool */
    val role: String,
    val content: String? = null,
    val toolCalls: List<HarnessToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
)

data class HarnessReply(
    val text: String?,
    val toolCalls: List<HarnessToolCall> = emptyList(),
    /** stop | tool_calls | length | null — "length" when the upstream cut the reply off. */
    val finishReason: String? = null,
)

/** The model round-trip. The app wraps AssistantClient; context/profile go in
 *  the app-owned system/context message the app builds. A failure THROWS —
 *  the harness wraps it in [HarnessAskFailed] with whatever went through. */
fun interface HarnessAsk { suspend fun ask(messages: List<HarnessMessage>): HarnessReply }

/** Executes one tool call and returns the contract string: `ok: …` | `error: …`.
 *  A throw is turned into an `error: …` result, never a crash. */
fun interface HarnessToolRunner { suspend fun run(call: HarnessToolCall): String }

enum class HarnessFallback { LOST_THREAD, PARTWAY, RAN_OUT }

data class HarnessTurn(
    /** The closing text to show (already polished / fallback-substituted). */
    val text: String,
    /** The full history to persist, including tool rounds and hidden bounces
     *  (see [AssistantHarnessRules.hiddenIndices]). */
    val messages: List<HarnessMessage>,
    /** Every executed call with its result, in order — receipts come from here. */
    val toolResults: List<Pair<HarnessToolCall, String>>,
    /** Which honest fallback produced [text], or null when the model's own text closed the turn. */
    val fallback: HarnessFallback?,
    /** The fabrication guard bounced the model once this turn. */
    val bounced: Boolean,
)

/** `ask` failed. [partial] is null on a FIRST-round failure (nothing happened —
 *  the app keeps/drops the user's turn and shows the error inline); on a later
 *  round it carries the partial exchange, closed with
 *  [AssistantHarnessRules.DROPPED_MID_REPLY] when tools already ran so the
 *  committed actions are visible with their receipts (never re-run on retry). */
class HarnessAskFailed(cause: Throwable, val partial: HarnessTurn?) : RuntimeException(cause.message, cause)

object AssistantHarnessRules {
    const val MAX_ROUNDS = 5

    /** The per-request model window (matches the web's MAX_MODEL_WINDOW / iOS). */
    const val MAX_MODEL_WINDOW = ASSISTANT_MAX_MODEL_WINDOW

    // The registry's tool CLASSES (lib/assistant/tool-registry.json `kind`).
    // :core cannot see the generated ToolRegistry (it lives in :app), so these
    // are pinned copies — ToolRegistryParityTest (:app) asserts they equal
    // ToolRegistry.READ_ONLY / NAVIGATION / STAGED, so a regenerated registry
    // that adds a read tool fails a test instead of silently arming the guard.

    /** Reads never disarm the fabrication guard and earn no receipt. */
    val READ_ONLY_TOOLS: Set<String> = setOf("get_tasks", "find_tasks", "get_schedule", "get_lists", "get_captures", "get_settings", "get_insights", "get_period_review", "get_calls")

    /** Did a get_period_review of THIS turn return `ok:`? Then the reply is a
     *  recap of the user's own past actions and the guard neutralises
     *  user-subject verbs (week-review-spec §5.4). */
    fun reviewedThisTurn(results: List<Pair<HarnessToolCall, String>>): Boolean =
        results.any { (call, result) -> call.name == "get_period_review" && result.startsWith("ok:") }

    /** Tools that only NAVIGATE — no data changes, no staged card. Neither a
     *  write (they must not disarm the guard) nor "nothing changed" (the
     *  empty-reply fallback says what was opened instead). */
    val NAVIGATION_TOOLS: Set<String> = setOf("open_screen")

    /** share_task / share_list only STAGE a confirm card — the receipt-less
     *  writes the "check the card below" fallback is written for. */
    val STAGED_TOOLS: Set<String> = setOf("share_task", "share_list")

    /** Hidden user-role bounce after a fabricated claim. NOT from the user, and
     *  the user never saw the claim. It never asserts "nothing was done": that
     *  wording invited the model to REDO an earlier turn's action (a duplicate
     *  task) when the bounced sentence was a truthful recap. Verbatim on web,
     *  iOS and Android — docs/assistant-tooling-rules.md §3 (2026-09-20). */
    const val CORRECTIVE = "(from the app, not the user: you described an action, but no tool ran THIS turn. If it is still needed, call the right tool now and then say in a few words what happened; if you were describing something from an earlier turn, answer plainly without claiming it again. Never claim an action without its tool result.)"

    /** Hidden hint when the upstream says the reply was cut off by length. */
    const val CUT_OFF_HINT = "(your previous reply was cut off by the length limit — continue from where it stopped, splitting any large tool call into smaller calls of at most 12 items.)"

    /** Tool result substituted when the call's JSON parsed to {} but wasn't empty. */
    const val TRUNCATED_ARGS_RESULT = "error: your tool call arguments were cut off mid-JSON — retry with fewer items per call (split large lists across several calls)"

    // Honest fallbacks (contract §6) — verbatim from use-assistant.ts / AssistantHarness.swift.
    const val LOST_THREAD = "Hmm, I lost my thread there — nothing was changed. Try me again?"
    const val STAGED_READY = "Ready — check the card below."
    const val WENT_THROUGH = "That went through."
    const val DROPPED_MID_REPLY = "The connection dropped mid-reply — but these went through:"
    const val PARTWAY_STAGED = "I got partway through — see what's staged below and tell me what's still missing."
    const val PARTWAY_UNRECEIPTED = "I got partway through — something went through, but I ran out of steps. Tell me what's still missing."
    const val RAN_OUT = "I ran out of steps without getting that done — nothing was changed. Try again, or break it into smaller asks."

    fun partway(n: Int): String =
        "I got partway through — $n thing${if (n == 1) "" else "s"} went through (receipts below). Tell me what's still missing."

    /** The executor contract (docs/assistant-tooling-rules.md §1): a result is a
     *  success ONLY when it starts with `ok:` — "not an error" also let a bare
     *  "ok" or a malformed result arm the guard. */
    fun succeeded(result: String) = result.startsWith("ok:")

    /** A WRITE tool succeeded (even receipt-less ones like share_task). Read-only
     *  successes, navigation and errored tools don't count — and don't disarm the guard. */
    fun writeToolSucceeded(results: List<Pair<HarnessToolCall, String>>): Boolean =
        results.any { (call, result) -> succeeded(result) && call.name !in READ_ONLY_TOOLS && call.name !in NAVIGATION_TOOLS }

    private fun stagedShare(results: List<Pair<HarnessToolCall, String>>): Boolean =
        results.any { (call, result) -> succeeded(result) && call.name in STAGED_TOOLS }

    /** The screen a successful navigation opened ("ok: opened today" → "today"), or null. */
    fun navigatedTo(results: List<Pair<HarnessToolCall, String>>): String? =
        results.lastOrNull { (call, result) -> succeeded(result) && call.name in NAVIGATION_TOOLS }
            ?.let { (_, result) -> result.replace(Regex("^ok:\\s*opened\\s*"), "").trim().ifEmpty { "that screen" } }

    /** Receipts the app will derive from these results (store-less: names come
     *  from the result strings; a Later/recurrence receipt just lacks its task name). */
    fun receiptsOf(results: List<Pair<HarnessToolCall, String>>, clock: ClockMode = ClockMode.H24): List<Receipt> =
        results.mapNotNull { (call, result) -> deriveReceipt(call.name, receiptArgsFromJson(call.argumentsJson), result, emptyList(), clock = clock) }

    /** The honest closing line for a turn the model didn't close itself. Never
     *  "Done.": every line is earned by [results] or says nothing changed. */
    fun honestFallback(kind: HarnessFallback, results: List<Pair<HarnessToolCall, String>>, clock: ClockMode = ClockMode.H24): String {
        val receipts = receiptsOf(results, clock)
        val nav = navigatedTo(results)
        return when (kind) {
            HarnessFallback.LOST_THREAD -> if (nav != null) "Opened $nav." else LOST_THREAD
            // Empty text after a successful write: say what happened.
            HarnessFallback.PARTWAY -> when {
                receipts.isNotEmpty() -> "${receipts[0].label}${if (receipts.size > 1) " — and ${receipts.size - 1} more below" else ""}."
                stagedShare(results) -> STAGED_READY
                writeToolSucceeded(results) -> WENT_THROUGH
                nav != null -> "Opened $nav."
                else -> LOST_THREAD
            }
            // Ran out of rounds — the plan may be half-executed.
            HarnessFallback.RAN_OUT -> when {
                receipts.isNotEmpty() -> partway(receipts.size)
                stagedShare(results) -> PARTWAY_STAGED
                writeToolSucceeded(results) -> PARTWAY_UNRECEIPTED
                nav != null -> "I opened $nav but ran out of steps before finishing — nothing else was changed. Tell me what's still missing."
                else -> RAN_OUT
            }
        }
    }

    /** Indices of the messages the user must never see: the hidden bounce
     *  (the bounced claim + the corrective) and the cut-off hint. The model
     *  DOES see them (they're part of the thread). */
    fun hiddenIndices(messages: List<HarnessMessage>): Set<Int> {
        val out = HashSet<Int>()
        for ((i, m) in messages.withIndex()) {
            if (m.role == "user" && (m.content == CORRECTIVE || m.content == CUT_OFF_HINT)) {
                out += i
                if (m.content == CORRECTIVE && i > 0 && messages[i - 1].role == "assistant" && messages[i - 1].toolCalls.isEmpty()) out += i - 1
            }
        }
        return out
    }

    fun isHidden(messages: List<HarnessMessage>, index: Int): Boolean = index in hiddenIndices(messages)

    /** The per-request model window: the tail, aligned to start at a user
     *  turn, never leading with an orphaned tool message (the upstream 400s on
     *  a tool message whose tool_calls parent was sliced off). Local display
     *  turns (the check-in line) must be excluded by the caller. */
    fun modelWindow(messages: List<HarnessMessage>, max: Int = MAX_MODEL_WINDOW): List<HarnessMessage> {
        var win = messages.takeLast(max)
        val firstUser = win.indexOfFirst { it.role == "user" }
        if (firstUser > 0) win = win.drop(firstUser)
        while (win.isNotEmpty() && win[0].role == "tool") win = win.drop(1)
        return win
    }

    private val LENIENT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Does the call's `arguments` JSON parse to a non-empty object? Malformed
     *  (e.g. cut off mid-JSON) reads as empty, like the web's parseToolArgs. */
    fun argsAreEmpty(argumentsJson: String): Boolean =
        runCatching { LENIENT_JSON.parseToJsonElement(argumentsJson).jsonObject.isEmpty() }.getOrDefault(true)

    // ---- tool-call hygiene

    /** A tool call's `arguments` as it is PERSISTED and replayed: anything that
     *  does not parse (STRICTLY) to a JSON object becomes "{}". DashScope rejects
     *  the WHOLE request (400 InvalidParameter: "function.arguments … must be in
     *  JSON format") when any replayed assistant tool_call carries an empty
     *  string or JSON cut off by finish_reason=length — clients persisted those
     *  turns verbatim, so ONE bad call poisoned every later turn of the thread
     *  (2026-09-06). A valid object is returned untouched, so the model-facing
     *  history is otherwise identical. Execution still sees the raw string (the
     *  truncated-args hint keys on it). */
    fun argumentsAsObjectJson(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return "{}"
        return if (runCatching { Json.parseToJsonElement(s).jsonObject }.isSuccess) raw else "{}"
    }

    /** The calls as they go into the thread (see [argumentsAsObjectJson]). */
    fun normalisedForHistory(calls: List<HarnessToolCall>): List<HarnessToolCall> =
        calls.map { it.copy(argumentsJson = argumentsAsObjectJson(it.argumentsJson)) }
}

class AssistantHarness(
    private val ask: HarnessAsk,
    private val runner: HarnessToolRunner,
    /** The display name of the thing a confirm-first call would destroy (the
     *  task, list, area or tag its arguments point at), or null when unknown /
     *  when there is only one (cancel_focus) — see [ConfirmFirstRules]. */
    private val confirmTarget: suspend (HarnessToolCall) -> String? = { null },
    /** The phone's 12/24-hour preference — the times in a closing line the
     *  harness writes itself (a receipt's label, [AssistantHarnessRules.honestFallback]). */
    private val clock: ClockMode = ClockMode.H24,
    /** Observed after every executed call (the app derives + shows receipts live). */
    private val onToolResult: (HarnessToolCall, String) -> Unit = { _, _ -> },
) {
    /** Run one turn. [history] is the persisted thread WITHOUT the new user
     *  message (local display-only turns already excluded); [userText] is
     *  appended as the user turn. Throws [HarnessAskFailed] when the model
     *  round-trip fails; CancellationException propagates untouched. */
    suspend fun turn(history: List<HarnessMessage>, userText: String): HarnessTurn {
        val working = ArrayList<HarnessMessage>(history)
        // The assistant's last reply the user SAW before this message — what a
        // "yes" answers (confirm-first). Tool rounds and the hidden bounce don't count.
        val hidden = AssistantHarnessRules.hiddenIndices(history)
        val previousReply = history.indices.reversed().firstOrNull { i ->
            val m = history[i]
            i !in hidden && m.role == "assistant" && m.toolCalls.isEmpty() && !m.content.isNullOrBlank()
        }?.let { history[it].content }
        working += HarnessMessage("user", userText)
        val results = ArrayList<Pair<HarnessToolCall, String>>()
        // Fabrication guard state — at most one corrective bounce per turn.
        var corrected = false
        var writeSucceeded = false

        for (i in 0 until AssistantHarnessRules.MAX_ROUNDS) {
            val reply = try {
                ask.ask(AssistantHarnessRules.modelWindow(working))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A failed FIRST round: nothing happened. A later round keeps
                // the partial exchange — and if tools already ran, says so.
                if (i == 0) throw HarnessAskFailed(e, null)
                val text = if (results.isNotEmpty()) AssistantHarnessRules.DROPPED_MID_REPLY else ""
                if (text.isNotEmpty()) working += HarnessMessage("assistant", text)
                throw HarnessAskFailed(e, HarnessTurn(text, working.toList(), results.toList(), null, corrected))
            }

            val content = reply.text ?: ""
            // FABRICATION GUARD (a stable qwen failure mode, 2026-08-29).
            if (reply.toolCalls.isEmpty() && !corrected && !writeSucceeded &&
                AssistantGuard.looksLikeActionClaim(content, recap = AssistantHarnessRules.reviewedThisTurn(results))
            ) {
                corrected = true
                working += HarnessMessage("assistant", content)
                working += HarnessMessage("user", AssistantHarnessRules.CORRECTIVE)
                continue
            }

            val replyIndex = working.size
            // The thread (persisted + replayed) carries NORMALISED tool calls — a
            // non-object `arguments` string would 400 every later request (see
            // argumentsAsObjectJson); execution below still runs on the raw
            // reply so the truncated-args hint fires.
            working += HarnessMessage("assistant", reply.text, AssistantHarnessRules.normalisedForHistory(reply.toolCalls))

            if (reply.toolCalls.isEmpty()) {
                // Final reply. NEVER synthesise "Done." (harness audit, 2026-09-01).
                var closing = content.trim()
                // Retry after a bounce and the tool really ran: drop any
                // "sorry, I said…" the model aimed at the hidden check.
                if (corrected && writeSucceeded) closing = AssistantGuard.stripSelfCorrection(closing)
                // Deterministic register polish on the model's FINAL text only —
                // AFTER the fabrication guard saw the raw claim, never on the
                // hidden bounce, never on voice (naturalness, 2026-09-06).
                if (closing.isNotEmpty()) closing = polishReply(closing, PolishOptions(clock = clock))
                var fallback: HarnessFallback? = null
                if (closing.isEmpty()) {
                    fallback = if (writeSucceeded) HarnessFallback.PARTWAY else HarnessFallback.LOST_THREAD
                    closing = AssistantHarnessRules.honestFallback(fallback, results, clock)
                }
                working[replyIndex] = working[replyIndex].copy(content = closing)
                return HarnessTurn(closing, working.toList(), results.toList(), fallback, corrected)
            }

            // Execute each tool call, append its result for the next round.
            for (call in reply.toolCalls) {
                // CONFIRM-FIRST in code: a destructive tool the user's message
                // didn't ask for (nor said yes to) never runs — the model reads
                // why and asks instead (James's stray deletes, build 51).
                val refused = if (call.name in ConfirmFirstRules.TOOLS) {
                    val target = try { confirmTarget(call) } catch (e: CancellationException) { throw e } catch (e: Throwable) { null }
                    ConfirmFirstRules.refusal(call.name, target, userText, previousReply)
                } else null
                var result = refused ?: try {
                    runner.run(call)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    "error: ${e.message ?: "failed"}"
                }
                // "Write succeeded" = a tool outside the registry's read-only and
                // navigation sets whose result starts with `ok:` (rules §3).
                if (AssistantHarnessRules.succeeded(result) &&
                    call.name !in AssistantHarnessRules.READ_ONLY_TOOLS &&
                    call.name !in AssistantHarnessRules.NAVIGATION_TOOLS
                ) writeSucceeded = true
                // Truncated tool-call JSON (completion cap) parses to {} —
                // tell the model WHY it failed so it splits the call instead of flailing.
                if (result.startsWith("error") && AssistantHarnessRules.argsAreEmpty(call.argumentsJson) && call.argumentsJson.length > 2) {
                    result = AssistantHarnessRules.TRUNCATED_ARGS_RESULT
                }
                results += call to result
                onToolResult(call, result)
                working += HarnessMessage("tool", result, toolCallId = call.id, name = call.name)
            }
            // The upstream said the reply was CUT OFF by length: later tool
            // calls never arrived. Tell the model explicitly — AFTER the tool
            // results, so every tool message stays adjacent to its parent.
            if (reply.finishReason == "length") working += HarnessMessage("user", AssistantHarnessRules.CUT_OFF_HINT)
        }

        // Ran out of rounds — close out HONESTLY (the plan may be half-executed).
        val closing = AssistantHarnessRules.honestFallback(HarnessFallback.RAN_OUT, results, clock)
        working += HarnessMessage("assistant", closing)
        return HarnessTurn(closing, working.toList(), results.toList(), HarnessFallback.RAN_OUT, corrected)
    }
}
