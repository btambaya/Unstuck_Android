package tech.csalliance.unstuck.calls

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.core.logic.CallScript
import tech.csalliance.unstuck.core.logic.IncomingCallPayload
import tech.csalliance.unstuck.surface.NotificationChannels
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

    /**
     * The COLD-START shape these service tests describe: nothing has bound the
     * app-side seams, so `begin()` posts its launcher grace and leaves the call
     * STARTING (foreground notification up, `activeCallId` set) instead of dialling.
     *
     * This has to be asserted, not assumed. `CallVoiceService.deps` is a static, and
     * Robolectric reuses ONE sandbox — one class loader, one set of statics — for
     * every test class with the same @Config. `AppViewModel.init` binds itself into
     * that static and only `onCleared` unbinds it, which never runs in a unit test:
     * so every AppViewModel built by a sibling suite (AppViewModelTest,
     * AssistantMemoryHooksTest) leaves a DEAD view model bound here. Whenever
     * Gradle happened to run one of those classes first, `begin()` found deps,
     * dialled the corpse, got `isVoiceConfigured() == false`, and finished the call
     * on the spot — which calls stopForeground(true) (Robolectric clears
     * lastForegroundNotification with it) and nulls activeCallId. Both service tests
     * then failed together, in about half of all full-suite runs and never alone.
     *
     * bind() then unbind() of our own object is the public way to claim the seam and
     * put it back to null regardless of who held it. bind() also posts a re-dial to
     * the main looper, so drain it before the test runs.
     */
    private val noDeps = object : CallVoiceService.Deps {
        override fun isVoiceConfigured() = false
        override fun accessToken(): String? = null
        override val proxyUrl get() = ""
        override val model get() = ""
        override suspend fun voiceInstructions() = ""
        override fun voiceTools(): JsonArray = buildJsonArray { }
        override suspend fun runAppTool(name: String, args: JsonObject) = ""
    }

    @Before fun coldStart() {
        CallVoiceService.bind(noDeps)
        CallVoiceService.unbind(noDeps)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    /** …and leave the statics as we found them, for whoever runs next in this
     *  sandbox (the service tests below own `instance` / `activeCallId`). */
    @After fun releaseSeam() {
        CallVoiceService.bind(noDeps)
        CallVoiceService.unbind(noDeps)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

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

    /**
     * The in-call notification carries the End action — the only hang-up outside
     * the app — so it gets its own channel. On FOCUS_ONGOING ("Focus session",
     * LOW, "shows the running focus timer") a user who had silenced the focus
     * timer lost the End button while the microphone kept running, and a live
     * call read as a low-priority focus entry in the shade.
     */
    @Test fun `the in-call notification is on the call-ongoing channel, not the focus timer`() {
        val start = Intent(app, CallVoiceService::class.java)
        payload.toData().forEach { (k, v) -> start.putExtra("p.$k", v) }
        val controller = Robolectric.buildService(CallVoiceService::class.java, start).create()
        controller.startCommand(0, 0)
        val shadow = shadowOf(controller.get())
        val n = shadow.lastForegroundNotification
        assertNotNull("the 5 s startForeground contract is honoured unconditionally", n)
        assertEquals(NotificationChannels.CALL_ONGOING, n.channelId)
        assertEquals(CallVoiceService.NOTIF_ID, shadow.lastForegroundNotificationId)
        assertTrue("the End action is what the channel has to keep reachable", n.actions.any { it.title == "End" })
        // Created before the notification is posted, and silent: the phone is
        // already in a conversation.
        val ch = app.getSystemService(NotificationManager::class.java).getNotificationChannel(NotificationChannels.CALL_ONGOING)
        assertNotNull(ch)
        assertNull("silent", ch.sound)
        assertFalse(ch.shouldVibrate())
        controller.destroy()
    }

    /**
     * Sign-out with a call still up. The microphone foreground service, its
     * AudioRecord and the realtime socket all ran on the JWT captured at dial
     * time, so without this the previous account kept talking (and listening)
     * straight through the next sign-in. `signedOut` must:
     *  - drop the service and clear `activeCallId` (so the Talk gate and the
     *    ring's busy check both go free), and
     *  - report NOTHING — an outcome queued here would ride the NEXT account's
     *    token and come back 404 (the ring record is cleared by the same scrub).
     */
    @Test fun `sign-out abandons a live call, silently`() {
        CallOutcomeStore.clear(app)
        val start = Intent(app, CallVoiceService::class.java)
        payload.toData().forEach { (k, v) -> start.putExtra("p.$k", v) }
        val controller = Robolectric.buildService(CallVoiceService::class.java, start).create()
        controller.startCommand(0, 0)
        assertEquals(payload.callId, CallVoiceService.activeCallId)
        assertEquals("the observable form the in-call bar reads must agree", payload.callId, CallVoiceService.activeCall.value)

        CallVoiceService.signedOut(app)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertNull("a live call must not outlive the account", CallVoiceService.activeCallId)
        assertNull(CallVoiceService.activeCall.value)
        assertTrue("nothing may be reported under a dead JWT", CallOutcomeStore.load(app).isEmpty)
        // ENDED before the teardown, so the destroy that follows can't turn the
        // abandoned call into a reported `done`.
        controller.destroy()
        assertTrue(CallOutcomeStore.load(app).isEmpty)
    }
}
