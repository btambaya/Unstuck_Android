package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    private data class ShareResponse(
        val ok: Boolean? = null,
        val invited: Boolean? = null,
        val userId: String? = null,
        val role: String? = null,
        val email: String? = null,
        val error: String? = null,
    )

    @Serializable
    private data class MemberRow(
        @SerialName("user_id") val userId: String = "",
        val email: String = "",
        val role: String? = null,
    )

    @Serializable
    private data class PendingRow(val email: String = "", val role: String? = null)

    @Serializable
    private data class ListResponse(
        val ok: Boolean? = null,
        val members: List<MemberRow> = emptyList(),
        val pending: List<PendingRow> = emptyList(),
        val isOwner: Boolean? = null,
    )

    private suspend fun call(body: ShareBody): ShareResponse =
        client.functions.invoke("share-collection") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()

    /** Share with an email. Existing account → member; otherwise pending invite + email. */
    suspend fun share(collectionId: String, email: String, role: String): ShareOutcome = runCatching {
        val r = call(ShareBody("add", collectionId, email = email, role = role))
        when {
            r.error == "not_found" -> ShareOutcome.NOT_FOUND
            r.error == "self" -> ShareOutcome.SELF
            r.invited == true -> ShareOutcome.INVITED
            r.ok == true && r.userId != null -> ShareOutcome.OK
            else -> ShareOutcome.ERROR
        }
    }.getOrDefault(ShareOutcome.ERROR)

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

    suspend fun addItem(collectionId: String, id: String, body: String, at: String) {
        runCatching { client.postgrest.rpc("collection_add_item", AddItemParams(collectionId, id, body, at)) }
    }

    suspend fun updateItem(collectionId: String, itemId: String, body: String) {
        runCatching { client.postgrest.rpc("collection_update_item", UpdateItemParams(collectionId, itemId, body)) }
    }

    suspend fun removeItem(collectionId: String, itemId: String) {
        runCatching { client.postgrest.rpc("collection_remove_item", ItemRefParams(collectionId, itemId)) }
    }

    suspend fun setItemFlag(collectionId: String, itemId: String, flag: String, value: Boolean) {
        runCatching { client.postgrest.rpc("collection_set_item_flag", FlagParams(collectionId, itemId, flag, value)) }
    }
}
