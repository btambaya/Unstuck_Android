package tech.csalliance.unstuck.ui.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.csalliance.unstuck.SettingsStore
import tech.csalliance.unstuck.calls.CallVoiceService
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

/**
 * The live Talk session, hosted OUTSIDE the composition so a configuration
 * change (rotation, fold/unfold, dark-mode or locale switch — MainActivity
 * declares no configChanges) never tears the call down: the Activity is
 * recreated, the composition is rebuilt, and [VoiceModeScreen] simply
 * re-attaches to the same engine/client/state. Scoped to the Activity's
 * ViewModelStore (the same owner AppViewModel lives in).
 *
 * Lifecycle contract:
 *  - [ensureStarted] starts a session only when none is live (re-attach is a no-op);
 *  - [detach] on a plain dispose ends the session; on a config change it arms a
 *    short re-attach deadline instead — if no screen comes back (the host didn't
 *    restore the sheet), the session is ended rather than left as a headless call;
 *  - [end] is the ONLY real teardown (user left, focus lost, backgrounded);
 *  - a session the server fails BEFORE ANY REPLY (its capacity error — iOS
 *    device 2026-09-20 00:41: "Socket is not connected" on the first try,
 *    fine on the second) is reconnected quietly on a fresh engine + client,
 *    twice at most, before the user sees anything (iOS build 70 parity).
 */
class VoiceSessionHolder(private val appContext: Context) : ViewModel() {
    private val main = Handler(Looper.getMainLooper())
    private val settingsStore = SettingsStore(appContext)

    var state by mutableStateOf(VoiceState.CONNECTING); private set
    var caption by mutableStateOf(""); private set
    var note by mutableStateOf<String?>(null); private set
    // Hold-to-talk: the engine + client read the pref once per session (the
    // engine's snapshot is what the client's controller was built from); this
    // mirrors it for the orb/label and flips with the noisy-room chip.
    var holdToTalk by mutableStateOf(false); private set
    // 3 false barge-ins inside 2 min → one-tap offer to switch to hold to talk.
    var suggestHoldToTalk by mutableStateOf(false); private set

    private var audio: VoiceAudioEngine? = null
    /** Set when a session actually dials: the receipts of everything the voice
     *  assistant wrote are appended to the assistant thread as a local
     *  "While we talked:" turn when it ends (AppViewModel.endVoiceSession, the
     *  same entry CallVoiceService.Deps.sessionDidEnd uses). Held as a lambda
     *  rather than an AppViewModel reference so the holder never outlives it. */
    private var sessionDidEnd: (() -> Unit)? = null
    // Compose state: the screen derives `live`/`canInterrupt` from it.
    var client: VoiceRealtimeClient? by mutableStateOf(null); private set
    private var attached = false
    private val reattachDeadline = Runnable { if (!attached) end() }
    /** A start is in flight (its prompt is still being read off the main thread). */
    private var starting = false
    /** Bumped by [end] so a start still building its prompt aborts instead of
     *  dialling into a session the user already left. */
    private var startEpoch = 0
    /** Quiet reconnects after the server failed the session before any reply
     *  (VoiceRealtimeClient.failedBeforeAnyReply). Per user-initiated start. */
    private var reconnects = 0
    private var reconnectRunnable: Runnable? = null

    /** A client exists and hasn't been stop()ped (CONNECTING counts — the dial is in flight). */
    val sessionActive: Boolean get() = client?.let { !it.isStopped } == true

    /** Screen composed. Cancels a pending re-attach deadline; a fresh (non-live)
     *  holder is reset so a previous session's "Ended"/error never shows first. */
    fun attach() {
        attached = true
        main.removeCallbacks(reattachDeadline)
        if (!sessionActive) {
            state = VoiceState.CONNECTING; caption = ""; note = null; suggestHoldToTalk = false
            // The switch shows the saved choice before the session dials.
            holdToTalk = settingsStore.voiceHoldToTalk()
        }
    }

    /** Screen leaving the composition. [changingConfigurations] = the Activity is
     *  being recreated and the screen will re-attach in a moment. */
    fun detach(changingConfigurations: Boolean) {
        attached = false
        if (!changingConfigurations) { end(); return }
        main.removeCallbacks(reattachDeadline)
        main.postDelayed(reattachDeadline, REATTACH_GRACE_MS)
    }

    fun switchToHoldToTalk() = setHoldToTalkPref(true)

