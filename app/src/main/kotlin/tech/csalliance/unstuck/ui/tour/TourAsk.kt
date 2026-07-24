package tech.csalliance.unstuck.ui.tour

import kotlinx.coroutines.withTimeoutOrNull
import tech.csalliance.unstuck.sync.ChatMessage

// "Ask a question" — the REAL assistant, tour-framed. Verbatim port of the
// web tour-ask.ts: ONE stateless round-trip through the production assistant
// pipeline (AppViewModel.tourAsk → the `assistant` edge fn). The tour framing
// is embedded in the user message; `context.tour = {step, title}` flags the
// server's PRODUCT-TOUR Q&A MODE (no tools, product-Q&A only — the guardrail
// lives on the SERVER). Any tool_calls in the reply are IGNORED; an error,
// timeout, offline, or tool-calls-only reply falls back to the canned TOUR_QA
// answer instantly (unlabelled — it just answers).

/** Wire cap: last 2 complete Q/A pairs + the current question (web parity). */
const val MAX_TOUR_HISTORY = 4
const val TOUR_ASK_TIMEOUT_MS = 20_000L

/** Marker the preamble carries so buildAskWire can tell whether the capped
 *  history still contains the tour framing. */
const val TOUR_CONTEXT_MARK = "I'm taking the Unstuck product tour"

data class TourAskResult(val text: String, val fromAssistant: Boolean)

/** The tour framing + the question, as ONE user message (no server change,
 *  the scope guardrail stays intact — this is an Unstuck question). Pure. */
fun buildTourPrompt(stepTitle: String, stepBody: String, question: String): String {
    val body = stepBody.trim()
    val summary = if (body.length > 220) body.take(217).trimEnd() + "…" else body
    return "($TOUR_CONTEXT_MARK, currently on the step \"$stepTitle\" — $summary " +
        "Please answer this question about Unstuck briefly, in 2–3 calm sentences, without using any tools.)\n\n" +
        "Question: ${question.trim()}"
}

/** Build the wire messages for one ask: capped history (always starting at a
 *  user turn) + the current question — re-embedding the tour framing whenever
 *  the cap dropped the original preambled turn. Pure. */
fun buildAskWire(
    stepTitle: String,
    stepBody: String,
    thread: List<ChatMessage>,
    question: String,
): List<ChatMessage> {
    val kept = thread.takeLast(MAX_TOUR_HISTORY).toMutableList()
    while (kept.isNotEmpty() && kept.first().role != "user") kept.removeAt(0)
    val framed = kept.any { it.role == "user" && (it.content ?: "").contains(TOUR_CONTEXT_MARK) }
    val content = if (framed) question.trim() else buildTourPrompt(stepTitle, stepBody, question)
    return kept + ChatMessage(role = "user", content = content)
}

/** Fallback logic, pure: a real text reply passes through; anything else
 *  (error/timeout/offline ⇒ null, tool-calls-only ⇒ blank) answers canned. */
fun resolveAskReply(content: String?, question: String): TourAskResult {
    val text = content?.trim()
    return if (!text.isNullOrEmpty()) TourAskResult(text, fromAssistant = true)
    else TourAskResult(answerFor(question), fromAssistant = false)
}

/** One bounded round-trip to the production assistant. Never throws. `ask` is
 *  AppViewModel.tourAsk (which attaches context.tour + ignores tool_calls). */
suspend fun sendTourAsk(
    wire: List<ChatMessage>,
    question: String,
    ask: suspend (List<ChatMessage>) -> String?,
): TourAskResult {
    val content = try {
        withTimeoutOrNull(TOUR_ASK_TIMEOUT_MS) { ask(wire) }
    } catch (_: Exception) {
        null
    }
    return resolveAskReply(content, question)
}
