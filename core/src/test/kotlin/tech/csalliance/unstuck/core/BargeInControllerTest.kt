package tech.csalliance.unstuck.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.BargeInCommand
import tech.csalliance.unstuck.core.logic.BargeInConfirm
import tech.csalliance.unstuck.core.logic.BargeInController
import tech.csalliance.unstuck.core.logic.BargeInEvent
import tech.csalliance.unstuck.core.logic.BargeInPhase
import tech.csalliance.unstuck.core.logic.BargeInProfile
import tech.csalliance.unstuck.core.logic.BargeInUi
import tech.csalliance.unstuck.core.logic.RmsGate
import tech.csalliance.unstuck.core.logic.TurnDetection
import tech.csalliance.unstuck.core.logic.VoiceRoute
import kotlin.math.pow
import kotlin.math.roundToLong

// Barge-in state machine + RMS gate — pure, driven with a fake clock and
// scripted events / synthetic PCM. The cases 1–24d are the iOS
// Tests/UnstuckAppTests/BargeInTests.swift (64 cases, each replaying a phone
// test from 2026-09-17…20) ported 1:1 on 2026-09-20, so the two platforms
// stay in lock-step; 26–28 are iOS 25a–c, 26a–b and 27a (builds 75–77,
// ported 2026-09-23), with 27c and the ask's grace tick ahead of iOS (the
// review of that port); the Android-only cases (RMS gate at the Android floor,
// the "noisy room?" chip, the hold-to-talk buffer error) follow at the end.
// Times are the iOS seconds, as ms on the fake clock.
class BargeInControllerTest {

    private var clock = 0L
    private fun at(sec: Double) { clock = (sec * 1000).roundToLong() }

    /** The ENERGY confirm with the loudspeaker's timings (300 ms, +9 dB while
     *  playing), so the 2026-09-06 state-machine cases read unchanged. The
     *  shipped loudspeaker profile confirms by WORDS since 2026-09-19 (test
     *  17); the energy path still ships on low-echo routes. */
    private val energy = BargeInProfile.SPEAKER.copy(confirm = BargeInConfirm.ENERGY)
    private val speaker = BargeInProfile.SPEAKER
    private val lowEcho = BargeInProfile.LOW_ECHO

    private fun controller(profile: BargeInProfile = energy, holdToTalk: Boolean = false) =
        BargeInController(profile, holdToTalk) { clock }

    /** A controller mid-reply: response r1 created and its first audio queued. */
    private fun speaking(profile: BargeInProfile = energy, holdToTalk: Boolean = false): BargeInController {
        at(0.0)
        val c = controller(profile, holdToTalk)
        c.handle(BargeInEvent.ResponseCreated("r1"))
        c.handle(BargeInEvent.AudioDelta("r1"))
        assertEquals(BargeInPhase.SPEAKING, c.phase)
        assertTrue(c.responseActive && c.playbackQueued)
        return c
    }

    private fun BargeInController.h(event: BargeInEvent, sec: Double): List<BargeInCommand> { at(sec); return handle(event) }
    private fun said(c: BargeInController, text: String) { c.h(BargeInEvent.AssistantTranscript(text), 0.5) }
    private fun count(cmds: List<BargeInCommand>, cmd: BargeInCommand) = cmds.count { it == cmd }
    private fun cmds(vararg c: BargeInCommand) = listOf(*c)
    private val duck = BargeInCommand.Duck
    private val restore = BargeInCommand.Restore
    private val cancel = BargeInCommand.SendCancel
    private val flush = BargeInCommand.FlushPlayback
    private val create = BargeInCommand.CreateResponse
    /** The ask: the create, its grace's own tick, THINKING. The grace timer
     *  is ahead of iOS (review of the 2026-09-23 parity port), where the
     *  same asks read `[.createResponse, .uiState(.thinking)]`. */
    private val ask = cmds(create, timer(BargeInController.CREATE_GRACE_MS + 1), BargeInCommand.Ui(BargeInUi.THINKING))
    private val clear = BargeInCommand.ClearCaption
    private val listening = BargeInCommand.Ui(BargeInUi.LISTENING)
    private val thinking = BargeInCommand.Ui(BargeInUi.THINKING)
    private val speakingUi = BargeInCommand.Ui(BargeInUi.SPEAKING)
    private fun timer(ms: Long) = BargeInCommand.StartTimer(ms)
    private fun turn(t: String) = BargeInCommand.UserTurn(t)
    private fun delete(id: String) = BargeInCommand.DeleteItem(id)
    private fun tr(text: String, item: String?, final: Boolean) = BargeInEvent.Transcription(text, item, final)
    private fun started(item: String?) = BargeInEvent.SpeechStarted(item)
    private val stopped = BargeInEvent.SpeechStopped
    private val tick = BargeInEvent.Tick
    private fun done(id: String, status: String = "completed") = BargeInEvent.ResponseDone(id, status)

    // ── 1: speech_started ducks synchronously, no cancel yet ──

    @Test fun `1 speech_started ducks without cancelling`() {
        val c = speaking()
        val out = c.h(started(null), 1.0)
        assertEquals(cmds(duck, timer(300)), out)
        assertEquals(BargeInPhase.DUCKED, c.phase)
        assertFalse(c.muted)
        assertTrue("audio keeps flowing (ducked) until confirmed", c.shouldEnqueueAudio("r1"))
    }

    // ── 2: confirm needs BOTH the server's segment and a mic still above the
    // gate; the timer alone is a blip → restore. Ahmad's iPhone, 2026-09-17.

    @Test fun `2 confirm with sustained mic energy cancels`() {
        val c = speaking()
        c.h(started(null), 1.0)
        c.h(BargeInEvent.GateOpen, 1.05)          // the mic agrees, and stays open
        assertEquals("gate opening while ducked is bookkeeping only", BargeInPhase.DUCKED, c.phase)
        assertEquals("before confirmMs nothing happens", emptyList<BargeInCommand>(), c.h(tick, 1.2))
        val out = c.h(tick, 1.3)
        assertEquals(1, count(out, cancel))
        assertEquals(cmds(cancel, flush, restore, clear, listening), out)
        assertTrue(c.muted)
        assertFalse(c.playbackQueued)
        assertEquals("r1", c.cancelledResponseId)
        assertEquals(BargeInPhase.IDLE, c.phase)
        assertFalse(c.shouldEnqueueAudio("r1"))
        assertFalse(c.acceptsTranscript("r1"))
        // A second tick (stale timer) is a no-op.
        assertEquals(emptyList<BargeInCommand>(), c.h(tick, 1.6))
        assertEquals(0, count(c.h(tick, 2.0), cancel))
        // Stale deltas of r1 are dropped even before the next response.
        assertTrue(c.handle(BargeInEvent.AudioDelta("r1")).isEmpty())
        assertTrue(c.handle(BargeInEvent.TranscriptDelta("r1")).isEmpty())
    }

    @Test fun `2b a server blip with no mic energy at confirm restores, and its segment answers nothing`() {
        // The loudspeaker case that cut every reply: the server VAD fired on
        // a tap, the gate had already closed again (or never opened), and
        // speech_stopped cannot arrive inside the window (600 ms of silence
        // first). The timer must NOT cancel.
        val c = speaking()
        c.h(started("blip"), 1.0)
        c.h(BargeInEvent.GateOpen, 1.02)
        c.h(BargeInEvent.GateClose, 1.25)         // the tap ended; server still in its segment
        assertEquals("a server duck waits for the tick", BargeInPhase.DUCKED, c.phase)
        val out = c.h(tick, 1.3)
        assertEquals(cmds(restore), out)
        assertEquals(BargeInPhase.SPEAKING, c.phase)
        assertTrue("the reply keeps playing", c.shouldEnqueueAudio("r1"))
        assertEquals(1, c.falseBargeIns)
        c.h(stopped, 1.9)
        // The server answers nothing by itself (create_response:false); the
        // segment's transcript has no words → its item goes, nothing is asked.
        assertEquals(emptyList<BargeInCommand>(), c.h(tr("", "blip", final = true), 2.2))
        assertEquals("held until the next segment starts", listOf("blip"), c.pendingDeletes)
        assertFalse(c.pendingCreate)
        assertTrue(c.shouldEnqueueAudio("r1"))
    }

    @Test fun `2c a gate-only duck with no server agreement restores`() {
        // Sustained mic energy the server never called speech (a fan, a
        // loud room): nothing was committed server-side, so restore.
        val c = speaking()
        c.h(BargeInEvent.GateOpen, 1.0)
        assertEquals(BargeInPhase.DUCKED, c.phase)
        assertEquals(cmds(restore), c.h(tick, 1.3))
        assertEquals(BargeInPhase.SPEAKING, c.phase)
        assertTrue("the gate is still open; a later speech_started re-ducks and can then confirm", c.gateOpen)
        c.h(started(null), 2.0)
        assertEquals(BargeInPhase.DUCKED, c.phase)
        assertEquals("gate + server agree at confirm → real talk-over", 1, count(c.h(tick, 2.3), cancel))
    }

    // ── 3: speech_stopped inside confirm → restore; the segment's WORDS then
    // decide whether anything is answered (the server never replies by
    // itself since iOS build 66).

    @Test fun `3 a blip restores, then no words answers nothing and real words are a turn`() {
        val c = speaking()
        c.h(started("s1"), 1.0)
        assertEquals(cmds(restore), c.h(stopped, 1.15))
        assertEquals(BargeInPhase.SPEAKING, c.phase)
        assertEquals(1, c.falseBargeIns)
        // The late tick from that duck must not cancel anything.
        assertEquals(0, count(c.h(tick, 1.3), cancel))
        // No words: a cough. Its item goes, nothing is asked, the reply plays on.
        assertEquals(emptyList<BargeInCommand>(), c.h(tr("…", "s1", final = true), 1.5))
        assertEquals("held until the next segment starts", listOf("s1"), c.pendingDeletes)
        assertTrue(c.shouldEnqueueAudio("r1"))
        assertFalse(c.pendingCreate)
        // A second segment WITH words on a low-echo route is the user even
        // though the mic never confirmed it: stop the reply, answer once the
        // cancel has settled.
        c.h(started("s2"), 2.0)
        c.h(stopped, 2.1)
        val words = c.h(tr("no wait, the other one", "s2", final = true), 2.4)
        assertEquals(1, count(words, cancel))
        assertTrue(words.contains(flush))
        assertTrue(words.contains(turn("no wait, the other one")))
        assertFalse("never in the same breath as the cancel", words.contains(create))
        assertTrue(c.pendingCreate)
        assertEquals("settled, but the hold is not up", cmds(timer(500)), c.h(done("r1", "cancelled"), 2.7))
        assertEquals(ask, c.h(tick, 3.0))
        assertTrue("asked for; pending until the server creates (iOS build 75)", c.pendingCreate)
        c.h(BargeInEvent.ResponseCreated("r2"), 3.2)
        assertFalse(c.pendingCreate)
        assertTrue(c.shouldEnqueueAudio("r2"))
    }

    // ── 4: gate_close before confirm with no server agreement → restore only ──

    @Test fun `4 a gate blip restores without suppression`() {
        val c = speaking()
        assertEquals(cmds(duck, timer(300)), c.h(BargeInEvent.GateOpen, 1.0))
        assertEquals(BargeInPhase.DUCKED, c.phase)
        assertEquals(cmds(restore), c.h(BargeInEvent.GateClose, 1.12))
        assertEquals(BargeInPhase.SPEAKING, c.phase)
        assertEquals(0, count(c.h(tick, 1.3), cancel))
        // A gate duck the server AGREES with is speech → cancel at once.
        c.h(BargeInEvent.GateOpen, 2.0)
        val agreed = c.h(started(null), 2.05)
        assertEquals(1, count(agreed, cancel))
        assertTrue(agreed.contains(flush))
        assertEquals(BargeInPhase.IDLE, c.phase)
    }

    // ── 5: transcription while ducked → immediate cancel ──

    @Test fun `5 a transcription while ducked with the mic open cancels immediately`() {
        val c = speaking()
        c.h(started(null), 1.0)
        c.h(BargeInEvent.GateOpen, 1.02)           // the mic agrees
        val out = c.h(tr("yes go ahead", null, final = false), 1.1)
        assertEquals(1, count(out, cancel))
        assertTrue(out.contains(flush))
        assertTrue(c.muted)
        assertEquals(BargeInPhase.IDLE, c.phase)
    }

