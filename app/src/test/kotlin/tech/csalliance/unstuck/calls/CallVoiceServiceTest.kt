package tech.csalliance.unstuck.calls

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.core.logic.CallScript
import tech.csalliance.unstuck.core.logic.IncomingCallPayload
import tech.csalliance.unstuck.ui.assistant.CallMode

/**
 * The pure half of CallVoiceService (mirrors iOS RealtimeCallVoiceLauncherTests
 * `compose`): the session is the assistant's voice instructions + the call
 * script, the opening rides in the hidden primer, the tools are the call tools
 * only (+ snooze_call, always ours) in CallScript order, and the start intent
 * round-trips the payload.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CallVoiceServiceTest {

    private val app = ApplicationProvider.getApplicationContext<Context>()

    private val payload = IncomingCallPayload(
        callId = "11111111-1111-4111-8111-111111111111", label = "speak to James",
        notes = listOf("ask about the deck", "mention Friday"), taskId = "t1", taskName = "Board prep",
        name = "Ahmad Tambaya",
    )

    private fun tool(name: String) = buildJsonObject { put("type", "function"); put("name", name); put("description", "d") }
    private fun registry(vararg names: String): JsonArray = buildJsonArray { names.forEach { add(tool(it)) } }

    @Test fun `compose - base instructions plus the call script, the opening in the primer`() {
        val comp = CallVoiceService.compose(payload, "BASE VOICE INSTRUCTIONS", registry("create_task", "complete_task", "add_capture", "schedule_task", "start_focus", "update_call"), nowMs = 0L)
        assertTrue(comp.instructions.startsWith("BASE VOICE INSTRUCTIONS\n\nTHIS IS A PHONE CALL"))
        assertEquals(CallScript.opening(payload, nowMs = 0L), comp.opening)
        assertTrue(comp.opening.startsWith("Hi Ahmad — you asked me to ring so you'd speak to James."))
        assertTrue(comp.primer.contains("\"${comp.opening}\""))
        assertTrue(comp.primer.contains("YOU rang them"))
        assertEquals(CallScript.callTools(), comp.toolNames)
    }

    @Test fun `call tool schemas - filtered to the call tools in order, snooze always ours, update_call synthesised`() {
        val tools = CallVoiceService.callToolSchemas(registry("get_schedule", "complete_task", "start_focus", "add_capture", "schedule_task", "snooze_call"))
        val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("complete_task", "add_capture", "schedule_task", "start_focus", "update_call", "snooze_call"), names)
        val snooze = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == CallMode.SNOOZE_TOOL }.jsonObject
        assertEquals("ours, not the registry's stub", "10", snooze["parameters"]!!.jsonObject["properties"]!!.jsonObject["minutes"]!!.jsonObject["default"]!!.jsonPrimitive.content)
        assertEquals("Change this call's notes for later (replaces them, verbatim), or its label/time.",
            tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "update_call" }.jsonObject["description"]!!.jsonPrimitive.content)
        // A registry that lacks a call tool other than update_call just doesn't advertise it.
        assertEquals(listOf("complete_task", "update_call", "snooze_call"), CallVoiceService.callToolSchemas(registry("complete_task")).map { it.jsonObject["name"]!!.jsonPrimitive.content })
    }

    @Test fun `the start intent round-trips the payload, and a bare intent carries none`() {
        val i = Intent(app, CallVoiceService::class.java)
        payload.toData().forEach { (k, v) -> i.putExtra("p.$k", v) }
        assertEquals(payload, CallVoiceService.payloadFrom(i))
        assertNull(CallVoiceService.payloadFrom(Intent(app, CallVoiceService::class.java).setAction(CallVoiceService.ACTION_END)))
    }
}
