package tech.csalliance.unstuck.ui.assistant

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
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
import tech.csalliance.unstuck.core.logic.AssistantGuard
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
// 1:1 with iOS App/Voice/VoiceRealtimeClient.swift (ported 2026-09-20).
// Protocol verified against the live DashScope endpoint:
//   session.update {modalities,instructions,input/output_audio_format:pcm16,
//                   turn_detection:{server_vad,threshold,prefix_padding_ms,
//                   silence_duration_ms,interrupt_response:false,
//                   create_response:false} per ROUTE PROFILE — the CLIENT
//                   cancels and creates every reply (BargeIn.kt) | null
//                   (hold-to-talk), tools, tool_choice}
//   client → conversation.item.create {PRIMER} + response.create   (opening)
//   client → input_audio_buffer.append {audio: base64}
//   server → response.created {response:{id}}
//          → response.audio.delta {response_id, delta: base64}  (24k speech)
//          → response.audio_transcript.delta {response_id, delta}  (captions + echo reference)
//          → response.done {response:{id,status,status_details:{reason}}}
//          → input_audio_buffer.speech_started {item_id} / speech_stopped
//          → conversation.item.input_audio_transcription.delta {item_id, text, stash}
//          → conversation.item.input_audio_transcription.completed {item_id, transcript}
//          → response.function_call_arguments.done {name, call_id, arguments}
//          → response.output_item.done {item: function_call}  (same, other shape)
//   client → conversation.item.create {function_call_output, call_id, output}
//          → response.create (ONE, coalesced ~120 ms after the last tool output)
//   client → response.cancel  (ONLY while a response is in flight; the server
//            answers a stray one with an "…active response" error — benign)
//   client → conversation.item.delete {item_id}  (an echo / no-word segment)
//
// Plus the voice integrity guard (web lib/voice/realtime-client.ts + iOS
// VoiceRealtimeClient.swift): a spoken "I've added it" with no write tool call
// behind it in that response gets ONE hidden corrective (3 per session, never
// its own follow-up, never mid-utterance) — see VoiceIntegrityGuard below.
//
// TURN-TAKING (pure logic in :core BargeIn.kt — read its header): since the
// 2026-09-20 port the server never cuts a reply nor answers by itself; every
// reply is asked for by the client from a speech segment's COMPLETED
// transcript, held 500 ms of quiet and never before a cancelled reply's done.
// Every transport/audio event is fed to a BargeInController and the commands
// it returns are executed here. On the loudspeaker the transcriber's LIVE
// GUESS (`stash` on every transcription delta) cuts a reply on the first
// real words of a segment that began on air; echo (the reply's own words
// back through the mic) is deleted from the conversation, never captioned,
// never answered. Low-echo routes keep "duck-and-confirm" by energy.
// Deltas of a cancelled response are dropped even after the next
// response.created (they interleave on the wire). Interrupt (button/orb) is a
// hard cancel that never ducks.
//
// CALL MODE (C1-android — "Unstuck calls you", CallVoiceService): the SAME client
// with a [CallMode] attached. Three things differ, all mirroring iOS
// RealtimeCallVoiceLauncher: (1) tool calls are filtered to the call tools —
// `snooze_call` is answered LOCALLY (the tool result the model reads back, then
// `CallMode.onSnoozeCall` so the owner hangs up and reports `snoozed`) and any
// tool outside `allowedTools` returns "error: <name> isn't available during a
// call" without reaching the executor; (2) `micMuted` gates the upload (audio
// focus lost → mute, never end); (3) `onTransportEnded` fires ONCE when the
// socket ends on its own (failure → message, clean close → null) and never after
// stop() — a protocol-level `error` event does NOT end the call.
//
// DEAD ON ARRIVAL (iOS build 70): the server failed a session 1 s after the
// socket opened ("thread pool exausted max_workers 100", device 2026-09-20
// 00:41; fine on the second try — the provider's capacity). A server error or
// a drop BEFORE ANY REPLY is not surfaced as an error when an owner has hooked
// `onTransportEnded`: `failedBeforeAnyReply` is set and the hook fires, and
// the Talk screen reconnects quietly (VoiceSessionHolder), twice at most.

