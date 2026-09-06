package tech.csalliance.unstuck.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire half of CallsClient that needs no network: the call_requests row
 * decode (tolerant, other-platform rows), the compare-and-set status lists, the
 * upsert row / patch shapes (explicit nulls — kotlinx would drop a defaulted
 * field and the server would keep a stale value) and the timestamp parsing.
 * Mirrors iOS CallsClientWireTest.
 */
class CallsClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `a PostgREST row decodes, array columns default to empty`() {
        val row = json.decodeFromString<CallRequest>(
            """{"id":"c1","user_id":"u","task_id":null,"block_id":null,"call_at":"2026-09-02T14:45:00+00:00","lead_min":null,"label":"speak to James","status":"scheduled","attempts":0,"extra":1}""",
        )
        assertEquals("c1", row.id)
        assertEquals(emptyList<String>(), row.notes)
        assertEquals(emptyList<String>(), row.outcomeNotes)
        assertTrue(row.isLive); assertTrue(row.isEditable); assertFalse(row.isInProgress)
        assertEquals(1_788_360_300_000L, row.callAtMs)
    }

    @Test fun `status families - live, editable, reschedulable, in progress`() {
        fun r(status: String, snooze: String? = null) = CallRequest(id = "x", callAt = "2026-09-02T14:45:00.000Z", label = "l", status = status, snoozeUntil = snooze)
        assertTrue(r("calling").isLive)
        assertFalse(r("answered").isLive)
        assertTrue(r("answered").isEditable)
        assertTrue(r("answered").isInProgress)
        assertTrue(r("calling").isInProgress)
        assertFalse(r("cancelled").isEditable)
        assertEquals(CallRequest.reschedulableStatuses, CallsClient.statusesAccepting(timeChange = true))
        assertEquals(CallRequest.editableStatuses, CallsClient.statusesAccepting(timeChange = false))
        // A snoozed row rings at snooze_until, not call_at.
        val snoozed = r("snoozed", "2026-09-02T15:05:00.000Z")
        assertEquals(CallsClient.parseIsoMs("2026-09-02T15:05:00.000Z"), snoozed.effectiveAtMs)
        assertEquals(r("scheduled").callAtMs, r("scheduled").effectiveAtMs)
    }

    @Test fun `parseIsoMs accepts both the fractional Z form and the PostgREST offset form`() {
        assertEquals(1_788_360_300_000L, CallsClient.parseIsoMs("2026-09-02T14:45:00.000Z"))
        assertEquals(1_788_360_300_000L, CallsClient.parseIsoMs("2026-09-02T14:45:00+00:00"))
        assertNull(CallsClient.parseIsoMs("yesterday"))
        assertEquals("2026-09-02T14:45:00.000Z", CallsClient.iso(1_788_360_300_000L))
    }

    @Test fun `the create row sends every column explicitly, nulls included`() {
        val row = CallsClient.createRow(
            id = "c1", userId = "u", taskId = null, blockId = null, callAtMs = 1_788_360_300_000L,
            leadMin = null, label = "speak to James", notes = listOf("ask about the deck"), nowMs = 1_756_820_000_000L,
        )
        assertEquals("c1", row["id"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, row["task_id"])
        assertEquals(JsonNull, row["lead_min"])
        assertEquals("2026-09-02T14:45:00.000Z", row["call_at"]!!.jsonPrimitive.content)
        assertEquals("scheduled", row["status"]!!.jsonPrimitive.content)
        assertEquals("[\"ask about the deck\"]", row["notes"].toString())
        val anchored = CallsClient.createRow("c2", "u", "t1", "b1", 0L, 15, "Board prep", emptyList(), 0L)
        assertEquals("t1", anchored["task_id"]!!.jsonPrimitive.content)
        assertEquals(15, anchored["lead_min"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun `the update patch touches only what changed and re-arms a moved call`() {
        val notesOnly = CallsClient.updatePatch(null, null, null, null, listOf("bring the contract"), 0L)
        assertEquals(setOf("updated_at", "notes"), notesOnly.keys)
        val moved = CallsClient.updatePatch(1_788_360_300_000L, CallsClient.Patch(null), CallsClient.Patch(null), null, null, 0L)
        assertEquals("scheduled", moved["status"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, moved["snooze_until"])
        assertEquals(JsonNull, moved["block_id"])
        assertEquals(JsonNull, moved["lead_min"])
        assertEquals("2026-09-02T14:45:00.000Z", moved["call_at"]!!.jsonPrimitive.content)
        val reanchored = CallsClient.updatePatch(0L, CallsClient.Patch("b2"), CallsClient.Patch(10), "new label", null, 0L)
        assertEquals("b2", reanchored["block_id"]!!.jsonPrimitive.content)
        assertEquals("10", reanchored["lead_min"]!!.jsonPrimitive.content)
        assertEquals("new label", reanchored["label"]!!.jsonPrimitive.content)
    }

    @Test fun `call-outcome permanence - 4xx minus 401 408 429`() {
        assertTrue(CallOutcomeRejected.isPermanent(404))
        assertTrue(CallOutcomeRejected.isPermanent(422))
        assertFalse(CallOutcomeRejected.isPermanent(401))
        assertFalse(CallOutcomeRejected.isPermanent(429))
        assertFalse(CallOutcomeRejected.isPermanent(500))
    }
}
