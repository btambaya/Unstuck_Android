package tech.csalliance.unstuck.ui.assistant

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import tech.csalliance.unstuck.core.logic.BargeInCommand
import tech.csalliance.unstuck.core.logic.BargeInController
import tech.csalliance.unstuck.core.logic.BargeInEvent
import tech.csalliance.unstuck.core.logic.BargeInProfile
import tech.csalliance.unstuck.core.logic.BargeInUi
import tech.csalliance.unstuck.core.logic.TurnDetection
import tech.csalliance.unstuck.core.logic.VoiceRoute
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// Realtime voice client for Qwen-Omni (via the Cloudflare proxy). Streams mic
// PCM16/16k up, plays the model's PCM16/24k speech back, shows live captions,
// and runs the agent's tool calls through the SAME dispatcher as text mode.
// Protocol verified against the live DashScope endpoint:
//   session.update {modalities,instructions,input/output_audio_format:pcm16,
//                   turn_detection:{server_vad,threshold,prefix_padding_ms,
//                   silence_duration_ms} | null (hold-to-talk), tools, tool_choice}
//   client → input_audio_buffer.append {audio: base64}
//   server → response.created {response:{id}}
//          → response.audio.delta {response_id, delta: base64}  (24k speech)
//          → response.audio_transcript.delta {response_id, delta}  (captions)
//          → response.done {response:{id,status}}
//          → input_audio_buffer.speech_started / speech_stopped  (→ barge-in)
//          → response.function_call_arguments.done {name, call_id, arguments}
//   client → conversation.item.create {function_call_output, call_id, output}
//          → response.create
//   client → response.cancel  (ONLY while a response is in flight; the server
//            answers a stray one with an "…active response" error — benign)
//
// BARGE-IN (spec: scratchpad bargein.md §2/§6/§8; pure logic in :core BargeIn.kt)
// Every transport/audio event is fed to a BargeInController and the commands it
// returns are executed here. "Duck-and-confirm": the first hint of the user
// talking over the model (RMS gate opening on the capture thread, or the server's
// speech_started) only ducks playback to -12 dB and arms a confirm timer; the
// reply is cancelled once confirmed (gate held ≥ confirmMs, server + gate agree,
// a transcription arrives, or the timer fires with no speech_stopped). A cough
// ducks then restores without cancelling. Deltas of a cancelled response are
// dropped even after the next response.created (they interleave on the wire).
// Interrupt (button/orb) is a hard cancel that never ducks.

/** THINKING = a response is in flight but no audio has arrived yet (tool call /
 *  model latency) — the screen shows "Thinking…" and offers Interrupt. */
enum class VoiceState { CONNECTING, LISTENING, THINKING, SPEAKING, ERROR, CLOSED }

