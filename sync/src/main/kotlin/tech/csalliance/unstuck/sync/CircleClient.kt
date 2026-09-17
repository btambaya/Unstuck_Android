package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.ZoneId
import tech.csalliance.unstuck.core.logic.FocusTimer
import tech.csalliance.unstuck.core.logic.IsoRange
import tech.csalliance.unstuck.core.logic.clampSharedRange
import tech.csalliance.unstuck.core.logic.resolveSharedSlot
import tech.csalliance.unstuck.core.model.CircleMember
import tech.csalliance.unstuck.core.model.CircleStatus
import tech.csalliance.unstuck.core.model.Objective
import tech.csalliance.unstuck.core.model.PendingInvite
import tech.csalliance.unstuck.core.model.PendingInviteKind
import tech.csalliance.unstuck.core.model.ShareBadge
import tech.csalliance.unstuck.core.model.ShareForTask
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.core.model.SharedBlock
import tech.csalliance.unstuck.core.model.SharedTaskDetail
import tech.csalliance.unstuck.core.model.SharedWithMe

// CircleClient — the Android port of the web trusted-circle + per-task-sharing
// plumbing (lib/use-circle.ts + lib/use-task-shares.ts). Every read/write goes
// through the SECURITY DEFINER RPCs (migrations 036/037/040/044) or the
// circle-invite / share-notify edge functions — the same auth.uid()-scoped,
// server-validated surface the web uses. Recipients CANNOT read raw task rows
// (RLS): shared tasks come from the tasks_shared_with_me RPC projection, never a
// table mirror. Error handling mirrors CollectionShareClient: reads degrade to
// empty, best-effort writes are swallowed, and only the two RPCs the web throws
// on (task_share, shared_task_set_done) propagate.
//
// The wire DTOs below are `internal` (not `private`) so the module's unit tests
// can assert the exact snake_case param names + default-omission (kotlinx omits
// null defaults — that's how circle-invite sends `{}` for the no-email case).

/** Outcome of a log_shared_focus accrual — surfaced (not swallowed) because the
 *  ledger is the EXCLUSIVE accrual path for partner-shared sessions.
 *  LOGGED = RPC accepted (or deduped server-side); SKIPPED = nothing to log
 *  (clamped ≤ 0); NOT_ALLOWED = the server refused the caller (share revoked
 *  mid-session — terminal, never retried); FAILED = transient (offline / 5xx) —
 *  the caller must queue a durable retry. */
enum class SharedFocusLogResult { LOGGED, SKIPPED, NOT_ALLOWED, FAILED }

// ── RPC param classes (snake_case wire names — must match the SQL signatures) ──
@Serializable internal data class CodeParam(@SerialName("p_code") val code: String)
@Serializable internal data class IdParam(@SerialName("p_id") val id: String)
@Serializable internal data class TaskIdParam(@SerialName("p_task_id") val taskId: String)

@Serializable internal data class ShareParams(
    @SerialName("p_task_id") val taskId: String,
    @SerialName("p_user") val user: String,
    @SerialName("p_level") val level: String,
)

@Serializable internal data class SetDoneParams(
    @SerialName("p_task_id") val taskId: String,
    @SerialName("p_done") val done: Boolean,
)

// migration 052 shared_task_blocks(p_from date, p_to date) — inclusive 'YYYY-MM-DD'
// bounds. Both REQUIRED (no defaults: kotlinx would omit them and the RPC raises
// bad_range on a null bound).
@Serializable internal data class RangeParams(
    @SerialName("p_from") val from: String,
    @SerialName("p_to") val to: String,
)

@Serializable internal data class LogFocusParams(
    @SerialName("p_task_id") val taskId: String,
    @SerialName("p_actual_sec") val actualSec: Int,
    // migration 046: the live focus session's uuid. The RPC is IDEMPOTENT per session
    // id, so a re-fire (double finalize / cross-surface) no-ops server-side.
    @SerialName("p_session_id") val sessionId: String,
)

// ── RPC row DTOs (server column names) ──
@Serializable internal data class CircleRow(
    val id: String,
    @SerialName("relationship_label") val relationshipLabel: String? = null,
    val level: String = "view",
    val status: String = "invited",
    @SerialName("invite_code") val inviteCode: String? = null,
    @SerialName("member_user_id") val memberUserId: String? = null,
    @SerialName("member_name") val memberName: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    // Unified sharing v1 (migration 065): the pending invite's address, so People
    // can show WHO was invited. Nullable WITH a default — absent entirely on a
    // pre-065 projection, explicit null for link-only / active rows.
    @SerialName("invitee_email") val inviteeEmail: String? = null,
)

