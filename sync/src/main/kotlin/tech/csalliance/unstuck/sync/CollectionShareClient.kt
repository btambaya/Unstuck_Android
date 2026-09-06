package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// CollectionShareClient — the Android port of the web shared-collections plumbing
// (use-collections.ts share/unshare/leave/listMembers + the atomic item RPCs).
//
//  • Membership is managed by the `share-collection` edge function (owner-only
//    add/remove; self leave; list members + pending invites). The function
//    resolves email → user id server-side and, when no account exists, stores a
//    pending invite + emails them (claimed on signup) → ShareOutcome.INVITED.
//  • Item edits on a SHARED collection go through the atomic JSONB RPCs (one
//    server-side statement, RLS-gated) so two people editing the same list don't
//    clobber each other. Own/unshared lists keep the whole-row outbox path.

enum class ShareOutcome { OK, INVITED, NOT_FOUND, SELF, ERROR }

/** A member (joined) or pending invite of a shared collection, for the share sheet. */
data class CollectionMemberInfo(
    val userId: String,     // "" for a pending invite
    val email: String,
    val role: String,       // "editor" | "viewer"
    val pending: Boolean,
)

class CollectionShareClient(private val client: SupabaseClient) {

    // ── share-collection edge function ─────────────────────────────────────
    @Serializable
    private data class ShareBody(
        val action: String,
        val collectionId: String,
        val email: String? = null,
        val userId: String? = null,
        val role: String? = null,
    )

    @Serializable
    private data class MemberRow(
        @SerialName("user_id") val userId: String = "",
        val email: String = "",
        val role: String? = null,
    )

    @Serializable
    private data class ShareResponse(
        val ok: Boolean? = null,
        val invited: Boolean? = null,
        val userId: String? = null,
        val role: String? = null,
        val email: String? = null,
        val error: String? = null,
        // Contract 2026-09: `add` returns the collection's membership rows so the
        // owner's client can flip the list to "shared" IMMEDIATELY (its own
        // collection_members channel never fired for another user's row, so the
        // owner kept whole-row upserting the items JSONB and clobbering members'
        // atomic edits until the next full hydrate). Absent on an older server.
        val members: List<MemberRow>? = null,
    )

    /** Result of a share: the outcome + the membership the server returned (user ids,
     *  owner excluded), or null when the server didn't return it. */
    data class ShareResult(val outcome: ShareOutcome, val memberIds: List<String>?)

    @Serializable
    private data class PendingRow(val email: String = "", val role: String? = null)

    @Serializable
    private data class ListResponse(
        val ok: Boolean? = null,
        val members: List<MemberRow> = emptyList(),
        val pending: List<PendingRow> = emptyList(),
        val isOwner: Boolean? = null,
    )

    private val lenientJson = Json { ignoreUnknownKeys = true }

    private suspend fun call(body: ShareBody): ShareResponse =
        client.functions.invoke("share-collection") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()

    /** Decode a ShareResponse even from a non-2xx (the supabase ktor client has
     *  expectSuccess=true, so 4xx/5xx throw before .body() — but they still carry a
     *  `{error: ...}` JSON body we want to surface as a specific outcome). Returns null
     *  on a non-HTTP failure (no response, e.g. offline) → caller maps to ERROR. */
    private suspend fun callOrError(body: ShareBody): ShareResponse? =
        runCatching { call(body) }.getOrElse { e ->
            val resp = (e as? ResponseException)?.response ?: return null
            runCatching { lenientJson.decodeFromString<ShareResponse>(resp.bodyAsText()) }.getOrNull()
        }

    /** Share with an email. Existing account → member; otherwise pending invite + email.
     *  Maps the distinct server error codes (`self`, `not_found`) to their outcomes even
     *  when the function returns them at a 4xx status. */
    suspend fun share(collectionId: String, email: String, role: String): ShareOutcome =
        shareDetailed(collectionId, email, role).outcome

