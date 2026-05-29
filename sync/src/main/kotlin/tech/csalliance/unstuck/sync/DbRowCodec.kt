package tech.csalliance.unstuck.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import tech.csalliance.unstuck.core.logic.uuidOrNull
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.CalendarConnection
import tech.csalliance.unstuck.core.model.CalendarProvider
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.model.Comment
import tech.csalliance.unstuck.core.model.CollectionItem
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.core.model.Objective
import tech.csalliance.unstuck.core.model.Priority
import tech.csalliance.unstuck.core.model.ReasonAction
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TagRow
import tech.csalliance.unstuck.core.model.TaskItem

// DbRowCodec — the PostgREST boundary. Explicit per-entity row DTOs mapping the
// :core models to/from the exact Supabase row shape the web uses
// (lib/use-tasks.ts taskToDbRow et al.). Port of the iOS DbRowCodec.swift.
//
// Two gotchas the row encoders resolve:
//  1. Top-level columns are snake_case (@SerialName), but JSONB blobs keep
//     camelCase keys (recurrence.daysOfWeek, objectives, …) — the nested
//     :core types serialize with their own camelCase names, and we apply NO
//     global SnakeCase strategy, so daysOfWeek survives.
//  2. Nullable optionals serialize as explicit `null` (explicitNulls = true)
//     so an upsert CLEARS a removed field. The lone exception is
//     reason_logs.duration_sec, omitted so an upsert never clobbers a
//     server-set value (matches the web writer) — stripped post-encode.
// user_id is NOT included — the gateway attaches it. Defaults match the web
// (tags ?? [], move_count ?? 0, later ?? false). FK columns drop to null when
// not a valid UUID (uuidOrNull).

internal val rowJson = Json {
    explicitNulls = true
    encodeDefaults = true
    ignoreUnknownKeys = true
    isLenient = true
}

@Serializable
internal data class TaskRow(
    val id: String,
    val name: String,
    @SerialName("estimate_min") val estimateMin: Int,
    @SerialName("total_focused") val totalFocused: Int,
    val done: Boolean,
    val priority: Priority?,
    val tags: List<String>? = emptyList(),
    val objectives: List<Objective>? = emptyList(),
    val comments: List<Comment>? = emptyList(),
    @SerialName("intent_when") val intentWhen: String?,
    @SerialName("intent_then") val intentThen: String?,
    @SerialName("life_area") val lifeArea: String?,
    @SerialName("first_physical_action") val firstPhysicalAction: String?,
    @SerialName("move_count") val moveCount: Int,
    @SerialName("completed_at") val completedAt: String?,
    val later: Boolean,
    val recurrence: Recurrence?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
) {
    constructor(t: TaskItem) : this(
        t.id, t.name, t.estimateMin, t.totalFocused, t.done, t.priority,
        t.tags ?: emptyList(), t.objectives ?: emptyList(), t.comments ?: emptyList(),
        t.intentWhen, t.intentThen, t.lifeArea, t.firstPhysicalAction,
        t.moveCount ?: 0, t.completedAt, t.later ?: false, t.recurrence, t.createdAt, t.updatedAt,
    )
    fun toModel() = TaskItem(
        id = id, name = name, estimateMin = estimateMin, totalFocused = totalFocused, done = done,
        priority = priority, tags = tags, objectives = objectives, comments = comments,
        intentWhen = intentWhen, intentThen = intentThen, lifeArea = lifeArea,
        firstPhysicalAction = firstPhysicalAction, moveCount = moveCount, completedAt = completedAt,
        later = later, recurrence = recurrence, createdAt = createdAt, updatedAt = updatedAt,
    )
}

@Serializable
internal data class SessionRow(
    val id: String,
    @SerialName("task_id") val taskId: String?,
    @SerialName("task_name") val taskName: String,
    val tags: List<String>? = emptyList(),
    @SerialName("estimate_min") val estimateMin: Int?,
    @SerialName("actual_sec") val actualSec: Int,
    @SerialName("completed_at") val completedAt: String,
) {
    constructor(s: Session) : this(s.id, uuidOrNull(s.taskId), s.taskName, s.tags ?: emptyList(), s.estimateMin, s.actualSec, s.completedAt)
    fun toModel() = Session(id, taskId, taskName, tags, estimateMin, actualSec, completedAt)
}

@Serializable
internal data class CalBlockRow(
    val id: String,
    @SerialName("task_id") val taskId: String?,
    @SerialName("task_name") val taskName: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("duration_minutes") val durationMinutes: Int,
    val date: String,
    @SerialName("external_event_id") val externalEventId: String?,
    @SerialName("external_connection_id") val externalConnectionId: String?,
    val kind: CalBlockKind,
) {
    constructor(b: CalBlock) : this(
        b.id, uuidOrNull(b.taskId), b.taskName, b.startTime, b.durationMinutes, b.date,
        b.externalEventId, uuidOrNull(b.externalConnectionId), b.kind ?: CalBlockKind.TASK,
    )
    fun toModel() = CalBlock(id, taskId, taskName, startTime, durationMinutes, date, externalEventId, externalConnectionId, kind)
}

@Serializable
internal data class CaptureRow(
    val id: String,
    @SerialName("task_id") val taskId: String?,
    @SerialName("session_id") val sessionId: String?,
    val tag: CaptureTag,
    val body: String,
    val at: String,
) {
    constructor(c: Capture) : this(c.id, uuidOrNull(c.taskId), uuidOrNull(c.sessionId), c.tag, c.body, c.at)
    fun toModel() = Capture(id, taskId, sessionId, tag, body, at)
}

