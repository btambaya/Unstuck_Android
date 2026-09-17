package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tech.csalliance.unstuck.core.logic.normalizedShareEmail
import tech.csalliance.unstuck.core.model.ShareLevel

// TaskShareClient — the `share-task` edge function (unified sharing v1,
// docs/unified-sharing-spec.md §3.3). 1:1 with iOS Sources/UnstuckSync/
// TaskShareClient.swift and the shape of CollectionShareClient: camelCase
// edge-fn bodies, tolerant reads, outcomes that report what the SERVER said.
//
//   add    { taskId, email, level }  → { ok, status: 'shared'|'invited', userId?, displayName? }
//                                     | { ok:false, reason: 'self'|'blocked'|… }
//   remove { taskId, userId? | inviteId? } → { ok }
//   list   { taskId }               → { members:[{userId,displayName,level}], pending:[{id,email,level}] }
//   link   { taskId, level }        → { ok, url }
//
// `status` is decoded HONESTLY: "shared" means the person had an account and
// got the task right now; "invited" means an invite was stored + emailed and
// is claimed when they sign up. The decoders are pure (`decodeAdd` & co.) so
// the contract is unit-tested without a network.
//
// ktor gotcha (memory android-build-state): every invoke sets
// `contentType(Application.Json)` explicitly — without it the body ships as
// text/plain and the function's `req.json()` 400s.

/** What `share-task add` did. */
sealed class TaskShareOutcome {
    /** An existing account — the task is theirs to see now. */
    data class Shared(val userId: String, val displayName: String) : TaskShareOutcome()
    /** No account for that address yet — invite stored, email sent. */
    data object Invited : TaskShareOutcome()
    /** The server refused / could not be reached; [reason] is its code
     *  ("self", "blocked", "rate_limited", "forbidden", …) or "network". */
    data class Failed(val reason: String) : TaskShareOutcome()
}

/** A join link the item rides on (`share-task link` / `share-collection link`). */
sealed class ShareLinkOutcome {
    data class Ok(val url: String) : ShareLinkOutcome()
    data class Failed(val reason: String) : ShareLinkOutcome()
}

/** A joined recipient of a task (from `share-task list`). */
data class TaskShareMember(val userId: String, val displayName: String, val level: ShareLevel)

/** A pending email invite on a task (`task_invites`). */
data class TaskSharePendingInvite(val id: String, val email: String, val level: ShareLevel)

/** The roster `share-task list` returns. */
data class TaskShareRoster(
    val members: List<TaskShareMember> = emptyList(),
    val pending: List<TaskSharePendingInvite> = emptyList(),
) {
    companion object { val EMPTY = TaskShareRoster() }
}

class TaskShareClient(private val client: SupabaseClient) {

    // ── wire shapes (internal → unit-tested) ────────────────────────────────
    // `action` + `taskId` have NO defaults (kotlinx omits default-valued fields
    // — encodeDefaults off — and the function 400s without them); the optional
    // fields default to null so they are omitted when unused.
    @Serializable
    internal data class Body(
        val action: String,
        val taskId: String,
        val email: String? = null,
        val level: String? = null,
        val userId: String? = null,
        val inviteId: String? = null,
    )

    @Serializable
    internal data class AddResponse(
        val ok: Boolean? = null,
        val status: String? = null,
        val userId: String? = null,
        val displayName: String? = null,
        val reason: String? = null,
        val error: String? = null,
    )

    @Serializable internal data class MemberRow(val userId: String? = null, val displayName: String? = null, val level: String? = null)
    @Serializable internal data class PendingRow(val id: String? = null, val email: String? = null, val level: String? = null)

    @Serializable
    internal data class ListResponse(
        val members: List<MemberRow>? = null,
        val pending: List<PendingRow>? = null,
        val error: String? = null,
    )

    @Serializable
    internal data class LinkResponse(
        val ok: Boolean? = null,
        val url: String? = null,
        val reason: String? = null,
        val error: String? = null,
    )

    @Serializable internal data class OkResponse(val ok: Boolean? = null, val error: String? = null, val reason: String? = null)

    // ── calls ───────────────────────────────────────────────────────────────

    /** Share a task I own with an email. Existing account → shared at once
     *  (+ the server pushes them); no account → invite by email. */
    suspend fun add(taskId: String, email: String, level: ShareLevel): TaskShareOutcome =
        when (val r = raw(Body("add", taskId, email = normalizedShareEmail(email), level = level.wire))) {
            is Raw.Ok -> decodeAdd(r.body)
            is Raw.Err -> TaskShareOutcome.Failed(r.reason)
        }

    /** Revoke a joined recipient. TRUE only when the server confirmed. */
    suspend fun remove(taskId: String, userId: String): Boolean =
        confirmed(Body("remove", taskId, userId = userId))

    /** Cancel a pending email invite. TRUE only when the server confirmed. */
    suspend fun cancelInvite(taskId: String, inviteId: String): Boolean =
        confirmed(Body("remove", taskId, inviteId = inviteId))

    /** Joined recipients + pending invites. Tolerant → [TaskShareRoster.EMPTY]
     *  on any failure (incl. a server where `share-task` isn't deployed yet). */
    suspend fun list(taskId: String): TaskShareRoster =
        when (val r = raw(Body("list", taskId))) {
            is Raw.Ok -> decodeList(r.body)
            is Raw.Err -> TaskShareRoster.EMPTY
        }