// migration 067 cancel_pending_invite(p_kind text, p_id text) → boolean.
@Serializable internal data class CancelPendingInviteParams(
    @SerialName("p_kind") val kind: String,
    @SerialName("p_id") val id: String,
)

@Serializable internal data class ShareForTaskRow(
    @SerialName("share_id") val shareId: String,
    @SerialName("recipient_user_id") val recipientUserId: String = "",
    @SerialName("recipient_name") val recipientName: String = "",
    val level: String = "view",
)

// completed_at only arrives once migration 049 widens the tasks_shared_with_me
// projection — nullable WITH a default so an older server (key absent) and a
// migrated one that sends an explicit null both decode. Read-only column.
@Serializable internal data class SharedWithMeRow(
    @SerialName("share_id") val shareId: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("owner_name") val ownerName: String = "",
    val level: String = "view",
    val title: String = "",
    val done: Boolean? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    // Migration 052 schedule projection — same absent-OR-explicit-null tolerance:
    // nullable WITH a default. All read-only (this DTO is never encoded).
    @SerialName("estimate_min") val estimateMin: Int? = null,
    @SerialName("life_area") val lifeArea: String? = null,
    @SerialName("next_block_id") val nextBlockId: String? = null,
    @SerialName("next_date") val nextDate: String? = null,
    @SerialName("next_start_time") val nextStartTime: String? = null,
    @SerialName("next_duration_minutes") val nextDurationMinutes: Int? = null,
    @SerialName("next_done") val nextDone: Boolean? = null,
    // Migration 053: the same slot as an INSTANT (owner wall-clock → their zone),
    // the owner's Later flag and recurrence template (a jsonb object, or null).
    // Nullable WITH defaults — absent on a pre-053 server, explicit null when the
    // task has no block / isn't recurring. `recurrence` is kept as a raw element:
    // only its presence matters here, and a shape this build can't parse must not
    // fail the whole row (FORGIVING decode).
    @SerialName("next_start_at") val nextStartAt: String? = null,
    val later: Boolean? = null,
    val recurrence: JsonElement? = null,
)

/** The `later` column as a flag: only an explicit true parks the share. */
internal val SharedWithMeRow.isLater: Boolean get() = later == true

/** A recurrence template is present when the column is a non-empty JSON object. */
internal val SharedWithMeRow.isRecurring: Boolean
    get() = (recurrence as? JsonObject)?.isNotEmpty() == true

/** Row → model, with the owner's slot re-expressed in [zone] (the recipient's)
 *  when `next_start_at` arrived; the raw owner date/time otherwise. Internal so
 *  the module's tests pin the zone. */
internal fun SharedWithMeRow.toModel(zone: ZoneId = ZoneId.systemDefault()): SharedWithMe {
    val slot = resolveSharedSlot(nextStartAt, nextDate, nextStartTime, zone)
    return SharedWithMe(
        shareId = shareId, taskId = taskId, ownerName = ownerName,
        level = ShareLevel.fromWire(level), title = title, done = done == true,
        completedAt = completedAt,
        estimateMin = estimateMin, lifeArea = lifeArea,
        nextBlockId = nextBlockId, nextDate = slot.date, nextStartTime = slot.time,
        nextDurationMinutes = nextDurationMinutes, nextDone = nextDone,
        nextStartAt = nextStartAt, later = isLater, recurring = isRecurring,
    )
}

// One row of shared_task_blocks (migration 052): a block of a task shared WITH me
// inside the requested window. block_id / task_id / date are the identity and are
// always present; everything else tolerates absence.
@Serializable internal data class SharedBlockRow(
    @SerialName("block_id") val blockId: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("share_id") val shareId: String = "",
    val level: String = "view",
    @SerialName("owner_name") val ownerName: String? = null,
    val title: String = "",
    val date: String,
    @SerialName("start_time") val startTime: String = "00:00",
    @SerialName("duration_minutes") val durationMinutes: Int = 0,
    val done: Boolean? = null,
    val skipped: Boolean? = null,
    val kind: String? = null,
    // Migration 053: the block's start as an instant (absent pre-053).
    @SerialName("start_at") val startAt: String? = null,
)

