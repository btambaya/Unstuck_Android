package tech.csalliance.unstuck.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// Guards the pause/resume race fix (the live-sync robustness bug). RealtimeLifecycle
// is pure orchestration, so we drive it with counting fakes and assert the contract:
//  - resume() ALWAYS hydrates, even when already subscribed
//  - subscribe() is idempotent (never double-subscribes)
//  - a quick background→foreground settles subscribed+refreshed
//  - a quick foreground→background settles unsubscribed
//  - a subscribe that no-ops on a null user must NOT leave subscribed=true, and a
//    later real sign-in must actually subscribe (BUG 1 — the flag-vs-reality fix)
@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeLifecycleTest {

    private class Fakes(
        // Models "is a user present". When false the injected subscribe no-ops (like
        // the real `auth.currentUserId?.let { ... } ?: false`) and returns false.
        val userPresent: AtomicBoolean = AtomicBoolean(true),
    ) {
        val hydrate = AtomicInteger(0)
        val subscribe = AtomicInteger(0)   // counts REAL subscribes only
        val unsubscribe = AtomicInteger(0)
    }

    private fun lifecycle(scope: CoroutineScope, f: Fakes) = RealtimeLifecycle(
        scope = scope,
        hydrate = { f.hydrate.incrementAndGet() },
        // Return true only when a REAL subscribe happened (a user exists), mirroring
        // SyncCoordinator's `auth.currentUserId?.let { doSubscribeRealtime(it); true } ?: false`.
        subscribe = { if (f.userPresent.get()) { f.subscribe.incrementAndGet(); true } else false },
        unsubscribe = { f.unsubscribe.incrementAndGet() },
    )

    @Test fun resume_alwaysHydrates_evenWhenAlreadySubscribed() = runTest {
        val f = Fakes()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val lc = lifecycle(scope, f)

        lc.ensureSubscribed()            // subscribe #1
        assertTrue(lc.isSubscribed)
        assertEquals(1, f.subscribe.get())

        lc.resume()                      // must hydrate, but NOT re-subscribe
        advanceUntilIdle()
        assertEquals("resume must hydrate every time", 1, f.hydrate.get())
        assertEquals("subscribe is idempotent — no double-subscribe", 1, f.subscribe.get())
        assertTrue(lc.isSubscribed)
    }

    @Test fun resume_subscribesWhenNotYetSubscribed() = runTest {
        val f = Fakes()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val lc = lifecycle(scope, f)

        lc.resume()
        advanceUntilIdle()
        assertEquals(1, f.hydrate.get())
        assertEquals(1, f.subscribe.get())
        assertTrue(lc.isSubscribed)
    }

    @Test fun quickBackgroundThenForeground_settlesSubscribedAndRefreshed() = runTest {
        val f = Fakes()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val lc = lifecycle(scope, f)

        lc.ensureSubscribed()
        // Rapid onStop → onStart: resume() cancels the still-pending pause().
        lc.pause()
        lc.resume()
        advanceUntilIdle()

        assertTrue("must end SUBSCRIBED after a quick bg→fg", lc.isSubscribed)
        assertEquals("the cancelled pause must not have torn down", 0, f.unsubscribe.get())
        assertTrue("resume must have refreshed", f.hydrate.get() >= 1)
    }

    @Test fun quickForegroundThenBackground_settlesUnsubscribed() = runTest {
        val f = Fakes()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val lc = lifecycle(scope, f)

        lc.ensureSubscribed()
        // Rapid onStart → onStop: pause() cancels the still-pending resume().
        lc.resume()
        lc.pause()
        advanceUntilIdle()

        assertFalse("must end UNSUBSCRIBED after a quick fg→bg", lc.isSubscribed)
        assertEquals(1, f.unsubscribe.get())
    }

    @Test fun ensureSubscribed_isIdempotent() = runTest {
        val f = Fakes()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val lc = lifecycle(scope, f)

        lc.ensureSubscribed()
        lc.ensureSubscribed()
        lc.resume()
        advanceUntilIdle()

        assertEquals("subscribe must run at most once while subscribed", 1, f.subscribe.get())
        assertTrue(lc.isSubscribed)
    }

    @Test fun forceUnsubscribe_tearsDownAndAllowsReSubscribe() = runTest {
        val f = Fakes()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val lc = lifecycle(scope, f)

        lc.ensureSubscribed()
        lc.forceUnsubscribe()
        assertFalse(lc.isSubscribed)
        assertEquals(1, f.unsubscribe.get())

        lc.resume()                      // a fresh sign-in / foreground re-subscribes
        advanceUntilIdle()
        assertTrue(lc.isSubscribed)
        assertEquals(2, f.subscribe.get())
    }

    // --- BUG 1: flag-vs-reality divergence (no realtime for the whole session). ---

    @Test fun noOpSubscribeOnNullUser_doesNotLeaveSubscribed_thenSignInReallySubscribes() = runTest {
        // Signed out: the injected subscribe no-ops (currentUserId == null → returns false).
        val f = Fakes(userPresent = AtomicBoolean(false))
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val lc = lifecycle(scope, f)

        // resume() wins the race vs the async session restore (no user yet). It must
        // still hydrate, but a no-op subscribe must NOT flip the flag to subscribed —
        // otherwise the later SIGNED_IN skips the real subscribe (the whole-session-
        // no-realtime bug).
        lc.resume()
        advanceUntilIdle()
        assertFalse("a no-op subscribe (null user) must NOT leave subscribed=true", lc.isSubscribed)
        assertEquals("no REAL subscribe happened while signed out", 0, f.subscribe.get())
        assertTrue("resume still hydrates every time", f.hydrate.get() >= 1)

        // The real session now arrives (SIGNED_IN): a user exists. resubscribe() MUST
        // establish a real live subscription regardless of the earlier flag state.
        f.userPresent.set(true)
        lc.resubscribe()
        advanceUntilIdle()
        assertTrue("sign-in must actually subscribe now a user exists", lc.isSubscribed)
        assertEquals("exactly one real subscribe on sign-in", 1, f.subscribe.get())
    }

    @Test fun resubscribe_forcesFreshSubscribe_evenWhenAlreadySubscribed() = runTest {
        val f = Fakes()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val lc = lifecycle(scope, f)

        lc.ensureSubscribed()
        assertTrue(lc.isSubscribed)
        assertEquals(1, f.subscribe.get())

        // Sign-in path forces a real re-subscribe even if the flag was already set,
        // so a stale/ghost subscribed==true can never leave the session without a mirror.
        lc.resubscribe()
        advanceUntilIdle()
        assertTrue(lc.isSubscribed)
        assertEquals("resubscribe re-subscribes even when the flag was already set", 2, f.subscribe.get())
    }
}