    /** A one-shot join link that grants THIS task at [level] on redeem. */
    suspend fun link(taskId: String, level: ShareLevel): ShareLinkOutcome =
        when (val r = raw(Body("link", taskId, level = level.wire))) {
            is Raw.Ok -> decodeLink(r.body)
            is Raw.Err -> ShareLinkOutcome.Failed(r.reason)
        }

    private sealed class Raw {
        data class Ok(val body: String) : Raw()
        data class Err(val reason: String) : Raw()
    }

    /** POST the body; a 2xx yields its text, a non-2xx yields the server's
     *  reason code (`{error}` / `{ok:false, reason}`), no response → "network".
     *  supabase-kt's Functions plugin turns a non-2xx into a [RestException]
     *  whose `error` IS the response body (Functions.parseErrorResponse); a
     *  bare ktor [ResponseException] is read the same way in case the plugin
     *  ever stops wrapping. */
    private suspend fun raw(body: Body): Raw = try {
        val text = client.functions.invoke("share-task") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(body)
        }.bodyAsText()
        Raw.Ok(text)
    } catch (e: RestException) {
        Raw.Err(failureReason(e.error.ifBlank { e.message }))
    } catch (e: ResponseException) {
        val text = runCatching { e.response.bodyAsText() }.getOrNull()
        Raw.Err(failureReason(text))
    } catch (e: Exception) {
        println("[share-task] ${body.action} failed: ${e.message}")
        Raw.Err("network")
    }

    private suspend fun confirmed(body: Body): Boolean = when (val r = raw(body)) {
        is Raw.Ok -> decodeOk(r.body)
        is Raw.Err -> { println("[share-task] ${body.action} refused: ${r.reason}"); false }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** `add` → outcome. `status` wins ("shared" / "invited"); a refusal is
         *  `{ok:false, reason}` (or a legacy `{error}`); anything unreadable is
         *  a failure — never a fabricated "invited". */
        internal fun decodeAdd(body: String): TaskShareOutcome {
            val r = runCatching { json.decodeFromString<AddResponse>(body) }.getOrNull()
                ?: return TaskShareOutcome.Failed("bad_response")
            val refusal = r.reason ?: r.error
            if (!refusal.isNullOrEmpty() && r.ok != true) return TaskShareOutcome.Failed(refusal)
            return when ((r.status ?: "").lowercase()) {
                "shared" -> TaskShareOutcome.Shared(r.userId ?: "", r.displayName ?: "")
                "invited" -> TaskShareOutcome.Invited
                else -> {
                    // A 2xx with `ok` but no status: honest fallback on what's present.
                    val uid = r.userId
                    if (r.ok == true && !uid.isNullOrEmpty()) TaskShareOutcome.Shared(uid, r.displayName ?: "")
                    else TaskShareOutcome.Failed(r.reason ?: r.error ?: "bad_response")
                }
            }
        }

        /** `list` → roster. Unknown levels degrade to VIEW (least privilege);
         *  rows missing their key are dropped rather than failing the whole list. */
        internal fun decodeList(body: String): TaskShareRoster {
            val r = runCatching { json.decodeFromString<ListResponse>(body) }.getOrNull() ?: return TaskShareRoster.EMPTY
            val members = (r.members ?: emptyList()).mapNotNull { m ->
                val uid = m.userId?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                TaskShareMember(uid, m.displayName ?: "", ShareLevel.fromWire(m.level))
            }
            val pending = (r.pending ?: emptyList()).mapNotNull { p ->
                val id = p.id?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val email = p.email?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                TaskSharePendingInvite(id, email, ShareLevel.entries.firstOrNull { it.wire == p.level } ?: ShareLevel.PARTNER)
            }
            return TaskShareRoster(members, pending)
        }

        /** `link` → the URL, or the server's reason. */
        internal fun decodeLink(body: String): ShareLinkOutcome {
            val r = runCatching { json.decodeFromString<LinkResponse>(body) }.getOrNull()
                ?: return ShareLinkOutcome.Failed("bad_response")
            val url = r.url
            if (!url.isNullOrEmpty() && r.ok != false) return ShareLinkOutcome.Ok(url)
            return ShareLinkOutcome.Failed(r.reason ?: r.error ?: "bad_response")
        }

        /** A revoke is done only on an explicit `ok:true` with no error / reason. */
        internal fun decodeOk(body: String): Boolean {
            val r = runCatching { json.decodeFromString<OkResponse>(body) }.getOrNull() ?: return false
            return r.ok == true && r.error.isNullOrEmpty() && r.reason.isNullOrEmpty()
        }

        /** A non-2xx body → the server's code when it carries one
         *  (`{error:'rate_limited'}` / `{ok:false, reason:'self'}`), else "network". */
        internal fun failureReason(body: String?): String {
            if (body.isNullOrBlank()) return "network"
            val r = runCatching { json.decodeFromString<AddResponse>(body) }.getOrNull() ?: return "network"
            val code = r.reason ?: r.error
            return if (code.isNullOrEmpty()) "network" else code
        }
    }
}
