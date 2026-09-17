package tech.csalliance.unstuck.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.BargeInCommand
import tech.csalliance.unstuck.core.logic.BargeInController
import tech.csalliance.unstuck.core.logic.BargeInEvent
import tech.csalliance.unstuck.core.logic.BargeInPhase
import tech.csalliance.unstuck.core.logic.BargeInProfile
import tech.csalliance.unstuck.core.logic.BargeInUi
import tech.csalliance.unstuck.core.logic.RmsGate
import tech.csalliance.unstuck.core.logic.TurnDetection
import tech.csalliance.unstuck.core.logic.VoiceRoute
import kotlin.math.pow

// The 12 barge-in cases shared with lib/voice/barge-in.test.ts (web) and
// BargeInControllerTests.swift (iOS) — keep them in lock-step. Pure controller
// with a fake clock and scripted events.
class BargeInControllerTest {

    private var clock = 0L
    private fun controller(profile: BargeInProfile = BargeInProfile.SPEAKER, holdToTalk: Boolean = false) =
        BargeInController(profile, holdToTalk) { clock }

    /** Model mid-reply: response.created + one delta played. */
    private fun speaking(c: BargeInController, id: String = "r1") {
        c.handle(BargeInEvent.ResponseCreated(id))
        c.handle(BargeInEvent.AudioDelta(id))
        assertTrue(c.speaking)
    }

    private inline fun <reified T : BargeInCommand> List<BargeInCommand>.count() = filterIsInstance<T>().size
    private inline fun <reified T : BargeInCommand> List<BargeInCommand>.has() = any { it is T }

    @Test
    fun `1 speaking + speech_started ducks synchronously without cancelling`() {
        val c = controller()
        speaking(c)
        val out = c.handle(BargeInEvent.SpeechStarted)
        assertEquals(listOf<BargeInCommand>(BargeInCommand.Duck(300L)), out)
        assertEquals(BargeInPhase.DUCKED, c.phase)
        assertFalse(out.has<BargeInCommand.SendCancel>())
        assertFalse(c.muted)
    }

    // ── 2: confirm needs BOTH the server's segment and a mic still above the
    // gate; the timer alone is a blip → restore (+ suppress what the server
    // will reply to). Ahmad's iPhone, 2026-09-17 — iOS BargeInTests 2/2b/2c.

    @Test
    fun `2 confirm with sustained mic energy cancels - exactly one cancel, flush, mute, restore, listening`() {
        val c = controller()
        speaking(c)
        c.handle(BargeInEvent.SpeechStarted)
        clock = 50
        assertTrue("gate opening while ducked is bookkeeping only", c.handle(BargeInEvent.GateOpen).isEmpty())
        assertEquals(BargeInPhase.DUCKED, c.phase)
        assertTrue(c.gateOpen); assertTrue(c.serverSpeaking)
        clock = 150; assertTrue(c.handle(BargeInEvent.Tick).isEmpty()) // not yet
        clock = 300
        val out = c.handle(BargeInEvent.Tick)
        assertEquals(1, out.count<BargeInCommand.SendCancel>())
        assertTrue(out.has<BargeInCommand.FlushPlayback>())
        assertTrue(out.contains(BargeInCommand.SetMuted(true)))
        assertTrue(out.has<BargeInCommand.Restore>())
        assertTrue(out.contains(BargeInCommand.Ui(BargeInUi.LISTENING)))
        assertTrue(c.muted)
        assertEquals(BargeInPhase.IDLE, c.phase)
        assertEquals("r1", c.cancelledResponseId)
        // A later tick is inert.
        clock = 900; assertTrue(c.handle(BargeInEvent.Tick).isEmpty())
        // Stale deltas of r1 are dropped even before the next response.
        assertTrue(c.handle(BargeInEvent.AudioDelta("r1")).isEmpty())
        assertFalse(c.handle(BargeInEvent.TranscriptDelta("r1")).has<BargeInCommand.ShowCaption>())
    }

