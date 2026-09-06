package tech.csalliance.unstuck.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The edge fn's TOP-LEVEL `finish_reason` rides on AssistantReply.finishReason
 * (iOS AssistantClient parity): "length" ⇒ the harness sends the cut-off hint
 * instead of inferring truncation from bad tool JSON. It is NOT part of the
 * nested `assistant` object's wire shape, so a persisted reply carries null.
 */
class AssistantFinishReasonTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `the top-level finish_reason is lifted onto the reply`() {
        val resp = json.decodeFromString<AssistantResponse>(
            """{"assistant":{"content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"create_task","arguments":"{\"name\":\"x"}}]},"finish_reason":"length","usage":{"total_tokens":10}}""",
        )
        val r = resp.toResult() as AssistantResult.Ok
        assertEquals("length", r.reply.finishReason)
        assertEquals("create_task", r.reply.toolCalls!![0].function.name)
    }

    @Test fun `a reply without finish_reason carries null, an error body is an Err, an empty body is empty`() {
        val ok = json.decodeFromString<AssistantResponse>("""{"assistant":{"content":"hi"}}""").toResult() as AssistantResult.Ok
        assertNull(ok.reply.finishReason)
        assertEquals(AssistantResult.Err("upstream"), json.decodeFromString<AssistantResponse>("""{"error":"upstream"}""").toResult())
        assertEquals(AssistantResult.Err("empty"), json.decodeFromString<AssistantResponse>("""{}""").toResult())
    }

    @Test fun `finishReason never leaks into the persisted or wire shape`() {
        val encoded = Json.encodeToString(AssistantReply(content = "x", finishReason = "stop"))
        assertFalse(encoded.contains("finish"))
        assertTrue(encoded.contains("\"content\":\"x\""))
    }
}
