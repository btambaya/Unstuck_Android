package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import tech.csalliance.unstuck.core.model.CalendarConnection
import tech.csalliance.unstuck.core.model.CalendarProvider
import tech.csalliance.unstuck.core.model.ExternalEvent

// CalendarClient — invokes the existing `calendar-sync` Edge Function (NO new
// Google OAuth client). The Custom Tabs consent flow lives in the app layer;
// this provides the server calls. Port of the iOS CalendarClient.swift.

/** The Google event a block mapped to no longer exists (PATCH → 404 `event_gone`):
 *  the caller clears the stale external_event_id and falls through to INSERT. */
class CalendarEventGone : Exception("event_gone")

/** The provider / edge function rate-limited us (429): the caller backs off. */
class CalendarRateLimited : Exception("rate_limited")

class CalendarClient(private val client: SupabaseClient) {

    @Serializable data class AuthorizeResponse(val url: String, val state: String)
    @Serializable data class GoogleCalendar(val id: String, val summary: String, val primary: Boolean? = null)
    @Serializable data class ConnectResponse(val id: String, val accountEmail: String, val calendars: List<GoogleCalendar>, val colorSlot: Int? = null) {
        /** The row /connect just stored, as far as its answer tells: seeded locally until
         *  the catch-up brings the server's own (iOS ConnectResponse.localConnection, build
         *  81, audit 2026-09-22 C18). The server selects every readable calendar, or
         *  "primary" when Google listed none. */
        fun localConnection(connectedAt: String) = CalendarConnection(
            id = id, provider = CalendarProvider.GOOGLE, accountEmail = accountEmail, displayName = accountEmail,
            selectedCalendarIds = calendars.map { it.id }.ifEmpty { listOf("primary") },
            colorSlot = colorSlot ?: 0, connectedAt = connectedAt,
        )
    }
    /** One connection whose fetch failed inside /events (contract 2026-09): the
     *  clients must NOT treat its missing events as deletions. `status` is the
     *  provider's HTTP status (401 revoked / 429 rate limit / 5xx); `reason` a short
     *  code (e.g. `invalid_grant`). */
    @Serializable data class EventFailure(
        val connectionId: String,
        val calendarId: String? = null,
        val status: Int? = null,
        val reason: String? = null,
    ) {
        /** 401 / a dead refresh token: the connection needs a fresh consent. */
        val needsReauth: Boolean
            get() = status == 401 || reason == "invalid_grant" || reason == "needs_reauth" || reason == "unauthorized"
    }
    @Serializable data class EventsResponse(val events: List<ExternalEvent>, val failures: List<EventFailure> = emptyList()) {
        /** True when Google answered for NONE of [connections]: each failed whole (token
         *  mint / unreachable — calendarId "*" or none) or on every selected calendar, and
         *  not only for a dead token (the bar already offers "Reconnect Google" for that).
         *  calendar-sync reports Google's 429 / 5xx / 403 inside a 200's `failures`, never
         *  as an HTTP error, so this is how "Sync now" learns it read nothing. One calendar
         *  failing next to a readable one is not "nothing" (parity with iOS build 81,
         *  audit 2026-09-22 C18). */
        fun readNothing(connections: List<CalendarConnection>): Boolean {
            if (connections.isEmpty() || failures.isEmpty() || failures.all { it.needsReauth }) return false
            return connections.all { conn ->
                val own = failures.filter { it.connectionId == conn.id }
                if (own.any { (it.calendarId ?: "*") == "*" }) return@all true
                val failedCalendars = own.mapNotNull { it.calendarId }.toSet()
                own.isNotEmpty() && conn.selectedCalendarIds.all { it in failedCalendars }
            }
        }
    }

    // The /connections endpoint returns raw DB rows (snake_case) — unlike /connect,
    // which returns camelCase. Decode the snake_case shape, then map to the domain model.
    @Serializable private data class ConnRow(
        val id: String,
        val provider: CalendarProvider = CalendarProvider.GOOGLE,
        @SerialName("account_email") val accountEmail: String = "",
        @SerialName("display_name") val displayName: String = "",
        @SerialName("selected_calendar_ids") val selectedCalendarIds: List<String> = emptyList(),
        @SerialName("color_slot") val colorSlot: Int = 0,
        @SerialName("last_sync_cursor") val lastSyncCursor: String? = null,
        @SerialName("connected_at") val connectedAt: String = "",
        @SerialName("needs_reauth") val needsReauth: Boolean = false,
        @SerialName("last_error") val lastError: String? = null,
    )
    @Serializable private data class ConnectionsResponseRaw(val connections: List<ConnRow>)
    @Serializable data class InsertResponse(val id: String)

