package tech.csalliance.unstuck.calls

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import tech.csalliance.unstuck.MainActivity
import tech.csalliance.unstuck.R
import tech.csalliance.unstuck.core.logic.CallCoordinatorLogic
import tech.csalliance.unstuck.core.logic.CallEndReason
import tech.csalliance.unstuck.core.logic.CallOutcome
import tech.csalliance.unstuck.core.logic.CallScript
import tech.csalliance.unstuck.core.logic.IncomingCallPayload
import tech.csalliance.unstuck.surface.NotificationChannels
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.assistant.CallMode
import tech.csalliance.unstuck.ui.assistant.VoiceAudioEngine
import tech.csalliance.unstuck.ui.assistant.VoiceRealtimeClient
import tech.csalliance.unstuck.ui.assistant.VoiceState

// CallVoiceService — the live conversation of a call from Unstuck (C1-android;
// the Android half of iOS RealtimeCallVoiceLauncher + the answered part of
// CallCoordinator). A `microphone` foreground service that OWNS the voice
// stack — VoiceAudioEngine + VoiceRealtimeClient in call mode — for the
// duration of the call, so the socket and the mic live in a service, never in
// a Composable (risk 5), and the call survives the screen locking.
//
// START RULE (risk 3): a `microphone` FGS may not be started from the
// background on Android 14 — `ForegroundServiceStartNotAllowedException`. So
// [start] is called ONLY from the user's Answer tap (IncomingCallActivity /
// the CallStyle notification's Answer action, both user interactions), never
// from the FCM window, an alarm or a broadcast. Ringing needs no microphone.
//
//   Answer ──▶ start(): startForeground FIRST (the 5 s contract), then
//              compose the session (base voice instructions + CallScript.
//              instructions; opening = CallScript.opening carried by the hidden
//              primer, exactly like Talk; tools = the call tools + snooze_call)
//              and dial once the app-side seams (Deps) are bound — a cold-start
//              Answer waits up to LAUNCHER_GRACE_MS for AppViewModel to bind
//              them (iOS launcherGrace), and brings MainActivity up meanwhile.
//   Audio  ──▶ MODE_IN_COMMUNICATION + USAGE_VOICE_COMMUNICATION (the engine);
//              audio focus LOST → the mic is MUTED, not the call ended (risk 6);
//              focus regained → un-muted. The End action and the ring
//              notification's slot are the only ways out.
//   Tools  ──▶ snooze_call is answered inside the client (CallMode) and lands
//              here as onSnooze: outcome `snoozed` + minutes is queued AT ONCE
//              (a kill during the goodbye still snoozes), the goodbye gets
//              SNOOZE_GOODBYE_MS to play, then the session ends.
//   End    ──▶ finish(reason), ONCE per call: hung up → `done`; snoozed →
//              already reported; failed (mic / socket / proxy) → `done` with
//              outcomeNotes ["voice failed: …"] + the "here's what it was
//              about" notification (iOS performEnd 1:1). Outcomes go through
//              CallRinger.settle (first outcome wins, clears the ring state)
//              and the durable CallOutcomeStore; process death mid-call → the
//              next foreground flush reports what is queued.
class CallVoiceService : Service() {

    /** The app-side seams the conversation is built from (iOS
     *  RealtimeCallVoiceLauncher.Deps). Bound by AppViewModel via [bind]. */
    interface Deps {
        fun isVoiceConfigured(): Boolean
        fun accessToken(): String?
        val proxyUrl: String
        val model: String
        /** The assistant's voice instructions — scope guardrail + live app state. */
        suspend fun voiceInstructions(): String
        /** VOICE_TOOLS (the full registry; filtered to the call tools here). */
        fun voiceTools(): JsonArray
        /** The Talk executor. */
        suspend fun runAppTool(name: String, args: JsonObject): String
        /** Receipts land in the thread as "While we talked:" (iOS resetVoiceScratch / endVoiceSession). */
        fun sessionWillStart() {}
        fun sessionDidEnd() {}

