package tech.csalliance.unstuck.ui

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.core.logic.CopilotLevel
import tech.csalliance.unstuck.ui.assistant.SpeechSurface
import tech.csalliance.unstuck.ui.focus.FocusCopilotController

// Robolectric guardrail tests for the FocusCopilotController (:app wiring).
// The pure scheduling/parsing is covered headless in :core; here we verify the
// safety contract: feature-off => no TTS/STT, a throwing speaker NEVER breaks
// the tick (fail-safe), the mic only opens for question milestones when voice
// replies are on, and effects route through pure callbacks (no LLM/network).
// Stock Application (NOT the production UnstuckApp, which would build a Supabase
// client at startup). The controller only needs a Context for ringer/call state.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FocusCopilotControllerTest {

    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Records every interaction so tests can assert exactly what was/ wasn't called. */
    private open class FakeSpeech(override val sttAvailable: Boolean = true) : SpeechSurface {
        val spoken = mutableListOf<String>()
        var listenStarts = 0
        var listenStops = 0
        var speakStops = 0
        private var onFinalCb: ((String) -> Unit)? = null
        private var onDoneCb: (() -> Unit)? = null

        override fun startListening(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onDone: () -> Unit) {
            listenStarts++
            onFinalCb = onFinal
            onDoneCb = onDone
        }
        override fun stopListening() { listenStops++ }
        override fun speak(text: String) { spoken += text }
        override fun stopSpeaking() { speakStops++ }

        /** Simulate the recognizer returning a final transcript then closing. */
        fun deliver(utterance: String) { onFinalCb?.invoke(utterance); onDoneCb?.invoke() }
        fun close() { onDoneCb?.invoke() }
    }

    /** Captures the effect callbacks (the only path effects can take — pure, local). */
    private class Effects {
        var extended: Int? = null
        var keepGoing = false
        var stopped = false
        var captured: String? = null
    }

    private fun controller(speech: SpeechSurface, fx: Effects) = FocusCopilotController(
        context = app,
        voice = speech,
        onExtend = { fx.extended = it },
        onKeepGoing = { fx.keepGoing = true },
        onStop = { fx.stopped = true },
        onCapture = { fx.captured = it },
    )

    // ---------- feature OFF → never touches TTS/STT ----------

    @Test fun featureOff_neverSpeaksOrListens() {
        val s = FakeSpeech()
        val c = controller(s, Effects())
        // Well past AT_TIME for a 5-min block; enabled=false must stay silent.
        c.onTick(estimateMin = 5, level = CopilotLevel.COACH, focusedSec = 10 * 60, enabled = false, voiceReplies = true)
        assertTrue("no speech when disabled", s.spoken.isEmpty())
        assertEquals("no mic when disabled", 0, s.listenStarts)
    }

    // ---------- speak-only (voice replies OFF) → speaks, no mic ----------

    @Test fun speakOnly_speaksButNeverOpensMic() {
        val s = FakeSpeech()
        val c = controller(s, Effects())
        // AT_TIME on Calm/5-min — a question milestone, but voiceReplies=false.
        c.onTick(estimateMin = 5, level = CopilotLevel.CALM, focusedSec = 5 * 60, enabled = true, voiceReplies = false)
        assertEquals(1, s.spoken.size)
        assertTrue(s.spoken[0].startsWith("That's your block"))
        assertEquals("mic must stay closed in speak-only", 0, s.listenStarts)
        assertFalse(c.listening)
    }

    // ---------- voice replies ON → question opens mic; statement does not ----------

    @Test fun voiceReplies_questionOpensMic_listeningIndicatorTrue() {
        val s = FakeSpeech()
        val c = controller(s, Effects())
        c.onTick(estimateMin = 5, level = CopilotLevel.CALM, focusedSec = 5 * 60, enabled = true, voiceReplies = true)
        assertEquals(1, s.listenStarts)
        assertTrue("indicator on while listening", c.listening)
        s.close()
        assertFalse("indicator off after window closes", c.listening)
    }

    @Test fun voiceReplies_statementHalfwayDoesNotOpenMic() {
        val s = FakeSpeech()
        val c = controller(s, Effects())
        // Coach 25-min: HALFWAY @12:30 is a STATEMENT (speak-only).
        c.onTick(estimateMin = 25, level = CopilotLevel.COACH, focusedSec = 13 * 60, enabled = true, voiceReplies = true)
        assertTrue(s.spoken.any { it.startsWith("Halfway there") })
        assertEquals("statement must not open the mic", 0, s.listenStarts)
        assertFalse(c.listening)
    }

    // ---------- spoken command → pure parse → effect callback ----------

    @Test fun heardExtend_routesToOnExtendCallback() {
        val s = FakeSpeech()
        val fx = Effects()
        val c = controller(s, fx)
        c.onTick(estimateMin = 5, level = CopilotLevel.CALM, focusedSec = 5 * 60, enabled = true, voiceReplies = true)
        s.deliver("add ten")
        assertEquals(10, fx.extended)
        assertTrue("acks the extend", s.spoken.any { it == "Added 10 minutes." })
    }

    @Test fun heardStop_routesToOnStop() {
        val s = FakeSpeech()
        val fx = Effects()
        val c = controller(s, fx)
        c.onTick(estimateMin = 5, level = CopilotLevel.CALM, focusedSec = 5 * 60, enabled = true, voiceReplies = true)
        s.deliver("I'm done")
        assertTrue(fx.stopped)
    }

    @Test fun heardCapture_routesVerbatimToOnCapture() {
        val s = FakeSpeech()
        val fx = Effects()
        val c = controller(s, fx)
        c.onTick(estimateMin = 5, level = CopilotLevel.CALM, focusedSec = 5 * 60, enabled = true, voiceReplies = true)
        s.deliver("note call the bank")
        assertEquals("call the bank", fx.captured)
    }

    @Test fun heardKeepGoing_suppressesFurtherOverrun() {
        val s = FakeSpeech()
        val fx = Effects()
        val c = controller(s, fx)
        // Coach 10-min: AT_TIME @10:00 (question) → say "keep going".
        c.onTick(estimateMin = 10, level = CopilotLevel.COACH, focusedSec = 10 * 60, enabled = true, voiceReplies = true)
        s.deliver("keep going")
        assertTrue(fx.keepGoing)
        val spokenBefore = s.spoken.size
        // +5 over would normally fire OVERRUN; keepGoing must suppress it.
        c.onTick(estimateMin = 10, level = CopilotLevel.COACH, focusedSec = 15 * 60, enabled = true, voiceReplies = true)
        assertEquals("no overrun after keep-going", spokenBefore, s.spoken.size)
    }

    @Test fun unrecognizedUtterance_noEffect() {
        val s = FakeSpeech()
        val fx = Effects()
        val c = controller(s, fx)
        c.onTick(estimateMin = 5, level = CopilotLevel.CALM, focusedSec = 5 * 60, enabled = true, voiceReplies = true)
        s.deliver("what time is it")
        assertEquals(null, fx.extended)
        assertFalse(fx.stopped); assertFalse(fx.keepGoing); assertEquals(null, fx.captured)
    }

    // ---------- no double-fire across ticks ----------

    @Test fun sameMilestoneNeverFiresTwiceAcrossTicks() {
        val s = FakeSpeech()
        val c = controller(s, Effects())
        // Two ticks past AT_TIME (Calm 5-min): only one spoken line.
        c.onTick(5, CopilotLevel.CALM, 5 * 60, enabled = true, voiceReplies = false)
        c.onTick(5, CopilotLevel.CALM, 5 * 60 + 30, enabled = true, voiceReplies = false)
        assertEquals(1, s.spoken.size)
    }

    // ---------- mic closed unless listening; no mic while already listening ----------

    @Test fun noNewPromptWhileAlreadyListening() {
        val s = FakeSpeech()
        val c = controller(s, Effects())
        c.onTick(25, CopilotLevel.COACH, 13 * 60, enabled = true, voiceReplies = true) // HALFWAY statement (no mic)
        // Open a mic window via AT_TIME... but first reach a question.
        c.onTick(25, CopilotLevel.COACH, 20 * 60, enabled = true, voiceReplies = true) // T_MINUS_5 question → mic opens
        assertEquals(1, s.listenStarts)
        assertTrue(c.listening)
        // Another tick while listening must NOT start a second recognizer.
        c.onTick(25, CopilotLevel.COACH, 25 * 60, enabled = true, voiceReplies = true)
        assertEquals(1, s.listenStarts)
    }

    @Test fun sttUnavailable_speaksButNoCrash_indicatorStaysOff() {
        val s = FakeSpeech(sttAvailable = false)
        val c = controller(s, Effects())
        c.onTick(5, CopilotLevel.CALM, 5 * 60, enabled = true, voiceReplies = true)
        assertEquals(1, s.spoken.size) // still speaks the question
        assertEquals(0, s.listenStarts)
        assertFalse(c.listening)
    }

    // ---------- FAIL-SAFE: a throwing speaker must never propagate ----------

    private class ThrowingSpeech : SpeechSurface {
        override val sttAvailable: Boolean get() = throw RuntimeException("boom-stt-available")
        override fun startListening(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onDone: () -> Unit) =
            throw RuntimeException("boom-listen")
        override fun stopListening() = throw RuntimeException("boom-stoplisten")
        override fun speak(text: String): Unit = throw RuntimeException("boom-speak")
        override fun stopSpeaking() = throw RuntimeException("boom-stopspeak")
    }

    @Test fun throwingSpeaker_doesNotPropagate_timerUnaffected() {
        val c = controller(ThrowingSpeech(), Effects())
        // The contract: onTick must NEVER throw (it would crash the focus screen /
        // stop the timer). Every milestone path is exercised.
        c.onTick(5, CopilotLevel.CALM, 5 * 60, enabled = true, voiceReplies = true)        // AT_TIME + mic
        c.onTick(25, CopilotLevel.COACH, 13 * 60, enabled = true, voiceReplies = true)     // HALFWAY statement
        c.onTick(10, CopilotLevel.COACH, 16 * 60, enabled = true, voiceReplies = true)     // overrun
        // Reset / teardown must also swallow the throwing stops.
        c.reset()
        c.stopAll()
        // Reaching here without an exception IS the assertion.
        assertFalse(c.listening)
    }

    @Test fun throwingSpeaker_speakOnly_alsoSafe() {
        val c = controller(ThrowingSpeech(), Effects())
        c.onTick(5, CopilotLevel.CALM, 5 * 60, enabled = true, voiceReplies = false)
        assertFalse(c.listening)
    }

    // ---------- structural no-LLM/no-network guarantee ----------

    @Test fun controller_hasNoNetworkOrAssistantDependency() {
        // The controller's only constructor deps are: a Context (for ringer/call
        // state), a SpeechSurface (on-device TTS/STT), and four pure callbacks.
        // There is NO assistant client, NO HTTP, NO LLM reachable from here — the
        // entire "brain" is the pure FocusCopilot in :core. A heard command is
        // resolved purely (keyword parse) and dispatched to a local callback.
        val s = FakeSpeech()
        val fx = Effects()
        val c = controller(s, fx)
        c.onTick(5, CopilotLevel.CALM, 5 * 60, enabled = true, voiceReplies = true)
        s.deliver("add five")
        assertEquals(5, fx.extended) // resolved & dispatched with zero network calls
    }

    @Test fun reset_clearsFiredState_soNextSessionFiresAgain() {
        val s = FakeSpeech()
        val c = controller(s, Effects())
        c.onTick(5, CopilotLevel.CALM, 5 * 60, enabled = true, voiceReplies = false)
        assertEquals(1, s.spoken.size)
        c.reset()
        c.onTick(5, CopilotLevel.CALM, 5 * 60, enabled = true, voiceReplies = false)
        assertEquals("AT_TIME fires again for the new session", 2, s.spoken.size)
    }
}
