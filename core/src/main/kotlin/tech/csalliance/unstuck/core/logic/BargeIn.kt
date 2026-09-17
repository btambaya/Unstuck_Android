package tech.csalliance.unstuck.core.logic

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// BargeIn — the pure, headless half of realtime-voice barge-in. Mirrors
// lib/voice/barge-in.ts (web) and App/Voice/BargeInController.swift (iOS) so
// the three platforms stay in lock-step (same 12 unit cases).
//
//   * BargeInController: a state machine fed with transport/audio EVENTS plus a
//     monotonic clock; it emits COMMANDS the transport + audio layers execute
//     (duck/restore the player, flush, send response.cancel, mute stale deltas,
//     drive the UI state). "Duck-and-confirm": the first hint of the user
//     talking over the model only ducks the playback (-12 dB); the reply is
//     cancelled only once the barge-in is CONFIRMED — and a confirm needs BOTH
//     sides: the server VAD inside a speech segment AND the mic still above the
//     gate at the tick (or a transcription while the gate is open, or the
//     server agreeing with a gate duck). The timer alone confirms nothing
//     (2026-09-17): speech_stopped can only arrive after 600 ms of silence, so
//     a timer-only confirm cut every reply on any loudspeaker noise. A blip
//     ducks then restores without cancelling (and the reply the server makes
//     from a committed blip is cancelled on creation).
//   * RmsGate: the client-side energy gate that runs in the capture path (20 ms
//     sub-frames). Calibrates a noise floor, opens with hysteresis, flushes a
//     300 ms pre-roll so the onset isn't clipped, and emits DIGITAL SILENCE while
//     closed (the server's silence_duration_ms timer has to observe silence).
//     On the loudspeaker it is HELD CLOSED for the whole reply (half-duplex —
//     BargeInProfile.gateForcedClosed): the reply's own echo re-entering the
//     mic was transcribed, cancelled the reply, then got answered.
//
// No Android, no network — every function here is deterministic.

/** Output route class. Drives the server-VAD threshold + client confirm timing. */
enum class VoiceRoute { LOW_ECHO, SPEAKER }

/**
 * Per-route tuning (spec §1). [assistedDuplex] = upload mic frames THROUGH the
 * gate at [gateMarginPlayingDb] while the model plays, so the server VAD can
 * barge in (full duplex — earphones / Bluetooth, which run their own AEC).
 * `false` = HALF-DUPLEX while the model is audible: the gate is held closed
 * (digital silence up, pre-roll dropped) from response.created until the
 * playback has drained, and the Interrupt button is the way to cut a reply.
 * Hold-to-talk overrides it (the press opens the gate).
 *
 * Half-duplex is the loudspeaker DEFAULT (2026-09-17, iOS
 * `BargeInProfile.halfDuplexWhilePlaying`): with the gate open, the reply's
 * own echo re-entered the mic, the server VAD + transcription called it
 * speech, cancelled the reply and then ANSWERED the echo. The assisted-duplex
 * speaker profile stays available as an opt-in for devices whose AEC proves
 * good enough.
 */
