package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import java.util.TimeZone

// PushClient (FCM register) + NotificationsClient (recap / paused-checkin) +
// PreferencesClient (onboarding struggles + the server-backed notification level /
// reminder lead) + CapturesClient (the 053 inbox archive). Ports of the iOS
// PushClient.swift + NotificationsClient.swift, with the FCM token replacing the
// APNs token.

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

class LoginTrackerClient(private val client: SupabaseClient) {
    // platform has NO default on purpose — kotlinx omits default-valued fields
    // (encodeDefaults off), which would drop `platform` and the server would
    // mislabel the row. Always set it explicitly (same trap as PushClient).
    @Serializable private data class TrackBody(val platform: String, val device: String)

    /** Best-effort: record this sign-in (platform + device; the server derives
     *  country/city from the request IP). Never throws — usage analytics must
     *  not affect sign-in. Throttling is the caller's job. */
    suspend fun track(device: String) {
        runCatching {
            client.functions.invoke("track-login") {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(TrackBody(platform = "android", device = device))
            }
        }
    }
}

class PreferencesClient(private val client: SupabaseClient) {
    @Serializable private data class StrugglesRow(val user_id: String, val adhd_struggles: List<String>)

    /** Persist onboarding struggle selections to user_preferences (PK'd on
     *  user_id, so a dedicated upsert path rather than the generic gateway). */
    suspend fun setAdhdStruggles(userId: String, struggles: List<String>) {
        client.from("user_preferences").upsert(StrugglesRow(userId, struggles)) { onConflict = "user_id" }
    }

    /** The server's copy of the onboarding / interview state (user_preferences). Both
     *  nullable: an account that never onboarded has no row at all. */
    @Serializable
    data class ServerUserPrefs(
        val adhd_struggles: List<String>? = null,
        val assistant_interview_done_at: String? = null,
    )

    /** Read back the account-wide onboarding signals so a second account on the same
     *  device (or a fresh install) reconciles `onboarded` from the SERVER rather than a
     *  device-global flag. Null = no row (never onboarded anywhere) — and ALSO null on a
     *  transport error, so callers treat null as "unknown", never as "not onboarded". */
    suspend fun fetchUserPrefs(userId: String): ServerUserPrefs? =
        client.from("user_preferences")
            .select(Columns.list("adhd_struggles", "assistant_interview_done_at")) { filter { eq("user_id", userId) } }
            .decodeSingleOrNull<ServerUserPrefs>()

    @Serializable private data class NotifPrefsRow(
        val user_id: String,
        // The canonical column the web reads (lib/notification-prefs.ts) — the
        // booleans below are DERIVED from it and only exist for the server crons.
        val notification_level: String,
        val morning_brief_enabled: Boolean,
        val paused_checkin_enabled: Boolean,
    )

    /** Mirror the notification level to notification_preferences (owner-self RLS):
     *  the `notification_level` column (server source of truth, what web Settings
     *  shows) PLUS the level-derived toggles the morning brief + paused-checkin crons
     *  read. Other columns keep their values. [level] is 'calm' | 'balanced' | 'coach'. */
    suspend fun setNotificationLevel(userId: String, level: String, morningBrief: Boolean, pausedCheckin: Boolean) {
        client.from("notification_preferences")
            .upsert(NotifPrefsRow(userId, level, morningBrief, pausedCheckin)) { onConflict = "user_id" }
    }

    @Serializable private data class ReminderLeadRow(val user_id: String, val reminder_lead_min: Int)

    /** Mirror the pre-task reminder lead (minutes, 0 = off) — the server-cron
     *  reminders (dispatch_task_reminders) and web Settings read this column. */
    suspend fun setReminderLead(userId: String, leadMin: Int) {
        client.from("notification_preferences")
            .upsert(ReminderLeadRow(userId, leadMin)) { onConflict = "user_id" }
    }

    /** The server's copy of the two settings every platform shares. */
    @Serializable
    data class ServerNotificationPrefs(
        val notification_level: String? = null,
        val reminder_lead_min: Int? = null,
    )

    /** Read back notification_level + reminder_lead_min (the server is the source of
     *  truth — a level picked on the web must reach this phone's local alarms). Null
     *  = no row yet (register-push-token / the first mirror creates it) OR a transport
     *  error; callers must treat null as "unknown" and keep their local value. */
    suspend fun fetchNotificationPrefs(userId: String): ServerNotificationPrefs? =
        client.from("notification_preferences")
            .select(Columns.list("notification_level", "reminder_lead_min")) { filter { eq("user_id", userId) } }
            .decodeSingleOrNull<ServerNotificationPrefs>()
}

/** The inbox archive (migration 053 `captures.archived_at`): archive = set now(),
 *  unarchive = null. The SERVER is the source of truth for which captures are
 *  archived; the app keeps its old device-local id set only as a cache. Owner RLS
 *  scopes every call. */
class CapturesClient(private val client: SupabaseClient) {
    @Serializable private data class IdRow(val id: String)

    /** Archive (true) or restore (false) one capture. THROWS on error so the caller
     *  can keep the write queued and retry — an archive must never silently fail. */
    suspend fun setArchived(id: String, archived: Boolean) {
        val stamp: String? = if (archived) java.time.Instant.now().toString() else null
        client.from("captures").update({ set("archived_at", stamp) }) { filter { eq("id", id) } }
    }

    /** Every archived capture id of the signed-in user. THROWS on error (a pre-053
     *  server has no such column → PostgREST 400) so the caller falls back to its
     *  local cache instead of un-archiving everything. */
    suspend fun archivedIds(): Set<String> =
        client.from("captures")
            .select(Columns.list("id")) { filter { filterNot("archived_at", FilterOperator.IS, "null") } }
            .decodeList<IdRow>().map { it.id }.toSet()
}

/** wake_window_history (migration 015): one row per (user, local day) with the
 *  day's FIRST app input as local HH:MM — the sample calibrate_wake_windows medians
 *  to time the morning brief. Writes through `record_wake_window` (migration 056:
 *  keeps the EARLIEST sample of the day, derives the weekday server-side); a
 *  pre-056 server (function missing → 404) gets a direct first-wins upsert. */
class WakeWindowClient(private val client: SupabaseClient) {
    @Serializable private data class RpcParams(val p_local_date: String, val p_first_input_local: String)
    @Serializable private data class Row(
        val user_id: String,
        val local_date: String,
        val first_input_local: String,
        val weekday: Int,
    )

    suspend fun record(userId: String, sample: tech.csalliance.unstuck.core.logic.WakeWindowSample) {
        try {
            client.postgrest.rpc("record_wake_window", RpcParams(sample.localDate, sample.firstInputLocal))
        } catch (e: io.ktor.client.plugins.ResponseException) {
            if (e.response.status.value != 404) throw e
            client.from("wake_window_history").upsert(Row(userId, sample.localDate, sample.firstInputLocal, sample.weekday)) {
                onConflict = "user_id,local_date"
                ignoreDuplicates = true
            }
        }
    }
}
