package tech.csalliance.unstuck.ui.auth

import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// The sign-up / magic-link App Link (owner decision 2026-09-23, same contract as iOS):
// https://unstucknow.io/auth/app-confirm/?token_hash=…&type=signup|magiclink. Which
// URIs it claims, what it verifies, and that a failure is a message — never a crash.
class AppConfirmLinkTest {

    // GoTrue's answer for a used or expired token hash.
    private fun used() = AuthRestException("otp_expired", "Email link is invalid or has expired", 403)

    @Before fun reset() = AppConfirmLink.resetForTest()

    @Test fun `only the app-confirm path on unstucknow_io, with or without its slash`() {
        assertTrue(AppConfirmLink.matches("https", "unstucknow.io", "/auth/app-confirm/"))
        assertTrue(AppConfirmLink.matches("https", "unstucknow.io", "/auth/app-confirm"))
        assertTrue(AppConfirmLink.matches("HTTPS", "UnstuckNow.io", "/auth/app-confirm/"))
    }

    @Test fun `the web's own links and look-alikes are not claimed`() {
        // /auth/confirm is the web's link: it must stay in the browser.
        assertFalse(AppConfirmLink.matches("https", "unstucknow.io", "/auth/confirm/"))
        assertFalse(AppConfirmLink.matches("https", "unstucknow.io", "/auth/app-confirmx"))
        assertFalse(AppConfirmLink.matches("https", "unstucknow.io", "/auth/app-confirm/extra"))
        assertFalse(AppConfirmLink.matches("http", "unstucknow.io", "/auth/app-confirm/"))
        assertFalse(AppConfirmLink.matches("https", "evil.example", "/auth/app-confirm/"))
        assertFalse(AppConfirmLink.matches("https", "www.unstucknow.io", "/auth/app-confirm/"))
        assertFalse(AppConfirmLink.matches("unstuck", "auth-confirm", null))
        assertFalse(AppConfirmLink.matches(null, null, null))
    }

    @Test fun `the two types the emails send`() {
        assertEquals(AppConfirmLink.Link("pkce_abc", OtpType.Email.SIGNUP), AppConfirmLink.parse("pkce_abc", "signup"))
        assertEquals(AppConfirmLink.Link("pkce_abc", OtpType.Email.MAGIC_LINK), AppConfirmLink.parse("pkce_abc", "magiclink"))
        assertEquals("trimmed", "abc", AppConfirmLink.parse("  abc ", "signup")?.tokenHash)
    }

    @Test fun `an incomplete or foreign link verifies nothing`() {
        assertNull(AppConfirmLink.parse(null, "signup"))
        assertNull(AppConfirmLink.parse("   ", "signup"))
        assertNull(AppConfirmLink.parse("x".repeat(1025), "signup"))
        assertNull(AppConfirmLink.parse("abc", null))
        // Reset keeps unstuck://auth-callback; nothing else is part of the contract.
        for (t in listOf("recovery", "email", "email_change", "invite", "SIGNUP", "")) assertNull(t, AppConfirmLink.parse("abc", t))
    }

    @Test fun `a good link signs in with its token hash and type`() = runTest {
        var verified: AppConfirmLink.Link? = null
        val link = AppConfirmLink.parse("pkce_good", "signup")
        assertNull(AppConfirmLink.complete(link) { verified = it })
        assertEquals(AppConfirmLink.Link("pkce_good", OtpType.Email.SIGNUP), verified)
    }

    @Test fun `an incomplete link says so without calling the server`() = runTest {
        assertEquals(AppConfirmLink.INCOMPLETE, AppConfirmLink.complete(null) { fail("nothing to verify") })
    }

    @Test fun `a used sign-up link says sign in - it did confirm the email`() = runTest {
        assertEquals(AppConfirmLink.SIGNUP_USED, AppConfirmLink.complete(AppConfirmLink.parse("h1", "signup")) { throw used() })
        assertEquals("That link has already been used — sign in.", AppConfirmLink.SIGNUP_USED)
    }