    suspend fun shareDetailed(collectionId: String, email: String, role: String): ShareResult {
        val r = callOrError(ShareBody("add", collectionId, email = email, role = role))
            ?: return ShareResult(ShareOutcome.ERROR, null)
        val outcome = when {
            r.error == "not_found" -> ShareOutcome.NOT_FOUND
            r.error == "self" -> ShareOutcome.SELF
            r.error != null -> ShareOutcome.ERROR
            r.invited == true -> ShareOutcome.INVITED
            r.ok == true -> ShareOutcome.OK
            else -> ShareOutcome.ERROR
        }
        return ShareResult(outcome, r.members?.map { it.userId }?.filter { it.isNotBlank() })
    }

    /** Remove a joined member (owner-only). */
    suspend fun unshare(collectionId: String, userId: String) {
        runCatching { call(ShareBody("remove", collectionId, userId = userId)) }
    }

    /** Cancel a pending email invite (owner-only). */
    suspend fun cancelInvite(collectionId: String, email: String) {
        runCatching { call(ShareBody("remove", collectionId, email = email)) }
    }

    /** Leave a collection shared WITH me. */
    suspend fun leave(collectionId: String) {
        runCatching { call(ShareBody("leave", collectionId)) }
    }

    /** Joined members + pending invites for the share sheet. */
    suspend fun listMembers(collectionId: String): List<CollectionMemberInfo> = runCatching {
        val r: ListResponse = client.functions.invoke("share-collection") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(ShareBody("list", collectionId))
        }.body()
        val members = r.members.map {
            CollectionMemberInfo(it.userId, it.email, if (it.role == "viewer") "viewer" else "editor", pending = false)
        }
        val pending = r.pending.map {
            CollectionMemberInfo("", it.email, if (it.role == "viewer") "viewer" else "editor", pending = true)
        }
        members + pending
    }.getOrDefault(emptyList())

    // ── Atomic item RPCs (shared collections only) ─────────────────────────
    @Serializable
    private data class AddItemParams(
        @SerialName("p_collection_id") val collectionId: String,
        @SerialName("p_id") val id: String,
        @SerialName("p_body") val body: String,
        @SerialName("p_at") val at: String,
    )

    @Serializable
    private data class UpdateItemParams(
        @SerialName("p_collection_id") val collectionId: String,
        @SerialName("p_item_id") val itemId: String,
        @SerialName("p_body") val body: String,
    )

    @Serializable
    private data class ItemRefParams(
        @SerialName("p_collection_id") val collectionId: String,
        @SerialName("p_item_id") val itemId: String,
    )

    @Serializable
    private data class FlagParams(
        @SerialName("p_collection_id") val collectionId: String,
        @SerialName("p_item_id") val itemId: String,
        @SerialName("p_flag") val flag: String,
        @SerialName("p_value") val value: Boolean,
    )

    // Direct (non-outbox) callers — THROW on failure so nothing is silently lost.
    // The AppViewModel routes item edits through WriteThrough.enqueueCollectionRpc
    // with the descriptors in [CollectionRpcs] instead (offline retry + rollback).
    suspend fun addItem(collectionId: String, id: String, body: String, at: String) {
        client.postgrest.rpc("collection_add_item", AddItemParams(collectionId, id, body, at))
    }

    suspend fun updateItem(collectionId: String, itemId: String, body: String) {
        client.postgrest.rpc("collection_update_item", UpdateItemParams(collectionId, itemId, body))
    }

    suspend fun removeItem(collectionId: String, itemId: String) {
        client.postgrest.rpc("collection_remove_item", ItemRefParams(collectionId, itemId))
    }

    suspend fun setItemFlag(collectionId: String, itemId: String, flag: String, value: Boolean) {
        client.postgrest.rpc("collection_set_item_flag", FlagParams(collectionId, itemId, flag, value))
    }

    // ── Move-to-task accountability ────────────────────────────────────────
    @Serializable
    private data class PromotionParams(
        @SerialName("p_collection_id") val collectionId: String,
        @SerialName("p_item_id") val itemId: String,
        @SerialName("p_assignee") val assignee: String,
        @SerialName("p_done") val done: Boolean? = null,
        @SerialName("p_due_at") val dueAt: String? = null,
    )

    /** Mark a SHARED item as promoted (assignee + optional pending/done + by-time). */
    suspend fun setItemPromotion(collectionId: String, itemId: String, assignee: String, done: Boolean?, dueAt: String?) {
        client.postgrest.rpc("collection_set_item_promotion", PromotionParams(collectionId, itemId, assignee, done, dueAt))
    }

    @Serializable
    private data class CollectionMetaUpdate(val name: String, val color: String, val subtitle: String, val archived: Boolean)

    /** Update ONLY a shared collection's metadata columns (a PostgREST UPDATE, not
     *  a whole-row upsert) so the `items` JSONB isn't shipped + can't clobber a
     *  member's concurrent item edit. RLS gates it to owner/editor. */
    suspend fun updateCollectionFields(id: String, name: String, color: String, subtitle: String, archived: Boolean) {
        runCatching {
            client.from("collections").update(CollectionMetaUpdate(name, color, subtitle, archived)) { filter { eq("id", id) } }
        }
    }

    // `action` has NO default (kotlinx omits default-valued fields — encodeDefaults
    // off — and the server would then treat every call as 'done').
    @Serializable
    private data class TaskDoneBody(val collectionId: String, val itemId: String, val taskName: String, val by: String, val action: String)

    /** The assignee completed a promoted task → flip the shared item to done +
     *  notify the other members (server-side; best-effort). [action] 'reopen'
     *  (task deleted / un-completed by its assignee) releases the item back to open
     *  — contract 2026-09 — so a loop-promoted item is never stuck "done by ✓" /
     *  "<name>'s on it" forever. Only the assignee (or the owner) may flip it. */
    suspend fun taskDone(collectionId: String, itemId: String, taskName: String, by: String, action: String = "done") {
        runCatching {
            client.functions.invoke("collection-task-done") {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(TaskDoneBody(collectionId, itemId, taskName, by, action))
            }
        }
    }
}