    @Test fun `5b a transcription with the mic closed is not a confirm`() {
        // Deltas stream for the previous turn and for the model's own echo
        // (device log 2026-09-17); with the gate closed they confirm nothing.
        val c = speaking()
        c.h(started(null), 1.0)
        assertFalse(c.gateOpen)
        assertEquals(emptyList<BargeInCommand>(), c.h(tr("yes go ahead", null, final = false), 1.1))
        assertEquals("still waiting for the tick", BargeInPhase.DUCKED, c.phase)
        assertTrue(c.shouldEnqueueAudio("r1"))
        assertEquals("and the tick restores (no mic energy)", cmds(restore), c.h(tick, 1.3))
    }

    // ── 6: Interrupt = hard cancel, never ducks; flush-only on the tail; idle no-op ──

    @Test fun `6 interrupt pressed`() {
        val c = speaking()
        val out = c.h(BargeInEvent.InterruptPressed, 1.0)
        assertFalse(out.contains(duck))
        assertEquals(1, count(out, cancel))
        assertTrue(out.contains(flush))
        assertTrue(out.contains(listening))
        assertEquals(BargeInPhase.IDLE, c.phase)

        // Only the buffered tail left (response.done already arrived).
        val tail = speaking()
        tail.h(done("r1"), 1.0)
        assertTrue(tail.playbackQueued); assertFalse(tail.responseActive)
        val tailOut = tail.h(BargeInEvent.InterruptPressed, 2.0)
        assertEquals("nothing is generating — never cancel", 0, count(tailOut, cancel))
        assertTrue(tailOut.contains(flush))
        assertFalse(tail.playbackQueued)

        val idle = controller(speaker)
        assertEquals(emptyList<BargeInCommand>(), idle.h(BargeInEvent.InterruptPressed, 0.0))
    }

    // ── 7: "active response" errors are benign ──

    @Test fun `7 active-response errors are benign`() {
        val c = speaking()
        c.h(BargeInEvent.PlaybackDrained, 1.0)
        val out = c.h(BargeInEvent.Error("Conversation has no active response"), 2.0)
        assertFalse(c.responseActive)
        assertEquals(cmds(listening), out)
        assertFalse(out.any { it is BargeInCommand.ReportError })
        // While ducked: the gain comes back too.
        val d = speaking()
        d.h(started(null), 1.0)
        val ducked = d.h(BargeInEvent.Error("Conversation already has an active response"), 1.1)
        assertTrue(ducked.contains(restore))
        assertEquals("audio is still queued", BargeInPhase.SPEAKING, d.phase)
        // A real error is reported.
        val e = controller()
        assertTrue(e.handle(BargeInEvent.Error("Rate limit exceeded")).contains(BargeInCommand.ReportError("Rate limit exceeded")))
    }

    // ── 8: response.done keeps "speaking" while audio is queued ──

    @Test fun `8 response_done then drained`() {
        val c = speaking()
        assertEquals(cmds(speakingUi), c.h(done("r1"), 1.0))
        assertTrue("Interrupt stays enabled through the tail", c.modelBusy)
        assertEquals(cmds(listening), c.h(BargeInEvent.PlaybackDrained, 2.0))
        assertEquals(BargeInPhase.IDLE, c.phase)
        assertFalse(c.modelBusy)
        // With no audio queued, done goes straight to listening.
        val t = controller(speaker)
        t.h(BargeInEvent.ResponseCreated("r1"), 0.0)
        assertEquals(cmds(listening), t.h(done("r1"), 1.0))
    }

    // ── 9: cancelled-id deltas dropped even after a new response (interleaving) ──

    @Test fun `9 cancelled deltas are dropped after a new response`() {
        val c = speaking()
        c.h(BargeInEvent.InterruptPressed, 1.0)
        assertFalse(c.shouldEnqueueAudio("r1"))
        c.h(BargeInEvent.ResponseCreated("r2"), 2.0)
        assertFalse(c.muted)
        assertFalse("late r1 delta after r2 started", c.shouldEnqueueAudio("r1"))
        assertFalse(c.acceptsTranscript("r1"))
        assertTrue(c.shouldEnqueueAudio("r2"))
        assertTrue(c.acceptsTranscript("r2"))
        assertTrue(c.handle(BargeInEvent.AudioDelta("r2")).contains(BargeInCommand.EnqueueAudio))
        assertFalse(c.handle(BargeInEvent.AudioDelta("r1")).contains(BargeInCommand.EnqueueAudio))
        assertTrue(c.handle(BargeInEvent.TranscriptDelta("r2")).contains(BargeInCommand.ShowCaption))
        // r1's own late done doesn't clobber r2's active flag.
        c.h(done("r1", "cancelled"), 2.1)
        assertTrue(c.responseActive)
        // A response.created for the cancelled id is ignored outright.
        c.h(done("r2"), 3.0)
        c.h(BargeInEvent.ResponseCreated("r1"), 4.0)
        assertFalse(c.responseActive)
        assertFalse(c.shouldEnqueueAudio("r1"))
    }

    // ── 11: route change → new turn_detection + profile ──

    @Test fun `11 a route change re-profiles`() {
        val c = controller(speaker)
        assertEquals(300L, c.profile.confirmMs)
        assertEquals(TurnDetection(threshold = 0.6), c.turnDetection())
        val out = c.h(BargeInEvent.RouteChanged(BargeInProfile.forRoute(VoiceRoute.LOW_ECHO)), 0.0)
        assertEquals(cmds(BargeInCommand.SessionUpdate(TurnDetection(threshold = 0.5, prefixPaddingMs = 300, silenceDurationMs = 600))), out)
        assertEquals(lowEcho, c.profile)
        assertEquals(200L, c.profile.confirmMs)
        assertEquals("same profile → nothing re-sent", emptyList<BargeInCommand>(), c.h(BargeInEvent.RouteChanged(lowEcho), 1.0))
        val json = c.turnDetection()!!.toJson()
        assertEquals("server_vad", json["type"]!!.jsonPrimitive.content)
        assertEquals(0.5, json["threshold"]!!.jsonPrimitive.double, 0.0)
        assertEquals(300, json["prefix_padding_ms"]!!.jsonPrimitive.int)
        assertEquals(600, json["silence_duration_ms"]!!.jsonPrimitive.int)
        assertFalse("the server never cuts a reply on its own VAD", json["interrupt_response"]!!.jsonPrimitive.boolean)
        assertFalse("and never answers by itself", json["create_response"]!!.jsonPrimitive.boolean)
        // Speaker margin is +9 dB only while playing; low-echo stays +6.
        assertEquals(9.0, speaker.gateMargin(true), 0.0)
        assertEquals(6.0, speaker.gateMargin(false), 0.0)
        assertEquals(6.0, lowEcho.gateMargin(true), 0.0)
        // The gate margin follows playback: +9 while queued on speaker — and
        // the mic is never forced closed (full-duplex since 2026-09-19).
        val s = speaking(speaker)
        assertEquals(9.0, s.gateMarginDb(), 0.0)
        assertFalse(s.gateForcedClosed)
        s.h(done("r1"), 1.0)
        s.h(BargeInEvent.PlaybackDrained, 2.0)
        assertEquals(6.0, s.gateMarginDb(), 0.0)
        // Confirm window now 200 ms on the low-echo profile.
        val e = speaking(lowEcho)
        e.h(started(null), 1.0)
        e.h(BargeInEvent.GateOpen, 1.05)
        assertEquals(emptyList<BargeInCommand>(), e.h(tick, 1.199))
        assertEquals(1, count(e.h(tick, 1.2), cancel))
    }

    // ── 12: hold-to-talk ──

    @Test fun `12 hold-to-talk`() {
        val c = speaking(holdToTalk = true)
        assertNull(c.turnDetection())
        assertEquals(JsonNull, TurnDetection.json(c.turnDetection()))
        val down = c.h(BargeInEvent.PttDown, 1.0)
        assertEquals(1, count(down, cancel))
        assertTrue(down.contains(flush))
        assertTrue(down.contains(BargeInCommand.ForceGate(true)))
        assertEquals(BargeInPhase.HOLD, c.phase)
        assertTrue(c.pttPressed)
        // Nothing the server VAD would send matters while held.
        assertEquals(emptyList<BargeInCommand>(), c.h(started(null), 1.2))
        assertEquals(emptyList<BargeInCommand>(), c.h(BargeInEvent.InterruptPressed, 1.3))
        val up = c.h(BargeInEvent.PttUp, 2.0)
        assertTrue(up.contains(BargeInCommand.CommitAndRespond))
        assertTrue(up.contains(BargeInCommand.ForceGate(false)))
        assertFalse(c.pttPressed)
        assertEquals(BargeInPhase.IDLE, c.phase)
        assertEquals("release without press is a no-op", emptyList<BargeInCommand>(), c.h(BargeInEvent.PttUp, 3.0))
        // server-vad mode ignores ptt entirely.
        val s = speaking()
        assertEquals(emptyList<BargeInCommand>(), s.h(BargeInEvent.PttDown, 1.0))
        assertEquals(BargeInPhase.SPEAKING, s.phase)
        // Route changes in hold mode never re-send turn_detection (it stays null).
        assertEquals(emptyList<BargeInCommand>(), c.h(BargeInEvent.RouteChanged(lowEcho), 4.0))
        // Switching hold-to-talk off restores server VAD.
        val off = c.setHoldToTalk(false)
        assertEquals(0.5, off.filterIsInstance<BargeInCommand.SessionUpdate>().single().turnDetection!!.threshold, 0.0)
    }

    // ── 13: nothing to barge into: idle / listening no-ops — and a plain
    // turn while idle is answered from its completed transcript (the client
    // creates every reply since iOS build 66).

    @Test fun `13 idle events never duck or cancel, and an idle turn is answered`() {
        val c = controller(speaker)
        assertEquals("normal turn-taking: no duck", emptyList<BargeInCommand>(), c.h(started("q"), 0.0))
        assertEquals(emptyList<BargeInCommand>(), c.h(BargeInEvent.GateOpen, 0.1))
        assertEquals("streaming words of an idle turn: nothing yet", emptyList<BargeInCommand>(), c.h(tr("yes go", "q", final = false), 0.4))
        assertEquals(emptyList<BargeInCommand>(), c.h(stopped, 0.5))
        assertEquals(emptyList<BargeInCommand>(), c.h(BargeInEvent.GateClose, 0.6))
        assertEquals(cmds(turn("yes go ahead"), timer(500), thinking), c.h(tr("yes go ahead", "q", final = true), 0.7))
        assertEquals("a re-sent completed transcript never asks twice", emptyList<BargeInCommand>(), c.h(tr("yes go ahead", "q", final = true), 0.8))
        assertEquals("300 ms after the transcript: the hold is not up", emptyList<BargeInCommand>(), c.h(tick, 1.0))
        assertEquals(ask, c.h(tick, 1.3))
        assertEquals(BargeInPhase.IDLE, c.phase)
        assertEquals(0, c.falseBargeIns)
        // "Thinking" (response active, no audio yet) can be stopped before the
        // first delta too. On the loudspeaker that is by WORDS (speech alone
        // does nothing); on a low-echo route by energy, as before.
        c.h(BargeInEvent.ResponseCreated("r1"), 2.0)
        assertEquals(BargeInUi.THINKING, c.uiStateNow)
        assertEquals("loudspeaker: no duck, no timer", emptyList<BargeInCommand>(), c.h(started("i1"), 2.5))
        val out = c.h(tr("no wait", "i1", final = false), 2.8)
        assertEquals(1, count(out, cancel))
        assertEquals(BargeInPhase.IDLE, c.phase)

        val e = controller(lowEcho)
        e.h(BargeInEvent.ResponseCreated("r1"), 2.0)
        assertEquals(cmds(duck, timer(200)), e.h(started(null), 2.5))
        e.h(BargeInEvent.GateOpen, 2.55)           // the mic agrees (confirm needs both sides)
        assertEquals(1, count(e.h(tick, 2.7), cancel))
        assertEquals(BargeInPhase.IDLE, e.phase)
    }

    // ── 14: the reply ends on its own while ducked ──

