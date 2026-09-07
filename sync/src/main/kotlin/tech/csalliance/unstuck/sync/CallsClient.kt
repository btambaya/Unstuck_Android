package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.flow.Flow
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables
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
// caller), and callers must say so rather than echo stale state. A returned row
// is not proof the write LANDED: 053's `call_requests_guard_stale_write` can
// revert `status` / `snooze_until` inside the same statement, so both writes
// check the row against what they asked for ([reflectsWrite]) and neither sends
// a device-clock `updated_at` any more (the column is the server's — see
// updatePatch). Without that a lagging phone clock produced "ok: cancelled the
// call" for a call that then rang anyway.
//
// LOCAL MIRROR (C1-android): `Tables.CALL_REQUESTS` is hydrated + realtime-mirrored
// READ-ONLY into the LocalStore (Hydrator / RealtimeMirror). Given a
// [CallRequestsMirror], the reads below (`list` / `get` / `forTask`) come from it —
// offline-safe, no round trip for get_calls / the task editor — and every write
// still goes DIRECT to PostgREST (never the outbox: 053's BEFORE UPDATE guard owns
// status) with the returned row absorbed into the mirror at once, so a booking is
// visible locally before its realtime echo lands. Without a mirror the reads hit
// the network exactly as before.

/** What the phone reports back after a call attempt (call-outcome `outcome`) is
 *  :core's `CallOutcome` — the ring path, the voice service and the durable
 *  outcome queue all report through it, so there is ONE type with ONE set of
 *  wire strings. Aliased here so every `tech.csalliance.unstuck.sync.CallOutcome`
 *  import keeps resolving. */
typealias CallOutcome = tech.csalliance.unstuck.core.logic.CallOutcome

/** The `call_requests` row shape is :core's `CallRequest` (core/model/CallRequest.kt
 *  — tolerant decode, the status families, callAtMs / effectiveAtMs / isLive /
 *  isEditable / isInProgress). Aliased here so every existing
 *  `tech.csalliance.unstuck.sync.CallRequest` import (the assistant's call
 *  tools, the task editor, the mirror) keeps resolving; there is ONE row type. */
typealias CallRequest = tech.csalliance.unstuck.core.model.CallRequest

/** call-outcome refused a report FOR GOOD (a 4xx other than 401 / 408 / 429):
 *  retrying can never succeed, so a reporter drops the item. */
class CallOutcomeRejected(val status: Int, override val message: String?) : Exception(message) {
    companion object {
        fun isPermanent(status: Int): Boolean = status in 400..499 && status !in setOf(401, 408, 429)
    }
}

/** The read side of the local `call_requests` mirror (see the file header). Pure
 *  LocalStore reads + the absorb rule (last-write-wins by `updated_at`, like the
 *  realtime path) — no network, unit-tested with an in-memory Room store. */
class CallRequestsMirror(private val store: LocalStore) {
    suspend fun all(): List<CallRequest> = store.snapshot(Tables.CALL_REQUESTS, CallRequest.serializer())
    /** Live rows (scheduled / snoozed / calling), soonest first. */
    suspend fun live(): List<CallRequest> = all().filter { it.isLive }.sortedBy { it.effectiveAtMs ?: Long.MAX_VALUE }
    suspend fun get(id: String): CallRequest? = store.getOne(Tables.CALL_REQUESTS, id, CallRequest.serializer())
    /** The live call anchored to a task, soonest first, if any. */
    suspend fun forTask(taskId: String): CallRequest? = live().firstOrNull { it.taskId == taskId }
    fun observe(): Flow<List<CallRequest>> = store.observeTable(Tables.CALL_REQUESTS, CallRequest.serializer())
    /** Write a row the SERVER just returned into the mirror (skipped when the local
     *  copy is strictly newer — a realtime echo may have overtaken the response). */
    suspend fun absorb(row: CallRequest) {
        store.upsertIfNewer(Tables.CALL_REQUESTS, row, CallRequest.serializer(), row.id, row.updatedAt)
    }
}

class CallsClient(private val client: SupabaseClient, private val mirror: CallRequestsMirror? = null) {

