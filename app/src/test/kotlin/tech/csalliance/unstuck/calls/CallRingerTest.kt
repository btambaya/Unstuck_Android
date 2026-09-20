package tech.csalliance.unstuck.calls

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.core.logic.CallNotificationKind
import tech.csalliance.unstuck.core.logic.CallOutcome
import tech.csalliance.unstuck.core.logic.IncomingCallPayload
import tech.csalliance.unstuck.core.logic.PendingOutcome
import tech.csalliance.unstuck.surface.NotifIds
import tech.csalliance.unstuck.surface.NotificationChannels

// The ring's one-outcome-per-call rule (iOS CallCoordinator "ONE END PER
// CALL") across the three background settlers — the 30 s missed alarm and the
// shade's Decline / Snooze (MissedCallReceiver) — plus what an answer leaves
// behind for the voice service.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CallRingerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    /**
     * The synthetic clock the first half of this suite rings on. Every read of the
     * persisted ring state below is handed it too: the staleness bound measures the
     * record's stamp against the clock it is GIVEN, so a fixed instant read back
     * against the real system clock would rot into "stale" (or, if the instant is
     * in the future, pass for the wrong reason until that date arrives).
     */
    private val now = 1_800_000_000_000L
    private val payload = IncomingCallPayload(
        callId = "0b8a7e60-1111-4222-8333-444455556666", label = "speak to James", notes = listOf("A", "B"),
        taskId = "t-1", blockId = "b-1", taskName = "Call James",
    )

    private fun queued(): List<PendingOutcome> = CallOutcomeStore.load(context).items
    private fun ring() { NotificationChannels.ensureAll(context); CallRinger.ring(context, payload, now) }
    private fun fire(action: String, callId: String = payload.callId) =
        MissedCallReceiver().onReceive(context, MissedCallReceiver.intent(context, action, callId))

    @Test fun `the missed alarm settles MISSED once, hands the notice to the queue, clears the ring`() {
        ring()
        fire(MissedCallReceiver.ACTION_MISSED)
        assertEquals(listOf(CallOutcome.MISSED), queued().map { it.outcome })
        assertNull("ring notification gone", shadowOf(nm).getNotification(NotifIds.CALL))
        // The "I called about X" notice is NOT posted here: it rides with the
        // queued report and CallOutcomeStore posts it only once call-outcome
        // answers `retry: false` (a first miss is re-rung by the server).
        assertNull(shadowOf(nm).getNotification(NotifIds.callResult(payload.callId)))
        val item = queued().single()
        assertEquals(CallNotificationKind.MISSED, item.notify)
        assertEquals(payload, item.ringPayload)
        assertNull(CallRinger.activeCallId(context, now))
        assertNull(CallRinger.ringing(context, now))
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())

        // A second fire (a duplicate alarm, or the activity's own countdown) is a no-op.
        fire(MissedCallReceiver.ACTION_MISSED)
        assertEquals(1, queued().size)
        // And so is a late Decline / Answer from a stale screen.
        fire(MissedCallReceiver.ACTION_DECLINE)
        assertFalse(CallRinger.settle(context, payload.callId, CallOutcome.ANSWERED))
        assertEquals(1, queued().size)
    }

    @Test fun `the alarm for a call that is not the active one does nothing`() {
        ring()
        fire(MissedCallReceiver.ACTION_MISSED, callId = "some-other-call")
        assertTrue(queued().isEmpty())
        assertNotNull(shadowOf(nm).getNotification(NotifIds.CALL))
        assertEquals(payload.callId, CallRinger.activeCallId(context, now))
    }

    @Test fun `Decline from the shade reports DECLINED with no notice, and the alarm after it is a no-op`() {
        ring()
        fire(MissedCallReceiver.ACTION_DECLINE)
        assertEquals(listOf(CallOutcome.DECLINED), queued().map { it.outcome })
        assertEquals(0, shadowOf(nm).size())
        fire(MissedCallReceiver.ACTION_MISSED)
        assertEquals(listOf(CallOutcome.DECLINED), queued().map { it.outcome })
        assertNull(shadowOf(nm).getNotification(NotifIds.callResult(payload.callId)))
    }

    @Test fun `Snooze 10 from the shade reports SNOOZED(10) + a brief confirmation`() {
        ring()
        fire(MissedCallReceiver.ACTION_SNOOZE)
        val item = queued().single()
        assertEquals(CallOutcome.SNOOZED, item.outcome)
        assertEquals(10, item.snoozeMin)
        val ack = shadowOf(nm).getNotification(NotifIds.callResult(payload.callId))
        assertEquals("I'll call back in 10 minutes", ack.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNull(CallRinger.activeCallId(context, now))
    }

    @Test fun `answering keeps the call ACTIVE for the voice service and blocks missed or declined`() {
        ring()
        // The whole test runs on the synthetic `now`, so the answer is stamped with
        // it too — the ACTIVE record's staleness bound is measured from that stamp.
        assertTrue(CallRinger.settle(context, payload.callId, CallOutcome.ANSWERED, nowMs = now))
        assertEquals(listOf(CallOutcome.ANSWERED), queued().map { it.outcome })
        assertNull("ring notification down", shadowOf(nm).getNotification(NotifIds.CALL))
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
        assertEquals("still the active call", payload.callId, CallRinger.activeCallId(context, now))
        assertNull("but no longer ringing", CallRinger.ringing(context, now))
        // The alarm / a stale Decline can't undo an answer.
        fire(MissedCallReceiver.ACTION_MISSED)
        fire(MissedCallReceiver.ACTION_DECLINE)
        assertFalse(CallRinger.settle(context, payload.callId, CallOutcome.ANSWERED))
        assertEquals(listOf(CallOutcome.ANSWERED), queued().map { it.outcome })
        // A retried push for the answered call must not re-ring over the conversation.
        CallRinger.ring(context, payload, now + 5_000)
        assertNull(shadowOf(nm).getNotification(NotifIds.CALL))
        // The voice service ends it: done (or snoozed) settles the active call.
        assertTrue(CallRinger.settle(context, payload.callId, CallOutcome.DONE))
        assertEquals(listOf(CallOutcome.ANSWERED, CallOutcome.DONE), queued().map { it.outcome })
        assertNull(CallRinger.activeCallId(context, now))
        assertFalse(CallRinger.settle(context, payload.callId, CallOutcome.SNOOZED, snoozeMin = 5))
    }

    @Test fun `a new ring replaces a settled one, and ringing() restores the payload`() {
        ring()
        fire(MissedCallReceiver.ACTION_MISSED)
        val second = payload.copy(callId = "second", label = "the 3 o'clock", notes = emptyList(), taskId = null, blockId = null)
        CallRinger.ring(context, second, now + 60_000)
        assertEquals("second", CallRinger.activeCallId(context, now + 60_000))
        assertEquals(second, CallRinger.ringing(context, now + 60_000))
        assertEquals(now + 60_000, CallRinger.ringStartedMs(context, now + 60_000))
        assertNotNull(shadowOf(nm).getNotification(NotifIds.CALL))
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java)).nextScheduledAlarm
        assertEquals(now + 60_000 + CallRinger.MISSED_AFTER_MS, alarm!!.triggerAtTime)
    }

    @Test fun `clear() forgets the ring without reporting (sign-out)`() {
        ring()
        CallRinger.clear(context)
        assertNull(CallRinger.activeCallId(context, now))
        assertNull(shadowOf(nm).getNotification(NotifIds.CALL))
        assertTrue(queued().isEmpty())
        assertFalse(CallRinger.settle(context, payload.callId, CallOutcome.MISSED))
    }

    @Test fun `the full-screen-intent flag reflects the OS permission at ring time`() {
        ring()
        // Robolectric grants USE_FULL_SCREEN_INTENT by default (API 34).
        assertEquals(!CallRinger.canUseFullScreenIntent(context), CallRinger.fullScreenIntentDenied(context))
    }

    // ── staleness + recovery: the record must never wedge the phone ──────────
    // A reboot drops the 30 s missed alarm with every other alarm, and a process
    // kill mid-call leaves nobody to end the conversation. Before the bound, the
    // unsettled record made activeCallId non-null for ever and EVERY later call
    // was reported `busy` without ringing — the user's calls died permanently.
    // These use offsets from the real clock: the staleness readers ask the system
    // clock, exactly as the FCM path does.

    private fun ringAgo(ms: Long, p: IncomingCallPayload = payload) {
        NotificationChannels.ensureAll(context)
        CallRinger.ring(context, p, System.currentTimeMillis() - ms)
    }

    @Test fun `a ring the alarm never settled goes stale, so the next call still rings`() {
        ringAgo(CallRinger.MISSED_AFTER_MS + CallRinger.RING_STALE_GRACE_MS + 1_000)
        assertNull("no longer 'busy'", CallRinger.activeCallId(context))
        assertNull(CallRinger.ringing(context))
        assertNull(CallRinger.ringStartedMs(context))
    }

    @Test fun `a ring still inside its 30 s window is live, not stale`() {
        ringAgo(5_000)
        assertEquals(payload.callId, CallRinger.activeCallId(context))
        assertNotNull(CallRinger.ringing(context))
        assertNull("nothing to recover", CallRinger.recover(context))
        assertTrue(queued().isEmpty())
    }

    @Test fun `recover reports the pending missed with its deferred notice, clears the ring, and is idempotent`() {
        ringAgo(CallRinger.MISSED_AFTER_MS + CallRinger.RING_STALE_GRACE_MS + 1_000)
        assertEquals(CallOutcome.MISSED, CallRinger.recover(context))
        assertEquals(listOf(CallOutcome.MISSED), queued().map { it.outcome })
        assertEquals("the notice rides with the report", CallNotificationKind.MISSED, queued().single().notify)
        assertEquals(payload, queued().single().ringPayload)
        assertNull("posted only when the server settles it", shadowOf(nm).getNotification(NotifIds.callResult(payload.callId)))
        assertNull(shadowOf(nm).getNotification(NotifIds.CALL))
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
        // A second launch (or the FCM path) must not report it twice.
        assertNull(CallRinger.recover(context))
        assertEquals(1, queued().size)
        // And a late alarm for the retired call is a no-op.
        fire(MissedCallReceiver.ACTION_MISSED)
        assertEquals(1, queued().size)
    }

    @Test fun `an answered call whose voice service died recovers as done`() {
        val answeredAgo = CallRinger.ACTIVE_HANDOFF_GRACE_MS + 60_000
        ringAgo(answeredAgo + 10_000)
        assertTrue(CallRinger.settle(context, payload.callId, CallOutcome.ANSWERED, nowMs = System.currentTimeMillis() - answeredAgo))
        assertNull("no CallVoiceService owns it in this process", CallRinger.activeCallId(context))
        assertEquals(CallOutcome.DONE, CallRinger.recover(context))
        assertEquals(listOf(CallOutcome.ANSWERED, CallOutcome.DONE), queued().map { it.outcome })
        // No "I called about …" notice: the conversation happened.
        assertNull(shadowOf(nm).getNotification(NotifIds.callResult(payload.callId)))
    }

    @Test fun `the answer hand-off to the voice service is not mistaken for an abandoned call`() {
        ringAgo(5_000)
        assertTrue(CallRinger.settle(context, payload.callId, CallOutcome.ANSWERED))
        assertEquals("still busy while the FGS starts", payload.callId, CallRinger.activeCallId(context))
        assertNull(CallRinger.recover(context))
    }

    @Test fun `the server re-ringing a stale call starts a fresh ring rather than a duplicate`() {
        ringAgo(CallRinger.MISSED_AFTER_MS + CallRinger.RING_STALE_GRACE_MS + 1_000)
        val fresh = System.currentTimeMillis()
        CallRinger.ring(context, payload, fresh)
        assertEquals(payload.callId, CallRinger.activeCallId(context))
        assertEquals("the 30 s clock restarted", fresh, CallRinger.ringStartedMs(context))
        assertNotNull(shadowOf(nm).getNotification(NotifIds.CALL))
    }

    @Test fun `the activity intent round-trips the payload through String extras`() {
        val i = CallRinger.activityIntent(context, payload, CallRinger.ACTION_ANSWER)
        assertEquals(CallRinger.ACTION_ANSWER, i.action)
        assertEquals(payload, IncomingCallActivity.payloadFrom(i))
        assertEquals(IncomingCallActivity::class.java.name, i.component?.className)
    }
}