    @Test fun `14 response done and drained while ducked`() {
        val c = speaking()
        c.h(started(null), 1.0)
        c.h(BargeInEvent.GateOpen, 1.02)           // the mic agrees (confirm needs both sides)
        // Generation finished mid-duck: still speaking (tail queued), still ducked.
        assertEquals(cmds(speakingUi), c.h(done("r1"), 1.05))
        assertEquals(BargeInPhase.DUCKED, c.phase)
        // Confirmed: only the tail is left → flush, no response.cancel.
        val out = c.h(tick, 1.3)
        assertEquals(0, count(out, cancel))
        assertTrue(out.contains(flush))
        assertEquals("r1", c.cancelledResponseId)
        assertEquals(BargeInPhase.IDLE, c.phase)

        // Tail drains during the duck: nothing left to cancel; restore on confirm.
        val d = speaking()
        d.h(done("r1"), 0.5)
        d.h(started(null), 1.0)
        assertEquals(cmds(listening), d.h(BargeInEvent.PlaybackDrained, 1.1))
        assertFalse(d.modelBusy)
        val confirm = d.h(tick, 1.3)
        assertEquals(0, count(confirm, cancel))
        assertTrue(confirm.contains(restore))
        assertEquals(BargeInPhase.IDLE, d.phase)
    }

    // ── 15 (iOS: gate context pushed only on change). Android's engine reads
    // the margin per frame instead of a pushed context; what must hold is
    // that the margin follows playback and adaptation is frozen while busy.

    @Test fun `15 the gate margin follows playback and never forces the mic closed`() {
        val c = controller(speaker)
        assertEquals(6.0, c.gateMarginDb(), 0.0)
        c.h(BargeInEvent.ResponseCreated("r1"), 0.0)
        assertTrue("response.created freezes adaptation (the engine reads responseActive)", c.responseActive)
        assertFalse(c.gateForcedClosed)
        c.h(BargeInEvent.AudioDelta("r1"), 0.1)
        assertEquals("first audio: +9 dB margin on the loudspeaker, still full-duplex", 9.0, c.gateMarginDb(), 0.0)
        assertFalse(c.gateForcedClosed)
        c.h(BargeInEvent.RouteChanged(lowEcho), 0.3)
        assertEquals("low-echo route: the margin stays +6 while playing", 6.0, c.gateMarginDb(), 0.0)
        assertFalse(c.gateForcedClosed)
    }

    // ── 16: the loudspeaker is FULL-duplex (2026-09-19): the mic is never
    // forced closed. It was muted for the whole reply on 2026-09-17 because
    // the phone heard its own echo; interruptions are now confirmed by WORDS
    // (test 17), echo cancellation keeps the reply out of the mic stream, and
    // the upload stays open.

    @Test fun `16 the speaker is full duplex now, and the gate still honours a forced close`() {
        val c = controller(speaker)
        c.h(BargeInEvent.ResponseCreated("r1"), 0.0)
        assertFalse("generating: mic open", c.gateForcedClosed)
        c.h(BargeInEvent.AudioDelta("r1"), 0.1)
        assertFalse("audible: mic open — talk-over on the loudspeaker", c.gateForcedClosed)
        c.h(done("r1"), 1.0)
        assertFalse(c.gateForcedClosed)
        c.h(BargeInEvent.PlaybackDrained, 1.5)
        assertFalse(c.gateForcedClosed)
        // Hold-to-talk still forces the gate OPEN mid-reply.
        val h = controller(speaker, holdToTalk = true)
        h.h(BargeInEvent.ResponseCreated("r1"), 0.0)
        h.h(BargeInEvent.AudioDelta("r1"), 0.1)
        assertTrue(h.h(BargeInEvent.PttDown, 0.2).contains(BargeInCommand.ForceGate(true)))
        assertFalse(h.gateForcedClosed)
        assertTrue(h.pttPressed)
        // Low-echo never did force it closed.
        val e = speaking(lowEcho)
        assertFalse(e.gateForcedClosed)
        // The pure helper the audio engine calls per frame: false for every shipped profile.
        assertFalse(BargeInProfile.gateForcedClosed(speaker, modelBusy = true, pttPressed = false))
        assertFalse(BargeInProfile.gateForcedClosed(lowEcho, modelBusy = true, pttPressed = false))
        assertTrue(BargeInProfile.gateForcedClosed(speaker.copy(halfDuplexWhilePlaying = true), modelBusy = true, pttPressed = false))

        // Gate: an open gate slams shut, uploads digital silence for as long as
        // it is forced, drops the pre-roll, and reopens on speech after.
        val g = RmsGate()
        val floor = -60.0
        repeat(RmsGate.CALIBRATION_SUB_FRAMES) { g.process(tone(floor), 6.0, false, false) }
        val loud = tone(floor + 12.0)
        g.process(loud, 6.0, false, false)
        val opened = g.process(loud, 6.0, false, false)
        assertTrue(opened.opened && g.open)
        val slammed = g.process(loud, 9.0, true, true, forcedClosed = true)
        assertTrue(slammed.closed)
        assertFalse(g.open)
        assertEquals(1, slammed.emit.size)
        assertTrue("silence, not the loud frame", slammed.emit[0].all { it == 0.toShort() })
        repeat(10) {
            val o = g.process(loud, 9.0, true, true, forcedClosed = true)
            assertFalse(o.opened); assertFalse(o.closed)
            assertTrue(o.emit.single().all { it == 0.toShort() })
        }
        repeat(3) { g.process(tone(floor - 2), 6.0, false, false) }
        g.process(loud, 6.0, false, false)
        val reopened = g.process(loud, 6.0, false, false)
        assertTrue(reopened.opened)
        assertEquals("pre-roll = 3 quiet + the two onset frames — nothing from before the mute", 5, reopened.emit.size)
        assertTrue(reopened.emit.first().contentEquals(tone(floor - 2)))
        // Hold-to-talk's press beats the mute.
        g.forceOpen(true)
        val pressed = g.process(loud, 9.0, true, true, forcedClosed = true)
        assertTrue(pressed.emit.single().contentEquals(loud))
        g.forceOpen(false)
    }

    // ── 17: transcript-confirmed barge-in on the loudspeaker (2026-09-19).
    // Words decide: nothing ducks, no timer runs, echo is discarded, a cough
    // has no words. Since iOS build 66 the server neither truncates nor
    // answers by itself: the client cancels on real words and creates the
    // reply from the segment's completed transcript.

    @Test fun `17a the speaker never ducks or arms a timer on speech or gate`() {
        val c = speaking(speaker)
        assertEquals("the server VAD hears the loudspeaker's echo on every reply", emptyList<BargeInCommand>(), c.h(started(null), 1.0))
        assertEquals(BargeInPhase.SPEAKING, c.phase)
        assertEquals("so does the gate — ducking on either would dim every reply", emptyList<BargeInCommand>(), c.h(BargeInEvent.GateOpen, 1.05))
        assertEquals(BargeInPhase.SPEAKING, c.phase)
        assertEquals(emptyList<BargeInCommand>(), c.h(tick, 2.0))
        assertTrue(c.shouldEnqueueAudio("r1"))
    }

    @Test fun `17b the echo of the reply is discarded and answers nothing`() {
        val c = speaking(speaker)
        said(c, "Taxi's at quarter to eight, after the gym.")
        c.h(started("item-echo"), 1.0)
        c.h(stopped, 1.3)
        val out = c.h(tr("taxi's at quarter to eight after the gym", "item-echo", final = true), 1.4)
        assertEquals("its own words: out of the conversation, nothing asked, nothing shown", emptyList<BargeInCommand>(), out)
        assertEquals("held until the next segment starts", listOf("item-echo"), c.pendingDeletes)
        assertEquals(BargeInPhase.SPEAKING, c.phase)
        assertTrue("the real reply keeps playing", c.shouldEnqueueAudio("r1"))
        assertFalse(c.pendingCreate)
        assertEquals("content words only: taxi's, quarter, eight, after, gym", 5, c.lastEchoScore.hits)
        assertEquals(5, c.lastEchoScore.heard)
    }

    @Test fun `17c real words over the reply stop it, and are answered once the cancel settles`() {
        val c = speaking(speaker)
        said(c, "Taxi's at quarter to eight, after the gym.")
        c.h(started("item-1"), 1.0)
        val out = c.h(tr("actually make it before the gym", "item-1", final = false), 1.4)
        assertEquals(cmds(cancel, flush, restore, clear, listening), out)
        assertTrue(c.muted)
        assertFalse(c.playbackQueued)
        assertEquals(BargeInPhase.IDLE, c.phase)
        assertEquals("r1", c.cancelledResponseId)
        c.h(stopped, 2.0)
        val final = c.h(tr("actually make it before the gym", "item-1", final = true), 2.3)
        assertEquals(cmds(turn("actually make it before the gym"), timer(500), timer(2500), thinking), final)
        assertFalse("a real turn stays in the conversation", final.contains(delete("item-1")))
        assertTrue(c.pendingCreate)
        assertEquals(cmds(timer(500)), c.h(done("r1", "cancelled"), 2.6))
        assertEquals(ask, c.h(tick, 2.9))
        c.h(BargeInEvent.ResponseCreated("r2"), 3.0)
        assertTrue(c.shouldEnqueueAudio("r2"))
        assertFalse(c.pendingCreate)
    }

    @Test fun `17d no words is not an interruption, and its item is deleted`() {
        val c = speaking(speaker)
        c.h(started("i"), 1.0)
        assertEquals(emptyList<BargeInCommand>(), c.h(tr("…", "i", final = false), 1.2))
        assertEquals("one-letter tokens match everything and are dropped", emptyList<BargeInCommand>(), c.h(tr("a", "i", final = false), 1.2))
        assertEquals(BargeInPhase.SPEAKING, c.phase)
        c.h(stopped, 1.5)
        assertEquals("a cough: out, nothing asked", emptyList<BargeInCommand>(), c.h(tr("a", "i", final = true), 1.8))
        assertEquals("held until the next segment starts", listOf("i"), c.pendingDeletes)
        assertEquals(BargeInPhase.SPEAKING, c.phase)
        assertTrue(c.shouldEnqueueAudio("r1"))
    }

    @Test fun `17e words that land after the segment ended still stop the reply and are answered`() {
        // The transcript only completes ~300 ms after speech_stopped; the
        // reply is still on air then.
        val c = speaking(speaker)
        said(c, "Taxi's at quarter to eight, after the gym.")
        c.h(started("i2"), 1.0)
        c.h(stopped, 1.3)
        assertTrue("no words yet: the reply plays on", c.shouldEnqueueAudio("r1"))
        val out = c.h(tr("no wait cancel that one", "i2", final = true), 1.6)
        assertEquals(1, count(out, cancel))
        assertTrue(out.contains(flush))
        assertTrue(out.contains(turn("no wait cancel that one")))
        assertFalse(out.contains(create))
        assertTrue(c.pendingCreate)
        c.h(done("r1", "cancelled"), 1.9)
        assertTrue("settled, but held", c.pendingCreate)
        assertEquals(ask, c.h(tick, 2.2))
        assertTrue("asked for; pending until the server creates", c.pendingCreate)
        c.h(BargeInEvent.ResponseCreated("r3"), 2.2)
        assertFalse(c.pendingCreate)
        assertTrue(c.shouldEnqueueAudio("r3"))
    }

    @Test fun `17f streaming words cancel once, the completed transcript asks once`() {
        val c = speaking(speaker)
        said(c, "Taxi's at quarter to eight, after the gym.")
        c.h(started("i3"), 1.0)
        assertEquals("two words are not evidence yet", 0, count(c.h(tr("make it", "i3", final = false), 1.2), cancel))
        assertEquals("the first three real words stop the reply", 1, count(c.h(tr("make it before", "i3", final = false), 1.3), cancel))
        assertEquals("once", 0, count(c.h(tr("make it before the", "i3", final = false), 1.4), cancel))
        c.h(stopped, 1.5)
        val done = c.h(tr("make it before the gym", "i3", final = true), 1.8)
        assertEquals(0, count(done, cancel))
        assertTrue(c.pendingCreate)
        assertEquals("held", 0, count(c.h(done("r1", "cancelled"), 1.9), create))
        assertEquals(1, count(c.h(tick, 2.4), create))
        c.h(BargeInEvent.ResponseCreated("r2"), 2.2)
        assertTrue(c.shouldEnqueueAudio("r2"))
        // The same completed transcript again never asks twice.
        assertEquals(emptyList<BargeInCommand>(), c.h(tr("make it before the gym", "i3", final = true), 2.3))
        assertTrue(c.shouldEnqueueAudio("r2"))
    }

    @Test fun `17g the low-echo route still confirms by energy`() {
        val c = speaking(lowEcho)
        assertEquals(cmds(duck, timer(200)), c.h(started(null), 1.0))
        assertEquals(BargeInPhase.DUCKED, c.phase)
    }