        companion object {
            /** The production seams over the live AppViewModel. */
            fun live(vm: AppViewModel): Deps = object : Deps {
                override fun isVoiceConfigured() = vm.voiceConfigured()
                override fun accessToken() = vm.voiceAccessToken()
                override val proxyUrl: String get() = vm.voiceProxyUrl
                override val model: String get() = vm.voiceModel
                override suspend fun voiceInstructions() = vm.voiceInstructionsAsync()
                override fun voiceTools() = vm.voiceTools()
                override suspend fun runAppTool(name: String, args: JsonObject) = vm.runVoiceTool(name, args)
                override fun sessionWillStart() = vm.resetVoiceScratch()
            }
        }
    }

    private enum class Phase { STARTING, ACTIVE, ENDED }

    private var payload: IncomingCallPayload? = null
    private var phase = Phase.ENDED
    private var client: VoiceRealtimeClient? = null
    private var engine: VoiceAudioEngine? = null
    private var pendingSnoozeMin: Int? = null
    /** Bumped per call; a callback from an older session is ignored. */
    private var generation = 0
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val graceRunnable = Runnable { if (phase == Phase.STARTING) finish(CallEndReason.Failed("voice unavailable")) }
    private var snoozeEndRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Honour the startForeground()-within-5s contract UNCONDITIONALLY before
        // anything else (a redelivered null intent / an END for a dead call included).
        val incoming = intent?.let { payloadFrom(it) }
        // Belt-and-braces for the RECORD_AUDIO rule the ring screen now enforces:
        // a microphone FGS that cannot start must never take the app down with it.
        val started = runCatching { startForegroundNow(incoming ?: payload) }.isSuccess
        if (!started) {
            val p = incoming ?: payload
            if (p != null) {
                report(p, CallOutcome.DONE)
                runCatching { CallNotifications.voiceFailed(applicationContext, p) }
            }
            phase = Phase.ENDED
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_END -> {
                // An END for a call that already ended (a late shade tap): just drop the FGS.
                if (phase == Phase.ENDED) stopSelfNow() else finish(CallEndReason.HungUp)
                return START_NOT_STICKY
            }
            else -> {
                if (incoming == null) {
                    // Nothing to talk about (a null redelivery): tear straight down.
                    if (phase == Phase.ENDED) stopSelfNow()
                    return START_NOT_STICKY
                }
                begin(incoming)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // The system killed the service under a live call (or stopSelf ran):
        // settle it as hung up so the row never sits in `answered` for ever.
        if (phase != Phase.ENDED) finish(CallEndReason.HungUp)
        scope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    private fun begin(p: IncomingCallPayload) {
        val cur = payload
        if (cur != null && phase != Phase.ENDED) {
            // A retried Answer for the call that is ALREADY up: touch nothing.
            if (cur.callId == p.callId) return
            // A second call answered over a live one (should not happen — the
            // ringer reports busy): the old conversation ends as done.
            finish(CallEndReason.HungUp)
        }
        payload = p
        pendingSnoozeMin = null
        phase = Phase.STARTING
        generation++
        activeCallId = p.callId
        state = VoiceState.CONNECTING
        val d = deps
        if (d != null) {
            dial(d, p, generation)
        } else {
            // Cold-start Answer: nobody has bound the seams yet. Bring the app up so
            // AppViewModel binds them (bind() dials then), and give it a grace.
            main.removeCallbacks(graceRunnable)
            main.postDelayed(graceRunnable, LAUNCHER_GRACE_MS)
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .setData(Uri.parse(p.deepLink)),
                )
            }
        }
    }

    private fun dial(d: Deps, p: IncomingCallPayload, gen: Int) {
        main.removeCallbacks(graceRunnable)
        if (!d.isVoiceConfigured()) { finish(CallEndReason.Failed("voice not configured")); return }
        val token = d.accessToken()
        if (token.isNullOrBlank()) { finish(CallEndReason.Failed("not signed in")); return }
        scope.launch {
            // The prompt is the whole app context (a dozen Room reads) — never on main.
            val base = runCatching { withContext(Dispatchers.Default) { d.voiceInstructions() } }.getOrNull()
            withContext(Dispatchers.Main) {
                if (gen != generation || phase != Phase.STARTING) return@withContext
                if (base == null) { finish(CallEndReason.Failed("couldn't build the session")); return@withContext }
                val comp = compose(p, base, d.voiceTools())
                openSession(d, p, token, comp, gen)
            }
        }
    }

