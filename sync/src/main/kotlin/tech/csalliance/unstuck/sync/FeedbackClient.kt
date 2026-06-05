package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One-way in-app beta feedback → the `feedback` table (owner-RLS). Users submit
 * bugs / ideas; the team triages in the Supabase dashboard. Online-only: returns
 * false on any failure so the UI can offer a retry (no offline outbox — feedback
 * is low-volume and the user is normally online when sending).
 *
 * NOTE: every field is set explicitly — kotlinx omits default-valued fields from
 * JSON (encodeDefaults off), so a defaulted field the server stores would silently
 * never be sent (the recurring `platform` gotcha).
 */
class FeedbackClient(private val client: SupabaseClient) {

    @Serializable
    private data class Row(
        val id: String,
        val body: String,
        val category: String?,
        @SerialName("user_id") val userId: String,
        val email: String?,
        @SerialName("app_version") val appVersion: String?,
        val platform: String,
        val device: String?,
        val screen: String?,
    )

    suspend fun submit(
        id: String,
        body: String,
        category: String?,
        email: String?,
        appVersion: String?,
        platform: String,
        device: String?,
        screen: String?,
    ): Boolean = runCatching {
        val uid = client.auth.currentUserOrNull()?.id ?: return false
        client.from("feedback").insert(Row(id, body, category, uid, email, appVersion, platform, device, screen))
        true
    }.getOrDefault(false)
}