    @Test fun `17h tokens and the echo rule`() {
        assertEquals(listOf("taxis", "at", "45", "after", "the", "gym"), BargeInController.tokens("Taxi's at 7:45, after the GYM!"))
        val c = controller(speaker)
        assertFalse("nothing said yet: nothing can be echo", c.isEcho(BargeInController.tokens("after the gym")))
        said(c, "Taxi's at quarter to eight, after the gym.")
        assertTrue(c.isEcho(BargeInController.tokens("after the gym")))
        assertTrue("transcription drops words; 70 % is enough", c.isEcho(BargeInController.tokens("taxi at quarter to eight after gym")))
        assertFalse("one shared word out of three is not echo", c.isEcho(BargeInController.tokens("book the dentist")))
        assertFalse(c.isEcho(BargeInController.tokens("stop")))
    }

    // ── 18: the loop from Ahmad's phone, 2026-09-19 22:18, replayed from
    // the device log: the greeting's echo was committed as the user's turn
    // and answered; the answer echoed and was answered; the assistant talked
    // to itself. Since iOS build 66 the server answers nothing by itself —
    // the echo is recognised by its words and deleted, and nothing is asked.

    @Test fun `18a the greeting's echo is deleted and nothing is asked`() {
        val c = controller(speaker)
        c.h(BargeInEvent.ResponseCreated("r1"), 3.5)
        c.h(BargeInEvent.AudioDelta("r1"), 3.6)
        said(c, "Hey, just what's on your plate?")
        c.h(BargeInEvent.GateOpen, 3.96)
        c.h(started("item_A"), 4.52)                    // the echo, while r1 is on air
        repeat(8) { assertEquals(emptyList<BargeInCommand>(), c.h(tr("", "item_A", final = false), 4.7)) }   // DashScope's empty deltas
        c.h(done("r1"), 5.0)
        c.h(BargeInEvent.PlaybackDrained, 5.24)         // the greeting finished
        assertEquals(BargeInPhase.IDLE, c.phase)
        c.h(stopped, 6.22)
        val out = c.h(tr("Hey, just what's on your plate?", "item_A", final = true), 6.376)
        assertEquals("the echo leaves the conversation; no reply, no caption", emptyList<BargeInCommand>(), out)
        assertEquals("held until the next segment starts", listOf("item_A"), c.pendingDeletes)
        assertFalse(c.pendingCreate)
        // A real turn afterwards is answered.
        c.h(started("item_Q"), 8.0)
        c.h(stopped, 9.0)
        val q = c.h(tr("what have I got left today", "item_Q", final = true), 9.3)
        assertEquals(cmds(turn("what have I got left today"), timer(500), thinking), q)
        assertEquals(ask, c.h(tick, 9.8))
        c.h(BargeInEvent.ResponseCreated("r2"), 9.8)
        assertTrue(c.shouldEnqueueAudio("r2"))
    }

    @Test fun `18b echo while the reply streams is deleted, the reply keeps playing`() {
        val c = controller(speaker)
        c.h(BargeInEvent.ResponseCreated("r1"), 0.0)
        c.h(BargeInEvent.AudioDelta("r1"), 0.1)
        said(c, "Taxi's at quarter to eight, after the gym.")
        c.h(started("item_B"), 1.0)                     // echo of r1 while r1 streams
        assertEquals(emptyList<BargeInCommand>(), c.h(stopped, 1.8))
        c.h(BargeInEvent.AudioDelta("r1"), 2.0)
        assertTrue(c.playbackQueued)
        val out = c.h(tr("taxi's at quarter to eight after the gym", "item_B", final = true), 2.2)
        assertEquals(emptyList<BargeInCommand>(), out)
        assertEquals("held until the next segment starts", listOf("item_B"), c.pendingDeletes)
        assertTrue(c.shouldEnqueueAudio("r1"))
        assertEquals(BargeInPhase.SPEAKING, c.phase)
    }

    @Test fun `18c echo that began on air and completed after the drain is still echo`() {
        val c = controller(speaker)
        c.h(BargeInEvent.ResponseCreated("r1"), 0.0)
        c.h(BargeInEvent.AudioDelta("r1"), 0.1)
        said(c, "Do small things.")
        c.h(started("item_C"), 0.4)                     // echo begins while r1 is on air
        c.h(done("r1"), 0.5)
        c.h(BargeInEvent.PlaybackDrained, 0.9)
        assertEquals(BargeInPhase.IDLE, c.phase)
        c.h(stopped, 1.7)
        assertEquals(emptyList<BargeInCommand>(), c.h(tr("do small things", "item_C", final = true), 2.0))
        assertEquals("held until the next segment starts", listOf("item_C"), c.pendingDeletes)
        assertEquals(BargeInPhase.IDLE, c.phase)
        assertFalse(c.pendingCreate)
    }

    @Test fun `18d the user's question is answered from its transcript, never twice`() {
        val c = controller(speaker)
        c.h(started("item_Q"), 0.0)                     // the user asks, while idle
        c.h(stopped, 1.0)
        assertEquals(cmds(turn("what have I got left today"), timer(500), thinking), c.h(tr("what have I got left today", "item_Q", final = true), 1.3))
        c.h(BargeInEvent.ResponseCreated("r1"), 1.8)
        c.h(BargeInEvent.AudioDelta("r1"), 2.0)
        said(c, "You have three things left today.")
        // The same completed transcript again (a re-send) neither interrupts nor asks.
        assertEquals(emptyList<BargeInCommand>(), c.h(tr("what have I got left today", "item_Q", final = true), 2.2))
        assertEquals(BargeInPhase.SPEAKING, c.phase)
        assertTrue(c.shouldEnqueueAudio("r1"))
    }

    // ── 19: the second phone test, 2026-09-19 22:39 ──

    @Test fun `19a the reply's tail echo never eats the user's next turn`() {
        // 22:38:59 the reply's last words echoed after its audio drained;
        // 22:39:02 the user's real question. The echo answers nothing and
        // leaves nothing armed against the question.
        val c = controller(speaker)
        c.h(BargeInEvent.ResponseCreated("r1"), 0.0)
        c.h(BargeInEvent.AudioDelta("r1"), 0.1)
        said(c, "I'm doing well. How about you?")
        c.h(started("echo"), 1.0)
        c.h(done("r1"), 1.2)
        c.h(BargeInEvent.PlaybackDrained, 1.3)
        c.h(stopped, 1.8)
        assertEquals(emptyList<BargeInCommand>(), c.h(tr("How about you?", "echo", final = true), 1.9))
        assertEquals("held until the next segment starts", listOf("echo"), c.pendingDeletes)
        c.h(started("q"), 3.5)
        c.h(stopped, 5.8)
        val q = c.h(tr("how is my day going to be tomorrow", "q", final = true), 6.1)
        assertTrue("the question is answered once the hold is up", q.contains(timer(500)))
        assertTrue(c.h(tick, 6.7).contains(create))
        c.h(BargeInEvent.ResponseCreated("r2"), 6.5)
        assertTrue("the answer to the question plays", c.shouldEnqueueAudio("r2"))
    }

    @Test fun `19b a curly apostrophe does not make an echo look like real words`() {
        val c = controller(speaker)
        said(c, "Tuesday’s wide open.")                          // the model, curly
        assertTrue("the transcriber, straight", c.isEcho(BargeInController.tokens("Tuesday's wide open.")))
        assertEquals(listOf("tuesdays", "wide", "open"), BargeInController.tokens("Tuesday’s wide open."))
        assertEquals(listOf("im", "doing", "well"), BargeInController.tokens("I'm doing well."))
    }

    @Test fun `19c a segment begun after the queue drained is the user's turn, whatever its words`() {
        // 22:39:35.645 drained; 35.680 a segment began; it transcribed as the
        // reply's last words — the loudspeaker's tail, before echo
        // cancellation came back on. Since it did, no tail echo has reached
        // the transcriber, and the drain grace is exactly when the user
        // answers ("Have you set up the call?", "What is today?" were deleted
        // as echo, 2026-09-20 15:21 / 15:36): a segment begun in the grace is
        // never judged by its words.
        val c = controller(speaker)
        c.h(BargeInEvent.ResponseCreated("r1"), 0.0)
        c.h(BargeInEvent.AudioDelta("r1"), 0.1)
        said(c, "Tuesday's wide open. Want to block something?")
        c.h(done("r1"), 2.0)
        c.h(BargeInEvent.PlaybackDrained, 3.0)
        c.h(started("tail"), 3.03)                      // 30 ms after drain
        c.h(stopped, 3.8)
        assertEquals("begun in the grace: theirs", cmds(turn("Want to block something?"), timer(500), thinking), c.h(tr("Want to block something?", "tail", final = true), 3.9))
        assertEquals("nothing deleted", emptyList<String>(), c.pendingDeletes)
        assertTrue(c.pendingCreate)
        // Well after the grace window, the same words from the user are a turn.
        val d = controller(speaker)
        d.h(BargeInEvent.ResponseCreated("r1"), 0.0)
        d.h(BargeInEvent.AudioDelta("r1"), 0.1)
        said(d, "Want to block something?")
        d.h(done("r1"), 1.0)
        d.h(BargeInEvent.PlaybackDrained, 2.0)
        d.h(started("later"), 6.0)
        d.h(stopped, 7.0)
        assertEquals(cmds(turn("want to block something"), timer(500), thinking), d.h(tr("want to block something", "later", final = true), 7.3))
    }

    @Test fun `19d a transcript with no Latin words is noise, not an interruption`() {
        assertEquals(emptyList<String>(), BargeInController.tokens("嘿。"))
        assertEquals(emptyList<String>(), BargeInController.tokens("你好吗？"))
        val c = speaking(speaker)
        c.h(started("cn"), 1.0)
        assertEquals(emptyList<BargeInCommand>(), c.h(tr("你好吗？", "cn", final = false), 1.5))
        c.h(stopped, 1.7)
        assertEquals("never shown as the user's words (Ahmad: \"then it just shows a Chinese phrase\")", emptyList<BargeInCommand>(), c.h(tr("你好吗？", "cn", final = true), 2.0))
        assertEquals("deleted once the next segment starts", listOf("cn"), c.pendingDeletes)
        assertEquals(BargeInPhase.SPEAKING, c.phase)
        assertTrue(c.shouldEnqueueAudio("r1"))
    }

    // ── 20: the client owns turn-taking (iOS build 66, 2026-09-19). Measured
    // against the live proxy: with the defaults the server cancelled its own
    // reply the moment its VAD heard the loudspeaker's echo (status=cancelled,
    // reason=turn_detected); with interrupt_response and create_response off
    // it still segments + transcribes but never cuts or answers by itself; and
    // a response.create in the same breath as a response.cancel drops the
    // connection ("thread pool exhausted").

    @Test fun `20a session turn_detection turns the server's interrupt and auto-reply off`() {
        for (profile in listOf(speaker, lowEcho)) {
            val td = TurnDetection.forProfile(profile, holdToTalk = false)!!
            assertFalse(td.interruptResponse)
            assertFalse(td.createResponse)
            val json = td.toJson()
            assertEquals("server_vad", json["type"]!!.jsonPrimitive.content)
            assertFalse("the server never cuts a reply on its own VAD", json["interrupt_response"]!!.jsonPrimitive.boolean)
            assertFalse("and never answers by itself", json["create_response"]!!.jsonPrimitive.boolean)
            assertEquals(profile.threshold, json["threshold"]!!.jsonPrimitive.double, 0.0)
        }
        assertEquals("hold-to-talk stays null", JsonNull, TurnDetection.json(TurnDetection.forProfile(speaker, holdToTalk = true)))
    }

    @Test fun `20b the reply is asked only after the cancelled one's done`() {
        val c = speaking(speaker)
        said(c, "Taxi's at quarter to eight, after the gym.")
        c.h(started("u"), 1.0)
        c.h(stopped, 1.6)
        val out = c.h(tr("no, book the dentist instead", "u", final = true), 1.9)
        assertEquals(cmds(turn("no, book the dentist instead"), cancel, flush, restore, clear, listening, timer(500), timer(2500), thinking), out)
        assertTrue(c.pendingCreate)
        assertFalse("audio still in flight for the cancelled reply is dropped", c.shouldEnqueueAudio("r1"))
        // A late done for some OTHER id changes nothing.
        assertEquals(emptyList<BargeInCommand>(), c.h(done("r0"), 2.0))
        assertTrue(c.pendingCreate)
        assertEquals("settled 300 ms in: held", cmds(timer(500)), c.h(done("r1", "cancelled"), 2.2))
        assertEquals(ask, c.h(tick, 2.5))
        assertTrue("asked for; pending until the server creates", c.pendingCreate)
        assertFalse(c.responseActive)
        c.h(BargeInEvent.ResponseCreated("r2"), 2.7)
        assertTrue(c.shouldEnqueueAudio("r2"))
        assertFalse(c.muted)
    }

