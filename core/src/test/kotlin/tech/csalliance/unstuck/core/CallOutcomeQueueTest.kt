package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.CallOutcome
import tech.csalliance.unstuck.core.logic.CallOutcomeQueue
import tech.csalliance.unstuck.core.logic.PendingOutcome

// The persisted, ordered, retried outcome queue — the iOS
// CallsOutcomeReporterTests rules without the network: in-order flush,
// backoff on transient failures (never dropped), a permanent refusal drops
// that item and the drain continues, sign-out discards, JSON round-trip.
class CallOutcomeQueueTest {
    private val t0 = 1_800_000_000_000L

    /** Drive a queue the way CallOutcomeStore.flush does, with a fake sender:
     *  `null` = sent, `false` = transient failure, `true` = permanent. */
    private fun drain(q: CallOutcomeQueue, nowMs: Long, send: (PendingOutcome) -> Boolean?): List<PendingOutcome> {
        val sent = ArrayList<PendingOutcome>()
        while (true) {
            val head = q.next(nowMs) ?: break
            when (send(head)) {
                null -> { sent.add(head); q.markSent(head.callId) }
                false -> { q.markFailed(head.callId, permanent = false, nowMs = nowMs); break }
                true -> q.markFailed(head.callId, permanent = true, nowMs = nowMs)
            }
        }
        return sent
    }

    @Test fun `queued items persist and flush in order`() {
        val q = CallOutcomeQueue()
        q.enqueue("c1", CallOutcome.ANSWERED, nowMs = t0)
        q.enqueue("c1", CallOutcome.DONE, outcomeNotes = listOf("voice failed: x"), nowMs = t0 + 1)
        assertEquals(2, q.size)
        val relaunch = CallOutcomeQueue.fromJson(q.toJson())
        assertEquals(listOf(CallOutcome.ANSWERED, CallOutcome.DONE), relaunch.items.map { it.outcome })
        assertEquals(listOf("voice failed: x"), relaunch.items[1].outcomeNotes)
        val sent = drain(relaunch, t0 + 5) { null }
        assertEquals(listOf(CallOutcome.ANSWERED, CallOutcome.DONE), sent.map { it.outcome })
        assertTrue(relaunch.isEmpty)
        assertEquals("[]", relaunch.toJson())
    }

    @Test fun `next is the head only and never reorders`() {
        val q = CallOutcomeQueue()
        q.enqueue("c1", CallOutcome.MISSED, nowMs = t0)
        q.enqueue("c2", CallOutcome.SNOOZED, snoozeMin = 10, nowMs = t0)
        assertEquals("c1", q.next(t0)!!.callId)
        q.markFailed("c1", permanent = false, nowMs = t0)
        assertNull("c2 waits for c1 — never reordered", q.next(t0))
        assertEquals(t0 + 2_000, q.nextAttemptAt())
        assertEquals("c1", q.next(t0 + 2_000)!!.callId)
        assertEquals(1, q.next(t0 + 2_000)!!.attempts)
    }

