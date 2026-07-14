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
import tech.csalliance.unstuck.core.logic.FocusTimer
import tech.csalliance.unstuck.core.model.CircleMember
import tech.csalliance.unstuck.core.model.CircleStatus
import tech.csalliance.unstuck.core.model.Objective
import tech.csalliance.unstuck.core.model.ShareBadge
import tech.csalliance.unstuck.core.model.ShareForTask
import tech.csalliance.unstuck.core.model.ShareLevel
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
)

@Serializable internal data class ShareForTaskRow(
    @SerialName("share_id") val shareId: String,
    @SerialName("recipient_user_id") val recipientUserId: String = "",
    @SerialName("recipient_name") val recipientName: String = "",
    val level: String = "view",
)

@Serializable internal data class SharedWithMeRow(
    @SerialName("share_id") val shareId: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("owner_name") val ownerName: String = "",
    val level: String = "view",
    val title: String = "",
    val done: Boolean? = null,
)

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
)

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
            )
        }
    }.getOrDefault(emptyList())

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
        runCatching { client.postgrest.rpc("task_unshare", IdParam(shareId)) }
    }

    /** Who a task I own is shared with — drives the share sheet's current state. */
    suspend fun taskSharesForTask(taskId: String): List<ShareForTask> = runCatching {
        client.postgrest.rpc("task_shares_for_task", TaskIdParam(taskId)).decodeList<ShareForTaskRow>().map {
            ShareForTask(it.shareId, it.recipientUserId, it.recipientName, ShareLevel.fromWire(it.level))
        }
    }.getOrDefault(emptyList())

    /** Tasks other people have shared WITH me (SECURITY DEFINER projection — the raw
     *  task rows are RLS-forbidden). Degrades to empty on error. */
    suspend fun tasksSharedWithMe(): List<SharedWithMe> = runCatching {
        client.postgrest.rpc("tasks_shared_with_me").decodeList<SharedWithMeRow>().map {
            SharedWithMe(it.shareId, it.taskId, it.ownerName, ShareLevel.fromWire(it.level), it.title, it.done == true)
        }
    }.getOrDefault(emptyList())

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
            .decodeList<SharedTaskDetailRow>().firstOrNull()?.let { r ->
                SharedTaskDetail(
                    taskId = r.taskId,
                    ownerName = r.ownerName.orEmpty(),
                    level = ShareLevel.fromWire(r.level),
                    title = r.name,
                    done = r.done,
                    estimateMin = r.estimateMin,
                    totalFocused = r.totalFocused,
                    lifeArea = r.lifeArea,
                    tags = r.tags.orEmpty(),
                    objectives = r.objectives.orEmpty(),
                    dueAt = r.dueAt,
                    createdAt = r.createdAt.orEmpty(),
                )
            }
    }.getOrNull()

    /** Accrue a recipient's focus onto the OWNER's shared task (task.total_focused) —
     *  the Option-B "focus reflects onto the owner" write. [actualSec] is CLAMPED to the
     *  session estimate + grace (clampSharedElapsedSec) so a stale wall-clock session
     *  can't dump hours onto the owner. [sessionId] is the live session's uuid: migration
     *  046's RPC is IDEMPOTENT per session id, so a re-fire (double finalize) no-ops
     *  server-side. The RPC allows only the caller's PARTNER or ASSIGN share (raises
     *  'not_allowed' for view) and no-ops for <= 0. Best-effort fire-and-forget accrual. */
    suspend fun logSharedFocus(taskId: String, actualSec: Int, sessionId: String, estimateMin: Int) {
        val capped = FocusTimer.clampSharedElapsedSec(actualSec, estimateMin)
        if (capped <= 0) return
        runCatching { client.postgrest.rpc("log_shared_focus", LogFocusParams(taskId, capped, sessionId)) }
            .onFailure { println("[shared-focus] log_shared_focus failed for $taskId (${capped}s): ${it.message}") }
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
