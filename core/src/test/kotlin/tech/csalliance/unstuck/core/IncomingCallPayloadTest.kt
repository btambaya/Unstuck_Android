package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.IncomingCallPayload
import tech.csalliance.unstuck.core.model.CallKind
import java.time.LocalDateTime
import java.time.ZoneId

// IncomingCallPayload — the FCM ring contract (docs/ios-gateway-plan.md
// "C0-android") decoded with the iOS tolerance rules (CallScriptTests
// testPayload* + CallCoordinatorTests testValidDictionaryPayload…), the
// toData round-trip for Intent extras, and the CallSession derivations
// (preferredName, parseStart, minutesUntilStart).
class IncomingCallPayloadTest {
    private val callId = "0f1e2d3c-4b5a-4697-8877-665544332211"
    private val london: ZoneId = ZoneId.of("Europe/London")

    private fun contract(): Map<String, String> = mapOf(
        "kind" to "call", "callId" to callId, "taskId" to "t1", "title" to "speak to James",
        "notes" to """["A"," B ",""]""", "scheduledAt" to "2026-09-02T14:35:00.000Z",
        "deepLink" to "unstuck://call/$callId",
        "label" to "speak to James", "blockId" to "b1", "taskName" to "Speak to James",
        "startTime" to "2026-09-02T14:45:00Z", "firstAction" to "open the thread", "estimateMin" to "25",
        "captures" to """["x"]""", "name" to "Ahmad", "body" to "Unstuck is calling about speak to James",
    )

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(london).toInstant().toEpochMilli()

    @Test fun `decodes the contract shape`() {
        val p = IncomingCallPayload.fromData(contract())!!
        assertEquals(callId, p.callId)
        assertEquals("speak to James", p.label)
        assertEquals(listOf("A", "B"), p.notes)
        assertEquals("t1", p.taskId)
        assertEquals("b1", p.blockId)
        assertEquals("Speak to James", p.taskName)
        assertEquals("2026-09-02T14:45:00Z", p.startTime)
        assertEquals("open the thread", p.firstAction)
        assertEquals(25, p.estimateMin)
        assertEquals(listOf("x"), p.captures)
        assertEquals("Ahmad", p.name)
        assertEquals(1788359700000L, p.scheduledAtMs)
        assertEquals("unstuck://call/$callId", p.deepLink)
    }

    @Test fun `defaults missing arrays and blanks`() {
        val p = IncomingCallPayload.fromData(mapOf("callId" to "x", "label" to "y", "name" to "  ", "estimateMin" to "abc"))!!
        assertEquals(emptyList<String>(), p.notes)
        assertEquals(emptyList<String>(), p.captures)
        assertNull(p.name)
        assertNull(p.taskId)
        assertNull(p.estimateMin)
        assertNull(p.scheduledAtMs)
        assertEquals("unstuck://call/x", p.deepLink)
    }

    @Test fun `requires callId and a label — title stands in for label`() {
        assertNull(IncomingCallPayload.fromData(mapOf("label" to "x")))
        assertNull(IncomingCallPayload.fromData(mapOf("callId" to "x", "label" to "   ")))
        assertNull(IncomingCallPayload.fromData(mapOf("callId" to "  ", "title" to "x")))
        assertNull(IncomingCallPayload.fromData(mapOf("kind" to "reminder", "callId" to "x", "title" to "y")))
        assertNull(IncomingCallPayload.fromData(emptyMap()))
        assertNotNull(IncomingCallPayload.fromData(mapOf("callId" to "x", "label" to "y")))
        assertEquals("nested", IncomingCallPayload.fromData(mapOf("kind" to "call", "callId" to "n", "title" to "nested"))?.label)
        assertEquals("lbl", IncomingCallPayload.fromData(mapOf("callId" to "n", "title" to "ttl", "label" to "lbl"))?.label)
        assertEquals("ttl", IncomingCallPayload.fromData(mapOf("callId" to "n", "title" to "ttl", "label" to " "))?.label)
    }

