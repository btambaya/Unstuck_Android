package tech.csalliance.unstuck.ui.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.csalliance.unstuck.core.model.CallRequest
import tech.csalliance.unstuck.core.time.Time
import tech.csalliance.unstuck.surface.NotificationLog
import tech.csalliance.unstuck.sync.NotificationsClient

/**
 * The bell's "Unstuck called you about X" cards — ported from iOS
 * CallsMirrorTests (build 72; the web's useNotificationQueue / mergeRecent).
 */
class NotificationQueueCardsTest {
    private fun call(
        id: String, label: String, status: String = "scheduled", taskId: String? = null,
        callAt: String = "2026-09-20T09:00:00.000Z", updatedAt: String, notes: List<String> = emptyList(),
    ) = CallRequest(id = id, taskId = taskId, callAt = callAt, label = label, notes = notes, status = status, updatedAt = updatedAt)

    private fun card(id: String, body: String, createdAt: String, title: String = "Unstuck is calling") =
        NotificationsClient.QueueCard(id = id, moment = "call", title = title, body = body, createdAt = createdAt)

    @Test fun `a queue card becomes a call entry with the call's notes`() {
        val calls = listOf(
            call("c1", "speak to James", status = "missed", taskId = "t1", callAt = "2026-09-20T14:45:00.000Z",
                updatedAt = "2026-09-20T14:46:00.000Z", notes = listOf("Ask about the invoice", "Confirm Friday")),
            call("c0", "speak to James", status = "done", callAt = "2026-09-01T14:45:00.000Z",
                updatedAt = "2026-09-01T14:46:00.000Z", notes = listOf("an older call, same label")),
        )
        val e = NotificationQueueCards.entry(card("q1", "Unstuck is calling about speak to James", "2026-09-20T14:45:03.000Z"), calls)
        assertEquals("q_q1", e.id)
        assertEquals("call", e.kind)
        assertEquals("Unstuck called you about speak to James", e.title)
        assertEquals("the nearest call with that label, not the older one", "Ask about the invoice\nConfirm Friday", e.body)
        assertEquals("unstuck://task/t1", e.deepLink)
        assertEquals(Time.parseMillis("2026-09-20T14:45:03.000Z"), e.at)
    }

    @Test fun `a card with no matching call keeps its own copy`() {
        val c = card("q2", "Unstuck is calling about the dentist", "2026-09-20T09:00:00.000Z")
        val e = NotificationQueueCards.entry(c, emptyList())
        assertEquals("Unstuck called you about the dentist", e.title)
        assertEquals("Unstuck is calling about the dentist", e.body)
        assertEquals("unstuck://today", e.deepLink)
        // A missed call with no notes says so; a body that isn't a call keeps the card's title.
        val missed = listOf(call("m", "the dentist", status = "missed", updatedAt = "2026-09-20T09:00:30.000Z"))
        assertEquals("You missed it — no notes on this one.", NotificationQueueCards.entry(c, missed).body)
        assertEquals("Custom", NotificationQueueCards.entry(card("q3", "something else", "x", title = "Custom"), emptyList()).title)
        assertEquals("Unstuck called you", NotificationQueueCards.entry(NotificationsClient.QueueCard(id = "q4", body = "odd"), emptyList()).title)
        assertNull(NotificationQueueCards.callLabel("Unstuck is calling about "))
        assertEquals("ring the bank", NotificationQueueCards.callLabel("  Unstuck is calling about ring the bank "))
    }

    @Test fun `mergeRecent is the web rule`() {
        fun entry(id: String, kind: String, title: String, body: String, at: Long) =
            NotificationLog.Entry(id = id, kind = kind, title = title, body = body, deepLink = null, at = at)
        val t = 1_800_000_000_000L
        val local = listOf(
            entry("l1", "call_missed", "I called about speak to James", "Ask", t + 60_000),
            entry("l2", "session_recap", "You did the thing.", "", t),
            entry("l3", "task_reminder", "Same copy", "Same", t - 1_000),
        )
        val queue = listOf(
            entry("q_1", "call", "Unstuck called you about speak to James", "Ask", t + 3_000),
            entry("q_2", "session_recap", "Session wrapped", "x", t + 30_000),
            entry("q_3", "task_reminder", "Same copy", "Same", t + 2_000),
            entry("q_4", "task_reminder", "Same copy", "Same", t + 10 * 60_000),
            entry("q_1", "call", "dup id", "", t),
        )
        assertEquals(
            "newest first; the recap within 5 min and the same-copy card within 5 min collapse; the call card stays (different copy); a duplicate id is dropped; the far-apart same-copy card stays",
            listOf("q_4", "l1", "q_1", "l2", "l3"),
            NotificationQueueCards.mergeRecent(local, queue).map { it.id },
        )
        assertEquals(1, NotificationQueueCards.mergeRecent(emptyList(), listOf(queue[0]), cap = 1).size)
        val many = (0 until 30).map { entry("q_$it", "call", "t$it", "", t + it) }
        assertEquals(NotificationQueueCards.CAP, NotificationQueueCards.mergeRecent(emptyList(), many).size)
    }
}