    @Test fun `20c if the done never comes the fallback tick asks, and the Interrupt button drops a pending ask`() {
        val c = speaking(speaker)
        c.h(started("u"), 1.0)
        c.h(stopped, 1.6)
        c.h(tr("no, book the dentist instead", "u", final = true), 1.9)
        assertTrue(c.pendingCreate)
        assertEquals("not yet", emptyList<BargeInCommand>(), c.h(tick, 2.5))
        assertEquals("1.5 s: a done took 1.9 s once, with a tool call in flight", emptyList<BargeInCommand>(), c.h(tick, 3.4))
        assertEquals(ask, c.h(tick, 4.4))
        assertTrue("asked for; pending until the server creates", c.pendingCreate)
        assertFalse(c.responseActive)
        // "no active response" to our cancel = it had already finished: ask now.
        val e = speaking(speaker)
        e.h(started("u"), 1.0)
        e.h(stopped, 1.6)
        e.h(tr("no, book the dentist instead", "u", final = true), 1.9)
        assertEquals("nothing to wait for but the hold", cmds(timer(500)), e.h(BargeInEvent.Error("Conversation has no active response"), 2.0))
        assertEquals(ask, e.h(tick, 2.5))
        assertTrue("asked for; pending until the server creates", e.pendingCreate)
        // Interrupt pressed while an ask is pending: the user wants silence.
        val i = speaking(speaker)
        i.h(started("u"), 1.0)
        i.h(stopped, 1.6)
        i.h(tr("no, book the dentist instead", "u", final = true), 1.9)
        i.h(BargeInEvent.InterruptPressed, 2.0)
        assertFalse(i.pendingCreate)
        assertEquals(0, count(i.h(done("r1", "cancelled"), 2.2), create))
    }

    @Test fun `20d only the tail left - real words flush and ask at once`() {
        val c = speaking(speaker)
        said(c, "Taxi's at quarter to eight, after the gym.")
        c.h(done("r1"), 0.5)                            // generated; audio still queued
        c.h(started("u"), 1.0)
        c.h(stopped, 1.6)
        val out = c.h(tr("no, book the dentist instead", "u", final = true), 1.9)
        assertEquals("nothing is generating — never cancel", 0, count(out, cancel))
        assertTrue(out.contains(flush))
        assertTrue("no done to wait for — only the hold", out.contains(timer(500)))
        assertFalse(out.contains(timer(2500)))
        assertEquals(ask, c.h(tick, 2.5))
        assertTrue("asked for; pending until the server creates", c.pendingCreate)
        assertEquals("late audio for the flushed tail stays dropped", "r1", c.cancelledResponseId)
    }

    @Test fun `20e the echo reference is the current and previous reply only`() {
        val c = controller(speaker)
        c.h(BargeInEvent.ResponseCreated("r1"), 0.0)
        said(c, "Book the dentist tomorrow morning.")
        c.h(done("r1"), 1.0)
        c.h(BargeInEvent.ResponseCreated("r2"), 2.0)
        said(c, "Taxi's at quarter to eight.")
        assertTrue("the previous reply can still echo", c.isEcho(BargeInController.tokens("book the dentist tomorrow morning")))
        c.h(done("r2"), 3.0)
        c.h(BargeInEvent.ResponseCreated("r3"), 4.0)
        said(c, "Anything else?")
        assertFalse("two replies back: no longer echo", c.isEcho(BargeInController.tokens("book the dentist tomorrow morning")))
        assertTrue(c.isEcho(BargeInController.tokens("taxi's at quarter to eight")))
        assertEquals(listOf("taxis", "at", "quarter", "to", "eight"), c.spokenPrevious)
        assertEquals(listOf("anything", "else"), c.spokenCurrent)
    }

    @Test fun `20f low-echo route - a confirmed talk-over is answered after the cancel settles`() {
        val c = speaking(lowEcho)
        c.h(started("u"), 1.0)
        c.h(BargeInEvent.GateOpen, 1.02)
        assertEquals("energy confirms in 200 ms", 1, count(c.h(tick, 1.2), cancel))
        assertEquals(BargeInPhase.IDLE, c.phase)
        c.h(stopped, 2.0)
        val out = c.h(tr("actually make it before the gym", "u", final = true), 2.3)
        assertEquals("already cancelled by the mic", 0, count(out, cancel))
        assertTrue(out.contains(turn("actually make it before the gym")))
        assertTrue(c.pendingCreate)
        assertEquals("held", 0, count(c.h(done("r1", "cancelled"), 2.5), create))
        assertEquals(1, count(c.h(tick, 2.9), create))
    }

    @Test fun `20g hold-to-talk - the transcript is caption only`() {
        val c = speaking(holdToTalk = true)
        c.h(BargeInEvent.PttDown, 1.0)
        c.h(BargeInEvent.PttUp, 2.0)                    // commit + response.create already sent
        c.h(BargeInEvent.ResponseCreated("r2"), 2.3)
        val out = c.h(tr("book the dentist", "h", final = true), 2.4)
        assertEquals("never cancels the reply it already asked for, never asks twice", cmds(turn("book the dentist")), out)
        assertTrue(c.responseActive)
        assertFalse(c.pendingCreate)
    }

    @Test fun `20h a transcript whose speech we never saw begin never cuts a reply`() {
        // The ASR of the question a reply is already answering can land after
        // the reply started; a server may send no speech_started at all.
        // Caption only while on air; a turn when idle.
        val c = speaking(speaker)
        said(c, "You have three things left today.")
        val late = c.h(tr("what's on today", null, final = true), 1.0)
        assertEquals("captioned, never cancelled, never asked again", cmds(turn("what's on today")), late)
        assertEquals(BargeInPhase.SPEAKING, c.phase)
        assertTrue(c.shouldEnqueueAudio("r1"))
        assertFalse(c.pendingCreate)
        assertEquals("streaming words of an unknown segment: nothing", emptyList<BargeInCommand>(), c.h(tr("what's on", "x", final = false), 1.1))
        c.h(done("r1"), 2.0)
        c.h(BargeInEvent.PlaybackDrained, 3.0)
        assertEquals("idle: a turn", cmds(turn("and tomorrow"), timer(500), thinking), c.h(tr("and tomorrow", null, final = true), 4.0))
    }

    // ── 21: the fourth phone test, 2026-09-19 23:50 (iOS build 66): echo
    // handled, real turns answered, one genuine talk-over — and two SHORT
    // replies whose echo the transcriber garbled ("Saturday's clear" →
    // "Saturday's players", "Monday's open" → "Monday is open") scored 1/2
    // and 2/3 against a 70 % all-words rule, were taken for the user, cut the
    // reply and were answered again. Filler words are ignored, plurals and
    // possessives fold, a segment that began while the reply's AUDIO was on
    // air needs only half its content words to match, one in the drain grace
    // is never judged by its words (19c, 25), and one that began while the
    // model was merely thinking is never echo.

    @Test fun `21a a garbled echo of a short reply is still echo`() {
        val c = speaking(speaker)
        said(c, "Monday's open.")
        c.h(started("a"), 1.0)
        c.h(stopped, 1.8)
        assertEquals(emptyList<BargeInCommand>(), c.h(tr("Monday is open.", "a", final = true), 1.9))
        assertEquals("held until the next segment starts", listOf("a"), c.pendingDeletes)
        assertEquals(2, c.lastEchoScore.hits)
        assertEquals("\"is\" carries nothing; Monday's and Monday fold", 2, c.lastEchoScore.heard)
        val d = speaking(speaker)
        said(d, "Saturday's clear.")
        d.h(started("b"), 1.0)
        assertEquals("half the content words, on air: echo", emptyList<BargeInCommand>(), d.h(tr("Saturday's players.", "b", final = true), 1.9))
        assertEquals("held until the next segment starts", listOf("b"), d.pendingDeletes)
        assertTrue("the reply plays on", d.shouldEnqueueAudio("r1"))
        assertEquals(BargeInPhase.SPEAKING, d.phase)
    }

    @Test fun `21b a real talk-over sharing only filler words is still real`() {
        val c = speaking(speaker)
        said(c, "I'm doing well. How about you?")
        c.h(started("q"), 1.0)
        val out = c.h(tr("How about Tuesday?", "q", final = true), 1.9)
        assertTrue(out.contains(turn("How about Tuesday?")))
        assertEquals(1, count(out, cancel))
        assertEquals(0, c.lastEchoScore.hits)
        assertEquals("only \"tuesday\" carries content", 1, c.lastEchoScore.heard)
    }

    @Test fun `21c in the drain grace nothing is judged echo`() {
        // A follow-up sharing one topic word, right after the reply ended: a turn.
        val c = controller(speaker)
        c.h(BargeInEvent.ResponseCreated("r1"), 0.0)
        c.h(BargeInEvent.AudioDelta("r1"), 0.1)
        said(c, "Tuesday's wide open.")
        c.h(done("r1"), 1.0)
        c.h(BargeInEvent.PlaybackDrained, 2.0)
        c.h(started("f"), 2.3)                          // inside the grace window
        c.h(stopped, 3.2)
        assertEquals("1 of 2 content words in the grace window is not echo", cmds(turn("Tuesday morning"), timer(500), thinking), c.h(tr("Tuesday morning", "f", final = true), 3.4))
        // Even the reply's own last words, in the grace window, are theirs:
        // with echo cancellation on no tail reaches the transcriber (19c).
        val t = controller(speaker)
        t.h(BargeInEvent.ResponseCreated("r1"), 0.0)
        t.h(BargeInEvent.AudioDelta("r1"), 0.1)
        said(t, "Tuesday's wide open.")
        t.h(done("r1"), 1.0)
        t.h(BargeInEvent.PlaybackDrained, 2.0)
        t.h(started("tail"), 2.05)
        assertEquals(cmds(turn("wide open."), timer(500), thinking), t.h(tr("wide open.", "tail", final = true), 2.9))
        assertEquals("nothing deleted", emptyList<String>(), t.pendingDeletes)
        // The same follow-up while that reply's audio was still on air would be echo.
        val d = speaking(speaker)
        said(d, "Tuesday's wide open.")
        d.h(started("g"), 1.0)
        assertEquals(emptyList<BargeInCommand>(), d.h(tr("Tuesday morning", "g", final = true), 1.9))
        assertEquals("held until the next segment starts", listOf("g"), d.pendingDeletes)
    }

    @Test fun `21d words while the model is only thinking are never echo`() {
        // The reply's transcript arrives ~1 s BEFORE its audio: the reference
        // already holds "Monday's open" while nothing has been said aloud.
        val c = controller(speaker)
        c.h(BargeInEvent.ResponseCreated("r1"), 0.0)
        said(c, "Monday's open.")
        assertFalse(c.playbackQueued)
        c.h(started("k"), 0.5)
        c.h(stopped, 1.3)
        val out = c.h(tr("Monday is open?", "k", final = true), 1.4)
        assertTrue(out.contains(turn("Monday is open?")))
        assertEquals("the user spoke over a thinking model: stop it, answer them", 1, count(out, cancel))
        assertTrue(c.pendingCreate)
        // Streaming words in that state stop it early too.
        val d = controller(speaker)
        d.h(BargeInEvent.ResponseCreated("r1"), 0.0)
        said(d, "Monday's open.")
        d.h(started("m"), 0.5)
        assertEquals(1, count(d.h(tr("Monday is", "m", final = false), 0.9), cancel))
    }

    @Test fun `21e filler-only utterances are judged whole`() {
        val c = speaking(speaker)
        said(c, "Okay. How about you?")
        c.h(started("h"), 1.0)
        assertEquals(emptyList<BargeInCommand>(), c.h(tr("How about you?", "h", final = true), 1.9))
        assertEquals("held until the next segment starts", listOf("h"), c.pendingDeletes)
        val d = speaking(speaker)
        said(d, "Taxi's at quarter to eight.")
        d.h(started("i"), 1.0)
        assertEquals("a \"No!\" the model never said is an interruption", 1, count(d.h(tr("No!", "i", final = true), 1.9), cancel))
    }