@Serializable
internal data class ReasonLogRow(
    val id: String,
    @SerialName("task_id") val taskId: String?,
    val reason: String,
    val action: ReasonAction,
    val at: String,
    @SerialName("duration_sec") val durationSec: Int?,
) {
    constructor(r: ReasonLog) : this(r.id, uuidOrNull(r.taskId), r.reason, r.action, r.at, r.durationSec)
    fun toModel() = ReasonLog(id, taskId, reason, action, at, durationSec)
}

@Serializable
internal data class CollectionRow(
    val id: String,
    val name: String,
    val color: String,
    val subtitle: String?,
    val items: List<CollectionItem>? = emptyList(),
    @SerialName("sort_order") val sortOrder: Int,
) {
    constructor(c: ItemCollection) : this(c.id, c.name, c.color, c.subtitle ?: "", c.items, c.sortOrder)
    fun toModel() = ItemCollection(id, name, color, subtitle?.ifEmpty { null }, items ?: emptyList(), sortOrder)
}

@Serializable
internal data class TagDbRow(
    val id: String,
    val name: String,
    val color: String?,
    @SerialName("sort_order") val sortOrder: Int,
) {
    constructor(t: TagRow) : this(t.id, t.name, t.color, t.sortOrder)
    fun toModel() = TagRow(id, name, color, sortOrder)
}

@Serializable
internal data class LifeAreaDbRow(
    val id: String,
    val name: String,
    val color: String,
    @SerialName("sort_order") val sortOrder: Int,
) {
    constructor(a: LifeArea) : this(a.id, a.name, a.color, a.sortOrder)
    fun toModel() = LifeArea(id, name, color, sortOrder)
}

@Serializable
internal data class CalendarConnectionRow(
    val id: String,
    val provider: CalendarProvider,
    @SerialName("account_email") val accountEmail: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("selected_calendar_ids") val selectedCalendarIds: List<String> = emptyList(),
    @SerialName("color_slot") val colorSlot: Int,
    @SerialName("last_sync_cursor") val lastSyncCursor: String?,
    @SerialName("connected_at") val connectedAt: String,
) {
    constructor(c: CalendarConnection) : this(
        c.id, c.provider, c.accountEmail, c.displayName, c.selectedCalendarIds, c.colorSlot, c.lastSyncCursor, c.connectedAt,
    )
    fun toModel() = CalendarConnection(id, provider, accountEmail, displayName, selectedCalendarIds, colorSlot, lastSyncCursor, connectedAt)
}

/** Encode/decode the PostgREST row shape. Encoders return a [JsonObject] so
 *  the gateway can inject user_id while preserving explicit JSON nulls. */
internal object DbRowCodec {
    private inline fun <reified T> obj(serializer: kotlinx.serialization.KSerializer<T>, v: T): JsonObject =
        rowJson.encodeToJsonElement(serializer, v) as JsonObject

    fun encodeTask(t: TaskItem) = obj(TaskRow.serializer(), TaskRow(t))
    fun encodeSession(s: Session) = obj(SessionRow.serializer(), SessionRow(s))
    fun encodeCalBlock(b: CalBlock) = obj(CalBlockRow.serializer(), CalBlockRow(b))
    fun encodeCapture(c: Capture) = obj(CaptureRow.serializer(), CaptureRow(c))
    fun encodeReasonLog(r: ReasonLog): JsonObject {
        val full = obj(ReasonLogRow.serializer(), ReasonLogRow(r))
        // Omit duration_sec when null so an upsert never clobbers the server value.
        return if (full["duration_sec"] == JsonNull) JsonObject(full - "duration_sec") else full
    }
    fun encodeCollection(c: ItemCollection) = obj(CollectionRow.serializer(), CollectionRow(c))
    fun encodeTag(t: TagRow) = obj(TagDbRow.serializer(), TagDbRow(t))
    fun encodeLifeArea(a: LifeArea) = obj(LifeAreaDbRow.serializer(), LifeAreaDbRow(a))

    fun decodeTask(o: JsonObject) = rowJson.decodeFromJsonElement(TaskRow.serializer(), o).toModel()
    fun decodeSession(o: JsonObject) = rowJson.decodeFromJsonElement(SessionRow.serializer(), o).toModel()
    fun decodeCalBlock(o: JsonObject) = rowJson.decodeFromJsonElement(CalBlockRow.serializer(), o).toModel()
    fun decodeCapture(o: JsonObject) = rowJson.decodeFromJsonElement(CaptureRow.serializer(), o).toModel()
    fun decodeReasonLog(o: JsonObject) = rowJson.decodeFromJsonElement(ReasonLogRow.serializer(), o).toModel()
    fun decodeCollection(o: JsonObject) = rowJson.decodeFromJsonElement(CollectionRow.serializer(), o).toModel()
    fun decodeTag(o: JsonObject) = rowJson.decodeFromJsonElement(TagDbRow.serializer(), o).toModel()
    fun decodeLifeArea(o: JsonObject) = rowJson.decodeFromJsonElement(LifeAreaDbRow.serializer(), o).toModel()
    fun decodeConnection(o: JsonObject) = rowJson.decodeFromJsonElement(CalendarConnectionRow.serializer(), o).toModel()
}
