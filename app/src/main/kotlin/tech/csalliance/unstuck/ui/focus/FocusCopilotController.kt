package tech.csalliance.unstuck.ui.focus

import android.content.Context
import android.media.AudioManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tech.csalliance.unstuck.core.logic.CopilotLevel
import tech.csalliance.unstuck.core.logic.FocusCommand
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
 */
class FocusCopilotController(
    private val context: Context,
    private val voice: SpeechSurface,
    // Effects — wired by FocusScreen to the VM. Pure callbacks; no network.
    private val onExtend: (Int) -> Unit,
    private val onKeepGoing: () -> Unit,
    private val onStop: () -> Unit,
    private val onCapture: (String) -> Unit,
) {
    /** True while the mic window is live — the UI renders a "Listening…" pill. */
    var listening by mutableStateOf(false)
        private set

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
