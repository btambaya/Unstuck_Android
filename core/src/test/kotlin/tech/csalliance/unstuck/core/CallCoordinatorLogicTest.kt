package tech.csalliance.unstuck.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.CallCoordinatorLogic
import tech.csalliance.unstuck.core.logic.CallDecision
import tech.csalliance.unstuck.core.logic.CallEndReason
import tech.csalliance.unstuck.core.logic.CallEnv
import tech.csalliance.unstuck.core.logic.CallNotificationCopy
import tech.csalliance.unstuck.core.logic.CallNotificationKind
import tech.csalliance.unstuck.core.logic.CallOutcome
import tech.csalliance.unstuck.core.logic.CoordinatorState
import tech.csalliance.unstuck.core.logic.IncomingCallPayload
import tech.csalliance.unstuck.core.model.CallRequest
import tech.csalliance.unstuck.core.model.CallStatus

// The pure call state machine — the iOS CallCoordinatorTests rules that
// survive without CallKit (receipt rules + their order, the notifications'
// copy and deep links, one outcome per call, missed after 30 s, decline is
// silent, snooze clamps + acks, end reason → outcome, persisted state) plus
// the CallRequest row model (migrations 051/053) and the server's outcome
// wire strings (call-outcome/index.ts OUTCOMES).
class CallCoordinatorLogicTest {
    private val callId = "0f1e2d3c-4b5a-4697-8877-665544332211"
    private val t0 = 1_800_000_000_000L

    /** The iOS FakeEnvironment defaults: signed in, inside hours, no focus, anchor live. */
    private fun env(
        signedIn: Boolean = true, assistantEnabled: Boolean = true, withinHours: Boolean = true,
        focusLive: Boolean = false, anchorExists: Boolean? = true, callsEnabled: Boolean = true,
    ) = CallEnv(signedIn, assistantEnabled, withinHours, focusLive, anchorExists, callsEnabled)

    private fun payload(taskId: String? = "task-1", notes: List<String> = listOf("Ask about the invoice", "Confirm Friday")) =
        IncomingCallPayload(
            callId = callId, label = "speak to James", notes = notes,
            taskId = taskId, blockId = taskId?.let { "block-1" }, taskName = "Speak to James",
            firstAction = "open the thread", name = "Ahmad",
        )

    // ── wire strings ────────────────────────────────────────────────────────

    @Test fun `outcome wire strings are the server's OUTCOMES set`() {
        assertEquals(
            listOf("answered", "declined", "missed", "busy", "snoozed", "done", "stale"),
            CallOutcome.entries.map { it.wire },
        )
        assertEquals(CallOutcome.SNOOZED, CallOutcome.fromWire("snoozed"))
        assertNull(CallOutcome.fromWire("outside_hours"))
        assertEquals("\"missed\"", Json.encodeToString(CallOutcome.serializer(), CallOutcome.MISSED))
    }

    @Test fun `call_requests statuses are the 051 CHECK list and the web's live editable reschedulable sets`() {
        assertEquals(
            listOf("scheduled", "calling", "answered", "declined", "missed", "busy", "snoozed", "stale", "cancelled", "done"),
            CallStatus.entries.map { it.wire },
        )
        assertEquals(listOf("scheduled", "snoozed", "calling"), CallRequest.liveStatuses)
        assertEquals(listOf("scheduled", "snoozed", "calling", "answered"), CallRequest.editableStatuses)
        assertEquals(listOf("scheduled", "snoozed"), CallRequest.reschedulableStatuses)
        assertEquals(CallStatus.RESCHEDULABLE, CallStatus.statusesAccepting(timeChange = true))
        assertEquals(CallStatus.EDITABLE, CallStatus.statusesAccepting(timeChange = false))
        assertEquals(listOf("scheduled", "snoozed"), CallStatus.wiresAccepting(timeChange = true))
        assertTrue(CallStatus.CALLING.isLive && CallStatus.CALLING.isEditable && !CallStatus.CALLING.isReschedulable)
        assertTrue(CallStatus.ANSWERED.isInProgress && CallStatus.ANSWERED.isEditable && !CallStatus.ANSWERED.isLive)
        assertTrue(CallStatus.DONE.isTerminal && CallStatus.CANCELLED.isTerminal && !CallStatus.MISSED.isTerminal)
        assertNull(CallStatus.fromWire("bogus"))
    }

