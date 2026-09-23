package tech.csalliance.unstuck.surface

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
import tech.csalliance.unstuck.calls.CallOutcomeStore
import tech.csalliance.unstuck.calls.CallRinger
import tech.csalliance.unstuck.calls.IncomingCallActivity
import tech.csalliance.unstuck.core.logic.CallEnv
import tech.csalliance.unstuck.core.logic.CallNotificationCopy
import tech.csalliance.unstuck.core.logic.CallOutcome
import tech.csalliance.unstuck.core.logic.IncomingCallPayload
import tech.csalliance.unstuck.core.logic.PendingOutcome

// "Unstuck calls you" on Android, C1-android: the FCM ring push (send-call →
// data-only, kind=call) runs the iOS CallCoordinator receipt table inside the
// FCM window — ring (CallStyle + full-screen intent + 30 s missed alarm), or
// silent / declined / busy / stale with the matching outcome + notification.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PushTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    /**
     * 30 s after the contract's `scheduledAt` — a ring due more than 10 min ago is
     * stale by rule. Every read of the persisted ring state below passes THIS clock
     * (CallRinger.activeCallId/ringStartedMs take one, as CallPushHandler does):
     * a fixed instant stamped into the record and then measured against the real
     * system clock would go "stale" as soon as the wall clock moved past it.
     */
    private val now = java.time.Instant.parse("2026-09-09T13:45:30Z").toEpochMilli()

    private val contract = mapOf(
        "kind" to "call",
        "callId" to "0b8a7e60-1111-4222-8333-444455556666",
        "taskId" to "t-1",
        "blockId" to "b-1",
        "title" to "speak to James",
        "notes" to """["A","  B ",""]""",
        "scheduledAt" to "2026-09-09T13:45:00Z",
        "deepLink" to "unstuck://call/0b8a7e60-1111-4222-8333-444455556666",
        // sendFcmPush also folds a fallback body in; extras from the VoIP payload ride along.
        "body" to "Unstuck is calling about speak to James\n• A\n• B",
        "label" to "speak to James",
        "taskName" to "Call James about the invoice",
        "name" to "Ahmad",
    )
    private val callId = contract.getValue("callId")

    private val ringEnv = CallEnv(signedIn = true, assistantEnabled = true, withinHours = true, focusLive = false, anchorExists = true)
    private fun queued(): List<PendingOutcome> = CallOutcomeStore.load(context).items
    private fun handle(data: Map<String, String> = contract, env: CallEnv = ringEnv, at: Long = now): Boolean {
        NotificationChannels.ensureAll(context)
        return CallPushHandler.handle(context, data, nowMs = at, env = { env })
    }

    @Test fun `not a call push, or an invalid one, falls through to the generic renderer`() {
        assertFalse(handle(mapOf("kind" to "morning_brief", "title" to "x", "body" to "y")))
        assertFalse(handle(mapOf("kind" to "call", "callId" to " ", "title" to "x", "body" to "fallback")))
        assertFalse(handle(mapOf("kind" to "call", "callId" to "c", "title" to "  ", "body" to "fallback")))
        assertEquals(0, shadowOf(nm).size())
        assertTrue(queued().isEmpty())
    }

    @Test fun `a valid ring posts the CallStyle notification with full-screen intent + arms the 30 s missed alarm`() {
        assertTrue(handle())
        val n: Notification = shadowOf(nm).getNotification(NotifIds.CALL)
        assertNotNull("the ring rides under NotifIds.CALL", n)
        assertEquals(NotificationChannels.CALLS, n.channelId)
        assertEquals(Notification.CATEGORY_CALL, n.category)
        assertNotNull("full-screen intent → IncomingCallActivity", n.fullScreenIntent)
        val fsi = shadowOf(n.fullScreenIntent).savedIntent
        assertEquals(IncomingCallActivity::class.java.name, fsi.component?.className)
        assertEquals(callId, fsi.getStringExtra("callId"))
        assertEquals("speak to James", fsi.getStringExtra("label"))
        assertNotNull("tap opens the ring too", n.contentIntent)
        assertTrue("sticky while ringing", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertTrue("Snooze 10 rides as an extra action", n.actions.any { it.title.toString() == "Snooze 10" })
        // The ring channel: HIGH, a ringtone, vibration.
        val ch = nm.getNotificationChannel(NotificationChannels.CALLS)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, ch.importance)
        assertNotNull(ch.sound)
        assertTrue(ch.shouldVibrate())
        // The missed alarm, 30 s out, through Doze.
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java)).nextScheduledAlarm
        assertNotNull("missed alarm armed", alarm)
        assertEquals(now + CallRinger.MISSED_AFTER_MS, alarm!!.triggerAtTime)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        // Nothing reported yet: the outcome is whatever settles the ring first.
        assertTrue(queued().isEmpty())
        assertEquals(callId, CallRinger.activeCallId(context, now))
        assertEquals(now, CallRinger.ringStartedMs(context, now))
        // Logged to the in-app notification centre under kind=call.
        val entry = NotificationLog.items.value.first()
        assertEquals("call", entry.kind)
        assertEquals("unstuck://call/$callId", entry.deepLink)
    }

    @Test fun `a retried push for the ringing call updates in place and does not restart the clock`() {
        assertTrue(handle(at = now))
        assertTrue(handle(at = now + 12_000))
        assertEquals(1, shadowOf(nm).size())
        assertEquals(now, CallRinger.ringStartedMs(context, now + 12_000))
        assertEquals(1, shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
        assertTrue(queued().isEmpty())
    }

    @Test fun `a push for ANOTHER call while one is up ends as busy with the notice`() {
        assertTrue(handle())
        val second = contract + mapOf("callId" to "second-call", "title" to "the 3 o'clock", "label" to "the 3 o'clock", "deepLink" to "unstuck://call/second-call")
        assertTrue(handle(second))
        assertEquals(listOf("second-call" to CallOutcome.BUSY), queued().map { it.callId to it.outcome })
        val n = shadowOf(nm).getNotification(NotifIds.callResult("second-call"))
        assertNotNull(n)
        assertEquals(CallNotificationCopy.busy(IncomingCallPayload.fromData(second)!!).title, n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        // The first ring is untouched.
        assertEquals(callId, CallRinger.activeCallId(context, now))
        assertNotNull(shadowOf(nm).getNotification(NotifIds.CALL))
    }

    @Test fun `nobody signed in → silent - no ring, no outcome, no notes shown`() {
        assertTrue(handle(env = ringEnv.copy(signedIn = false)))
        assertEquals(0, shadowOf(nm).size())
        assertTrue(queued().isEmpty())
        assertNull(CallRinger.activeCallId(context, now))
    }

    @Test fun `outside the call hours → declined + the hours notice with Start and Reschedule`() {
        assertTrue(handle(env = ringEnv.copy(withinHours = false)))
        assertNull(shadowOf(nm).getNotification(NotifIds.CALL))
        assertEquals(listOf(CallOutcome.DECLINED), queued().map { it.outcome })
        val n = shadowOf(nm).getNotification(NotifIds.callResult(callId))
        assertNotNull(n)
        assertEquals("I called about speak to James", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("A\nB\n" + CallNotificationCopy.HOURS_HINT, n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertEquals(listOf("Start", "Reschedule"), n.actions.map { it.title.toString() })
        assertEquals("unstuck://task/t-1?exact", shadowOf(n.contentIntent).savedIntent.data.toString())
        // QUIET (parity with iOS build 78): it lands when the server rang —
        // outside the hours, possibly 3 am — so no sound, no heads-up.
        assertEquals(NotificationChannels.CALL_NOTES, n.channelId)
        val ch = nm.getNotificationChannel(NotificationChannels.CALL_NOTES)
        assertEquals(NotificationManager.IMPORTANCE_LOW, ch.importance)
        assertNull("silent", ch.sound)
        assertFalse(ch.shouldVibrate())
        @Suppress("DEPRECATION") assertEquals(Notification.PRIORITY_LOW, n.priority)
        assertEquals("setSilent: a member of the group never alerts", Notification.GROUP_ALERT_SUMMARY, n.groupAlertBehavior)
    }

    @Test fun `the kill-switches decline with the calls-off notice`() {
        assertTrue(handle(env = ringEnv.copy(assistantEnabled = false)))
        var n = shadowOf(nm).getNotification(NotifIds.callResult(callId))
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().endsWith("(calls are switched off — Settings › Calls)"))
        assertTrue(handle(contract + mapOf("callId" to "c2"), env = ringEnv.copy(callsEnabled = false)))
        n = shadowOf(nm).getNotification(NotifIds.callResult("c2"))
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().endsWith("(calls are switched off — Settings › Calls)"))
        assertEquals("the user switched calls off — a quiet note, never a buzz", NotificationChannels.CALL_NOTES, n.channelId)
        assertEquals(listOf(CallOutcome.DECLINED, CallOutcome.DECLINED), queued().map { it.outcome })
    }

    @Test fun `a live focus session → busy + notice`() {
        assertTrue(handle(env = ringEnv.copy(focusLive = true)))
        assertEquals(listOf(CallOutcome.BUSY), queued().map { it.outcome })
        val n = shadowOf(nm).getNotification(NotifIds.callResult(callId))
        assertEquals("I called about speak to James — you were mid-focus", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("busy / missed / voice-failed stay loud, as on iOS", NotificationChannels.REMINDERS, n.channelId)
    }

    @Test fun `anchor gone → stale, silently - unknown anchor rings`() {
        assertTrue(handle(env = ringEnv.copy(anchorExists = false)))
        assertEquals(listOf(CallOutcome.STALE), queued().map { it.outcome })
        assertEquals(0, shadowOf(nm).size())
        assertTrue(handle(contract + mapOf("callId" to "c2"), env = ringEnv.copy(anchorExists = null)))
        assertNotNull(shadowOf(nm).getNotification(NotifIds.CALL))
    }

    @Test fun `a Doze-delayed ring (due more than 10 min ago) is reported stale instead of ringing late`() {
        val due = 1_800_000_000_000L
        val late = contract + mapOf("scheduledAt" to java.time.Instant.ofEpochMilli(due).toString())
        assertTrue(handle(late, at = due + 11 * 60_000))
        assertEquals(listOf(CallOutcome.STALE), queued().map { it.outcome })
        assertNull(shadowOf(nm).getNotification(NotifIds.CALL))
    }

    @Test fun `a task-less call has no Start or Reschedule on its notice and lands on Today`() {
        val standalone = mapOf("kind" to "call", "callId" to "c-3", "title" to "the 3 o'clock", "notes" to "[]")
        assertTrue(handle(standalone, env = ringEnv.copy(focusLive = true)))
        val n = shadowOf(nm).getNotification(NotifIds.callResult("c-3"))
        assertTrue(n.actions == null || n.actions.isEmpty())
        assertEquals("No notes on this one.", n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertEquals("unstuck://today", shadowOf(n.contentIntent).savedIntent.data.toString())
    }

    // ── callKind + endTime (calls build-out 2026-09-20) ──

    @Test fun `a proactive ring carries its callKind and endTime into the persisted ring, and kind stays the discriminator`() {
        val after = contract + mapOf("callKind" to "after_block", "endTime" to "11:30")
        assertTrue(handle(after))
        val ringing = CallRinger.ringing(context, now)!!
        assertEquals(tech.csalliance.unstuck.core.model.CallKind.AFTER_BLOCK, ringing.resolvedKind)
        assertEquals("11:30", ringing.endTime)
        assertNotNull(shadowOf(nm).getNotification(NotifIds.CALL))
        CallRinger.clear(context)
        // A morning call has no anchor: it rings even though nothing is checked.
        val morning = mapOf("kind" to "call", "callKind" to "morning", "callId" to "m1", "title" to "Morning plan", "label" to "Morning plan", "scheduledAt" to "2026-09-09T13:45:00Z")
        assertTrue(handle(morning, env = ringEnv.copy(anchorExists = null)))
        assertEquals(tech.csalliance.unstuck.core.model.CallKind.MORNING, CallRinger.ringing(context, now)!!.resolvedKind)
        CallRinger.clear(context)
        // A server that wrote the row's kind into `kind` still rings; a foreign kind still falls through.
        assertTrue(handle(mapOf("kind" to "evening", "callId" to "e1", "title" to "Evening wrap-up", "scheduledAt" to "2026-09-09T13:45:00Z"), env = ringEnv.copy(anchorExists = null)))
        assertEquals(tech.csalliance.unstuck.core.model.CallKind.EVENING, CallRinger.ringing(context, now)!!.resolvedKind)
        assertFalse(handle(mapOf("kind" to "morning_brief", "callId" to "x", "title" to "y")))
    }
}
