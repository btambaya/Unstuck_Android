package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import java.util.TimeZone

// PushClient (FCM register) + NotificationsClient (recap / paused-checkin) +
// PreferencesClient (onboarding struggles). Ports of the iOS PushClient.swift +
// NotificationsClient.swift, with the FCM token replacing the APNs token.

class PushClient(private val client: SupabaseClient) {
    @Serializable
    private data class RegisterBody(
        val deviceId: String,
        val fcmToken: String?,
        val platform: String = "android",
        val timezone: String,
    )

    /** Register the device's FCM token with the register-push-token function
     *  (platform = "android" → the backend stores fcm_token + branches sends). */
    suspend fun register(deviceId: String, fcmToken: String?, timezone: String = TimeZone.getDefault().id) {
        client.functions.invoke("register-push-token") {
            method = HttpMethod.Post
            setBody(RegisterBody(deviceId = deviceId, fcmToken = fcmToken, timezone = timezone))
        }
    }
}

class NotificationsClient(private val client: SupabaseClient) {
    @Serializable private data class RecapBody(val taskName: String, val away: Boolean)
    @Serializable private data class Empty(val ping: Boolean = true)
    @Serializable private data class AllowedResponse(val allowed: Boolean? = null)

    suspend fun sessionRecap(taskName: String, away: Boolean) {
        client.functions.invoke("send-session-recap") {
            method = HttpMethod.Post; setBody(RecapBody(taskName, away))
        }
    }

    /** Whether a paused-checkin notification is allowed (cap + preference).
     *  Defaults to false if the server can't be reached. */
    suspend fun pausedCheckin(): Boolean = runCatching {
        client.functions.invoke("send-paused-checkin") { method = HttpMethod.Post; setBody(Empty()) }
            .body<AllowedResponse>().allowed ?: false
    }.getOrDefault(false)
}

class PreferencesClient(private val client: SupabaseClient) {
    @Serializable private data class StrugglesRow(val user_id: String, val adhd_struggles: List<String>)

    /** Persist onboarding struggle selections to user_preferences (PK'd on
     *  user_id, so a dedicated upsert path rather than the generic gateway). */
    suspend fun setAdhdStruggles(userId: String, struggles: List<String>) {
        client.from("user_preferences").upsert(StrugglesRow(userId, struggles)) { onConflict = "user_id" }
    }
}
