package tech.csalliance.unstuck.ui.focus

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tech.csalliance.unstuck.core.logic.CopilotLevel
import tech.csalliance.unstuck.core.logic.FocusCopilot
import tech.csalliance.unstuck.core.logic.FocusEffect
import tech.csalliance.unstuck.core.logic.FocusMilestone
import tech.csalliance.unstuck.surface.AmbientAudio
import tech.csalliance.unstuck.ui.assistant.SpeechSurface

/** The ambient-loop ducking seam: [AmbientAudio] in production, a recorder in
 *  unit tests (so "ambient is restored after every speak" is assertable). */
interface AmbientDucker {
    fun duck()
    fun unduck()
}

/** Production ducker — the focus screen's ambient loop. */
object AmbientAudioDucker : AmbientDucker {
    override fun duck() = AmbientAudio.duck()
    override fun unduck() = AmbientAudio.unduck()
}

/**
 * Hands-Free Focus Copilot — Phase 1 controller (:app wiring).
 *
 * Drives the PURE [FocusCopilot] brain off the focus timer tick: when a
 * milestone comes due it SPEAKS the line via the reused on-device
 * [VoiceController] (TextToSpeech). For QUESTION milestones, if "Voice replies"
 * is enabled, it then opens a short (~6s) [SpeechRecognizer] window, runs the
 * heard text through [FocusCopilot.parseCommand] (keyword only — ZERO LLM /
 * network), and dispatches the resolved [FocusEffect] to the supplied callbacks
 * (which the FocusScreen wires to AppViewModel: extend / keepGoing / finish /
 * saveCapture). It ducks the ambient loop while speaking/listening and restores
 * it after.
 *
 * TTS SEQUENCING (the coach must never hear itself): the listen window opens
 * ONLY from the speech surface's per-utterance completion — never at the same
 * instant the question starts playing — so the recognizer cannot transcribe
 * "…stop, or keep going?" and resolve the coach's own voice to Stop. A
 * generous fallback timer ([FocusCopilot.speechFallbackMs]) covers an engine
 * that never reports completion; a missing/refused engine completes at once
 * (VoiceController). The TTS init race (first line of a session buffered until
 * the engine is ready) is covered by the same mechanism: the completion is
 * attached to the buffered utterance and fires only once it was actually
 * spoken.
 *
 * Guardrails (all enforced here):
 *  - ZERO LLM/network: this class has NO assistant/network dependency. The only
 *    "brain" is the pure FocusCopilot; effects route through plain callbacks.
 *  - Mic only DURING a session, only the short post-question window, transcript
 *    never stored/sent (parsed in-memory then discarded), with a live [listening]
 *    indicator the UI shows.
 *  - Cadence respected, no double-fire (the fired set / overrun count gate it),
 *    overrun capped at 2, keepGoing suppresses overrun.
 *  - A milestone never fires on top of a live listen window, a live push-to-talk
 *    CAPTURE window, or a line still being spoken — it is DEFERRED (left
 *    unfired) so the next quiet tick delivers it, never dropped.
 *  - Doesn't speak if muted / on a call / disabled.
 *  - Ambient ducking is ALWAYS restored: speak-only lines restore on completion,
 *    question lines hand over to the listen window which restores on close.
 *  - FAIL-SAFE: every TTS/STT/permission call is wrapped — any failure degrades
 *    silently to the existing visual buttons and NEVER stops/corrupts the timer.
 *  - No voice command deletes data (only stop/extend/keepGoing/capture).
 *
 * Phase 1.5 — Push-to-talk capture (the deliberate-tap dictation path):
 *  - [toggleCapture] opens the SAME on-device [SpeechSurface] listen window on an
 *    EXPLICIT user tap, and on result saves the heard text VERBATIM as a capture
 *    via [onCapture]. It runs the transcript through the PURE
 *    [FocusCopilot.captureFromTranscript] helper only — it NEVER touches
 *    [FocusCopilot.parseCommand], so "I should stop procrastinating" is saved
 *    word-for-word and never interpreted as a stop command. Same ZERO LLM /
 *    network / off-device guarantee; same fail-safe wrapping (a throwing speech
 *    surface can never stop/corrupt the focus timer).
 */
