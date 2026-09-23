package tech.csalliance.unstuck.calls

import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.csalliance.unstuck.sync.SessionGate
import tech.csalliance.unstuck.sync.SessionPort

// Call outcomes made after the app left the screen are sent (Android audit
// 2026-09-23, A3). The old gate read `auth.currentUserId`, null ~700 ms after
// ON_STOP (supabase-kt resets the session to Initializing) and at the ON_START
// that fires the foreground flush, before the SDK's own reload: a snooze said on a
// locked phone stayed queued until the next pull, so its call-back never came.
@OptIn(ExperimentalCoroutinesApi::class)
class CallOutcomeFlushSessionTest {
    private val now = 1_800_000_000_000L
    private val session = UserSession(
        accessToken = "a", refreshToken = "r", expiresIn = 3600, tokenType = "bearer",
        user = UserInfo(aud = "authenticated", id = "u1"), expiresAt = Instant.fromEpochMilliseconds(now + 3_600_000),
    )

    private class Auth(initial: SessionStatus, val saved: UserSession?) : SessionPort {
        override val status = MutableStateFlow(initial)
        override suspend fun stored(): UserSession? = saved
        override suspend fun refresh(refreshToken: String): UserSession = error("not expired")
        override suspend fun adopt(session: UserSession) { status.value = SessionStatus.Authenticated(session, SessionSource.Unknown) }
        override fun stopAutoRefresh() = Unit
    }

    /** A process that loaded its session, then went to the background (ON_STOP reset). */
    private fun TestScope.reset(foreground: Boolean): Pair<Auth, SessionGate> {
        val auth = Auth(SessionStatus.Authenticated(session, SessionSource.Storage), session)
        val gate = SessionGate(auth, CoroutineScope(StandardTestDispatcher(testScheduler)), { foreground }, nowMs = { now }, log = {})
        runCurrent()
        auth.status.value = SessionStatus.Initializing
        return auth to gate
    }

    @Test fun `a snooze queued with the screen locked is sent`() = runTest {
        val (_, gate) = reset(foreground = false)
        var drains = 0
        CallOutcomeStore.flushWhenSignedIn(gate) { drains++ }
        assertEquals(1, drains)
    }

    @Test fun `the foreground flush waits for the SDK's reload, then sends`() = runTest {
        val (auth, gate) = reset(foreground = true)
        var drains = 0
        val flush = async { CallOutcomeStore.flushWhenSignedIn(gate) { drains++ } }
        advanceTimeBy(200)
        assertEquals("not before the session is back", 0, drains)
        auth.status.value = SessionStatus.Authenticated(session, SessionSource.Storage)
        advanceUntilIdle()
        flush.await()
        assertEquals(1, drains)
    }

    @Test fun `signed out - nothing is sent`() = runTest {
        val auth = Auth(SessionStatus.NotAuthenticated(isSignOut = true), null)
        val gate = SessionGate(auth, CoroutineScope(StandardTestDispatcher(testScheduler)), { false }, nowMs = { now }, log = {})
        var drains = 0
        CallOutcomeStore.flushWhenSignedIn(gate) { drains++ }
        assertEquals(0, drains)
    }

    // Second pass (R3): a ring at 10:00 restores the session in the background with no
    // refresh timer; the user opens the app at 11:30 and the ON_START flush fires while
    // the SDK's reload is still refreshing that expired token. Counting it live sent the
    // outcome with a dead JWT — a 401, and the item backed off.
    @Test fun `the foreground flush never sends with an expired token a background restore left behind`() = runTest {
        val expired = session.copy(expiresAt = Instant.fromEpochMilliseconds(now - 60_000))
        val auth = Auth(SessionStatus.Authenticated(expired, SessionSource.Unknown), expired)
        val gate = SessionGate(auth, CoroutineScope(StandardTestDispatcher(testScheduler)), { true }, nowMs = { now }, log = {})
        var drains = 0
        val flush = async { CallOutcomeStore.flushWhenSignedIn(gate) { drains++ } }
        advanceTimeBy(500)
        assertEquals("not with the dead JWT", 0, drains)
        auth.status.value = SessionStatus.Authenticated(session, SessionSource.Refresh(expired))
        advanceUntilIdle()
        flush.await()
        assertEquals(1, drains)
    }
}