    @Test fun `notes accept a JSON array or a bare line and never lose text`() {
        assertEquals(listOf("A", "B"), IncomingCallPayload.parseList("""["A","B"]"""))
        assertEquals(listOf("ask about the invoice"), IncomingCallPayload.parseList("ask about the invoice"))
        assertEquals(emptyList<String>(), IncomingCallPayload.parseList(""))
        assertEquals(emptyList<String>(), IncomingCallPayload.parseList(null))
        assertEquals(emptyList<String>(), IncomingCallPayload.parseList("""["  ",""]"""))
    }

    @Test fun `toData round-trips through fromData`() {
        val p = IncomingCallPayload.fromData(contract())!!
        assertEquals(p, IncomingCallPayload.fromData(p.toData()))
        val minimal = IncomingCallPayload(callId = "c", label = "James")
        assertEquals(minimal, IncomingCallPayload.fromData(minimal.toData()))
        val d = minimal.toData()
        assertEquals("call", d["kind"])
        assertEquals("James", d["title"])
        assertEquals("James", d["label"])
        assertFalse("empty arrays are omitted like the server does", d.containsKey("notes"))
        assertEquals("unstuck://call/c", d["deepLink"])
    }

    @Test fun `preferredName is the first token only`() {
        assertEquals("Ahmad", IncomingCallPayload(callId = "c", label = "x", name = "Ahmad Tambaya").preferredName)
        assertEquals("Ahmad", IncomingCallPayload(callId = "c", label = "x", name = "  Ahmad ").preferredName)
        assertNull(IncomingCallPayload(callId = "c", label = "x", name = "   ").preferredName)
        assertNull(IncomingCallPayload(callId = "c", label = "x").preferredName)
    }

    @Test fun `parseStart accepts ISO, clock and local forms`() {
        val anchor = ms(2026, 9, 2, 10, 0)
        assertEquals(1788356700000L, IncomingCallPayload.parseStart("2026-09-02T13:45:00Z", anchor, london))
        assertEquals(1788356700000L, IncomingCallPayload.parseStart("2026-09-02T13:45:00+00:00", anchor, london))
        assertEquals(ms(2026, 9, 2, 14, 45), IncomingCallPayload.parseStart("14:45", anchor, london))
        assertEquals(ms(2026, 9, 3, 9, 5), IncomingCallPayload.parseStart("2026-09-03 09:05", anchor, london))
        assertEquals(ms(2026, 9, 3, 9, 5), IncomingCallPayload.parseStart("2026-09-03T09:05", anchor, london))
        assertNull(IncomingCallPayload.parseStart("25:00", anchor, london))
        assertNull(IncomingCallPayload.parseStart("soon", anchor, london))
        assertNull(IncomingCallPayload.parseStart(null, anchor, london))
        assertNull(IncomingCallPayload.parseStart("2026-13-40 09:05", anchor, london))
    }

    @Test fun `minutesUntilStart rounds like Swift and is null without a start`() {
        val now = 1_800_000_000_000L
        fun at(deltaSec: Long) = IncomingCallPayload(callId = "c", label = "x", startTime = java.time.Instant.ofEpochMilli(now + deltaSec * 1000).toString())
        assertEquals(12, at(12 * 60).minutesUntilStart(now))
        assertEquals(0, at(20).minutesUntilStart(now))
        assertEquals(1, at(30).minutesUntilStart(now), "half a minute rounds up")
        assertEquals(-1, at(-30).minutesUntilStart(now), "and away from zero going down")
        assertEquals(-5, at(-5 * 60).minutesUntilStart(now))
        assertNull(IncomingCallPayload(callId = "c", label = "x").minutesUntilStart(now))
        assertTrue(IncomingCallPayload.roundHalfAwayFromZero(2.5) == 3 && IncomingCallPayload.roundHalfAwayFromZero(-2.5) == -3)
    }

    // ── callKind + endTime (calls build-out 2026-09-20, migration 072) ──

