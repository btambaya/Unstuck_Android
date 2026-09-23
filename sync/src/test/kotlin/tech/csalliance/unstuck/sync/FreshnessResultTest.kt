package tech.csalliance.unstuck.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The worker's pull reports its outcome (Android audit 2026-09-23, A2): runOnce
// swallowed every failure, so SyncWorker's Result.retry() could never fire; and
// in a backgrounded process the pull must establish the session (the live
// status reads null after supabase-kt's ON_STOP reset) instead of skipping.
@OptIn(ExperimentalCoroutinesApi::class)
class FreshnessResultTest {

    private fun kotlinx.coroutines.test.TestScope.owner(
        user: suspend () -> String? = { "u1" },
        catchUp: suspend (String, Boolean) -> CatchUpOutcome? = { _, _ -> CatchUpOutcome() },
        onCatchUp: () -> Unit = {},
    ) = FreshnessOwner(
        scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        currentUserId = user,
        runFullHydrate = { true },
        runCatchUp = { u, sweep -> onCatchUp(); catchUp(u, sweep) },
        needsFullHydrate = { false },
        rebuildSubscriptions = {},
        log = {},
    )

    @Test fun `a clean pull reports success`() = runTest {
        assertTrue(owner().requestAndWait(FreshnessTrigger.WORKER))
    }

    @Test fun `a pull that threw reports failure`() = runTest {
        assertFalse(owner(catchUp = { _, _ -> throw java.io.IOException("offline") }).requestAndWait(FreshnessTrigger.WORKER))
    }

    @Test fun `a pull that could not run or lost a table reports failure`() = runTest {
        assertFalse(owner(catchUp = { _, _ -> null }).requestAndWait(FreshnessTrigger.WORKER))
        assertFalse(owner(catchUp = { _, _ -> CatchUpOutcome(failed = setOf("tasks")) }).requestAndWait(FreshnessTrigger.WORKER))
    }

    @Test fun `nobody signed in is not a successful pull`() = runTest {
        var pulls = 0
        assertFalse(owner(user = { null }, onCatchUp = { pulls++ }).requestAndWait(FreshnessTrigger.WORKER))
        assertEquals(0, pulls)
    }

    @Test fun `the pull waits for the session to be established, then runs`() = runTest {
        var pulls = 0
        val o = owner(user = { delay(1_200); "u1" }, onCatchUp = { pulls++ })
        assertTrue(o.requestAndWait(FreshnessTrigger.NETWORK))
        assertEquals(1, pulls)
    }
}