    @Test fun `21f stem folds plurals and possessives`() {
        assertEquals("monday", BargeInController.stem("mondays"))
        assertEquals("player", BargeInController.stem("players"))
        assertEquals("gym", BargeInController.stem("gym"))
        assertEquals("was", BargeInController.stem("was"))
        assertEquals("tokens stay raw; folding happens at the comparison", listOf("mondays", "open"), BargeInController.tokens("Monday's open."))
    }

    // ── 22: the fifth phone test, 2026-09-20 00:04 (iOS build 67): the
    // garbled-echo rule held ("How will this be like? You've got a few tasks
    // wrapped up" 4/5 → echo). Then the transcriber completed ONE segment in
    // two pieces — "Coming up on." (echo) and "Day." — and the fragment,
    // judged on its own, cut the reply; its own echo "And Friday." landed
    // 90 ms after the flush, outside any grace window, and was answered too.

    @Test fun `22a a later piece of an echo segment is still echo`() {
        val c = speaking(speaker)
        said(c, "Looks pretty solid. You've got a few tasks wrapped up, and Friday coming up.")
        c.h(started("p"), 1.0)
        assertEquals(emptyList<BargeInCommand>(), c.h(tr("Coming up on.", "p", final = true), 1.9))
        assertEquals(listOf("p"), c.pendingDeletes)
        assertEquals("a fragment of the echo, not a turn", emptyList<BargeInCommand>(), c.h(tr("Day.", "p", final = true), 2.5))
        assertTrue("the reply plays on", c.shouldEnqueueAudio("r1"))
        assertEquals(BargeInPhase.SPEAKING, c.phase)
        assertEquals(listOf("p"), c.pendingDeletes)
        assertEquals("nor do its streaming words cancel", emptyList<BargeInCommand>(), c.h(tr("Day", "p", final = false), 2.6))
    }

    @Test fun `22b the user's question inside the echo segment is kept`() {
        // The reply ends; the user speaks before the VAD's 600 ms is up, so
        // the echo tail and the question share one segment and one item.
        val c = speaking(speaker)
        said(c, "You've got a few tasks wrapped up, and Friday coming up.")
        c.h(started("m"), 1.0)
        val whole = c.h(tr("Coming up on Friday. What about Monday?", "m", final = true), 2.4)
        assertTrue("three words after the last echoed word are the user's", whole.contains(turn("Coming up on Friday. What about Monday?")))
        assertEquals(1, count(whole, cancel))
        assertFalse("the item is theirs, echo prefix and all", whole.contains(delete("m")))
        assertEquals(emptyList<String>(), c.pendingDeletes)
        // Or in pieces: the echo first, then the question for the SAME item.
        val d = speaking(speaker)
        said(d, "You've got a few tasks wrapped up, and Friday coming up.")
        d.h(started("n"), 1.0)
        assertEquals(emptyList<BargeInCommand>(), d.h(tr("Coming up on Friday.", "n", final = true), 1.9))
        assertEquals("held, not sent", listOf("n"), d.pendingDeletes)
        val q = d.h(tr("What about Monday?", "n", final = true), 2.6)
        assertTrue(q.contains(turn("What about Monday?")))
        assertEquals(1, count(q, cancel))
        assertEquals("the held delete is dropped: the item is the user's turn", emptyList<String>(), d.pendingDeletes)
        assertFalse(q.contains(delete("n")))
    }

    @Test fun `22c held deletes go out when the next segment starts`() {
        val c = speaking(speaker)
        said(c, "Taxi's at quarter to eight, after the gym.")
        c.h(started("e1"), 1.0)
        c.h(tr("after the gym", "e1", final = true), 1.9)
        assertEquals(listOf("e1"), c.pendingDeletes)
        assertEquals("sent when the next segment begins", cmds(delete("e1")), c.h(started("e2"), 2.5))
        assertEquals(emptyList<String>(), c.pendingDeletes)
        c.h(tr("quarter to eight", "e2", final = true), 3.2)
        assertEquals(listOf("e2"), c.pendingDeletes)
        c.h(done("r1"), 3.5)
        c.h(BargeInEvent.PlaybackDrained, 4.0)
        assertEquals(cmds(delete("e2")), c.h(started("u"), 6.0))
        assertEquals(cmds(turn("book the dentist"), timer(500), thinking), c.h(tr("book the dentist", "u", final = true), 7.0))
    }

    @Test fun `22d a flush starts the grace window, and words in it are the user's`() {
        val c = speaking(speaker)
        said(c, "Looks pretty solid. Friday's coming up.")
        c.h(started("q"), 1.0)
        val cut = c.h(tr("What about Monday?", "q", final = true), 1.9)
        assertTrue(cut.contains(flush))
        assertTrue(c.pendingCreate)
        // 90 ms later a segment begins in the flush's grace window. Never
        // judged by its words (19c): the user going on — "And Friday."
        c.h(started("e"), 1.99)
        c.h(stopped, 2.7)
        val more = c.h(tr("And Friday.", "e", final = true), 2.8)
        assertTrue(more.contains(turn("And Friday.")))
        assertEquals("the reply is already cut: no second cancel", 0, count(more, cancel))
        assertFalse(more.contains(delete("e")))
        assertEquals("nothing deleted", emptyList<String>(), c.pendingDeletes)
        assertTrue("one ask still waiting, re-held from their last words", c.pendingCreate)
        assertEquals("the hold restarted at 2.8: not yet", 0, count(c.h(done("r1", "cancelled"), 3.0), create))
        assertEquals("hold up, server quiet, cancel settled", ask, c.h(tick, 3.4))
    }

    @Test fun `22e no words first then words for the same item is a turn`() {
        val c = speaking(speaker)
        said(c, "Taxi's at quarter to eight.")
        c.h(started("w"), 1.0)
        assertEquals(emptyList<BargeInCommand>(), c.h(tr(".", "w", final = true), 1.5))
        assertEquals(listOf("w"), c.pendingDeletes)
        val words = c.h(tr("book the dentist instead", "w", final = true), 2.2)
        assertTrue(words.contains(turn("book the dentist instead")))
        assertEquals(1, count(words, cancel))
        assertEquals("never deleted: the words came", emptyList<String>(), c.pendingDeletes)
    }

    // ── 23: the same phone test: three interruptions "totally ignored till
    // it finished". On the loudspeaker a segment can only end when the reply
    // pauses, so a verdict at the end of the segment is a verdict after the
    // reply — and the user's words, first in the segment, were outscored by
    // the echo of what played after them ("How will this be like? You've got
    // a few tasks wrapped up" → 4/5). The transcriber's live guess grows word
    // by word from ~200 ms in; the reply is cut on it.

    @Test fun `23a the user's words first and the echo after are the user's`() {
        val c = speaking(speaker)
        said(c, "Looks pretty solid. You've got a few tasks wrapped up, and Friday coming up.")
        c.h(started("o"), 1.0)
        val out = c.h(tr("How will this be like? You've got a few tasks wrapped up.", "o", final = true), 4.6)
        assertTrue(out.contains(turn("How will this be like? You've got a few tasks wrapped up.")))
        assertEquals(1, count(out, cancel))
        assertEquals(emptyList<String>(), c.pendingDeletes)
    }

    @Test fun `23b the live guess cuts the reply while they are still talking`() {
        val c = speaking(speaker)
        said(c, "Looks pretty solid. You've got a few tasks wrapped up, and Friday coming up.")
        c.h(started("o"), 1.0)
        assertEquals("one word proves nothing", emptyList<BargeInCommand>(), c.h(tr("How", "o", final = false), 1.2))
        assertEquals("filler only: could be anything", emptyList<BargeInCommand>(), c.h(tr("How will this", "o", final = false), 1.5))
        val cut = c.h(tr("How will this be like", "o", final = false), 1.9)
        assertEquals("\"like\" the model never said: theirs — cut now", 1, count(cut, cancel))
        assertTrue(cut.contains(flush))
        assertEquals(BargeInPhase.IDLE, c.phase)
        assertEquals("once", 0, count(c.h(tr("How will this be like you've got", "o", final = false), 2.3), cancel))
        c.h(stopped, 4.4)
        val final = c.h(tr("How will this be like? You've got a few tasks wrapped up.", "o", final = true), 4.6)
        assertTrue(final.contains(turn("How will this be like? You've got a few tasks wrapped up.")))
        assertEquals(0, count(final, cancel))
        assertTrue(c.pendingCreate)
        assertEquals("held", 0, count(c.h(done("r1", "cancelled"), 4.9), create))
        assertEquals(1, count(c.h(tick, 5.2), create))
    }

    @Test fun `23c the live guess of an echo never cuts`() {
        val c = speaking(speaker)
        said(c, "Looks pretty solid. You've got a few tasks wrapped up.")
        c.h(started("e"), 1.0)
        for (guess in listOf("Looks", "Looks pretty", "Lucks pretty solid", "Looks pretty solid you've got", "Looks pretty solid. You've got a few")) {
            assertEquals(guess, emptyList<BargeInCommand>(), c.h(tr(guess, "e", final = false), 1.5))
        }
        assertEquals(BargeInPhase.SPEAKING, c.phase)
        assertTrue(c.shouldEnqueueAudio("r1"))
        c.h(stopped, 2.6)
        assertEquals(emptyList<BargeInCommand>(), c.h(tr("Looks pretty solid. You've got a few tasks wrapped up.", "e", final = true), 2.8))
        assertEquals(listOf("e"), c.pendingDeletes)
        // A short real interruption is cut at three words: "How about Tuesday".
        val d = speaking(speaker)
        said(d, "I'm doing well. How about you?")
        d.h(started("q"), 1.0)
        assertEquals(emptyList<BargeInCommand>(), d.h(tr("How about", "q", final = false), 1.3))
        assertEquals(1, count(d.h(tr("How about Tuesday", "q", final = false), 1.6), cancel))
    }

    @Test fun `23d nothing said yet means any three words are the user's`() {
        // First reply, no reference yet, audio on air: the first three words
        // of a guess cut it (there is nothing they could be an echo of… except
        // the reply itself, whose transcript always precedes its audio).
        val c = speaking(speaker)
        c.h(started("z"), 1.0)
        assertEquals(0, count(c.h(tr("Wait one", "z", final = false), 1.3), cancel))
        assertEquals(1, count(c.h(tr("Wait one second", "z", final = false), 1.6), cancel))
    }

    // ── 24: the sixth phone test, 2026-09-20 00:25 (iOS build 68): the live
    // guess cut two real talk-overs within a second and the held deletes
    // kept a question that shared its segment with the echo. Then: long
    // questions "kept tripping itself" — a pause mid-sentence ended the
    // segment, the fragment was answered, the continuation cancelled that and
    // got its own; "Alright" heard as "All right" (two unknown words) cut a
    // reply; the echo of "Is there anything…" caught mid-word as "Is there
    // any" cut another; "Have to go" cut on "go" alone.

    @Test fun `24a a turn is held half a second, and while they go on talking`() {
        val c = controller(speaker)
        c.h(started("f1"), 0.0)
        c.h(stopped, 1.0)
        assertEquals(cmds(turn("Um. I can't remember."), timer(500), thinking), c.h(tr("Um. I can't remember.", "f1", final = true), 1.2))
        assertTrue(c.pendingCreate)
        // They go on before the hold is up: nothing is asked.
        c.h(started("f2"), 1.5)
        assertEquals("still talking", emptyList<BargeInCommand>(), c.h(tick, 1.7))
        assertEquals("still talking", emptyList<BargeInCommand>(), c.h(tick, 2.5))
        assertEquals(cmds(timer(500)), c.h(stopped, 3.0))
        val rest = c.h(tr("you know, that I normally do in a week.", "f2", final = true), 3.2)
        assertEquals(cmds(turn("you know, that I normally do in a week."), timer(500), thinking), rest)
        assertEquals("300 ms after the second piece", emptyList<BargeInCommand>(), c.h(tick, 3.5))
        assertEquals("one ask, for both pieces", ask, c.h(tick, 3.7))
        assertTrue("asked for; pending until the server creates", c.pendingCreate)
        assertFalse("never twice — in flight, only the grace timer", c.h(tick, 4.0).contains(create))
        // A segment that ends WITHOUT a transcript still lets the ask through.
        val d = controller(speaker)
        d.h(started("g1"), 0.0)
        d.h(stopped, 1.0)
        d.h(tr("what's on tomorrow", "g1", final = true), 1.2)
        d.h(started("g2"), 1.4)
        assertEquals(cmds(timer(500)), d.h(stopped, 2.0))
        assertEquals(ask, d.h(tick, 2.5))
    }