    @Test
    fun `2b a server blip with no mic energy at confirm restores and suppresses the reply it will make`() {
        // The loudspeaker case that cut every reply: the server VAD fired on a
        // tap, the gate had already closed again (or never opened), and
        // speech_stopped cannot arrive inside the window (600 ms of silence
        // first). The timer must NOT cancel.
        val c = controller()
        speaking(c)
        c.handle(BargeInEvent.SpeechStarted)
        clock = 20; c.handle(BargeInEvent.GateOpen)
        clock = 250; c.handle(BargeInEvent.GateClose)          // the tap ended; server still in its segment
        assertEquals("a server duck waits for the tick", BargeInPhase.DUCKED, c.phase)
        clock = 300
        val out = c.handle(BargeInEvent.Tick)
        assertEquals(listOf<BargeInCommand>(BargeInCommand.Restore), out)
        assertFalse(out.has<BargeInCommand.SendCancel>())
        assertEquals(BargeInPhase.SPEAKING, c.phase)
        assertFalse(c.muted)
        assertTrue("the reply keeps playing", c.handle(BargeInEvent.AudioDelta("r1")).has<BargeInCommand.EnqueueAudio>())
        assertTrue("the server will still reply to the blip — that reply is cancelled on creation", c.suppressNextResponse)
        clock = 900; c.handle(BargeInEvent.SpeechStopped)
        val created = c.handle(BargeInEvent.ResponseCreated("r2"))
        assertEquals(1, created.count<BargeInCommand.SendCancel>())
        assertFalse(c.handle(BargeInEvent.AudioDelta("r2")).has<BargeInCommand.EnqueueAudio>())
    }

    @Test
    fun `2c a gate-only duck with no server agreement restores without suppression`() {
        // Sustained mic energy the server never called speech (a fan, a loud
        // room): nothing was committed server-side, so restore and suppress nothing.
        val c = controller()
        speaking(c)
        c.handle(BargeInEvent.GateOpen)
        assertEquals(BargeInPhase.DUCKED, c.phase)
        clock = 300
        val out = c.handle(BargeInEvent.Tick)
        assertEquals(listOf<BargeInCommand>(BargeInCommand.Restore), out)
        assertFalse(c.suppressNextResponse)
        assertEquals(BargeInPhase.SPEAKING, c.phase)
        assertTrue("the gate is still open; a later speech_started re-ducks and can then confirm", c.gateOpen)
        clock = 1000
        c.handle(BargeInEvent.SpeechStarted)
        assertEquals(BargeInPhase.DUCKED, c.phase)
        clock = 1300
        assertEquals("gate + server agree at confirm → real talk-over", 1, c.handle(BargeInEvent.Tick).count<BargeInCommand.SendCancel>())
    }

    @Test
    fun `3 speech_stopped at 150ms - restore, no cancel, next response suppressed`() {
        val c = controller()
        speaking(c)
        c.handle(BargeInEvent.SpeechStarted)
        clock = 150
        val out = c.handle(BargeInEvent.SpeechStopped)
        assertTrue(out.has<BargeInCommand.Restore>())
        assertFalse(out.has<BargeInCommand.SendCancel>())
        assertTrue(c.suppressNextResponse)
        assertEquals(BargeInPhase.SPEAKING, c.phase)
        clock = 400; assertTrue(c.handle(BargeInEvent.Tick).isEmpty()) // restored → no late cancel

        val created = c.handle(BargeInEvent.ResponseCreated("r2"))
        assertEquals(1, created.count<BargeInCommand.SendCancel>())
        assertTrue(created.contains(BargeInCommand.SetMuted(true)))
        assertFalse(c.suppressNextResponse)
        assertFalse(c.handle(BargeInEvent.AudioDelta("r2")).has<BargeInCommand.EnqueueAudio>())
        // The genuine next reply plays again.
        val r3 = c.handle(BargeInEvent.ResponseCreated("r3"))
        assertTrue(r3.contains(BargeInCommand.SetMuted(false)))
        assertTrue(c.handle(BargeInEvent.AudioDelta("r3")).has<BargeInCommand.EnqueueAudio>())
    }

    @Test
    fun `4 gate_close at 120ms with no speech_started - restore, no cancel, no suppression`() {
        val c = controller()
        speaking(c)
        val duck = c.handle(BargeInEvent.GateOpen)
        assertEquals(listOf<BargeInCommand>(BargeInCommand.Duck(300L)), duck)
        clock = 120
        val out = c.handle(BargeInEvent.GateClose)
        assertEquals(listOf<BargeInCommand>(BargeInCommand.Restore), out)
        assertFalse(c.suppressNextResponse)
        assertEquals(BargeInPhase.SPEAKING, c.phase)
        assertFalse(c.muted)
        clock = 400; assertTrue(c.handle(BargeInEvent.Tick).isEmpty())
        // Gate + server agreement cancels immediately.
        c.handle(BargeInEvent.GateOpen)
        val agreed = c.handle(BargeInEvent.SpeechStarted)
        assertEquals(1, agreed.count<BargeInCommand.SendCancel>())
        assertEquals(BargeInPhase.IDLE, c.phase)
    }

