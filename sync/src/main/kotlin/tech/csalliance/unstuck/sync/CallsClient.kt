package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

// CallsClient — "Unstuck calls you" (android-gateway-plan Part C). The client
// half of the call_requests contract: the owner-RLS table the user books calls
// into (from the assistant's request_call / cancel_call / update_call /
// get_calls, and later the task editor's "Call me about this"), plus the
// `call-outcome` edge fn the ring path reports every end state to. 1:1 with
// iOS UnstuckSync/CallsClient.swift.
//
// Server contract (migrations 051 + 053):
//   public.call_requests(id, user_id, task_id?, block_id?, call_at timestamptz,
//     lead_min?, label, notes text[], status, snooze_until, outcome_notes text[],
//     call_id, attempts, created_at, updated_at)
//   status: scheduled|calling|answered|declined|missed|busy|snoozed|stale|cancelled|done
//   call-outcome (user JWT): { callId, outcome, snoozeMinutes?, outcomeNotes?[] }
//
// Writes that can lose a race (update / cancel) are compare-and-set on the row
// still being LIVE and return the row the server actually wrote — null means
// zero rows matched (the call was cancelled / rang / finished underneath the
// caller), and callers must say so rather than echo stale state.

/** What the phone reports back after a call attempt (call-outcome `outcome`). */
enum class CallOutcome(val wire: String) {
    ANSWERED("answered"), DECLINED("declined"), MISSED("missed"), BUSY("busy"),
    SNOOZED("snoozed"), DONE("done"), STALE("stale"),
}

/** A `call_requests` row as the client reads it. Tolerant decoding: array
 *  columns default to `[]`, so a row written by another platform without notes
 *  still loads. Every field with a default is ALSO emitted on encode only when
 *  the writer builds the JSON by hand (see [CallsClient.create]) — the
 *  kotlinx default-omission rule means this type is a READ shape. */
@Serializable
data class CallRequest(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("block_id") val blockId: String? = null,
    /** ISO-8601 timestamptz (absolute; the server re-derives it from the block
     *  when `leadMin` is set and the block moves). */
    @SerialName("call_at") val callAt: String,
    @SerialName("lead_min") val leadMin: Int? = null,
    val label: String = "",
    val notes: List<String> = emptyList(),
    val status: String = "scheduled",
    @SerialName("snooze_until") val snoozeUntil: String? = null,
    @SerialName("outcome_notes") val outcomeNotes: List<String> = emptyList(),
    @SerialName("call_id") val callId: String? = null,
    val attempts: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    companion object {
        /** Web parity (lib/calls/types.ts LIVE_CALL_STATUSES): a call that is
         *  ringing right now still "is coming" — get_calls lists it ("· ringing
         *  now") and it counts as a duplicate anchor. */
        val liveStatuses = listOf("scheduled", "snoozed", "calling")
        /** Rows whose NOTES / LABEL may still change: the live ones plus a call
         *  that has been ANSWERED and is in progress. Its TIME may not move. */
        val editableStatuses = listOf("scheduled", "snoozed", "calling", "answered")
        /** Rows whose TIME may move. A ringing / answered call is NOT one of them:
         *  re-arming it to `scheduled` would be overwritten by the phone's own
         *  outcome report a moment later. */
        val reschedulableStatuses = listOf("scheduled", "snoozed")
    }

    /** `callAt` as epoch ms (null if the server sent something unparseable). */
    val callAtMs: Long? get() = CallsClient.parseIsoMs(callAt)
    /** The effective next ring time: `snoozeUntil` when snoozed, else `callAt`. */
    val effectiveAtMs: Long? get() =
        if (status == "snoozed") snoozeUntil?.let { CallsClient.parseIsoMs(it) } ?: callAtMs else callAtMs
    val isLive: Boolean get() = status in liveStatuses
    /** Notes / label may still change (live, or answered and in progress). */
    val isEditable: Boolean get() = status in editableStatuses
    /** The call is ringing or in progress right now: notes/label edits land,
     *  a time change is refused. */
    val isInProgress: Boolean get() = status == "calling" || status == "answered"
}