/** Row → model, painted on the RECIPIENT's calendar day/time (see [resolveSharedSlot]). */
internal fun SharedBlockRow.toModel(zone: ZoneId = ZoneId.systemDefault()): SharedBlock {
    val slot = resolveSharedSlot(startAt, date, startTime, zone)
    return SharedBlock(
        blockId = blockId, taskId = taskId, shareId = shareId,
        level = ShareLevel.fromWire(level), ownerName = ownerName.orEmpty(), title = title,
        date = slot.date ?: date, startTime = slot.time ?: startTime, durationMinutes = durationMinutes,
        done = done == true, skipped = skipped == true, kind = kind ?: "task",
        startAt = startAt,
    )
}

@Serializable internal data class BadgeRow(
    @SerialName("task_id") val taskId: String,
    val level: String = "view",
    @SerialName("recipient_name") val recipientName: String = "",
)

// Read-only detail for a task shared WITH me (shared_task_detail, migration 045).
// The RPC returns a single-row table; nullable list/date columns tolerate an absent
// OR explicit-null value (a default only covers an ABSENT key, so these must be
// nullable to survive a literal `null`). priority is decoded but unused (the detail
// is read-only + the redesign has no priority surface).
@Serializable internal data class SharedTaskDetailRow(
    @SerialName("task_id") val taskId: String,
    @SerialName("owner_name") val ownerName: String? = null,
    val level: String = "view",
    val name: String = "",
    val done: Boolean = false,
    @SerialName("estimate_min") val estimateMin: Int = 25,
    @SerialName("total_focused") val totalFocused: Int = 0,
    @SerialName("life_area") val lifeArea: String? = null,
    val priority: String? = null,
    val tags: List<String>? = null,
    val objectives: List<Objective>? = null,
    @SerialName("due_at") val dueAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    // Migration 052: the owner's next block (absent on a pre-052 server).
    @SerialName("next_block_id") val nextBlockId: String? = null,
    @SerialName("next_date") val nextDate: String? = null,
    @SerialName("next_start_time") val nextStartTime: String? = null,
    @SerialName("next_duration_minutes") val nextDurationMinutes: Int? = null,
    @SerialName("next_done") val nextDone: Boolean? = null,
    // Migration 053 (see SharedWithMeRow).
    @SerialName("next_start_at") val nextStartAt: String? = null,
    val later: Boolean? = null,
)

/** Row → model, the owner's next slot re-expressed in [zone] (the recipient's). */
internal fun SharedTaskDetailRow.toModel(zone: ZoneId = ZoneId.systemDefault()): SharedTaskDetail {
    val slot = resolveSharedSlot(nextStartAt, nextDate, nextStartTime, zone)
    return SharedTaskDetail(
        taskId = taskId,
        ownerName = ownerName.orEmpty(),
        level = ShareLevel.fromWire(level),
        title = name,
        done = done,
        estimateMin = estimateMin,
        totalFocused = totalFocused,
        lifeArea = lifeArea,
        tags = tags.orEmpty(),
        objectives = objectives.orEmpty(),
        dueAt = dueAt,
        createdAt = createdAt.orEmpty(),
        nextBlockId = nextBlockId, nextDate = slot.date, nextStartTime = slot.time,
        nextDurationMinutes = nextDurationMinutes, nextDone = nextDone,
        nextStartAt = nextStartAt, later = later == true,
    )
}

// ── Edge-fn bodies ──
@Serializable internal data class InviteBody(val email: String? = null)
@Serializable internal data class NotifyBody(
    val kind: String,
    val taskId: String,
    val recipientId: String? = null,
)

// ── Public result types (the ViewModel branches on these) ──

/** Result of circle-invite: what the server did with the invite. */
@Serializable
data class InviteResult(
    val ok: Boolean? = null,
    val added: Boolean? = null,    // an existing Unstuck user was added directly
    val emailed: Boolean? = null,  // the join link was emailed to a new person
    val link: String? = null,      // a one-time join link for the inviter to share
    val error: String? = null,     // e.g. "server_error", "invite_failed"
)

/** Result of circle_redeem: joined that owner's circle, or a reason it failed. */
@Serializable
data class RedeemResult(
    val ok: Boolean = false,
    val error: String? = null,           // "invalid_or_expired" | "self" | "already_in_circle" | ...
    @SerialName("owner_name") val ownerName: String? = null,
)