    private fun openSession(d: Deps, p: IncomingCallPayload, token: String, comp: Composition, gen: Int) {
        val audio = VoiceAudioEngine(applicationContext)
        lateinit var rc: VoiceRealtimeClient
        fun current() = gen == generation && client === rc
        rc = VoiceRealtimeClient(
            proxyUrl = d.proxyUrl, token = token, model = d.model,
            instructions = comp.instructions, tools = comp.tools, opening = comp.primer,
            audio = audio,
            runTool = { name, args -> d.runAppTool(name, args) },
            onState = { s -> main.post { if (current()) state = s } },
            onCaption = { _, _, _ -> },
            onError = { _ -> },
            callMode = CallMode(
                allowedTools = comp.toolNames.toSet(),
                onSnoozeCall = { minutes -> main.post { if (current()) snooze(minutes) } },
            ),
        )
        // Focus lost (a phone call, an alarm) → MUTE, never end; regained → un-mute.
        audio.onFocusLost = { rc.micMuted = true }
        audio.onFocusGained = { rc.micMuted = false }
        // Mic acquisition failed — the call can't proceed; degrade to the notes notification.
        audio.onCaptureError = { main.post { if (current()) finish(CallEndReason.Failed("microphone unavailable")) } }
        // The transport ended on its own (never after stop()): a clean close is the
        // model's goodbye + the server hanging up; a failure is a voice failure.
        rc.onTransportEnded = { error ->
            main.post {
                if (!current()) return@post
                if (pendingSnoozeMin != null) finish(CallEndReason.Snoozed(pendingSnoozeMin!!))
                else finish(error?.let { CallEndReason.Failed(it) } ?: CallEndReason.HungUp)
            }
        }
        engine = audio
        client = rc
        phase = Phase.ACTIVE
        runCatching { d.sessionWillStart() }
        rc.start()
    }

    /** `snooze_call` ran: report `snoozed` NOW (one outcome — a second snooze
     *  repeats the first), let the goodbye play, then end. */
    private fun snooze(minutes: Int) {
        if (phase != Phase.ACTIVE) return
        if (pendingSnoozeMin != null) return
        val m = CallCoordinatorLogic.clampSnooze(minutes)
        pendingSnoozeMin = m
        val p = payload ?: return
        report(p, CallOutcome.SNOOZED, snoozeMin = m)
        runCatching { CallNotifications.snoozed(applicationContext, p, m) }
        val r = Runnable { if (phase == Phase.ACTIVE && pendingSnoozeMin == m) finish(CallEndReason.Snoozed(m)) }
        snoozeEndRunnable = r
        main.postDelayed(r, SNOOZE_GOODBYE_MS)
    }

    /** End the call ONCE: tear the voice stack down, report, drop the FGS. */
    private fun finish(reason: CallEndReason) {
        if (phase == Phase.ENDED) return
        val p = payload
        phase = Phase.ENDED
        main.removeCallbacks(graceRunnable)
        snoozeEndRunnable?.let { main.removeCallbacks(it) }
        snoozeEndRunnable = null
        val rc = client
        val audio = engine
        client = null
        engine = null
        if (rc != null) rc.stop() else audio?.shutdown()
        runCatching { deps?.sessionDidEnd() }
        state = VoiceState.CLOSED
        activeCallId = null
        if (p != null) {
            when (reason) {
                CallEndReason.HungUp -> report(p, CallOutcome.DONE)
                // Normally reported the moment the tool ran (snooze()) — the ring state
                // closed with it; only a snooze that skipped that path reports here.
                is CallEndReason.Snoozed -> if (pendingSnoozeMin == null) report(p, CallOutcome.SNOOZED, snoozeMin = CallCoordinatorLogic.clampSnooze(reason.minutes))
                is CallEndReason.Failed -> {
                    val r = CallCoordinatorLogic.endOutcome(reason)
                    // Direct enqueue: the failure note must reach the row (settle carries none).
                    CallOutcomeStore.enqueue(applicationContext, p.callId, r.outcome, outcomeNotes = r.outcomeNotes)
                    CallRinger.clear(applicationContext)
                    runCatching { CallNotifications.voiceFailed(applicationContext, p) }
                }
            }
        }
        stopSelfNow()
    }