    @Test fun `callKind and endTime decode, default to requested, and round-trip`() {
        val plain = IncomingCallPayload.fromData(contract())!!
        assertNull(plain.callKind)
        assertNull(plain.endTime)
        assertEquals(CallKind.REQUESTED, plain.resolvedKind)
        assertNull("a push that named no kind round-trips without one", plain.toData()["callKind"])
        assertEquals("call", plain.toData()["kind"])
        assertEquals("requested", IncomingCallPayload.fromData(contract() + mapOf("callKind" to "requested"))!!.toData()["callKind"])

        val after = IncomingCallPayload.fromData(contract() + mapOf("callKind" to "after_block", "endTime" to "11:30"))!!
        assertEquals("after_block", after.callKind)
        assertEquals("11:30", after.endTime)
        assertEquals(CallKind.AFTER_BLOCK, after.resolvedKind)
        assertEquals(after, IncomingCallPayload.fromData(after.toData()))
        assertEquals("after_block", after.toData()["callKind"])
        assertEquals("11:30", after.toData()["endTime"])

        for (k in listOf("test", "morning", "evening")) {
            assertEquals(CallKind.fromWire(k), IncomingCallPayload.fromData(mapOf("callId" to "x", "label" to "y", "callKind" to k))!!.resolvedKind)
        }
        // Case / blanks are tolerated; garbage is requested.
        assertEquals(CallKind.MORNING, IncomingCallPayload.fromData(mapOf("callId" to "x", "label" to "y", "callKind" to " Morning "))!!.resolvedKind)
        assertEquals(CallKind.REQUESTED, IncomingCallPayload.fromData(mapOf("callId" to "x", "label" to "y", "callKind" to "lunch"))!!.resolvedKind)
        assertNull(IncomingCallPayload.fromData(mapOf("callId" to "x", "label" to "y", "callKind" to "  "))!!.callKind)
    }

    @Test fun `kind stays the push discriminator, but a server that wrote the row's kind there is accepted`() {
        assertTrue(IncomingCallPayload.isCallPush("call"))
        assertTrue(IncomingCallPayload.isCallPush(" CALL "))
        for (k in CallKind.entries) assertTrue(k.wire, IncomingCallPayload.isCallPush(k.wire))
        assertFalse(IncomingCallPayload.isCallPush("morning_brief"))
        assertFalse(IncomingCallPayload.isCallPush("reminder"))
        assertFalse(IncomingCallPayload.isCallPush(null))
        assertFalse(IncomingCallPayload.isCallPush(""))
        val p = IncomingCallPayload.fromData(mapOf("kind" to "evening", "callId" to "x", "label" to "Evening wrap-up"))!!
        assertEquals(CallKind.EVENING, p.resolvedKind)
        assertEquals("evening", p.kind)
        // Once re-encoded the discriminator is `call` again and the kind rides as callKind.
        assertEquals("call", p.toData()["kind"])
        assertEquals("evening", p.toData()["callKind"])
        assertNull(IncomingCallPayload.fromData(mapOf("kind" to "reminder", "callId" to "x", "label" to "y")))
    }

    @Test fun `spokenEnd speaks a clock end, an ISO end in the zone, and nothing for garbage`() {
        val anchor = ms(2026, 9, 2, 10, 0)
        fun p(end: String?) = IncomingCallPayload(callId = "c", label = "x", endTime = end)
        assertEquals("11:30am", p("11:30").spokenEnd(anchor, london))
        assertEquals("2pm", p("14:00").spokenEnd(anchor, london))
        assertEquals("12:05am", p("00:05").spokenEnd(anchor, london))
        assertEquals("12pm", p("12:00").spokenEnd(anchor, london))
        // ISO → local London (BST in September: 13:45Z = 14:45).
        assertEquals("2:45pm", p("2026-09-02T13:45:00Z").spokenEnd(anchor, london))
        assertEquals(ms(2026, 9, 2, 14, 45), p("2026-09-02T13:45:00Z").endMs(anchor, london))
        assertEquals("9:05am", p("2026-09-03 09:05").spokenEnd(anchor, london))
        assertNull(p("soon").spokenEnd(anchor, london))
        assertNull(p(null).spokenEnd(anchor, london))
        assertNull(p("  ").endMs(anchor, london))
    }

    private fun assertEquals(expected: Int, actual: Int?, message: String) = assertEquals(message, expected, actual)
}
