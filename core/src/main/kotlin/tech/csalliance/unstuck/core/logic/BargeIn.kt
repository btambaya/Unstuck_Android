package tech.csalliance.unstuck.core.logic

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// BargeIn — the pure, headless half of realtime-voice turn-taking. A faithful
// port of iOS App/Voice/BargeIn.swift (2026-09-20, builds 66–70 of the iOS
// app, each verified on a phone), so the two platforms stay in lock-step:
// the same events, the same commands, the same 64 unit cases
// (core/src/test/.../BargeInControllerTest.kt ↔ Tests/UnstuckAppTests/BargeInTests.swift).
//
// The contract with the server (DashScope Qwen-Omni realtime) since iOS build
// 66: server_vad with `interrupt_response:false` and `create_response:false`.
// Measured against the live proxy on 2026-09-19:
//
//   * with the defaults the server CANCELS its own reply the moment its VAD
//     hears speech (response.done status=cancelled, reason=turn_detected). On
//     the loudspeaker that "speech" is the reply's own echo, so replies came
//     out in fragments — nothing a client could prevent after the fact;
//   * with both flags off it still segments (speech_started(item_id) …
//     speech_stopped), commits and transcribes what it heard (the completed
//     transcript ~300 ms after speech_stopped), but neither truncates nor
//     answers anything by itself;
//   * a `response.create` in the same breath as a `response.cancel` drops the
//     connection ("thread pool exhausted"); sent after the cancelled
//     response.done (~300 ms later) it works.
//
// So the CLIENT owns turn-taking:
//
//   1. REPLY: a speech segment's completed transcript with real words →
//      `response.create`. No words (a cough, an echo the transcriber heard
//      as Chinese) → the item is deleted, nothing is answered. If a reply is
//      still generating, cancel it first and create only when its done
//      arrives (`pendingCreate`) — and never before TURN_HOLD_MS of quiet,
//      so a pause mid-sentence does not get the fragment answered.
//   2. INTERRUPT: on low-echo routes (earphones, Bluetooth) the DUCK →
//      CONFIRM → CANCEL energy machine: the first hint of speech ducks
//      −12 dB; the server VAD in a segment AND the mic above the gate for
//      confirmMs cancels; a blip restores. On the loudspeaker the mic hears
//      every reply, so WORDS decide: the first real words of a segment that
//      began while a reply was on air stop it.
//   3. ECHO: a transcript that is (≥70 %) words the model itself just said,
//      from a segment that began while a reply was on air or within 1.5 s of
//      its audio draining, is echo → nothing answered, nothing shown, and
//      the item deleted once the next segment starts (the user's question
//      often shares the segment with the echo's tail and arrives as a later
//      piece of the same item). The reference is the reply on air and the
//      one before it, not a long tail, so a real sentence sharing everyday
//      words with older replies does not look like echo.
//   4. An RMS noise gate in the capture path: floor-calibrated, hysteresis,
//      300 ms pre-roll, DIGITAL SILENCE while closed (the server's
//      silence_duration_ms timer must observe silence to end a turn), and no
//      floor adaptation while the model is playing (residual echo).
//   5. TOOLS: the client reports each tool call it starts and each
//      function_call_output it has sent. While one is running nothing is
//      asked for; when the last output is out, ONE reply is asked for (120 ms
//      later, or at the done of the reply that carried the calls) and it
//      answers any turn the user took meanwhile too. The turn used to be
//      asked at that done, before the output: Zubair's morning call
//      (2026-09-24 07:02:50, iOS) heard "I tried to cancel the repeat, but it
//      didn't go through" over an ok, and the continuation then collided
//      with it ("already has an active response"). Calls are tracked by id,
//      so a late output (after the 10 s wait gave up on it) never stands in
//      for a call still running; a reply we did not cancel is never asked
//      over (its done asks); and nothing is asked while hold-to-talk is held
//      (the release asks, with the continuation riding on it).
//
// The loudspeaker is FULL-DUPLEX again (it was half-duplex — mic muted for
// the whole reply — from 2026-09-17): the platform echo canceller keeps the
// reply out of the mic stream (VoiceAudioEngine: VOICE_COMMUNICATION source
// + MODE_IN_COMMUNICATION + AcousticEchoCanceler), and the word rules here
// are the backstop for what is left. Talk-over works on the speaker.
//
// Hold-to-talk (turn_detection null) is unchanged: the client commits and
// creates on release. Inputs are events + a monotonic clock (ms); outputs
// are commands the transport/audio layers execute (VoiceRealtimeClient /
// VoiceAudioEngine). No Android, no network — every function here is
// deterministic.

/** Where the model's audio comes out — what decides the barge-in profile. */
enum class VoiceRoute { LOW_ECHO, SPEAKER }

/**
 * How a barge-in is CONFIRMED (iOS `BargeInProfile.Confirm`).
 * [ENERGY]: the two-sided rule — server VAD in a speech segment AND the mic
 *   still above the gate for `confirmMs`. Right where the mic hears little
 *   playback (earphones, Bluetooth).
 * [TRANSCRIPT]: by WORDS. Nothing ducks and no confirm timer runs; the
 *   server's transcription of what it heard is compared with what the model
 *   just said. Echo → discarded (its item deleted). Real words → the reply
 *   stops. A cough has no words. This is the loudspeaker answer: half-duplex
 *   took talk-over away entirely, and words don't care about acoustics.
 */
enum class BargeInConfirm { ENERGY, TRANSCRIPT }

/**
 * Per-route tuning (spec §1). [halfDuplexWhilePlaying] is kept for the gate
 * helper the audio engine calls per frame, but no shipped profile sets it
 * any more: the loudspeaker runs full-duplex since the 2026-09-20 port (iOS
 * build 69), with echo cancellation on and words as the backstop.
 */
data class BargeInProfile(
    val route: VoiceRoute,
    /** server_vad threshold — above the 0.5 default because the gate + AEC
     *  already remove floor noise. */
    val threshold: Double,
    /** How long a DUCK lasts before it becomes a CANCEL (energy confirm). */
    val confirmMs: Long,
    val gateMarginDb: Double,
    val gateMarginPlayingDb: Double,
    val confirm: BargeInConfirm,
    val halfDuplexWhilePlaying: Boolean = false,
) {
    /** The RMS gate's margin above the noise floor — higher on the loudspeaker
     *  while the model plays, where residual echo is the false-trigger source. */
    fun gateMargin(playbackQueued: Boolean): Double = if (playbackQueued) gateMarginPlayingDb else gateMarginDb

    companion object {
        /** Wired / BT headset: T 0.5, confirm 200 ms, +6 dB, energy confirm. */
        val LOW_ECHO = BargeInProfile(VoiceRoute.LOW_ECHO, 0.5, 200L, 6.0, 6.0, BargeInConfirm.ENERGY)
        /** Built-in loudspeaker: T 0.6, confirm 300 ms, +9 dB while playing, +6
         *  otherwise — confirmed by WORDS, full-duplex. */
        val SPEAKER = BargeInProfile(VoiceRoute.SPEAKER, 0.6, 300L, 6.0, 9.0, BargeInConfirm.TRANSCRIPT)

        fun forRoute(route: VoiceRoute): BargeInProfile = when (route) {
            VoiceRoute.LOW_ECHO -> LOW_ECHO
            VoiceRoute.SPEAKER -> SPEAKER
        }

        /** Whether the capture gate must be held closed right now: a
         *  half-duplex profile, the model busy, and no hold-to-talk press
         *  overriding. False for every shipped profile since 2026-09-20. */
        fun gateForcedClosed(profile: BargeInProfile, modelBusy: Boolean, pttPressed: Boolean): Boolean =
            profile.halfDuplexWhilePlaying && modelBusy && !pttPressed
    }
}