    /** Through the ringer's first-outcome-wins state when it still knows the
     *  call; straight into the durable queue when it doesn't (the process died
     *  and came back, or the ring state was cleared) — never lost either way. */
    private fun report(p: IncomingCallPayload, outcome: CallOutcome, snoozeMin: Int? = null) {
        val settled = runCatching { CallRinger.settle(applicationContext, p.callId, outcome, snoozeMin) }.getOrDefault(false)
        if (!settled && CallRinger.activeCallId(applicationContext) != p.callId) {
            CallOutcomeStore.enqueue(applicationContext, p.callId, outcome, snoozeMin)
        }
    }

    private fun stopSelfNow() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── notification ─────────────────────────────────────────────────────────

    private fun startForegroundNow(p: IncomingCallPayload?) {
        val n = build(p)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun build(p: IncomingCallPayload?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .setData(Uri.parse(p?.deepLink ?: "unstuck://today")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val end = PendingIntent.getService(
            this, 1, Intent(this, CallVoiceService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, NotificationChannels.FOCUS_ONGOING)
            .setSmallIcon(R.drawable.ic_orbit)
            .setColor(NotificationChannels.CORAL)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentTitle("On a call with Unstuck")
            .setContentText(p?.let { "About ${it.label}" } ?: "Connecting…")
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "End", end)
            .build()
    }

    // ── session composition (pure; tested) ───────────────────────────────────

    /** What a call's realtime session is built from. */
    data class Composition(
        /** base voice instructions + "\n\n" + CallScript.instructions. */
        val instructions: String,
        /** The verbatim first utterance (CallScript.opening). */
        val opening: String,
        /** The hidden primer carrying [opening] — sent exactly like Talk's opening. */
        val primer: String,
        /** Call tools only (+ snooze_call), realtime function shape. */
        val tools: JsonArray,
    ) {
        val toolNames: List<String> get() = tools.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
    }

    companion object {
        const val ACTION_END = "tech.csalliance.unstuck.call.END"
        /** The in-call notification. Distinct from NotifIds.CALL (the ring): the
         *  ringer cancels its own slot on Answer while this one stays up. */
        const val NOTIF_ID = tech.csalliance.unstuck.surface.NotifIds.CALL_VOICE
        /** How long a cold-start Answer waits for AppViewModel to bind the seams
         *  before degrading (iOS launcherGrace). */
        const val LAUNCHER_GRACE_MS = 8_000L
        /** After snooze_call: room for "call you back in ten — bye" to play. */
        const val SNOOZE_GOODBYE_MS = 6_000L

        @Volatile private var instance: CallVoiceService? = null
        @Volatile private var deps: Deps? = null

        /** The call the service is running (null = idle). */
        @Volatile var activeCallId: String? = null
            private set
        /** The conversation's state, for a screen that wants to show it. */
        @Volatile var state: VoiceState = VoiceState.CLOSED
            private set

        /**
         * Start the conversation for an ANSWERED call. MUST be called from a user
         * interaction — the Answer tap in IncomingCallActivity or the CallStyle
         * notification's Answer action — never from onMessageReceived, an alarm or
         * a receiver: a `microphone` foreground service started from the background
         * throws ForegroundServiceStartNotAllowedException on Android 14. The caller
         * has already settled the ring as `answered` (CallRinger.settle).
         */
        fun start(context: Context, payload: IncomingCallPayload) {
            val i = Intent(context, CallVoiceService::class.java)
            payload.toData().forEach { (k, v) -> i.putExtra(EXTRA_PREFIX + k, v) }
            context.startForegroundService(i)
        }

        /** End the live call (the in-app screen's End; the notification uses the
         *  same action). A no-op when nothing is up. */
        fun end(context: Context) {
            if (instance == null) return
            runCatching { context.startService(Intent(context, CallVoiceService::class.java).setAction(ACTION_END)) }
        }

        /** Bind the app-side seams (AppViewModel init: `CallVoiceService.bind(
         *  CallVoiceService.Deps.live(this))`). A call answered while nothing was
         *  bound (cold start) starts speaking now. */
        fun bind(d: Deps) {
            deps = d
            main().post { instance?.let { s -> if (s.phase == Phase.STARTING) s.payload?.let { p -> s.dial(d, p, s.generation) } } }
        }

        /** Unbind (the ViewModel is cleared). A live call keeps its own client. */
        fun unbind(d: Deps) { if (deps === d) deps = null }

        private fun main() = Handler(Looper.getMainLooper())

        private const val EXTRA_PREFIX = "p."

        /** The payload carried by a start intent (one string extra per key). */
        fun payloadFrom(intent: Intent): IncomingCallPayload? {
            val extras = intent.extras ?: return null
            val data = HashMap<String, String>()
            for (k in extras.keySet()) {
                if (!k.startsWith(EXTRA_PREFIX)) continue
                val v = extras.getString(k) ?: continue
                data[k.removePrefix(EXTRA_PREFIX)] = v
            }
            if (data.isEmpty()) return null
            return IncomingCallPayload.fromData(data)
        }

        /** instructions = base + call script; opening = CallScript.opening; primer
         *  wraps the opening; tools = call tools only (+ snooze_call). Port of iOS
         *  RealtimeCallVoiceLauncher.compose. */
        fun compose(p: IncomingCallPayload, baseInstructions: String, voiceTools: JsonArray, nowMs: Long = System.currentTimeMillis()): Composition {
            val opening = CallScript.opening(p, nowMs = nowMs)
            return Composition(
                instructions = baseInstructions + "\n\n" + CallScript.instructions(p, nowMs = nowMs),
                opening = opening,
                primer = primer(opening),
                tools = callToolSchemas(voiceTools),
            )
        }

        /** The hidden primer: the model must speak the opening verbatim, once. */
        fun primer(opening: String): String =
            "(The call just connected — YOU rang them, this is not the user speaking. Say EXACTLY this now, word for word, before anything else, then listen: \"$opening\" This opening happens ONCE — after any interruption continue the conversation naturally; never repeat it.)"

        /** The VOICE_TOOLS schemas filtered to CallScript.callTools (in that
         *  order), snooze_call always from [snoozeCallSchema], update_call from
         *  [updateCallSchema] when the registry doesn't carry one. */
        fun callToolSchemas(voiceTools: JsonArray): JsonArray {
            val byName = LinkedHashMap<String, JsonObject>()
            for (t in voiceTools) {
                val o = t as? JsonObject ?: continue
                val n = o["name"]?.jsonPrimitive?.contentOrNull ?: continue
                if (n !in byName) byName[n] = o
            }
            return buildJsonArray {
                for (name in CallScript.callTools()) {
                    val schema = when {
                        name == CallMode.SNOOZE_TOOL -> snoozeCallSchema
                        byName[name] != null -> byName[name]!!
                        name == "update_call" -> updateCallSchema
                        else -> null
                    }
                    if (schema != null) add(schema)
                }
            }
        }

        /** Call-level: "call me back in ten". `{minutes: integer, default 10}` (iOS verbatim). */
        val snoozeCallSchema: JsonObject = buildJsonObject {
            put("type", "function"); put("name", CallMode.SNOOZE_TOOL)
            put("description", "\"Call me back in ten\" — hang up now and ring again in `minutes`. Say the minutes out loud, then a quick goodbye.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("minutes") { put("type", "integer"); put("description", "Minutes until the call-back (1–180)."); put("default", CallMode.DEFAULT_SNOOZE_MIN) }
                }
                putJsonArray("required") {}
            }
        }

        /** Mirrors tools.ts `update_call` (used when the registry lacks it). */
        val updateCallSchema: JsonObject = buildJsonObject {
            put("type", "function"); put("name", "update_call")
            put("description", "Change this call's notes for later (replaces them, verbatim), or its label/time.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("callId") { put("type", "string"); put("description", "The call id from the call context.") }
                    putJsonObject("notes") { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "Replaces the notes, verbatim.") }
                    putJsonObject("label") { put("type", "string"); put("description", "New label.") }
                    putJsonObject("when") { put("type", "string"); put("description", "New local 'YYYY-MM-DD HH:MM'.") }
                }
                putJsonArray("required") { add("callId") }
            }
        }
    }
}