    @Test fun `CallRequest decodes a PostgREST row with null arrays`() {
        val json = """
        {"id":"r1","user_id":"u","task_id":null,"block_id":null,"call_at":"2026-09-02T14:45:00+00:00",
         "lead_min":null,"label":"speak to James","notes":["A","B"],"status":"snoozed",
         "snooze_until":"2026-09-02T15:00:00+00:00","outcome_notes":null,"call_id":"c1","attempts":1,
         "created_at":"2026-09-02T10:00:00.123456+00:00","updated_at":"2026-09-02T10:00:00+00:00","future_col":1}
        """
        val r = Json { ignoreUnknownKeys = true }.decodeFromString(CallRequest.serializer(), json)
        assertEquals(listOf("A", "B"), r.notes)
        assertEquals(emptyList<String>(), r.outcomeNotes)
        assertTrue(r.isLive)
        assertEquals(CallStatus.SNOOZED, r.statusEnum)
        assertEquals(1788360300000L, r.callAtMs)
        assertEquals(1788361200000L, r.effectiveAtMs)
        assertEquals("c1", r.callId)
        val bare = Json { ignoreUnknownKeys = true }.decodeFromString(CallRequest.serializer(), """{"id":"r2","call_at":"2026-09-02T14:45:00Z","label":"x"}""")
        assertEquals(emptyList<String>(), bare.notes)
        assertEquals("scheduled", bare.status)
        assertEquals(bare.callAtMs, bare.effectiveAtMs)
        val unknown = Json { ignoreUnknownKeys = true }.decodeFromString(CallRequest.serializer(), """{"id":"r3","call_at":"x","label":"x","status":"paused"}""")
        assertNull(unknown.statusEnum)
        assertNull(unknown.callAtMs)
        assertFalse(unknown.isLive)
        // 072: kind + retries. A pre-072 row is a requested call with no retries.
        assertEquals(tech.csalliance.unstuck.core.model.CallKind.REQUESTED, bare.kindEnum)
        assertNull(bare.retries)
        assertFalse(bare.isTestCall)
        val proactive = Json { ignoreUnknownKeys = true }.decodeFromString(CallRequest.serializer(), """{"id":"r4","call_at":"2026-09-02T14:45:00Z","label":"Morning plan","kind":"morning","retries":1}""")
        assertEquals(tech.csalliance.unstuck.core.model.CallKind.MORNING, proactive.kindEnum)
        assertEquals(1, proactive.retries)
        val test = Json { ignoreUnknownKeys = true }.decodeFromString(CallRequest.serializer(), """{"id":"r5","call_at":"2026-09-02T14:45:00Z","label":"Test call","kind":"test","retries":0}""")
        assertTrue(test.isTestCall)
        assertEquals(tech.csalliance.unstuck.core.model.CallKind.REQUESTED, test.copy(kind = "lunch").kindEnum)
        assertEquals(tech.csalliance.unstuck.core.model.CallKind.AFTER_BLOCK, tech.csalliance.unstuck.core.model.CallKind.fromWire(" After_Block "))
        assertNull(tech.csalliance.unstuck.core.model.CallKind.strict("lunch"))
    }

    // ── receipt rules ───────────────────────────────────────────────────────

    @Test fun `a normal ring rings`() {
        assertEquals(CallDecision.Ring, CallCoordinatorLogic.decide(payload(), env()))
        assertTrue(CallDecision.Ring.rings)
        assertNull(CallCoordinatorLogic.reportFor(CallDecision.Ring))
    }

