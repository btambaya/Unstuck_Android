package tech.csalliance.unstuck.ui

import io.github.jan.supabase.auth.status.SessionSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The one-shot PKCE recovery probe may only be spent by the deep-link code
// exchange. A reset link tapped with the app killed + a session stored arms the
// probe BEFORE the storage-restored session emits; consuming it there classified
// the OLD token, so the set-new-password screen never appeared.
class RecoveryProbeTest {
    @Test fun onlyTheExternalExchangeConsumes() {
        assertTrue(RecoveryProbe.consumes(SessionSource.External))
        assertFalse("stored session on a cold start", RecoveryProbe.consumes(SessionSource.Storage))
        assertFalse(RecoveryProbe.consumes(SessionSource.Unknown))
        assertFalse(RecoveryProbe.consumes(SessionSource.AnonymousSignIn))
    }
}
