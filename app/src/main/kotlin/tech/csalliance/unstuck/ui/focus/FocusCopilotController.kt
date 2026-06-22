package tech.csalliance.unstuck.ui.focus

import android.content.Context
import android.media.AudioManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tech.csalliance.unstuck.core.logic.CopilotLevel
import tech.csalliance.unstuck.core.logic.FocusCopilot
import tech.csalliance.unstuck.core.logic.FocusEffect
import tech.csalliance.unstuck.core.logic.FocusMilestone
import tech.csalliance.unstuck.surface.AmbientAudio
import tech.csalliance.unstuck.ui.assistant.SpeechSurface

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
 * Guardrails (all enforced here):
 *  - ZERO LLM/network: this class has NO assistant/network dependency. The only
 *    "brain" is the pure FocusCopilot; effects route through plain callbacks.
 *  - Mic only DURING a session, only the short post-question window, transcript
 *    never stored/sent (parsed in-memory then discarded), with a live [listening]
 *    indicator the UI shows.
 *  - Cadence respected, no double-fire (the fired set / overrun count gate it),
 *    overrun capped at 2, keepGoing suppresses overrun.
 *  - Doesn't speak if muted / on a call / disabled.
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

    /** True if the on-device speech recognizer is available (gates the button). */
    val captureAvailable: Boolean get() = runCatching { voice.sttAvailable }.getOrDefault(false)

    // Per-session state (reset by [reset]).
    private val fired = mutableSetOf<FocusMilestone>()
    private var overrunCount = 0
    private var keepGoing = false
    private var lastSpokenAtSec = -100 // throttle so two milestones don't talk over each other

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
        runCatching { voice.stopListening() }
        runCatching { voice.stopSpeaking() }
        runCatching { AmbientAudio.unduck() }
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
        if (listening) return@runCatching // never start a new prompt mid-listen
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
        speak(line)

        // Statements (HALFWAY) are speak-only. Questions optionally open the mic.
        if (due.isQuestion && voiceReplies) {
            openListenWindow(focusedSec)
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
     */
    fun toggleCapture() = runCatching {
        if (capturing) { cancelCapture(); return@runCatching }
        if (listening) return@runCatching // don't fight a live voice-reply window
        if (!voice.sttAvailable) return@runCatching
        capturing = true
        AmbientAudio.duck()
        voice.startListening(
            onPartial = { /* live transcript — shown nowhere, never stored */ },
            onFinal = { text -> handleCaptureTranscript(text) },
            onDone = {
                // Always restore on close (ok / error / timeout / cancel).
                capturing = false
                runCatching { AmbientAudio.unduck() }
            },
        )
    }.getOrElse {
        // Any failure: degrade silently — the focus timer is unaffected.
        capturing = false
        runCatching { AmbientAudio.unduck() }
    }

    /** Cancel an in-flight capture window without saving anything. */
    fun cancelCapture() = runCatching {
        capturing = false
        runCatching { voice.stopListening() }
        runCatching { AmbientAudio.unduck() }
    }.getOrElse {
        capturing = false
        runCatching { AmbientAudio.unduck() }
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

    private fun speak(text: String) = runCatching {
        if (text.isBlank()) return@runCatching
        AmbientAudio.duck()
        voice.speak(text)
        // We don't get a reliable TTS-done callback through VoiceController; the
        // ambient is restored when the listen window closes, or here for speak-only.
    }.getOrElse { runCatching { AmbientAudio.unduck() } }

    private fun openListenWindow(@Suppress("UNUSED_PARAMETER") atSec: Int) = runCatching {
        if (!voice.sttAvailable) { runCatching { AmbientAudio.unduck() }; return@runCatching }
        listening = true
        AmbientAudio.duck()
        voice.startListening(
            onPartial = { /* live transcript — shown nowhere, never stored */ },
            onFinal = { text -> handleUtterance(text) },
            onDone = {
                // Always restore on close (ok or error/timeout). The recognizer's own
                // end-of-speech / error timeout bounds the window to a few seconds.
                listening = false
                runCatching { AmbientAudio.unduck() }
            },
        )
    }.getOrElse {
        listening = false
        runCatching { AmbientAudio.unduck() }
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