data class BargeInProfile(
    val route: VoiceRoute,
    val threshold: Double,
    val confirmMs: Long,
    val gateMarginDb: Double,
    val gateMarginPlayingDb: Double,
    val assistedDuplex: Boolean,
) {
    fun gateMargin(playbackQueued: Boolean): Double = if (playbackQueued) gateMarginPlayingDb else gateMarginDb

    /** The mic upload is muted while the model is audible (iOS `halfDuplexWhilePlaying`). */
    val halfDuplexWhilePlaying: Boolean get() = !assistedDuplex

    companion object {
        /** Wired / BT headset, phone receiver: T 0.5, confirm 200 ms, +6 dB, full duplex. */
        val LOW_ECHO = BargeInProfile(VoiceRoute.LOW_ECHO, 0.5, 200L, 6.0, 6.0, assistedDuplex = true)
        /** Built-in loudspeaker: T 0.6, confirm 300 ms, +9 dB while playing, +6
         *  otherwise — and HALF-DUPLEX while the model is audible (the default). */
        val SPEAKER = BargeInProfile(VoiceRoute.SPEAKER, 0.6, 300L, 6.0, 9.0, assistedDuplex = false)
        /** The same speaker tuning, spelled out. */
        val SPEAKER_HALF_DUPLEX = SPEAKER
        /** Opt-in: talk-over on the loudspeaker for a device whose AEC leaves no residual echo. */
        val SPEAKER_ASSISTED_DUPLEX = SPEAKER.copy(assistedDuplex = true)

        fun forRoute(route: VoiceRoute, speakerHalfDuplex: Boolean = true): BargeInProfile = when (route) {
            VoiceRoute.LOW_ECHO -> LOW_ECHO
            VoiceRoute.SPEAKER -> if (speakerHalfDuplex) SPEAKER_HALF_DUPLEX else SPEAKER_ASSISTED_DUPLEX
        }

        /** Whether the capture gate must be held closed right now (iOS
         *  `GateContext.forcedClosed`): the half-duplex profile, the model busy
         *  (a reply in flight OR audio still queued — the queue runs dry for a
         *  moment at the start of a reply and between bursts, and each gap let
         *  the gate open on room noise), and no hold-to-talk press overriding. */
        fun gateForcedClosed(profile: BargeInProfile, modelBusy: Boolean, pttPressed: Boolean): Boolean =
            profile.halfDuplexWhilePlaying && modelBusy && !pttPressed
    }
}

/** The `turn_detection` block of session.update. `null` = hold-to-talk (client commits). */
data class TurnDetection(
    val type: String = "server_vad",
    val threshold: Double,
    val prefixPaddingMs: Int = PREFIX_PADDING_MS,
    val silenceDurationMs: Int = SILENCE_DURATION_MS,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("type", type)
        put("threshold", threshold)
        put("prefix_padding_ms", prefixPaddingMs)
        put("silence_duration_ms", silenceDurationMs)
    }

    companion object {
        /** Matches the 300 ms client pre-roll flushed when the gate opens. */
        const val PREFIX_PADDING_MS = 300
        /** Inside the doc's 500–600 ms recommendation for snappy turn-taking. */
        const val SILENCE_DURATION_MS = 600

        fun forProfile(profile: BargeInProfile, holdToTalk: Boolean, semantic: Boolean = false): TurnDetection? =
            if (holdToTalk) null
            else TurnDetection(type = if (semantic) "semantic_vad" else "server_vad", threshold = profile.threshold)

        /** JSON to put under `session.turn_detection` (JsonNull for hold-to-talk). */
        fun json(td: TurnDetection?) = td?.toJson() ?: JsonNull
    }
}

enum class BargeInPhase { IDLE, SPEAKING, DUCKED, HOLD }
enum class BargeInUi { LISTENING, THINKING, SPEAKING }
enum class DuckTrigger { GATE, SERVER }

sealed class BargeInEvent {
    data class ResponseCreated(val id: String?) : BargeInEvent()
    data class AudioDelta(val id: String?) : BargeInEvent()
    data class TranscriptDelta(val id: String?) : BargeInEvent()
    data class ResponseDone(val id: String?, val status: String? = null) : BargeInEvent()
    object PlaybackDrained : BargeInEvent()
    object SpeechStarted : BargeInEvent()
    object SpeechStopped : BargeInEvent()
    object TranscriptionDelta : BargeInEvent()
    object TranscriptionCompleted : BargeInEvent()
    object GateOpen : BargeInEvent()
    object GateClose : BargeInEvent()
    object InterruptPressed : BargeInEvent()
    object Tick : BargeInEvent()
    data class RouteChanged(val profile: BargeInProfile) : BargeInEvent()
    object PttDown : BargeInEvent()
    object PttUp : BargeInEvent()
    data class Error(val message: String?) : BargeInEvent()
}