    @Test fun `24b the hold applies after an interruption too`() {
        val c = speaking(speaker)
        said(c, "Taxi's at quarter to eight.")
        c.h(started("u"), 1.0)
        c.h(stopped, 1.6)
        c.h(tr("no, I meant", "u", final = true), 1.9)
        assertTrue(c.pendingCreate)
        c.h(done("r1", "cancelled"), 2.2)
        // They go on: "…the dentist, not the taxi" — the ask waits for it.
        c.h(started("v"), 2.3)
        assertEquals(emptyList<BargeInCommand>(), c.h(tick, 2.4))
        c.h(stopped, 3.4)
        val rest = c.h(tr("the dentist, not the taxi", "v", final = true), 3.6)
        assertTrue(rest.contains(turn("the dentist, not the taxi")))
        assertEquals("nothing is playing", 0, count(rest, cancel))
        assertEquals(ask, c.h(tick, 4.1))
    }

    @Test fun `24c a heard word that starts a said word matches`() {
        // ("Alright" heard as "All right" is NOT this case — a-l-l is not the
        // start of a-l-r-i-g-h-t. That one is left to echo cancellation: no
        // amount of text matching will cover every way a transcriber can
        // render an echo, which is why the loudspeaker runs the platform
        // echo canceller with the mic open.)
        val c = speaking(speaker)
        said(c, "Alright. Not much else on my end. Is there anything you'd like to do?")
        c.h(started("b"), 2.5)
        assertEquals("\"any\" is \"anything\" cut short: no cut", emptyList<BargeInCommand>(), c.h(tr("Is there any", "b", final = false), 2.9))
        assertEquals(BargeInPhase.SPEAKING, c.phase)
        assertTrue(c.shouldEnqueueAudio("r1"))
        assertFalse("two letters never match by prefix", c.isEcho(BargeInController.tokens("on Monday")))
        val d = speaking(speaker)
        said(d, "Monday's open.")
        assertTrue("three do", d.isEcho(BargeInController.tokens("Mon"), onAir = true))
    }

    @Test fun `24d a two-letter word is no evidence for an early cut`() {
        val c = speaking(speaker)
        said(c, "Looks solid. A few things to get through this week.")
        c.h(started("h"), 1.0)
        assertEquals("\"go\" alone proves nothing", emptyList<BargeInCommand>(), c.h(tr("Have to go", "h", final = false), 1.4))
        assertEquals("\"through\" the model said", emptyList<BargeInCommand>(), c.h(tr("Have to go through", "h", final = false), 1.6))
        assertEquals("\"friday\" it never said", 1, count(c.h(tr("Have to go through Friday", "h", final = false), 1.9), cancel))
    }

    // ── 25: the calls test, 2026-09-20 15:21–15:37 (assistant_turns), with
    // echo cancellation back on: no true echo reached the transcriber, and
    // the word rule deleted the user's real speech three times — "Have you
    // set up the call?" and "What is today?" right after a reply ended
    // (segments begun in the drain grace) and "Book the cool call now." over
    // "…would you like me to book a quick call now?" (3 of 4 content words,
    // on air). A segment begun in the grace is never judged by its words;
    // one on air of four words or more is echo only when near verbatim
    // (every content word said, at most one filler not).

    @Test fun `25a a long answer in the reply's own terms is the user's`() {
        val c = speaking(speaker)
        said(c, "I can't set a reminder without a call being booked first. Shall I schedule a task to remind you, or would you like me to book a quick call now?")
        c.h(started("b"), 1.0)
        val out = c.h(tr("Book the cool call now.", "b", final = true), 2.4)
        assertTrue("3 of 4 content words said, but \"cool\" never: theirs", out.contains(turn("Book the cool call now.")))
        assertEquals("exactly one cancel", 1, count(out, cancel))
        assertFalse(out.contains(delete("b")))
        assertEquals("nothing deleted", emptyList<String>(), c.pendingDeletes)
        assertTrue(c.pendingCreate)
    }

    @Test fun `25b an answer right after the reply ended is never judged by its words`() {
        val c = controller(speaker)
        c.h(BargeInEvent.ResponseCreated("r1"), 0.0)
        c.h(BargeInEvent.AudioDelta("r1"), 0.1)
        said(c, "Got it, I'll set that up now. What's the call about?")
        c.h(done("r1"), 2.0)
        c.h(BargeInEvent.PlaybackDrained, 3.0)
        c.h(started("a"), 3.3)                          // 0.3 s into the grace
        c.h(stopped, 4.4)
        assertEquals(cmds(turn("Have you set up the call?"), timer(500), thinking), c.h(tr("Have you set up the call?", "a", final = true), 4.6))
        assertEquals("nothing deleted", emptyList<String>(), c.pendingDeletes)
        // On air the same words would be theirs too: two fillers the model
        // never said ("have", "you") is more than the one the transcriber slips in.
        assertFalse(c.isEcho(BargeInController.tokens("Have you set up the call?"), onAir = true))
    }

    @Test fun `25c the answer to "ask me what's on today" is a turn`() {
        val c = controller(speaker)
        c.h(BargeInEvent.ResponseCreated("r1"), 0.0)
        c.h(BargeInEvent.AudioDelta("r1"), 0.1)
        said(c, "You're all set. Want to try something — ask me what's on today?")
        c.h(done("r1"), 2.0)
        c.h(BargeInEvent.PlaybackDrained, 3.0)
        c.h(started("w"), 3.2)
        c.h(stopped, 4.0)
        assertEquals(cmds(turn("What is today?"), timer(500), thinking), c.h(tr("What is today?", "w", final = true), 4.2))
        assertEquals("nothing deleted", emptyList<String>(), c.pendingDeletes)
    }

    @Test fun `25d four words on air are echo only when near verbatim`() {
        val c = speaking(speaker)
        said(c, "You've got a few tasks wrapped up, and Friday coming up.")
        c.h(started("e"), 1.0)
        assertEquals("one slipped filler (\"on\"): still the echo", emptyList<BargeInCommand>(), c.h(tr("Coming up on Friday", "e", final = true), 1.9))
        assertEquals("held until the next segment starts", listOf("e"), c.pendingDeletes)
        assertTrue("the reply plays on", c.shouldEnqueueAudio("r1"))
        val d = speaking(speaker)
        said(d, "You've got a few tasks wrapped up, and Friday coming up.")
        d.h(started("u"), 1.0)
        val out = d.h(tr("Coming up on Sunday", "u", final = true), 1.9)
        assertTrue("a content word it never said: theirs", out.contains(turn("Coming up on Sunday")))
        assertEquals(1, count(out, cancel))
        assertEquals("nothing deleted", emptyList<String>(), d.pendingDeletes)
    }

    // ── 26: a create the server swallowed (iOS BargeInTests 25a–c, build 75 —
    // Zubair's call, 2026-09-20 18:02: a cancel went unanswered, the fallback
    // create produced nothing, the muted reply completed, his "Yes." was never
    // answered — 20 s of silence). The turn now stays pending until
    // response.created. ──

    @Test fun `26a the turn stays pending until the server creates`() {
        val c = controller(speaker)
        c.h(started("u"), 0.0)
        c.h(stopped, 1.0)
        c.h(tr("what's on today", "u", final = true), 1.2)
        assertEquals(ask, c.h(tick, 1.8))
        assertTrue("asked for, not yet created", c.pendingCreate)
        assertEquals(1800L, c.createSentAt)
        assertEquals("in flight: no second create, a timer for the rest of the grace", cmds(timer(2301)), c.h(tick, 2.5))
        c.h(BargeInEvent.ResponseCreated("r1"), 2.6)
        assertFalse(c.pendingCreate)
        assertNull(c.createSentAt)
        assertEquals("nothing pending once created", emptyList<BargeInCommand>(), c.h(tick, 5.0))
    }

    @Test fun `26b a swallowed fallback create is re-asked when the active reply finishes`() {
        val c = controller(speaker)
        c.h(BargeInEvent.ResponseCreated("r1"), 0.0)   // the results reply: thinking, no audio yet
        c.h(started("y"), 0.4)
        c.h(stopped, 0.9)
        val turn = c.h(tr("Yes.", "y", final = true), 1.0)
        assertEquals(1, count(turn, cancel))
        assertTrue(c.pendingCreate)
        assertEquals("waiting for the cancelled done", emptyList<BargeInCommand>(), c.h(tick, 1.5))
        assertEquals("no done, no error: the 2.5 s fallback asks", ask, c.h(tick, 3.6))
        assertTrue("still pending: nothing was created", c.pendingCreate)
        // The server swallowed the create AND the cancel: the reply completes, muted.
        assertEquals("re-asked at once — hold long up, server quiet", ask, c.h(done("r1"), 4.0))
        c.h(BargeInEvent.ResponseCreated("r2"), 4.5)
        assertFalse(c.pendingCreate)
        assertTrue("the answer to \"Yes.\" plays", c.shouldEnqueueAudio("r2"))
    }

    @Test fun `26c a create swallowed while idle is resent after the grace`() {
        val c = controller(speaker)
        c.h(started("u"), 0.0)
        c.h(stopped, 1.0)
        c.h(tr("what's on today", "u", final = true), 1.2)
        assertEquals("the create arms its own grace tick", ask, c.h(tick, 1.8))
        assertEquals(cmds(timer(2801)), c.h(tick, 2.0))
        // Nothing else will come from idle: the tick the create armed re-asks.
        assertEquals("nothing came in 3 s: ask again", ask, c.h(tick, 4.801))
        // "Already has an active response" to that re-send: the mark clears and
        // the turn is asked for again once the hold is up.
        val complaint = c.h(BargeInEvent.Error("Conversation already has an active response"), 5.0)
        assertTrue("re-asked at once: $complaint", complaint.contains(create))
        assertEquals("a fresh create in flight", 5000L, c.createSentAt)
    }

    // ── 27: a rate-limited reply (iOS BargeInTests 26a–b, build 76 — OpenAI:
    // the token bucket ran dry; Ahmad's 2026-09-20 23:48 session went silent)
    // is asked for again after the bucket's reset, three times at most. The
    // retry-delay parsing is the client's (VoiceRealtimeClientTest). ──

    @Test fun `27a a rate-limited reply is re-asked after the reset`() {
        val c = controller(speaker)
        c.h(started("u"), 0.0)
        c.h(stopped, 1.0)
        c.h(tr("what's on today", "u", final = true), 1.2)
        assertEquals(ask, c.h(tick, 1.8))
        c.h(BargeInEvent.ResponseCreated("r1"), 2.0)
        assertFalse(c.pendingCreate)
        val out = c.h(BargeInEvent.ResponseRateLimited(7000), 2.3)
        assertEquals("the turn is pending again; the tick after the reset asks", cmds(timer(7000), thinking), out)
        assertTrue(c.pendingCreate)
        assertFalse(c.responseActive)
        assertEquals(1, c.rateLimitRetries)
        assertEquals(ask, c.h(tick, 9.4))
        c.h(BargeInEvent.ResponseCreated("r2"), 9.6)
        c.h(BargeInEvent.AudioDelta("r2"), 9.7)
        assertTrue("the retried reply plays", c.shouldEnqueueAudio("r2"))
        c.h(done("r2"), 12.0)
        assertEquals("a completed reply clears the count", 0, c.rateLimitRetries)
    }

    @Test fun `27b after three rate-limited retries the turn is dropped`() {
        val c = controller(speaker)
        c.h(started("u"), 0.0)
        c.h(stopped, 1.0)
        c.h(tr("what's on today", "u", final = true), 1.2)
        c.h(tick, 1.8)
        var t = 2.0
        for (i in 1..3) {
            c.h(BargeInEvent.ResponseCreated("r$i"), t)
            assertEquals("retry $i", cmds(timer(2000), thinking), c.h(BargeInEvent.ResponseRateLimited(2000), t + 0.3))
            assertEquals(ask, c.h(tick, t + 2.4))
            t += 3
        }
        c.h(BargeInEvent.ResponseCreated("r4"), t)
        assertEquals("fourth failure: give up (the client says so out loud)", cmds(listening), c.h(BargeInEvent.ResponseRateLimited(2000), t + 0.3))
        assertFalse(c.pendingCreate)
        // A new turn starts the count afresh.
        c.h(started("v"), t + 5)
        c.h(stopped, t + 6)
        c.h(tr("hello?", "v", final = true), t + 6.2)
        assertEquals(0, c.rateLimitRetries)
    }