    @Test fun `busy rule when a focus session is live`() {
        val d = CallCoordinatorLogic.decide(payload(), env(focusLive = true))
        assertEquals(CallDecision.Busy, d)
        val r = CallCoordinatorLogic.reportFor(d)!!
        assertEquals(CallOutcome.BUSY, r.outcome)
        assertEquals(CallNotificationKind.BUSY, r.notify)
        assertEquals("I called about speak to James — you were mid-focus", CallNotificationCopy.of(r.notify!!, payload()).title)
    }

    @Test fun `stale rule when the anchor is gone is silent`() {
        val d = CallCoordinatorLogic.decide(payload(), env(anchorExists = false))
        assertEquals(CallDecision.Stale, d)
        val r = CallCoordinatorLogic.reportFor(d)!!
        assertEquals(CallOutcome.STALE, r.outcome)
        assertNull("no notification", r.notify)
        // No task anchored → the anchor rule does not apply, even if the env says false.
        assertEquals(CallDecision.Ring, CallCoordinatorLogic.decide(payload(taskId = null), env(anchorExists = false)))
        // No store yet (killed-state launch) → ring rather than silently drop.
        assertEquals(CallDecision.Ring, CallCoordinatorLogic.decide(payload(), env(anchorExists = null)))
    }

    @Test fun `outside call hours declines quietly with a notification and wins over focus`() {
        val d = CallCoordinatorLogic.decide(payload(), env(withinHours = false, focusLive = true))
        assertTrue(d is CallDecision.Declined)
        val r = CallCoordinatorLogic.reportFor(d)!!
        assertEquals(CallOutcome.DECLINED, r.outcome)
        assertEquals(CallNotificationKind.OUTSIDE_HOURS, r.notify)
        val n = CallNotificationCopy.of(r.notify!!, payload())
        assertEquals("I called about speak to James", n.title)
        assertTrue(n.body.contains("outside your call hours"))
        assertEquals("Ask about the invoice\nConfirm Friday\n(outside your call hours — Settings › Notifications & calls)", n.body)
    }

    @Test fun `the kill-switches decline with the same notice`() {
        assertTrue(CallCoordinatorLogic.decide(payload(), env(assistantEnabled = false)) is CallDecision.Declined)
        assertTrue(CallCoordinatorLogic.decide(payload(), env(callsEnabled = false)) is CallDecision.Declined)
        assertEquals(CallOutcome.DECLINED, CallCoordinatorLogic.reportFor(CallDecision.Declined("assistant off"))!!.outcome)
    }

    @Test fun `a signed-out device drops the call silently — no outcome, no notes`() {
        val d = CallCoordinatorLogic.decide(payload(), env(signedIn = false, withinHours = false, focusLive = true))
        assertTrue("signed-out wins over every other rule", d is CallDecision.Silent)
        assertNull("no JWT to report with", CallCoordinatorLogic.reportFor(d))
        assertFalse(d.rings)
    }

    @Test fun `a Doze-delayed ring is late after ten minutes`() {
        val p = payload().copy(scheduledAtMs = t0)
        assertFalse(CallCoordinatorLogic.isLate(p, t0 + 9 * 60_000))
        assertTrue(CallCoordinatorLogic.isLate(p, t0 + 10 * 60_000 + 1))
        assertFalse("unknown due time → not late", CallCoordinatorLogic.isLate(payload(), t0 + 60 * 60_000))
    }

    // ── notifications (copy + routing) ──────────────────────────────────────

    @Test fun `unanswered notifies with the notes and task actions`() {
        val n = CallNotificationCopy.missed(payload())
        assertEquals("I called about speak to James", n.title)
        assertEquals("Ask about the invoice\nConfirm Friday", n.body)
        assertTrue(n.hasActions)
        assertEquals("task-1", n.taskId)
        assertEquals("block-1", n.blockId)
        assertEquals("Speak to James", n.taskName)
        assertEquals("unstuck://task/task-1?exact", n.deepLink)
        assertEquals("unstuck.call.missed.$callId", n.id)
        assertEquals(CallNotificationKind.MISSED, n.kind)
        assertEquals(CallNotificationKind.MISSED, CallCoordinatorLogic.missedReport().notify)
        assertEquals(CallOutcome.MISSED, CallCoordinatorLogic.missedReport().outcome)
    }

