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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.csalliance.unstuck.core.logic.PAPrefsLogic
import tech.csalliance.unstuck.core.logic.RitualPrefs
import tech.csalliance.unstuck.core.logic.CallProactivePrefs
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

    /** The server's copy of the onboarding / interview / PA state (user_preferences).
     *  All nullable: an account that never onboarded has no row at all, and a column
     *  that was never set on any device is null. */
    @Serializable
    data class ServerUserPrefs(
        val adhd_struggles: List<String>? = null,
        /** Migration 052 — when the get-to-know-you interview finished on ANY platform. */
        val assistant_interview_done_at: String? = null,
        /** Migration 053 — `{morning, evening, friday, sunday}` jsonb; parse with
         *  [PAPrefsLogic.parseRitualPrefs] (missing keys → defaults, null → none). */
        val pa_rituals: JsonObject? = null,
    ) {
        /** The account's ritual toggles, or null when never set anywhere. */
        val rituals: RitualPrefs? get() = PAPrefsLogic.parseRitualPrefs(pa_rituals)
    }

    /** Read back the account-wide onboarding signals so a second account on the same
     *  device (or a fresh install) reconciles `onboarded` from the SERVER rather than a
     *  device-global flag — plus the interview flag + PA rituals the gateway pins its
     *  local caches from after every pull. Null = no row (never onboarded anywhere) —
     *  and ALSO null on a transport error, so callers treat null as "unknown", never as
     *  "not onboarded" / "not done". */
    suspend fun fetchUserPrefs(userId: String): ServerUserPrefs? =
        client.from("user_preferences")
            .select(Columns.list("adhd_struggles", "assistant_interview_done_at", PAPrefsLogic.RITUALS_COLUMN)) { filter { eq("user_id", userId) } }
            .decodeSingleOrNull<ServerUserPrefs>()

    // ── PA rituals (migration 053: `user_preferences.pa_rituals jsonb`) ─────────
    // Rows below are built as JsonObjects on purpose: the supabase-kt Json omits
    // default-valued fields, so a `@Serializable RitualPrefs(morning = true)` would
    // drop `morning` from the payload and the server would keep a stale value.

    /** Persist the ritual toggles (`{"morning":bool,"evening":bool,"friday":bool,"sunday":bool}`)
     *  account-wide — upsert on user_id like the other prefs writers (a bare UPDATE on
     *  a missing prefs row is a silent zero-row no-op; same lesson as the web). */
    suspend fun setRituals(userId: String, prefs: RitualPrefs) {
        val row = buildJsonObject {
            put("user_id", userId)
            put(PAPrefsLogic.RITUALS_COLUMN, PAPrefsLogic.ritualsJson(prefs))
        }
        client.from("user_preferences").upsert(row) { onConflict = "user_id" }
    }

    // ── timezone (migration 053 C) ──────────────────────────────────────────────

    @Serializable private data class TimezoneParams(val p_tz: String)

    /** Mirror the device's IANA zone into `notification_preferences.timezone`
     *  through `set_timezone` (validated server-side against pg_timezone_names;
     *  a partial upsert that no-ops when unchanged). Returns the RPC's boolean —
     *  false = the server rejected the zone string. The Hydrator calls this on
     *  every pull (Hydrator.pushTimezone, once per zone per process); this entry
     *  point is for an explicit Settings-driven push. Throws on transport / 404. */
    suspend fun setTimezone(tz: String = TimeZone.getDefault().id): Boolean =
        client.postgrest.rpc("set_timezone", TimezoneParams(tz)).decodeAs<Boolean>()

    // ── assistant history (migration 074) ───────────────────────────────────────

    /** `delete_my_assistant_turns()`: clears THIS user's stored Assistant
     *  conversations and returns how many rows went. The privacy policy (§9.5,
     *  §17) promises both the 90-day purge and this control; the function is
     *  scoped to `auth.uid()` server-side, so it can only ever delete the
     *  caller's own rows (parity with iOS build 78, 0f24908). Throws on
     *  transport / auth failure. */
    suspend fun deleteAssistantHistory(): Int =
        client.postgrest.rpc("delete_my_assistant_turns").decodeAs<Int>()

    // ── interview flag (migration 052) ──────────────────────────────────────────

    /** Mirror "the get-to-know-you interview is done" to the ACCOUNT —
     *  `user_preferences.assistant_interview_done_at` — so no other device re-asks
     *  (the web + iOS write the same column). [atIso] = the ISO-8601 instant. Throws
     *  while the column doesn't exist yet (PGRST204) — callers are best-effort and
     *  the next hydrate re-pushes. */
    suspend fun markInterviewDone(userId: String, atIso: String) {
        val row = buildJsonObject {
            put("user_id", userId)
            put("assistant_interview_done_at", atIso)
        }
        client.from("user_preferences").upsert(row) { onConflict = "user_id" }
    }

    // ── usable minutes (migration 007) ──────────────────────────────────────────

    /** Mirror the usable-minutes budget (Settings / the assistant's
     *  `set_usable_minutes`) to user_preferences — upsert on user_id, like the web
     *  `setUsableMinutes`. A null value is NOT sent, so that column keeps what it
     *  had (PostgREST updates only the columns present). Columns:
     *  usable_minutes_per_day / usable_minutes_weekend (check 1…1440 — callers
     *  validate). */
    suspend fun setUsableMinutes(userId: String, perDay: Int?, weekend: Int?) {
        val row = buildJsonObject {
            put("user_id", userId)
            if (perDay != null) put("usable_minutes_per_day", JsonPrimitive(perDay))
            if (weekend != null) put("usable_minutes_weekend", JsonPrimitive(weekend))
        }
        client.from("user_preferences").upsert(row) { onConflict = "user_id" }
    }

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

    // ── proactive calls (migration 072, calls build-out 2026-09-20) ──────────────
    // The three opt-in calls Unstuck can make on its own — morning plan / evening
    // wrap-up / check-in after a block — are ACCOUNT-wide: `dispatch_proactive_calls`
    // reads these columns every 5 min. Same read/write path as the level + lead.

    @Serializable private data class CallProactiveRow(
        val call_morning_enabled: Boolean? = null,
        /** A Postgres `time` — PostgREST emits "08:30:00". */
        val call_morning_time: String? = null,
        val call_evening_enabled: Boolean? = null,
        val call_evening_time: String? = null,
        val call_after_block_enabled: Boolean? = null,
    )

    @Serializable private data class CallProactiveWrite(
        val user_id: String,
        val call_morning_enabled: Boolean,
        val call_morning_time: String,
        val call_evening_enabled: Boolean,
        val call_evening_time: String,
        val call_after_block_enabled: Boolean,
    )

    /** The account's proactive-call toggles + times. Null = no row yet OR a
     *  transport error (callers keep their cached copy); a null column reads as
     *  its default (off / 08:30 / 18:00). Throws on a pre-072 server (PGRST204)
     *  like the other best-effort reads — the caller swallows it. */
    suspend fun fetchCallProactivePrefs(userId: String): CallProactivePrefs? {
        val r = client.from("notification_preferences")
            .select(Columns.list("call_morning_enabled", "call_morning_time", "call_evening_enabled", "call_evening_time", "call_after_block_enabled")) {
                filter { eq("user_id", userId) }
            }
            .decodeSingleOrNull<CallProactiveRow>() ?: return null
        return CallProactivePrefs(
            morningEnabled = r.call_morning_enabled ?: false,
            morningTime = CallProactivePrefs.hhmm(r.call_morning_time) ?: CallProactivePrefs.DEFAULT_MORNING_TIME,
            eveningEnabled = r.call_evening_enabled ?: false,
            eveningTime = CallProactivePrefs.hhmm(r.call_evening_time) ?: CallProactivePrefs.DEFAULT_EVENING_TIME,
            afterBlockEnabled = r.call_after_block_enabled ?: false,
        )
    }

    /** Persist the proactive-call toggles + times (upsert on user_id like the
     *  other prefs writers — a bare UPDATE on a missing row is a silent no-op).
     *  Every column is sent explicitly (no defaults — kotlinx would omit them);
     *  times go up as "HH:MM", which Postgres `time` accepts. Throws on failure
     *  so the caller keeps the write pending. */
    suspend fun setCallProactivePrefs(userId: String, prefs: CallProactivePrefs) {
        client.from("notification_preferences").upsert(
            CallProactiveWrite(
                user_id = userId,
                call_morning_enabled = prefs.morningEnabled, call_morning_time = prefs.morningTime,
                call_evening_enabled = prefs.eveningEnabled, call_evening_time = prefs.eveningTime,
                call_after_block_enabled = prefs.afterBlockEnabled,
            ),
        ) { onConflict = "user_id" }
    }
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
