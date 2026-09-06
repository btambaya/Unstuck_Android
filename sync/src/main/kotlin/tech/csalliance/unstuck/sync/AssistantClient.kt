package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import tech.csalliance.unstuck.core.logic.AssistantWindowTurn
import tech.csalliance.unstuck.core.logic.Receipt

// AssistantClient — transport for the in-app agent. Calls the stateless
// `assistant` edge function (a qwen proxy that owns the system prompt + tool
// schemas) and returns the assistant message (text and/or tool_calls). The
// CLIENT (AppViewModel) holds the conversation, executes the tool_calls through
// its own offline-first methods, appends the results, and re-invokes until the
// assistant returns a plain text reply. Messages use the OpenAI chat shape.

/** One message in the OpenAI-style conversation (user | assistant | tool).
 *
 *  This type is BOTH the display/persistence record and the source of the wire
 *  payload. The trailing fields are CLIENT-ONLY: they drive the endless thread
 *  (day dividers, receipts, the local check-in) and are stripped in [ask] — the
 *  edge function forwards messages verbatim to an OpenAI-compatible upstream, so
 *  an unknown per-message field must never leak. Mirrors the web ChatMessage +
 *  its `wire` map in lib/assistant/client.ts. */
@Serializable
data class ChatMessage(
    override val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
    /** Stable client-side id so the thread keys on identity, not list position. */
    val id: String? = null,
    /** Epoch ms the turn landed — drives the day dividers. */
    val at: Long? = null,
    /** Deterministic action receipts attached to the closing assistant turn. */
    val receipts: List<Receipt>? = null,
    /** Locally-injected display turn (the daily check-in) — NEVER part of the
     *  model window; purely visual history. */
    override val local: Boolean = false,
) : AssistantWindowTurn

/** A tool call the model wants run. id/type are required so they always
 *  round-trip back to qwen on the next turn (kotlinx omits default-valued
 *  fields, so we give these no defaults). */
@Serializable
data class ToolCall(
    val id: String,
    val type: String,
    val function: ToolFunction,
)

@Serializable
data class ToolFunction(
    val name: String,
    val arguments: String, // JSON-encoded args object
)

@Serializable
data class AssistantReply(
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    /** The upstream's finish reason ("stop" | "length" | "tool_calls" | …), lifted
     *  from the edge fn's TOP-LEVEL `finish_reason` — it is NOT part of the nested
     *  `assistant` object's wire shape (@Transient), so a persisted/decoded reply
     *  carries null. "length" ⇒ the harness sends the cut-off hint instead of
     *  inferring truncation from bad tool JSON (iOS AssistantClient parity). */
    @kotlinx.serialization.Transient val finishReason: String? = null,
)

/** The EXACT shape the model expects — the client-only fields on [ChatMessage]
 *  (id / at / receipts / local) are not part of it. */
@Serializable
internal data class WireMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
)

internal fun ChatMessage.toWire(): WireMessage =
    WireMessage(role = role, content = content, toolCalls = toolCalls, toolCallId = toolCallId, name = name)

@Serializable
private data class AssistantRequest(
    val messages: List<WireMessage>,
    val context: JsonElement,
)

/** The edge fn body: `{ assistant, finish_reason, usage }` or `{ error }`. */
@Serializable
internal data class AssistantResponse(
    val assistant: AssistantReply? = null,
    val error: String? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
) {
    /** The reply with the top-level finish reason attached (null when absent). */
    fun toResult(): AssistantResult = when {
        error != null -> AssistantResult.Err(error)
        assistant != null -> AssistantResult.Ok(assistant.copy(finishReason = finishReason))
        else -> AssistantResult.Err("empty")
    }
}

/** Outcome of one round-trip: the assistant turn, or an error code for the UI. */
sealed interface AssistantResult {
    data class Ok(val reply: AssistantReply) : AssistantResult
    /** "not_configured" | "upstream" | "network" | "unauthorized" | … */
    data class Err(val code: String) : AssistantResult
}

class AssistantClient(private val client: SupabaseClient) {

    suspend fun ask(messages: List<ChatMessage>, context: JsonElement): AssistantResult {
        // Narrow to the wire shape: the display-only fields (id/at/receipts/local)
        // are ours, and the edge function forwards messages verbatim upstream.
        val wire = messages.map { it.toWire() }
        // One retry on a thrown error (transient network / cold-start timeout).
        var last: Throwable? = null
        repeat(2) { attempt ->
            val r = runCatching {
                val resp: AssistantResponse = client.functions.invoke("assistant") {
                    method = HttpMethod.Post
                    contentType(ContentType.Application.Json)
                    setBody(AssistantRequest(wire, context))
                }.body()
                resp.toResult()
            }
            r.getOrNull()?.let { return it }
            last = r.exceptionOrNull()
            if (attempt == 0) kotlinx.coroutines.delay(800)
        }
        return AssistantResult.Err(if (last is kotlinx.coroutines.TimeoutCancellationException) "timeout" else "network")
    }
}