    @Test
    fun `5 transcription while ducked with the mic open cancels immediately`() {
        val c = controller()
        speaking(c)
        c.handle(BargeInEvent.SpeechStarted)
        clock = 20; c.handle(BargeInEvent.GateOpen)           // the mic agrees
        clock = 100
        val out = c.handle(BargeInEvent.TranscriptionCompleted)
        assertEquals(1, out.count<BargeInCommand.SendCancel>())
        assertTrue(out.has<BargeInCommand.FlushPlayback>())
        assertEquals(BargeInPhase.IDLE, c.phase)
        assertTrue(c.muted)
        // The delta form confirms the same way.
        val d = controller()
        speaking(d)
        d.handle(BargeInEvent.GateOpen); d.handle(BargeInEvent.SpeechStarted)
        assertEquals(BargeInPhase.IDLE, d.phase)              // gate + server agreement already cancelled
    }

    @Test
    fun `5b a transcription with the mic closed is not a confirm`() {
        // Deltas stream for the previous turn and for the model's own echo
        // (iOS device log 2026-09-17); with the gate closed they confirm nothing.
        val c = controller()
        speaking(c)
        c.handle(BargeInEvent.SpeechStarted)
        assertFalse(c.gateOpen)
        clock = 100
        assertTrue(c.handle(BargeInEvent.TranscriptionDelta).isEmpty())
        assertTrue(c.handle(BargeInEvent.TranscriptionCompleted).isEmpty())
        assertEquals("still waiting for the tick", BargeInPhase.DUCKED, c.phase)
        assertTrue(c.handle(BargeInEvent.AudioDelta("r1")).has<BargeInCommand.EnqueueAudio>())
        clock = 300
        assertEquals("and the tick restores (no mic energy)", listOf<BargeInCommand>(BargeInCommand.Restore), c.handle(BargeInEvent.Tick))
        assertTrue(c.suppressNextResponse)
    }

    @Test
    fun `6 interrupt_pressed is a hard cancel - never ducks, tail = flush only, idle = no-op`() {
        val c = controller()
        speaking(c)
        val out = c.handle(BargeInEvent.InterruptPressed)
        assertFalse(out.has<BargeInCommand.Duck>())
        assertEquals(1, out.count<BargeInCommand.SendCancel>())
        assertTrue(out.has<BargeInCommand.FlushPlayback>())
        assertTrue(out.contains(BargeInCommand.Ui(BargeInUi.LISTENING)))
        assertEquals(BargeInPhase.IDLE, c.phase)

        // Only the buffered tail left (response.done, not drained yet).
        val c2 = controller()
        speaking(c2)
        c2.handle(BargeInEvent.ResponseDone("r1"))
        assertTrue(c2.playbackQueued); assertFalse(c2.responseActive)
        val tail = c2.handle(BargeInEvent.InterruptPressed)
        assertFalse(tail.has<BargeInCommand.SendCancel>())
        assertTrue(tail.has<BargeInCommand.FlushPlayback>())
        assertFalse(c2.playbackQueued)

        // Idle: nothing.
        val c3 = controller()
        assertTrue(c3.handle(BargeInEvent.InterruptPressed).isEmpty())
    }

    @Test
    fun `7 active-response errors are benign`() {
        for (msg in listOf("Conversation has no active response", "Conversation already has an active response")) {
            val c = controller()
            c.handle(BargeInEvent.ResponseCreated("r1"))
            val out = c.handle(BargeInEvent.Error(msg))
            assertFalse(c.responseActive)
            assertFalse(out.has<BargeInCommand.ReportError>())
            assertEquals(BargeInUi.LISTENING, c.ui)
        }
        val c = controller()
        val real = c.handle(BargeInEvent.Error("Rate limit exceeded"))
        assertTrue(real.contains(BargeInCommand.ReportError("Rate limit exceeded")))
    }

