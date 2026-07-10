package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.csalliance.unstuck.core.logic.SessionSignalKind
import tech.csalliance.unstuck.core.logic.SigFire
import tech.csalliance.unstuck.core.logic.SigState
import tech.csalliance.unstuck.core.logic.initSigState
import tech.csalliance.unstuck.core.logic.sessionSignalStep

// 1:1 port of lib/use-share-session-signals.test.ts — drives a sequence of
// (sid, shared) observations through the pure reducer and collects everything it
// would fire, exactly as the AppViewModel observer feeds it.
class SessionSignalsTest {

    private fun start(t: String) = SigFire(SessionSignalKind.START, t)
    private fun end(t: String) = SigFire(SessionSignalKind.END, t)

    private fun run(steps: List<Pair<String?, String?>>): List<SigFire> {
        var s: SigState = initSigState()
        val fired = mutableListOf<SigFire>()
        for ((sid, shared) in steps) {
            val (state, fires) = sessionSignalStep(s, sid, shared)
            s = state
            fired += fires
        }
        return fired
    }

    @Test fun `fires start then end for a fresh shared session (badges already loaded)`() {
        assertEquals(
            listOf(start("t1"), end("t1")),
            run(listOf(
                null to null,      // mount, idle
                "s1" to "t1",      // start on shared task
                null to null,      // done
            )),
        )
    }

    @Test fun `still fires start when badges resolve AFTER the session began`() {
        assertEquals(
            listOf(start("t1"), end("t1")),
            run(listOf(
                null to null,      // mount, idle, badges empty
                "s1" to null,      // start — badges not loaded yet (shared=null)
                "s1" to "t1",      // badges resolve, same session
                null to null,      // done
            )),
        )
    }

    @Test fun `does NOT re-announce a reloaded (adopted) session, but still fires its end`() {
        assertEquals(
            listOf(end("t1")),
            run(listOf(
                "s1" to "t1",      // mount MID-session (reload) — adopt, no start
                null to null,      // done
            )),
        )
    }

    @Test fun `does not re-announce a reloaded session even if badges resolve late`() {
        assertEquals(
            listOf(end("t1")),      // no start, but end fires
            run(listOf(
                "s1" to null,      // mount mid-session, badges empty → adopt
                "s1" to "t1",      // badges resolve, same adopted session
                null to null,      // done
            )),
        )
    }

    @Test fun `handles a direct switch from shared A to shared B`() {
        assertEquals(
            listOf(start("A"), end("A"), start("B"), end("B")),
            run(listOf(
                null to null,
                "s1" to "A",       // start A
                "s2" to "B",       // displace → new session on B
                null to null,      // done
            )),
        )
    }

    @Test fun `never fires for a private (unshared) session`() {
        assertEquals(
            emptyList<SigFire>(),
            run(listOf(
                null to null,
                "s1" to null,      // start on a private task
                null to null,      // done
            )),
        )
    }

    @Test fun `is quiet across pause and resume (same sid, same shared)`() {
        assertEquals(
            listOf(start("t1"), end("t1")),
            run(listOf(
                null to null,
                "s1" to "t1",      // start → one start
                "s1" to "t1",      // pause tick (deps unchanged in practice)
                "s1" to "t1",      // resume tick
                null to null,      // done → one end
            )),
        )
    }

    @Test fun `switching a shared task to a private one ends the shared one only`() {
        assertEquals(
            listOf(start("t1"), end("t1")),
            run(listOf(
                null to null,
                "s1" to "t1",      // start shared
                "s2" to null,      // switch to private task
                null to null,      // done
            )),
        )
    }
}
