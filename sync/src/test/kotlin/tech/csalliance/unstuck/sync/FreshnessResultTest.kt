package tech.csalliance.unstuck.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
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
        onRebuild: () -> Unit = {},
    ) = FreshnessOwner(
        scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        currentUserId = user,
        runFullHydrate = { true },
        runCatchUp = { u, sweep -> onCatchUp(); catchUp(u, sweep) },
        needsFullHydrate = { false },
        rebuildSubscriptions = { onRebuild() },
        log = {},
    )

    /** The first pull of a launch is a full hydrate (A11, andfix/sync); these
     *  cases are about the catch-up pull after it, so each warms the owner up. */
    private suspend fun FreshnessOwner.warm(): FreshnessOwner { assertTrue(requestAndWait(FreshnessTrigger.WORKER)); return this }

    @Test fun `a clean pull reports success`() = runTest {
        assertTrue(owner().requestAndWait(FreshnessTrigger.WORKER))
    }

    @Test fun `a pull that threw reports failure`() = runTest {
        assertFalse(owner(catchUp = { _, _ -> throw java.io.IOException("offline") }).warm().requestAndWait(FreshnessTrigger.WORKER))
    }

    @Test fun `a pull that could not run or lost a table reports failure`() = runTest {
        assertFalse(owner(catchUp = { _, _ -> null }).warm().requestAndWait(FreshnessTrigger.WORKER))
        assertFalse(owner(catchUp = { _, _ -> CatchUpOutcome(failed = setOf("tasks")) }).warm().requestAndWait(FreshnessTrigger.WORKER))
    }

    @Test fun `nobody signed in is not a successful pull`() = runTest {
        var pulls = 0
        assertFalse(owner(user = { null }, onCatchUp = { pulls++ }).requestAndWait(FreshnessTrigger.WORKER))
        assertEquals(0, pulls)
    }

    @Test fun `the pull waits for the session to be established, then runs`() = runTest {
        var pulls = 0
        val o = owner(user = { delay(1_200); "u1" }, onCatchUp = { pulls++ }).warm()
        assertTrue(o.requestAndWait(FreshnessTrigger.NETWORK))
        assertEquals(1, pulls)
    }

    // Second pass (R1): the DEAF rule rebuilt the realtime mirror after any pull that
    // caught remote edits the socket "missed". In the background the socket is closed
    // on purpose, so every worker pull that found a web edit opened the websocket from
    // the background — and nothing closed it again until the next foreground.
    private val caughtUpRemoteEdits: suspend (String, Boolean) -> CatchUpOutcome =
        { _, _ -> CatchUpOutcome(applied = mapOf("tasks" to 2), provenMissed = 2) }

    @Test fun `a background pull that catches remote edits never rebuilds the realtime mirror`() = runTest {
        var rebuilds = 0
        val o = owner(catchUp = caughtUpRemoteEdits, onRebuild = { rebuilds++ })
        o.onVisible(); runCurrent()
        o.onHidden()   // Home: pauseRealtime closed the socket
        rebuilds = 0
        val deafBefore = o.state.value.deafConfirmed
        assertTrue(o.requestAndWait(FreshnessTrigger.WORKER))
        assertEquals(0, rebuilds)
        assertEquals("a closed socket is not a deaf one", deafBefore, o.state.value.deafConfirmed)
    }

    @Test fun `on screen the same evidence still rebuilds it`() = runTest {
        var rebuilds = 0
        val o = owner(catchUp = caughtUpRemoteEdits, onRebuild = { rebuilds++ })
        o.onVisible(); runCurrent()
        val before = rebuilds
        o.requestAndWait(FreshnessTrigger.FLOOR)
        assertEquals(before + 1, rebuilds)
        o.onHidden()
    }

    @Test fun `a sign-out on screen leaves the DEAF rule on for the next account`() = runTest {
        var rebuilds = 0
        val o = owner(catchUp = caughtUpRemoteEdits, onRebuild = { rebuilds++ })
        o.onVisible(); runCurrent()
        o.reset()
        o.warm()   // reset() forgets the hydrate too: the next account starts with one
        val before = rebuilds
        o.requestAndWait(FreshnessTrigger.NETWORK)
        assertEquals(before + 1, rebuilds)
        o.onHidden()
    }
}