class VoiceRealtimeClient(
    private val proxyUrl: String,          // wss://…workers.dev   (token added per-connect)
    private val token: String,             // Supabase access token (Worker validates it)
    private val model: String,
    private val instructions: String,      // system prompt + live context
    private val tools: JsonArray,          // tool schemas (OpenAI/DashScope shape)
    /** What the assistant should DO the moment the session opens (a short by-name
     *  hello) — sent as a hidden user primer the user never sees (web parity). */
    private val opening: String? = null,
    private val audio: VoiceAudioEngine,
    private val runTool: suspend (name: String, args: JsonObject) -> String,
    private val onState: (VoiceState) -> Unit,
    private val onCaption: (role: String, text: String, done: Boolean) -> Unit,
    private val onError: (String) -> Unit = {},
    /** 3 false barge-ins inside 2 min: the screen may offer "Noisy room? Switch to hold to talk". */
    private val onSuggestHoldToTalk: () -> Unit = {},
    /** Devices whose AEC leaves enough echo to self-trigger fall back to hard half-duplex on the speaker. */
    private val speakerHalfDuplex: Boolean = false,
    /** Test seam: where sockets come from (production = the shared OkHttpClient). */
    private val socketFactory: WebSocket.Factory = http,
) {
    companion object {
        // One client for ALL voice sessions — each OkHttpClient owns a dispatcher
        // executor, connection pool, and ping scheduler that linger long after
        // the session ends, so per-session clients pile up idle thread pools.
        private val http by lazy {
            OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived socket
                .build()
        }
        // Client-chosen id for the hidden opening primer so it can be deleted
        // after the first response (same ids as web lib/voice/realtime-client.ts).
        private const val PRIMER_ITEM_ID = "a0e1f2d3c4b5a6978869504132231405"
        // Client event id on the primer delete, so ONLY its rejection is swallowed.
        private const val PRIMER_DELETE_EVENT = "evt_primer_delete_0001"
    }

    @Volatile private var primerDeleted = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var ws: WebSocket? = null
    @Volatile private var open = false
    @Volatile private var stopped = false

    /** The socket is up (session.update sent) and stop() hasn't run. A server-side
     *  error leaves this true — the session is still usable (the screen keeps the
     *  orb gesture on that basis). */
    val isOpen: Boolean get() = open && !stopped

    /** stop() has run (by the UI, focus loss, or a capture failure) — a new session needs a new client. */
    val isStopped: Boolean get() = stopped

    // The barge-in state machine. Events arrive from the WS reader thread, the
    // capture thread (gate), the playback thread (drain), the main thread (route
    // change, timer, UI) — `ctlLock` serializes handle()+command execution.
    private val ctlLock = Any()
    private val ctl = BargeInController(
        profile = BargeInProfile.forRoute(audio.route, speakerHalfDuplex),
        holdToTalk = audio.holdToTalkPref,
    ) { SystemClock.uptimeMillis() }
    private val tick = Runnable { dispatch(BargeInEvent.Tick) }

    /** Hold-to-talk is on for this session (orb = press-and-hold; VAD off). */
    val holdToTalk: Boolean get() = ctl.holdToTalk

    /** Speaking-or-still-playing: the Interrupt control should be offered. */
    val canInterrupt: Boolean get() = ctl.speaking

    fun start() {
        onState(VoiceState.CONNECTING)
        audio.profile = ctl.profile
        audio.holdToTalk = ctl.holdToTalk
        audio.onGateOpen = { dispatch(BargeInEvent.GateOpen) }
        audio.onGateClose = { dispatch(BargeInEvent.GateClose) }
        audio.onPlaybackDrained = { dispatch(BargeInEvent.PlaybackDrained) }
        audio.onRouteChanged = { route -> dispatch(BargeInEvent.RouteChanged(profileFor(route))) }
        // Capture starts while the socket is still connecting so the gate's 500 ms
        // floor calibration overlaps the handshake; nothing is appended before
        // onOpen (frames are dropped below) and nothing before calibration ends
        // (the gate emits no frames yet).
        audio.startCapture { frame ->
            val socket = ws ?: return@startCapture
            if (!open) return@startCapture
            socket.send(buildJsonObject {
                put("type", "input_audio_buffer.append")
                put("audio", Base64.encodeToString(frame, Base64.NO_WRAP))
            }.toString())
        }
        // A synchronous capture failure (mic held by another app, focus denied
        // during a call) has already stop()ped us from inside onCaptureError:
        // never dial — the proxy session would open with nobody able to close it
        // (a "Listening…" zombie holding one of the user's concurrent-session slots).
        if (stopped) return
        val req = Request.Builder()
            .url(proxyUrl.replace(Regex("\\?.*$"), "") + "?model=" + model)
            .header("Authorization", "Bearer $token")
            .build()
        val socket = socketFactory.newWebSocket(req, listener)
        ws = socket
        // stop() raced the dial (it saw ws == null): tear this one down ourselves.
        if (stopped) { ws = null; runCatching { socket.cancel() } }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        open = false
        mainHandler.removeCallbacks(tick)
        scope.cancel() // a dead session must not keep running tools / mutating state
        val socket = ws
        ws = null
        // Off the caller's thread: audio.shutdown() joins the capture/playback
        // threads (up to ~600ms) and stop() runs on main (dispose / ON_STOP).
        thread(name = "voice-stop") {
            runCatching { socket?.close(1000, "bye") }
            audio.shutdown()
        }
        onState(VoiceState.CLOSED)
    }

    /** Manual interrupt = HARD cancel (never ducks): cut playback now, tell the
     *  server to stop generating (only if a response is actually in flight — a
     *  stray response.cancel is answered with an error), and drop every later
     *  delta of the cancelled response. A no-op while idle. */
    fun interrupt() {
        if (!open) return
        dispatch(BargeInEvent.InterruptPressed)
    }

    /** Hold-to-talk: orb pressed — cancels any reply, forces the gate open (pre-roll flushed). */
    fun pttDown() { if (open) dispatch(BargeInEvent.PttDown) }

    /** Hold-to-talk: orb released — commits the buffered audio and asks for a reply. */
    fun pttUp() { if (open) dispatch(BargeInEvent.PttUp) }

    /** Switch hold-to-talk on/off mid-session (re-sends turn_detection). Persisting
     *  the preference (SettingsStore.setVoiceHoldToTalk) is the caller's job. */
    fun setHoldToTalk(on: Boolean) {
        synchronized(ctlLock) {
            val cmds = ctl.setHoldToTalk(on)
            audio.holdToTalk = ctl.holdToTalk
            execute(cmds)
        }
    }

    private fun profileFor(route: VoiceRoute) = BargeInProfile.forRoute(route, speakerHalfDuplex)

    /** Feed one event to the controller and execute what it asks for. */
    private fun dispatch(event: BargeInEvent, payload: String? = null) {
        if (stopped) return
        synchronized(ctlLock) { execute(ctl.handle(event), payload) }
    }

    // Runs under ctlLock. `payload` is the base64 audio / caption text of the
    // delta event that produced an EnqueueAudio / ShowCaption command.
    private fun execute(cmds: List<BargeInCommand>, payload: String? = null) {
        // Keep the capture path's view of the controller current.
        audio.responseActive = ctl.responseActive
        audio.profile = ctl.profile
        for (cmd in cmds) when (cmd) {
            is BargeInCommand.Duck -> {
                audio.duck()
                mainHandler.removeCallbacks(tick)
                mainHandler.postDelayed(tick, cmd.confirmMs)
            }
            BargeInCommand.Restore -> { mainHandler.removeCallbacks(tick); audio.restore() }
            BargeInCommand.FlushPlayback -> audio.flushPlayback()
            BargeInCommand.SendCancel -> send(buildJsonObject { put("type", "response.cancel") })
            is BargeInCommand.SetMuted -> Unit // the controller owns `muted`; deltas are filtered by it
            BargeInCommand.EnqueueAudio -> payload?.let { audio.enqueue(Base64.decode(it, Base64.NO_WRAP)) }
            BargeInCommand.ShowCaption -> payload?.let { onCaption("assistant", it, false) }
            // The screen clears the reply line on a "user" caption (new user turn).
            BargeInCommand.ClearCaption -> onCaption("user", "", true)
            // Hold-to-talk release: the frame being read when the finger lifted is
            // still in the capture thread — commit only once it has been appended
            // (the engine runs this on that thread, right behind the last append),
            // so the tail of the utterance is never cut and a short tap still sends
            // the audio it covered.
            BargeInCommand.CommitAndRespond -> audio.afterCaptureDrain {
                send(buildJsonObject { put("type", "input_audio_buffer.commit") })
                send(buildJsonObject { put("type", "response.create") })
            }
            is BargeInCommand.ForceGate -> audio.forceGate(cmd.open)
            is BargeInCommand.Ui -> onState(
                when (cmd.state) {
                    BargeInUi.SPEAKING -> VoiceState.SPEAKING
                    BargeInUi.THINKING -> VoiceState.THINKING
                    BargeInUi.LISTENING -> VoiceState.LISTENING
                },
            )
            is BargeInCommand.SessionUpdate -> send(turnDetectionUpdate(cmd.turnDetection))
            is BargeInCommand.ReportError -> { onError(cmd.message.take(160)); onState(VoiceState.ERROR) }
            BargeInCommand.SuggestHoldToTalk -> mainHandler.post { onSuggestHoldToTalk() }
        }
    }

    private fun send(obj: JsonObject) {
        if (!open) return
        runCatching { ws?.send(obj.toString()) }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // stop() ran while the handshake was in flight (capture failed, focus
            // lost, user left): the socket it couldn't see must not become a live
            // session nobody owns — close it here instead of greeting into the void.
            if (stopped) { runCatching { webSocket.close(1000, "bye") }; return }
            open = true
            webSocket.send(sessionUpdate())
            audio.startPlayback()
            // Personal-assistant opening (web parity): the assistant speaks FIRST —
            // a short by-name hello per the instructions — instead of sitting in
            // silence. DashScope 400s a response.create with NO user element in
            // the conversation, so a synthetic user item primes it. It produces no
            // caption and barge-in applies from the first syllable. The primer is
            // ONE-SHOT: left in the conversation it becomes a standing instruction
            // the model re-executes after every barge-in, so it gets a known id
            // and is deleted as soon as the first response completes.
            val primer = opening?.takeIf { it.isNotBlank() }
            if (primer != null) {
                webSocket.send(buildJsonObject {
                    put("type", "conversation.item.create")
                    putJsonObject("item") {
                        put("id", PRIMER_ITEM_ID); put("type", "message"); put("role", "user")
                        putJsonArray("content") { addJsonObject { put("type", "input_text"); put("text", primer) } }
                    }
                }.toString())
                webSocket.send(buildJsonObject { put("type", "response.create") }.toString())
            } else primerDeleted = true
            onState(VoiceState.LISTENING)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val ev = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (ev["type"]?.jsonPrimitive?.contentOrNull) {
                "input_audio_buffer.speech_started" -> dispatch(BargeInEvent.SpeechStarted)
                "input_audio_buffer.speech_stopped" -> dispatch(BargeInEvent.SpeechStopped)
                "response.created" -> dispatch(BargeInEvent.ResponseCreated(responseId(ev)))
                "response.audio.delta" ->
                    ev["delta"]?.jsonPrimitive?.contentOrNull?.let { dispatch(BargeInEvent.AudioDelta(responseId(ev)), it) }
                "response.audio_transcript.delta" ->
                    ev["delta"]?.jsonPrimitive?.contentOrNull?.let { dispatch(BargeInEvent.TranscriptDelta(responseId(ev)), it) }
                "response.audio_transcript.done" -> onCaption("assistant", "", true)
                "conversation.item.input_audio_transcription.delta" -> dispatch(BargeInEvent.TranscriptionDelta)
                "conversation.item.input_audio_transcription.completed" -> {
                    dispatch(BargeInEvent.TranscriptionCompleted)
                    ev["transcript"]?.jsonPrimitive?.contentOrNull?.let { onCaption("user", it, true) }
                }
                "response.done" -> {
                    deletePrimer()
                    val r = ev["response"]?.jsonObject
                    dispatch(BargeInEvent.ResponseDone(responseId(ev), r?.get("status")?.jsonPrimitive?.contentOrNull))
                }
                // Audio finished streaming but the reply may still be playing/thinking
                // (tool call) — the controller decides from response.done + drain.
                "response.audio.done" -> deletePrimer()
                "response.function_call_arguments.done" -> handleToolCall(webSocket, ev)
                "error" -> {
                    val errObj = ev["error"]?.jsonObject
                    val m = errObj?.get("message")?.jsonPrimitive?.contentOrNull
                        ?: ev["error"]?.jsonPrimitive?.contentOrNull
                    // Swallow ONLY the primer-delete rejection (matched by our client
                    // event id or the primer's item id) — never a blanket "not found".
                    val evId = ev["event_id"]?.jsonPrimitive?.contentOrNull
                        ?: errObj?.get("event_id")?.jsonPrimitive?.contentOrNull
                    if (evId == PRIMER_DELETE_EVENT || m?.contains(PRIMER_ITEM_ID) == true) return
                    // "Conversation has no active response" / "already has an active
                    // response" are benign (guarded cancel raced the server) — the
                    // controller keeps LISTENING; anything else surfaces as ERROR.
                    dispatch(BargeInEvent.Error(m))
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            open = false
            if (stopped) return // already torn down by stop(); keep its CLOSED, don't paint an ERROR over it
            mainHandler.removeCallbacks(tick); audio.shutdown()
            val code = response?.code
            val body = runCatching { response?.body?.string() }.getOrNull()
            val msg = when {
                !body.isNullOrBlank() -> body.take(160)
                code != null -> "Voice server error (HTTP $code)"
                else -> t.message?.take(160) ?: "Couldn't reach the voice server"
            }
            onError(msg); onState(VoiceState.ERROR)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            open = false
            if (stopped) return // stop() already shut audio down and reported CLOSED
            mainHandler.removeCallbacks(tick); audio.shutdown(); onState(VoiceState.CLOSED)
        }
    }

    /** First response finished → the opening primer has served its purpose; remove
     *  it so it can never be re-executed after an interruption. Best effort: a
     *  backend without item.delete just leaves it (the primer also says "ONCE"). */
    private fun deletePrimer() {
        if (primerDeleted) return
        primerDeleted = true
        send(buildJsonObject {
            put("type", "conversation.item.delete")
            put("item_id", PRIMER_ITEM_ID)
            put("event_id", PRIMER_DELETE_EVENT)
        })
    }

    /** response_id on delta events; response.id on created/done. */
    private fun responseId(ev: JsonObject): String? =
        ev["response_id"]?.jsonPrimitive?.contentOrNull
            ?: ev["response"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull

    private fun handleToolCall(webSocket: WebSocket, ev: JsonObject) {
        val name = ev["name"]?.jsonPrimitive?.contentOrNull ?: return
        val callId = ev["call_id"]?.jsonPrimitive?.contentOrNull ?: return
        val args = runCatching {
            Json.parseToJsonElement(ev["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}").jsonObject
        }.getOrDefault(JsonObject(emptyMap()))
        scope.launch {
            val result = runCatching { runTool(name, args) }.getOrElse { "error: ${it.message ?: "failed"}" }
            // Feed the tool result back + ask the model to continue (speak).
            webSocket.send(buildJsonObject {
                put("type", "conversation.item.create")
                putJsonObject("item") {
                    put("type", "function_call_output")
                    put("call_id", callId)
                    put("output", result)
                }
            }.toString())
            webSocket.send(buildJsonObject { put("type", "response.create") }.toString())
        }
    }

    private fun sessionUpdate(): String = buildJsonObject {
        put("type", "session.update")
        putJsonObject("session") {
            putJsonArray("modalities") { add("text"); add("audio") }
            put("instructions", instructions)
            put("input_audio_format", "pcm16")
            put("output_audio_format", "pcm16")
            put("turn_detection", TurnDetection.json(synchronized(ctlLock) { ctl.turnDetection() }))
            put("tools", tools)
            put("tool_choice", "auto")
        }
    }.toString()

    /** Partial session.update carrying only the new turn_detection (route change /
     *  hold-to-talk toggle); the proxy clamps threshold + silence and passes null through. */
    private fun turnDetectionUpdate(td: TurnDetection?): JsonObject = buildJsonObject {
        put("type", "session.update")
        putJsonObject("session") { put("turn_detection", TurnDetection.json(td)) }
    }
}