sealed class BargeInCommand {
    /** Drop playback gain to -12 dB (×0.25) with a ~20 ms ramp; schedule a Tick after [confirmMs]. */
    data class Duck(val confirmMs: Long) : BargeInCommand()
    /** Gain back to unity with a ~50 ms ramp. */
    object Restore : BargeInCommand()
    object FlushPlayback : BargeInCommand()
    object SendCancel : BargeInCommand()
    data class SetMuted(val muted: Boolean) : BargeInCommand()
    /** The audio delta that produced this event should be enqueued for playback. */
    object EnqueueAudio : BargeInCommand()
    /** The transcript delta that produced this event should be shown as caption. */
    object ShowCaption : BargeInCommand()
    object ClearCaption : BargeInCommand()
    /** Hold-to-talk: input_audio_buffer.commit + response.create. */
    object CommitAndRespond : BargeInCommand()
    /** Hold-to-talk: force the capture gate open (flush pre-roll) / release it. */
    data class ForceGate(val open: Boolean) : BargeInCommand()
    data class Ui(val state: BargeInUi) : BargeInCommand()
    /** Re-send session.update with this turn_detection (route profile changed). */
    data class SessionUpdate(val turnDetection: TurnDetection?) : BargeInCommand()
    /** Surface a real server error (anything NOT about an active response). */
    data class ReportError(val message: String) : BargeInCommand()
    /** 3 duck→restore cycles within 2 min: offer "Noisy room? Switch to hold to talk". */
    object SuggestHoldToTalk : BargeInCommand()
}

/**
 * The barge-in state machine (spec §2). Not thread-safe — the caller serializes
 * [handle] (the Android client wraps it in a lock; events come from the WS
 * reader, capture and main threads).
 *
 * @param now monotonic clock in ms (injected so tests drive time).
 */