    @Test
    fun `8 response_done with playback queued stays speaking until drained`() {
        val c = controller()
        speaking(c)
        val done = c.handle(BargeInEvent.ResponseDone("r1", "completed"))
        assertFalse(done.contains(BargeInCommand.Ui(BargeInUi.LISTENING)))
        assertEquals(BargeInUi.SPEAKING, c.ui)
        assertTrue(c.speaking)
        val drained = c.handle(BargeInEvent.PlaybackDrained)
        assertTrue(drained.contains(BargeInCommand.Ui(BargeInUi.LISTENING)))
        assertEquals(BargeInPhase.IDLE, c.phase)
    }

    @Test
    fun `9 deltas for the cancelled response are dropped even after the next response is created`() {
        val c = controller()
        speaking(c)
        c.handle(BargeInEvent.InterruptPressed)
        c.handle(BargeInEvent.ResponseCreated("r2"))
        assertFalse(c.muted)
        assertFalse(c.handle(BargeInEvent.AudioDelta("r1")).has<BargeInCommand.EnqueueAudio>())
        assertTrue(c.handle(BargeInEvent.AudioDelta("r2")).has<BargeInCommand.EnqueueAudio>())
        assertFalse(c.handle(BargeInEvent.AudioDelta("r1")).has<BargeInCommand.EnqueueAudio>())
        // Captions follow the same rule.
        assertFalse(c.handle(BargeInEvent.TranscriptDelta("r1")).has<BargeInCommand.ShowCaption>())
        assertTrue(c.handle(BargeInEvent.TranscriptDelta("r2")).has<BargeInCommand.ShowCaption>())
    }

    // ── RMS gate ──

    private fun tone(db: Double, n: Int = RmsGate.SUB_FRAME_SAMPLES): ShortArray {
        val amp = (32768.0 * 10.0.pow(db / 20.0)).toInt().coerceIn(0, 32767).toShort()
        return ShortArray(n) { amp }
    }

    @Test
    fun `10 gate calibrates by median, opens with hysteresis, pre-rolls, emits silence, no adaptation while playing`() {
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
        repeat(3) { assertFalse(g2.process(tone(floor + 7.0), BargeInProfile.SPEAKER.gateMargin(true), true, true).opened) }
        assertFalse(g2.open)
        assertEquals(6.0, BargeInProfile.SPEAKER.gateMargin(false), 0.0)
        assertEquals(6.0, BargeInProfile.LOW_ECHO.gateMargin(true), 0.0)
    }

    @Test
    fun `11 route change re-sends turn_detection and swaps the confirm window`() {
        val c = controller(BargeInProfile.SPEAKER)
        assertEquals(0.6, c.turnDetection()!!.threshold, 0.0)
        val out = c.handle(BargeInEvent.RouteChanged(BargeInProfile.forRoute(VoiceRoute.LOW_ECHO)))
        val upd = out.filterIsInstance<BargeInCommand.SessionUpdate>().single()
        val td = upd.turnDetection!!
        assertEquals("server_vad", td.type)
        assertEquals(0.5, td.threshold, 0.0)
        assertEquals(300, td.prefixPaddingMs)
        assertEquals(600, td.silenceDurationMs)
        assertEquals(200L, c.profile.confirmMs)
        val json = td.toJson()
        assertEquals(0.5, json["threshold"]!!.jsonPrimitive.double, 0.0)
        assertEquals(300, json["prefix_padding_ms"]!!.jsonPrimitive.int)
        assertEquals(600, json["silence_duration_ms"]!!.jsonPrimitive.int)
        assertEquals("server_vad", json["type"]!!.jsonPrimitive.content)
        // Confirm window now 200 ms (gate + server both agreeing at the tick).
        speaking(c)
        c.handle(BargeInEvent.SpeechStarted)
        c.handle(BargeInEvent.GateOpen)
        clock = 199; assertTrue(c.handle(BargeInEvent.Tick).isEmpty())
        clock = 200; assertEquals(1, c.handle(BargeInEvent.Tick).count<BargeInCommand.SendCancel>())
        // The loudspeaker is HALF-DUPLEX by default (2026-09-17); the assisted
        // duplex speaker profile is the opt-in, with the same tuning. Earphones /
        // Bluetooth stay full duplex.
        assertFalse(BargeInProfile.forRoute(VoiceRoute.SPEAKER).assistedDuplex)
        assertTrue(BargeInProfile.forRoute(VoiceRoute.SPEAKER).halfDuplexWhilePlaying)
        assertFalse(BargeInProfile.forRoute(VoiceRoute.SPEAKER, speakerHalfDuplex = true).assistedDuplex)
        assertTrue(BargeInProfile.forRoute(VoiceRoute.SPEAKER, speakerHalfDuplex = false).assistedDuplex)
        assertEquals(0.6, BargeInProfile.forRoute(VoiceRoute.SPEAKER, speakerHalfDuplex = false).threshold, 0.0)
        assertEquals(300L, BargeInProfile.SPEAKER_ASSISTED_DUPLEX.confirmMs)
        assertTrue(BargeInProfile.forRoute(VoiceRoute.LOW_ECHO).assistedDuplex)
        assertFalse(BargeInProfile.LOW_ECHO.halfDuplexWhilePlaying)
    }