    /** The Talk screen's "Noisy room? Hold to talk" switch — the device key
     *  Settings used to hold (voice.holdToTalk; slim settings moved it here,
     *  2026-09-24). A live session switches at once, without reconnecting. */
    fun setHoldToTalkPref(on: Boolean) {
        settingsStore.setVoiceHoldToTalk(on)
        client?.setHoldToTalk(on)
        holdToTalk = on
        suggestHoldToTalk = false
    }

    fun fail(message: String) { note = message; state = VoiceState.ERROR }

    /** The client's state report (main thread). A reply is coming: whatever
     *  went wrong before is over, so SPEAKING clears the note (parity with
     *  iOS build 78). */
    internal fun clientState(s: VoiceState) {
        if (s == VoiceState.SPEAKING) note = null
        state = s
    }

    /** The client's error report (main thread). Also sent while the session
     *  stays LIVE — a rate-limited reply's "busy" — so the screen shows the
     *  note under the status line then, not only in the ERROR state. */
    internal fun clientError(message: String) { note = message }

    /** Is a call FROM Unstuck live right now? A seam so the gate below is
     *  unit-testable (CallVoiceService.activeCallId has a private setter). */
    internal var isCallActive: () -> Boolean = { CallVoiceService.activeCallId != null }

    /**
     * One voice conversation at a time. A call from Unstuck owns the
     * microphone, the comm-mode audio focus and the tool scratch
     * (CallVoiceService, a live `microphone` FGS). A Talk session started over
     * it would take exclusive focus off the call (muting the call's mic with no
     * un-mute in sight), reset the SAME `voiceScratch` the call's conversation
     * is mid-way through, and burn the proxy's second per-user socket — two
     * assistants talking over each other. Refuse, and say why.
     *
     * @return true when the start was refused.
     */
    fun refuseWhileOnCall(): Boolean {
        if (!isCallActive()) return false
        fail(ON_A_CALL)
        return true
    }

    /** Start a session unless one is live (re-attach after a config change). */
    fun ensureStarted(vm: AppViewModel) {
        if (sessionActive || starting) return
        if (refuseWhileOnCall()) return
        val token = vm.voiceAccessToken()
        if (token.isNullOrBlank()) { fail("Please sign in to use voice."); return }
        if (!vm.voiceConfigured()) { fail("Voice isn't set up yet."); return }
        note = null; caption = ""; state = VoiceState.CONNECTING
        reconnects = 0
        vm.resetVoiceScratch()
        // The session prompt IS the whole app context (tasks, blocks, captures,
        // lists, circle, profile facts) — a dozen Room reads. The blocking
        // voiceInstructions()/voiceOpening() would run all of that on the main
        // thread from the mic tap; read it on viewModelScope and dial after.
        starting = true
        val epoch = startEpoch
        viewModelScope.launch {
            // viewModelScope is Main.immediate; the builders are suspending but
            // decode whole Room tables on the caller's dispatcher — hop to
            // Default so the prompt build never runs on the main thread. The
            // opening carries the first-contact interview branch (no saved
            // facts yet) and the name-once / no-name rules from the builder.
            val prompt = runCatching {
                withContext(Dispatchers.Default) { vm.voiceInstructionsAsync() to vm.voiceOpeningAsync() }
            }.getOrNull()
            starting = false
            if (epoch != startEpoch || sessionActive) return@launch   // ended (or restarted) while reading
            if (prompt == null) { fail("Couldn't start voice — try again."); return@launch }
            dial(vm, token, prompt.first, prompt.second)
        }
    }