class BargeInController(
    profile: BargeInProfile,
    holdToTalk: Boolean = false,
    private val now: () -> Long,
) {
    companion object {
        const val DUCK_GAIN = 0.25f // -12 dB
        const val DUCK_RAMP_MS = 20L
        const val RESTORE_RAMP_MS = 50L
        /** Auto-suggest hold-to-talk after this many false barge-ins inside [SUGGEST_WINDOW_MS]. */
        const val SUGGEST_AFTER_RESTORES = 3
        const val SUGGEST_WINDOW_MS = 120_000L

        /** DashScope rejects response.cancel with no response in flight ("Conversation has
         *  no active response") and a second response.create with one running ("already
         *  has an active response") — both benign for us. */
        fun isBenignError(message: String?): Boolean =
            message?.lowercase()?.contains("active response") == true

        /** Hold-to-talk: a press too short to capture anything (or one that landed
         *  inside the gate's calibration) makes the commit fail — "buffer too small" /
         *  "buffer is empty". Not an error state: the next press just works (iOS parity). */
        fun isEmptyBufferError(message: String?): Boolean =
            message?.lowercase()?.contains("buffer") == true
    }

    var profile: BargeInProfile = profile
        private set
    var holdToTalk: Boolean = holdToTalk
        private set

    var responseActive = false; private set
    var activeResponseId: String? = null; private set
    var cancelledResponseId: String? = null; private set
    var playbackQueued = false; private set
    var muted = false; private set
    var gateOpen = false; private set
    var gateOpenSince = 0L; private set
    /** The server VAD is inside a speech segment (speech_started … speech_stopped). */
    var serverSpeaking = false; private set
    var suppressNextResponse = false; private set
    var pttPressed = false; private set
    var ui: BargeInUi = BargeInUi.LISTENING; private set

    private var duckedSince: Long? = null
    private var duckTrigger: DuckTrigger? = null
    private var sawSpeechStartedWhileDucked = false
    private val restoreTimes = ArrayDeque<Long>()
    private var suggested = false

    val speaking: Boolean get() = responseActive || playbackQueued

    /** Half-duplex: the capture gate must be held closed right now — the
     *  loudspeaker profile while the model is busy (reply in flight or audio
     *  queued), unless hold-to-talk's press is opening the mic. The audio
     *  engine derives the same answer per frame from its own volatiles (it
     *  knows the playback tail); this is the controller's view for tests/UI. */
    val gateForcedClosed: Boolean get() = BargeInProfile.gateForcedClosed(profile, speaking, pttPressed)
    val phase: BargeInPhase
        get() = when {
            holdToTalk -> BargeInPhase.HOLD
            duckedSince != null -> BargeInPhase.DUCKED
            speaking -> BargeInPhase.SPEAKING
            else -> BargeInPhase.IDLE
        }

    /** The turn_detection to send at session start (and after every route change). */
    fun turnDetection(): TurnDetection? = TurnDetection.forProfile(profile, holdToTalk)

    /** Gate margin the capture path should use right now. */
    fun gateMarginDb(): Double = profile.gateMargin(playbackQueued)

    fun handle(event: BargeInEvent): List<BargeInCommand> {
        val out = ArrayList<BargeInCommand>(4)
        when (event) {
            is BargeInEvent.ResponseCreated -> onResponseCreated(event.id, out)
            is BargeInEvent.AudioDelta -> {
                val id = event.id ?: activeResponseId
                if (!muted && (cancelledResponseId == null || id != cancelledResponseId) && id == activeResponseId) {
                    playbackQueued = true
                    out += BargeInCommand.EnqueueAudio
                    setUi(BargeInUi.SPEAKING, out)
                }
            }
            is BargeInEvent.TranscriptDelta -> {
                val id = event.id ?: activeResponseId
                if (!muted && (cancelledResponseId == null || id != cancelledResponseId)) out += BargeInCommand.ShowCaption
            }
            is BargeInEvent.ResponseDone -> {
                if (event.id == null || event.id == activeResponseId) {
                    responseActive = false
                    if (duckedSince != null && !playbackQueued) restore(out) // nothing left to protect
                    setUi(if (playbackQueued) BargeInUi.SPEAKING else BargeInUi.LISTENING, out)
                }
            }
            BargeInEvent.PlaybackDrained -> {
                playbackQueued = false
                if (!responseActive) {
                    if (duckedSince != null) restore(out)
                    setUi(BargeInUi.LISTENING, out)
                }
            }
            BargeInEvent.SpeechStarted -> onSpeechStarted(out)
            BargeInEvent.SpeechStopped -> onSpeechStopped(out)
            BargeInEvent.TranscriptionDelta, BargeInEvent.TranscriptionCompleted -> {
                // The accelerator is subject to the same two-sided rule as the
                // tick: transcription deltas also stream for the PREVIOUS turn
                // and for the model's own echo (iOS device log 2026-09-17: a
                // speech_started and a delta 0.4 ms apart cancelled a reply the
                // user never interrupted). A transcript with the mic already
                // closed is not the user talking over.
                if (duckedSince != null && gateOpen) cancel(out)
            }
            BargeInEvent.GateOpen -> onGateOpen(out)
            BargeInEvent.GateClose -> onGateClose(out)
            BargeInEvent.Tick -> onTick(out)
            BargeInEvent.InterruptPressed -> if (speaking) cancel(out)
            is BargeInEvent.RouteChanged -> {
                profile = event.profile
                if (!holdToTalk) out += BargeInCommand.SessionUpdate(turnDetection())
            }
            BargeInEvent.PttDown -> {
                if (!holdToTalk) return out
                pttPressed = true
                if (speaking) cancel(out)
                out += BargeInCommand.ForceGate(true)
                setUi(BargeInUi.LISTENING, out)
            }
            BargeInEvent.PttUp -> {
                if (!holdToTalk || !pttPressed) return out
                pttPressed = false
                out += BargeInCommand.ForceGate(false)
                out += BargeInCommand.CommitAndRespond
                setUi(BargeInUi.THINKING, out)
            }
            is BargeInEvent.Error -> when {
                isBenignError(event.message) -> {
                    responseActive = false
                    if (duckedSince != null && !playbackQueued) restore(out)
                    setUi(if (playbackQueued) BargeInUi.SPEAKING else BargeInUi.LISTENING, out)
                }
                // A too-short press committed nothing: the session is intact (mic,
                // socket, comm mode all live), so go back to the hold prompt instead
                // of a dead ERROR screen. Only in hold mode — in open-mic mode a
                // buffer error is a real protocol fault and is surfaced.
                holdToTalk && isEmptyBufferError(event.message) ->
                    setUi(if (speaking) BargeInUi.SPEAKING else BargeInUi.LISTENING, out)
                else -> out += BargeInCommand.ReportError(event.message?.takeIf { it.isNotBlank() } ?: "Voice error")
            }
        }
        return out
    }

    /** Switch hold-to-talk on/off mid-session (emits the matching session.update). */
    fun setHoldToTalk(on: Boolean): List<BargeInCommand> {
        val out = ArrayList<BargeInCommand>(3)
        if (on == holdToTalk) return out
        holdToTalk = on
        if (duckedSince != null) restore(out)
        if (!on && pttPressed) { pttPressed = false; out += BargeInCommand.ForceGate(false) }
        out += BargeInCommand.SessionUpdate(turnDetection())
        return out
    }

    // ── transitions ──

    private fun onResponseCreated(id: String?, out: MutableList<BargeInCommand>) {
        responseActive = true
        activeResponseId = id
        if (suppressNextResponse) {
            // The server replied to a false-start blip we already restored from:
            // kill it before a single delta plays.
            suppressNextResponse = false
            cancelledResponseId = id
            responseActive = false
            muted = true
            out += BargeInCommand.SendCancel
            out += BargeInCommand.SetMuted(true)
            return
        }
        if (id == null || id != cancelledResponseId) {
            muted = false
            out += BargeInCommand.SetMuted(false)
        }
        setUi(BargeInUi.THINKING, out)
    }

    private fun onSpeechStarted(out: MutableList<BargeInCommand>) {
        serverSpeaking = true
        if (holdToTalk) return
        if (duckedSince != null) {
            sawSpeechStartedWhileDucked = true
            if (duckTrigger == DuckTrigger.GATE) cancel(out) // server agrees with the gate
            return
        }
        if (speaking) duck(DuckTrigger.SERVER, out)
        else out += BargeInCommand.ClearCaption // new user turn while idle: caption is the last reply
    }

    private fun onSpeechStopped(out: MutableList<BargeInCommand>) {
        serverSpeaking = false
        if (holdToTalk) return
        if (duckedSince == null || duckTrigger != DuckTrigger.SERVER) return
        // The server saw a blip and WILL commit + reply to it: restore now and
        // cancel that reply the moment it is created. (A GATE duck that the
        // server never agreed with is restored by gate_close / the tick.)
        suppressNextResponse = true
        restore(out)
        noteFalseBargeIn(out)
    }

    private fun onGateOpen(out: MutableList<BargeInCommand>) {
        gateOpen = true
        gateOpenSince = now()
        if (holdToTalk) return
        if (duckedSince == null && speaking) duck(DuckTrigger.GATE, out)
    }

    private fun onGateClose(out: MutableList<BargeInCommand>) {
        gateOpen = false
        if (holdToTalk) return
        if (duckedSince == null || duckTrigger != DuckTrigger.GATE) return
        // Below the server's threshold the whole time: nothing was committed,
        // no suppression needed. (A gate duck the server agreed with was
        // cancelled on the spot in onSpeechStarted.)
        restore(out)
        noteFalseBargeIn(out)
    }

    private fun onTick(out: MutableList<BargeInCommand>) {
        val since = duckedSince ?: return
        if (now() - since < profile.confirmMs) return
        // CONFIRM needs evidence from both sides: the server VAD is inside a
        // speech segment AND the mic is still above the gate — sound that
        // lasted the whole confirm window. The timer alone confirmed nothing:
        // the server's speech_stopped can only arrive after silence_duration_ms
        // (600) of silence, i.e. never inside a 300 ms window, so every VAD
        // blip on a loudspeaker — a tap, a chair, a cough — cancelled the reply
        // (Ahmad's iPhone, 2026-09-17: "interrupted by any noise").
        if (gateOpen && serverSpeaking) {
            cancel(out)
            return
        }
        // A blip. If the server is still in its segment it WILL commit + reply
        // to it — suppress that reply, as the speech_stopped path does. A
        // gate-only duck the server never called speech committed nothing.
        if (serverSpeaking) suppressNextResponse = true
        restore(out)
        noteFalseBargeIn(out)
    }

    private fun duck(trigger: DuckTrigger, out: MutableList<BargeInCommand>) {
        duckedSince = now()
        duckTrigger = trigger
        sawSpeechStartedWhileDucked = false
        out += BargeInCommand.Duck(profile.confirmMs)
    }

    private fun restore(out: MutableList<BargeInCommand>) {
        duckedSince = null
        duckTrigger = null
        sawSpeechStartedWhileDucked = false
        out += BargeInCommand.Restore
    }

    /** CANCEL: stop the reply for good — cancel server-side (only if one is in
     *  flight), flush the player, drop every later delta of that response. */
    private fun cancel(out: MutableList<BargeInCommand>) {
        val wasDucked = duckedSince != null
        duckedSince = null
        duckTrigger = null
        sawSpeechStartedWhileDucked = false
        cancelledResponseId = activeResponseId
        if (responseActive) out += BargeInCommand.SendCancel
        // The server's response.done for the cancelled id only confirms this.
        responseActive = false
        out += BargeInCommand.FlushPlayback
        playbackQueued = false
        muted = true
        out += BargeInCommand.SetMuted(true)
        if (wasDucked) out += BargeInCommand.Restore // unity gain for the NEXT reply
        out += BargeInCommand.ClearCaption
        setUi(BargeInUi.LISTENING, out)
    }

    private fun noteFalseBargeIn(out: MutableList<BargeInCommand>) {
        val t = now()
        restoreTimes.addLast(t)
        while (restoreTimes.isNotEmpty() && t - restoreTimes.first() > SUGGEST_WINDOW_MS) restoreTimes.removeFirst()
        if (!suggested && restoreTimes.size >= SUGGEST_AFTER_RESTORES) {
            suggested = true
            out += BargeInCommand.SuggestHoldToTalk
        }
    }

    private fun setUi(state: BargeInUi, out: MutableList<BargeInCommand>) {
        if (ui == state) return
        ui = state
        out += BargeInCommand.Ui(state)
    }
}