    @Test
    fun `12 hold-to-talk - press cancels, release commits, turn_detection null`() {
        val c = controller(holdToTalk = true)
        assertNull(c.turnDetection())
        assertEquals(JsonNull, TurnDetection.json(c.turnDetection()))
        assertEquals(BargeInPhase.HOLD, c.phase)
        speaking(c)
        // VAD events are ignored in hold mode.
        assertTrue(c.handle(BargeInEvent.SpeechStarted).isEmpty())
        assertTrue(c.handle(BargeInEvent.GateOpen).isEmpty())
        val down = c.handle(BargeInEvent.PttDown)
        assertEquals(1, down.count<BargeInCommand.SendCancel>())
        assertTrue(down.has<BargeInCommand.FlushPlayback>())
        assertTrue(down.contains(BargeInCommand.ForceGate(true)))
        assertTrue(c.pttPressed)
        val up = c.handle(BargeInEvent.PttUp)
        assertTrue(up.contains(BargeInCommand.ForceGate(false)))
        assertTrue(up.has<BargeInCommand.CommitAndRespond>())
        assertFalse(c.pttPressed)
        // Route change in hold mode never re-enables server VAD.
        assertTrue(c.handle(BargeInEvent.RouteChanged(BargeInProfile.LOW_ECHO)).isEmpty())
        // Switching hold-to-talk off restores server VAD.
        val off = c.setHoldToTalk(false)
        assertEquals(0.5, off.filterIsInstance<BargeInCommand.SessionUpdate>().single().turnDetection!!.threshold, 0.0)
        // Gate helper: forced open flushes the pre-roll, released appends nothing (caller's job).
        val g = RmsGate()
        repeat(RmsGate.CALIBRATION_SUB_FRAMES) { g.process(tone(-60.0), 6.0, false, false) }
        repeat(5) { g.process(tone(-62.0), 6.0, false, false) }
        g.forceOpen(true)
        val pressed = g.process(tone(-40.0), 6.0, false, false)
        assertTrue(pressed.opened)
        assertEquals(RmsGate.PRE_ROLL_SUB_FRAMES + 1, pressed.emit.size) // full 300 ms pre-roll + the live one
        g.forceOpen(false)
        assertFalse(g.open)
    }

    @Test
    fun `hold-to-talk empty-buffer commit error is non-fatal, open-mic buffer error is reported`() {
        // Too-short press: commit rejected ("buffer too small") — back to the hold
        // prompt, NOT a dead ERROR screen (mic, socket and comm mode are all live).
        val c = controller(holdToTalk = true)
        c.handle(BargeInEvent.PttDown)
        val up = c.handle(BargeInEvent.PttUp)
        assertTrue(up.contains(BargeInCommand.Ui(BargeInUi.THINKING)))
        val out = c.handle(BargeInEvent.Error("input_audio_buffer commit failed: buffer too small"))
        assertFalse(out.has<BargeInCommand.ReportError>())
        assertTrue(out.contains(BargeInCommand.Ui(BargeInUi.LISTENING)))
        assertEquals(BargeInUi.LISTENING, c.ui)
        // Still in hold mode; the next press works as usual.
        assertTrue(c.handle(BargeInEvent.PttDown).contains(BargeInCommand.ForceGate(true)))
        assertTrue(BargeInController.isEmptyBufferError("Buffer is empty"))
        assertFalse(BargeInController.isEmptyBufferError("invalid_request_error"))
        // Open mic: a buffer error is a real protocol fault and IS surfaced.
        val o = controller()
        val err = o.handle(BargeInEvent.Error("buffer too small"))
        assertEquals(1, err.count<BargeInCommand.ReportError>())
    }

