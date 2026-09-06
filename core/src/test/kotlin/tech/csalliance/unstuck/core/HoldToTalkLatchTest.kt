package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.HoldToTalkLatch

// Hold-to-talk press/release sequencing (spec §8). The bug this guards: the
// release (and the commit behind it) used to apply the instant the finger
// lifted, while the capture thread was still inside the ~100 ms read that
// contained the end of the utterance — that frame was then discarded as
// "released", cutting the tail of every press and committing an EMPTY buffer
// for a tap shorter than one read.
class HoldToTalkLatchTest {

    @Test
    fun `press applies now, release only at the next frame boundary`() {
        val l = HoldToTalkLatch()
        l.press()
        assertTrue(l.pressed)
        l.release()
        // The finger is up, but the frame in flight must still see the press.
        assertTrue(l.pressed)
        assertTrue(l.releasePending)
        l.frameBoundary()
        assertFalse(l.pressed)
        assertFalse(l.releasePending)
    }

    @Test
    fun `drains run at the boundary AFTER the release is applied, in order, once`() {
        val l = HoldToTalkLatch()
        val log = mutableListOf<String>()
        l.press(); l.release()
        l.afterDrain { log += "commit" }
        l.afterDrain { log += "respond" }
        assertTrue(log.isEmpty()) // nothing until the capture thread reaches the boundary
        val drains = l.frameBoundary()
        assertEquals(2, drains.size)
        drains.forEach { it() }
        assertEquals(listOf("commit", "respond"), log)
        assertFalse(l.pressed)
        assertTrue(l.frameBoundary().isEmpty()) // consumed
    }

    @Test
    fun `re-press before the boundary cancels the release but keeps the drains`() {
        val l = HoldToTalkLatch()
        var ran = 0
        l.press(); l.release(); l.afterDrain { ran++ }
        l.press()
        val drains = l.frameBoundary()
        assertTrue(l.pressed) // still held: the second press wins
        assertEquals(1, drains.size)
        drains.forEach { it() }
        assertEquals(1, ran)
    }

    @Test
    fun `release without a press is a no-op`() {
        val l = HoldToTalkLatch()
        l.release()
        assertFalse(l.releasePending)
        assertFalse(l.pressed)
    }

    @Test
    fun `releaseNow applies everything for a dead capture loop`() {
        val l = HoldToTalkLatch()
        var ran = 0
        l.press(); l.release(); l.afterDrain { ran++ }
        val drains = l.releaseNow()
        assertFalse(l.pressed)
        assertFalse(l.releasePending)
        drains.forEach { it() }
        assertEquals(1, ran)
        assertTrue(l.releaseNow().isEmpty())
    }
}
