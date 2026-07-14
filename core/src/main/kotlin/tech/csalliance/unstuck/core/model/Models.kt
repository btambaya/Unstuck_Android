package tech.csalliance.unstuck.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Domain models. Mirror lib/types.ts (web) + UnstuckCore/Models (iOS).
// camelCase throughout — the snake_case PostgREST mapping lives in :data's
// DbRowCodec. Embedded JSONB types (Objective/Comment/CollectionItem/
// Recurrence) keep camelCase keys (that IS the server JSONB shape).

@Serializable
data class Objective(val text: String, val done: Boolean? = null, val minutes: Int? = null)

@Serializable
data class Comment(val text: String, val at: String? = null)

/** Recurring schedule. `null` (the optional) = does not repeat. Encodes the
 *  tagged-union JSON the web uses: {kind, daysOfWeek?, until?}. daysOfWeek
 *  is 0=Sun…6=Sat. `until` (YYYY-MM-DD) inclusive. */
@Serializable(with = RecurrenceSerializer::class)
sealed class Recurrence {
    abstract val until: String?
    data class Daily(override val until: String? = null) : Recurrence()
    data class Weekly(val daysOfWeek: List<Int>, override val until: String? = null) : Recurrence()
    data class Monthly(override val until: String? = null) : Recurrence()
}

object RecurrenceSerializer : KSerializer<Recurrence> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Recurrence")

    /** `until` sentinel for an unrecognised recurrence kind (see [deserialize]). A
     *  date far enough in the past that the until-guard trips before any occurrence
     *  is materialised, so the task repeats zero times here. [isUnknown] detects it. */
    const val UNKNOWN_UNTIL = "0001-01-01"

    /** True for the [deserialize] degrade-sentinel — a daily whose until is in the
     *  distant past. Callers (recurrenceLabel) treat it as "no recurrence". */
    fun isUnknown(r: Recurrence?): Boolean = r is Recurrence.Daily && r.until == UNKNOWN_UNTIL

    override fun serialize(encoder: Encoder, value: Recurrence) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("Recurrence needs JSON")
        val obj = buildJsonObject {
            when (value) {
                is Recurrence.Daily -> put("kind", "daily")
                is Recurrence.Monthly -> put("kind", "monthly")
                is Recurrence.Weekly -> {
                    put("kind", "weekly")
                    put("daysOfWeek", JsonArray(value.daysOfWeek.map { JsonPrimitive(it) }))
                }
            }
            value.until?.let { put("until", it) }
        }
        json.encodeJsonElement(obj)
    }

    override fun deserialize(decoder: Decoder): Recurrence {
        val json = decoder as? JsonDecoder ?: throw SerializationException("Recurrence needs JSON")
        val obj = json.decodeJsonElement().jsonObject
        val until = obj["until"]?.jsonPrimitive?.contentOrNull
        return when (obj["kind"]?.jsonPrimitive?.content) {
            "daily" -> Recurrence.Daily(until)
            "monthly" -> Recurrence.Monthly(until)
            "weekly" -> Recurrence.Weekly(
                obj["daysOfWeek"]?.jsonArray?.map { it.jsonPrimitive.int } ?: emptyList(), until)
            // Forward-compat: an UNKNOWN kind (a newer web/iOS release added a
            // recurrence type this build can't model). A bare throw aborts the WHOLE
            // TaskItem decode, so the task would VANISH from the list. Degrade to a no-op
            // daily that already ENDED (UNKNOWN_UNTIL): zero occurrences materialise and
            // recurrenceLabel renders nothing, so the task stays usable (it just doesn't
            // repeat on this build) instead of disappearing.
            else -> Recurrence.Daily(until = UNKNOWN_UNTIL)
        }
    }
}

