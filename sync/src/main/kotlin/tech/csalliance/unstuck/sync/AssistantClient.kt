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

// AssistantClient — transport for the in-app agent. Calls the stateless
// `assistant` edge function (a qwen proxy that owns the system prompt + tool
// schemas) and returns the assistant message (text and/or tool_calls). The
// CLIENT (AppViewModel) holds the conversation, executes the tool_calls through
// its own offline-first methods, appends the results, and re-invokes until the
// assistant returns a plain text reply. Messages use the OpenAI chat shape.

/** One message in the OpenAI-style conversation (user | assistant | tool). */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
)

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
)

@Serializable
private data class AssistantRequest(
    val messages: List<ChatMessage>,
    val context: JsonElement,
)

@Serializable
private data class AssistantResponse(
    val assistant: AssistantReply? = null,
    val error: String? = null,
)

/** Outcome of one round-trip: the assistant turn, or an error code for the UI. */
sealed interface AssistantResult {
    data class Ok(val reply: AssistantReply) : AssistantResult
    /** "not_configured" | "upstream" | "network" | "unauthorized" | … */
    data class Err(val code: String) : AssistantResult
}

class AssistantClient(private val client: SupabaseClient) {

    suspend fun ask(messages: List<ChatMessage>, context: JsonElement): AssistantResult = runCatching {
        val resp: AssistantResponse = client.functions.invoke("assistant") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(AssistantRequest(messages, context))
        }.body()
        when {
            resp.error != null -> AssistantResult.Err(resp.error)
            resp.assistant != null -> AssistantResult.Ok(resp.assistant)
            else -> AssistantResult.Err("empty")
        }
    }.getOrElse { AssistantResult.Err("network") }
}