/** Call-mode configuration for [VoiceRealtimeClient] (see the file header). */
class CallMode(
    /** Tool names the model may run during the call (CallScript.callTools). */
    val allowedTools: Set<String>,
    /** `snooze_call` ran with these (clamped) minutes — the owner ends the call
     *  and reports `snoozed`; the client itself keeps the session open so the
     *  goodbye can play. Invoked on the client's IO scope. */
    val onSnoozeCall: (minutes: Int) -> Unit,
) {
    companion object {
        const val SNOOZE_TOOL = "snooze_call"
        const val DEFAULT_SNOOZE_MIN = 10
        const val MAX_SNOOZE_MIN = 180

        /** `{minutes}` from the raw tool args; default 10, clamped 1…180 (the
         *  coordinator's clamp — call-outcome applies the same one). */
        fun snoozeMinutes(args: JsonObject): Int {
            val raw = (args["minutes"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim()?.toDoubleOrNull()
            val m = raw?.let { Math.round(it).toInt() } ?: DEFAULT_SNOOZE_MIN
            return m.coerceIn(1, MAX_SNOOZE_MIN)
        }

        fun snoozeResult(minutes: Int): String =
            "ok: I'll call back in $minutes minutes — say a quick goodbye; the call ends now"

        fun notAvailable(name: String): String = "error: $name isn't available during a call"
    }
}

/** THINKING = a response is in flight but no audio has arrived yet (tool call /
 *  model latency) — the screen shows "Thinking…" and offers Interrupt. */
enum class VoiceState { CONNECTING, LISTENING, THINKING, SPEAKING, ERROR, CLOSED }

/**
 * The voice integrity guard's per-response bookkeeping — pure, so the "claim
 * with no tool → corrective, capped, never loops" rule is unit-testable without
 * a socket. Mirrors the web client's respTranscript / respToolCalled /
 * respWasCorrection / nextRespToolBacked / correctionsLeft and iOS
 * `VoiceIntegrityGuard` 1:1. NOT thread-safe: the client mutates it under its
 * controller lock.
 */
class VoiceIntegrityGuard {
    var transcript: String = ""
        private set
    var toolCalled: Boolean = false
        private set
    var wasCorrection: Boolean = false
        private set
    /** The reply AFTER a tool result is tool-backed. */
    var nextResponseToolBacked: Boolean = false
        private set
    /** Per-session cap — never loop. */
    var correctionsLeft: Int = 3
        private set

    fun responseCreated() {
        transcript = ""
        toolCalled = nextResponseToolBacked
        nextResponseToolBacked = false
    }

    fun bargeIn() { transcript = "" }

    fun transcriptDelta(d: String) { transcript += d }

    /** A tool ran during this response (read-only tools don't make it "tool-backed"). */
    // Tool-backed = a tool that CHANGES something (not a read, not opening a
    // screen) whose result says "ok:" — the same rule as the text harness
    // (unstuck/docs/assistant-tooling-rules.md §3, 2026-09-20).
    private fun counts(name: String): Boolean = name !in ToolRegistry.READ_ONLY && name !in ToolRegistry.NAVIGATION
    fun toolDispatched(name: String) { if (counts(name)) toolCalled = true }

    fun toolFinished(name: String, result: String) {
        nextResponseToolBacked = result.startsWith("ok:") && counts(name)
    }

    /** A cancelled/incomplete response (barge-in) is not a claim. */
    fun responseCancelled() { wasCorrection = false }

    /** Called on a COMPLETED response: true when a corrective must be sent. */
    fun shouldCorrect(): Boolean {
        if (wasCorrection) { wasCorrection = false; return false }
        if (toolCalled || correctionsLeft <= 0) return false
        if (!AssistantGuard.looksLikeActionClaim(transcript)) return false
        correctionsLeft -= 1
        wasCorrection = true
        return true
    }

    companion object {
        /** The corrective injected as a hidden user item (verbatim from the web / iOS). */
        const val correctiveText = "(integrity check from the app, not the user: you claimed an action or said you would note something, but no tool ran — nothing actually happened. If it is still needed, call the right tool NOW, then say in a few words what you did (e.g. \"Added it now\") — no apology, no explanation. Never claim an action without its tool call.)"
    }
}

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
    /** Test seam: where sockets come from (production = the shared OkHttpClient). */
    private val socketFactory: WebSocket.Factory = http,
    /** Non-null ⇒ this session is a call from Unstuck (see the file header). */
    private val callMode: CallMode? = null,
) {
    companion object {
        /** Logcat tag — `adb logcat -s voice` is the Android analog of the iOS
         *  `voice` log category the phone tests were diagnosed from. */
        const val TAG = "voice"
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
        /** Coalescing window for the response.create after tool outputs (web/iOS: 120 ms). */
        const val CONTINUE_DELAY_MS = 120L
        /** The opening reply went missing once on the iOS device (2026-09-20
         *  00:04: socket open, primer + response.create sent, nothing back —
         *  no response.created, no error — until the user spoke 6 s later;
         *  the same code had greeted in 2 s the session before). Ask again,
         *  once, if nothing has started this long after the first ask. */
        const val OPENING_WATCHDOG_MS = 2500L
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

    /** Call mode: drop captured frames instead of uploading them (audio focus lost
     *  to another app — most importantly a phone call — mutes rather than ends;
     *  the CallStyle notification's mute would land here too). Playback and the
     *  socket stay up. Read on the capture thread. */
    @Volatile var micMuted: Boolean = false

    /** The transport ended on its own — `null` for a clean server close, a
     *  message for a failure. Fires at most once, from the socket listener
     *  thread, and NEVER after [stop]. Call mode hangs up on it; the Talk
     *  screen reconnects on it when [failedBeforeAnyReply]. Set it BEFORE
     *  [start]. */
    @Volatile var onTransportEnded: ((String?) -> Unit)? = null
    @Volatile private var transportEndedFired = false

    // The barge-in state machine. Events arrive from the WS reader thread, the
    // capture thread (gate), the playback thread (drain), the main thread (route
    // change, timer, UI) — `ctlLock` serializes handle()+command execution.
    private val ctlLock = Any()
    private val ctl = BargeInController(
        profile = BargeInProfile.forRoute(audio.route),
        holdToTalk = audio.holdToTalkPref,
    ) { SystemClock.uptimeMillis() }
    /** One Runnable for every timer the controller arms; stale ticks are
     *  harmless (the controller checks the elapsed time against the CURRENT
     *  duck / pending turn), so nothing ever cancels one but stop(). */
    private val tick = Runnable { dispatch(BargeInEvent.Tick) }

    // Voice integrity guard — mutated ONLY under ctlLock (transcript deltas and
    // barge-ins arrive through execute(), tool events from the IO coroutine).
    private val guard = VoiceIntegrityGuard()
    /** Tool calls already dispatched (both event shapes can fire for one call). */
    private val handledCalls = HashSet<String>()
    /** One coalesced response.create ~120 ms after the LAST tool output — a
     *  response.create per parallel call races "already has an active response". */
    private val continueRunnable = Runnable { send(buildJsonObject { put("type", "response.create") }) }

    // Session bookkeeping, under ctlLock: any response.created seen this
    // session, whether the socket ever opened, how many opening
    // response.creates went out (the first + at most one retry), and whether
    // the server failed the session before it did anything.
    private var anyResponse = false
    private var openedOnce = false
    private var openingCreates = 0
    private var earlyFailure = false

    /** The server failed the session before ANY reply (its capacity error, or
     *  a drop after the handshake). Not an error state: the owner reconnects
     *  quietly on `onTransportEnded` (iOS `failedBeforeAnyReply`). */
    val failedBeforeAnyReply: Boolean get() = synchronized(ctlLock) { earlyFailure }

    private val openingWatchdog = Runnable {
        val retry = synchronized(ctlLock) {
            if (open && !stopped && !anyResponse && openingCreates < 2) { openingCreates++; true } else false
        }
        if (retry) {
            Log.i(TAG, "voice opening retry: no response ${OPENING_WATCHDOG_MS} ms after the first response.create")
            send(buildJsonObject { put("type", "response.create") })
        }
    }

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
            if (!open || micMuted) return@startCapture
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
        mainHandler.removeCallbacks(continueRunnable)
        mainHandler.removeCallbacks(openingWatchdog)
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
     *  stray response.cancel is answered with an error), drop every later
     *  delta of the cancelled response, and drop a pending ask (the user wants
     *  silence, not the next reply). A no-op while idle. */
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

    private fun profileFor(route: VoiceRoute) = BargeInProfile.forRoute(route)

    /** Feed one event to the controller and execute what it asks for. Returns
     *  the commands so a caller can see what was decided. */
    private fun dispatch(event: BargeInEvent, payload: String? = null): List<BargeInCommand> {
        if (stopped) return emptyList()
        val (cmds, stateAfter) = synchronized(ctlLock) {
            val c = ctl.handle(event)
            val e = ctl.lastEchoScore
            val s = "${ctl.phase} gate=${ctl.gateOpen} server=${ctl.serverSpeaking} echo=${e.hits}/${e.heard} ref=${e.spoken}"
            execute(c, payload)
            c to s
        }
        // The barge-in decisions, as on iOS: which event, what it decided.
        // Ducks/restores/cancels/asks are a handful per session; routine
        // events (audio deltas, ticks that decided nothing) stay out of the log.
        val decisive = cmds.any {
            it is BargeInCommand.Duck || it is BargeInCommand.Restore || it is BargeInCommand.SendCancel ||
                it is BargeInCommand.FlushPlayback || it is BargeInCommand.CreateResponse || it is BargeInCommand.DeleteItem
        }
        val always = event is BargeInEvent.SpeechStarted || event is BargeInEvent.SpeechStopped ||
            event is BargeInEvent.Transcription || event is BargeInEvent.InterruptPressed ||
            event is BargeInEvent.GateOpen || event is BargeInEvent.GateClose
        if (always || decisive) Log.i(TAG, "voice barge-in ${describe(event)} → ${describe(cmds)} [$stateAfter]")
        return cmds
    }

    /** Event kind (+ the transcript, as the iOS log has it — that is what the
     *  phone tests were diagnosed from) for the log line above. */
    private fun describe(event: BargeInEvent): String = when (event) {
        is BargeInEvent.SpeechStarted -> "speechStarted(${event.itemId})"
        is BargeInEvent.Transcription -> "transcription(${if (event.final) "final" else "live"}, ${event.itemId}, \"${event.text.take(80)}\")"
        is BargeInEvent.ResponseCreated -> "responseCreated(${event.id})"
        is BargeInEvent.ResponseDone -> "responseDone(${event.id}, ${event.status})"
        is BargeInEvent.AudioDelta -> "audioDelta"
        is BargeInEvent.Error -> "error"
        else -> event::class.simpleName ?: "event"
    }

    /** Command kinds only (no payloads). */
    private fun describe(cmds: List<BargeInCommand>): String {
        val kinds = cmds.mapNotNull {
            when (it) {
                BargeInCommand.Duck -> "duck"
                BargeInCommand.Restore -> "restore"
                BargeInCommand.SendCancel -> "cancel"
                is BargeInCommand.DeleteItem -> "delete-echo"
                BargeInCommand.CreateResponse -> "respond"
                is BargeInCommand.UserTurn -> "turn"
                BargeInCommand.FlushPlayback -> "flush"
                is BargeInCommand.StartTimer -> "timer${it.ms}"
                is BargeInCommand.Ui -> "ui:${it.state.name.lowercase()}"
                else -> null
            }
        }
        return if (kinds.isEmpty()) "-" else kinds.joinToString(",")
    }

    // Runs under ctlLock. `payload` is the base64 audio / caption text of the
    // delta event that produced an EnqueueAudio / ShowCaption command.
    private fun execute(cmds: List<BargeInCommand>, payload: String? = null) {
        // Keep the capture path's view of the controller current.
        audio.responseActive = ctl.responseActive
        audio.profile = ctl.profile
        for (cmd in cmds) when (cmd) {
            BargeInCommand.Duck -> audio.duck()
            BargeInCommand.Restore -> audio.restore()
            is BargeInCommand.StartTimer -> mainHandler.postDelayed(tick, cmd.ms)
            // A CANCEL (any command list carrying FlushPlayback) also resets the
            // integrity guard's transcript — a cut-off reply is never scored.
            BargeInCommand.FlushPlayback -> { audio.flushPlayback(); guard.bargeIn() }
            BargeInCommand.SendCancel -> send(buildJsonObject { put("type", "response.cancel") })
            is BargeInCommand.DeleteItem -> send(buildJsonObject { put("type", "conversation.item.delete"); put("item_id", cmd.id) })
            BargeInCommand.CreateResponse -> send(buildJsonObject { put("type", "response.create") })
            BargeInCommand.EnqueueAudio -> payload?.let { audio.enqueue(Base64.decode(it, Base64.NO_WRAP)) }
            // Only captions the controller accepts (not a cancelled reply's) count
            // towards the spoken transcript the guard scores.
            BargeInCommand.ShowCaption -> payload?.let { guard.transcriptDelta(it); onCaption("assistant", it, false) }
            // The screen clears the reply line on a "user" caption (new user turn).
            BargeInCommand.ClearCaption -> onCaption("user", "", true)
            // The user's completed words — only for a turn the controller judged
            // real; echo and coughs never reach the screen.
            is BargeInCommand.UserTurn -> onCaption("user", cmd.text, true)
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
            synchronized(ctlLock) { openedOnce = true }
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
                synchronized(ctlLock) { openingCreates = 1 }
                // A duplicate while a reply IS starting only earns an "already
                // has an active response" error, which is benign.
                mainHandler.postDelayed(openingWatchdog, OPENING_WATCHDOG_MS)
            } else primerDeleted = true
            onState(VoiceState.LISTENING)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val ev = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (ev["type"]?.jsonPrimitive?.contentOrNull) {
                // The server VAD opened a segment on this item: WHEN it began
                // (a reply on air, or not) is what a transcript is judged by.
                "input_audio_buffer.speech_started" -> dispatch(BargeInEvent.SpeechStarted(ev["item_id"]?.jsonPrimitive?.contentOrNull))
                "input_audio_buffer.speech_stopped" -> dispatch(BargeInEvent.SpeechStopped)
                "response.created" -> {
                    synchronized(ctlLock) { guard.responseCreated(); anyResponse = true }
                    dispatch(BargeInEvent.ResponseCreated(responseId(ev)))
                }
                "response.audio.delta" ->
                    ev["delta"]?.jsonPrimitive?.contentOrNull?.let { dispatch(BargeInEvent.AudioDelta(responseId(ev)), it) }
                "response.audio_transcript.delta" -> {
                    // The echo reference sees EVERY word the model produced, before
                    // the caption/cancel gate: a reply cancelled mid-air still played
                    // its first second, and that second comes back through the mic.
                    val d = ev["delta"]?.jsonPrimitive?.contentOrNull ?: ev["text"]?.jsonPrimitive?.contentOrNull
                    if (!d.isNullOrEmpty()) {
                        dispatch(BargeInEvent.AssistantTranscript(d))
                        // Captions for a cancelled reply never leak through (same id rule).
                        dispatch(BargeInEvent.TranscriptDelta(responseId(ev)), d)
                    }
                }
                "response.audio_transcript.done" -> {
                    // Belt and braces for the echo reference: the whole reply at
                    // once, in case the deltas lagged the audio (iOS device log 2026-09-19).
                    ev["transcript"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { dispatch(BargeInEvent.AssistantTranscript(it)) }
                    onCaption("assistant", "", true)
                }
                "conversation.item.input_audio_transcription.delta" -> {
                    // DashScope: `text` is the confirmed part (empty until the
                    // segment ends) and `stash` the live guess, cumulative, first
                    // word ~200 ms after speech_started; the OpenAI shape is
                    // `delta`. The guess is what lets the controller cut a reply
                    // while the user is still talking (iOS build 68).
                    val confirmed = ev["text"]?.jsonPrimitive?.contentOrNull ?: ev["delta"]?.jsonPrimitive?.contentOrNull ?: ""
                    val guess = ev["stash"]?.jsonPrimitive?.contentOrNull ?: ""
                    val piece = if (confirmed.isEmpty()) guess else confirmed + guess
                    dispatch(BargeInEvent.Transcription(piece, ev["item_id"]?.jsonPrimitive?.contentOrNull, final = false))
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    // THE decision point: the controller answers with UserTurn
                    // (caption) + CreateResponse for a real turn, or DeleteItem for
                    // echo / no words — nothing is shown or asked for those (the
                    // echo used to appear as the user's line and wipe the reply's caption).
                    val t = ev["transcript"]?.jsonPrimitive?.contentOrNull ?: ""
                    dispatch(BargeInEvent.Transcription(t, ev["item_id"]?.jsonPrimitive?.contentOrNull, final = true))
                }
                "response.done" -> {
                    // Terminal for the WHOLE reply, so it closes the caption segment
                    // too (a backend that never sends audio_transcript.done would
                    // otherwise let the next segment's deltas run into this one).
                    onCaption("assistant", "", true)
                    deletePrimer()
                    val r = ev["response"]?.jsonObject
                    val id = responseId(ev)
                    val status = r?.get("status")?.jsonPrimitive?.contentOrNull
                    val reason = r?.get("status_details")?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull ?: "-"
                    Log.i(TAG, "voice response.done status=${status ?: "nil"} reason=$reason")
                    // A cancelled/incomplete response (barge-in) is not a claim:
                    // never score it, and never inject a corrective mid-utterance.
                    if (status == null || status == "completed") checkFabrication()
                    else synchronized(ctlLock) { guard.responseCancelled() }
                    dispatch(BargeInEvent.ResponseDone(id, status))
                }
                // Audio finished streaming but the reply may still be playing/thinking
                // (tool call) — the controller decides from response.done + drain.
                "response.audio.done" -> deletePrimer()
                "response.function_call_arguments.done" -> handleToolCall(
                    name = ev["name"]?.jsonPrimitive?.contentOrNull,
                    callId = ev["call_id"]?.jsonPrimitive?.contentOrNull,
                    arguments = ev["arguments"]?.jsonPrimitive?.contentOrNull,
                )
                // Some realtime backends deliver function calls (or their name)
                // ONLY on the output_item event — dispatch from here too, deduped
                // by call_id, or calls silently never execute while the model
                // believes it acted (harness audit, 2026-09-01).
                "response.output_item.done" -> {
                    val item = ev["item"]?.jsonObject
                    if (item?.get("type")?.jsonPrimitive?.contentOrNull == "function_call") handleToolCall(
                        name = item["name"]?.jsonPrimitive?.contentOrNull,
                        callId = item["call_id"]?.jsonPrimitive?.contentOrNull,
                        arguments = item["arguments"]?.jsonPrimitive?.contentOrNull,
                    )
                }
                "error" -> {
                    val errObj = ev["error"]?.jsonObject
                    val m = errObj?.get("message")?.jsonPrimitive?.contentOrNull
                        ?: ev["error"]?.jsonPrimitive?.contentOrNull
                    // Swallow ONLY the primer-delete rejection (matched by our client
                    // event id or the primer's item id) — never a blanket "not found".
                    val evId = ev["event_id"]?.jsonPrimitive?.contentOrNull
                        ?: errObj?.get("event_id")?.jsonPrimitive?.contentOrNull
                    if (evId == PRIMER_DELETE_EVENT || m?.contains(PRIMER_ITEM_ID) == true) return
                    // A server error before ANY reply started: the session is dead
                    // on arrival (the socket closes right after). Not surfaced —
                    // the owner reconnects once or twice; only if that fails does
                    // the user see a message.
                    val early = synchronized(ctlLock) {
                        if (!anyResponse && onTransportEnded != null) { earlyFailure = true; true } else false
                    }
                    if (early) { Log.i(TAG, "voice server failed before any reply: ${m ?: "-"}"); return }
                    // "Conversation has no active response" / "already has an active
                    // response" are benign (guarded cancel raced the server) — the
                    // controller resyncs; a hold-to-talk buffer error keeps the
                    // session; anything else surfaces as ERROR.
                    dispatch(BargeInEvent.Error(m))
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            open = false
            if (stopped) return // already torn down by stop(); keep its CLOSED, don't paint an ERROR over it
            mainHandler.removeCallbacks(tick); mainHandler.removeCallbacks(openingWatchdog); audio.shutdown()
            val code = response?.code
            val body = runCatching { response?.body?.string() }.getOrNull()
            val msg = when {
                !body.isNullOrBlank() -> body.take(160)
                code != null -> "Voice server error (HTTP $code)"
                else -> t.message?.take(160) ?: "Couldn't reach the voice server"
            }
            transportEnded(msg)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            open = false
            if (stopped) return // stop() already shut audio down and reported CLOSED
            mainHandler.removeCallbacks(tick); mainHandler.removeCallbacks(openingWatchdog); audio.shutdown()
            transportEnded(null)
        }
    }

    /** The transport is gone on its own (never via stop()). Reports ONCE: with
     *  an error → onError + ERROR; a clean close → CLOSED; then
     *  `onTransportEnded`. Dead on arrival (a server error, or a drop after the
     *  handshake before any reply) with an owner hooked: no error state — the
     *  hook fires with [failedBeforeAnyReply] set and the owner reconnects, or
     *  reports if it has already tried. */
    private fun transportEnded(error: String?) {
        if (stopped || transportEndedFired) return
        transportEndedFired = true
        Log.i(TAG, "voice transport ended (${error ?: "clean close"})")
        val hook = onTransportEnded
        val early = synchronized(ctlLock) {
            if (openedOnce && !anyResponse && error != null && hook != null) earlyFailure = true
            earlyFailure
        }
        if (early && hook != null) { hook(error); return }
        if (error != null) { onError(error); onState(VoiceState.ERROR) } else onState(VoiceState.CLOSED)
        hook?.invoke(error)
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

    /** Spoken claim of a completed action with NO write tool call in that
     *  response → bounce one hidden corrective so the model acts for real and
     *  corrects itself out loud. Capped per session; never bounces a
     *  correction's own follow-up, so it cannot loop. */
    private fun checkFabrication() {
        val correct = synchronized(ctlLock) { guard.shouldCorrect() }
        if (!correct) return
        send(buildJsonObject {
            put("type", "conversation.item.create")
            putJsonObject("item") {
                put("type", "message"); put("role", "user")
                putJsonArray("content") { addJsonObject { put("type", "input_text"); put("text", VoiceIntegrityGuard.correctiveText) } }
            }
        })
        send(buildJsonObject { put("type", "response.create") })
    }

    private fun handleToolCall(name: String?, callId: String?, arguments: String?) {
        if (name == null || callId == null) return
        val fresh = synchronized(ctlLock) {
            if (!handledCalls.add(callId)) return@synchronized false   // both event shapes fired
            // Read-only tools don't make a reply "tool-backed" — otherwise
            // get_schedule + "Done, I moved it" sailed past the voice guard.
            guard.toolDispatched(name)
            true
        }
        if (!fresh) return
        val args = runCatching {
            Json.parseToJsonElement(arguments ?: "{}").jsonObject
        }.getOrDefault(JsonObject(emptyMap()))
        scope.launch {
            val result = runCatching { runCallAwareTool(name, args) }.getOrElse { "error: ${it.message ?: "failed"}" }
            // Feed the tool result back; the reply after it is tool-backed only
            // when the tool really changed something.
            send(buildJsonObject {
                put("type", "conversation.item.create")
                putJsonObject("item") {
                    put("type", "function_call_output")
                    put("call_id", callId)
                    put("output", result)
                }
            })
            synchronized(ctlLock) { guard.toolFinished(name, result) }
            scheduleContinue()
        }
    }

    /** Call mode's tool gate (iOS RealtimeCallVoiceLauncher.runCallTool): snooze
     *  is answered here and handed to the owner; a tool outside the call set never
     *  reaches the executor; everything else is the normal executor. Talk mode
     *  (no [callMode]) is untouched. */
    private suspend fun runCallAwareTool(name: String, args: JsonObject): String {
        val cm = callMode ?: return runTool(name, args)
        if (name == CallMode.SNOOZE_TOOL) {
            val minutes = CallMode.snoozeMinutes(args)
            runCatching { cm.onSnoozeCall(minutes) }
            return CallMode.snoozeResult(minutes)
        }
        if (name !in cm.allowedTools) return CallMode.notAvailable(name)
        return runTool(name, args)
    }

    /** One coalesced response.create ~120 ms after the last tool output. */
    private fun scheduleContinue() {
        if (stopped) return
        mainHandler.removeCallbacks(continueRunnable)
        mainHandler.postDelayed(continueRunnable, CONTINUE_DELAY_MS)
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
