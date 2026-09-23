package tech.csalliance.unstuck.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

// A drain in flight when supabase-kt's ON_STOP reset lands (~700 ms after leaving
// the app) stops at OutboxFlusher's next pass — the live user reads null — and an op
// sent after the reset goes out without the JWT and fails. Nothing re-armed it, so
// the rest waited for the next write or SyncWorker run: tick a task, press Home a
// second later, and part of it stayed on the phone (Android audit 2026-09-23, A2 —
// second pass).
class DrainAcrossResetTest {

    private class Run(var live: String?, var pending: Boolean = true, val restored: String? = "u1") {
        var drains = 0
        var restores = 0
        suspend fun go() = drainAcrossReset(
            "u1",
            drain = { drains++ },
            liveUser = { live },
            hasPending = { pending },
            ensure = { restores++; restored },
        )
    }

    @Test fun `a drain the ON_STOP reset cut short finishes once the session is restored`() = runTest {
        val r = Run(live = null)
        r.go()
        assertEquals(2, r.drains)
        assertEquals(1, r.restores)
    }

    @Test fun `a drain that ended with the session still live is not repeated`() = runTest {
        val r = Run(live = "u1")
        r.go()
        assertEquals(1, r.drains)
        assertEquals(0, r.restores)
    }

    @Test fun `nothing left queued - no restore, no second drain`() = runTest {
        val r = Run(live = null, pending = false)
        r.go()
        assertEquals(1, r.drains)
        assertEquals(0, r.restores)
    }

    @Test fun `signed out or another account meanwhile - no second drain as the old user`() = runTest {
        val out = Run(live = null, restored = null).apply { go() }
        assertEquals(1, out.drains)
        val other = Run(live = null, restored = "u2").apply { go() }
        assertEquals(1, other.drains)
    }
}