class FocusCopilotController(
    private val context: Context,
    private val voice: SpeechSurface,
    // Effects — wired by FocusScreen to the VM. Pure callbacks; no network.
    private val onExtend: (Int) -> Unit,
    private val onKeepGoing: () -> Unit,
    private val onStop: () -> Unit,
    private val onCapture: (String) -> Unit,
    // Push-to-talk capture result — wired to a transient on-screen confirm
    // ("Captured." / "Didn't catch that."). Optional; pure local callback.
    private val onCaptureResult: (CaptureResult) -> Unit = {},
    private val ambient: AmbientDucker = AmbientAudioDucker,
    /** Main-thread scheduler for the speech-completion fallback (tests drive
     *  it through Robolectric's paused looper). */
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    /** Outcome of a push-to-talk capture window, for the transient UI confirm. */
    enum class CaptureResult { SAVED, EMPTY }

    /** True while a Phase-1 voice-reply mic window is live — the UI renders a
     *  "Listening…" pill. */
    var listening by mutableStateOf(false)
        private set

    /** True while a push-to-talk CAPTURE mic window is live — the UI renders the
     *  same "Listening…" pill and the capture button reads as active/cancelable. */
    var capturing by mutableStateOf(false)
        private set

    /** True while a coach line is being spoken (until the speech surface reports
     *  completion, or the fallback fires). Milestones defer behind it. */
    val speaking: Boolean get() = pendingSpeech != null

    /** True if the on-device speech recognizer is available (gates the button). */
    val captureAvailable: Boolean get() = runCatching { voice.sttAvailable }.getOrDefault(false)

    // Per-session state (reset by [reset]).
    private val fired = mutableSetOf<FocusMilestone>()
    private var overrunCount = 0
    private var keepGoing = false
    private var lastSpokenAtSec = -100 // throttle so two milestones don't talk over each other

    /** The single in-flight utterance's continuation + its fallback timer.
     *  QUEUE_FLUSH semantics: a newer line replaces (and silently drops) it. */
    private class PendingSpeech(val then: () -> Unit) { lateinit var fallback: Runnable }
    private var pendingSpeech: PendingSpeech? = null

    /** Reset for a fresh session (or on pause/end teardown). Idempotent. */
    fun reset() {
        fired.clear()
        overrunCount = 0
        keepGoing = false
        lastSpokenAtSec = -100
        stopAll()
    }

    /** Tear down — cut any speech/listen and restore ambient. Safe to call repeatedly. */
    fun stopAll() {
        listening = false
        capturing = false
        cancelPendingSpeech()
        runCatching { voice.stopListening() }
        runCatching { voice.stopSpeaking() }
        runCatching { ambient.unduck() }
    }

    /**
     * Called on every timer tick with the ACCUMULATED focus seconds (paused time
     * already excluded — FocusScreen passes FocusTimer.displayedElapsedSec while
     * running, never while paused). Fires at most one milestone per call. The
     * whole body is fail-safe.
     *
     * @param enabled        the "Spoken focus coach" toggle (off → never speaks).
     * @param voiceReplies   the "Voice replies" sub-toggle (off → speak-only, no mic).
     */
    fun onTick(
        estimateMin: Int,
        level: CopilotLevel,
        focusedSec: Int,
        enabled: Boolean,
        voiceReplies: Boolean,
    ) = runCatching {
        if (!enabled) return@runCatching
        // Never start a new prompt mid-listen, mid-CAPTURE (a milestone must not
        // destroy the user's dictation recognizer, dictate the coach's line into
        // it, or parse the dictation as a command), or mid-speech. The due
        // milestone stays unfired and lands on the next quiet tick.
        if (listening || capturing || speaking) return@runCatching
        val due = FocusCopilot.dueMilestone(estimateMin, level, focusedSec, fired, overrunCount, keepGoing)
            ?: return@runCatching
        // Don't talk on top of a just-spoken line (defensive against bunched ticks).
        if (focusedSec - lastSpokenAtSec < 2) return@runCatching
        // Respect the device: muted ring / active call → stay silent (visual buttons
        // remain). This is the "don't speak if muted/on call/off" guardrail.
        if (!canSpeakNow()) {
            markFired(due) // count it as handled so we don't backlog a burst of catch-ups
            return@runCatching
        }
        lastSpokenAtSec = focusedSec
        markFired(due)

        val line = FocusCopilot.line(due, estimateMin, focusedSec)
        // Statements (HALFWAY) are speak-only. Questions optionally open the mic —
        // ONLY once the line has actually been spoken (see the header).
        if (due.isQuestion && voiceReplies) {
            speak(line, then = { openListenWindow() })
        } else {
            speak(line)
        }
    }.getOrElse {
        // Any failure: degrade silently — the focus timer + visual buttons are unaffected.
        stopAll()
    }

    /** A spoken command landed → run it through the pure brain + dispatch the effect. */
    private fun handleUtterance(utterance: String) = runCatching {
        // Parse is pure keyword matching — NO LLM, NO network. The transcript is
        // used here and then dropped; it is never stored or transmitted.
        val command = FocusCopilot.parseCommand(utterance)
        val effect = FocusCopilot.effectFor(command)
        applyEffect(effect)
    }.getOrElse { stopAll() }

    private fun applyEffect(effect: FocusEffect) {
        when (effect) {
            is FocusEffect.Extend -> { runCatching { onExtend(effect.minutes) }; speak(effect.ack) }
            is FocusEffect.KeepGoing -> { keepGoing = true; runCatching { onKeepGoing() }; speak(effect.ack) }
            is FocusEffect.Stop -> { runCatching { onStop() }; speak(effect.ack) }
            is FocusEffect.Capture -> { runCatching { onCapture(effect.text) }; speak(effect.ack) }
            is FocusEffect.None -> { /* unrecognized — stay quiet, visual buttons remain */ }
        }
    }

    // --- push-to-talk capture (Phase 1.5: pure dictation, deliberate tap) ---

    /**
     * Deliberate-tap entry point for the focus-screen capture button. Tapping
     * while idle OPENS the on-device listen window for a verbatim capture; tapping
     * again WHILE capturing CANCELS it (saves nothing). Fully fail-safe — any
     * STT/permission error degrades silently and never disturbs the focus timer.
     *
     * Guardrails on this path:
     *  - Reuses the same on-device [SpeechSurface] — nothing leaves the device.
     *  - The mic opens ONLY on this explicit tap and closes on result/timeout/
     *    cancel.
     *  - The transcript is fed ONLY to the pure [FocusCopilot.captureFromTranscript]
     *    and then to [onCapture]; it is NEVER run through [parseCommand], stored, or
     *    transmitted. (So "I should stop procrastinating" saves verbatim.)
     *  - A coach line still being spoken is cut first (the user's tap wins), so
     *    the dictation never transcribes the coach's voice.
     */
    fun toggleCapture() = runCatching {
        if (capturing) { cancelCapture(); return@runCatching }
        if (listening) return@runCatching // don't fight a live voice-reply window
        if (!voice.sttAvailable) return@runCatching
        cancelPendingSpeech()
        runCatching { voice.stopSpeaking() }
        capturing = true
        ambient.duck()
        voice.startListening(
            onPartial = { /* live transcript — shown nowhere, never stored */ },
            onFinal = { text -> handleCaptureTranscript(text) },
            onDone = {
                // Always restore on close (ok / error / timeout / cancel) — unless
                // the confirm ack is already speaking (it restores on completion).
                capturing = false
                if (!speaking) runCatching { ambient.unduck() }
            },
        )
    }.getOrElse {
        // Any failure: degrade silently — the focus timer is unaffected.
        capturing = false
        runCatching { ambient.unduck() }
    }

    /** Cancel an in-flight capture window without saving anything. */
    fun cancelCapture() = runCatching {
        capturing = false
        runCatching { voice.stopListening() }
        runCatching { ambient.unduck() }
    }.getOrElse {
        capturing = false
        runCatching { ambient.unduck() }
    }

    /**
     * A push-to-talk transcript landed → save it VERBATIM as a capture. This path
     * deliberately does NOT call [FocusCopilot.parseCommand]; it uses only the pure
     * [FocusCopilot.captureFromTranscript] (trim / blank→null). Blank saves nothing
     * and gives a "didn't catch that" confirm.
     */
    private fun handleCaptureTranscript(transcript: String) = runCatching {
        val body = FocusCopilot.captureFromTranscript(transcript)
        if (body == null) {
            runCatching { onCaptureResult(CaptureResult.EMPTY) }
            speak("Didn't catch that.")
            return@runCatching
        }
        runCatching { onCapture(body) }
        runCatching { onCaptureResult(CaptureResult.SAVED) }
        speak("Captured.")
    }.getOrElse { stopAll() }

    // --- voice plumbing (all fail-safe) ---

    /**
     * Speak [text] with the ambient ducked, then run [then] ONCE when the speech
     * surface reports the utterance finished — or when the fallback timer fires
     * first (an engine that never calls back). The default continuation restores
     * the ambient loop, so a speak-only line can never leave it ducked. A newer
     * speak() supersedes an older one (its continuation is dropped, mirroring
     * QUEUE_FLUSH); [stopAll] cancels outright.
     */
    private fun speak(text: String, then: () -> Unit = { runCatching { ambient.unduck() } }) = runCatching {
        if (text.isBlank()) return@runCatching
        cancelPendingSpeech()
        ambient.duck()
        val pending = PendingSpeech(then)
        val complete: () -> Unit = {
            // Only the CURRENT utterance's completion counts — a superseded or
            // cancelled one is a no-op (its timer was removed; a late engine
            // callback for it lands here and is ignored).
            if (pendingSpeech === pending) {
                pendingSpeech = null
                handler.removeCallbacks(pending.fallback)
                runCatching { pending.then() }
            }
        }
        pending.fallback = Runnable { complete() }
        pendingSpeech = pending
        handler.postDelayed(pending.fallback, FocusCopilot.speechFallbackMs(text))
        voice.speak(text) { complete() }
    }.getOrElse {
        cancelPendingSpeech()
        runCatching { ambient.unduck() }
    }

    /** Drop the in-flight continuation + its timer without running it. */
    private fun cancelPendingSpeech() {
        pendingSpeech?.let { runCatching { handler.removeCallbacks(it.fallback) } }
        pendingSpeech = null
    }

    private fun openListenWindow() = runCatching {
        if (!voice.sttAvailable) { runCatching { ambient.unduck() }; return@runCatching }
        listening = true
        ambient.duck()
        voice.startListening(
            onPartial = { /* live transcript — shown nowhere, never stored */ },
            onFinal = { text -> handleUtterance(text) },
            onDone = {
                // Always restore on close (ok or error/timeout) — unless an ack is
                // already speaking (it restores on completion). The recognizer's
                // own end-of-speech / error timeout bounds the window to a few
                // seconds.
                listening = false
                if (!speaking) runCatching { ambient.unduck() }
            },
        )
    }.getOrElse {
        listening = false
        runCatching { ambient.unduck() }
    }

    private fun markFired(m: FocusMilestone) {
        if (m == FocusMilestone.OVERRUN) overrunCount++ else fired += m
    }

    /** Don't speak when the ringer is silenced or a call is active. Best-effort. */
    private fun canSpeakNow(): Boolean = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return@runCatching true
        val ringerOk = am.ringerMode == AudioManager.RINGER_MODE_NORMAL
        val notInCall = am.mode != AudioManager.MODE_IN_CALL && am.mode != AudioManager.MODE_IN_COMMUNICATION
        ringerOk && notInCall
    }.getOrDefault(true)
}