@Serializable
data class TaskItem(
    val id: String,
    val name: String,
    val estimateMin: Int,
    val totalFocused: Int = 0,
    val done: Boolean = false,
    val priority: Priority? = null,
    val tags: List<String>? = null,
    val objectives: List<Objective>? = null,
    val comments: List<Comment>? = null,
    val intentWhen: String? = null,
    val intentThen: String? = null,
    val lifeArea: String? = null,
    val firstPhysicalAction: String? = null,
    val moveCount: Int? = null,
    val completedAt: String? = null,
    val later: Boolean? = null,
    val recurrence: Recurrence? = null,
    // Back-link to a collection item this task was promoted from (migration 025),
    // so completing it can flip the shared item + a cron can escalate if it's late.
    // (`late_nudged` is server-owned by the cron and never written by the client.)
    val sourceCollectionId: String? = null,
    val sourceItemId: String? = null,
    /** ISO "by" time for an accountability promote; the late cron checks due+5m. */
    val dueAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class Session(
    val id: String,
    val taskId: String? = null,
    val taskName: String,
    val tags: List<String>? = null,
    val estimateMin: Int? = null,
    val actualSec: Int,
    val completedAt: String,
)

@Serializable
data class CalBlock(
    val id: String,
    val taskId: String?,
    val taskName: String,
    val startTime: String,        // HH:MM
    val durationMinutes: Int,
    val date: String,             // YYYY-MM-DD
    val externalEventId: String? = null,
    val externalConnectionId: String? = null,
    val kind: CalBlockKind? = null,
    // Per-occurrence state (migration 033). For a recurring template's
    // occurrence blocks, completion/skip live here (per day), not on the
    // template task — so one day can be ticked off or cancelled without
    // ending the series. See core/logic/Occurrences.kt.
    val done: Boolean = false,
    val skipped: Boolean = false,
    val completedAt: String? = null,
)

@Serializable
data class ReasonLog(
    val id: String,
    val taskId: String? = null,
    val reason: String,
    val action: ReasonAction,
    val at: String,
    val durationSec: Int? = null,
)

@Serializable
data class Capture(
    val id: String,
    val taskId: String? = null,
    val sessionId: String? = null,
    val tag: CaptureTag,
    val body: String,
    val at: String,
)

@Serializable
data class CalendarConnection(
    val id: String,
    val provider: CalendarProvider,
    val accountEmail: String,
    val displayName: String,
    val selectedCalendarIds: List<String>,
    val colorSlot: Int,
    val lastSyncCursor: String? = null,
    val connectedAt: String,
)

@Serializable
data class ExternalEvent(
    val id: String,
    val connectionId: String,
    val calendarId: String,
    val summary: String,
    val start: String,
    val end: String,
)

@Serializable
data class CollectionItem(
    val id: String,
    val body: String,
    val pinned: Boolean? = null,
    val done: Boolean? = null,
    val at: String,
    // Move-to-task / accountability (migration 025). Set when an item is promoted
    // to a task. On a shared list these are visible to all members so the list
    // doubles as an accountability board. Optional → back-compatible JSONB.
    /** True once the item has been moved to a task. */
    val promoted: Boolean? = null,
    /** Display name of the person who took the task (the promoter). */
    val assignee: String? = null,
    /** True once the assignee's linked task is completed (live status). */
    val promotedDone: Boolean? = null,
    /** ISO "by" time for keep-in-loop promotes; drives the overdue indicator. */
    val dueAt: String? = null,
)

@Serializable
data class ItemCollection(
    val id: String,
    val name: String,
    val color: String,
    val subtitle: String? = null,
    val items: List<CollectionItem>,
    val sortOrder: Int,
    // Shared-collection fields (migration 020/022). Client-only — populated by
    // the Hydrator from collections.user_id + collection_members; NEVER written
    // back to the DB (the CollectionRow codec drops them). Defaults keep local
    // cache + own unshared lists untouched.
    /** Owner's user id (the collection's user_id). Null for local/demo rows. */
    val ownerId: String? = null,
    /** Shared-with user ids (excludes the owner). Empty = not shared. */
    val members: List<String> = emptyList(),
    /** Current user's role: "owner" | "editor" | "viewer". Null = local/own. */
    val myRole: String? = null,
    /** Archived (migration 026) — hidden from the main overview; restorable. */
    val archived: Boolean? = null,
)

@Serializable
data class TagRow(val id: String, val name: String, val color: String? = null, val sortOrder: Int)

@Serializable
data class LifeArea(val id: String, val name: String, val color: String, val sortOrder: Int)

/** Device-local live focus session (mirrors the web unstuck-session). */
@Serializable
data class LiveSession(
    val id: String?,
    val taskId: String,
    val sessionStart: Long? = null,    // epoch ms
    val paused: Boolean = false,
    val pausedAt: Long? = null,        // epoch ms
    val sessionEstimateMin: Int,
    val nudge80Fired: Boolean = false,
    val overrunPromptFired: Boolean = false,
    val treatment: FocusTreatment,
    val priorAccumulatedSec: Int? = null,
    // Focusing a recurring OCCURRENCE: taskId above is the template (totalFocused
    // continuity); completing marks THIS occurrence's cal_block done. Device-local.
    val occurrenceBlockId: String? = null,
    // Recipient's focus on a task shared WITH them (T3, Option B). The task is NOT in
    // the recipient's own store, so a non-null [sharedTitle] MARKS this session shared:
    // finish / cancel / displace accrue the time onto the OWNER's task via
    // log_shared_focus instead of writing an own Session row / totalFocused, and the
    // recap uses [sharedTitle]. [sharedLevel] is the ShareLevel wire ("partner"/"assign")
    // — it gates the co-focus presence broadcast (partner appears as "focusing" to the
    // owner). Device-local, backward-compatible defaults (own sessions leave them null).
    val sharedTitle: String? = null,
    val sharedLevel: String? = null,
)