/**
 * The `turn_detection` block of session.update. `null` = hold-to-talk (client
 * commits). prefix_padding 300 matches the gate's 300 ms pre-roll; 600 ms
 * silence is inside the doc's 500–600 recommendation for short turns.
 * [interruptResponse] / [createResponse] are OFF (see the file header): the
 * server segments and transcribes; the client cancels and creates.
 */
data class TurnDetection(
    val type: String = "server_vad",
    val threshold: Double,
    val prefixPaddingMs: Int = PREFIX_PADDING_MS,
    val silenceDurationMs: Int = SILENCE_DURATION_MS,
    val interruptResponse: Boolean = false,
    val createResponse: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("type", type)
        put("threshold", threshold)
        put("prefix_padding_ms", prefixPaddingMs)
        put("silence_duration_ms", silenceDurationMs)
        put("interrupt_response", interruptResponse)
        put("create_response", createResponse)
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
    /** An audio delta arrived; the controller answers [BargeInCommand.EnqueueAudio] when it should play. */
    data class AudioDelta(val id: String?) : BargeInEvent()
    /** A caption delta for the model's reply; answered with [BargeInCommand.ShowCaption] when accepted. */
    data class TranscriptDelta(val id: String?) : BargeInEvent()
    data class ResponseDone(val id: String?, val status: String? = null) : BargeInEvent()
    /** `response.done` with status `failed` for want of rate limit (OpenAI:
     *  the org's tokens-per-minute bucket ran dry — 40k TPM ≈ 4 replies a
     *  minute with the tool schemas; Ahmad's session 2026-09-20 23:48 went
     *  silent). The reply was for a turn already asked: ask again after the
     *  bucket's reset, a few times, then give up out loud (parity with iOS
     *  build 76). */
    data class ResponseRateLimited(val retryAfterMs: Long) : BargeInEvent()
    object PlaybackDrained : BargeInEvent()
    /** The server VAD opened a segment; [itemId] is the conversation item it
     *  will commit that speech into (DashScope sends it), so a transcript can
     *  be tied back to WHEN its speech began — while a reply was busy, or not. */
    data class SpeechStarted(val itemId: String? = null) : BargeInEvent()
    object SpeechStopped : BargeInEvent()
    /** transcription.delta (`final = false`, the transcriber's live guess) or
     *  .completed (`final = true`) for the user's input. Deltas can stop a
     *  reply early; only the completed transcript decides what is answered. */
    data class Transcription(val text: String, val itemId: String?, val final: Boolean) : BargeInEvent()
    /** response.audio_transcript.delta — the model's own words, the echo reference. */
    data class AssistantTranscript(val delta: String) : BargeInEvent()
    object GateOpen : BargeInEvent()
    object GateClose : BargeInEvent()
    object InterruptPressed : BargeInEvent()
    object Tick : BargeInEvent()
    data class RouteChanged(val profile: BargeInProfile) : BargeInEvent()
    object PttDown : BargeInEvent()
    object PttUp : BargeInEvent()
    /** A protocol `error` event; "active response" ones are benign (iOS `benignActiveResponseError`).
     *  [code] is the server's `error.code`, carried so the client can word a
     *  rate limit without matching the provider's text (iOS build 78). */
    data class Error(val message: String?, val code: String? = null) : BargeInEvent()
    /** The client started running one of the reply's tool calls (deduped by
     *  call id). Until its `function_call_output` is in the conversation no
     *  reply may be asked for: one generated without the result reads the call
     *  as failed (Zubair's morning call, 2026-09-24 07:02:50 — a pending turn
     *  was asked at the done of the reply carrying set_task_recurrence, 70 ms
     *  before the ok went out: "I tried to cancel the repeat, but it didn't go
     *  through"). Arrives before that reply's done (same socket, same order). */
    data class ToolCallStarted(val callId: String) : BargeInEvent()
    /** That call's `function_call_output` has been SENT. With none left
     *  running, ONE reply is asked for — the continuation that reads the
     *  results, and answers any turn the user took meanwhile. By id: a late
     *  output of a call the wait gave up on is read, but never counts against
     *  a call that is still running. */
    data class ToolCallFinished(val callId: String) : BargeInEvent()
}

sealed class BargeInCommand {
    /** Playback gain −12 dB (linear 0.25), ~20 ms ramp. */
    object Duck : BargeInCommand()
    /** Gain back to unity, ~50 ms ramp. */
    object Restore : BargeInCommand()
    /** Drop queued + playing model audio now. */
    object FlushPlayback : BargeInCommand()
    /** `response.cancel` — only ever emitted while a response is active. */
    object SendCancel : BargeInCommand()
    /** The audio delta that produced this event should be enqueued for playback. */
    object EnqueueAudio : BargeInCommand()
    /** The transcript delta that produced this event should be shown as caption. */
    object ShowCaption : BargeInCommand()
    /** The reply being cancelled must not linger on screen. */
    object ClearCaption : BargeInCommand()
    /** Hold-to-talk release: input_audio_buffer.commit + response.create. */
    object CommitAndRespond : BargeInCommand()
    /** Hold-to-talk: force the capture gate open (flush pre-roll) / release it. */
    data class ForceGate(val open: Boolean) : BargeInCommand()
    data class Ui(val state: BargeInUi) : BargeInCommand()
    /** Re-send session.update with this turn_detection (route profile changed / mode). */
    data class SessionUpdate(val turnDetection: TurnDetection?) : BargeInCommand()
    /** Surface a real server error (anything NOT about an active response).
     *  [message] is the server's raw text — for the device log only; the
     *  client turns it into plain words before the user sees it (iOS build 78). */
    data class ReportError(val message: String, val code: String? = null) : BargeInCommand()
    /** 3 duck→restore cycles within 2 min: offer "Noisy room? Switch to hold to talk". */
    object SuggestHoldToTalk : BargeInCommand()
    /** Arm a timer: deliver [BargeInEvent.Tick] after this many ms (the energy
     *  confirm, the turn hold, and the fallback for a cancelled reply whose
     *  done never comes). Stale ticks are harmless: the controller checks the
     *  elapsed time against the CURRENT duck / pending turn. */
    data class StartTimer(val ms: Long) : BargeInCommand()
    /** `conversation.item.delete` — the segment was the model's own echo, or
     *  had no words (a cough, "um", an echo the transcriber heard as Chinese);
     *  take it out so the model never sees it as the user's turn. */
    data class DeleteItem(val id: String) : BargeInCommand()
    /** `response.create` — a user turn is complete (its transcript has real
     *  words), or the tool outputs of the last reply are all in, and nothing
     *  is generating. The server never creates replies by itself
     *  (create_response:false). The continuation after tool outputs is asked
     *  for here too (it used to be the client's own timer), so a turn's ask
     *  and a continuation never race each other or a tool's output. */
    object CreateResponse : BargeInCommand()
    /** The user's completed words, for the caption — REAL turns only. Every
     *  completed transcript used to be shown as the user's line, so the
     *  reply's own echo wiped the reply's caption a second in and left "a
     *  Chinese phrase" there (Ahmad, 2026-09-19). */
    data class UserTurn(val text: String) : BargeInCommand()
}

