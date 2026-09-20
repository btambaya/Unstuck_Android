package tech.csalliance.unstuck.calls

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Notification
import android.app.NotificationManager
import org.robolectric.Shadows.shadowOf
import tech.csalliance.unstuck.core.logic.CallNotificationKind
import tech.csalliance.unstuck.core.logic.CallOutcome
import tech.csalliance.unstuck.core.logic.CallOutcomeQueue
import tech.csalliance.unstuck.core.logic.CallOutcomeReceipt
import tech.csalliance.unstuck.core.logic.IncomingCallPayload
import tech.csalliance.unstuck.core.logic.PendingOutcome
import tech.csalliance.unstuck.surface.NotifIds
import tech.csalliance.unstuck.sync.CallOutcomeRejected

/**
 * The durable call-outcome queue (mirrors iOS CallCoordinatorTests' reporter
 * cases): persisted the moment it's queued, flushed IN ORDER, a transient
 * failure keeps the head (attempts+1, backoff) and stops the drain, a
 * permanent refusal drops that item and the drain continues, sign-out clears.
 * A plain Application context (no UnstuckApp graph): flushAsync is a no-op,
 * so enqueue() only persists — exactly the killed-process case.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CallOutcomeStoreTest {

    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Before fun clean() { CallOutcomeStore.clear(app) }

    @Test fun `enqueue persists before returning and survives a reload`() {
        CallOutcomeStore.enqueue(app, "c1", CallOutcome.ANSWERED, nowMs = 1_000)
        CallOutcomeStore.enqueue(app, "c1", CallOutcome.SNOOZED, snoozeMin = 15, nowMs = 2_000)
        val q = CallOutcomeStore.load(app)
        assertEquals(listOf(CallOutcome.ANSWERED, CallOutcome.SNOOZED), q.items.map { it.outcome })
        assertEquals(15, q.items[1].snoozeMin)
        assertEquals(1_000L, q.items[0].at)
        // A corrupt preference never crashes a launch.
        app.getSharedPreferences(CallOutcomeStore.PREFS, Context.MODE_PRIVATE).edit().putString(CallOutcomeStore.KEY_QUEUE, "{nope").commit()
        assertTrue(CallOutcomeStore.load(app).isEmpty)
    }

    @Test fun `flush sends in order and removes what the server accepted`() = runTest {
        CallOutcomeStore.enqueue(app, "c1", CallOutcome.ANSWERED, nowMs = 1)
        CallOutcomeStore.enqueue(app, "c1", CallOutcome.DONE, outcomeNotes = listOf("voice failed: mic"), nowMs = 2)
        val sent = mutableListOf<PendingOutcome>()
        val n = CallOutcomeStore.flush(app, nowMs = { 10 }) { sent += it; Result.success(CallOutcomeReceipt.EMPTY) }
        assertEquals(2, n)
        assertEquals(listOf(CallOutcome.ANSWERED, CallOutcome.DONE), sent.map { it.outcome })
        assertEquals(listOf("voice failed: mic"), sent[1].outcomeNotes)
        assertTrue(CallOutcomeStore.load(app).isEmpty)
    }

    @Test fun `a transient failure keeps the head with a backoff and stops the drain`() = runTest {
        CallOutcomeStore.enqueue(app, "c1", CallOutcome.MISSED, nowMs = 1)
        CallOutcomeStore.enqueue(app, "c2", CallOutcome.MISSED, nowMs = 2)
        val attempted = mutableListOf<String>()
        val n = CallOutcomeStore.flush(app, nowMs = { 1_000 }) { attempted += it.callId; Result.failure(RuntimeException("offline")) }
        assertEquals(0, n)
        assertEquals("never skips ahead — ordering is the point", listOf("c1"), attempted)
        val q = CallOutcomeStore.load(app)
        assertEquals(2, q.size)
        assertEquals(1, q.items[0].attempts)
        assertEquals(1_000 + CallOutcomeQueue.backoffMs(1), q.items[0].notBeforeMs)
        assertNull("still backing off", q.next(1_500))
        // Once the backoff has elapsed the next flush retries the same head.
        val again = mutableListOf<String>()
        CallOutcomeStore.flush(app, nowMs = { 1_000 + CallOutcomeQueue.backoffMs(1) }) { again += it.callId; Result.success(CallOutcomeReceipt.EMPTY) }
        assertEquals(listOf("c1", "c2"), again)
        assertTrue(CallOutcomeStore.load(app).isEmpty)
    }

    @Test fun `a permanent refusal drops that item and the drain continues`() = runTest {
        CallOutcomeStore.enqueue(app, "dead", CallOutcome.DONE, nowMs = 1)
        CallOutcomeStore.enqueue(app, "c2", CallOutcome.SNOOZED, snoozeMin = 10, nowMs = 2)
        val sent = mutableListOf<String>()
        val n = CallOutcomeStore.flush(app, nowMs = { 10 }) { item ->
            if (item.callId == "dead") Result.failure(CallOutcomeRejected(404, "not_found")) else { sent += item.callId; Result.success(CallOutcomeReceipt.EMPTY) }
        }
        assertEquals(1, n)
        assertEquals(listOf("c2"), sent)
        assertTrue(CallOutcomeStore.load(app).isEmpty)
    }

    /**
     * The enqueue/flush race that silently dropped a report: the Answer tap
     * queues `answered` and starts a flush; three seconds into that POST the
     * model runs snooze_call and queues `snoozed`. The drain used to hold its
     * in-memory copy across the suspending send and write it back on return,
     * wiping the snooze — the server never got snooze_until, the cron never
     * re-rang, and the user who had just been told "I'll call back in ten" never
     * heard from us again.
     */
    @Test fun `an outcome queued while a send is in flight is not overwritten`() = runTest {
        CallOutcomeStore.enqueue(app, "c1", CallOutcome.ANSWERED, nowMs = 1)
        val sent = mutableListOf<CallOutcome>()
        val n = CallOutcomeStore.flush(app, nowMs = { 10 }) { item ->
            sent += item.outcome
            // snooze_call runs while the `answered` POST is still in flight.
            if (item.outcome == CallOutcome.ANSWERED) {
                CallOutcomeStore.enqueue(app, "c1", CallOutcome.SNOOZED, snoozeMin = 10, nowMs = 2)
            }
            Result.success(CallOutcomeReceipt.EMPTY)
        }
        assertEquals(2, n)
        assertEquals(listOf(CallOutcome.ANSWERED, CallOutcome.SNOOZED), sent)
        assertTrue(CallOutcomeStore.load(app).isEmpty)
    }

    @Test fun `an outcome queued during a FAILING send survives behind the head`() = runTest {
        CallOutcomeStore.enqueue(app, "c1", CallOutcome.MISSED, nowMs = 1)
        CallOutcomeStore.flush(app, nowMs = { 1_000 }) {
            CallOutcomeStore.enqueue(app, "c2", CallOutcome.BUSY, nowMs = 2)
            Result.failure(RuntimeException("offline"))
        }
        val q = CallOutcomeStore.load(app)
        assertEquals(listOf(CallOutcome.MISSED, CallOutcome.BUSY), q.items.map { it.outcome })
        assertEquals("the backoff landed on the head, not on a stale copy", 1, q.items[0].attempts)
    }

    // ── the retry-gated "I called about X" (calls build-out 2026-09-20 §5) ──

    private val nm: NotificationManager get() = app.getSystemService(NotificationManager::class.java)
    private val ring = IncomingCallPayload(
        callId = "c1", label = "speak to James", notes = listOf("A", "B"), taskId = "t1", blockId = "b1", taskName = "Call James",
    )
    private fun enqueueMissed(callId: String = "c1") =
        CallOutcomeStore.enqueue(app, callId, CallOutcome.MISSED, nowMs = 1, notify = CallNotificationKind.MISSED, payload = ring.copy(callId = callId).toData())
    private fun notice(callId: String = "c1"): Notification? = shadowOf(nm).getNotification(NotifIds.callResult(callId))

    @Test fun `a miss the server will re-ring posts NO notice - the second, final miss posts it`() = runTest {
        enqueueMissed()
        assertNull("nothing at the 30 s timeout — the flag hasn't arrived", notice())
        val n = CallOutcomeStore.flush(app, nowMs = { 10 }) { Result.success(CallOutcomeReceipt(ok = true, retry = true, status = "snoozed", snoozeUntil = "x")) }
        assertEquals(1, n)
        assertTrue(CallOutcomeStore.load(app).isEmpty)
        assertNull("retry: true ⇒ the server rings again in 5 min ⇒ stay quiet", notice())
        // Five minutes later the re-ring is missed too: the server answers `retry: false`.
        enqueueMissed()
        CallOutcomeStore.flush(app, nowMs = { 20 }) { Result.success(CallOutcomeReceipt(ok = true, retry = false, status = "missed")) }
        val posted = notice()
        assertNotNull(posted)
        assertEquals("I called about speak to James", posted!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("A\nB", posted.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertTrue("Start / Reschedule ride along for a task-anchored call", posted.actions.size == 2)
    }

    @Test fun `a pre-072 server (no retry field) and a permanent refusal both post the notice`() = runTest {
        enqueueMissed()
        CallOutcomeStore.flush(app, nowMs = { 10 }) { Result.success(CallOutcomeReceipt.fromJson("""{"ok":true}""")) }
        assertNotNull("no `retry` ⇒ no re-ring is coming ⇒ tell them", notice())
        nm.cancelAll()
        enqueueMissed("dead")
        val n = CallOutcomeStore.flush(app, nowMs = { 10 }) { Result.failure(CallOutcomeRejected(404, "not_found")) }
        assertEquals(0, n)
        assertTrue(CallOutcomeStore.load(app).isEmpty)
        assertNotNull("the server won't take the report, so nobody will re-ring: the notes still reach the user", notice("dead"))
    }

    @Test fun `a transient failure keeps the notice pending with the report, across a relaunch`() = runTest {
        enqueueMissed()
        CallOutcomeStore.flush(app, nowMs = { 1_000 }) { Result.failure(RuntimeException("offline")) }
        assertNull("not settled yet", notice())
        val q = CallOutcomeStore.load(app)
        assertEquals(CallNotificationKind.MISSED, q.items[0].notify)
        assertEquals(ring, q.items[0].ringPayload)
        // The next foreground (a new process: the queue re-read from disk) settles it.
        CallOutcomeStore.flush(app, nowMs = { 1_000 + CallOutcomeQueue.backoffMs(1) }) { Result.success(CallOutcomeReceipt(retry = false)) }
        assertNotNull(notice())
        assertTrue(CallOutcomeStore.load(app).isEmpty)
    }

    @Test fun `an outcome without a notice never posts one, whatever the server says`() = runTest {
        CallOutcomeStore.enqueue(app, "c1", CallOutcome.DECLINED, nowMs = 1)
        CallOutcomeStore.enqueue(app, "c1", CallOutcome.DONE, nowMs = 2)
        CallOutcomeStore.flush(app, nowMs = { 10 }) { Result.success(CallOutcomeReceipt(retry = false)) }
        assertEquals(0, shadowOf(nm).size())
    }

    @Test fun `sign-out clears the queue on disk`() {
        CallOutcomeStore.enqueue(app, "c1", CallOutcome.ANSWERED, nowMs = 1)
        CallOutcomeStore.clear(app)
        assertTrue(CallOutcomeStore.load(app).isEmpty)
        assertNull(app.getSharedPreferences(CallOutcomeStore.PREFS, Context.MODE_PRIVATE).getString(CallOutcomeStore.KEY_QUEUE, null))
    }
}