    @Test fun `unanswered without a task has no actions and opens Today`() {
        val n = CallNotificationCopy.missed(payload(taskId = null))
        assertFalse(n.hasActions)
        assertEquals("unstuck://today", n.deepLink)
        assertNull(n.taskId)
        assertNull(n.blockId)
        assertNull(n.taskName)
    }

    @Test fun `unanswered with no notes says so`() {
        assertEquals("No notes on this one.", CallNotificationCopy.missed(payload(notes = emptyList())).body)
    }

    @Test fun `voice failure notifies with the notes`() {
        val n = CallNotificationCopy.voiceFailed(payload())
        assertEquals("Couldn't start the call — here's what it was about", n.title)
        assertEquals("Ask about the invoice\nConfirm Friday", n.body)
        assertEquals("unstuck.call.failed.$callId", n.id)
    }

    @Test fun `every kind has a distinct stable id per call`() {
        val ids = CallNotificationKind.entries.map { CallNotificationCopy.of(it, payload()).id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(ids, CallNotificationKind.entries.map { CallNotificationCopy.of(it, payload()).id })
    }

    // ── ending ──────────────────────────────────────────────────────────────

    @Test fun `user hang-up reports done, snooze reports snoozed with minutes, failure reports done with a note and notifies`() {
        assertEquals(CallOutcome.DONE, CallCoordinatorLogic.endOutcome(CallEndReason.HungUp).outcome)
        assertNull(CallCoordinatorLogic.endOutcome(CallEndReason.HungUp).notify)
        val s = CallCoordinatorLogic.endOutcome(CallEndReason.Snoozed(10))
        assertEquals(CallOutcome.SNOOZED, s.outcome)
        assertEquals(10, s.snoozeMin)
        assertEquals(180, CallCoordinatorLogic.endOutcome(CallEndReason.Snoozed(999)).snoozeMin)
        assertEquals(1, CallCoordinatorLogic.endOutcome(CallEndReason.Snoozed(0)).snoozeMin)
        val f = CallCoordinatorLogic.endOutcome(CallEndReason.Failed("socket closed"))
        assertEquals(CallOutcome.DONE, f.outcome)
        assertEquals(listOf("voice failed: socket closed"), f.outcomeNotes)
        assertEquals(CallNotificationKind.VOICE_FAILED, f.notify)
    }

    @Test fun `decline while ringing is logged without a notification, answer is answered, ring-snooze is snoozed`() {
        assertEquals(CallOutcome.DECLINED, CallCoordinatorLogic.declinedReport().outcome)
        assertNull(CallCoordinatorLogic.declinedReport().notify)
        assertEquals(CallOutcome.ANSWERED, CallCoordinatorLogic.answeredReport().outcome)
        val s = CallCoordinatorLogic.ringSnoozeReport()
        assertEquals(CallOutcome.SNOOZED, s.outcome)
        assertEquals(10, s.snoozeMin)
        assertEquals(180, CallCoordinatorLogic.ringSnoozeReport(999).snoozeMin)
    }

    @Test fun `snooze ack clamps and a second snooze repeats the first`() {
        val a = CallCoordinatorLogic.snoozeAck(10)
        assertTrue(a, a.startsWith("ok:"))
        assertTrue(a.contains("10 minutes"))
        assertEquals("ok: I'll call back in 10 minutes — say a quick goodbye; the call ends now", a)
        assertTrue(CallCoordinatorLogic.snoozeAck(999).contains("180 minutes"))
        assertTrue(CallCoordinatorLogic.snoozeAck(0).contains("1 minutes"))
        assertTrue("the second snooze repeats the first, it can't change it", CallCoordinatorLogic.snoozeAck(25, pendingSnoozeMin = 10).contains("10 minutes"))
        assertEquals("error: no call is active", CallCoordinatorLogic.NO_ACTIVE_CALL)
    }

    // ── one outcome per call + missed timeout + persistence ────────────────

    @Test fun `the first outcome wins and a straggler is refused`() {
        val s0 = CoordinatorState(callId, CallDecision.Ring, ringStartedMs = t0)
        assertTrue(s0.isRinging)
        val missed = s0.firstOutcomeWins(CallOutcome.MISSED)!!
        assertTrue(missed.outcomeReported)
        assertEquals(CallOutcome.MISSED, missed.outcome)
        assertFalse(missed.isRinging)
        assertNull("a late decline after missed is not re-reported", missed.firstOutcomeWins(CallOutcome.DECLINED))
        assertNull(missed.firstOutcomeWins(CallOutcome.ANSWERED))
    }

    @Test fun `answered is not terminal — the end reports once more, then nothing`() {
        val s0 = CoordinatorState(callId, CallDecision.Ring, ringStartedMs = t0)
        val answered = s0.firstOutcomeWins(CallOutcome.ANSWERED)!!
        assertTrue(answered.answered)
        assertFalse(answered.outcomeReported)
        assertFalse("no longer ringing: the missed alarm must not fire", answered.isRinging)
        assertFalse(answered.isMissed(t0 + 60_000))
        assertNull("a duplicate answer is a no-op", answered.firstOutcomeWins(CallOutcome.ANSWERED))
        val done = answered.firstOutcomeWins(CallOutcome.DONE)!!
        assertEquals(CallOutcome.DONE, done.outcome)
        assertNull("one end, one outcome", done.firstOutcomeWins(CallOutcome.SNOOZED))
    }

    @Test fun `missed after thirty seconds, only while ringing`() {
        assertEquals(30_000L, CallCoordinatorLogic.MISSED_AFTER_MS)
        val s = CoordinatorState(callId, CallDecision.Ring, ringStartedMs = t0)
        assertFalse(s.isMissed(t0 + 29_999))
        assertTrue(s.isMissed(t0 + 30_000))
        assertFalse(s.firstOutcomeWins(CallOutcome.DECLINED)!!.isMissed(t0 + 60_000))
        assertFalse(CoordinatorState(callId, CallDecision.Busy, ringStartedMs = t0).isMissed(t0 + 60_000))
    }

    @Test fun `state persists and comes back — a process killed mid-ring still reports missed`() {
        val s = CoordinatorState(callId, CallDecision.Ring, ringStartedMs = t0)
        val back = CoordinatorState.fromJson(s.toJson())!!
        assertEquals(s, back)
        assertTrue(back.isMissed(t0 + 31_000))
        val declined = CoordinatorState(callId, CallDecision.Declined("outside hours"), ringStartedMs = t0)
            .firstOutcomeWins(CallOutcome.DECLINED)!!
        assertEquals(declined, CoordinatorState.fromJson(declined.toJson()))
        assertNull(CoordinatorState.fromJson(null))
        assertNull(CoordinatorState.fromJson(""))
        assertNull(CoordinatorState.fromJson("{not json"))
        assertNotNull(CoordinatorState.fromJson(s.toJson().replace("}", ",\"future\":1}")))
    }

    /** A provider error mid-call used to be dropped — dead air with the mic hot
     *  until the 14-minute cap (parity with iOS build 78). */
    @Test fun `a conversation that ends on its own - error is a voice failure, a snooze in flight wins, clean is a goodbye`() {
        assertEquals(CallEndReason.Failed("The voice server is unavailable right now (502)."),
            CallCoordinatorLogic.endedOnItsOwn("The voice server is unavailable right now (502).", pendingSnoozeMin = null))
        assertEquals(CallEndReason.Snoozed(20), CallCoordinatorLogic.endedOnItsOwn("socket closed", pendingSnoozeMin = 20))
        assertEquals(CallEndReason.Snoozed(10), CallCoordinatorLogic.endedOnItsOwn(null, pendingSnoozeMin = 10))
        assertEquals(CallEndReason.HungUp, CallCoordinatorLogic.endedOnItsOwn(null, pendingSnoozeMin = null))
        val r = CallCoordinatorLogic.endOutcome(CallCoordinatorLogic.endedOnItsOwn("boom", null))
        assertEquals(listOf("voice failed: boom"), r.outcomeNotes)
    }
}