/**
 * Hold-to-talk press/release sequencing for the capture path (spec §8).
 *
 * The orb's press and release arrive on the UI thread while the capture thread
 * is blocked inside a ~100 ms `AudioRecord.read`. A press applies IMMEDIATELY
 * (the next frame the thread processes is forced open + pre-roll flushed). A
 * release is only REQUESTED: the capture thread applies it at the next frame
 * boundary, i.e. AFTER the frame that was being read when the finger lifted has
 * been appended — so the tail of every utterance survives and a tap shorter
 * than one read still uploads the audio it covered. Work that must follow the
 * released audio (the `input_audio_buffer.commit`) is queued with [afterDrain]
 * and run by the capture thread at that same boundary, on the same thread that
 * appends frames, so the commit can never overtake the audio on the wire.
 *
 * Thread-safe: press/release/afterDrain come from any thread, [frameBoundary]
 * from the capture thread. No Android.
 */
class HoldToTalkLatch {
    @Volatile var pressed: Boolean = false
        private set
    @Volatile private var releaseRequested = false
    private val drains = ArrayList<() -> Unit>(2)
    private val lock = Any()

    /** Orb down: applies now. A release still pending from the previous press is
     *  cancelled (its drains still run at the next boundary). */
    fun press() { synchronized(lock) { releaseRequested = false; pressed = true } }