    /** Every timer posts the same tick and none is cancelled: the 2.5 s
     *  fallback armed when they talked over the reply, a create's grace, a VAD
     *  blip's 500 ms all wake tryAsk. None may re-ask before the bucket's
     *  reset, or the three retries go in seconds into an empty bucket (review
     *  of the B76.3 port, 2026-09-23; ahead of iOS). */
    @Test fun `27c no stale timer re-asks a rate-limited turn before the reset`() {
        val c = speaking(speaker)
        said(c, "Taxi's at quarter to eight.")
        c.h(started("u"), 1.0)
        c.h(stopped, 1.6)
        assertTrue("the fallback is armed", c.h(tr("what's on today", "u", final = true), 1.8).contains(timer(2500)))
        c.h(done("r1", "cancelled"), 2.0)
        assertTrue(c.h(tick, 2.3).contains(create))
        c.h(BargeInEvent.ResponseCreated("r2"), 2.4)
        assertEquals(cmds(timer(7000), thinking), c.h(BargeInEvent.ResponseRateLimited(7000), 2.6))
        assertEquals(9600L, c.retryNotBefore)
        assertEquals("the stale 2.5 s fallback waits for the reset", cmds(timer(5300)), c.h(tick, 4.3))
        assertEquals("so does the stale grace", cmds(timer(4299)), c.h(tick, 5.301))
        // A new turn in the wait is asked at the reset too: before it, it would only fail again.
        c.h(started("v"), 7.0)
        c.h(stopped, 7.5)
        assertTrue(c.h(tr("hello?", "v", final = true), 7.7).contains(turn("hello?")))
        assertEquals(cmds(timer(1400)), c.h(tick, 8.2))
        assertTrue("asked at the reset", c.h(tick, 9.6).contains(create))
        c.h(BargeInEvent.ResponseCreated("r3"), 9.8)
        assertNull("a created reply clears the wait", c.retryNotBefore)
        assertFalse(c.pendingCreate)
    }

    // ── 28: one word is the user (iOS BargeInTests 27a, build 77 — Zubair's
    // morning call, 2026-09-21 07:01: "Morning." answering "Morning. Want to
    // walk through today?" was deleted as echo of the greeting; nothing
    // happened until "Hello?"). ──

    @Test fun `28a a one-word answer that shares the greeting's word is a turn`() {
        val c = speaking(speaker)
        said(c, "Morning. Want to walk through today?")
        c.h(started("m"), 1.0)                          // on air, as the greeting's last words play
        c.h(stopped, 1.6)
        val out = c.h(tr("Morning.", "m", final = true), 1.8)
        assertTrue("$out", out.contains(turn("Morning.")))
        assertEquals("their word ends the greeting's tail", 1, count(out, cancel))
        assertEquals(emptyList<String>(), c.pendingDeletes)
        // Two words that are the reply's are still judged (a garble is echo).
        val d = speaking(speaker)
        said(d, "Saturday's clear.")
        d.h(started("b"), 1.0)
        assertEquals(emptyList<BargeInCommand>(), d.h(tr("Saturday's players.", "b", final = true), 1.9))
        assertEquals(listOf("b"), d.pendingDeletes)
        // A one-word later piece of an echo-judged segment stays echo.
        val e = speaking(speaker)
        said(e, "Looks pretty solid. You've got a few tasks wrapped up, and Friday coming up.")
        e.h(started("p"), 1.0)
        assertEquals(emptyList<BargeInCommand>(), e.h(tr("Coming up on.", "p", final = true), 1.9))
        assertEquals(emptyList<BargeInCommand>(), e.h(tr("Day.", "p", final = true), 2.5))
        assertEquals(listOf("p"), e.pendingDeletes)
    }

    // ── Android-only: the RMS gate at the Android floor, the noisy-room chip,
    // and the hold-to-talk buffer error ──

    private fun tone(db: Double, n: Int = RmsGate.SUB_FRAME_SAMPLES): ShortArray {
        val amp = (32768.0 * 10.0.pow(db / 20.0)).toInt().coerceIn(0, 32767).toShort()
        return ShortArray(n) { amp }
    }
    @Test fun `10 gate calibrates by median, opens with hysteresis, pre-rolls, emits silence, no adaptation while playing`() {
        val g = RmsGate()
        val floor = -60.0
        // Calibration: 25 sub-frames, one -20 dB cough outlier.
        for (i in 0 until RmsGate.CALIBRATION_SUB_FRAMES) {
            val out = g.process(tone(if (i == 7) -20.0 else floor), 6.0, playbackQueued = false, responseActive = false)
            assertTrue(out.calibrating)
            assertTrue(out.emit.isEmpty())
        }
        assertFalse(g.calibrating)
        assertEquals(floor, g.floorDb, 0.5)

        // Closed: silence frames (same size), not nothing.
        val quiet = g.process(tone(floor), 6.0, false, false)
        assertEquals(1, quiet.emit.size)
        assertEquals(RmsGate.SUB_FRAME_SAMPLES, quiet.emit[0].size)
        assertTrue(quiet.emit[0].all { it == 0.toShort() })
        assertFalse(quiet.opened)

        // Fill the pre-roll ring with distinguishable quiet audio (15 × 20 ms).
        repeat(RmsGate.PRE_ROLL_SUB_FRAMES) { g.process(tone(floor - 2), 6.0, false, false) }

        // Opens at floor+6 after 2 consecutive sub-frames — the first is still silence.
        val first = g.process(tone(floor + 6.5), 6.0, false, false)
        assertFalse(first.opened)
        assertTrue(first.emit[0].all { it == 0.toShort() })
        val second = g.process(tone(floor + 6.5), 6.0, false, false)
        assertTrue(second.opened)
        // Pre-roll (300 ms = 15 sub-frames, the newest being the two onset frames) emitted before anything live.
        assertEquals(RmsGate.PRE_ROLL_SUB_FRAMES, second.emit.size)
        assertTrue(second.emit.first().any { it != 0.toShort() })
        assertTrue(second.emit.last().contentEquals(tone(floor + 6.5)))

        // Stays open at floor+4 (above closeDb = floor+3).
        val mid = g.process(tone(floor + 4.0), 6.0, false, false)
        assertTrue(g.open); assertFalse(mid.closed)
        assertEquals(1, mid.emit.size)
        assertTrue(mid.emit[0].contentEquals(tone(floor + 4.0)))

        // Closes only after 200 ms (10 sub-frames) below floor+3.
        repeat(RmsGate.HOLD_SUB_FRAMES - 1) { assertFalse(g.process(tone(floor + 1.0), 6.0, false, false).closed) }
        assertTrue(g.open)
        val closed = g.process(tone(floor + 1.0), 6.0, false, false)
        assertTrue(closed.closed); assertFalse(g.open)
        assertTrue(closed.emit[0].all { it == 0.toShort() })

        // No adaptation while the model plays; adapts (slowly upward) when idle.
        val before = g.floorDb
        repeat(50) { g.process(tone(floor + 2.0), 6.0, playbackQueued = true, responseActive = false) }
        assertEquals(before, g.floorDb, 1e-9)
        repeat(50) { g.process(tone(floor + 2.0), 6.0, playbackQueued = false, responseActive = false) }
        assertTrue(g.floorDb > before)
        assertTrue(g.floorDb - before <= 50 * RmsGate.ADAPT_UP_MAX_DB_PER_SUB_FRAME + 1e-9)
        // Quieter room: fast downward follow, floor clamped at -70.
        repeat(100) { g.process(tone(-90.0), 6.0, false, false) }
        assertEquals(RmsGate.FLOOR_MIN_DB, g.floorDb, 0.5)

        // Speaker profile: +9 dB margin while playing keeps floor+7 from opening.
        val g2 = RmsGate()
        repeat(RmsGate.CALIBRATION_SUB_FRAMES) { g2.process(tone(floor), 9.0, false, false) }
        repeat(3) { assertFalse(g2.process(tone(floor + 7.0), speaker.gateMargin(true), true, true).opened) }
        assertFalse(g2.open)

        // Hold-to-talk helper: forced open flushes the pre-roll, released appends nothing (caller's job).
        val g3 = RmsGate()
        repeat(RmsGate.CALIBRATION_SUB_FRAMES) { g3.process(tone(-60.0), 6.0, false, false) }
        repeat(5) { g3.process(tone(-62.0), 6.0, false, false) }
        g3.forceOpen(true)
        val pressed = g3.process(tone(-40.0), 6.0, false, false)
        assertTrue(pressed.opened)
        assertEquals(RmsGate.PRE_ROLL_SUB_FRAMES + 1, pressed.emit.size) // full 300 ms pre-roll + the live one
        g3.forceOpen(false)
        assertFalse(g3.open)
    }

    @Test fun `hold-to-talk empty-buffer commit error is non-fatal, open-mic buffer error is reported`() {
        // Too-short press: commit rejected ("buffer too small") — back to the hold
        // prompt, NOT a dead ERROR screen (mic, socket and comm mode are all live).
        val c = controller(holdToTalk = true)
        c.handle(BargeInEvent.PttDown)
        val up = c.handle(BargeInEvent.PttUp)
        assertTrue(up.contains(thinking))
        val out = c.handle(BargeInEvent.Error("input_audio_buffer commit failed: buffer too small"))
        assertFalse(out.any { it is BargeInCommand.ReportError })
        assertTrue(out.contains(listening))
        assertEquals(BargeInUi.LISTENING, c.ui)
        // Still in hold mode; the next press works as usual.
        assertTrue(c.handle(BargeInEvent.PttDown).contains(BargeInCommand.ForceGate(true)))
        assertTrue(BargeInController.isEmptyBufferError("Buffer is empty"))
        assertFalse(BargeInController.isEmptyBufferError("invalid_request_error"))
        // Open mic: a buffer error is a real protocol fault and IS surfaced.
        val o = controller()
        assertEquals(1, o.handle(BargeInEvent.Error("buffer too small")).count { it is BargeInCommand.ReportError })
    }

    @Test fun `auto-suggest hold-to-talk after 3 false barge-ins within 2 minutes`() {
        val c = speaking()
        var suggested = false
        repeat(3) { i ->
            clock = i * 1000L
            c.handle(BargeInEvent.GateOpen)
            clock += 50
            if (c.handle(BargeInEvent.GateClose).contains(BargeInCommand.SuggestHoldToTalk)) suggested = true
        }
        assertTrue(suggested)
        assertEquals(3, c.falseBargeIns)
    }

    // ── the recap's end (cross-platform rule, 2026-09-24): the client ends a
    // spoken review's recap when answeredTurns moves — a turn the app answers,
    // never a raw speech start (the loudspeaker's echo of the review fires one).

    @Test fun `29a answeredTurns counts only turns the app answers, never echo or a speech start`() {
        val c = controller(speaker)
        c.h(BargeInEvent.ResponseCreated("r1"), 0.0)
        c.h(BargeInEvent.AudioDelta("r1"), 0.1)
        said(c, "You finished the chapter draft and skipped stretch once.")
        c.h(started("echo"), 1.0)                                    // the review's echo, on air
        assertEquals("a speech start is no turn", 0, c.answeredTurns)
        c.h(done("r1"), 1.2)
        c.h(BargeInEvent.PlaybackDrained, 1.3)
        c.h(stopped, 1.8)
        c.h(tr("chapter draft and skipped stretch once", "echo", final = true), 1.9)
        assertEquals("echo judged by its words is no turn", 0, c.answeredTurns)
        c.h(started("tail"), 2.0)                                     // inside the drain grace
        assertEquals(0, c.answeredTurns)
        c.h(started("q"), 4.0)
        c.h(stopped, 5.0)
        c.h(tr("thanks what is next today", "q", final = true), 5.2)
        assertEquals("the user's words, answered", 1, c.answeredTurns)
        c.h(tr("thanks what is next today", "q", final = true), 5.3)
        assertEquals("a re-sent transcript is not a second turn", 1, c.answeredTurns)
    }

    @Test fun `29b hold-to-talk - the release is the turn, its late transcript is not another`() {
        val c = speaking(speaker, holdToTalk = true)
        c.h(BargeInEvent.PttDown, 1.0)
        assertEquals(0, c.answeredTurns)
        c.h(BargeInEvent.PttUp, 2.0)
        assertEquals(1, c.answeredTurns)
        c.h(BargeInEvent.ResponseCreated("r2"), 2.3)
        c.h(tr("how was my week", "h", final = true), 2.4)
        assertEquals("the caption of the turn already answered", 1, c.answeredTurns)
    }
}
