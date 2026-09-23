package tech.csalliance.unstuck.ui.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The voice dial token (parity with iOS build 81 FreshAccessTokenTests +
 * testTheCachedTokenIsNeverTheAnswerToAForcedRefresh, audit 2026-09-22 C14/C15):
 * a token that outlives the session is used as it is; one that would expire
 * mid-session is topped up; an expired one is refreshed first; a forced refresh
 * never hands back the stored token; and a stalled refresh never holds the dial.
 */
class VoiceTokenTest {
    private val now = 1_000_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun resolve(
        leftMs: Long?, force: Boolean = false, refreshes: AtomicInteger = AtomicInteger(), reads: AtomicInteger = AtomicInteger(),
        refresh: suspend () -> String? = { "refreshed" },
    ): String? = runBlocking {
        VoiceToken.resolve(
            forceRefresh = force,
            nowMs = { now },
            stored = { reads.incrementAndGet(); leftMs?.let { VoiceToken.Stored("stored", now + it) } },
            refresh = { refreshes.incrementAndGet(); refresh() },
            scope = scope,
        )
    }

    @Test fun `a token that outlives the session is used as it is`() {
        val r = AtomicInteger()
        assertEquals("stored", resolve(60 * 60_000L, refreshes = r))
        assertEquals(0, r.get())
    }

    @Test fun `a token that would expire mid-session is refreshed first`() {
        val r = AtomicInteger()
        assertEquals("refreshed", resolve(10 * 60_000L, refreshes = r))
        assertEquals(1, r.get())
    }

    @Test fun `an expired token is refreshed, and a failed refresh falls back to it for the 401 path`() {
        assertEquals("refreshed", resolve(-60_000L))
        assertEquals("stored", resolve(-60_000L, refresh = { throw IllegalStateException("offline") }))
    }

    @Test fun `a failed top-up falls back to the still-valid token`() {
        val r = AtomicInteger()
        assertEquals("stored", resolve(10 * 60_000L, refreshes = r, refresh = { null }))
        assertEquals(1, r.get())
    }

    @Test fun `a stalled top-up does not hold the dial`() {
        val started = System.currentTimeMillis()
        assertEquals("stored", resolve(10 * 60_000L, refresh = { delay(4_000); "late" }))
        assertTrue(System.currentTimeMillis() - started < VoiceToken.TOP_UP_DEADLINE_MS + 1_000)
    }

    @Test fun `a forced refresh never hands back the stored token`() {
        val r = AtomicInteger(); val reads = AtomicInteger()
        assertEquals("refreshed", resolve(60 * 60_000L, force = true, refreshes = r, reads = reads))
        assertEquals(1, r.get())
        assertEquals("the stored token is the one the server refused", 0, reads.get())
        val failedReads = AtomicInteger()
        assertNull(resolve(60 * 60_000L, force = true, reads = failedReads, refresh = { throw IllegalStateException("revoked") }))
        assertEquals(0, failedReads.get())
    }

    @Test fun `no readable session is null without a refresh`() {
        val r = AtomicInteger()
        assertNull(resolve(null, refreshes = r))
        assertEquals(0, r.get())
    }

    @Test fun `firstWithin gives up on a stalled operation without cancelling it`() = runBlocking {
        val finished = AtomicInteger()
        val started = System.currentTimeMillis()
        val v: String? = VoiceToken.firstWithin(100, scope) { delay(500); finished.incrementAndGet(); "late" }
        assertNull(v)
        assertTrue("the stalled refresh is not awaited", System.currentTimeMillis() - started < 450)
        delay(700)
        assertEquals("the loser runs to the end — an SDK refresh is never cut mid-flight", 1, finished.get())
        assertEquals("tok", VoiceToken.firstWithin(5_000, scope) { "tok" })
    }

    /** The cached token stands in when the fresh read fails — but never after a
     *  FORCED refresh, which follows the proxy refusing exactly that token. */
    @Test fun `the cached token is never the answer to a forced refresh`() {
        assertEquals("f", VoiceToken.dialToken("f", "c", forceRefresh = false))
        assertEquals("f", VoiceToken.dialToken("f", "c", forceRefresh = true))
        assertEquals("c", VoiceToken.dialToken(null, "c", forceRefresh = false))
        assertEquals("c", VoiceToken.dialToken("", "c", forceRefresh = false))
        assertNull(VoiceToken.dialToken(null, "c", forceRefresh = true))
        assertNull(VoiceToken.dialToken(null, null, forceRefresh = false))
        assertNull(VoiceToken.dialToken(null, "", forceRefresh = false))
    }

    /** The proxy hard-closes a session at 15 min and keeps the connect-time token. */
    @Test fun `a dialled token outlives the proxy's session cap, inside the dial watchdog`() {
        assertTrue(VoiceToken.MIN_VALIDITY_MS > 15 * 60_000L)
        assertTrue(VoiceToken.TOP_UP_DEADLINE_MS < VoiceToken.DEADLINE_MS)
        assertTrue(VoiceToken.DEADLINE_MS < 15_000L)
    }
}
