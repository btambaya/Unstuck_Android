package tech.csalliance.unstuck.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import tech.csalliance.unstuck.core.time.Time

// CallRequest — a `public.call_requests` row ("Unstuck calls you", migrations
// 051 + 053 + 058 + 072), 1:1 with iOS UnstuckSync/CallsClient.swift `CallRequest`
// and the web lib/calls/types.ts. Lives in :core so the sync mirror
// (Tables.CALL_REQUESTS hydrate + realtime), the assistant's get_calls, the
// task editor's "Call me about this" and the ring path all share ONE row
// shape. Pure Kotlin — no Android, no Supabase.
//
// Server contract:
//   call_requests(id, user_id, task_id?, block_id?, call_at timestamptz,
//     lead_min?, label, notes text[], status, snooze_until, outcome_notes text[],
//     call_id, attempts, kind, retries, created_at, updated_at)
//   status: scheduled|calling|answered|declined|missed|busy|snoozed|stale|cancelled|done
//   kind (072): requested|test|morning|evening|after_block — who booked it; the
//     phone's script opens differently per kind ([CallKind]).
//   retries (072): automatic re-rings after a miss (0 = the next miss earns one,
//     1 = spent). Server-owned like `attempts`.
//
// READ shape: every optional column defaults, array columns tolerate an
// explicit JSON `null` (PostgREST emits `"outcome_notes":null` for a row that
// never had notes) — see [StringListOrEmpty]. The kotlinx default-omission
// rule means writers build their JSON by hand (CallsClient.createRow /
// updatePatch) instead of encoding this type. Decode with a Json that has
// `ignoreUnknownKeys = true` (a future column must not break the mirror).

/** The `status` column. Wire strings are the 051 CHECK constraint verbatim. */
@Serializable
enum class CallStatus(val wire: String) {
    @SerialName("scheduled") SCHEDULED("scheduled"),
    @SerialName("calling") CALLING("calling"),
    @SerialName("answered") ANSWERED("answered"),
    @SerialName("declined") DECLINED("declined"),
    @SerialName("missed") MISSED("missed"),
    @SerialName("busy") BUSY("busy"),
    @SerialName("snoozed") SNOOZED("snoozed"),
    @SerialName("stale") STALE("stale"),
    @SerialName("cancelled") CANCELLED("cancelled"),
    @SerialName("done") DONE("done");

    /** Web parity (LIVE_CALL_STATUSES): the call "is coming" — get_calls lists
     *  it and it counts as a duplicate anchor. A ringing call is still live. */
    val isLive: Boolean get() = this in LIVE
    /** Notes / label may still change (live, or answered and in progress). */
    val isEditable: Boolean get() = this in EDITABLE
    /** The TIME may move (never a ringing / answered call — see [RESCHEDULABLE]). */
    val isReschedulable: Boolean get() = this in RESCHEDULABLE
    /** Ringing or in progress right now: notes/label edits land, a time change is refused. */
    val isInProgress: Boolean get() = this == CALLING || this == ANSWERED
    /** A terminal state the USER chose — call-outcome leaves it alone. */
    val isTerminal: Boolean get() = this == CANCELLED || this == DONE

    companion object {
        /** Web parity (lib/calls/types.ts LIVE_CALL_STATUSES). */
        val LIVE: List<CallStatus> = listOf(SCHEDULED, SNOOZED, CALLING)
        /** Rows whose NOTES / LABEL may still change: the live ones plus a call
         *  that has been ANSWERED and is in progress — "add 'bring the contract'
         *  to the notes and call me back in 20" edits the row mid-conversation.
         *  Its TIME may not move (see [RESCHEDULABLE]). */
        val EDITABLE: List<CallStatus> = listOf(SCHEDULED, SNOOZED, CALLING, ANSWERED)
        /** Rows whose TIME may move. A ringing / answered call is NOT one of
         *  them: re-arming it to `scheduled` would be overwritten by the phone's
         *  own outcome report a moment later (missed / done), silently
         *  discarding the reschedule the tool just confirmed. */
        val RESCHEDULABLE: List<CallStatus> = listOf(SCHEDULED, SNOOZED)

        /** The compare-and-set status list for an update: the reschedulable
         *  rows when the patch moves the call, the editable rows otherwise.
         *  Mirrors iOS `CallsClient.statusesAccepting(timeChange:)`. */
        fun statusesAccepting(timeChange: Boolean): List<CallStatus> =
            if (timeChange) RESCHEDULABLE else EDITABLE

        /** [statusesAccepting] as the wire strings a PostgREST `in` filter takes. */
        fun wiresAccepting(timeChange: Boolean): List<String> = statusesAccepting(timeChange).map { it.wire }

        /** Decode a wire string; null for anything unknown (never guess). */
        fun fromWire(value: String?): CallStatus? = entries.firstOrNull { it.wire == value }
    }
}