    private fun dial(vm: AppViewModel, token: String, instructions: String, opening: String) {
        val engine = VoiceAudioEngine(appContext)
        audio = engine
        holdToTalk = engine.holdToTalkPref
        suggestHoldToTalk = false
        // Every callback is bound to THIS client and ignored once it is no longer
        // the holder's current one — a late CLOSED/ERROR from an ended session must
        // never paint over the next session's state.
        lateinit var rc: VoiceRealtimeClient
        fun current() = client === rc
        rc = VoiceRealtimeClient(
            proxyUrl = vm.voiceProxyUrl, token = token, model = vm.voiceModel,
            instructions = instructions, tools = vm.voiceTools(), opening = opening,
            audio = engine,
            runTool = { name, args -> vm.runVoiceTool(name, args) },
            onState = { s -> main.post { if (current()) clientState(s) } },
            onError = { msg -> main.post { if (current()) clientError(msg) } },
            onCaption = { role, text, done ->
                main.post {
                    if (!current()) return@post
                    when {
                        role == "user" -> caption = "" // new user turn → clear the reply line
                        role == "assistant" && !done -> caption += text
                    }
                }
            },
            // Already posted to the main thread by the client.
            onSuggestHoldToTalk = { if (current()) suggestHoldToTalk = true },
            // Every dial — the start and each quiet reconnect — resolves a token
            // that outlives the session; `token` is only the fallback (parity
            // with iOS build 81, audit 2026-09-22 C14).
            freshToken = { force -> vm.freshVoiceAccessToken(force) },
        )
        sessionDidEnd = { vm.endVoiceSession() }
        // Another app (most importantly an incoming phone call) took audio focus →
        // end the session instead of talking over it, transient or not. stop()
        // reports CLOSED ("Ended"). (Only a CALL from Unstuck mutes-and-waits.)
        engine.onFocusLost = { _ -> rc.stop() }
        // May fire SYNCHRONOUSLY from inside rc.start() (mic held elsewhere): the
        // client checks `stopped` after startCapture and never dials in that case.
        engine.onCaptureError = {
            rc.stop()
            main.post { if (current()) fail("Couldn't access the microphone — it may be in use by another app.") }
        }
        // Dead on arrival (the server failed before any reply — the provider's
        // capacity, not the user's problem): reconnect on a fresh engine +
        // client, twice at most, before telling the user anything. Any other
        // transport end was already reported by the client (ERROR / CLOSED).
        rc.onTransportEnded = { error ->
            main.post {
                if (!current() || !rc.failedBeforeAnyReply) return@post
                if (reconnects < 2) {
                    reconnects += 1
                    Log.i(VoiceRealtimeClient.TAG, "voice reconnect #$reconnects: the server failed before any reply (${error ?: "closed"})")
                    client = null          // rc's late CLOSED/ERROR reports are ignored from here
                    rc.stop()
                    state = VoiceState.CONNECTING
                    val epoch = startEpoch
                    val r = Runnable {
                        reconnectRunnable = null
                        if (epoch != startEpoch || sessionActive) return@Runnable   // ended (or restarted) meanwhile
                        dial(vm, token, instructions, opening)
                    }
                    reconnectRunnable = r
                    main.postDelayed(r, RECONNECT_DELAY_MS)
                } else {
                    note = DROPPED_TWICE
                    state = VoiceState.ERROR
                }
            }
        }
        client = rc
        rc.start()
    }

    /** Real teardown. stop() tears audio down too (off the main thread); shutdown()
     *  directly only covers the never-started case, where it's a cheap no-op sweep. */
    fun end() {
        main.removeCallbacks(reattachDeadline)
        reconnectRunnable?.let { main.removeCallbacks(it) }
        reconnectRunnable = null
        startEpoch++          // abort a start whose prompt is still being read (or a reconnect)
        val rc = client
        val engine = audio
        val didEnd = sessionDidEnd
        client = null
        audio = null
        sessionDidEnd = null
        if (rc != null) {
            rc.stop()
            // rc's own CLOSED report is ignored now that it isn't current — say it here
            // (an ON_STOP end leaves the screen up, and it must read "Ended").
            state = VoiceState.CLOSED
        } else engine?.shutdown()
        // Everything the voice assistant wrote lands in the thread with Undo —
        // the receipt IS the consent UX (web overlay close / iOS endVoiceSession).
        if (didEnd != null) runCatching { didEnd() }
    }

    override fun onCleared() { end() }