    @Test fun `a used or expired magic link says so`() = runTest {
        assertEquals(AppConfirmLink.MAGIC_USED, AppConfirmLink.complete(AppConfirmLink.parse("h2", "magiclink")) { throw used() })
        // A plain RestException 4xx (not the auth subclass) reads the same way.
        assertEquals(AppConfirmLink.MAGIC_USED, AppConfirmLink.complete(AppConfirmLink.parse("h3", "magiclink")) {
            throw RestException("validation_failed", "Invalid token", 400, "https://x/auth/v1/verify")
        })
    }

    @Test fun `a network drop says check your connection, and the link can be tapped again`() = runTest {
        val link = AppConfirmLink.parse("h4", "signup")
        assertEquals(AuthLink.OFFLINE, AppConfirmLink.complete(link) { throw java.net.UnknownHostException("no route") })
        assertEquals(AuthLink.OFFLINE, AppConfirmLink.complete(link) { throw RuntimeException("wrapped", java.net.SocketTimeoutException("timeout")) })
        var calls = 0
        assertNull(AppConfirmLink.complete(link) { calls++ })
        assertEquals("a failed link is not remembered as done", 1, calls)
    }

    @Test fun `a rate limit or a server error is not called a used link`() = runTest {
        assertEquals(AppConfirmLink.RETRY, AppConfirmLink.complete(AppConfirmLink.parse("h5", "signup")) {
            throw AuthRestException("over_request_rate_limit", "Too many requests", 429)
        })
        assertEquals(AppConfirmLink.RETRY, AppConfirmLink.complete(AppConfirmLink.parse("h6", "signup")) {
            throw AuthRestException("unexpected_failure", "Database error", 500)
        })
        assertEquals(AppConfirmLink.RETRY, AppConfirmLink.complete(AppConfirmLink.parse("h7", "signup")) { throw IllegalStateException("bad body") })
    }

    @Test fun `one link delivered twice is verified once and the copy says nothing`() = runTest {
        val serverAnswer = CompletableDeferred<Unit>()
        var calls = 0
        val link = AppConfirmLink.parse("pkce_twice", "magiclink")
        val first = async { AppConfirmLink.complete(link) { calls++; serverAnswer.await() } }
        runCurrent()
        assertNull("the first copy decides", AppConfirmLink.complete(link) { calls++; throw used() })
        serverAnswer.complete(Unit)
        assertNull(first.await())
        assertNull("a later re-delivery of a link that signed in says nothing", AppConfirmLink.complete(link) { calls++; throw used() })
        assertEquals(1, calls)
    }

    @Test fun `a used link is reported again when tapped again`() = runTest {
        val link = AppConfirmLink.parse("pkce_spent", "signup")
        assertEquals(AppConfirmLink.SIGNUP_USED, AppConfirmLink.complete(link) { throw used() })
        assertEquals(AppConfirmLink.SIGNUP_USED, AppConfirmLink.complete(link) { throw used() })
    }

    @Test fun `signed in, a used-up link needs no message but a failure worth acting on does`() {
        assertFalse(AppConfirmLink.showWhenSignedIn(AppConfirmLink.SIGNUP_USED))
        assertFalse(AppConfirmLink.showWhenSignedIn(AppConfirmLink.MAGIC_USED))
        assertTrue(AppConfirmLink.showWhenSignedIn(AuthLink.OFFLINE))
        assertTrue(AppConfirmLink.showWhenSignedIn(AppConfirmLink.RETRY))
        assertTrue(AppConfirmLink.showWhenSignedIn(AppConfirmLink.INCOMPLETE))
    }

    @Test fun `cancellation is not swallowed and the link can be tried again`() = runTest {
        val link = AppConfirmLink.parse("pkce_cancel", "signup")
        try {
            AppConfirmLink.complete(link) { throw CancellationException("scope gone") }
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
        var calls = 0
        assertNull(AppConfirmLink.complete(link) { calls++ })
        assertEquals(1, calls)
    }
}
