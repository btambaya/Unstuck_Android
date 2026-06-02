package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
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
        // NO default: kotlinx omits default-valued fields from JSON (encodeDefaults
        // off), so a `= "android"` default would drop `platform` from the body and
        // the server falls through to its `'ios'` branch (register-push-token:47),
        // mislabeling the row → send-* filters on platform === 'android' never route
        // FCM to it. Always serialize it by keeping it required + set explicitly below.
        val platform: String,
        val timezone: String,
    )

    /** Register the device's FCM token with the register-push-token function
     *  (platform = "android" → the backend stores fcm_token + branches sends). */
    suspend fun register(deviceId: String, fcmToken: String?, timezone: String = TimeZone.getDefault().id) {
        client.functions.invoke("register-push-token") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(RegisterBody(deviceId = deviceId, fcmToken = fcmToken, platform = "android", timezone = timezone))
        }
    }

    /** Delete this device's token row on sign-out so the previous user's
     *  morning brief / pushes are never delivered to whoever signs in next on
     *  the same device. MUST run while the signing-out user's JWT is still
     *  valid (RLS: user_id = auth.uid()). */
    suspend fun unregister(deviceId: String) {
        client.from("device_tokens").delete {
            filter { eq("device_id", deviceId) }
        }
    }
}

class NotificationsClient(private val client: SupabaseClient) {
    @Serializable private data class RecapBody(val taskName: String, val away: Boolean)
    @Serializable private data class Empty(val ping: Boolean = true)
    @Serializable private data class AllowedResponse(val allowed: Boolean? = null)

    suspend fun sessionRecap(taskName: String, away: Boolean) {
        client.functions.invoke("send-session-recap") {
            method = HttpMethod.Post; contentType(ContentType.Application.Json); setBody(RecapBody(taskName, away))
        }
    }

    /** Whether a paused-checkin notification is allowed (cap + preference).
     *  Defaults to false if the server can't be reached. */
    suspend fun pausedCheckin(): Boolean = runCatching {
        client.functions.invoke("send-paused-checkin") { method = HttpMethod.Post; contentType(ContentType.Application.Json); setBody(Empty()) }
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

    @Serializable private data class NotifPrefsRow(
        val user_id: String,
        val morning_brief_enabled: Boolean,
        val paused_checkin_enabled: Boolean,
    )

    /** Mirror the device notification level to notification_preferences (owner-self
     *  RLS) so the server-driven morning brief + paused-checkin cap honour it.
     *  Only the level-derived toggles are sent; other columns keep their values. */
    suspend fun setNotificationLevel(userId: String, morningBrief: Boolean, pausedCheckin: Boolean) {
        client.from("notification_preferences")
            .upsert(NotifPrefsRow(userId, morningBrief, pausedCheckin)) { onConflict = "user_id" }
    }
}