    /** Orb up: applied by the capture thread at the next [frameBoundary]. */
    fun release() { synchronized(lock) { if (pressed) releaseRequested = true } }

    /** Run [block] on the capture thread once the frame in flight has been appended
     *  (or at once via [releaseNow] when there is no capture thread to wait for). */
    fun afterDrain(block: () -> Unit) { synchronized(lock) { drains += block } }

    /** True while a release is waiting for the frame in flight. */
    val releasePending: Boolean get() = releaseRequested

    /**
     * Capture thread, at the start of each read: apply a pending release and hand
     * back the drains to run. Called BEFORE the next read so the frame just
     * appended is the last one of the press.
     */
    fun frameBoundary(): List<() -> Unit> = synchronized(lock) {
        if (releaseRequested) { releaseRequested = false; pressed = false }
        if (drains.isEmpty()) emptyList() else ArrayList(drains).also { drains.clear() }
    }

    /** No capture thread is alive (capture failed / stopped): apply everything now. */
    fun releaseNow(): List<() -> Unit> = synchronized(lock) {
        releaseRequested = false; pressed = false
        if (drains.isEmpty()) emptyList() else ArrayList(drains).also { drains.clear() }
    }
}

/** What the capture path should do with one 20 ms sub-frame. */
class GateOutput(
    /** PCM sub-frames to append, in order (pre-roll first when the gate just opened;
     *  a digital-silence frame while closed; nothing while calibrating). */
    val emit: List<ShortArray>,
    val opened: Boolean,
    val closed: Boolean,
    val rmsDb: Double,
    val calibrating: Boolean,
)