    companion object {
        /** How long a detached session waits for the recreated screen before ending itself. */
        const val REATTACH_GRACE_MS = 2_000L
        /** Refusal shown when Talk is opened while a call FROM Unstuck is live. */
        const val ON_A_CALL = "You’re on a call with Unstuck."
        /** Pause between a dead-on-arrival session and its quiet reconnect (iOS: 800 ms). */
        const val RECONNECT_DELAY_MS = 800L
        /** Shown only after the second quiet reconnect failed too. */
        const val DROPPED_TWICE = "The voice server dropped the session twice. Please try again in a moment."

        /** The note shown UNDER the status line: any state but ERROR, which
         *  already shows the note AS the status line. Without it the one
         *  message written for a rate-limited reply was never rendered and the
         *  orb just kept pulsing — "it just went quiet" (parity with iOS build
         *  78, audit 2026-09-21). */
        fun liveNote(state: VoiceState, note: String?): String? =
            note?.takeIf { state != VoiceState.ERROR && it.isNotEmpty() }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Full-screen voice mode — live speech-to-speech with Qwen-Omni (through the
 * Cloudflare proxy). Talk naturally; it listens, reasons, runs your scheduling
 * tools, and speaks back, with barge-in. Shows the current state + live caption.
 *
 * Two ways to talk (spec bargein.md §6/§8):
 *  - open mic (default): the server VAD + RMS gate handle turns; tapping the orb
 *    or the Interrupt pill hard-cancels a reply — offered ONLY while the model is
 *    responding or its speech is still playing (client.canInterrupt);
 *  - hold to talk (the "Noisy room? Hold to talk" switch, or the one-tap "Noisy room?" chip the
 *    client raises after 3 false barge-ins in 2 min): press and hold the orb to
 *    speak, release to send. The label shows the mode.
 *
 * The session itself lives in [VoiceSessionHolder] (a ViewModel), so this
 * composable is disposable: rotation / fold / theme / locale changes rebuild it
 * and it re-attaches to the live call.
 */
@Composable
fun VoiceModeScreen(vm: AppViewModel, onClose: () -> Unit) {
    val c = UTheme.colors
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val appContext = context.applicationContext
    val holder: VoiceSessionHolder = viewModel { VoiceSessionHolder(appContext) }

    val state = holder.state
    val caption = holder.caption
    val note = holder.note
    val holdToTalk = holder.holdToTalk
    val suggestHoldToTalk = holder.suggestHoldToTalk
    // Transient gesture state: a press in progress is released by the gesture's
    // own finally{} when the composition goes away, so it needn't survive.
    var holding by remember { mutableStateOf(false) }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) holder.ensureStarted(vm) else holder.fail("Microphone access is needed for voice.")
    }

