package tech.csalliance.unstuck.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.SharedSessionState

// Wire-contract tests for the one-true-shared-session `timer` broadcast (event
// codecs in CoFocusPresence.kt). The send side must ship whole-number epoch-ms with
// field names matching web + iOS byte-for-byte (hand-built JsonObject — the kotlinx
// default-omission gotcha); the receive side must tolerate decimals (iOS fractional
// Doubles), missing NEW fields (old builds → display-only) and partial payloads.
// Pure serialization — no Supabase client / channel.
class CoFocusWireTest {

    private fun obj(raw: String) = Json.parseToJsonElement(raw).jsonObject

    // --- send: sharedTimerJson ---

    @Test fun `sharedTimerJson ships every v2 field with whole-number epoch-ms`() {
        val s = SharedSessionState(
            sessionId = "sid-1", sessionStartMs = 1784200494383, paused = false, pausedAtMs = null,
            estimateMin = 25, rev = 3, atMs = 1784200501000, ended = false,
        )
        assertEquals(
            """{"userId":"me","sessionStartMs":1784200494383,"paused":false,"estimateMin":25,""" +
                """"sessionId":"sid-1","rev":3,"atMs":1784200501000,"ended":false}""",
            sharedTimerJson("me", s).toString(),
        )
    }

    @Test fun `sharedTimerJson includes pausedAtMs only when paused-at exists (never a JSON null)`() {
        val paused = SharedSessionState("sid-1", 1000L, true, 5000L, 25, 2, 6000L, false)
        val json = sharedTimerJson("me", paused).toString()
        assertTrue(json.contains(""""pausedAtMs":5000"""))
        val running = SharedSessionState("sid-1", 1000L, false, null, 25, 2, 6000L, false)
        assertFalse(sharedTimerJson("me", running).toString().contains("pausedAtMs"))
    }

    @Test fun `an ended broadcast round-trips through the decoder`() {
        val s = SharedSessionState("sid-9", 1000L, false, null, 45, 7, 999_000L, ended = true)
        val decoded = decodeSharedTimerMessage(sharedTimerJson("me", s))!!
        assertEquals(s, decoded)
    }

    // --- receive: decodeSharedTimerMessage ---

    @Test fun `decodes a full v2 message`() {
        val st = decodeSharedTimerMessage(
            obj(
                """{"userId":"u2","sessionId":"sid-1","sessionStartMs":1784200494383,"paused":true,
                    "pausedAtMs":1784200500000,"estimateMin":45,"rev":4,"atMs":1784200501000,"ended":false}""",
            ),
        )!!
        assertEquals("sid-1", st.sessionId)
        assertEquals(1784200494383L, st.sessionStartMs)
        assertTrue(st.paused)
        assertEquals(1784200500000L, st.pausedAtMs)
        assertEquals(45, st.estimateMin)
        assertEquals(4, st.rev)
        assertEquals(1784200501000L, st.atMs)
        assertFalse(st.ended)
    }

    @Test fun `an OLD build's message (no sessionId) is NOT a control — display-only`() {
        val legacy = obj("""{"userId":"u2","sessionStartMs":1784200494383,"paused":false,"estimateMin":25}""")
        assertNull(decodeSharedTimerMessage(legacy))
        // …but the display timer still decodes, so the shared view keeps working.
        val timer = decodeCoFocusTimerMessage(legacy)!!
        assertEquals(1784200494383L, timer.sessionStartMs)
        assertEquals(25, timer.estimateMin)
    }

    @Test fun `tolerates iOS decimal epoch-ms on every numeric field`() {
        val st = decodeSharedTimerMessage(
            obj(
                """{"userId":"u2","sessionId":"sid-1","sessionStartMs":1784200494383.217,"paused":true,
                    "pausedAtMs":1784200500000.9,"estimateMin":45.0,"rev":4.0,"atMs":1784200501000.5,"ended":false}""",
            ),
        )!!
        assertEquals(1784200494383L, st.sessionStartMs)
        assertEquals(1784200500000L, st.pausedAtMs)
        assertEquals(45, st.estimateMin)
        assertEquals(4, st.rev)
        assertEquals(1784200501000L, st.atMs)
    }

    @Test fun `missing new fields default instead of dropping the message`() {
        // A sessionId + start alone is enough: rev/atMs default to 0 (the reducer's
        // strictly-newer check still orders real controls after it), ended to false.
        val st = decodeSharedTimerMessage(obj("""{"userId":"u2","sessionId":"sid-1","sessionStartMs":1000}"""))!!
        assertEquals(0, st.rev)
        assertEquals(0L, st.atMs)
        assertFalse(st.ended)
        assertFalse(st.paused)
        assertNull(st.pausedAtMs)
        assertEquals(25, st.estimateMin)
    }

    @Test fun `a message without sessionStartMs never decodes (timer or control)`() {
        val broken = obj("""{"userId":"u2","sessionId":"sid-1","paused":true}""")
        assertNull(decodeSharedTimerMessage(broken))
        assertNull(decodeCoFocusTimerMessage(broken))
    }

    // --- hello: the diverged flag (spec amendments — the both-diverged deadlock
    // breaker: a diverged hello makes a focuser answer even while itself diverged) ---

    @Test fun `helloJson carries diverged ONLY when true (old builds keep decoding {userId})`() {
        assertEquals("""{"userId":"me"}""", helloJson("me", diverged = false).toString())
        assertEquals("""{"userId":"me","diverged":true}""", helloJson("me", diverged = true).toString())
    }

    @Test fun `decodeHelloMessage round-trips and degrades on missing or malformed diverged`() {
        assertEquals(HelloMessage("me", true), decodeHelloMessage(helloJson("me", diverged = true)))
        assertEquals(HelloMessage("me", false), decodeHelloMessage(helloJson("me", diverged = false)))
        // An old build's {userId}-only hello → diverged defaults false.
        assertEquals(HelloMessage("u2", false), decodeHelloMessage(obj("""{"userId":"u2"}""")))
        // Malformed diverged degrades, never throws.
        assertEquals(HelloMessage("u2", false), decodeHelloMessage(obj("""{"userId":"u2","diverged":"nope"}""")))
        // No userId → nothing to answer.
        assertNull(decodeHelloMessage(obj("""{"diverged":true}""")))
    }
}
