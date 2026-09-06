package tech.csalliance.unstuck.calls

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.core.logic.CallOutcome
import tech.csalliance.unstuck.core.logic.CallOutcomeQueue
import tech.csalliance.unstuck.core.logic.PendingOutcome
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
        val n = CallOutcomeStore.flush(app, nowMs = { 10 }) { sent += it; Result.success(Unit) }
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
        CallOutcomeStore.flush(app, nowMs = { 1_000 + CallOutcomeQueue.backoffMs(1) }) { again += it.callId; Result.success(Unit) }
        assertEquals(listOf("c1", "c2"), again)
        assertTrue(CallOutcomeStore.load(app).isEmpty)
    }

    @Test fun `a permanent refusal drops that item and the drain continues`() = runTest {
        CallOutcomeStore.enqueue(app, "dead", CallOutcome.DONE, nowMs = 1)
        CallOutcomeStore.enqueue(app, "c2", CallOutcome.SNOOZED, snoozeMin = 10, nowMs = 2)
        val sent = mutableListOf<String>()
        val n = CallOutcomeStore.flush(app, nowMs = { 10 }) { item ->
            if (item.callId == "dead") Result.failure(CallOutcomeRejected(404, "not_found")) else { sent += item.callId; Result.success(Unit) }
        }
        assertEquals(1, n)
        assertEquals(listOf("c2"), sent)
        assertTrue(CallOutcomeStore.load(app).isEmpty)
    }

    @Test fun `sign-out clears the queue on disk`() {
        CallOutcomeStore.enqueue(app, "c1", CallOutcome.ANSWERED, nowMs = 1)
        CallOutcomeStore.clear(app)
        assertTrue(CallOutcomeStore.load(app).isEmpty)
        assertNull(app.getSharedPreferences(CallOutcomeStore.PREFS, Context.MODE_PRIVATE).getString(CallOutcomeStore.KEY_QUEUE, null))
    }
}
