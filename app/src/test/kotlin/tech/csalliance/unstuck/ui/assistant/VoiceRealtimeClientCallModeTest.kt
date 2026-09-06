package tech.csalliance.unstuck.ui.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Call mode of the realtime client (CallVoiceService's session) against a fake
 * engine + socket: snooze_call is answered locally and handed to the owner
 * (never the executor), a tool outside the call set is refused before the
 * executor, the mic mute gates the upload, and onTransportEnded fires exactly
 * once on a self-ending transport and never after stop().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VoiceRealtimeClientCallModeTest {

    private val app = ApplicationProvider.getApplicationContext<Context>()

    private class FakeEngine(ctx: Context) : VoiceAudioEngine(ctx) {
        var onFrame: ((ByteArray) -> Unit)? = null
        override fun startCapture(onFrame: (ByteArray) -> Unit) { this.onFrame = onFrame }
        override fun startPlayback() {}
        override fun shutdown() {}
        override fun forceGate(open: Boolean) {}
        override fun afterCaptureDrain(block: () -> Unit) { block() }
        override fun stopCapture() {}
        override fun stopPlayback() {}
        override fun duck() {}
        override fun restore() {}
        override fun flushPlayback() {}
        override fun enqueue(pcm: ByteArray) {}
        override fun playbackQueued(): Boolean = false
        override fun outputBusy(): Boolean = false
    }

    private class FakeSocket(private val req: Request) : WebSocket {
        private val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        val sent: List<String> get() = synchronized(log) { log.toList() }
        override fun request(): Request = req
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean { log += text; return true }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {}
        fun outputs(): List<String> = sent.mapNotNull { raw ->
            val item = Json.parseToJsonElement(raw).jsonObject["item"]?.jsonObject ?: return@mapNotNull null
            if (item["type"]?.jsonPrimitive?.contentOrNull != "function_call_output") return@mapNotNull null
            item["output"]?.jsonPrimitive?.contentOrNull
        }
        fun appends(): Int = sent.count { Json.parseToJsonElement(it).jsonObject["type"]?.jsonPrimitive?.contentOrNull == "input_audio_buffer.append" }
    }

    private class FakeFactory : WebSocket.Factory {
        var socket: FakeSocket? = null
        var listener: WebSocketListener? = null
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.listener = listener
            return FakeSocket(request).also { socket = it }
        }
        fun open() {
            val s = socket!!
            listener!!.onOpen(s, Response.Builder().request(s.request()).protocol(Protocol.HTTP_1_1).code(101).message("ok").build())
        }
        fun toolCall(name: String, callId: String, args: String = "{}") =
            listener!!.onMessage(socket!!, """{"type":"response.function_call_arguments.done","name":"$name","call_id":"$callId","arguments":${Json.encodeToString(kotlinx.serialization.serializer<String>(), args)}}""")
        fun closed() = listener!!.onClosed(socket!!, 1000, "bye")
        fun failed() = listener!!.onFailure(socket!!, RuntimeException("boom"), null)
    }

    private fun awaitOutputs(s: FakeSocket, n: Int) {
        var waited = 0
        while (s.outputs().size < n && waited < 5_000) { Thread.sleep(10); waited += 10 }
        assertEquals(n, s.outputs().size)
    }

    private fun session(
        allowed: Set<String>, onSnooze: (Int) -> Unit = {},
        runTool: suspend (String, JsonObject) -> String = { _, _ -> "ok: ran" },
    ): Triple<VoiceRealtimeClient, FakeFactory, FakeEngine> {
        val engine = FakeEngine(app)
        val factory = FakeFactory()
        val c = VoiceRealtimeClient(
            proxyUrl = "wss://voice.example/ws", token = "t", model = "m", instructions = "i",
            tools = JsonArray(emptyList()), opening = "primer", audio = engine,
            runTool = runTool, onState = {}, onCaption = { _, _, _ -> },
            socketFactory = factory, callMode = CallMode(allowed, onSnooze),
        )
        c.start()
        factory.open()
        return Triple(c, factory, engine)
    }

    @Test fun `snooze_call is answered locally with the clamped minutes and handed to the owner - never the executor`() {
        val ran = mutableListOf<String>()
        val snoozes = mutableListOf<Int>()
        val (_, f, _) = session(setOf("complete_task"), onSnooze = { snoozes += it }, runTool = { n, _ -> ran += n; "ok" })
        f.toolCall("snooze_call", "c1", """{"minutes": 20}""")
        awaitOutputs(f.socket!!, 1)
        assertEquals(listOf(CallMode.snoozeResult(20)), f.socket!!.outputs())
        assertEquals(listOf(20), snoozes)
        assertTrue(ran.isEmpty())
        // Default 10; clamped to 1…180.
        f.toolCall("snooze_call", "c2", "{}")
        f.toolCall("snooze_call", "c3", """{"minutes": 999}""")
        awaitOutputs(f.socket!!, 3)
        assertEquals(listOf(10, 180), snoozes.drop(1))
        assertEquals(listOf(20, 10, 180), snoozes)
    }

    @Test fun `a tool outside the call set is refused before the executor, an allowed one runs`() {
        val ran = mutableListOf<String>()
        val (_, f, _) = session(setOf("complete_task"), runTool = { n, _ -> ran += n; "ok: ran $n" })
        f.toolCall("delete_task", "c1")
        f.toolCall("complete_task", "c2")
        awaitOutputs(f.socket!!, 2)
        assertEquals(setOf(CallMode.notAvailable("delete_task"), "ok: ran complete_task"), f.socket!!.outputs().toSet())
        assertEquals(listOf("complete_task"), ran)
    }

    @Test fun `micMuted drops captured frames instead of uploading them`() {
        val (c, f, engine) = session(setOf("complete_task"))
        engine.onFrame!!(ByteArray(320))
        assertEquals(1, f.socket!!.appends())
        c.micMuted = true
        engine.onFrame!!(ByteArray(320))
        engine.onFrame!!(ByteArray(320))
        assertEquals("muted: nothing uploaded, session still open", 1, f.socket!!.appends())
        assertTrue(c.isOpen)
        c.micMuted = false
        engine.onFrame!!(ByteArray(320))
        assertEquals(2, f.socket!!.appends())
    }

    @Test fun `onTransportEnded fires once on a self-ending transport - null for a clean close, the message for a failure`() {
        val ended = mutableListOf<String?>()
        val (c, f, _) = session(setOf("complete_task"))
        c.onTransportEnded = { ended += it }
        f.closed()
        f.failed()   // a second signal for the same session is not a second end
        assertEquals(listOf<String?>(null), ended)
        assertFalse(c.isOpen)

        val failures = mutableListOf<String?>()
        val (c2, f2, _) = session(setOf("complete_task"))
        c2.onTransportEnded = { failures += it }
        f2.failed()
        assertEquals(1, failures.size)
        assertEquals("boom", failures[0])
    }

    @Test fun `onTransportEnded never fires after stop`() {
        var fired: String? = "untouched"
        val (c, f, _) = session(setOf("complete_task"))
        c.onTransportEnded = { fired = it }
        c.stop()
        f.closed()
        f.failed()
        assertEquals("untouched", fired)
        assertNull(f.socket!!.outputs().firstOrNull())
    }
}
