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
    private val now = 1_800_000_000_000L
    private val payload = IncomingCallPayload(
        callId = "0b8a7e60-1111-4222-8333-444455556666", label = "speak to James", notes = listOf("A", "B"),
        taskId = "t-1", blockId = "b-1", taskName = "Call James",
    )

    private fun queued(): List<PendingOutcome> = CallOutcomeStore.load(context).items
    private fun ring() { NotificationChannels.ensureAll(context); CallRinger.ring(context, payload, now) }
    private fun fire(action: String, callId: String = payload.callId) =
        MissedCallReceiver().onReceive(context, MissedCallReceiver.intent(context, action, callId))

    @Test fun `the missed alarm settles MISSED once, posts the notice, clears the ring`() {
        ring()
        fire(MissedCallReceiver.ACTION_MISSED)
        assertEquals(listOf(CallOutcome.MISSED), queued().map { it.outcome })
        assertNull("ring notification gone", shadowOf(nm).getNotification(NotifIds.CALL))
        val notice: Notification = shadowOf(nm).getNotification(NotifIds.callResult(payload.callId))
        assertNotNull(notice)
        assertEquals("I called about speak to James", notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("A\nB", notice.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertNull(CallRinger.activeCallId(context))
        assertNull(CallRinger.ringing(context))
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
        assertEquals(payload.callId, CallRinger.activeCallId(context))
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
        assertNull(CallRinger.activeCallId(context))
    }

    @Test fun `answering keeps the call ACTIVE for the voice service and blocks missed or declined`() {
        ring()
        assertTrue(CallRinger.settle(context, payload.callId, CallOutcome.ANSWERED))
        assertEquals(listOf(CallOutcome.ANSWERED), queued().map { it.outcome })
        assertNull("ring notification down", shadowOf(nm).getNotification(NotifIds.CALL))
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
        assertEquals("still the active call", payload.callId, CallRinger.activeCallId(context))
        assertNull("but no longer ringing", CallRinger.ringing(context))
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
        assertNull(CallRinger.activeCallId(context))
        assertFalse(CallRinger.settle(context, payload.callId, CallOutcome.SNOOZED, snoozeMin = 5))
    }

    @Test fun `a new ring replaces a settled one, and ringing() restores the payload`() {
        ring()
        fire(MissedCallReceiver.ACTION_MISSED)
        val second = payload.copy(callId = "second", label = "the 3 o'clock", notes = emptyList(), taskId = null, blockId = null)
        CallRinger.ring(context, second, now + 60_000)
        assertEquals("second", CallRinger.activeCallId(context))
        assertEquals(second, CallRinger.ringing(context))
        assertEquals(now + 60_000, CallRinger.ringStartedMs(context))
        assertNotNull(shadowOf(nm).getNotification(NotifIds.CALL))
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java)).nextScheduledAlarm
        assertEquals(now + 60_000 + CallRinger.MISSED_AFTER_MS, alarm!!.triggerAtTime)
    }

    @Test fun `clear() forgets the ring without reporting (sign-out)`() {
        ring()
        CallRinger.clear(context)
        assertNull(CallRinger.activeCallId(context))
        assertNull(shadowOf(nm).getNotification(NotifIds.CALL))
        assertTrue(queued().isEmpty())
        assertFalse(CallRinger.settle(context, payload.callId, CallOutcome.MISSED))
    }

    @Test fun `the full-screen-intent flag reflects the OS permission at ring time`() {
        ring()
        // Robolectric grants USE_FULL_SCREEN_INTENT by default (API 34).
        assertEquals(!CallRinger.canUseFullScreenIntent(context), CallRinger.fullScreenIntentDenied(context))
    }

    @Test fun `the activity intent round-trips the payload through String extras`() {
        val i = CallRinger.activityIntent(context, payload, CallRinger.ACTION_ANSWER)
        assertEquals(CallRinger.ACTION_ANSWER, i.action)
        assertEquals(payload, IncomingCallActivity.payloadFrom(i))
        assertEquals(IncomingCallActivity::class.java.name, i.component?.className)
    }
}
