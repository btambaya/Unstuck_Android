package tech.csalliance.unstuck.sync

import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The ONE "is anyone signed in?" answer for background work (Android audit
// 2026-09-23, A1/A2/A3). supabase-kt 3.0.3 leaves sessionStatus at Initializing
// after every ON_STOP, and while a process FCM / WorkManager started is still
// loading the stored session; reading that as "signed out" dropped call rings and
// made background sync a no-op. The rule under test: Initializing is never signed
// out — wait for the SDK when it is loading, load the stored session when nothing
// will, and let the stored session answer when time runs out.
@OptIn(ExperimentalCoroutinesApi::class)
class SessionGateTest {

    private val now = 1_800_000_000_000L

    private fun session(uid: String = "u1", validForMs: Long = 3_600_000, token: String = "a-$uid") = UserSession(
        accessToken = token, refreshToken = "r-$token", expiresIn = validForMs / 1000, tokenType = "bearer",
        user = UserInfo(aud = "authenticated", id = uid),
        expiresAt = Instant.fromEpochMilliseconds(now + validForMs),
    )

    /** supabase-kt Auth as far as the gate sees it. */
    private inner class FakeAuth(initial: SessionStatus) : SessionPort {
        override val status = MutableStateFlow(initial)
        var storedSession: UserSession? = null
        val refreshedWith = mutableListOf<String>()
        val adopted = mutableListOf<UserSession>()
        var timerStops = 0
        var refreshTakesMs = 0L
        var offline = false

        override suspend fun stored(): UserSession? = storedSession
        override suspend fun refresh(refreshToken: String): UserSession {
            refreshedWith += refreshToken
            delay(refreshTakesMs)
            if (offline) throw java.io.IOException("offline")
            return session(token = "fresh${refreshedWith.size}")
        }
        override suspend fun adopt(session: UserSession) {
            adopted += session
            storedSession = session
            status.value = SessionStatus.Authenticated(session, SessionSource.Unknown)
        }
        override fun stopAutoRefresh() { timerStops++ }
        var reloads = 0
        override suspend fun reload() {
            reloads++
            storedSession?.let { status.value = SessionStatus.Authenticated(it, SessionSource.Storage) }
        }
    }

    private fun TestScope.gate(auth: FakeAuth, foreground: () -> Boolean = { false }) = SessionGate(
        auth, CoroutineScope(StandardTestDispatcher(testScheduler)), isForeground = foreground,
        nowMs = { now }, log = {},
    ).also { runCurrent() }

    /** A process that has loaded its session and then left the screen: the SDK's
     *  ON_STOP hook reset the status to Initializing, and nothing reloads it. */
    private fun TestScope.afterOnStopReset(
        stored: UserSession = session(), foreground: () -> Boolean = { false },
    ): Pair<FakeAuth, SessionGate> {
        val auth = FakeAuth(SessionStatus.Authenticated(stored, SessionSource.Storage)).apply { storedSession = stored }
        val g = gate(auth, foreground)
        auth.status.value = SessionStatus.Initializing
        return auth to g
    }

    // ── cold start: FCM / WorkManager / a broadcast started the process ──────

    @Test fun `cold start - waits for the SDK's own load and never races it`() = runTest {
        val auth = FakeAuth(SessionStatus.Initializing).apply { storedSession = session() }
        val g = gate(auth)
        val check = async { g.ensure(3_000) }
        advanceTimeBy(800)   // the SDK is refreshing the expired stored token over the network
        auth.status.value = SessionStatus.Authenticated(session(token = "sdk"), SessionSource.Refresh(session()))
        advanceUntilIdle()
        assertEquals(SessionCheck.Live("u1"), check.await())
        assertEquals("the SDK's load is in flight: no second refresh of the same token", emptyList<String>(), auth.refreshedWith)
        assertEquals(emptyList<UserSession>(), auth.adopted)
    }

    @Test fun `cold start, signed out - the SDK's load ends NotAuthenticated`() = runTest {
        val auth = FakeAuth(SessionStatus.Initializing)
        val g = gate(auth)
        val check = async { g.ensure(3_000) }
        advanceTimeBy(50)
        auth.status.value = SessionStatus.NotAuthenticated(isSignOut = false)
        advanceUntilIdle()
        assertEquals(SessionCheck.SignedOut, check.await())
    }

    @Test fun `cold start, the SDK's load outlives the deadline - the stored session answers, never signed out`() = runTest {
        val auth = FakeAuth(SessionStatus.Initializing).apply { storedSession = session() }
        val g = gate(auth)
        assertEquals(SessionCheck.Stored("u1"), g.ensure(3_000))
        assertTrue("still the SDK's load to finish", auth.refreshedWith.isEmpty() && auth.adopted.isEmpty())
    }

    @Test fun `cold start with nothing stored is signed out once time runs out`() = runTest {
        val g = gate(FakeAuth(SessionStatus.Initializing))
        assertEquals(SessionCheck.SignedOut, g.ensure(3_000))
    }

    // ── a live process in the background (the ON_STOP reset) ────────────────

    @Test fun `after the ON_STOP reset the stored session is restored without the network`() = runTest {
        val (auth, g) = afterOnStopReset()
        assertEquals(SessionCheck.Live("u1"), g.ensure())
        assertEquals(emptyList<String>(), auth.refreshedWith)
        assertEquals(listOf(session()), auth.adopted)
        val s = auth.status.value as SessionStatus.Authenticated
        assertEquals("not mistaken for a launch (INITIAL_SESSION)", SessionSource.Unknown, s.source)
    }

    @Test fun `after the reset an expired stored token is refreshed first`() = runTest {
        val (auth, g) = afterOnStopReset(session(validForMs = -60_000))
        assertEquals(SessionCheck.Live("u1"), g.ensure())
        assertEquals(listOf("r-a-u1"), auth.refreshedWith)
        assertEquals("fresh1", auth.adopted.single().accessToken)
    }

    @Test fun `after the reset while offline - the account is still here, and the next call tries again`() = runTest {
        val (auth, g) = afterOnStopReset(session(validForMs = -60_000))
        auth.offline = true
        assertEquals(SessionCheck.Stored("u1"), g.ensure())
        assertEquals(SessionStatus.Initializing, auth.status.value)
        auth.offline = false
        assertEquals(SessionCheck.Live("u1"), g.ensure())
    }

    @Test fun `after the reset with nothing stored (signed out meanwhile) is signed out`() = runTest {
        val (auth, g) = afterOnStopReset()
        auth.storedSession = null
        assertEquals(SessionCheck.SignedOut, g.ensure())
    }

    @Test fun `callers at the same moment share one restore`() = runTest {
        val (auth, g) = afterOnStopReset(session(validForMs = -60_000))
        auth.refreshTakesMs = 400
        val a = async { g.ensure() }
        val b = async { g.ensure() }
        advanceUntilIdle()
        assertEquals(SessionCheck.Live("u1"), a.await())
        assertEquals(SessionCheck.Live("u1"), b.await())
        assertEquals("one refresh token, spent once", 1, auth.refreshedWith.size)
        assertEquals(1, auth.adopted.size)
    }

    @Test fun `a caller's deadline never cancels the refresh it started`() = runTest {
        val (auth, g) = afterOnStopReset(session(validForMs = -60_000))
        auth.refreshTakesMs = 5_000
        assertEquals(SessionCheck.Stored("u1"), g.ensure(1_000))
        advanceUntilIdle()
        assertEquals("the refresh landed and was kept", 1, auth.adopted.size)
        assertEquals(SessionCheck.Live("u1"), g.ensure())
        assertEquals(1, auth.refreshedWith.size)
    }

    @Test fun `a backgrounded token about to expire is refreshed once and the SDK's timer is stopped`() = runTest {
        val stale = session(validForMs = 60_000)
        val auth = FakeAuth(SessionStatus.Authenticated(stale, SessionSource.Storage)).apply { storedSession = stale }
        val g = gate(auth)
        assertEquals(SessionCheck.Live("u1"), g.ensure())
        assertEquals(listOf("r-a-u1"), auth.refreshedWith)
        assertEquals("the sleeping SDK timer would spend the same refresh token later", 1, auth.timerStops)
        assertEquals(SessionCheck.Live("u1"), g.ensure())
        assertEquals("fresh now — no second refresh", 1, auth.refreshedWith.size)
    }

    @Test fun `a background token that can't be refreshed is the stored account, not live`() = runTest {
        val stale = session(validForMs = 60_000)
        val auth = FakeAuth(SessionStatus.Authenticated(stale, SessionSource.Storage)).apply { storedSession = stale; offline = true }
        assertEquals(SessionCheck.Stored("u1"), gate(auth).ensure())
    }

    // ── the foreground: the SDK owns the session ─────────────────────────────

    @Test fun `in the foreground the SDK's ON_START reload is awaited, not raced`() = runTest {
        val (auth, g) = afterOnStopReset(foreground = { true })
        val check = async { g.ensure() }
        advanceTimeBy(300)
        auth.status.value = SessionStatus.Authenticated(session(), SessionSource.Storage)
        advanceUntilIdle()
        assertEquals(SessionCheck.Live("u1"), check.await())
        assertEquals(emptyList<UserSession>(), auth.adopted)
    }

    @Test fun `in the foreground a reload that never comes is done after the grace, the SDK's way`() = runTest {
        val (auth, g) = afterOnStopReset(foreground = { true })
        val check = async { g.ensure() }
        advanceTimeBy(SessionGate.SDK_GRACE_MS - 1)
        assertEquals(0, auth.reloads)
        advanceUntilIdle()
        assertEquals(SessionCheck.Live("u1"), check.await())
        assertEquals("with its refresh timer + the launch it signals, not a timerless adopt", 1, auth.reloads)
        assertEquals(emptyList<UserSession>(), auth.adopted)
    }

    @Test fun `in the foreground an expiring token is left to the SDK's timer`() = runTest {
        val auth = FakeAuth(SessionStatus.Authenticated(session(validForMs = 60_000), SessionSource.Storage))
        assertEquals(SessionCheck.Live("u1"), gate(auth, foreground = { true }).ensure())
        assertEquals(emptyList<String>(), auth.refreshedWith)
    }

    // ── the settled states ───────────────────────────────────────────────────

    @Test fun `signed out is signed out`() = runTest {
        val auth = FakeAuth(SessionStatus.NotAuthenticated(isSignOut = true)).apply { storedSession = session() }
        assertEquals(SessionCheck.SignedOut, gate(auth).ensure())
    }

    @Test fun `a refresh failure is the stored account - the SDK is already retrying`() = runTest {
        val auth = FakeAuth(SessionStatus.RefreshFailure(RefreshFailureCause.NetworkError(java.io.IOException("offline"))))
            .apply { storedSession = session() }
        assertEquals(SessionCheck.Stored("u1"), gate(auth).ensure())
        assertEquals(emptyList<String>(), auth.refreshedWith)
    }

    @Test fun `a live session answers at once`() = runTest {
        val auth = FakeAuth(SessionStatus.Authenticated(session(), SessionSource.Storage))
        assertEquals(SessionCheck.Live("u1"), gate(auth).ensure())
        assertEquals("u1", SessionCheck.Stored("u1").accountId)
        assertEquals(null, SessionCheck.Stored("u1").liveUserId)
    }
}
