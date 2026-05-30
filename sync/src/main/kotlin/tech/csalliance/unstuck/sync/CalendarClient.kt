package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.ktor.client.call.body
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

class CalendarClient(private val client: SupabaseClient) {

    @Serializable data class AuthorizeResponse(val url: String, val state: String)
    @Serializable data class GoogleCalendar(val id: String, val summary: String, val primary: Boolean? = null)
    @Serializable data class ConnectResponse(val id: String, val accountEmail: String, val calendars: List<GoogleCalendar>, val colorSlot: Int? = null)
    @Serializable data class EventsResponse(val events: List<ExternalEvent>)

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
                )
            }

    suspend fun pullEvents(from: String, to: String, connectionId: String? = null): List<ExternalEvent> =
        client.functions.invoke("calendar-sync/events") {
            method = HttpMethod.Get
            parameter("from", from); parameter("to", to)
            connectionId?.let { parameter("connectionId", it) }
        }.body<EventsResponse>().events

    suspend fun insertEvent(connectionId: String, calendarId: String, summary: String, start: String, end: String): String =
        client.functions.invoke("calendar-sync/events") {
            method = HttpMethod.Post; contentType(ContentType.Application.Json); setBody(InsertBody(connectionId, calendarId, summary, start, end))
        }.body<InsertResponse>().id

    suspend fun patchEvent(eventId: String, connectionId: String, calendarId: String, summary: String?, start: String?, end: String?) {
        client.functions.invoke("calendar-sync/events/$eventId") {
            method = HttpMethod.Patch; contentType(ContentType.Application.Json); setBody(PatchBody(connectionId, calendarId, summary, start, end))
        }
    }

    suspend fun deleteEvent(eventId: String, connectionId: String, calendarId: String) {
        client.functions.invoke("calendar-sync/events/$eventId") {
            method = HttpMethod.Delete
            parameter("connectionId", connectionId); parameter("calendarId", calendarId)
        }
    }
}