    @Test
    fun `auto-suggest hold-to-talk after 3 false barge-ins within 2 minutes`() {
        val c = controller()
        speaking(c)
        var suggested = false
        repeat(3) { i ->
            clock = i * 1000L
            c.handle(BargeInEvent.GateOpen)
            clock += 50
            if (c.handle(BargeInEvent.GateClose).has<BargeInCommand.SuggestHoldToTalk>()) suggested = true
        }
        assertTrue(suggested)
    }

    // ── 16: loudspeaker half-duplex while the model is audible ──

    @Test
    fun `16 the speaker gate is forced closed for the whole reply, hold-to-talk overrides, earphones never`() {
        // Controller: forced closed from response.created (the queue runs dry
        // between bursts and at the start) until the playback has drained.
        val c = controller(BargeInProfile.SPEAKER)
        assertFalse(c.gateForcedClosed)
        c.handle(BargeInEvent.ResponseCreated("r1"))
        assertTrue("muted from response.created", c.gateForcedClosed)
        c.handle(BargeInEvent.AudioDelta("r1"))
        assertTrue(c.gateForcedClosed)
        c.handle(BargeInEvent.ResponseDone("r1", "completed"))
        assertTrue("the buffered tail is still audible", c.gateForcedClosed)
        c.handle(BargeInEvent.PlaybackDrained)
        assertFalse(c.gateForcedClosed)
        // Hold-to-talk wins: the button down opens the mic even mid-reply.
        val h = controller(BargeInProfile.SPEAKER, holdToTalk = true)
        speaking(h)
        assertTrue(h.gateForcedClosed)
        h.handle(BargeInEvent.PttDown)
        assertFalse(h.gateForcedClosed)
        assertTrue(h.pttPressed)
        // Low-echo never forces the gate closed.
        val e = controller(BargeInProfile.LOW_ECHO)
        speaking(e)
        assertFalse(e.gateForcedClosed)
        // The pure helper the audio engine calls per frame.
        assertTrue(BargeInProfile.gateForcedClosed(BargeInProfile.SPEAKER, modelBusy = true, pttPressed = false))
        assertFalse(BargeInProfile.gateForcedClosed(BargeInProfile.SPEAKER, modelBusy = true, pttPressed = true))
        assertFalse(BargeInProfile.gateForcedClosed(BargeInProfile.SPEAKER, modelBusy = false, pttPressed = false))
        assertFalse(BargeInProfile.gateForcedClosed(BargeInProfile.SPEAKER_ASSISTED_DUPLEX, modelBusy = true, pttPressed = false))
        assertFalse(BargeInProfile.gateForcedClosed(BargeInProfile.LOW_ECHO, modelBusy = true, pttPressed = false))

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
            assertEquals(1, o.emit.size)
            assertTrue(o.emit[0].all { it == 0.toShort() })
        }
        // Released: post-reply room tone becomes the new pre-roll; nothing from before the mute.
        repeat(3) { g.process(tone(floor - 2), 6.0, false, false) }
        g.process(loud, 6.0, false, false)
        val reopened = g.process(loud, 6.0, false, false)
        assertTrue(reopened.opened)
        assertEquals("pre-roll = 3 quiet + the two onset frames — nothing from before the mute", 5, reopened.emit.size)
        assertTrue(reopened.emit.first().contentEquals(tone(floor - 2)))
        assertTrue(reopened.emit.last().contentEquals(loud))
        // Hold-to-talk's press beats the mute.
        g.forceOpen(true)
        val pressed = g.process(loud, 9.0, true, true, forcedClosed = true)
        assertEquals(1, pressed.emit.size)
        assertTrue(pressed.emit[0].contentEquals(loud))
        g.forceOpen(false)
    }

    @Test
    fun `speech_started while idle clears the caption and response_done then drain ends a ducked tail`() {
        val c = controller()
        assertEquals(listOf<BargeInCommand>(BargeInCommand.ClearCaption), c.handle(BargeInEvent.SpeechStarted))
        speaking(c)
        c.handle(BargeInEvent.ResponseDone("r1"))
        c.handle(BargeInEvent.GateOpen)
        assertEquals(BargeInPhase.DUCKED, c.phase)
        val out = c.handle(BargeInEvent.PlaybackDrained)
        assertTrue(out.has<BargeInCommand.Restore>())
        assertEquals(BargeInPhase.IDLE, c.phase)
    }
}