    // Attach/detach: a plain dispose (X / End / sheet closed) ends the call; a
    // configuration change keeps it alive for the recreated screen. Declared
    // BEFORE the start effect: attach() resets a non-live holder's stale state,
    // and must not wipe a synchronous start failure (no token) reported below.
    DisposableEffect(holder) {
        holder.attach()
        onDispose { holder.detach(changingConfigurations = activity?.isChangingConfigurations == true) }
    }
    LaunchedEffect(holder) {
        if (holder.sessionActive) return@LaunchedEffect // re-attached to a live call
        // A call from Unstuck already owns the mic — say so instead of popping a
        // permission dialog over the conversation (ensureStarted refuses too).
        if (holder.refuseWhileOnCall()) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) holder.ensureStarted(vm) else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    // Graceful end on Home/lock — without a microphone foreground service Android
    // silences capture in the background, so a backgrounded session would be a
    // one-way zombie call (speaker + socket + comm-mode left alive). ON_STOP also
    // fires during a config-change recreation — that one is NOT an exit.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true) holder.end()
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Keep the screen awake while the call is live so the lock screen doesn't
        // cut the session mid-conversation. (The dialog has its own window.)
        val view = LocalView.current
        // A server-side error (ERROR while the socket is still open) leaves the
        // session usable — keep the orb's gesture so the user can talk again
        // instead of a dead screen whose only exit is End. Transport failures
        // close the socket, so they still fall out of `live`.
        val live = state == VoiceState.LISTENING || state == VoiceState.THINKING || state == VoiceState.SPEAKING ||
            (state == VoiceState.ERROR && holder.client?.isOpen == true)
        val sessionLive = state == VoiceState.CONNECTING || live
        DisposableEffect(sessionLive) {
            view.keepScreenOn = sessionLive
            onDispose { view.keepScreenOn = false }
        }
        Box(Modifier.fillMaxSize().background(c.bg)) {
            // Close (X)
            Box(
                Modifier.align(Alignment.TopEnd).padding(18.dp).size(40.dp).clip(CircleShape)
                    .background(c.bg2).clickable(role = Role.Button) { onClose() }
                    .semantics { contentDescription = "Close voice mode" },
                contentAlignment = Alignment.Center,
            ) { Text("✕", Modifier.clearAndSetSemantics {}, style = UFont.sans(18), color = c.ink2) }

            Column(
                Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // Interrupt is a hard cancel: offered only while a response is in
                // flight or its speech is still playing (spec §6). The state
                // transitions that flip `speaking` are the same ones that update
                // `state`, so reading canInterrupt on recomposition is current.
                val canInterrupt = (state == VoiceState.THINKING || state == VoiceState.SPEAKING) && holder.client?.canInterrupt == true
                val orbGesture = when {
                    !live -> Modifier
                    // Hold to talk: press = cancel any reply + open the gate; release = send.
                    holdToTalk -> Modifier
                        .semantics { role = Role.Button; contentDescription = if (holding) "Release to send" else "Hold to talk" }
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                holding = true
                                holder.client?.pttDown()
                                try { tryAwaitRelease() } finally { holder.client?.pttUp(); holding = false }
                            })
                        }
                    canInterrupt -> Modifier.clickable(role = Role.Button) { holder.client?.interrupt() }
                        .semantics { contentDescription = "Interrupt assistant" }
                    else -> Modifier
                }
                PulsingOrb(
                    active = state == VoiceState.SPEAKING || state == VoiceState.THINKING ||
                        (state == VoiceState.LISTENING && (!holdToTalk || holding)),
                    color = if (state == VoiceState.SPEAKING) c.coral else c.primary,
                    gesture = orbGesture,
                )
                Text(
                    when (state) {
                        VoiceState.CONNECTING -> "Connecting…"
                        VoiceState.LISTENING -> if (holdToTalk) (if (holding) "Listening…" else "Hold to talk") else "Listening…"
                        VoiceState.THINKING -> "Thinking…"
                        VoiceState.SPEAKING -> "Speaking…"
                        VoiceState.ERROR -> note ?: "Something went wrong."
                        VoiceState.CLOSED -> "Ended"
                    },
                    // Announce state changes (Listening/Thinking/Speaking/errors) to TalkBack.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = UFont.sans(15, FontWeight.Medium), color = c.ink2,
                )
                // A note while the session is still LIVE (the error state already
                // shows it as the status line above) — a rate-limited reply's "busy".
                VoiceSessionHolder.liveNote(state, note)?.let {
                    Text(
                        it, style = UFont.sans(14), color = c.ink3, textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (caption.isNotBlank()) {
                    Text(caption, style = UFont.serifItalic(22), color = c.ink, textAlign = TextAlign.Center)
                }
                // Manual interrupt — cut the model's speech and let the user talk.
                if (canInterrupt) {
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(c.bg2)
                            .clickable(role = Role.Button) { holder.client?.interrupt() }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    ) { Text("Interrupt", style = UFont.sans(15, FontWeight.SemiBold), color = c.ink) }
                }
                // Noisy-room fallback offer (spec §8): the client saw 3 duck→restore
                // cycles inside 2 minutes. One tap persists the pref + switches the
                // live session (turn_detection: null) without reconnecting.
                if (suggestHoldToTalk && !holdToTalk && live) {
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(c.bg2)
                            .clickable(role = Role.Button) { holder.switchToHoldToTalk() }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    ) { Text("Noisy room? Switch to hold to talk", style = UFont.sans(13, FontWeight.Medium), color = c.ink2) }
                }
            }

            // "Noisy room? Hold to talk" — the one place this choice lives now.
            // The whole pill is the switch for TalkBack; the inner toggle is decorative.
            androidx.compose.foundation.layout.Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 116.dp)
                    .clip(RoundedCornerShape(999.dp)).background(c.bg2)
                    .toggleable(value = holdToTalk, role = Role.Switch) { holder.setHoldToTalkPref(it) }
                    .padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(TALK_HOLD_TO_TALK, style = UFont.sans(13, FontWeight.Medium), color = c.ink2)
                tech.csalliance.unstuck.design.component.MdToggle(holdToTalk, { holder.setHoldToTalkPref(it) }, Modifier.clearAndSetSemantics {})
            }

            // End button
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)
                    .clip(RoundedCornerShape(999.dp)).background(c.coral)
                    .clickable(role = Role.Button) { onClose() }
                    .padding(horizontal = 28.dp, vertical = 14.dp),
            ) { Text("End", style = UFont.sans(15, FontWeight.SemiBold), color = Color.White) }
        }
    }
}

/** The orb. [gesture] carries the mode's interaction (tap-to-interrupt or
 *  press-and-hold) plus its TalkBack label; empty when nothing is offered. */
@Composable
private fun PulsingOrb(active: Boolean, color: Color, gesture: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "orb")
    val scale by transition.animateFloat(
        initialValue = 1f, targetValue = if (active) 1.15f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "scale",
    )
    Box(
        Modifier.size(120.dp).scale(scale).clip(CircleShape).background(color).then(gesture),
        contentAlignment = Alignment.Center,
        // The glyph is decorative — keep TalkBack on the orb's label, not "●".
    ) { Text("●", Modifier.clearAndSetSemantics {}, style = UFont.sans(36), color = Color.White.copy(alpha = 0.9f)) }
}

/** The Talk screen's hold-to-talk switch label (iOS / web: the same words). */
internal const val TALK_HOLD_TO_TALK = "Noisy room? Hold to talk"
