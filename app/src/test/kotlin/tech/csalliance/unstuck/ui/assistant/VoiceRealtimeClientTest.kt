package tech.csalliance.unstuck.ui.assistant

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import tech.csalliance.unstuck.SettingsStore
import tech.csalliance.unstuck.core.logic.BargeInController
import tech.csalliance.unstuck.core.logic.BargeInProfile
import tech.csalliance.unstuck.core.logic.VoiceRoute
import java.time.Duration

/**
 * Robolectric guardrails for the realtime voice client's LIFECYCLE against a
 * fake audio engine and a fake socket factory (no AudioRecord, no network):
 *  - a synchronous capture failure (mic held elsewhere) must never dial — the
 *    proxy session would otherwise open as a "Listening…" zombie nobody can close;
 *  - a socket that opens AFTER stop() is closed on the spot, not greeted;
 *  - hold-to-talk commits only after the capture thread has drained the frame
 *    read during the press, and a too-short press's "buffer too small" is not fatal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VoiceRealtimeClientTest {

    private val app = ApplicationProvider.getApplicationContext<Context>()

    /** No AudioRecord/AudioTrack: records what the client asked for. */
    private class FakeEngine(
        ctx: Context,
        private val failCaptureSynchronously: Boolean = false,
        /** The route communication mode picks when capture starts (a headset
         *  already plugged in); null = the loudspeaker throughout. */
        private val routeOnCapture: VoiceRoute? = null,
    ) : VoiceAudioEngine(ctx) {
        override var route: VoiceRoute = VoiceRoute.SPEAKER
        var captureStarts = 0
        var playbackStarts = 0
        var shutdowns = 0
        val gate = mutableListOf<Boolean>()
        val drains = mutableListOf<() -> Unit>()
        var onFrame: ((ByteArray) -> Unit)? = null

        override fun startCapture(onFrame: (ByteArray) -> Unit) {
            captureStarts++
            this.onFrame = onFrame
            routeOnCapture?.let { route = it }
            if (failCaptureSynchronously) onCaptureError?.invoke()
        }
        override fun startPlayback() { playbackStarts++ }
        override fun shutdown() { shutdowns++ }
        override fun forceGate(open: Boolean) { gate += open }
        override fun afterCaptureDrain(block: () -> Unit) { drains += block }
        override fun stopCapture() {}
        override fun stopPlayback() {}
        override fun duck() {}
        override fun restore() {}
        var flushes = 0
        override fun flushPlayback() { flushes++ }
        override fun enqueue(pcm: ByteArray) {}
        override fun playbackQueued(): Boolean = false
        override fun outputBusy(): Boolean = false
    }

    private class FakeSocket(private val req: Request) : WebSocket {
        // Tool outputs arrive from the client's IO coroutine while the test
        // thread polls — a synchronized list, read through snapshots.
        private val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        val sent: List<String> get() = synchronized(log) { log.toList() }
        var closed = false
        var cancelled = false
        override fun request(): Request = req
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean { log += text; return true }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean { closed = true; return true }
        override fun cancel() { cancelled = true }
        fun types(): List<String> = sent.map { Json.parseToJsonElement(it).jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "?" }
        /** Hidden user items carrying the integrity corrective. */
        fun correctives(): Int = sent.count { raw ->
            val ev = Json.parseToJsonElement(raw).jsonObject
            if (ev["type"]?.jsonPrimitive?.contentOrNull != "conversation.item.create") return@count false
            val item = ev["item"]?.jsonObject ?: return@count false
            item["role"]?.jsonPrimitive?.contentOrNull == "user" &&
                item["content"]?.jsonArray?.any { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull == VoiceIntegrityGuard.correctiveText } == true
        }
        fun toolOutputs(): Int = sent.count { raw ->
            Json.parseToJsonElement(raw).jsonObject["item"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull == "function_call_output"
        }
    }

    private class FakeFactory : WebSocket.Factory {
        var socket: FakeSocket? = null
        var listener: WebSocketListener? = null
        var dials = 0
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            dials++
            this.listener = listener
            return FakeSocket(request).also { socket = it }
        }
        fun open() {
            val s = socket!!
            listener!!.onOpen(s, Response.Builder().request(s.request()).protocol(Protocol.HTTP_1_1).code(101).message("ok").build())
        }
        fun message(json: String) { listener!!.onMessage(socket!!, json) }
    }

    private fun client(
        engine: FakeEngine, factory: FakeFactory, states: MutableList<VoiceState>,
        errors: MutableList<String> = mutableListOf(),
        captions: MutableList<Triple<String, String, Boolean>> = mutableListOf(),
        runTool: suspend (String, kotlinx.serialization.json.JsonObject) -> String = { _, _ -> "ok" },
    ) = VoiceRealtimeClient(
        proxyUrl = "wss://voice.example/ws", token = "t", model = "m", instructions = "i",
        tools = JsonArray(emptyList()), opening = "hello", audio = engine,
        runTool = runTool,
        onState = { states += it }, onCaption = { r, t, d -> captions += Triple(r, t, d) }, onError = { errors += it },
        socketFactory = factory,
    )

    // ── voice integrity guard: wire-level helpers ──

    private fun FakeFactory.created(id: String) = message("""{"type":"response.created","response":{"id":"$id"}}""")
    private fun FakeFactory.transcript(id: String, text: String) =
        message("""{"type":"response.audio_transcript.delta","response_id":"$id","delta":${Json.encodeToString(kotlinx.serialization.serializer<String>(), text)}}""")
    private fun FakeFactory.done(id: String, status: String = "completed") =
        message("""{"type":"response.done","response":{"id":"$id","status":"$status"}}""")
    private fun FakeFactory.toolCall(name: String, callId: String) =
        message("""{"type":"response.function_call_arguments.done","name":"$name","call_id":"$callId","arguments":"{}"}""")

    /** The tool runs on Dispatchers.IO: wait for its output only. The
     *  continuation that reads it is asked by the barge-in controller once
     *  every output is out AND the reply that carried the call is done — never
     *  before (Zubair's morning call, 2026-09-24) — so a test sends that done
     *  and then [awaitContinuation]. */
    private fun awaitToolOutput(s: FakeSocket, expected: Int) {
        var waited = 0
        while (s.toolOutputs() < expected && waited < 5_000) { Thread.sleep(10); waited += 10 }
        assertEquals("tool output sent", expected, s.toolOutputs())
    }

    /** Let the continuation's response.create land: ToolCallFinished is
     *  dispatched from the IO coroutine a beat AFTER the output reaches the
     *  socket and arms the controller's 120 ms tick on the (paused) main
     *  looper, so a single `idleFor` can run before it is posted. Pump until
     *  a create follows the last output (bounded). */
    private fun awaitContinuation(s: FakeSocket) {
        var waited = 0
        fun landed(): Boolean {
            val t = s.types()
            val lastOutput = s.sent.indexOfLast { raw ->
                Json.parseToJsonElement(raw).jsonObject["item"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull == "function_call_output"
            }
            return t.withIndex().any { (i, ty) -> i > lastOutput && ty == "response.create" }
        }
        while (!landed() && waited < 5_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(VoiceRealtimeClient.CONTINUE_DELAY_MS + 50))
            Thread.sleep(10); waited += 10
        }
        assertTrue("a response.create after the last tool output", landed())
    }

    private fun openSession(runTool: suspend (String, kotlinx.serialization.json.JsonObject) -> String = { _, _ -> "ok" }): Pair<FakeFactory, FakeSocket> {
        val engine = FakeEngine(app)
        val factory = FakeFactory()
        val c = client(engine, factory, mutableListOf(), runTool = runTool)
        c.start()
        factory.open()
        return factory to factory.socket!!
    }

    @Test
    fun `guard - a spoken claim with no tool call gets exactly one hidden corrective`() {
        val (factory, s) = openSession()
        factory.created("r1")
        factory.transcript("r1", "I've added the dentist for you.")
        // A second claim inside the SAME response is still one response → one corrective at most.
        factory.transcript("r1", " And I've scheduled it for Tuesday.")
        assertEquals("nothing injected mid-utterance", 0, s.correctives())
        factory.done("r1")
        assertEquals(1, s.correctives())
        // The corrective is a hidden user item immediately followed by a
        // response.create that FORCES a tool call: spoken, the corrective was
        // answered with another promise (2026-09-20 15:37).
        val types = s.types()
        val idx = types.lastIndexOf("conversation.item.create")
        assertEquals("response.create", types[idx + 1])
        val forced = Json.parseToJsonElement(s.sent[idx + 1]).jsonObject["response"]?.jsonObject
        assertEquals("required", forced?.get("tool_choice")?.jsonPrimitive?.contentOrNull)
        // The correction's own follow-up is NEVER scored — no loop.
        factory.created("r2")
        factory.transcript("r2", "Added it now.")
        factory.done("r2")
        assertEquals("no second corrective for the follow-up", 1, s.correctives())
    }

    @Test
    fun `guard - a claim backed by a succeeded write tool is not corrected`() {
        val (factory, s) = openSession { name, _ -> if (name == "create_task") "ok: created task id=1 name=\"x\"" else "ok" }
        factory.created("r1")
        factory.toolCall("create_task", "c1")
        awaitToolOutput(s, 1)
        factory.transcript("r1", "Done — added it.")
        factory.done("r1")
        awaitContinuation(s)
        assertEquals("one response.create after the tool output", "response.create", s.types().last())
        assertEquals(0, s.correctives())
        // The reply AFTER the tool result (the confirmation) is tool-backed too.
        factory.created("r2")
        factory.transcript("r2", "Booked — Thursday at two.")
        factory.done("r2")
        assertEquals(0, s.correctives())
        // …but the one after that is a fresh window: a bare claim is caught again.
        factory.created("r3")
        factory.transcript("r3", "I've moved it to Friday.")
        factory.done("r3")
        assertEquals(1, s.correctives())
    }

    @Test
    fun `guard - a read-only tool does not make a claim tool-backed, and a failed write does not back the next reply`() {
        val (factory, s) = openSession { name, _ -> if (name == "delete_task") "error: task not found" else "ok:\nMonday" }
        factory.created("r1")
        factory.toolCall("get_schedule", "c1")
        awaitToolOutput(s, 1)
        factory.transcript("r1", "Done — moved it.")
        factory.done("r1")
        awaitContinuation(s)
        assertEquals("get_schedule + a claim is still a fabrication", 1, s.correctives())
        // It rides on the continuation: after the output, with ONE forced create.
        val t = s.types()
        val corrective = s.sent.indexOfFirst { it.contains(VoiceIntegrityGuard.correctiveText.take(40)) }
        val output = s.sent.indexOfFirst { it.contains("function_call_output") }
        assertTrue("corrective after the output", corrective > output)
        assertEquals("response.create", t[corrective + 1])
        assertEquals(1, t.drop(output).count { it == "response.create" })
        // Follow-up of the corrective: not scored.
        factory.created("r2"); factory.transcript("r2", "Moved it now."); factory.done("r2")
        assertEquals(1, s.correctives())
        // A write tool that FAILED does not back the reply after it.
        factory.created("r3")
        factory.toolCall("delete_task", "c2")
        awaitToolOutput(s, 2)
        factory.done("r3")
        awaitContinuation(s)
        factory.created("r4"); factory.transcript("r4", "I've deleted it."); factory.done("r4")
        assertEquals(2, s.correctives())
    }

    @Test
    fun `guard - capped at three per session, and a cancelled response is never scored`() {
        val (factory, s) = openSession()
        var n = 0
        repeat(3) {
            factory.created("r${++n}"); factory.transcript("r$n", "I've added it."); factory.done("r$n")
            factory.created("r${++n}"); factory.transcript("r$n", "Added it now."); factory.done("r$n")   // follow-up
        }
        assertEquals(3, s.correctives())
        factory.created("r${++n}"); factory.transcript("r$n", "I've saved it again."); factory.done("r$n")
        assertEquals("capped per session", 3, s.correctives())
    }

    @Test
    fun `guard - an interrupted reply is not a claim`() {
        val (factory, s) = openSession()
        val before = s.correctives()
        factory.created("r1")
        factory.transcript("r1", "I've added the dentist")
        factory.done("r1", status = "cancelled")
        assertEquals(before, s.correctives())
        // And a fresh, honest answer afterwards is left alone.
        factory.created("r2"); factory.transcript("r2", "Your dentist is Friday at ten."); factory.done("r2")
        assertEquals(before, s.correctives())
    }

    /** Cross-platform rule (2026-09-24): a spoken review's recap ends only when
     *  the app answers a real user turn — never on a raw speech_started. The
     *  loudspeaker's echo of the review fires one even after the reply left the
     *  air, and ending the recap there turned the review's own "You finished …"
     *  into a false claim and a forced tool call. */
    @Test
    fun `guard - a speech start does not end the review's recap, the user's answered turn does`() {
        val (factory, s) = openSession { name, _ ->
            if (name == "get_period_review") "ok: review of last week (Mon 14 Sep – Sun 20 Sep).\nDone: 1 task — \"Draft chapter 3\"." else "ok"
        }
        val review = "You finished \"Draft chapter 3\" and skipped \"Stretch\" once."
        factory.created("r1")
        factory.toolCall("get_period_review", "c1")
        awaitToolOutput(s, 1)
        factory.done("r1")
        awaitContinuation(s)
        // Nothing on air (no audio was queued): a speech start here is what the
        // review's echo tail looks like on a loudspeaker.
        factory.message("""{"type":"input_audio_buffer.speech_started","item_id":"echo1"}""")
        factory.created("r2"); factory.transcript("r2", review); factory.done("r2")
        assertEquals("the spoken review is not a claim", 0, s.correctives())
        // The user's next turn — words the controller judged theirs — ends it.
        factory.message("""{"type":"input_audio_buffer.speech_started","item_id":"u1"}""")
        factory.message("""{"type":"input_audio_buffer.speech_stopped"}""")
        factory.message("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"u1","transcript":"thanks, what's next today"}""")
        factory.created("r3"); factory.transcript("r3", review); factory.done("r3")
        assertEquals("after the user's turn the same words are a claim again", 1, s.correctives())
    }

    @Test
    fun `tool calls are deduped across both event shapes and continue with ONE response create`() {
        var runs = 0
        val (factory, s) = openSession { _, _ -> runs++; "ok" }
        factory.created("r1")
        factory.toolCall("create_task", "c1")
        factory.message("""{"type":"response.output_item.done","item":{"type":"function_call","name":"create_task","call_id":"c1","arguments":"{}"}}""")
        awaitToolOutput(s, 1)
        factory.done("r1")
        awaitContinuation(s)
        assertEquals(1, runs)
        // (The greeting's done also removed the opening primer: an item.delete in between.)
        assertEquals("response.create", s.types().last())
        assertEquals("one continuation", 1, s.types().drop(s.sent.indexOfFirst { it.contains("function_call_output") }).count { it == "response.create" })
        // A function call delivered ONLY on output_item.done still executes.
        factory.message("""{"type":"response.output_item.done","item":{"type":"function_call","name":"complete_task","call_id":"c2","arguments":"{}"}}""")
        awaitToolOutput(s, 2)
        assertEquals(2, runs)
    }

    /** Zubair's iOS morning call, 2026-09-24 07:02:44–53 (session 1cbfac75):
     *  his turn was still pending at the done of the reply that carried
     *  set_task_recurrence, and was asked for right there — 70 ms before the
     *  tool's ok went out. That reply read the call as failed ("I tried to
     *  cancel the repeat, but it didn't go through") and the continuation
     *  collided with it ("already has an active response"). Android ran the
     *  same controller: nothing may be asked while a tool runs, and the ONE
     *  reply after the output answers the turn too. */
    @Test
    fun `a turn pending at the done of a reply carrying a tool call is asked for only after the tool's output`() {
        val gate = CompletableDeferred<Unit>()
        val (factory, s) = openSession { name, _ ->
            gate.await()
            if (name == "set_task_recurrence") "ok: \"Office Focus\" no longer repeats (future occurrences removed)" else "ok"
        }
        factory.created("r0"); factory.done("r0")
        idle(2_000)
        factory.speechStarted("u1"); factory.speechStopped()
        factory.completed("u1", "No, just cancel the recurring.")
        idle(BargeInController.TURN_HOLD_MS + 20)
        assertEquals("the turn is asked for", 2, s.creates())
        // A segment that starts after that create, with nothing on air, keeps
        // the turn pending past it (the controller's "they go on talking").
        idle(50)
        factory.speechStarted("n")
        factory.created("r1")
        factory.speechStopped()
        factory.completed("n", "")
        factory.transcript("r1", "We'll stop it repeating. One moment.")
        factory.toolCall("set_task_recurrence", "c1")
        factory.done("r1")
        idle(3_000)
        assertEquals("nothing asked while the tool runs", 2, s.creates())
        gate.complete(Unit)
        awaitToolOutput(s, 1)
        awaitContinuation(s)
        assertEquals("one reply reads the ok and answers the turn", 3, s.creates())
        factory.created("r2")
        idle(5_000)
        assertEquals("never a second ask", 3, s.creates())
    }

    /** The integrity corrective at the done of a reply that also carried a
     *  read: sent there, its forced create beat the read's output exactly as
     *  the turn's did. It rides on the continuation instead. */
    @Test
    fun `guard - a corrective owed while the reply's read tool still runs goes out after its output`() {
        val gate = CompletableDeferred<Unit>()
        val (factory, s) = openSession { _, _ -> gate.await(); "ok:\nMonday" }
        factory.created("r1")
        factory.toolCall("get_schedule", "c1")
        factory.transcript("r1", "Done — moved it.")
        factory.done("r1")
        idle(1_000)
        assertEquals("held: nothing before the output", 0, s.correctives())
        assertEquals("only the opening create", 1, s.creates())
        gate.complete(Unit)
        awaitToolOutput(s, 1)
        awaitContinuation(s)
        assertEquals(1, s.correctives())
        val output = s.sent.indexOfFirst { it.contains("function_call_output") }
        val corrective = s.sent.indexOfFirst { it.contains(VoiceIntegrityGuard.correctiveText.take(40)) }
        assertTrue("after the output", corrective > output)
        val forced = Json.parseToJsonElement(s.sent[corrective + 1]).jsonObject
        assertEquals("response.create", forced["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("required", forced["response"]?.jsonObject?.get("tool_choice")?.jsonPrimitive?.contentOrNull)
        assertEquals("one create after the output", 2, s.creates())
    }

    // ── pure guard (mirrors the iOS VoiceIntegrityGuard test) ──

    @Test
    fun `pure guard corrects a claim without a tool once and never loops`() {
        val g = VoiceIntegrityGuard()
        g.responseCreated()
        g.transcriptDelta("I've added the dentist for you.")
        assertTrue(g.shouldCorrect())
        assertEquals(2, g.correctionsLeft)
        g.responseCreated()
        g.transcriptDelta("I've added it now.")
        assertFalse("the correction's own follow-up is never scored", g.shouldCorrect())
        g.toolFinished("create_task", "ok: created task id=1 name=\"x\"")
        g.responseCreated()
        g.transcriptDelta("Done — added it.")
        assertFalse("reply right after a real write tool is tool-backed", g.shouldCorrect())
        g.toolFinished("get_schedule", "ok:\nMonday")
        g.responseCreated()
        g.toolDispatched("get_schedule")
        g.transcriptDelta("Done — moved it.")
        assertTrue("a read-only tool does not make a reply tool-backed", g.shouldCorrect())
        g.responseCreated(); assertFalse(g.shouldCorrect())
        g.responseCreated(); g.transcriptDelta("Done — saved."); assertTrue(g.shouldCorrect())
        g.responseCreated(); assertFalse(g.shouldCorrect())
        assertEquals(0, g.correctionsLeft)
        g.responseCreated(); g.transcriptDelta("Done — saved again."); assertFalse("capped per session", g.shouldCorrect())
        // Barge-in wipes the transcript so a cut-off reply can't be scored.
        g.responseCreated(); g.transcriptDelta("I've added"); g.bargeIn(); assertEquals("", g.transcript)
        assertTrue(VoiceIntegrityGuard.correctiveText.startsWith("(integrity check from the app, not the user:"))
    }

    @Test
    fun `synchronous capture failure stops the client before it dials`() {
        val engine = FakeEngine(app, failCaptureSynchronously = true)
        val factory = FakeFactory()
        val states = mutableListOf<VoiceState>()
        lateinit var c: VoiceRealtimeClient
        // What the screen does in onCaptureError.
        engine.onCaptureError = { c.stop() }
        c = client(engine, factory, states)
        c.start()
        assertEquals(1, engine.captureStarts)
        assertEquals("never dialled after stop()", 0, factory.dials)
        assertTrue(c.isStopped)
        assertFalse(c.isOpen)
        assertEquals(listOf(VoiceState.CONNECTING, VoiceState.CLOSED), states)
    }

    @Test
    fun `a socket that opens after stop() is closed, not greeted`() {
        val engine = FakeEngine(app)
        val factory = FakeFactory()
        val states = mutableListOf<VoiceState>()
        val c = client(engine, factory, states)
        c.start()
        assertEquals(1, factory.dials)
        c.stop() // e.g. focus lost / user left while the handshake was in flight
        factory.open()
        val s = factory.socket!!
        assertTrue("late socket closed by onOpen", s.closed)
        assertTrue("no session.update / primer on a dead session", s.sent.isEmpty())
        assertEquals(0, engine.playbackStarts)
        assertFalse(states.contains(VoiceState.LISTENING))
        assertEquals(VoiceState.CLOSED, states.last())
        // The socket's own close/failure callbacks don't paint over CLOSED.
        factory.listener!!.onClosed(s, 1000, "bye")
        factory.listener!!.onFailure(s, RuntimeException("x"), null)
        assertEquals(VoiceState.CLOSED, states.last())
    }

    @Test
    fun `open session greets and reports listening`() {
        val engine = FakeEngine(app)
        val factory = FakeFactory()
        val states = mutableListOf<VoiceState>()
        val c = client(engine, factory, states)
        c.start()
        factory.open()
        assertTrue(c.isOpen)
        assertEquals(listOf("session.update", "conversation.item.create", "response.create"), factory.socket!!.types())
        assertEquals(1, engine.playbackStarts)
        assertEquals(VoiceState.LISTENING, states.last())
    }

    @Test
    fun `hold-to-talk commits only after the capture drain, and an empty-buffer error is not fatal`() {
        SettingsStore(app).setVoiceHoldToTalk(true)
        val engine = FakeEngine(app)
        assertTrue(engine.holdToTalkPref)
        val factory = FakeFactory()
        val states = mutableListOf<VoiceState>()
        val errors = mutableListOf<String>()
        val c = client(engine, factory, states, errors)
        c.start()
        factory.open()
        assertTrue(c.holdToTalk)
        val s = factory.socket!!
        val before = s.sent.size
        c.pttDown()
        assertEquals(listOf(true), engine.gate)
        c.pttUp()
        assertEquals(listOf(true, false), engine.gate)
        assertEquals(VoiceState.THINKING, states.last())
        // Nothing committed yet: the frame read during the press hasn't been appended.
        assertEquals("commit deferred to the capture drain", before, s.sent.size)
        assertEquals(1, engine.drains.size)
        // Capture thread appends the last frame, then runs the drain.
        engine.onFrame!!.invoke(ByteArray(640))
        engine.drains.single().invoke()
        assertEquals(listOf("input_audio_buffer.append", "input_audio_buffer.commit", "response.create"), s.types().drop(before))

        // Too-short press: the server rejects the commit. Hold mode stays usable.
        factory.message("""{"type":"error","error":{"message":"input_audio_buffer commit failed: buffer too small"}}""")
        assertTrue(errors.isEmpty())
        assertFalse(states.contains(VoiceState.ERROR))
        assertEquals(VoiceState.LISTENING, states.last())
        assertTrue(c.isOpen)
        // Whereas a real error still surfaces — and leaves the socket open (the screen keeps the orb).
        // In plain words, never the provider's text (iOS build 78).
        factory.message("""{"type":"error","error":{"message":"rate limited"}}""")
        assertEquals(listOf("The assistant is busy right now — give it a minute and ask again."), errors)
        assertEquals(VoiceState.ERROR, states.last())
        assertTrue(c.isOpen)
    }

    // ── the CLIENT owns turn-taking (iOS builds 66–70, ported 2026-09-20):
    // the wiring between the socket events and BargeIn.kt, whose decisions
    // are pinned in :core BargeInControllerTest ──

    private fun str(s: String) = Json.encodeToString(kotlinx.serialization.serializer<String>(), s)
    private fun FakeFactory.speechStarted(item: String) = message("""{"type":"input_audio_buffer.speech_started","item_id":"$item"}""")
    private fun FakeFactory.speechStopped() = message("""{"type":"input_audio_buffer.speech_stopped"}""")
    private fun FakeFactory.completed(item: String, text: String) =
        message("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"$item","transcript":${str(text)}}""")
    /** DashScope's live guess: `text` stays empty until the segment ends, `stash` is the cumulative guess. */
    private fun FakeFactory.guess(item: String, stash: String) =
        message("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"$item","text":"","stash":${str(stash)}}""")
    private fun FakeFactory.audio(id: String) = message("""{"type":"response.audio.delta","response_id":"$id","delta":"AAAAAA=="}""")
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun FakeSocket.creates() = types().count { it == "response.create" }
    private fun FakeSocket.deletes(): List<String> = sent.mapNotNull { raw ->
        val ev = Json.parseToJsonElement(raw).jsonObject
        if (ev["type"]?.jsonPrimitive?.contentOrNull != "conversation.item.delete") null else ev["item_id"]?.jsonPrimitive?.contentOrNull
    }
    private val capacityError = """{"type":"error","error":{"code":"COMMON_ERROR","message":"thread pool exausted max_workers 100"}}"""

    private fun session(
        states: MutableList<VoiceState> = mutableListOf(), errors: MutableList<String> = mutableListOf(),
        captions: MutableList<Triple<String, String, Boolean>> = mutableListOf(), hook: ((String?) -> Unit)? = null,
    ): Triple<VoiceRealtimeClient, FakeFactory, FakeEngine> {
        val engine = FakeEngine(app)
        val factory = FakeFactory()
        val c = client(engine, factory, states, errors, captions)
        c.onTransportEnded = hook
        c.start()
        factory.open()
        return Triple(c, factory, engine)
    }

    @Test
    fun `session update turns the server's interrupt and auto-reply off`() {
        val (_, factory, _) = session()
        val td = Json.parseToJsonElement(factory.socket!!.sent.first()).jsonObject["session"]!!.jsonObject["turn_detection"]!!.jsonObject
        assertEquals("server_vad", td["type"]!!.jsonPrimitive.content)
        assertFalse("the server never cuts a reply on its own VAD", td["interrupt_response"]!!.jsonPrimitive.boolean)
        assertFalse("and never answers by itself", td["create_response"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `a real turn is the user's caption and is asked for after the hold, an echo is deleted and never shown`() {
        val captions = mutableListOf<Triple<String, String, Boolean>>()
        val (_, factory, _) = session(captions = captions)
        val s = factory.socket!!
        // The greeting.
        factory.created("r1"); factory.audio("r1"); factory.transcript("r1", "Taxi's at quarter to eight, after the gym.")
        assertEquals(1, s.creates())
        // Its echo, back through the mic while it plays: out of the conversation,
        // nothing asked, nothing shown — the delete HELD until the next segment.
        factory.speechStarted("echo"); factory.speechStopped()
        factory.completed("echo", "taxi's at quarter to eight after the gym")
        assertTrue("never the user's line", captions.none { it.first == "user" && it.second.isNotEmpty() })
        assertEquals("nothing asked", 1, s.creates())
        assertFalse("held", "echo" in s.deletes())
        factory.done("r1")   // (also deletes the opening primer — its own item, not the echo's)
        idle(VoiceAudioEngine.OUTPUT_TAIL_MS)
        // The user's question, well after the reply: captioned, asked for
        // once 500 ms of quiet have passed — not before.
        idle(2_000)
        factory.speechStarted("q")
        assertTrue("the held delete goes out when the next segment starts", "echo" in s.deletes())
        factory.speechStopped()
        factory.completed("q", "what have I got left today")
        assertEquals(listOf(Triple("user", "what have I got left today", true)), captions.filter { it.first == "user" && it.second.isNotEmpty() })
        assertEquals("the hold is not up", 1, s.creates())
        idle(BargeInController.TURN_HOLD_MS + 20)
        assertEquals("asked once the hold elapsed", 2, s.creates())
        factory.created("r2")
        idle(3_000)
        assertEquals("never twice once the server has created it", 2, s.creates())
    }

    @Test
    fun `the transcriber's live guess cuts a reply mid-segment on the loudspeaker`() {
        val (_, factory, engine) = session()
        val s = factory.socket!!
        factory.created("r1"); factory.audio("r1"); factory.transcript("r1", "Looks pretty solid. You've got a few tasks wrapped up.")
        factory.speechStarted("o")
        factory.guess("o", "How will this")
        assertEquals("filler only: could be anything", 0, s.types().count { it == "response.cancel" })
        assertEquals(0, engine.flushes)
        factory.guess("o", "How will this be like")
        assertEquals("\"like\" the model never said: theirs — cut now", 1, s.types().count { it == "response.cancel" })
        assertEquals(1, engine.flushes)
        // The echo's live guess never cuts.
        val (_, f2, e2) = session()
        f2.created("r1"); f2.audio("r1"); f2.transcript("r1", "Looks pretty solid. You've got a few tasks wrapped up.")
        f2.speechStarted("e")
        f2.guess("e", "Looks pretty solid you've got a few")
        assertEquals(0, f2.socket!!.types().count { it == "response.cancel" })
        assertEquals(0, e2.flushes)
    }

    @Test
    fun `the opening watchdog asks again once when no reply has started in 2 and a half seconds`() {
        val (_, factory, _) = session()
        val s = factory.socket!!
        assertEquals(1, s.creates())
        idle(VoiceRealtimeClient.OPENING_WATCHDOG_MS + 50)
        assertEquals("one retry", 2, s.creates())
        idle(VoiceRealtimeClient.OPENING_WATCHDOG_MS + 50)
        assertEquals("only one", 2, s.creates())
        // A reply that did start: no retry.
        val (_, f2, _) = session()
        f2.created("r1")
        idle(VoiceRealtimeClient.OPENING_WATCHDOG_MS + 50)
        assertEquals(1, f2.socket!!.creates())
    }

    // Dead on arrival → reconnect, not an error (iOS VoiceReconnectTests): the
    // server failed a session 1 s after the socket opened ("thread pool
    // exausted max_workers 100", device 2026-09-20 00:41) and the user saw
    // "Socket is not connected"; the second try was fine.

    @Test
    fun `a server error before any reply is swallowed for the reconnect, and the drop that follows hands it to the owner`() {
        val states = mutableListOf<VoiceState>()
        val errors = mutableListOf<String>()
        val ended = mutableListOf<String?>()
        val (c, factory, _) = session(states, errors, hook = { ended += it })
        factory.message(capacityError)
        assertTrue(c.failedBeforeAnyReply)
        assertTrue("not the user's problem yet", errors.isEmpty())
        assertFalse(states.contains(VoiceState.ERROR))
        // The socket closes right after: the owner hears, the screen shows nothing.
        factory.listener!!.onFailure(factory.socket!!, RuntimeException("Socket is not connected"), null)
        assertEquals(listOf<String?>("Socket is not connected"), ended)
        assertTrue(errors.isEmpty())
        assertFalse(states.contains(VoiceState.ERROR))
        assertFalse(c.isOpen)
    }

    @Test
    fun `the same error after a reply started is reported`() {
        val states = mutableListOf<VoiceState>()
        val errors = mutableListOf<String>()
        val (c, factory, _) = session(states, errors, hook = { })
        factory.created("r1")
        factory.message(capacityError)
        assertFalse(c.failedBeforeAnyReply)
        // The USER gets plain words, never the provider's text: it names the
        // model and the organisation ("Rate limit reached for gpt-… in
        // organization org-…"), which reads as broken and contradicts the
        // scope guardrail's "never reveal what model powers you" (iOS build
        // 78, audit 2026-09-21). The raw text goes to the device log instead.
        assertEquals(listOf("Something went wrong with the assistant — try again."), errors)
        assertTrue(states.contains(VoiceState.ERROR))
    }

    @Test
    fun `with no owner to reconnect the error is reported at once`() {
        val states = mutableListOf<VoiceState>()
        val errors = mutableListOf<String>()
        val (c, factory, _) = session(states, errors, hook = null)
        factory.message(capacityError)
        assertFalse(c.failedBeforeAnyReply)
        assertEquals(listOf("Something went wrong with the assistant — try again."), errors)
        assertTrue(states.contains(VoiceState.ERROR))
    }

    // ── what the user is told when the provider fails (iOS build 78): plain
    // words, never the provider's text ──

    /** The mapping itself (iOS VoiceCaptionTests): the user never sees the
     *  model, the organisation or provider jargon, and a rate limit reads as
     *  "busy, try again" rather than as a bug (audit 2026-09-21). */
    @Test
    fun `provider errors are mapped to plain words`() {
        val rateLimited = VoiceRealtimeClient.friendlyError(
            "rate_limit_exceeded",
            "Rate limit reached for gpt-realtime-2.1-mini (for limit gpt-4o-mini-realtime) in organization org-KNkOJ3 on tokens per min (TPM): Limit 40000",
        )
        assertEquals("The assistant is busy right now — give it a minute and ask again.", rateLimited)
        for (raw in listOf(
            rateLimited,
            VoiceRealtimeClient.friendlyError("", "Request timed out."),
            VoiceRealtimeClient.friendlyError("", "invalid_api_key"),
            VoiceRealtimeClient.friendlyError("", "thread pool exausted max_workers 100"),
        )) {
            for (leak in listOf("gpt", "org-", "openai", "qwen", "TPM", "api_key")) {
                assertFalse("$raw leaks $leak", raw.lowercase().contains(leak.lowercase()))
            }
            assertTrue(raw.isNotEmpty())
        }
    }

    // ── a rate-limited reply (iOS build 76): asked for again after the token
    // bucket's reset, three times at most, then said out loud ──

    private val rateLimitText =
        "Rate limit reached for gpt-realtime-2.1-mini in organization org-KNkOJ3 on tokens per min (TPM): Limit 40000, Used 39000, Requested 9000. Please try again in 6.946s."
    private fun FakeFactory.failed(id: String, code: String, text: String) =
        message("""{"type":"response.done","response":{"id":"$id","status":"failed","status_details":{"type":"failed","error":{"type":"tokens","code":"$code","message":${str(text)}}}}}""")

    /** iOS BargeInTests 26b's retry-delay half (the controller half is core 27b). */
    @Test
    fun `a rate-limited reply waits for the bucket's reset, else the server's hint, else 5 s, within 1 to 30 s`() {
        assertEquals(7196L, VoiceRealtimeClient.retryAfterMs("Rate limit reached … Please try again in 6.946s.", null))
        assertEquals(12750L, VoiceRealtimeClient.retryAfterMs("", 12.5))
        assertEquals(5250L, VoiceRealtimeClient.retryAfterMs("", null))
        assertEquals(30250L, VoiceRealtimeClient.retryAfterMs("", 90.0))
    }

    @Test
    fun `a rate-limited reply is asked for again after the bucket's reset, and the fourth failure says it is busy`() {
        val states = mutableListOf<VoiceState>()
        val errors = mutableListOf<String>()
        val (c, factory, _) = session(states, errors)
        val s = factory.socket!!
        // The greeting, then a real turn, asked for once the hold is up.
        factory.created("r0"); factory.done("r0")
        factory.speechStarted("u"); factory.speechStopped(); factory.completed("u", "what's on today")
        idle(BargeInController.TURN_HOLD_MS + 20)
        assertEquals(2, s.creates())
        // OpenAI after every response: the token bucket refills in 2 s.
        factory.message("""{"type":"rate_limits.updated","rate_limits":[{"name":"requests","limit":5000,"remaining":4999,"reset_seconds":0.01},{"name":"tokens","limit":40000,"remaining":0,"reset_seconds":2.0}]}""")
        val wait = VoiceRealtimeClient.retryAfterMs(rateLimitText, 2.0)
        assertEquals("the bucket's own reset wins over the text's hint", 2250L, wait)
        for (i in 1..BargeInController.RATE_LIMIT_MAX_RETRIES) {
            factory.created("r$i")
            factory.failed("r$i", "rate_limit_exceeded", rateLimitText)
            assertTrue("retry $i is silent", errors.isEmpty())
            assertEquals(VoiceState.THINKING, states.last())
            assertEquals("not before the reset", i + 1, s.creates())
            idle(wait + 20)
            assertEquals("asked again after the reset", i + 2, s.creates())
        }
        factory.created("r4")
        factory.failed("r4", "rate_limit_exceeded", rateLimitText)
        assertEquals("the fourth is said out loud, in plain words", listOf("The assistant is busy right now — give it a minute and ask again."), errors)
        assertEquals("the session stays live", VoiceState.LISTENING, states.last())
        assertFalse(states.contains(VoiceState.ERROR))
        idle(10_000)
        assertEquals("and nothing more is asked", 5, s.creates())
        assertTrue(c.isOpen)
    }

    /** Asked from idle, no done or error follows a create the server
     *  swallowed: the create's own grace tick asks again (B75.3; review of
     *  the 2026-09-23 parity port). */
    @Test
    fun `a create swallowed while idle is asked again after the grace, with nobody speaking`() {
        val (_, factory, _) = session()
        val s = factory.socket!!
        factory.created("r0"); factory.done("r0")
        factory.speechStarted("u"); factory.speechStopped(); factory.completed("u", "what's on today")
        idle(BargeInController.TURN_HOLD_MS + 20)
        assertEquals(2, s.creates())
        idle(BargeInController.CREATE_GRACE_MS + 20)
        assertEquals("nothing was created: asked again", 3, s.creates())
        factory.created("r1")
        idle(10_000)
        assertEquals("and never again once created", 3, s.creates())
    }

    /** The confirmation after a tool result is the reply most likely to be
     *  rate-limited (each tool round is another full-prefix reply). Its retry
     *  stands in for it: read as a bare claim, the forced corrective told the
     *  model nothing happened and it ran create_task again (review of the
     *  B76.3 port, 2026-09-23). */
    @Test
    fun `guard - the confirmation after a tool, rate-limited and retried, is still tool-backed`() {
        val (factory, s) = openSession { name, _ -> if (name == "create_task") "ok: created task id=1 name=\"buy milk\"" else "ok" }
        factory.created("r1")
        factory.toolCall("create_task", "c1")
        awaitToolOutput(s, 1)
        factory.done("r1")
        awaitContinuation(s)
        factory.created("r2")
        factory.failed("r2", "rate_limit_exceeded", rateLimitText)
        idle(VoiceRealtimeClient.retryAfterMs(rateLimitText, null) + 20)
        factory.created("r3")
        factory.transcript("r3", "I've added buy milk for today.")
        factory.done("r3")
        assertEquals("the retry confirms what the tool did: no corrective", 0, s.correctives())
    }

    /** OpenAI's shapes, as the proxy forwards them: a completed reply carries
     *  `"status_details": null`. Read with `.jsonObject` that threw inside
     *  onMessage, which OkHttp turns into onFailure — every session died on
     *  the greeting's done, the kotlinx exception text as its error line
     *  (review of this port, 2026-09-23). */
    @Test
    fun `OpenAI's completed reply with null status_details keeps the session live`() {
        val states = mutableListOf<VoiceState>()
        val errors = mutableListOf<String>()
        val (c, factory, _) = session(states, errors)
        val s = factory.socket!!
        factory.message("""{"type":"response.created","event_id":"e1","response":{"object":"realtime.response","id":"r1","status":"in_progress","status_details":null,"output":[],"usage":null}}""")
        factory.message("""{"type":"response.done","event_id":"e2","response":{"object":"realtime.response","id":"r1","status":"completed","status_details":null,"output":[{"id":"i1","type":"message","role":"assistant","content":[{"type":"output_audio","transcript":"Morning."}]}],"usage":{"total_tokens":12}}}""")
        assertTrue(c.isOpen)
        assertTrue("$errors", errors.isEmpty())
        assertFalse(states.contains(VoiceState.ERROR))
        // It still takes a turn.
        factory.speechStarted("u"); factory.speechStopped(); factory.completed("u", "what's on today")
        idle(BargeInController.TURN_HOLD_MS + 20)
        assertEquals(2, s.creates())
        // An `error` that is a bare string is worded too, not thrown on.
        factory.message("""{"type":"error","error":"boom"}""")
        assertEquals(listOf("Something went wrong with the assistant — try again."), errors)
    }

    @Test
    fun `a reply that failed for another reason is told once in plain words and the session stays live`() {
        val states = mutableListOf<VoiceState>()
        val errors = mutableListOf<String>()
        val (c, factory, _) = session(states, errors)
        factory.created("r1")
        factory.failed("r1", "server_error", "The server had an error while processing your request (org-KNkOJ3).")
        assertEquals(listOf("Something went wrong with the assistant — try again."), errors)
        assertFalse(states.contains(VoiceState.ERROR))
        assertEquals(VoiceState.LISTENING, states.last())
        assertTrue(c.isOpen)
        // A server `error` event is worded by its code, not by matching the text.
        factory.message("""{"type":"error","error":{"code":"rate_limit_exceeded","message":"Too many requests for org-KNkOJ3."}}""")
        assertEquals("The assistant is busy right now — give it a minute and ask again.", errors.last())
        assertEquals(VoiceState.ERROR, states.last())
    }

    // ── the route at session start (audit 2026-09-23 X1): the controller is
    // built before capture enters communication mode, when the engine still
    // reads the loudspeaker, and that first route pick fires no route change ──

    @Test
    fun `a headset found when capture starts sets the first session update's profile`() {
        val engine = FakeEngine(app, routeOnCapture = VoiceRoute.LOW_ECHO)
        val factory = FakeFactory()
        val c = client(engine, factory, mutableListOf())
        c.start()
        factory.open()
        val s = factory.socket!!
        val td = Json.parseToJsonElement(s.sent.first()).jsonObject["session"]!!.jsonObject["turn_detection"]!!.jsonObject
        assertEquals("earphones: the low-echo threshold", BargeInProfile.LOW_ECHO.threshold, td["threshold"]!!.jsonPrimitive.double, 0.0)
        assertEquals("the capture gate runs the low-echo margins", BargeInProfile.LOW_ECHO, engine.profile)
        assertEquals("one full session.update, no partial one before it", listOf("session.update", "conversation.item.create", "response.create"), s.types())
        // The loudspeaker throughout: the speaker profile, as before.
        val e2 = FakeEngine(app)
        val f2 = FakeFactory()
        client(e2, f2, mutableListOf()).start()
        f2.open()
        val td2 = Json.parseToJsonElement(f2.socket!!.sent.first()).jsonObject["session"]!!.jsonObject["turn_detection"]!!.jsonObject
        assertEquals(BargeInProfile.SPEAKER.threshold, td2["threshold"]!!.jsonPrimitive.double, 0.0)
        assertEquals(BargeInProfile.SPEAKER, e2.profile)
    }

    @Test
    fun `a drop after the handshake before any reply is dead on arrival too, a clean close is not`() {
        val states = mutableListOf<VoiceState>()
        val errors = mutableListOf<String>()
        val ended = mutableListOf<String?>()
        val (c, factory, _) = session(states, errors, hook = { ended += it })
        factory.listener!!.onFailure(factory.socket!!, RuntimeException("boom"), null)
        assertTrue(c.failedBeforeAnyReply)
        assertEquals(listOf<String?>("boom"), ended)
        assertTrue("the owner reconnects; nothing shown", errors.isEmpty())
        assertFalse(states.contains(VoiceState.ERROR))
        // A clean close is the server hanging up, not a failure.
        val states2 = mutableListOf<VoiceState>()
        val ended2 = mutableListOf<String?>()
        val (c2, f2, _) = session(states2, hook = { ended2 += it })
        f2.listener!!.onClosed(f2.socket!!, 1000, "bye")
        assertFalse(c2.failedBeforeAnyReply)
        assertEquals(listOf<String?>(null), ended2)
        assertEquals(VoiceState.CLOSED, states2.last())
    }

    // ── B78.3: logcat keeps the SHAPE of what was said, never the words ──

    @Test
    fun `the barge-in log carries a transcript's length, never the user's words`() {
        // Release builds keep Log calls, so the words reached logcat and any
        // bugreport a tester sent (parity with iOS build 78, 0f24908).
        ShadowLog.reset()
        val (_, factory, _) = session()
        val said = "my bank PIN is 4471"
        factory.message("""{"type":"input_audio_buffer.speech_started","item_id":"u1"}""")
        factory.message("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"u1","text":"","stash":"my bank PIN"}""")
        factory.message("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"u1","transcript":"$said"}""")
        val lines = ShadowLog.getLogsForTag(VoiceRealtimeClient.TAG).map { it.msg }
        assertTrue(lines.toString(), lines.any { it.contains("transcription(live, u1, chars=11)") })
        assertTrue(lines.toString(), lines.any { it.contains("transcription(final, u1, chars=${said.length})") })
        assertTrue(lines.toString(), lines.none { it.contains("PIN") || it.contains("4471") || it.contains("bank") })
    }
}