    // NOTE: `provider` has NO default — kotlinx.serialization omits default values
    // (encodeDefaults is off), so a defaulted `provider = "google"` was dropped from
    // the body and the server rejected it ("Only google supports authorize").
    @Serializable private data class AuthorizeBody(val redirectUri: String, val provider: String)
    @Serializable private data class ConnectBody(val code: String, val redirectUri: String, val state: String, val provider: String)
    @Serializable private data class DisconnectBody(val connectionId: String)
    @Serializable private data class InsertBody(val connectionId: String, val calendarId: String, val summary: String, val start: String, val end: String)
    @Serializable private data class PatchBody(val connectionId: String, val calendarId: String, val summary: String?, val start: String?, val end: String?)

    suspend fun authorize(redirectUri: String): AuthorizeResponse =
        client.functions.invoke("calendar-sync/authorize") { method = HttpMethod.Post; contentType(ContentType.Application.Json); setBody(AuthorizeBody(redirectUri, "google")) }.body()

    suspend fun connectGoogle(code: String, redirectUri: String, state: String): ConnectResponse =
        client.functions.invoke("calendar-sync/connect") { method = HttpMethod.Post; contentType(ContentType.Application.Json); setBody(ConnectBody(code, redirectUri, state, "google")) }.body()

    suspend fun disconnect(connectionId: String) {
        client.functions.invoke("calendar-sync/disconnect") { method = HttpMethod.Post; contentType(ContentType.Application.Json); setBody(DisconnectBody(connectionId)) }
    }

    suspend fun listConnections(): List<CalendarConnection> =
        client.functions.invoke("calendar-sync/connections") { method = HttpMethod.Get }
            .body<ConnectionsResponseRaw>().connections.map {
                CalendarConnection(
                    id = it.id, provider = it.provider, accountEmail = it.accountEmail,
                    displayName = it.displayName, selectedCalendarIds = it.selectedCalendarIds,
                    colorSlot = it.colorSlot, lastSyncCursor = it.lastSyncCursor, connectedAt = it.connectedAt,
                    needsReauth = it.needsReauth, lastError = it.lastError,
                )
            }

    /** Events + per-connection failures. Throws [CalendarRateLimited] on a 429 from
     *  the function itself (the caller backs off instead of hammering). */
    suspend fun pullEvents(from: String, to: String, connectionId: String? = null): EventsResponse =
        try {
            client.functions.invoke("calendar-sync/events") {
                method = HttpMethod.Get
                parameter("from", from); parameter("to", to)
                connectionId?.let { parameter("connectionId", it) }
            }.body<EventsResponse>()
        } catch (e: ResponseException) {
            if (e.response.status.value == 429) throw CalendarRateLimited() else throw e
        }

    suspend fun insertEvent(connectionId: String, calendarId: String, summary: String, start: String, end: String): String =
        client.functions.invoke("calendar-sync/events") {
            method = HttpMethod.Post; contentType(ContentType.Application.Json); setBody(InsertBody(connectionId, calendarId, summary, start, end))
        }.body<InsertResponse>().id

    /** Throws [CalendarEventGone] when the server reports the event no longer exists
     *  (404 — the user deleted it in Google, or it was removed at disconnect):
     *  the caller re-INSERTs instead of patching a ghost forever. */
    suspend fun patchEvent(eventId: String, connectionId: String, calendarId: String, summary: String?, start: String?, end: String?) {
        try {
            client.functions.invoke("calendar-sync/events/$eventId") {
                method = HttpMethod.Patch; contentType(ContentType.Application.Json); setBody(PatchBody(connectionId, calendarId, summary, start, end))
            }
        } catch (e: ResponseException) {
            when (e.response.status.value) {
                404 -> throw CalendarEventGone()
                429 -> throw CalendarRateLimited()
                else -> throw e
            }
        }
    }

    suspend fun deleteEvent(eventId: String, connectionId: String, calendarId: String) {
        client.functions.invoke("calendar-sync/events/$eventId") {
            method = HttpMethod.Delete
            parameter("connectionId", connectionId); parameter("calendarId", calendarId)
        }
    }
}
