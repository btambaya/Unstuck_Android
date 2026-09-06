package tech.csalliance.unstuck.surface

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// "Unstuck calls you" on Android, C0-android: the FCM ring contract
// (send-call → data-only, kind=call) parses tolerantly like iOS
// IncomingCallPayload, and posts a HIGH-priority, actionable notification with
// the notes preview + the unstuck://call/<id> deep link.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PushTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val contract = mapOf(
        "kind" to "call",
        "callId" to "0b8a7e60-1111-4222-8333-444455556666",
        "taskId" to "t-1",
        "title" to "speak to James",
        "notes" to """["A","  B ",""]""",
        "scheduledAt" to "2026-09-09T13:45:00Z",
        "deepLink" to "unstuck://call/0b8a7e60-1111-4222-8333-444455556666",
        // sendFcmPush also folds a fallback body in; extras from the VoIP payload ride along.
        "body" to "Unstuck is calling about speak to James\n• A\n• B",
        "label" to "speak to James",
        "name" to "Ahmad",
    )

    @Test fun `parses the C0-android contract (notes as a JSON array, blanks dropped)`() {
        val p = CallPush.fromData(contract)
        assertNotNull(p); p!!
        assertEquals("0b8a7e60-1111-4222-8333-444455556666", p.callId)
        assertEquals("speak to James", p.title)
        assertEquals(listOf("A", "B"), p.notes)
        assertEquals("t-1", p.taskId)
        assertEquals("2026-09-09T13:45:00Z", p.scheduledAt)
        assertEquals("unstuck://call/0b8a7e60-1111-4222-8333-444455556666", p.deepLink)
    }

    @Test fun `optional keys absent → empty notes, null ids, deep link derived from callId`() {
        val p = CallPush.fromData(mapOf("kind" to "call", "callId" to "c-2", "title" to "the 3 o'clock"))
        assertNotNull(p); p!!
        assertEquals(emptyList<String>(), p.notes)
        assertNull(p.taskId)
        assertNull(p.scheduledAt)
        assertEquals("unstuck://call/c-2", p.deepLink)
    }

    @Test fun `callId + a non-empty title are REQUIRED (iOS parity) and other kinds are not calls`() {
        assertNull(CallPush.fromData(mapOf("kind" to "call", "callId" to " ", "title" to "x")))
        assertNull(CallPush.fromData(mapOf("kind" to "call", "callId" to "c", "title" to "  ")))
        assertNull(CallPush.fromData(mapOf("kind" to "morning_brief", "callId" to "c", "title" to "x")))
        assertNull(CallPush.fromData(mapOf("callId" to "c", "title" to "x")))
        assertEquals("from label", CallPush.fromData(mapOf("kind" to "call", "callId" to "c", "label" to "from label"))?.title)
    }

    @Test fun `parseNotes keeps a bare string as one note and tolerates garbage JSON`() {
        assertEquals(listOf("remind me about A"), CallPush.parseNotes("remind me about A"))
        assertEquals(listOf("[not json"), CallPush.parseNotes("[not json"))
        assertEquals(emptyList<String>(), CallPush.parseNotes(null))
        assertEquals(emptyList<String>(), CallPush.parseNotes("[]"))
        assertEquals(listOf("A", "B"), CallPush.parseNotes("""["A", " ", "B"]"""))
    }

    @Test fun `notification copy previews three notes then counts the rest`() {
        val p = CallPush(callId = "c", title = "speak to James", notes = listOf("A", "B", "C", "D", "E"), taskId = null, scheduledAt = null, deepLink = "unstuck://call/c")
        assertEquals("Unstuck is calling", p.notificationTitle)
        assertEquals("About speak to James", p.notificationText)
        assertEquals("About speak to James\n• A\n• B\n• C\n… and 2 more", p.notificationBigText)
        val none = p.copy(notes = emptyList())
        assertEquals("About speak to James", none.notificationBigText)
    }

    @Test fun `notifId is stable per call and clear of the other families`() {
        val a = CallPush.fromData(contract)!!
        assertEquals(a.notifId, CallPush.fromData(contract)!!.notifId)
        assertTrue(a.notifId in CallPush.NOTIF_BASE until (CallPush.NOTIF_BASE + 0x10000))
        val b = a.copy(callId = "other-call")
        assertTrue(a.notifId != b.notifId)
    }

    @Test fun `post renders a HIGH-priority call notification with the notes + deep link`() {
        NotificationChannels.ensureAll(context)
        val p = CallPush.fromData(contract)!!
        CallRing.post(context, p)

        val mgr = context.getSystemService(NotificationManager::class.java)
        val shadow = shadowOf(mgr)
        assertEquals(1, shadow.size())
        val n: Notification = shadow.getNotification(p.notifId)
        assertNotNull("posted under the per-call id", n)
        assertEquals(NotificationChannels.REMINDERS, n.channelId)
        assertEquals(NotificationCompat.PRIORITY_HIGH, n.priority)
        assertEquals(Notification.CATEGORY_CALL, n.category)
        assertEquals("Unstuck is calling", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("About speak to James", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("About speak to James\n• A\n• B", n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertNotNull("tap is actionable", n.contentIntent)
        val launched = shadowOf(n.contentIntent).savedIntent
        assertEquals(p.deepLink, launched.data.toString())
        assertEquals(NotificationChannels.GROUP, n.group)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertNotNull("lock screen shows the private version", n.publicVersion)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        // The channel it rides on is heads-up capable (HIGH) with sound — a ring, not a whisper.
        val channel = mgr.getNotificationChannel(NotificationChannels.REMINDERS)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNotNull(channel.sound)
    }

    @Test fun `a retry of the same ring updates in place while two calls coexist`() {
        NotificationChannels.ensureAll(context)
        val a = CallPush.fromData(contract)!!
        CallRing.post(context, a)
        CallRing.post(context, a)
        val shadow = shadowOf(context.getSystemService(NotificationManager::class.java))
        assertEquals(1, shadow.size())
        CallRing.post(context, a.copy(callId = "second", deepLink = "unstuck://call/second"))
        assertEquals(2, shadow.size())
    }

    @Test fun `the ring is logged to the in-app notification center under kind=call`() {
        NotificationChannels.ensureAll(context)
        NotificationLog.clear(context)
        CallRing.post(context, CallPush.fromData(contract)!!)
        val entry = NotificationLog.items.value.first()
        assertEquals("call", entry.kind)
        assertEquals("Unstuck is calling", entry.title)
        assertEquals("unstuck://call/0b8a7e60-1111-4222-8333-444455556666", entry.deepLink)
    }
}