    companion object {
        private const val TABLE = "call_requests"

        /** The `outcome` values call-outcome accepts (its OUTCOMES set, verbatim). */
        val SERVER_OUTCOMES: Set<String> = setOf("answered", "declined", "missed", "busy", "snoozed", "done", "stale")

        /** The wire outcome + note for a client-side end state. The server only
         *  knows the seven [SERVER_OUTCOMES]; the phone's richer decisions are
         *  folded exactly as iOS does — outside call hours ends the row as
         *  `declined` (CallCoordinator.reportIncoming), a voice failure as `done`
         *  with the note "voice failed" (CallCoordinator.performEnd). Any other
         *  string is a programming error and is REJECTED here (null) rather than
         *  sent to be refused with a 400. */
        fun normalizeOutcome(raw: String): Pair<String, List<String>?>? {
            val o = raw.trim().lowercase()
            return when {
                o in SERVER_OUTCOMES -> o to null
                o == "outside_hours" -> "declined" to null
                o == "voice_failed" -> "done" to listOf("voice failed")
                else -> null
            }
        }

        /** The call-outcome POST body. Built as a JsonObject on purpose: kotlinx
         *  omits default-valued fields, and while a missing `snoozeMinutes` would
         *  merely default to 10 server-side, a body that silently loses a field is
         *  exactly the class of bug this codebase keeps hitting — so every present
         *  value is written explicitly and the absent ones are genuinely absent. */
        fun outcomeBody(callId: String, outcome: String, snoozeMinutes: Int?, outcomeNotes: List<String>?): JsonObject = buildJsonObject {
            put("callId", callId)
            put("outcome", outcome)
            if (snoozeMinutes != null) put("snoozeMinutes", snoozeMinutes)
            if (!outcomeNotes.isNullOrEmpty()) put("outcomeNotes", JsonArray(outcomeNotes.map { JsonPrimitive(it) }))
        }
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
            tech.csalliance.unstuck.core.model.CallStatus.wiresAccepting(timeChange)

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

        /**
         * The PATCH for [update]. `blockId` / `leadMin` use a present-vs-absent
         * wrapper: absent = leave the column alone, present-null = clear it.
         *
         * `updated_at` is DELIBERATELY absent. The column is server-owned
         * (051's `touch_call_requests` stamps `now()` on every update), and 053's
         * `call_requests_guard_stale_write` SILENTLY reverts `status` /
         * `snooze_until` when the incoming row carries a timestamp older than the
         * one already stored — so a phone whose clock lags by a minute would have
         * its reschedule quietly undone while PostgREST still returned a row and
         * the tool still answered "ok". Sending nothing leaves
         * `new.updated_at = old.updated_at`, which the guard passes; [update] then
         * VERIFIES the returned row rather than trusting it.
         */
        fun updatePatch(
            callAtMs: Long?, blockId: Patch<String?>?, leadMin: Patch<Int?>?,
            label: String?, notes: List<String>?,
        ): JsonObject = buildJsonObject {
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

        /**
         * Did the row the server returned actually TAKE the write? PostgREST
         * returns the row whenever the WHERE matched, but 053's BEFORE UPDATE
         * guard may have reverted `status` / `snooze_until` inside the same
         * statement (a client timestamp older than the row's), and the block-follow
         * step of `dispatch_calls` rewrites rows underneath us. Callers treat
         * `false` exactly like "zero rows matched" — CHANGED_UNDERNEATH — rather
         * than echoing a cancel / reschedule that did not happen.
         *
         * Only the guard's two columns are checked: `label` / `notes` are never
         * touched by it, and a call whose time is not moving does not claim either.
         */
        fun reflectsWrite(row: CallRequest, cancelled: Boolean = false, timeMoved: Boolean = false): Boolean = when {
            cancelled -> row.status == tech.csalliance.unstuck.core.model.CallStatus.CANCELLED.wire
            timeMoved -> row.status == tech.csalliance.unstuck.core.model.CallStatus.SCHEDULED.wire && row.snoozeUntil == null
            else -> true
        }
    }

    /** "Set this column to [value] (null clears it)" — distinct from "leave it". */
    data class Patch<T>(val value: T)

    // ── outcome (the ring path → call-outcome edge fn) ──────────────────────

    /** Report how a call ended. Throws [CallOutcomeRejected] for a PERMANENT
     *  refusal so the reporter can drop the item; every other failure (transport,
     *  5xx, 401 refresh, 429) is rethrown as-is → retry. */
    suspend fun outcome(callId: String, outcome: CallOutcome, snoozeMinutes: Int? = null, outcomeNotes: List<String>? = null) {
        postOutcome(outcomeBody(callId, outcome.wire, snoozeMinutes, outcomeNotes))
    }

    /** The C1 ring path's reporter entry point (CallOutcomeStore.flush): [outcome]
     *  is the wire string of the app's `CallOutcome` — the seven server values, or
     *  the two client-side ones (`outside_hours` / `voice_failed`) which are folded
     *  by [normalizeOutcome]. Never throws:
     *   - success → `Result.success(Unit)`;
     *   - a PERMANENT refusal (4xx other than 401/408/429, or an outcome string the
     *     server can never accept) → `Result.failure(CallOutcomeRejected)` — drop it;
     *   - anything else (offline, 5xx, 401 refresh, 429) → `Result.failure(other)` — retry. */
    suspend fun reportOutcome(callId: String, outcome: String, snoozeMin: Int? = null, outcomeNotes: List<String>? = null): Result<Unit> {
        val (wire, folded) = normalizeOutcome(outcome)
            ?: return Result.failure(CallOutcomeRejected(400, "unknown outcome \"$outcome\""))
        val notes = (folded.orEmpty() + outcomeNotes.orEmpty()).takeIf { it.isNotEmpty() }
        return try {
            postOutcome(outcomeBody(callId, wire, if (wire == "snoozed") snoozeMin else null, notes))
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private suspend fun postOutcome(body: JsonObject) {
        try {
            client.functions.invoke("call-outcome") {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: io.github.jan.supabase.exceptions.RestException) {
            val code = e.statusCode
            if (CallOutcomeRejected.isPermanent(code)) throw CallOutcomeRejected(code, e.message)
            throw e
        }
    }

    // ── reads ───────────────────────────────────────────────────────────────

    /** The user's calls. `upcoming` (default) = status in scheduled/snoozed/calling,
     *  soonest first; otherwise the 50 most recent rows of any status. From the
     *  local mirror when one is attached (offline-safe), else PostgREST. */
    suspend fun list(upcoming: Boolean = true): List<CallRequest> =
        if (mirror != null) {
            if (upcoming) mirror.live() else mirror.all().sortedByDescending { it.callAtMs ?: Long.MIN_VALUE }.take(50)
        } else if (upcoming) {
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
        mirror?.get(id) ?: client.from(TABLE).select {
            filter { eq("id", id) }
            limit(1)
        }.decodeList<CallRequest>().firstOrNull()

    /** The live (scheduled/snoozed/calling) call anchored to a task, if any. */
    suspend fun forTask(taskId: String): CallRequest? =
        if (mirror != null) mirror.forTask(taskId) else client.from(TABLE).select {
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
        val stored = rows.firstOrNull() ?: CallRequest(
            id = id, userId = userId, taskId = taskId, blockId = blockId, callAt = iso(callAtMs),
            leadMin = leadMin, label = label, notes = notes, updatedAt = iso(nowMs),
        )
        mirror?.absorb(stored)
        return stored
    }

    /** Patch a booked call — only the given fields change. Re-arms a snoozed
     *  row back to `scheduled` when its time is moved. Compare-and-set on the
     *  row's status (see [statusesAccepting]) AND on what the server actually
     *  stored ([reflectsWrite]). null ⇒ nothing was written: zero rows matched,
     *  or the row changed underneath and the time change was reverted. */
    suspend fun update(
        id: String, callAtMs: Long? = null, blockId: Patch<String?>? = null, leadMin: Patch<Int?>? = null,
        label: String? = null, notes: List<String>? = null,
    ): CallRequest? {
        val patch = updatePatch(callAtMs, blockId, leadMin, label, notes)
        val timeChange = callAtMs != null || blockId != null || leadMin != null
        val rows: List<CallRequest> = client.from(TABLE).update(patch) {
            select()
            filter { eq("id", id); isIn("status", statusesAccepting(timeChange)) }
        }.decodeList()
        val row = rows.firstOrNull() ?: return null
        // Absorb either way: the truth the server sent is what get_calls must show,
        // even (especially) when it isn't what we asked for.
        mirror?.absorb(row)
        return row.takeIf { reflectsWrite(it, timeMoved = callAtMs != null) }
    }

    /** Cancel a booked call (status → cancelled; the row stays for history).
     *  Compare-and-set on the row still being live AND on the row coming back
     *  actually cancelled: the cancelled row, or null when zero rows matched
     *  (already cancelled / rang / done elsewhere) or the server kept its own
     *  status (053's stale-write guard) — a call that will still ring must never
     *  be reported as cancelled. */
    suspend fun cancel(id: String): CallRequest? {
        val patch = buildJsonObject { put("status", "cancelled") }
        val rows: List<CallRequest> = client.from(TABLE).update(patch) {
            select()
            filter { eq("id", id); isIn("status", CallRequest.liveStatuses) }
        }.decodeList()
        val row = rows.firstOrNull() ?: return null
        mirror?.absorb(row)
        return row.takeIf { reflectsWrite(it, cancelled = true) }
    }
}
