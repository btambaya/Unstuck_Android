package tech.csalliance.unstuck.calls

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.core.logic.CallOutcome
import tech.csalliance.unstuck.core.logic.IncomingCallPayload
import tech.csalliance.unstuck.core.logic.PendingOutcome
import tech.csalliance.unstuck.surface.NotifIds
import tech.csalliance.unstuck.surface.NotificationChannels
import java.time.Duration

// The full-screen ring screen: label + notes, Decline / Snooze 10 / Answer,
// each settling the call exactly once; Answer is the ONLY path that starts the
// voice service; the 30 s countdown counts from the ORIGINAL ring.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class IncomingCallActivityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val payload = IncomingCallPayload(
        callId = "0b8a7e60-1111-4222-8333-444455556666", label = "speak to James", notes = listOf("A", "B"),
        taskId = "t-1", blockId = "b-1", taskName = "Call James",
    )
    private val started = mutableListOf<IncomingCallPayload>()
    private val originalStarter = IncomingCallActivity.voiceStarter

    @Before fun seam() {
        IncomingCallActivity.voiceStarter = { _, p -> started += p }
        // A microphone FGS may not start without RECORD_AUDIO on API 34+; the ring
        // screen now asks for it, so the answering tests grant it up front.
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }
    @After fun restore() { IncomingCallActivity.voiceStarter = originalStarter }

    private fun queued(): List<PendingOutcome> = CallOutcomeStore.load(context).items
    private fun ring(): IncomingCallPayload {
        NotificationChannels.ensureAll(context)
        CallRinger.ring(context, payload, System.currentTimeMillis())
        return payload
    }
    private fun launch(intent: Intent = CallRinger.activityIntent(context, payload)): IncomingCallActivity =
        Robolectric.buildActivity(IncomingCallActivity::class.java, intent).setup().get()

    @Test fun `shows the label and the notes while ringing`() {
        ring()
        val a = launch()
        assertEquals("speak to James", a.findViewById<TextView>(IncomingCallActivity.ID_LABEL).text.toString())
        assertEquals("Call James", a.findViewById<TextView>(IncomingCallActivity.ID_TASK).text.toString())
        assertEquals("• A\n• B", a.findViewById<TextView>(IncomingCallActivity.ID_NOTES).text.toString())
        assertNotNull(a.findViewById<android.view.View>(IncomingCallActivity.ID_ANSWER))
        assertTrue(!a.isFinishing)
        assertTrue(queued().isEmpty())
    }

    @Test fun `Answer settles ANSWERED and starts the voice service - the only place it starts`() {
        ring()
        val a = launch()
        a.findViewById<android.view.View>(IncomingCallActivity.ID_ANSWER).performClick()
        assertEquals(listOf(CallOutcome.ANSWERED), queued().map { it.outcome })
        assertEquals(listOf(payload), started)
        assertTrue(a.isFinishing)
        assertNull("ring notification down", shadowOf(nm).getNotification(NotifIds.CALL))
        assertEquals("the call stays active for the service", payload.callId, CallRinger.activeCallId(context))
    }

    @Test fun `the shade's Answer action lands here with ACTION_ANSWER and answers without showing the screen`() {
        ring()
        val a = launch(CallRinger.activityIntent(context, payload, CallRinger.ACTION_ANSWER))
        assertEquals(listOf(CallOutcome.ANSWERED), queued().map { it.outcome })
        assertEquals(1, started.size)
        assertTrue(a.isFinishing)
    }

    @Test fun `Answer without the mic permission asks instead of starting the service`() {
        shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        ring()
        val a = launch()
        a.findViewById<android.view.View>(IncomingCallActivity.ID_ANSWER).performClick()
        assertTrue("asked for the mic", shadowOf(a).lastRequestedPermission != null)
        assertTrue("nothing settled while the dialog is up", queued().isEmpty())
        assertTrue("service not started", started.isEmpty())
        assertTrue("screen stays up for the dialog", !a.isFinishing)

        a.onRequestPermissionsResult(
            IncomingCallActivity.REQ_MIC, arrayOf(Manifest.permission.RECORD_AUDIO),
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )
        assertEquals(listOf(CallOutcome.ANSWERED), queued().map { it.outcome })
        assertEquals(listOf(payload), started)
    }

    @Test fun `a refused mic permission reports done with a voice-failed note and the notice`() {
        shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        ring()
        val a = launch()
        a.findViewById<android.view.View>(IncomingCallActivity.ID_ANSWER).performClick()
        a.onRequestPermissionsResult(
            IncomingCallActivity.REQ_MIC, arrayOf(Manifest.permission.RECORD_AUDIO),
            intArrayOf(PackageManager.PERMISSION_DENIED),
        )
        shadowOf(Looper.getMainLooper()).idle()
        val q = queued()
        assertEquals(listOf(CallOutcome.DONE), q.map { it.outcome })
        assertEquals(listOf("voice failed: microphone permission"), q.first().outcomeNotes)
        assertTrue("service never started", started.isEmpty())
        assertTrue("the notes still reach the user", shadowOf(nm).allNotifications.isNotEmpty())
        assertTrue(a.isFinishing)
    }

    @Test fun `a voice start that throws reports done with a voice-failed note + the notice`() {
        IncomingCallActivity.voiceStarter = { _, _ -> throw IllegalStateException("no mic") }
        ring()
        launch(CallRinger.activityIntent(context, payload, CallRinger.ACTION_ANSWER))
        assertEquals(listOf(CallOutcome.ANSWERED, CallOutcome.DONE), queued().map { it.outcome })
        assertEquals(listOf("voice failed: no mic"), queued().last().outcomeNotes)
        val notice = shadowOf(nm).getNotification(NotifIds.callResult(payload.callId))
        assertEquals("Couldn't start the call — here's what it was about", notice.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        assertNull(CallRinger.activeCallId(context))
    }

    @Test fun `Decline settles DECLINED, no service, no notice`() {
        ring()
        val a = launch()
        a.findViewById<android.view.View>(IncomingCallActivity.ID_DECLINE).performClick()
        assertEquals(listOf(CallOutcome.DECLINED), queued().map { it.outcome })
        assertTrue(started.isEmpty())
        assertTrue(a.isFinishing)
        assertEquals(0, shadowOf(nm).size())
    }

    @Test fun `Snooze 10 settles SNOOZED(10)`() {
        ring()
        val a = launch()
        a.findViewById<android.view.View>(IncomingCallActivity.ID_SNOOZE).performClick()
        val item = queued().single()
        assertEquals(CallOutcome.SNOOZED, item.outcome)
        assertEquals(10, item.snoozeMin)
        assertTrue(a.isFinishing)
    }

    @Test fun `unanswered for 30 s → MISSED once (the alarm firing after it is a no-op)`() {
        ring()
        val a = launch()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        assertEquals(listOf(CallOutcome.MISSED), queued().map { it.outcome })
        assertTrue(a.isFinishing)
        assertNotNull(shadowOf(nm).getNotification(NotifIds.callResult(payload.callId)))
        MissedCallReceiver().onReceive(context, MissedCallReceiver.intent(context, MissedCallReceiver.ACTION_MISSED, payload.callId))
        assertEquals(1, queued().size)
    }

    @Test fun `a screen opened after the call settled just closes`() {
        ring()
        CallRinger.settle(context, payload.callId, CallOutcome.MISSED)
        val a = launch()
        assertTrue(a.isFinishing)
        assertTrue(started.isEmpty())
        assertEquals(1, queued().size)
    }

    @Test fun `an intent without a valid payload closes`() {
        val a = launch(Intent(context, IncomingCallActivity::class.java))
        assertTrue(a.isFinishing)
    }
}