/**
 * RMS noise gate (spec §3). Feed 20 ms sub-frames (320 samples @ 16 kHz) in
 * capture order. Digital-silence frames are emitted while closed so the
 * server's silence timer keeps running; the gate is "closed" only in the sense
 * that the user's room noise is replaced by zeros.
 */
class RmsGate(
    private val subFrameSamples: Int = SUB_FRAME_SAMPLES,
    private val preRollSubFrames: Int = PRE_ROLL_SUB_FRAMES,
    private val calibrationSubFrames: Int = CALIBRATION_SUB_FRAMES,
    private val holdSubFrames: Int = HOLD_SUB_FRAMES,
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val SUB_FRAME_MS = 20
        const val SUB_FRAME_SAMPLES = SAMPLE_RATE / 1000 * SUB_FRAME_MS // 320
        const val PRE_ROLL_MS = 300
        const val PRE_ROLL_SUB_FRAMES = PRE_ROLL_MS / SUB_FRAME_MS // 15
        const val CALIBRATION_MS = 500
        const val CALIBRATION_SUB_FRAMES = CALIBRATION_MS / SUB_FRAME_MS // 25
        const val HOLD_MS = 200
        const val HOLD_SUB_FRAMES = HOLD_MS / SUB_FRAME_MS // 10
        const val OPEN_CONSECUTIVE = 2
        const val HYSTERESIS_DB = 3.0
        const val FLOOR_MIN_DB = -70.0
        const val FLOOR_MAX_DB = -35.0
        const val RMS_FLOOR_DB = -90.0
        const val ADAPT_COEFF = 0.05 // ≈5 s time constant at 20 ms sub-frames
        const val ADAPT_DOWN_COEFF = 0.3 // quieter room: follow fast
        /** Upward drift cap: +10 dB per minute, expressed per 20 ms sub-frame. */
        const val ADAPT_UP_MAX_DB_PER_SUB_FRAME = 10.0 / (60_000.0 / SUB_FRAME_MS)

        fun rmsDb(samples: ShortArray, n: Int = samples.size): Double {
            if (n <= 0) return RMS_FLOOR_DB
            var acc = 0.0
            for (i in 0 until n) { val v = samples[i].toDouble(); acc += v * v }
            val rms = sqrt(acc / n) / 32768.0
            if (rms <= 0.0) return RMS_FLOOR_DB
            return max(RMS_FLOOR_DB, 20.0 * log10(rms))
        }
    }

    var floorDb: Double = Double.NaN; private set
    val calibrating: Boolean get() = floorDb.isNaN()
    var open: Boolean = false; private set
    var forcedOpen: Boolean = false; private set

    private val calibration = ArrayList<Double>(calibrationSubFrames)
    private val preRoll = ArrayDeque<ShortArray>(preRollSubFrames)
    private var aboveCount = 0
    private var belowCount = 0
    private val silence = ShortArray(subFrameSamples)

    /** Full re-calibration (route change). */
    fun reset() {
        floorDb = Double.NaN
        calibration.clear()
        preRoll.clear()
        aboveCount = 0; belowCount = 0
        open = false
    }

    /** Hold-to-talk: keep the gate open regardless of energy (pre-roll is flushed on the press). */
    fun forceOpen(on: Boolean) {
        forcedOpen = on
        if (!on) { open = false; aboveCount = 0; belowCount = 0 }
    }

    fun openDb(marginDb: Double) = floorDb + marginDb
    fun closeDb(marginDb: Double) = floorDb + marginDb - HYSTERESIS_DB

    /**
     * @param marginDb  profile margin (6 dB; 9 dB on the speaker profile while playing)
     * @param playbackQueued  model audio still playing/queued (blocks adaptation)
     * @param responseActive  a reply is in flight (blocks adaptation)
     * @param forcedClosed  half-duplex (the loudspeaker while the model is
     *   audible — [BargeInProfile.gateForcedClosed]): the gate is held shut —
     *   digital silence out, the pre-roll discarded — so nothing from the mic
     *   reaches the server; its own echo is what tripped the VAD. Hold-to-talk's
     *   [forceOpen] wins over it.
     */
    fun process(
        samples: ShortArray, marginDb: Double, playbackQueued: Boolean, responseActive: Boolean,
        forcedClosed: Boolean = false,
    ): GateOutput {
        val n = min(samples.size, subFrameSamples)
        val frame = if (samples.size == subFrameSamples) samples else samples.copyOf(subFrameSamples)
        val db = rmsDb(frame, n)

        if (calibrating) {
            pushPreRoll(frame)
            calibration += db
            if (calibration.size >= calibrationSubFrames) {
                val sorted = calibration.sorted()
                val mid = sorted.size / 2
                val median = if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
                floorDb = median.coerceIn(FLOOR_MIN_DB, FLOOR_MAX_DB)
            }
            return GateOutput(emptyList(), opened = false, closed = false, rmsDb = db, calibrating = true)
        }

        if (forcedClosed && !forcedOpen) {
            // Half-duplex: slam an open gate shut (one close event), keep it
            // shut, and drop the pre-roll — or the reply's tail would be
            // prefixed to the user's next turn. Silence of the same size keeps
            // the server's silence timer running.
            val wasOpen = open
            open = false
            aboveCount = 0; belowCount = 0
            preRoll.clear()
            return GateOutput(listOf(silence), opened = false, closed = wasOpen, rmsDb = db, calibrating = false)
        }

        if (forcedOpen) {
            if (!open) {
                open = true
                val pre = preRoll.toList(); preRoll.clear()
                return GateOutput(pre + listOf(frame), opened = true, closed = false, rmsDb = db, calibrating = false)
            }
            return GateOutput(listOf(frame), opened = false, closed = false, rmsDb = db, calibrating = false)
        }

        var opened = false
        var closed = false
        val emit: List<ShortArray>
        if (!open) {
            pushPreRoll(frame)
            if (db >= openDb(marginDb)) aboveCount++ else aboveCount = 0
            if (aboveCount >= OPEN_CONSECUTIVE) {
                open = true; opened = true
                aboveCount = 0; belowCount = 0
                // The sub-frame that opened the gate is already the newest pre-roll entry.
                emit = preRoll.toList()
                preRoll.clear()
            } else {
                emit = listOf(silence)
                adapt(db, playbackQueued, responseActive)
            }
        } else {
            if (db < closeDb(marginDb)) belowCount++ else belowCount = 0
            if (belowCount >= holdSubFrames) {
                open = false; closed = true
                belowCount = 0; aboveCount = 0
                pushPreRoll(frame)
                emit = listOf(silence)
            } else {
                emit = listOf(frame)
            }
        }
        return GateOutput(emit, opened, closed, db, calibrating = false)
    }

    private fun pushPreRoll(frame: ShortArray) {
        if (preRoll.size >= preRollSubFrames) preRoll.removeFirst()
        preRoll.addLast(frame.copyOf())
    }

    private fun adapt(db: Double, playbackQueued: Boolean, responseActive: Boolean) {
        if (playbackQueued || responseActive) return // residual echo would inflate the floor
        val delta = db - floorDb
        val step = if (delta < 0) ADAPT_DOWN_COEFF * delta else min(ADAPT_COEFF * delta, ADAPT_UP_MAX_DB_PER_SUB_FRAME)
        floorDb = (floorDb + step).coerceIn(FLOOR_MIN_DB, FLOOR_MAX_DB)
    }
}
