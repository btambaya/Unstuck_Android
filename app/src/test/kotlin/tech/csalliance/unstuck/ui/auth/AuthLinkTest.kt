package tech.csalliance.unstuck.ui.auth

import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

// A failed auth-link exchange is a message, never a crash (Android audit 2026-09-23, A7):
// supabase-kt's handleDeeplinks ran it where nothing could catch it.
class AuthLinkTest {

    @Test fun `a refused code - an older email after a second request - is a message, not a throw`() = runTest {
        val failure = AuthLink.complete("old-code", errorCode = null, errorDescription = null) {
            throw RestException("invalid_grant", "code challenge does not match previously saved code verifier", 400, "https://x/auth/v1/token?grant_type=pkce")
        }
        assertEquals(AuthLink.EXPIRED, failure)
    }

    @Test fun `a reused code and a missing verifier land on the same message`() = runTest {
        assertEquals(AuthLink.EXPIRED, AuthLink.complete("spent", null, null) { throw IllegalStateException("invalid flow state, flow state has already been used") })
        assertEquals(AuthLink.EXPIRED, AuthLink.complete("no-verifier", null, null) { throw IllegalArgumentException("code verifier missing") })
    }

    @Test fun `a network drop mid-exchange says to retry the link`() = runTest {
        assertEquals(AuthLink.OFFLINE, AuthLink.complete("c", null, null) { throw java.net.UnknownHostException("no route") })
        assertEquals(AuthLink.OFFLINE, AuthLink.complete("c", null, null) { throw RuntimeException("wrapped", java.net.SocketTimeoutException("timeout")) })
    }

    @Test fun `an expired email link arrives with an error and no code`() = runTest {
        var called = false
        val failure = AuthLink.complete(null, errorCode = "otp_expired", errorDescription = "Email link is invalid or has expired") { called = true }
        assertEquals(AuthLink.EXPIRED, failure)
        assertEquals("nothing to exchange", false, called)
    }

    @Test fun `a cancelled Google consent is not called an expired link`() = runTest {
        assertNull(AuthLink.complete(null, errorCode = null, errorDescription = "The user denied the request") { fail("no code, no exchange") })
    }

    @Test fun `a good code signs in and a bare link does nothing`() = runTest {
        var exchanged: String? = null
        assertNull(AuthLink.complete("good", null, null) { exchanged = it })
        assertEquals("good", exchanged)
        assertNull(AuthLink.complete(null, null, null) { fail("no code, no exchange") })
    }

    @Test fun `a link opened twice - the code-less second copy says nothing while the first signs in`() = runTest {
        val serverAnswer = CompletableDeferred<Unit>()
        val first = async { AuthLink.complete("reset-code", null, null) { serverAnswer.await() } }
        runCurrent()
        assertNull("the first copy's exchange decides",
            AuthLink.complete(null, errorCode = "otp_expired", errorDescription = "Email link is invalid or has expired") { fail("no code, no exchange") })
        serverAnswer.complete(Unit)
        assertNull(first.await())
        assertEquals("with nothing in flight an expired link is reported again",
            AuthLink.EXPIRED, AuthLink.complete(null, "otp_expired", null) { fail("no code, no exchange") })
    }

    @Test fun `a refused link says nothing while another link is still exchanging, and the last one speaks`() = runTest {
        val newerAnswer = CompletableDeferred<Unit>()
        val newer = async { AuthLink.complete("new-code", null, null) { newerAnswer.await() } }
        runCurrent()
        assertNull(AuthLink.complete("old-code", null, null) { throw RestException("invalid_grant", "flow state not found", 400, "https://x/auth/v1/token") })
        newerAnswer.completeExceptionally(java.net.UnknownHostException("no route"))
        assertEquals(AuthLink.OFFLINE, newer.await())
    }

    @Test fun `cancellation is not swallowed`() = runTest {
        try {
            AuthLink.complete("c", null, null) { throw CancellationException("scope gone") }
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
    }
}
