package tech.csalliance.unstuck.sync

import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.exceptions.RestException
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        /** The server refuses the refresh token outright (revoked) with this status. */
        var refuse: Int? = null

        override suspend fun stored(): UserSession? = storedSession
        override suspend fun refresh(refreshToken: String): UserSession {
            refreshedWith += refreshToken
            delay(refreshTakesMs)
            if (offline) throw java.io.IOException("offline")
            refuse?.let { throw RefreshRefused(it) }
            return session(token = "fresh${refreshedWith.size}")
        }
        override suspend fun adopt(session: UserSession) {
            adopted += session
            storedSession = session
            status.value = SessionStatus.Authenticated(session, SessionSource.Unknown)
        }
        override fun stopAutoRefresh() { timerStops++ }
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

    // Second pass (R2): the SDK's ON_START reload refreshes an expired token for up to
    // the client's 90 s request timeout. A gate that reloaded itself after a 3 s grace
    // spent the same refresh token twice (GoTrue revokes the family when the two land
    // >10 s apart → the SDK signs out and wipes the outbox), and parked the SDK's
    // for-ever network retry inside its single-flight step.
    @Test fun `in the foreground a slow SDK reload is never raced - no load or refresh of its own`() = runTest {
        val (auth, g) = afterOnStopReset(session(validForMs = -60_000), foreground = { true })
        val check = async { g.ensure() }
        advanceTimeBy(8_000)
        assertFalse("still the SDK's reload to land", check.isCompleted)
        assertEquals(emptyList<String>(), auth.refreshedWith)
        assertEquals(emptyList<UserSession>(), auth.adopted)
        auth.status.value = SessionStatus.Authenticated(session(token = "sdk"), SessionSource.Refresh(session()))
        advanceUntilIdle()
        assertEquals(SessionCheck.Live("u1"), check.await())
        assertEquals(emptyList<String>(), auth.refreshedWith)
    }

    @Test fun `in the foreground a reload that outlives the deadline is the stored account, still nothing of its own`() = runTest {
        val (auth, g) = afterOnStopReset(session(validForMs = -60_000), foreground = { true })
        assertEquals(SessionCheck.Stored("u1"), g.ensure(3_000))
        advanceUntilIdle()
        assertEquals(emptyList<String>(), auth.refreshedWith)
        assertEquals(emptyList<UserSession>(), auth.adopted)
    }

    // Second pass (R3): a background restore imports its session with no refresh timer;
    // by the next ON_START it has often expired, and the SDK's reload is refreshing it.
    // Counting it live on screen sent the ON_START outcome flush out with a dead JWT.
    @Test fun `on screen an expired token is not live - the SDK's refresh is awaited, not duplicated`() = runTest {
        val auth = FakeAuth(SessionStatus.Authenticated(session(validForMs = -60_000), SessionSource.Unknown))
        val g = gate(auth, foreground = { true })
        val check = async { g.ensure() }
        advanceTimeBy(500)
        assertFalse(check.isCompleted)
        auth.status.value = SessionStatus.Authenticated(session(token = "sdk"), SessionSource.Refresh(session()))
        advanceUntilIdle()
        assertEquals(SessionCheck.Live("u1"), check.await())
        assertEquals("the SDK's refresh, not a second one", emptyList<String>(), auth.refreshedWith)
    }

    @Test fun `on screen an expired token that outlives the deadline is the stored account, never live`() = runTest {
        val auth = FakeAuth(SessionStatus.Authenticated(session(validForMs = -60_000), SessionSource.Unknown))
        assertEquals(SessionCheck.Stored("u1"), gate(auth, foreground = { true }).ensure(3_000))
        assertEquals(emptyList<String>(), auth.refreshedWith)
    }

    @Test fun `liveNow is the user only while the token is good`() = runTest {
        val auth = FakeAuth(SessionStatus.Authenticated(session(validForMs = -60_000), SessionSource.Unknown))
        val g = gate(auth, foreground = { true })
        assertNull("expired: the SDK is refreshing it", g.liveNow())
        auth.status.value = SessionStatus.Authenticated(session(), SessionSource.Refresh(session()))
        assertEquals("u1", g.liveNow())
        auth.status.value = SessionStatus.Initializing
        assertNull(g.liveNow())
    }

    @Test fun `in the foreground an expiring token is left to the SDK's timer`() = runTest {
        val auth = FakeAuth(SessionStatus.Authenticated(session(validForMs = 60_000), SessionSource.Storage))
        assertEquals(SessionCheck.Live("u1"), gate(auth, foreground = { true }).ensure())
        assertEquals(emptyList<String>(), auth.refreshedWith)
    }

    // ── a refresh the server refused (second pass, R5) ───────────────────────
    // A token revoked by a sign-out elsewhere is refused on every try. Answered as
    // "stored, not live", the SyncWorker retried it with backoff all day, one doomed
    // refresh per run, until the app was opened.

    @Test fun `a refused refresh is signed out, and never asked again`() = runTest {
        val (auth, g) = afterOnStopReset(session(validForMs = -60_000))
        auth.refuse = 400
        assertEquals(SessionCheck.SignedOut, g.ensure())
        assertEquals(SessionCheck.SignedOut, g.ensure())
        assertEquals("one doomed refresh, not one per call", 1, auth.refreshedWith.size)
        assertEquals(emptyList<UserSession>(), auth.adopted)
    }

    @Test fun `a backgrounded token whose refresh is refused is signed out`() = runTest {
        val stale = session(validForMs = 60_000)
        val auth = FakeAuth(SessionStatus.Authenticated(stale, SessionSource.Storage)).apply { storedSession = stale; refuse = 400 }
        val g = gate(auth)
        assertEquals(SessionCheck.SignedOut, g.ensure())
        assertEquals(SessionCheck.SignedOut, g.ensure())
        assertEquals(1, auth.refreshedWith.size)
    }

    @Test fun `a new sign-in after a refusal is live again`() = runTest {
        val (auth, g) = afterOnStopReset(session(validForMs = -60_000))
        auth.refuse = 400
        assertEquals(SessionCheck.SignedOut, g.ensure())
        auth.status.value = SessionStatus.Authenticated(session(token = "new"), SessionSource.Storage)
        assertEquals(SessionCheck.Live("u1"), g.ensure())
    }

    @Test fun `only an outright 4xx refusal counts - offline, 5xx, 401, timeouts and rate limits stay transient`() {
        fun rest(code: Int) = RestException("e", "d", code, "https://x/auth/v1/token")
        assertEquals(400, refreshRefusalStatus(rest(400)))
        assertEquals(403, refreshRefusalStatus(rest(403)))
        assertEquals(404, refreshRefusalStatus(rest(404)))
        listOf(401, 408, 429, 500, 503).forEach { assertNull("$it", refreshRefusalStatus(rest(it))) }
        assertNull(refreshRefusalStatus(java.io.IOException("offline")))
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
