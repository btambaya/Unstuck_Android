package tech.csalliance.unstuck.ui.sharing

import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The C11 offline-blanking rule (parity with iOS build 79, audit 2026-09-22):
// People, Shared-with-you and the delegation badges keep what they show when a
// refresh fails, instead of reading empty — but one account's rows never carry
// over to the next. `next` returning null means "keep what is shown" (the flow's
// mapNotNull skips the emission). Pure JVM.
class LastGoodReadTest {

    @Test fun `a good read is shown and a failed read for the same account keeps it`() {
        val hold = LastGoodRead<List<String>>(emptyList())
        assertEquals(listOf("Maya"), hold.next("me", listOf("Maya")))
        assertNull("offline: keep the roster, never 'No one yet'", hold.next("me", null))
        assertNull(hold.next("me", null))
        assertEquals("a real empty answer still empties it", emptyList<String>(), hold.next("me", emptyList()))
    }

    @Test fun `a failed read with nothing held shows empty`() {
        val hold = LastGoodRead<List<String>>(emptyList())
        assertEquals(emptyList<String>(), hold.next("me", null))
        assertEquals(emptyList<String>(), hold.next("me", null))
    }

    @Test fun `signing out empties it and another account never inherits the rows`() {
        val hold = LastGoodRead<Map<String, Int>>(emptyMap())
        assertEquals(mapOf("t1" to 1), hold.next("a", mapOf("t1" to 1)))
        assertEquals("signed out", emptyMap<String, Int>(), hold.next(null, null))
        assertEquals("no user, even with an answer", emptyMap<String, Int>(), hold.next(null, mapOf("t1" to 1)))

        hold.next("a", mapOf("t1" to 1))
        assertEquals("B's failed first read must not show A's rows", emptyMap<String, Int>(), hold.next("b", null))
        assertEquals(emptyMap<String, Int>(), hold.next("a", null))
        assertEquals(mapOf("t2" to 2), hold.next("b", mapOf("t2" to 2)))
        assertNull(hold.next("b", null))
    }

    // supabase-kt reads the current user as null while a token refresh is failing
    // (offline with an expired access token) — still signed in per AppViewModel.authed.
    // The holds key on the session's account instead (review of the C11 port).

    @Test fun `a failed token refresh keeps the account's rows and never reads without the session`() = runBlocking {
        val hold = LastGoodRead<List<String>>(emptyList())
        var account = sessionAccount(signedIn("me"), null)
        assertEquals(listOf("Maya"), hold.refresh("me", account) { listOf("Maya") })

        account = sessionAccount(refreshFailed(), account)
        assertEquals("still my session", "me", account)
        var reads = 0
        assertNull("offline with an expired token: keep, never 'No one yet'",
            hold.refresh(null, account) { reads++; emptyList() })
        assertNull(hold.refresh(null, account) { reads++; null })
        assertEquals("no read goes out without my token (an anon call can answer empty)", 0, reads)

        account = sessionAccount(signedIn("me"), account)
        assertEquals("the refresh lands: a fresh answer shows", listOf("Maya", "Sam"),
            hold.refresh("me", account) { listOf("Maya", "Sam") })
    }

    @Test fun `a sign-out ends the session and the next account never inherits through a failed refresh`() = runBlocking {
        val hold = LastGoodRead<Map<String, Int>>(emptyMap())
        var account = sessionAccount(signedIn("a"), null)
        hold.refresh("a", account) { mapOf("t1" to 1) }

        // Signed out and back in as B with no read in between (the flow never
        // restarted), then B's token expires offline.
        account = sessionAccount(SessionStatus.NotAuthenticated(isSignOut = true), account)
        assertNull("a sign-out ends the session", account)
        account = sessionAccount(signedIn("b"), account)
        account = sessionAccount(refreshFailed(), account)
        assertEquals("b", account)
        assertEquals("B must not see A's rows", emptyMap<String, Int>(), hold.refresh(null, account) { null })

        assertEquals("signed out: empty, and nothing is read", emptyMap<String, Int>(),
            hold.refresh(null, null) { mapOf("t1" to 1) })
    }

    @Test fun `a cold start whose first refresh fails has nothing to keep`() = runBlocking {
        val hold = LastGoodRead<List<String>>(emptyList())
        var account = sessionAccount(SessionStatus.Initializing, null)
        assertNull(account)
        account = sessionAccount(refreshFailed(), account)
        assertNull("never authenticated in this process", account)
        assertEquals(emptyList<String>(), hold.refresh(null, account) { listOf("Maya") })
    }

    private fun signedIn(uid: String) = SessionStatus.Authenticated(
        UserSession(
            accessToken = "t", refreshToken = "r", expiresIn = 3600, tokenType = "bearer",
            user = UserInfo(aud = "authenticated", id = uid),
        ),
    )

    private fun refreshFailed() =
        SessionStatus.RefreshFailure(RefreshFailureCause.NetworkError(java.io.IOException("offline")))
}
