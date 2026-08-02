package tech.csalliance.unstuck.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.Receipt
import tech.csalliance.unstuck.core.logic.ReceiptIcon

// ChatMessage is BOTH the persisted display record and the source of the wire
// payload. The client-only fields (id / at / receipts / local) drive the endless
// thread and MUST NOT reach the model — the edge function forwards messages
// verbatim to an OpenAI-compatible upstream. Mirrors the web `wire` map.
class AssistantWireTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `the wire shape drops every client-only field`() {
        val m = ChatMessage(
            role = "assistant", content = "Done.",
            id = "local-uuid", at = 1_754_000_000_000L, local = true,
            receipts = listOf(Receipt(ReceiptIcon.PLUS, "Created x")),
        )
        val encoded = Json.encodeToString(m.toWire())
        assertEquals("""{"role":"assistant","content":"Done."}""", encoded)
        listOf("\"id\"", "\"at\"", "\"receipts\"", "\"local\"").forEach {
            assertTrue("$it leaked to the wire", !encoded.contains(it))
        }
    }

    @Test fun `the wire shape KEEPS everything the model actually needs`() {
        val call = ToolCall("c1", "function", ToolFunction("create_task", """{"name":"x"}"""))
        val assistant = Json.encodeToString(ChatMessage(role = "assistant", toolCalls = listOf(call), id = "x").toWire())
        assertTrue(assistant.contains("\"tool_calls\""))
        assertTrue(assistant.contains("create_task"))
        val tool = Json.encodeToString(
            ChatMessage(role = "tool", content = "ok", toolCallId = "c1", name = "create_task", at = 1L).toWire(),
        )
        assertEquals("""{"role":"tool","content":"ok","tool_call_id":"c1","name":"create_task"}""", tool)
    }

    @Test fun `persisted history round-trips the display fields, receipts included`() {
        val h = listOf(
            ChatMessage(role = "user", content = "hi", id = "u1", at = 5L),
            ChatMessage(
                role = "assistant", content = "Done.", id = "a1", at = 6L, local = true,
                receipts = listOf(Receipt(ReceiptIcon.CHECK, "Completed “Report”")),
            ),
        )
        val back = json.decodeFromString<List<ChatMessage>>(Json.encodeToString(h))
        assertEquals(h, back)
        assertEquals("Completed “Report”", back[1].receipts!![0].label)
        assertTrue(back[1].local)
    }

    @Test fun `a legacy persisted turn without the new fields still decodes`() {
        val m = json.decodeFromString<ChatMessage>("""{"role":"user","content":"old"}""")
        assertEquals("old", m.content)
        assertEquals(null, m.id)
        assertEquals(null, m.at)
        assertTrue(!m.local)
    }
}