/** The last echo decision, as hits/heard/reference — for the device log. */
data class EchoScore(val hits: Int, val heard: Int, val spoken: Int)

/**
 * The barge-in state machine (spec §2). Not thread-safe — the caller serializes
 * [handle] (the Android client wraps it in a lock; events come from the WS
 * reader, capture, playback and main threads).
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

        /** A pause mid-sentence ends a VAD segment (600 ms of silence) and the
         *  fragment was answered on its own; the continuation then cancelled
         *  that reply and got its own — "it kept tripping itself" on long
         *  questions (iOS device log 2026-09-20 00:25). Half a second more
         *  before asking bridges a pause of ~1.2 s; a longer one still gets
         *  cancel-and-re-ask. */
        const val TURN_HOLD_MS = 500L
        /** If a cancelled reply's done never comes (or the cancel found
         *  nothing), ask anyway. 2.5 s: a done took 1.9 s once (a tool call in flight). */
        const val PENDING_CREATE_FALLBACK_MS = 2500L
        /** How long a `response.create` may go unanswered by response.created
         *  before the pending turn is asked for again (see [createSentAt];
         *  parity with iOS build 75). */
        const val CREATE_GRACE_MS = 3_000L
        /** Rate-limited replies re-asked for one turn before the user is told
         *  the assistant is busy (parity with iOS build 76). */
        const val RATE_LIMIT_MAX_RETRIES = 3
        /** The continuation is asked this long after the LAST tool output
         *  (web/iOS: 120 ms), so parallel calls of one reply get one reply —
         *  or at once at the done of the reply that carried them. */
        const val CONTINUE_DELAY_MS = 120L
        /** A tool that never answers must not hold the user's turn forever
         *  (nothing times a tool out): past this, asks stop waiting for it. */
        const val TOOL_WAIT_MAX_MS = 10_000L
        /** Echo reference cap per reply. */
        const val SPOKEN_CAP = 400
        const val SEGMENT_HISTORY = 8
        /** The reply whose audio just finished: its LAST words echo back after
         *  the queue has drained (iOS device log 2026-09-19 22:39:35: drained,
         *  then 30 ms later a segment that transcribed as the reply's tail). A
         *  segment starting inside this window counts as begun on air. */
        const val DRAIN_ECHO_GRACE_MS = 1500L
        /** From this many words an on-air utterance is echo only when (near)
         *  verbatim (see [isEcho]); shorter ones are scored by their content
         *  words. */
        const val ECHO_VERBATIM_FROM = 4

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

        /** Words that carry no content — the transcriber adds and drops them
         *  freely ("Monday's open" came back as "Monday is open", iOS device
         *  log 2026-09-19 23:50), and a real interruption is full of them
         *  ("How about Tuesday?" shares two of three with "How about you?").
         *  They only count when an utterance has nothing else ("How about
         *  you?", "Okay."). */
        val STOP_WORDS: Set<String> = setOf(
            "a", "an", "the", "and", "or", "but", "if", "so", "of", "to", "in", "on", "at", "by", "for", "with", "from", "as",
            "into", "over", "about", "up", "down", "out", "off", "than", "then", "too", "also",
            "is", "are", "was", "were", "be", "been", "being", "am", "do", "does", "did", "have", "has", "had",
            "will", "would", "can", "could", "should", "may", "might", "shall", "must",
            "i", "me", "my", "mine", "you", "your", "yours", "youre", "youve", "youll", "youd", "he", "him", "his", "she", "her", "hers",
            "it", "its", "im", "ive", "ill", "id", "we", "us", "our", "ours", "weve", "well", "they", "them", "their", "theyre",
            "this", "that", "these", "those", "there", "here", "what", "which", "who", "whom", "whose", "how", "when", "where", "why",
            "not", "no", "yes", "ok", "okay", "oh", "um", "uh", "hmm", "just", "really", "very", "quite",
        )

        /** Plurals and possessives fold ("mondays" / "players" → "monday" /
         *  "player"): the model writes Monday's, the transcriber Monday is. */
        fun stem(t: String): String = if (t.length >= 4 && t.endsWith("s")) t.dropLast(1) else t

        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        /** Lower-cased word tokens, punctuation and APOSTROPHES stripped,
         *  one-letter tokens dropped ("a", "I" match everything). The model
         *  writes Tuesday’s with a curly apostrophe and the transcriber
         *  Tuesday's with a straight one — that one character failed a
         *  three-word echo at 2/3 (iOS device log 2026-09-19 22:39:30), so
         *  both become "tuesdays". Tokens without a Latin letter or digit are
         *  dropped: the transcriber sometimes hears the loudspeaker's echo as
         *  Chinese ("嘿。" for "Hey", same log), and this assistant speaks
         *  English — such a transcript is noise, never an instruction to stop. */
        fun tokens(text: String): List<String> =
            text.replace("’", "").replace("'", "").lowercase()
                .split(NON_WORD)
                .filter { t -> t.length >= 2 && t.any { it in 'a'..'z' || it in '0'..'9' } }
    }

    var profile: BargeInProfile = profile
        private set
    var holdToTalk: Boolean = holdToTalk
        private set

    var phase: BargeInPhase = BargeInPhase.IDLE; private set
    var responseActive = false; private set
    var activeResponseId: String? = null; private set
    var cancelledResponseId: String? = null; private set
    var playbackQueued = false; private set
    /** Drop audio/transcript deltas until the next (non-cancelled) response. */
    var muted = false; private set
    var gateOpen = false; private set
    var gateOpenSince: Long? = null; private set
    /** The server VAD is inside a speech segment (speech_started … speech_stopped). */
    var serverSpeaking = false; private set
    var pttPressed = false; private set
    /** The last UI state emitted. */
    var ui: BargeInUi = BargeInUi.LISTENING; private set
    /** How many DUCK→restore cycles happened (the "noisy room?" chip, spec §8). */
    var falseBargeIns = 0; private set

    private var duckedSince: Long? = null
    private var duckTrigger: DuckTrigger? = null
    private val restoreTimes = ArrayDeque<Long>()
    private var suggested = false

    /** A real turn waiting to be asked for: `since` = the last moment the
     *  user was heard (their completed transcript, or a further speech start
     *  while nothing plays). Asked once the hold has elapsed, the server VAD
     *  is silent and no reply is generating — a `response.create` in the same
     *  breath as a `response.cancel` drops the connection (measured 2026-09-19). */
    private var pendingTurnSince: Long? = null
    val pendingCreate: Boolean get() = pendingTurnSince != null
    /** User turns the controller took to answer: a completed transcript it
     *  judged theirs (not echo, not a cough) with a reply to be asked for, or a
     *  hold-to-talk release. Counts only up. The client ends a spoken review's
     *  recap exactly here — when the app answers a real user turn, never on a
     *  raw speech_started, which the loudspeaker's echo of the review fires too
     *  (cross-platform rule, 2026-09-24). */
    var answeredTurns: Int = 0; private set
    /** When our last `response.create` for the pending turn went out, until
     *  its response.created arrives. The turn stays PENDING meanwhile: a
     *  create the server swallowed (Zubair's call, 2026-09-20 18:02 — a cancel
     *  went unanswered, the 2.5 s fallback create produced nothing, the muted
     *  reply completed, and his "Yes." was never answered: 20 s of silence)
     *  is re-asked when the active reply finishes or after [CREATE_GRACE_MS];
     *  a slow one is told apart from it only by time (parity with iOS build 75). */
    var createSentAt: Long? = null; private set
    /** Rate-limited replies re-asked for the current turn; reset by a new turn
     *  or a reply that completed. */
    var rateLimitRetries = 0; private set
    /** A rate-limited turn is not asked again before this: the token bucket's
     *  reset. Every timer posts the same tick and none is cancelled, so a
     *  stale hold or fallback timer (or a VAD blip's 500 ms) re-asked into
     *  the still-empty bucket and spent the three retries in seconds (review
     *  of the 2026-09-23 parity port). Cleared when the server creates a
     *  reply. A new turn keeps it: asked before the reset, it only fails again. */
    var retryNotBefore: Long? = null; private set

    /** Tool calls (by call id) running whose `function_call_output` has not gone out. */
    private val toolCalls = LinkedHashSet<String>()
    val toolsInFlight: Int get() = toolCalls.size
    /** When the first of [toolsInFlight] started (for [TOOL_WAIT_MAX_MS]). */
    private var toolsSince: Long? = null
    /** The last tool output went out at this time and no reply has been asked
     *  for since: the continuation that reads the results is owed. */
    var continuationSince: Long? = null; private set
    /** A hold-to-talk release while a tool ran: its commit + ask wait for the
     *  continuation (the release appends nothing, so nothing is lost). */
    private var pttCommitOwed = false
    /** A tool call is running or its results are still to be read: the next
     *  reply is the continuation, and nothing else may ask before it. The
     *  client holds the integrity corrective for it on this. */
    val toolsPending: Boolean get() = toolCalls.isNotEmpty() || continuationSince != null
    /** The reply generating now is one we cancelled: its done may never come
     *  (the fallback's case). A reply we did not cancel always sends its done. */
    private val activeCancelled: Boolean get() = activeResponseId != null && activeResponseId == cancelledResponseId

    /** Echo reference: the words of the reply on air and of the one before
     *  it (a reply's tail echoes after the next response was created). Not a
     *  long history — everyday words pile up and a real question starts to
     *  look like echo (iOS device log 2026-09-19 23:01: 8/12 on a real one). */
    var spokenCurrent: List<String> = emptyList(); private set
    var spokenPrevious: List<String> = emptyList(); private set
    private var spokenSet: Set<String> = emptySet()
    /** The response whose audio is (or was last) queued for playback. */
    var playingResponseId: String? = null; private set

    /** One server VAD speech segment (speech_started … stopped), by the item
     *  id the server commits it into. */
    data class Segment(
        val itemId: String?,
        /** Began while a reply was on air / generating, or within the drain
         *  grace — only those words can be echo or an interruption. A segment
         *  that began while nothing played is the user, whatever it says. */
        val echoPossible: Boolean,
        /** The reply's AUDIO was on air when it began (as opposed to the
         *  drain grace, where only the reply's tail can still echo). */
        val onAir: Boolean = false,
        /** `response.create` already issued (or pending) for it. */
        val responded: Boolean = false,
        /** Judged echo by its words. The transcriber can complete one segment
         *  in pieces ("Coming up on." then "Day.", iOS device log 2026-09-20
         *  00:04:43): a short later piece is more of the same echo, not a turn. */
        val echoJudged: Boolean = false,
    )
    private val segments = ArrayList<Segment>()
    /** Items judged echo / no words, whose `conversation.item.delete` is HELD
     *  until the next segment starts or a reply is asked for: the user often
     *  starts talking inside the same VAD segment as the reply's echo tail,
     *  and their words then arrive as a later completed transcript for the
     *  same item — deleted already, it would answer nothing. */
    private val _pendingDeletes = ArrayList<String>()
    val pendingDeletes: List<String> get() = _pendingDeletes.toList()
    private var lastDrained: Pair<String?, Long>? = null
    /** For the device log: the last echo decision. */
    var lastEchoScore = EchoScore(0, 0, 0); private set

    /** True while the model is (or is about to be) audible — the Interrupt
     *  button's enablement and the DUCK precondition. */
    val modelBusy: Boolean get() = responseActive || playbackQueued
    /** Android's name for [modelBusy] (the client's `canInterrupt`). */
    val speaking: Boolean get() = modelBusy

    /** Half-duplex view for tests/UI — false for every shipped profile. */
    val gateForcedClosed: Boolean get() = BargeInProfile.gateForcedClosed(profile, modelBusy, pttPressed)

    /** The turn_detection to send at session start (and after every route change). */
    fun turnDetection(): TurnDetection? = TurnDetection.forProfile(profile, holdToTalk)

    /** Gate margin the capture path should use right now. */
    fun gateMarginDb(): Double = profile.gateMargin(playbackQueued)

    /** Whether an audio delta for [id] should be played. */
    fun shouldEnqueueAudio(id: String?): Boolean {
        if (id != null && id == cancelledResponseId) return false
        // The reply on air keeps streaming whatever else is going on.
        if (id != null && playingResponseId != null && id == playingResponseId) return true
        if (muted) return false
        if (id != null && activeResponseId != null && id != activeResponseId) return false
        return true
    }

    /** Whether a transcript (caption) delta for [id] should be shown. */
    fun acceptsTranscript(id: String?): Boolean = shouldEnqueueAudio(id)

    /** The UI state right now (the initial session.update / after a resync). */
    val uiStateNow: BargeInUi
        get() = when {
            phase == BargeInPhase.HOLD -> BargeInUi.LISTENING
            playbackQueued -> BargeInUi.SPEAKING
            responseActive || toolsPending -> BargeInUi.THINKING
            else -> BargeInUi.LISTENING
        }

    fun handle(event: BargeInEvent): List<BargeInCommand> {
        val out = ArrayList<BargeInCommand>(6)
        val t = now()
        when (event) {
            is BargeInEvent.ResponseCreated -> {
                val id = event.id
                if (id == null || id != cancelledResponseId) {   // never resurrect a cancelled reply
                    responseActive = true
                    activeResponseId = id
                    muted = false
                    // The turn this create was for is being answered. A turn taken
                    // AFTER the create went out (they spoke again while it was in
                    // flight) stays pending and is asked once this reply is done.
                    val since = pendingTurnSince
                    val sent = createSentAt
                    if (since == null || sent == null || since <= sent) pendingTurnSince = null
                    createSentAt = null
                    retryNotBefore = null
                    // A new reply: the one before it is now the "previous" reference.
                    spokenPrevious = spokenCurrent
                    spokenCurrent = emptyList()
                    spokenSet = spokenPrevious.map(::stem).toSet()
                    ui(BargeInUi.THINKING, out)
                    if (phase == BargeInPhase.IDLE) phase = BargeInPhase.SPEAKING
                }
            }
            is BargeInEvent.AudioDelta -> {
                if (shouldEnqueueAudio(event.id)) {
                    playbackQueued = true
                    event.id?.let { playingResponseId = it }
                    if (phase == BargeInPhase.IDLE) phase = BargeInPhase.SPEAKING
                    out += BargeInCommand.EnqueueAudio
                    ui(BargeInUi.SPEAKING, out)
                }
            }
            is BargeInEvent.TranscriptDelta -> if (acceptsTranscript(event.id)) out += BargeInCommand.ShowCaption
            is BargeInEvent.ResponseDone -> {
                // Only the ACTIVE response's done counts (or an id-less one): a
                // cancelled reply's late done must not clear the flag for the new
                // reply that has already started.
                val id = event.id
                if (id == null || activeResponseId == null || id == activeResponseId) {
                    responseActive = false
                    rateLimitRetries = 0
                    // Any create we sent while this reply was active is dead with it
                    // (the server never queues one): re-ask below if a turn is pending —
                    // also when the server ignored our cancel and the reply completed.
                    createSentAt = null
                    if (toolsPending) {
                        // This reply carried tool calls. The next reply is the one
                        // that reads their results, asked for once every output is
                        // out — at once if they already are. A turn the user took
                        // meanwhile rides on it (their words are in the
                        // conversation); asking for it NOW would beat the outputs
                        // (Zubair's morning call, 2026-09-24 07:02:50).
                        if (phase == BargeInPhase.SPEAKING && !playbackQueued) phase = BargeInPhase.IDLE
                        val ask = tryAsk(t, afterDone = true)
                        out += ask
                        if (ask.none { it == BargeInCommand.CreateResponse || it == BargeInCommand.CommitAndRespond }) {
                            if (pendingCreate) out += BargeInCommand.StartTimer(TURN_HOLD_MS)
                            // Holding the orb: they are talking — never "Thinking" over them.
                            ui(when {
                                phase == BargeInPhase.HOLD -> BargeInUi.LISTENING
                                playbackQueued -> BargeInUi.SPEAKING
                                else -> BargeInUi.THINKING
                            }, out)
                        }
                    } else if (pendingCreate) {
                        // The reply we cancelled is finished server-side: the user's
                        // turn can be asked for once its hold is up and they are quiet.
                        val ask = tryAsk(t)
                        if (ask.isEmpty()) out += BargeInCommand.StartTimer(TURN_HOLD_MS) else out += ask
                    } else if (!playbackQueued) {
                        if (phase == BargeInPhase.SPEAKING) phase = BargeInPhase.IDLE
                        ui(BargeInUi.LISTENING, out)
                    } else {
                        ui(BargeInUi.SPEAKING, out)
                    }
                }
            }
            is BargeInEvent.ResponseRateLimited -> {
                responseActive = false
                createSentAt = null
                muted = false
                if (phase == BargeInPhase.SPEAKING && !playbackQueued) phase = BargeInPhase.IDLE
                retryNotBefore = t + event.retryAfterMs
                rateLimitRetries += 1
                if (rateLimitRetries > RATE_LIMIT_MAX_RETRIES) {
                    pendingTurnSince = null
                    ui(BargeInUi.LISTENING, out)
                } else {
                    // The turn is pending again, its hold already up; the tick after
                    // the reset asks for it.
                    pendingTurnSince = t - TURN_HOLD_MS
                    out += BargeInCommand.StartTimer(max(event.retryAfterMs, TURN_HOLD_MS))
                    ui(BargeInUi.THINKING, out)
                }
            }
            BargeInEvent.PlaybackDrained -> {
                playbackQueued = false
                lastDrained = playingResponseId to t
                playingResponseId = null
                if (!responseActive) {
                    if (phase == BargeInPhase.SPEAKING) phase = BargeInPhase.IDLE
                    // "One moment." has played; the tool it announced is still
                    // working, or its results are about to be read.
                    ui(if (toolsPending && phase != BargeInPhase.HOLD) BargeInUi.THINKING else BargeInUi.LISTENING, out)
                }
            }
            BargeInEvent.GateOpen -> {
                gateOpen = true
                gateOpenSince = t
                if (phase == BargeInPhase.SPEAKING && modelBusy && profile.confirm == BargeInConfirm.ENERGY) duck(t, DuckTrigger.GATE, out)
            }
            BargeInEvent.GateClose -> {
                gateOpen = false
                gateOpenSince = null
                // Below threshold before confirm and the server never agreed:
                // nothing was committed server-side, just restore.
                if (phase == BargeInPhase.DUCKED && duckTrigger == DuckTrigger.GATE) restoreToSpeaking(out)
            }
            is BargeInEvent.SpeechStarted -> onSpeechStarted(event.itemId, t, out)
            BargeInEvent.SpeechStopped -> {
                serverSpeaking = false
                // A held turn is asked for TURN_HOLD_MS after the user's last
                // sound, whether or not that segment produced a transcript.
                if (pendingCreate) out += BargeInCommand.StartTimer(TURN_HOLD_MS)
                // A blip: nothing confirmed it. Its completed transcript decides
                // whether anything is answered (no words → nothing).
                if (phase == BargeInPhase.DUCKED && duckTrigger == DuckTrigger.SERVER) restoreToSpeaking(out)
            }
            is BargeInEvent.Transcription -> onTranscription(event.text, event.itemId, event.final, t, out)
            is BargeInEvent.AssistantTranscript -> {
                val words = tokens(event.delta)
                if (words.isNotEmpty()) {
                    var cur = spokenCurrent + words
                    if (cur.size > SPOKEN_CAP) cur = cur.drop(cur.size - SPOKEN_CAP)
                    spokenCurrent = cur
                    spokenSet = (spokenPrevious + spokenCurrent).map(::stem).toSet()
                }
            }
            BargeInEvent.Tick -> {
                val since = duckedSince
                if (phase == BargeInPhase.DUCKED && since != null && t - since >= profile.confirmMs) {
                    // CONFIRM needs evidence from both sides: the server VAD is
                    // inside a speech segment AND the mic is still above the
                    // gate — sound that lasted the whole confirm window. The
                    // timer alone confirmed nothing: the server's speech_stopped
                    // can only arrive after silence_duration_ms (600) of silence,
                    // i.e. never inside a 300 ms window, so every VAD blip on a
                    // loudspeaker — a tap, a chair, a cough — cancelled the reply
                    // (Ahmad's iPhone, 2026-09-17: "interrupted by any noise").
                    if (gateOpen && serverSpeaking) cancel(t, out) else restoreToSpeaking(out)
                }
                out += tryAsk(t)
            }
            BargeInEvent.InterruptPressed -> {
                if (phase != BargeInPhase.HOLD) {
                    pendingTurnSince = null   // the user wants silence, not the next reply
                    if (modelBusy) cancel(t, out, hard = true)
                }
            }
            is BargeInEvent.Error -> when {
                isBenignError(event.message) -> onBenignActiveResponseError(t, out)
                // A too-short press committed nothing: the session is intact (mic,
                // socket, comm mode all live), so go back to the hold prompt instead
                // of a dead ERROR screen. Only in hold mode — in open-mic mode a
                // buffer error is a real protocol fault and is surfaced.
                holdToTalk && isEmptyBufferError(event.message) -> ui(uiStateNow, out)
                else -> out += BargeInCommand.ReportError(event.message?.takeIf { it.isNotBlank() } ?: "Voice error", event.code)
            }
            is BargeInEvent.RouteChanged -> {
                if (event.profile != profile) {
                    profile = event.profile
                    if (!holdToTalk) out += BargeInCommand.SessionUpdate(turnDetection())
                }
            }
            BargeInEvent.PttDown -> {
                if (holdToTalk && phase != BargeInPhase.HOLD) {
                    if (modelBusy) cancel(t, out, hard = true)
                    phase = BargeInPhase.HOLD
                    pttPressed = true
                    out += BargeInCommand.ForceGate(true)
                    ui(BargeInUi.LISTENING, out)
                }
            }
            BargeInEvent.PttUp -> {
                if (holdToTalk && phase == BargeInPhase.HOLD) {
                    // The press already cancelled + flushed whatever was playing; the
                    // reply to this turn arrives as a fresh response.created.
                    phase = BargeInPhase.IDLE
                    pttPressed = false
                    out += BargeInCommand.ForceGate(false)
                    // A tool still running (or its results unread): the commit
                    // and its ask go out as the continuation — a create now would
                    // beat the outputs. Outputs that came in while the orb was
                    // held (nothing is asked over a hold) are read right here.
                    val ask = if (toolsPending) { pttCommitOwed = true; tryAsk(t) } else listOf(BargeInCommand.CommitAndRespond)
                    out += ask
                    answeredTurns += 1
                    if (ask.none { it is BargeInCommand.Ui }) ui(BargeInUi.THINKING, out)
                }
            }
            is BargeInEvent.ToolCallStarted -> {
                if (toolCalls.isEmpty()) {
                    toolsSince = t
                    // The tick that stops waiting for a tool that never answers.
                    out += BargeInCommand.StartTimer(TOOL_WAIT_MAX_MS + 1)
                }
                toolCalls += event.callId
            }
            is BargeInEvent.ToolCallFinished -> {
                // A late output (the wait gave up on it) is still read, but it
                // never stands in for another call that is still running.
                toolCalls -= event.callId
                continuationSince = t
                if (toolCalls.isEmpty()) {
                    toolsSince = null
                    // Coalesced: the tick asks CONTINUE_DELAY_MS after the LAST
                    // output — or the done of the reply still generating does. A
                    // create into that reply is refused, and the continuation
                    // went with it: only one we cancelled (whose done may never
                    // come) gets the 2.5 s fallback; one we did not, 10 s.
                    out += BargeInCommand.StartTimer(CONTINUE_DELAY_MS)
                    if (responseActive) out += BargeInCommand.StartTimer((if (activeCancelled) PENDING_CREATE_FALLBACK_MS else TOOL_WAIT_MAX_MS) + 1)
                }
            }
        }
        return out
    }

    /** Switch hold-to-talk on/off mid-session (emits the matching session.update). */
    fun setHoldToTalk(on: Boolean): List<BargeInCommand> {
        val out = ArrayList<BargeInCommand>(3)
        if (on == holdToTalk) return out
        holdToTalk = on
        if (phase == BargeInPhase.DUCKED) restoreToSpeaking(out)
        if (!on && phase == BargeInPhase.HOLD) {
            phase = BargeInPhase.IDLE
            pttPressed = false
            out += BargeInCommand.ForceGate(false)
        }
        out += BargeInCommand.SessionUpdate(turnDetection())
        return out
    }

    // ── events ──

    private fun onSpeechStarted(itemId: String?, t: Long, out: MutableList<BargeInCommand>) {
        serverSpeaking = true
        // Echo needs AUDIO: the reply on air, or just drained — its tail is
        // still in the room and comes back as a segment of its own. A model
        // that is only thinking (its transcript arrives ~1 s before its audio)
        // has said nothing aloud yet.
        val onAir = playbackQueued
        val inGrace = !onAir && lastDrained?.let { t - it.second <= DRAIN_ECHO_GRACE_MS } == true
        flushPendingDeletes(except = itemId, out)
        segments += Segment(itemId, echoPossible = onAir || inGrace, onAir = onAir)
        // They go on talking before their turn was asked for: hold from here.
        if (pendingCreate && !(onAir || inGrace)) pendingTurnSince = t
        while (segments.size > SEGMENT_HISTORY) segments.removeAt(0)
        when {
            // Words decide. The server VAD hears the loudspeaker's echo on
            // every reply, so ducking here would dim every reply.
            phase == BargeInPhase.SPEAKING && modelBusy && profile.confirm == BargeInConfirm.TRANSCRIPT -> Unit
            phase == BargeInPhase.SPEAKING && modelBusy -> duck(t, DuckTrigger.SERVER, out)
            // The server agrees with the gate — that's speech.
            phase == BargeInPhase.DUCKED && duckTrigger == DuckTrigger.GATE -> cancel(t, out)
        }
    }

    private fun onTranscription(text: String, itemId: String?, final: Boolean, t: Long, out: MutableList<BargeInCommand>) {
        // Energy routes: an accelerator, under the same two-sided rule as the
        // tick — a transcript with the mic already closed is not the user
        // talking over (deltas also stream for the model's echo).
        if (profile.confirm == BargeInConfirm.ENERGY && phase == BargeInPhase.DUCKED && gateOpen) cancel(t, out)
        val words = tokens(text)
        val index = segmentIndex(itemId)
        val alreadyCancelled = activeResponseId != null && activeResponseId == cancelledResponseId
        if (!final) {
            // Streaming words. On the loudspeaker the first REAL ones of a
            // segment we saw begin, while a reply is busy, stop it — once.
            // A segment we never saw begin never cuts a reply.
            if (profile.confirm != BargeInConfirm.TRANSCRIPT || words.isEmpty() || !modelBusy || alreadyCancelled || index == null) return
            val segment = segments[index]
            if (segment.echoJudged) return
            if (segment.echoPossible) {
                // The live guess grows word by word while they speak; the
                // reply is cut the moment it is clearly theirs, not when the
                // segment ends (which, on the loudspeaker, is when the reply
                // pauses — iOS device log 2026-09-20 00:04: three
                // interruptions "ignored until it finished").
                lastEchoScore = score(words)
                if (!isEarlyInterruption(words, segment.onAir)) return
            }
            cancel(t, out)
            return
        }
        if (index != null && segments[index].responded) return   // a completed transcript re-sent
        val id = index?.let { segments[it].itemId } ?: itemId
        if (index == null) {
            if (words.isEmpty()) {
                if (id != null) out += BargeInCommand.DeleteItem(id)   // no words, no segment: nothing to wait for
                return
            }
            // No segment we saw begin: a server that sends no speech_started,
            // or the ASR of the turn a reply is already answering, landing
            // late (it used to wipe the reply's first words). Caption it;
            // answer it only if nothing is on air; never cut a reply on it.
            out += BargeInCommand.UserTurn(text)
            if (!holdToTalk && !modelBusy) {
                pendingTurnSince = t
                answeredTurns += 1
                out += BargeInCommand.StartTimer(TURN_HOLD_MS)
                ui(BargeInUi.THINKING, out)
            }
            return
        }
        val segment = segments[index]
        var notATurn = words.isEmpty()                              // a cough, "um", "…", an echo heard as Chinese
        if (!notATurn && segment.echoJudged && words.size < 3) {
            notATurn = true                                         // a later piece of the echo already judged
        } else if (!notATurn && words.size == 1) {
            // One word is the user: "Morning." answering "Morning. Want to
            // walk through today?" was deleted as echo of the greeting
            // (Zubair's morning call, 2026-09-21 07:01) and the call went
            // nowhere until "Hello?". With echo cancellation on, a one-word
            // echo that reaches the transcriber is rarer than a one-word
            // answer that shares the reply's word (parity with iOS build 77).
        } else if (!notATurn && (segment.onAir || segment.echoJudged)) {
            // Judged by its words only when it began while the reply's
            // audio was ON AIR. After the drain the words are the user's:
            // with echo cancellation on no tail echo has reached the
            // transcriber, and that window is exactly when they answer —
            // "Have you set up the call?", "What is today?" were deleted as
            // echo of the question they answered (assistant_turns,
            // 2026-09-20 15:21 / 15:36).
            lastEchoScore = score(words)
            notATurn = isEcho(words, true)                          // the model's own words, back through the mic
        }
        if (notATurn) {
            if (words.isNotEmpty()) segments[index] = segment.copy(echoJudged = true)
            if (id != null && id !in _pendingDeletes) _pendingDeletes += id
            return
        }
        // The user's turn — possibly riding on the echo's tail inside the
        // same segment; then the whole item is theirs and stays.
        if (id != null) _pendingDeletes.remove(id)
        segments[index] = segments[index].copy(echoJudged = false)
        out += BargeInCommand.UserTurn(text)
        if (holdToTalk) return   // the release already committed + asked
        segments[index] = segments[index].copy(responded = true)
        answeredTurns += 1
        rateLimitRetries = 0
        flushPendingDeletes(except = null, out)   // before the ask: the model never sees the echo items
        if (modelBusy && !alreadyCancelled) cancel(t, out)
        // Not asked for yet: the hold first (they may be mid-sentence),
        // and, if a cancel is in flight, its done — or the fallback.
        pendingTurnSince = t
        out += BargeInCommand.StartTimer(TURN_HOLD_MS)
        if (responseActive) out += BargeInCommand.StartTimer(PENDING_CREATE_FALLBACK_MS)
        ui(BargeInUi.THINKING, out)
    }

    /** "no active response" / "already has an active response": the server
     *  and we disagree about what's generating — resync, never an error state. */
    private fun onBenignActiveResponseError(t: Long, out: MutableList<BargeInCommand>) {
        responseActive = false
        createSentAt = null
        when {
            phase == BargeInPhase.DUCKED -> {
                duckedSince = null; duckTrigger = null
                out += BargeInCommand.Restore
                phase = if (playbackQueued) BargeInPhase.SPEAKING else BargeInPhase.IDLE
            }
            phase == BargeInPhase.SPEAKING && !playbackQueued -> phase = BargeInPhase.IDLE
        }
        if (pendingCreate || toolsPending) {
            // Our cancel found nothing to cancel — the reply had already
            // finished. Its done is not coming; ask once the hold is up (and,
            // with a tool's results owed, once its outputs are out).
            val ask = tryAsk(t)
            if (ask.isEmpty()) out += BargeInCommand.StartTimer(if (pendingCreate) TURN_HOLD_MS else CONTINUE_DELAY_MS) else out += ask
        } else {
            ui(uiStateNow, out)
        }
    }

    // ── transcript confirm ──

    /** The segment a transcript belongs to: by item id when both sides carry
     *  one; otherwise (id-less on either side) the most recent segment. Two
     *  different ids is no match — never attribute one item's words to
     *  another's segment. */
    private fun segmentIndex(itemId: String?): Int? {
        if (itemId != null) {
            val i = segments.indexOfLast { it.itemId == itemId }
            if (i >= 0) return i
        }
        val last = segments.lastIndex
        if (last < 0) return null
        return if (itemId == null || segments[last].itemId == null) last else null
    }

    /** The held turn — and/or the continuation owed after tool outputs —
     *  asked for when it is ready: no tool output still to come, the hold has
     *  elapsed since the user was last heard, the server VAD is silent, the
     *  outputs settled [CONTINUE_DELAY_MS] (or the reply that carried the
     *  calls is done: [afterDone]), and no reply is generating — or the
     *  cancelled reply's done never came (the fallback). ONE create answers
     *  both: the user's words and the outputs are all in the conversation. */
    private fun tryAsk(t: Long, afterDone: Boolean = false): List<BargeInCommand> {
        val turn = pendingTurnSince
        val cont = continuationSince
        if (turn == null && cont == null && !pttCommitOwed) return emptyList()
        // A tool is still running: a reply asked for now is generated without
        // its result (Zubair's morning call, 2026-09-24). Its output's
        // ToolCallFinished asks; one that never answers is waited out.
        if (toolCalls.isNotEmpty()) {
            val since = toolsSince
            if (since != null && t - since < TOOL_WAIT_MAX_MS) return emptyList()
            toolCalls.clear()
            toolsSince = null
        }
        // Holding the orb: nothing is asked over them. The release commits
        // their words and asks, and the continuation rides on it — asked here,
        // its reply played over the hold and the release's create collided
        // with it (their turn went unanswered).
        if (phase == BargeInPhase.HOLD && turn == null) return emptyList()
        val notBefore = retryNotBefore
        if (notBefore != null && t < notBefore) return listOf(BargeInCommand.StartTimer(notBefore - t))
        if (responseActive) {
            // The fallback, from the turn (as ever) or, with none, the outputs.
            // The continuation alone never goes into a reply we did not cancel
            // before 10 s: its done comes and asks (afterDone); a create into
            // it would only be refused, and the continuation lost with it.
            val since = turn ?: cont ?: return emptyList()
            val wait = if (turn == null && !activeCancelled) TOOL_WAIT_MAX_MS else PENDING_CREATE_FALLBACK_MS
            if (t - since < wait) return emptyList()
            responseActive = false
        } else {
            if (turn != null && (t - turn < TURN_HOLD_MS || serverSpeaking)) return emptyList()
            if (cont != null && !afterDone && t - cont < CONTINUE_DELAY_MS) return emptyList()
        }
        val sent = createSentAt
        if (sent != null && t - sent < CREATE_GRACE_MS) {
            // Our create is in flight: wait the grace out, then ask again if
            // nothing was created. The turn stays pending until response.created
            // (parity with iOS build 75).
            return listOf(BargeInCommand.StartTimer(CREATE_GRACE_MS - (t - sent) + 1))
        }
        createSentAt = t
        // Whatever this create answers, it goes out after every output sent
        // so far: the continuation is no longer owed.
        continuationSince = null
        val ask = if (pttCommitOwed) BargeInCommand.CommitAndRespond else BargeInCommand.CreateResponse
        pttCommitOwed = false
        // The grace's own tick. Asked from idle, no done or error may ever
        // come, and a swallowed create then waited for the user to speak
        // again (review of the 2026-09-23 parity port). Once created, the
        // tick finds nothing pending.
        return listOf(ask, BargeInCommand.StartTimer(CREATE_GRACE_MS + 1), uiTracked(BargeInUi.THINKING))
    }

    /** The held deletes, as commands — all of them, or all but one item's. */
    private fun flushPendingDeletes(except: String?, out: MutableList<BargeInCommand>) {
        val due = _pendingDeletes.filter { it != except }
        _pendingDeletes.retainAll { it == except }
        for (id in due) out += BargeInCommand.DeleteItem(id)
    }

    private fun score(heard: List<String>): EchoScore {
        val e = echoEvidence(heard)
        return EchoScore(e.hits, e.heard, spokenPrevious.size + spokenCurrent.size)
    }

    /** Content words heard vs the reference (whole utterance if it has none),
     *  and the stretches BEFORE the first / AFTER the last content word the
     *  model said: how many words each, and whether either holds a content
     *  word the model never said. */
    private class EchoEvidence {
        var hits = 0; var heard = 0
        var fallback = false
        var leading = 0; var leadingMisses = 0
        var trailing = 0; var trailingMisses = 0
        var firstContentIsHit = false
    }

    /** A heard word matches a said one outright, or is the START of one at
     *  least two letters longer: the transcriber heard "Alright" as "All
     *  right" and caught "anything" mid-word as "any" (iOS device log
     *  2026-09-20 00:25, both cut the reply). Three letters at least, so "on"
     *  is not "Monday". */
    private fun matches(t: String): Boolean {
        val s = stem(t)
        if (s in spokenSet) return true
        if (s.length < 3) return false
        return spokenSet.any { it.length >= s.length + 2 && it.startsWith(s) }
    }

    private fun echoEvidence(heard: List<String>): EchoEvidence {
        val e = EchoEvidence()
        val isContent: (String) -> Boolean = { it !in STOP_WORDS }
        val isHit: (String) -> Boolean = { isContent(it) && matches(it) }
        // A content word the model never said — of three letters or more:
        // "go" alone cut a reply ("Have to go", same log).
        val isMiss: (String) -> Boolean = { isContent(it) && !matches(it) && it.length >= 3 }
        val content = heard.filter(isContent)
        val judged = if (content.isEmpty()) heard else content
        e.hits = judged.count { matches(it) }
        e.heard = judged.size
        e.fallback = content.isEmpty()
        e.firstContentIsHit = content.firstOrNull()?.let(isHit) ?: false
        val first = heard.indexOfFirst(isHit).takeIf { it >= 0 }
        val last = heard.indexOfLast(isHit).takeIf { it >= 0 }
        val head = heard.subList(0, first ?: heard.size)
        val tail = if (last != null) heard.subList(last + 1, heard.size) else heard
        e.leading = head.size
        e.leadingMisses = head.count(isMiss)
        e.trailing = tail.size
        e.trailingMisses = tail.count(isMiss)
        return e
    }

    /** While a segment is still open, from the transcriber's live guess: cut
     *  the reply only on clear evidence — three or more words, the first
     *  content word not one the model said (the user's words come first in a
     *  mixed segment; an echo's do not), at least one content word the model
     *  never said, and not echo by the usual rules. A garbled echo ("Lucks
     *  pretty solid") stays echo; "How will this be like" cuts at word five. */
    fun isEarlyInterruption(heard: List<String>, onAir: Boolean): Boolean {
        if (heard.size < 3) return false
        if (spokenSet.isEmpty()) return true
        val e = echoEvidence(heard)
        if (e.fallback || e.firstContentIsHit || e.leadingMisses + e.trailingMisses < 1) return false
        return !isEcho(heard, onAir)
    }

    /** Echo: the content words heard are words the model just said. Not all
     *  of them — the transcriber garbles short echoes ("Saturday's clear" →
     *  "Saturday's players", 1 of 2). While the reply's audio is ON AIR half
     *  is enough: echo is the likeliest source of a match, and a two-word
     *  interruption that shares one topic word can be repeated once the reply
     *  ends. In the drain grace only the reply's tail can echo, so a
     *  follow-up sharing one word ("Tuesday morning" after "Tuesday's wide
     *  open") stays a turn: 60 %. Filler-only utterances: 70 % of all words. */
    fun isEcho(heard: List<String>, onAir: Boolean = true): Boolean {
        if (heard.isEmpty() || spokenSet.isEmpty()) return false
        // Four words or more: echo only when every CONTENT word is one the
        // model said and at most ONE filler is one it never said. An echo
        // that survives echo cancellation is short and garbled; a longer
        // utterance that shares most of the reply's words is the user
        // answering in its terms — "Book the cool call now" over "…book a
        // quick call now?" was deleted at 3 of 4 (2026-09-20 15:37) and the
        // call was never booked. The one filler: the transcriber slips one
        // into an echo ("Coming up on Friday" for "…and Friday coming up.",
        // "Monday is open"), but the user's framing adds more ("HAVE YOU set
        // up the call?").
        if (heard.size >= ECHO_VERBATIM_FROM) {
            val unsaid = heard.filter { !matches(it) }
            return unsaid.size <= 1 && unsaid.all { it in STOP_WORDS }
        }
        val e = echoEvidence(heard)
        // Their words and the echo's in ONE segment: a question riding on the
        // echo's tail ("…coming up on Friday. What about Monday?" — the reply
        // ended, they spoke before the VAD's 600 ms), or their interruption
        // with the echo of what played after it ("How will this be like?
        // You've got a few tasks wrapped up", iOS device log 2026-09-20).
        // Three or more words before the first / after the last word the
        // model said, with a content word among them it never said, are
        // theirs, whatever the ratio. A garbled echo differs by one word.
        if (!e.fallback && e.trailing >= 3 && e.trailingMisses >= 1) return false
        if (!e.fallback && e.leading >= 3 && e.leadingMisses >= 1) return false
        val threshold = if (e.fallback) 0.7 else if (onAir) 0.5 else 0.6
        return e.hits.toDouble() / e.heard >= threshold
    }

    // ── transitions ──

    private fun duck(t: Long, trigger: DuckTrigger, out: MutableList<BargeInCommand>) {
        phase = BargeInPhase.DUCKED
        duckedSince = t
        duckTrigger = trigger
        out += BargeInCommand.Duck
        out += BargeInCommand.StartTimer(profile.confirmMs)
    }

    private fun restoreToSpeaking(out: MutableList<BargeInCommand>) {
        phase = if (modelBusy) BargeInPhase.SPEAKING else BargeInPhase.IDLE
        duckedSince = null
        duckTrigger = null
        falseBargeIns += 1
        out += BargeInCommand.Restore
        noteFalseBargeIn(out)
    }

    /** CANCEL: stop the reply for good. `hard` = the Interrupt button, which
     *  never went through DUCKED; the restore is idempotent either way and
     *  keeps the NEXT reply at unity. `responseActive` stays true until the
     *  server's done: the pending ask waits for it. */
    private fun cancel(t: Long, out: MutableList<BargeInCommand>, hard: Boolean = false) {
        if (responseActive) {
            cancelledResponseId = activeResponseId
            out += BargeInCommand.SendCancel
        } else if (activeResponseId != null) {
            // Only the buffered tail was left; make sure late deltas for it
            // (if any) stay dropped.
            cancelledResponseId = activeResponseId
        }
        out += BargeInCommand.FlushPlayback
        if (playbackQueued) {
            // The last of the flushed audio is already in the room and comes
            // back as a segment of its own, exactly as after a natural drain
            // (iOS device log 2026-09-20 00:04:43: "And Friday." 90 ms after a flush).
            lastDrained = playingResponseId to t
            playingResponseId = null
        }
        playbackQueued = false
        muted = true
        out += BargeInCommand.Restore
        out += BargeInCommand.ClearCaption
        ui(BargeInUi.LISTENING, out)
        phase = BargeInPhase.IDLE
        duckedSince = null
        duckTrigger = null
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

    /** Emit a UI state (every time, like iOS — the screen dedupes). */
    private fun ui(state: BargeInUi, out: MutableList<BargeInCommand>) { out += uiTracked(state) }
    private fun uiTracked(state: BargeInUi): BargeInCommand { ui = state; return BargeInCommand.Ui(state) }
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
     * @param forcedClosed  half-duplex ([BargeInProfile.gateForcedClosed] — no
     *   shipped profile since 2026-09-20): the gate is held shut — digital
     *   silence out, the pre-roll discarded — so nothing from the mic reaches
     *   the server. Hold-to-talk's [forceOpen] wins over it.
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