class CircleClient(private val client: SupabaseClient) {

    private val lenientJson = Json { ignoreUnknownKeys = true }

    // ── Trusted circle / connections (migrations 036 + 040) ─────────────────

    /** My circle roster: active members (resolved names) + pending invites (with
     *  their code, so the link can be re-copied). Degrades to empty on error. */
    suspend fun circleList(): List<CircleMember> = runCatching {
        client.postgrest.rpc("circle_list").decodeList<CircleRow>().map { r ->
            CircleMember(
                id = r.id,
                relationshipLabel = r.relationshipLabel,
                level = r.level,
                status = CircleStatus.fromWire(r.status),
                inviteCode = r.inviteCode,
                memberUserId = r.memberUserId,
                memberName = r.memberName,
                createdAt = r.createdAt,
                inviteeEmail = r.inviteeEmail?.takeIf { it.isNotBlank() },
            )
        }
    }.getOrDefault(emptyList())

    // ── Pending invites — Settings → People "Waiting to join" ───────────────
    // (unified sharing v1, spec §2 "One place for people"; migration 067)

    /** Every invite I sent that is still waiting: task_invites (`kind: task`),
     *  collection_invites (`collection`) and my own email circle invites
     *  (`circle`), newest first. RPC: my_pending_invites() → setof jsonb, each
     *  `{kind, id, itemId, itemName, email, access, createdAt}`. Tolerant → []
     *  on any failure — including a server where the RPC is not deployed yet
     *  (PostgREST 404) — so the People screen simply shows its roster then. */
    suspend fun myPendingInvites(): List<PendingInvite> = runCatching {
        decodePendingInvites(client.postgrest.rpc("my_pending_invites").data)
    }.getOrDefault(emptyList())

    /** Cancel one pending invite I sent. RPC: cancel_pending_invite(p_kind, p_id)
     *  → boolean. TRUE only when the server says a row was deleted — a missing
     *  RPC, a foreign row or a refusal all read as false so the UI never pretends. */
    suspend fun cancelPendingInvite(kind: PendingInviteKind, id: String): Boolean = runCatching {
        decodeCancelPendingInvite(client.postgrest.rpc("cancel_pending_invite", CancelPendingInviteParams(kind.wire, id)).data)
    }.getOrDefault(false)

    companion object {
        private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

        /** `my_pending_invites` body → models. Defensive by design: the body must
         *  be a JSON array; an element that is not an object, has an unknown
         *  `kind`, or has no `id` is dropped WITHOUT taking the rest down; every
         *  other field is optional. Accepts the contract's camelCase keys and
         *  their snake_case twins. Order is preserved. */
        internal fun decodePendingInvites(body: String): List<PendingInvite> {
            val arr = runCatching { lenient.parseToJsonElement(body) }.getOrNull() as? kotlinx.serialization.json.JsonArray
                ?: return emptyList()
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                fun str(vararg keys: String): String? {
                    for (k in keys) {
                        val p = o[k] as? kotlinx.serialization.json.JsonPrimitive ?: continue
                        if (p is kotlinx.serialization.json.JsonNull) continue
                        return p.content
                    }
                    return null
                }
                val kind = PendingInviteKind.fromWire(str("kind")) ?: return@mapNotNull null
                val id = str("id")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                PendingInvite(
                    kind = kind, inviteId = id,
                    itemId = str("itemId", "item_id")?.takeIf { it.isNotEmpty() },
                    itemName = str("itemName", "item_name")?.takeIf { it.isNotEmpty() },
                    email = (str("email", "invitee_email") ?: "").trim(),
                    access = str("access", "level", "role")?.takeIf { it.isNotEmpty() },
                    createdAt = str("createdAt", "created_at")?.takeIf { it.isNotEmpty() },
                )
            }
        }

