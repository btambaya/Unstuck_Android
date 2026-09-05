package tech.csalliance.unstuck.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

// Flush-on-enqueue (the CRITICAL Android sync finding): every local write must
// drain the outbox ~1.5 s later, a burst collapses to one drain, a write during a
// drain triggers exactly one follow-up drain, and sign-out cancels an armed one.
@OptIn(ExperimentalCoroutinesApi::class)
class EnqueueFlushSchedulerTest {

    @Test fun `a burst of enqueues collapses to ONE drain, debounceMs after the last`() = runTest {
        val runs = AtomicInteger(0)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val s = EnqueueFlushScheduler(scope, 1_500) { runs.incrementAndGet() }
        s.schedule()
        advanceTimeBy(1_000)
        s.schedule()                       // re-arms: the clock restarts from here
        advanceTimeBy(1_000)
        s.schedule()
        advanceTimeBy(1_499)
        assertEquals("nothing drains before the quiet window elapses", 0, runs.get())
        advanceTimeBy(2)
        assertEquals("exactly one drain after the last enqueue", 1, runs.get())
        advanceUntilIdle()
        assertEquals(1, runs.get())
    }

    @Test fun `an enqueue DURING a drain does not cancel it and triggers exactly one follow-up drain`() = runTest {
        val runs = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val s = EnqueueFlushScheduler(scope, 1_500) {
            runs.incrementAndGet()
            if (runs.get() == 1) gate.await()   // the first drain is slow (network)
        }
        s.schedule()
        advanceTimeBy(1_501)
        assertEquals(1, runs.get())           // drain #1 in flight, blocked on the gate
        s.schedule(); s.schedule()            // two writes land mid-drain
        advanceTimeBy(5_000)
        assertEquals("the running drain is never cancelled/restarted", 1, runs.get())
        gate.complete(Unit)
        advanceTimeBy(1_499)
        assertEquals("the follow-up is debounced too", 1, runs.get())
        advanceTimeBy(2)
        assertEquals("ONE follow-up drain for the writes queued mid-drain", 2, runs.get())
        advanceUntilIdle()
        assertEquals(2, runs.get())
    }

    @Test fun `cancel drops an armed drain (sign-out) and a later enqueue re-arms`() = runTest {
        val runs = AtomicInteger(0)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val s = EnqueueFlushScheduler(scope, 1_500) { runs.incrementAndGet() }
        s.schedule()
        advanceTimeBy(1_000)
        s.cancel()
        advanceTimeBy(5_000)
        assertEquals("a cancelled arm never fires", 0, runs.get())
        s.schedule()
        advanceTimeBy(1_501)
        assertEquals(1, runs.get())
    }

    @Test fun `a failing drain is reported, never crashes the scope, and the next enqueue still drains`() = runTest {
        val runs = AtomicInteger(0)
        val errors = AtomicInteger(0)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val s = EnqueueFlushScheduler(scope, 1_500, onError = { errors.incrementAndGet() }) {
            if (runs.incrementAndGet() == 1) throw IllegalStateException("offline")
        }
        s.schedule()
        advanceTimeBy(1_501)
        assertEquals(1, runs.get()); assertEquals(1, errors.get())
        s.schedule()
        advanceTimeBy(1_501)
        assertEquals(2, runs.get()); assertEquals(1, errors.get())
    }
}