    @Test fun `transient failures back off 2s 5s 15s … and are never dropped`() {
        val q = CallOutcomeQueue()
        q.enqueue("c1", CallOutcome.DONE, nowMs = t0)
        val delays = ArrayList<Long>()
        var now = t0
        repeat(7) {
            val item = q.markFailed("c1", permanent = false, nowMs = now)!!
            delays.add(item.notBeforeMs - now)
            now = item.notBeforeMs
        }
        assertEquals(listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L, 120_000L, 120_000L), delays)
        assertEquals(7, q.items[0].attempts)
        assertEquals(1, q.size)
        assertEquals(listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L, 120_000L), CallOutcomeQueue.BACKOFF_MS)
        assertEquals(2_000L, CallOutcomeQueue.backoffMs(0))
        assertEquals(120_000L, CallOutcomeQueue.backoffMs(99))
    }

    @Test fun `a permanent rejection drops that item and the drain continues`() {
        val q = CallOutcomeQueue()
        q.enqueue("dead", CallOutcome.ANSWERED, nowMs = t0)
        q.enqueue("c2", CallOutcome.MISSED, nowMs = t0)
        q.enqueue("c3", CallOutcome.SNOOZED, snoozeMin = 20, nowMs = t0)
        var attempts = 0
        val sent = drain(q, t0) { attempts++; if (it.callId == "dead") true else null }
        assertEquals(listOf("c2", "c3"), sent.map { it.callId })
        assertEquals("no retries for a permanent refusal", 3, attempts)
        assertEquals(20, sent.last().snoozeMin)
        assertTrue(q.isEmpty)
    }

    @Test fun `a transient failure still retries after a dropped item`() {
        val q = CallOutcomeQueue()
        q.enqueue("dead", CallOutcome.DONE, nowMs = t0)
        q.enqueue("c2", CallOutcome.DONE, nowMs = t0)
        var c2Failures = 1
        val first = drain(q, t0) { if (it.callId == "dead") true else if (c2Failures-- > 0) false else null }
        assertTrue(first.isEmpty())
        assertEquals(listOf("c2"), q.items.map { it.callId })
        assertEquals(1, q.items[0].attempts)
        val second = drain(q, t0 + 2_000) { null }
        assertEquals(listOf("c2"), second.map { it.callId })
        assertTrue(q.isEmpty)
    }

    @Test fun `markSent and markFailed by callId prefer the head, then the first match`() {
        val q = CallOutcomeQueue()
        q.enqueue("a", CallOutcome.ANSWERED, nowMs = t0)
        q.enqueue("b", CallOutcome.MISSED, nowMs = t0)
        q.enqueue("a", CallOutcome.DONE, nowMs = t0)
        assertTrue(q.markSent("a"))
        assertEquals(listOf(CallOutcome.MISSED, CallOutcome.DONE), q.items.map { it.outcome })
        assertTrue("a straggler for a non-head id is removed too", q.markSent("a"))
        assertEquals(listOf("b"), q.items.map { it.callId })
        assertFalse(q.markSent("zzz"))
        assertNull(q.markFailed("zzz", permanent = false, nowMs = t0))
    }

    @Test fun `clear forgets everything — nothing carries into the next account`() {
        val q = CallOutcomeQueue()
        q.enqueue("c1", CallOutcome.ANSWERED, nowMs = t0)
        q.enqueue("c1", CallOutcome.DONE, nowMs = t0)
        q.clear()
        assertTrue(q.isEmpty)
        assertNull(q.next(t0))
        assertTrue(CallOutcomeQueue.fromJson(q.toJson()).isEmpty)
        q.enqueue("new", CallOutcome.MISSED, nowMs = t0)
        assertEquals(listOf("new"), drain(q, t0) { null }.map { it.callId })
    }

    @Test fun `fromJson is tolerant`() {
        assertTrue(CallOutcomeQueue.fromJson(null).isEmpty)
        assertTrue(CallOutcomeQueue.fromJson("").isEmpty)
        assertTrue(CallOutcomeQueue.fromJson("{bad").isEmpty)
        assertTrue(CallOutcomeQueue.fromJson("""[{"callId":"x"}]""").isEmpty)
        val q = CallOutcomeQueue.fromJson("""[{"callId":"c","outcome":"snoozed","snoozeMin":15,"at":1,"attempts":2,"future":true}]""")
        assertEquals(1, q.size)
        assertEquals(CallOutcome.SNOOZED, q.items[0].outcome)
        assertEquals(15, q.items[0].snoozeMin)
        assertEquals(2, q.items[0].attempts)
        assertEquals(0L, q.items[0].notBeforeMs)
    }

    @Test fun `permanent status codes are the 4xx family minus 401 408 429`() {
        assertTrue(CallOutcomeQueue.isPermanentStatus(400))
        assertTrue(CallOutcomeQueue.isPermanentStatus(404))
        assertTrue(CallOutcomeQueue.isPermanentStatus(422))
        assertFalse(CallOutcomeQueue.isPermanentStatus(401))
        assertFalse(CallOutcomeQueue.isPermanentStatus(408))
        assertFalse(CallOutcomeQueue.isPermanentStatus(429))
        assertFalse(CallOutcomeQueue.isPermanentStatus(500))
        assertFalse(CallOutcomeQueue.isPermanentStatus(200))
    }
}
