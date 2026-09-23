package tech.csalliance.unstuck.ui.sharing

import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

// Android audit 2026-09-23, A16: the delegation badges started with the
// ViewModel, before the stored session had loaded, and were kept running for
// good by the widget / co-focus collectors — so their one read was skipped (no
// user yet) and nothing asked again. Handed-over tasks came back into Today and
// Start-Next, and a partner's focus session was never joined. These tests drive
// the same pipeline AppViewModel builds for shareBadges / sharedWithMe / circle
// (a manual pulse merged with [sessionRereads], one read on start, the
// LastGoodRead hold keyed on the session's account), with the session status
// played by hand. Pure JVM.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionRereadsTest {

    private class Harness(scope: TestScope) {
        val status = MutableStateFlow<SessionStatus>(SessionStatus.Initializing)
        val manual = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        /** What the server answers for whoever is signed in; reads are counted. */
        var server: Map<String, Map<String, String>> = emptyMap()
        var reads = 0
        private val hold = LastGoodRead<Map<String, String>>(emptyMap())

        // AppViewModel.heldAccount, verbatim.
        val account: StateFlow<String?> = status
            .runningFold(null as String?) { prev, s -> sessionAccount(s, prev) }
            .stateIn(scope.backgroundScope, SharingStarted.Eagerly, null)

        private fun currentUid(): String? = (status.value as? SessionStatus.Authenticated)?.session?.user?.id

        // The shareBadges pipeline, kept hot (Eagerly) as the widget collector keeps it.
        val badges: StateFlow<Map<String, String>> =
            merge(manual, sessionRereads(status, account))
                .onStart { emit(Unit) }
                .transform { hold.refreshInto(this, currentUid(), account.value) { reads++; server[currentUid()] } }
                .stateIn(scope.backgroundScope, SharingStarted.Eagerly, emptyMap())
    }

    @Test fun `the badges load once the stored session loads, although the flow started before it`() = runTest {
        val h = Harness(this)
        h.server = mapOf("me" to mapOf("t1" to "Sam"))
        runCurrent()
        assertEquals("no user yet: nothing is read", 0, h.reads)
        assertEquals(emptyMap<String, String>(), h.badges.value)

        h.status.value = signedIn("me")   // the cold-start restore / a sign-in
        runCurrent()
        assertEquals("the handed-over task is known again", mapOf("t1" to "Sam"), h.badges.value)
        assertEquals(1, h.reads)
    }

    @Test fun `signing out empties the badges and the next account reads its own`() = runTest {
        val h = Harness(this)
        h.server = mapOf("a" to mapOf("t1" to "Sam"), "b" to mapOf("t9" to "Lee"))
        h.status.value = signedIn("a")
        runCurrent()
        assertEquals(mapOf("t1" to "Sam"), h.badges.value)

        h.status.value = SessionStatus.NotAuthenticated(isSignOut = true)
        runCurrent()
        assertEquals("signed out: A's badges are gone", emptyMap<String, String>(), h.badges.value)

        h.status.value = signedIn("b")
        runCurrent()
        assertEquals("B sees B's, never A's", mapOf("t9" to "Lee"), h.badges.value)
    }

    @Test fun `the return from the SDK's background reset reads again`() = runTest {
        val h = Harness(this)
        h.server = mapOf("me" to mapOf("t1" to "Sam"))
        h.status.value = signedIn("me")
        runCurrent()
        assertEquals(1, h.reads)

        // ON_STOP: supabase-kt drops back to Initializing (no current user). A pulse
        // then is skipped and keeps what is shown.
        h.status.value = SessionStatus.Initializing
        runCurrent()
        h.server = mapOf("me" to mapOf("t1" to "Sam", "t2" to "Ana"))
        h.manual.tryEmit(Unit)
        runCurrent()
        assertEquals("no read without the session's token", 1, h.reads)
        assertEquals(mapOf("t1" to "Sam"), h.badges.value)

        h.status.value = signedIn("me")   // the stored session is back on foreground
        runCurrent()
        assertEquals(2, h.reads)
        assertEquals(mapOf("t1" to "Sam", "t2" to "Ana"), h.badges.value)
    }

    @Test fun `a token that works again after a failed refresh reads again, a routine refresh does not`() = runTest {
        val h = Harness(this)
        h.server = mapOf("me" to mapOf("t1" to "Sam"))
        // Offline cold start with an expired token: never authenticated yet.
        h.status.value = SessionStatus.RefreshFailure(RefreshFailureCause.NetworkError(java.io.IOException("offline")))
        runCurrent()
        assertEquals(0, h.reads)
        assertEquals(emptyMap<String, String>(), h.badges.value)

        h.status.value = signedIn("me")   // back online, the refresh lands
        runCurrent()
        assertEquals(mapOf("t1" to "Sam"), h.badges.value)
        assertEquals(1, h.reads)

        h.status.value = signedIn("me", token = "rotated")   // a routine token rotation
        runCurrent()
        assertEquals("same account, still authenticated: no extra read", 1, h.reads)
    }

    @Test fun `the state at collection start is not an edge — the projection's start read covers it`() = runTest {
        val status = MutableStateFlow<SessionStatus>(signedIn("me"))
        val account = MutableStateFlow<String?>("me")
        var ticks = 0
        backgroundScope.launch { sessionRereads(status, account).collect { ticks++ } }
        runCurrent()
        assertEquals(0, ticks)

        status.value = SessionStatus.NotAuthenticated(isSignOut = true)
        account.value = null
        runCurrent()
        assertEquals("the sign-out is", 1, ticks)
    }

    // Shared-with-you, People and the shared blocks stop reading 5 s after their last
    // screen goes; the StateFlow keeps its value. Signed out from a screen that
    // doesn't collect them (Settings from the Lists tab), the next account's Today
    // got the last account's rows first and kept them until its own read came back
    // (Android audit 2026-09-23, A16).
    @Test fun `a projection nobody collected through a sign-out never shows the last account's rows to the next`() = runTest {
        val status = MutableStateFlow<SessionStatus>(signedIn("a"))
        val account: StateFlow<String?> = status
            .runningFold(null as String?) { prev, s -> sessionAccount(s, prev) }
            .stateIn(backgroundScope, SharingStarted.Eagerly, null)
        fun currentUid(): String? = (status.value as? SessionStatus.Authenticated)?.session?.user?.id
        val server = mapOf("a" to listOf("A's partner's task"), "b" to listOf("B's partner's task"))
        val hold = LastGoodRead<List<String>>(emptyList())
        // The sharedWithMe pipeline; each read takes a second on the network.
        val sharedWithMe: StateFlow<List<String>> =
            merge(MutableSharedFlow<Unit>(), sessionRereads(status, account))
                .onStart { emit(Unit) }
                .transform { hold.refreshInto(this, currentUid(), account.value) { delay(1_000); server[currentUid()] } }
                .stateIn(backgroundScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        val todayA = backgroundScope.launch { sharedWithMe.collect {} }
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf("A's partner's task"), sharedWithMe.value)
        todayA.cancel()   // A goes to the Lists tab, then Settings
        advanceTimeBy(6_000)
        runCurrent()

        status.value = SessionStatus.NotAuthenticated(isSignOut = true)
        runCurrent()
        status.value = signedIn("b")
        runCurrent()

        backgroundScope.launch { sharedWithMe.collect {} }   // B's Today
        runCurrent()
        assertEquals("B's read is in flight: nothing of A's", emptyList<String>(), sharedWithMe.value)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf("B's partner's task"), sharedWithMe.value)
    }

    private fun signedIn(uid: String, token: String = "t") = SessionStatus.Authenticated(
        UserSession(
            accessToken = token, refreshToken = "r", expiresIn = 3600, tokenType = "bearer",
            user = UserInfo(aud = "authenticated", id = uid),
        ),
    )
}