        /** `cancel_pending_invite` body → did a row go? PostgREST renders a scalar
         *  `boolean` as `true` / `false`; `[true]` and `{"ok":true}` are read too in
         *  case the function is ever reshaped. Anything else is false. */
        internal fun decodeCancelPendingInvite(body: String): Boolean {
            val text = body.trim()
            if (text == "true") return true
            val el = runCatching { lenient.parseToJsonElement(text) }.getOrNull() ?: return false
            return when (el) {
                is kotlinx.serialization.json.JsonPrimitive -> el.content == "true"
                is kotlinx.serialization.json.JsonArray -> (el.firstOrNull() as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
                is JsonObject -> listOf("ok", "cancel_pending_invite").any { (el[it] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true" }
                else -> false
            }
        }
    }

    /** Redeem an invite code → join that owner's circle. Never throws (returns the
     *  server's {ok, error?, owner_name?}). */
    suspend fun circleRedeem(code: String): RedeemResult = runCatching {
        client.postgrest.rpc("circle_redeem", CodeParam(code.trim())).decodeAs<RedeemResult>()
    }.getOrElse { RedeemResult(ok = false, error = it.message ?: "error") }

    /** Remove a member (or cancel a pending invite). Cascades their task shares
     *  server-side. Best-effort. */
    suspend fun circleRemove(id: String) {
        runCatching { client.postgrest.rpc("circle_remove", IdParam(id)) }
    }

    /** Invite someone to my circle via the circle-invite edge fn. With an email the
     *  server reaches them (adds an existing user directly + notifies; emails a new
     *  person the join link). Blank/null email → a link I share myself. Returns what
     *  happened. Decodes the `{error}` body even from a non-2xx (like
     *  CollectionShareClient), so the caller can surface it. */
    suspend fun circleInvite(email: String? = null): InviteResult {
        val trimmed = email?.trim()?.takeIf { it.isNotEmpty() }
        return callInvite(InviteBody(email = trimmed)) ?: InviteResult(error = "invite_failed")
    }

    private suspend fun callInvite(body: InviteBody): InviteResult? =
        runCatching {
            client.functions.invoke("circle-invite") {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(body)
            }.body<InviteResult>()
        }.getOrElse { e ->
            val resp = (e as? ResponseException)?.response ?: return null
            runCatching { lenientJson.decodeFromString<InviteResult>(resp.bodyAsText()) }.getOrNull()
        }

    // ── Per-task sharing (migrations 037 + 044) ─────────────────────────────

    /** Share a task I own with a circle member at [level]. THROWS on error
     *  (bad_level / not_your_task / not_in_circle) — the share sheet needs to know. */
    suspend fun taskShare(taskId: String, userId: String, level: ShareLevel) {
        client.postgrest.rpc("task_share", ShareParams(taskId, userId, level.wire))
    }

    /** Remove a share by its id (owner-only, RPC-enforced). Best-effort. */
    suspend fun taskUnshare(shareId: String) {
        taskUnshareConfirmed(shareId)
    }

    /** Remove a share by its id and ANSWER whether the server did it — a refusal
     *  (not the owner, 5xx, offline) must never be reported as "removed" while
     *  the recipient still has the task (unified sharing v1 Share screen). */
    suspend fun taskUnshareConfirmed(shareId: String): Boolean =
        runCatching { client.postgrest.rpc("task_unshare", IdParam(shareId)); true }
            .getOrElse { println("[share] task_unshare failed: ${it.message}"); false }

    /** Who a task I own is shared with — drives the share sheet's current state. */
    suspend fun taskSharesForTask(taskId: String): List<ShareForTask> = runCatching {
        client.postgrest.rpc("task_shares_for_task", TaskIdParam(taskId)).decodeList<ShareForTaskRow>().map {
            ShareForTask(it.shareId, it.recipientUserId, it.recipientName, ShareLevel.fromWire(it.level))
        }
    }.getOrDefault(emptyList())

    /** Tasks other people have shared WITH me (SECURITY DEFINER projection — the raw
     *  task rows are RLS-forbidden). Degrades to empty on error. */
    suspend fun tasksSharedWithMe(): List<SharedWithMe> = runCatching {
        // Slots land in the RECIPIENT's zone (migration 053 next_start_at) — see
        // SharedWithMeRow.toModel. A pre-053 server leaves the owner's wall-clock.
        client.postgrest.rpc("tasks_shared_with_me").decodeList<SharedWithMeRow>().map { it.toModel() }
    }.getOrDefault(emptyList())

    /** Every block of every task shared WITH me dated inside [from, to] (inclusive
     *  'YYYY-MM-DD') — the calendar surface (migration 052 shared_task_blocks). The
     *  window is clamped to the RPC's 62-day cap client-side so it can never raise
     *  range_too_wide. Any share level; external blocks never arrive. Degrades to
     *  empty on error (a pre-052 server has no such function → empty calendar, not
     *  a crash). READ-only. */
    suspend fun sharedTaskBlocks(from: String, to: String): List<SharedBlock> = runCatching {
        val r = clampSharedRange(IsoRange(from, to))
        client.postgrest.rpc("shared_task_blocks", RangeParams(r.from, r.to)).decodeList<SharedBlockRow>().map { it.toModel() }
    }.getOrElse {
        println("[shared-blocks] shared_task_blocks($from..$to) failed: ${it.message}")
        emptyList()
    }

    /** Complete/uncomplete a task shared with me — partner OR assign only (the RPC
     *  rejects view). THROWS on error so the optimistic UI can roll back. */
    suspend fun sharedTaskSetDone(taskId: String, done: Boolean) {
        client.postgrest.rpc("shared_task_set_done", SetDoneParams(taskId, done))
    }

    /** Read-only detail for a task shared WITH me at ANY level (migration 045). RLS
     *  forbids the raw task row; this SECURITY DEFINER projection is the only window.
     *  Returns null on error / no such share (degrades — the caller keeps the row's
     *  known title + level). */
    suspend fun sharedTaskDetail(taskId: String): SharedTaskDetail? = runCatching {
        client.postgrest.rpc("shared_task_detail", TaskIdParam(taskId))
            .decodeList<SharedTaskDetailRow>().firstOrNull()?.toModel()
    }.getOrNull()

    /** Accrue a recipient's focus onto the OWNER's shared task (task.total_focused) —
     *  the Option-B "focus reflects onto the owner" write. [actualSec] is CLAMPED to the
     *  session estimate + grace (clampSharedElapsedSec) so a stale wall-clock session
     *  can't dump hours onto the owner. [sessionId] is the live session's uuid: migration
     *  046's RPC is IDEMPOTENT per session id, so a re-fire (double finalize) no-ops
     *  server-side. The RPC authorizes partner/assign shares + the task owner (047) and
     *  raises 'not_allowed' otherwise. The result is SURFACED (one-true-shared-session
     *  accrual is ledger-exclusive, so a swallowed failure would LOSE the minutes):
     *  FAILED → the caller queues a durable retry (SharedFocusLedger); NOT_ALLOWED →
     *  terminal (share revoked mid-session) — an owner falls back to the direct
     *  totalFocused bump, a recipient drops it. */
    suspend fun logSharedFocus(taskId: String, actualSec: Int, sessionId: String, estimateMin: Int): SharedFocusLogResult {
        val capped = FocusTimer.clampSharedElapsedSec(actualSec, estimateMin)
        if (capped <= 0) return SharedFocusLogResult.SKIPPED
        return runCatching { client.postgrest.rpc("log_shared_focus", LogFocusParams(taskId, capped, sessionId)) }
            .fold(
                onSuccess = { SharedFocusLogResult.LOGGED },
                onFailure = {
                    println("[shared-focus] log_shared_focus failed for $taskId (${capped}s): ${it.message}")
                    if (it.message?.contains("not_allowed") == true) SharedFocusLogResult.NOT_ALLOWED
                    else SharedFocusLogResult.FAILED
                },
            )
    }

    /** All my outgoing shares, grouped by taskId → the row badges (mirrors the web's
     *  useShareBadges().byTask map). Degrades to empty on error. */
    suspend fun myTaskShareBadges(): Map<String, List<ShareBadge>> = runCatching {
        client.postgrest.rpc("my_task_share_badges").decodeList<BadgeRow>()
            .map { ShareBadge(it.taskId, ShareLevel.fromWire(it.level), it.recipientName) }
            .groupBy { it.taskId }
    }.getOrDefault(emptyMap())

    // ── share-notify edge fn (best-effort; server re-validates + pref-gates) ─

    /** Tell the recipient a task was shared with them. Fire-and-forget. */
    suspend fun notifyTaskShare(taskId: String, recipientId: String) {
        notify(NotifyBody("task_share", taskId, recipientId))
    }

    /** Tell the OWNER a partner/assignee completed their shared task. Fire-and-forget. */
    suspend fun notifyTaskDone(taskId: String) {
        notify(NotifyBody("task_done", taskId))
    }

    /** Ping everyone a task is shared with that the owner started/finished a session. */
    suspend fun notifySession(taskId: String, started: Boolean) {
        notify(NotifyBody(if (started) "session_start" else "session_end", taskId))
    }

    private suspend fun notify(body: NotifyBody) {
        runCatching {
            client.functions.invoke("share-notify") {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
    }
}