/** A queued shared-collection RPC: function name + JSON params (the `p_*` wire
 *  names the migrations define). Built by [CollectionRpcs]; queued by
 *  WriteThrough.enqueueCollectionRpc; applied by OutboxFlusher via SyncRemote.rpc.
 *  Every function is idempotent server-side (add is UPSERT-BY-ID, migration 056),
 *  so an outbox replay after a partial failure is a no-op. */
data class CollectionRpc(val fn: String, val params: JsonObject, val legacy: CollectionRpc? = null)

object CollectionRpcs {
    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
    private fun s(v: String?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)

    /** Migration 056: `collection_add_item(p_collection_id, p_item jsonb)` is
     *  UPSERT-BY-ID (a replay of the same client id after a lost ack is a no-op,
     *  never a duplicate). A pre-056 server (PGRST202 — function not found) gets the
     *  legacy blind-append signature via [CollectionRpc.legacy]. */
    fun addItem(collectionId: String, id: String, body: String, at: String) = CollectionRpc(
        "collection_add_item",
        obj("p_collection_id" to s(collectionId), "p_item" to obj("id" to s(id), "body" to s(body), "at" to s(at))),
        legacy = CollectionRpc(
            "collection_add_item",
            obj("p_collection_id" to s(collectionId), "p_id" to s(id), "p_body" to s(body), "p_at" to s(at)),
        ),
    )

    fun updateItem(collectionId: String, itemId: String, body: String) = CollectionRpc(
        "collection_update_item",
        obj("p_collection_id" to s(collectionId), "p_item_id" to s(itemId), "p_body" to s(body)),
    )

    fun removeItem(collectionId: String, itemId: String) = CollectionRpc(
        "collection_remove_item",
        obj("p_collection_id" to s(collectionId), "p_item_id" to s(itemId)),
    )

    fun setItemFlag(collectionId: String, itemId: String, flag: String, value: Boolean) = CollectionRpc(
        "collection_set_item_flag",
        obj("p_collection_id" to s(collectionId), "p_item_id" to s(itemId), "p_flag" to s(flag), "p_value" to JsonPrimitive(value)),
    )

    fun setItemPromotion(collectionId: String, itemId: String, assignee: String, done: Boolean?, dueAt: String?) = CollectionRpc(
        "collection_set_item_promotion",
        obj(
            "p_collection_id" to s(collectionId), "p_item_id" to s(itemId), "p_assignee" to s(assignee),
            "p_done" to (done?.let { JsonPrimitive(it) } ?: JsonNull), "p_due_at" to s(dueAt),
        ),
    )
}