/** The `kind` column (072) — who booked the call. Wire strings are the CHECK
 *  constraint verbatim; the same vocabulary rides the ring push as `callKind`
 *  (IncomingCallPayload.resolvedKind). 1:1 with iOS `CallKind`. */
enum class CallKind(val wire: String) {
    /** The user asked — the assistant's request_call, the task editor's "Call me about this". */
    REQUESTED("requested"),
    /** Settings › Calls "Test call now". Never retried after a miss. */
    TEST("test"),
    /** The opt-in morning planning call (dispatch_proactive_calls). */
    MORNING("morning"),
    /** The opt-in evening wrap-up call. */
    EVENING("evening"),
    /** The opt-in check-in after a block that ended without its task marked done. */
    AFTER_BLOCK("after_block");

    companion object {
        /** Tolerant: null / blank / unknown → [REQUESTED], the only kind there
         *  was before 072 (a row or a push from an older server). */
        fun fromWire(raw: String?): CallKind = strict(raw) ?: REQUESTED

        /** The kind for a known wire string, else null (unknown is NOT requested here). */
        fun strict(raw: String?): CallKind? {
            val t = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == t }
        }
    }
}

/** A `text[]` column that PostgREST may emit as JSON `null` → `[]`. Elements
 *  that are not strings are dropped rather than failing the whole row. */
object StringListOrEmpty : KSerializer<List<String>> {
    private val delegate = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: List<String>) = delegate.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): List<String> {
        val jd = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val el = jd.decodeJsonElement()
        if (el is JsonNull) return emptyList()
        return runCatching { el.jsonArray.mapNotNull { it.jsonPrimitive.takeIf { p -> p.isString }?.content } }
            .getOrDefault(emptyList())
    }
}

/** A `call_requests` row as the client reads it. `status` stays a String on
 *  the wire (a server-added status must not break decoding); [statusEnum]
 *  is the typed view. */
@Serializable
data class CallRequest(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("block_id") val blockId: String? = null,
    /** ISO-8601 timestamptz (absolute; the server re-derives it from the block
     *  when [leadMin] is set and the block moves). */
    @SerialName("call_at") val callAt: String,
    @SerialName("lead_min") val leadMin: Int? = null,
    val label: String = "",
    @Serializable(with = StringListOrEmpty::class) val notes: List<String> = emptyList(),
    val status: String = CallStatus.SCHEDULED.wire,
    @SerialName("snooze_until") val snoozeUntil: String? = null,
    @SerialName("outcome_notes") @Serializable(with = StringListOrEmpty::class) val outcomeNotes: List<String> = emptyList(),
    /** The phone-side call id (iOS: the CXCall UUID; Android: omitted). */
    @SerialName("call_id") val callId: String? = null,
    val attempts: Int? = null,
    /** Who booked it (072); a pre-072 row decodes as `requested`. Stays a String
     *  on the wire like [status]; [kindEnum] is the typed view. */
    val kind: String = CallKind.REQUESTED.wire,
    /** Automatic re-rings spent after a miss (072); null from a pre-072 server. */
    val retries: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    companion object {
        val liveStatuses: List<String> = CallStatus.LIVE.map { it.wire }
        val editableStatuses: List<String> = CallStatus.EDITABLE.map { it.wire }
        val reschedulableStatuses: List<String> = CallStatus.RESCHEDULABLE.map { it.wire }
    }

    /** The typed status, or null for a status this build does not know. */
    val statusEnum: CallStatus? get() = CallStatus.fromWire(status)
    /** The typed kind — unknown / blank reads as `requested`. */
    val kindEnum: CallKind get() = CallKind.fromWire(kind)
    /** A Settings test call (kind `test`, or — for a row an older build booked
     *  before kinds existed — the test label). */
    val isTestCall: Boolean get() = kindEnum == CallKind.TEST
    /** `callAt` as epoch ms (null if the server sent something unparseable). */
    val callAtMs: Long? get() = Time.parseMillis(callAt)
    val snoozeUntilMs: Long? get() = snoozeUntil?.let { Time.parseMillis(it) }
    /** The effective next ring time: `snoozeUntil` when snoozed, else `callAt`
     *  (what dispatch_calls uses: `coalesce(snooze_until, call_at)`). */
    val effectiveAtMs: Long? get() =
        if (status == CallStatus.SNOOZED.wire) snoozeUntilMs ?: callAtMs else callAtMs
    val isLive: Boolean get() = status in liveStatuses
    /** Notes / label may still change (live, or answered and in progress). */
    val isEditable: Boolean get() = status in editableStatuses
    /** The call is ringing or in progress right now: notes/label edits land,
     *  a time change is refused. */
    val isInProgress: Boolean get() = status == CallStatus.CALLING.wire || status == CallStatus.ANSWERED.wire
}