/** call-outcome refused a report FOR GOOD (a 4xx other than 401 / 408 / 429):
 *  retrying can never succeed, so a reporter drops the item. */
class CallOutcomeRejected(val status: Int, override val message: String?) : Exception(message) {
    companion object {
        fun isPermanent(status: Int): Boolean = status in 400..499 && status !in setOf(401, 408, 429)
    }
}

class CallsClient(private val client: SupabaseClient) {

    companion object {
        private const val TABLE = "call_requests"
        private val ISO_MS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /** ISO-8601 with fractional seconds, UTC — the form every other writer uses. */
        fun iso(epochMs: Long): String = ISO_MS.format(Instant.ofEpochMilli(epochMs))

        /** Parse a server timestamptz. PostgREST emits `2026-09-02T14:45:00+00:00`
         *  (no fractional seconds, `+00:00` offset); the fractional `Z` form covers
         *  rows we wrote ourselves. Null when neither parses. */
        fun parseIsoMs(s: String): Long? =
            try { Instant.parse(s).toEpochMilli() } catch (_: DateTimeParseException) {
                try { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (_: DateTimeParseException) { null }
            }

        /** The compare-and-set status list for an [update]: the reschedulable
         *  rows when the patch moves the call, the editable rows otherwise. */
        fun statusesAccepting(timeChange: Boolean): List<String> =
            if (timeChange) CallRequest.reschedulableStatuses else CallRequest.editableStatuses

        /** The `call_requests` upsert row for [create] — built as a JsonObject on
         *  purpose so nullable columns are sent explicitly (kotlinx would drop a
         *  default-valued field and the server would keep a stale value). */
        fun createRow(
            id: String, userId: String, taskId: String?, blockId: String?, callAtMs: Long,
            leadMin: Int?, label: String, notes: List<String>, nowMs: Long,
        ): JsonObject = buildJsonObject {
            put("id", id)
            put("user_id", userId)
            put("task_id", taskId?.let { JsonPrimitive(it) } ?: JsonNull)
            put("block_id", blockId?.let { JsonPrimitive(it) } ?: JsonNull)
            put("call_at", iso(callAtMs))
            put("lead_min", leadMin?.let { JsonPrimitive(it) } ?: JsonNull)
            put("label", label)
            put("notes", JsonArray(notes.map { JsonPrimitive(it) }))
            put("status", "scheduled")
            put("updated_at", iso(nowMs))
        }

        /** The PATCH for [update]. `blockId` / `leadMin` use a present-vs-absent
         *  wrapper: absent = leave the column alone, present-null = clear it. */
        fun updatePatch(
            callAtMs: Long?, blockId: Patch<String?>?, leadMin: Patch<Int?>?,
            label: String?, notes: List<String>?, nowMs: Long,
        ): JsonObject = buildJsonObject {
            put("updated_at", iso(nowMs))
            if (callAtMs != null) {
                put("call_at", iso(callAtMs))
                put("status", "scheduled")
                put("snooze_until", JsonNull)
            }
            if (blockId != null) put("block_id", blockId.value?.let { JsonPrimitive(it) } ?: JsonNull)
            if (leadMin != null) put("lead_min", leadMin.value?.let { JsonPrimitive(it) } ?: JsonNull)
            if (label != null) put("label", label)
            if (notes != null) put("notes", JsonArray(notes.map { JsonPrimitive(it) }))
        }
    }

    /** "Set this column to [value] (null clears it)" — distinct from "leave it". */
    data class Patch<T>(val value: T)

    // ── outcome (the ring path → call-outcome edge fn) ──────────────────────

    @Serializable
    private data class OutcomeBody(
        val callId: String,
        val outcome: String,
        val snoozeMinutes: Int? = null,
        val outcomeNotes: List<String>? = null,
    )

    /** Report how a call ended. Throws [CallOutcomeRejected] for a PERMANENT
     *  refusal so the reporter can drop the item; every other failure (transport,
     *  5xx, 401 refresh, 429) is rethrown as-is → retry. */
    suspend fun outcome(callId: String, outcome: CallOutcome, snoozeMinutes: Int? = null, outcomeNotes: List<String>? = null) {
        try {
            client.functions.invoke("call-outcome") {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(OutcomeBody(callId, outcome.wire, snoozeMinutes, outcomeNotes))
            }
        } catch (e: io.github.jan.supabase.exceptions.RestException) {
            val code = e.statusCode
            if (CallOutcomeRejected.isPermanent(code)) throw CallOutcomeRejected(code, e.message)
            throw e
        }
    }

    // ── reads ───────────────────────────────────────────────────────────────

    /** The user's calls. `upcoming` (default) = status in scheduled/snoozed/calling,
     *  soonest first; otherwise the 50 most recent rows of any status. */
    suspend fun list(upcoming: Boolean = true): List<CallRequest> =
        if (upcoming) {
            client.from(TABLE).select {
                filter { isIn("status", CallRequest.liveStatuses) }
                order("call_at", Order.ASCENDING)
            }.decodeList()
        } else {
            client.from(TABLE).select {
                order("call_at", Order.DESCENDING)
                limit(50)
            }.decodeList()
        }

    suspend fun get(id: String): CallRequest? =
        client.from(TABLE).select {
            filter { eq("id", id) }
            limit(1)
        }.decodeList<CallRequest>().firstOrNull()

    /** The live (scheduled/snoozed/calling) call anchored to a task, if any. */
    suspend fun forTask(taskId: String): CallRequest? =
        client.from(TABLE).select {
            filter { eq("task_id", taskId); isIn("status", CallRequest.liveStatuses) }
            order("call_at", Order.ASCENDING)
            limit(1)
        }.decodeList<CallRequest>().firstOrNull()

    // ── writes ──────────────────────────────────────────────────────────────

    /** Book a call (upsert on id). Returns the row as stored. */
    suspend fun create(
        id: String, userId: String, taskId: String? = null, blockId: String? = null,
        callAtMs: Long, leadMin: Int? = null, label: String, notes: List<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): CallRequest {
        val row = createRow(id, userId, taskId, blockId, callAtMs, leadMin, label, notes, nowMs)
        val rows: List<CallRequest> = client.from(TABLE).upsert(row) {
            onConflict = "id"
            select()
        }.decodeList()
        return rows.firstOrNull() ?: CallRequest(
            id = id, userId = userId, taskId = taskId, blockId = blockId, callAt = iso(callAtMs),
            leadMin = leadMin, label = label, notes = notes,
        )
    }

    /** Patch a booked call — only the given fields change. Re-arms a snoozed
     *  row back to `scheduled` when its time is moved. Compare-and-set on the
     *  row's status (see [statusesAccepting]). null ⇒ nothing was written (it
     *  changed underneath, or the time change was refused). */
    suspend fun update(
        id: String, callAtMs: Long? = null, blockId: Patch<String?>? = null, leadMin: Patch<Int?>? = null,
        label: String? = null, notes: List<String>? = null, nowMs: Long = System.currentTimeMillis(),
    ): CallRequest? {
        val patch = updatePatch(callAtMs, blockId, leadMin, label, notes, nowMs)
        val timeChange = callAtMs != null || blockId != null || leadMin != null
        val rows: List<CallRequest> = client.from(TABLE).update(patch) {
            select()
            filter { eq("id", id); isIn("status", statusesAccepting(timeChange)) }
        }.decodeList()
        return rows.firstOrNull()
    }

    /** Cancel a booked call (status → cancelled; the row stays for history).
     *  Compare-and-set on the row still being live: the cancelled row, or null
     *  when zero rows matched (already cancelled / rang / done elsewhere). */
    suspend fun cancel(id: String, nowMs: Long = System.currentTimeMillis()): CallRequest? {
        val patch = buildJsonObject { put("status", "cancelled"); put("updated_at", iso(nowMs)) }
        val rows: List<CallRequest> = client.from(TABLE).update(patch) {
            select()
            filter { eq("id", id); isIn("status", CallRequest.liveStatuses) }
        }.decodeList()
        return rows.firstOrNull()
    }
}
