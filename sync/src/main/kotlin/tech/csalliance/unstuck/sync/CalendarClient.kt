package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.ktor.client.call.body
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import tech.csalliance.unstuck.core.model.CalendarConnection
import tech.csalliance.unstuck.core.model.ExternalEvent

// CalendarClient — invokes the existing `calendar-sync` Edge Function (NO new
// Google OAuth client). The Custom Tabs consent flow lives in the app layer;
// this provides the server calls. Port of the iOS CalendarClient.swift.

class CalendarClient(private val client: SupabaseClient) {

    @Serializable data class AuthorizeResponse(val url: String, val state: String)
    @Serializable data class GoogleCalendar(val id: String, val summary: String, val primary: Boolean? = null)
    @Serializable data class ConnectResponse(val id: String, val accountEmail: String, val calendars: List<GoogleCalendar>, val colorSlot: Int? = null)
    @Serializable data class ConnectionsResponse(val connections: List<CalendarConnection>)
    @Serializable data class EventsResponse(val events: List<ExternalEvent>)
    @Serializable data class InsertResponse(val id: String)

    @Serializable private data class AuthorizeBody(val redirectUri: String, val provider: String = "google")
    @Serializable private data class ConnectBody(val code: String, val redirectUri: String, val state: String, val provider: String = "google")
    @Serializable private data class DisconnectBody(val connectionId: String)
    @Serializable private data class InsertBody(val connectionId: String, val calendarId: String, val summary: String, val start: String, val end: String)
    @Serializable private data class PatchBody(val connectionId: String, val calendarId: String, val summary: String?, val start: String?, val end: String?)

    suspend fun authorize(redirectUri: String): AuthorizeResponse =
        client.functions.invoke("calendar-sync/authorize") { method = HttpMethod.Post; setBody(AuthorizeBody(redirectUri)) }.body()

    suspend fun connectGoogle(code: String, redirectUri: String, state: String): ConnectResponse =
        client.functions.invoke("calendar-sync/connect") { method = HttpMethod.Post; setBody(ConnectBody(code, redirectUri, state)) }.body()

    suspend fun disconnect(connectionId: String) {
        client.functions.invoke("calendar-sync/disconnect") { method = HttpMethod.Post; setBody(DisconnectBody(connectionId)) }
    }

    suspend fun listConnections(): List<CalendarConnection> =
        client.functions.invoke("calendar-sync/connections") { method = HttpMethod.Get }.body<ConnectionsResponse>().connections

    suspend fun pullEvents(from: String, to: String, connectionId: String? = null): List<ExternalEvent> =
        client.functions.invoke("calendar-sync/events") {
            method = HttpMethod.Get
            parameter("from", from); parameter("to", to)
            connectionId?.let { parameter("connectionId", it) }
        }.body<EventsResponse>().events

    suspend fun insertEvent(connectionId: String, calendarId: String, summary: String, start: String, end: String): String =
        client.functions.invoke("calendar-sync/events") {
            method = HttpMethod.Post; setBody(InsertBody(connectionId, calendarId, summary, start, end))
        }.body<InsertResponse>().id

    suspend fun patchEvent(eventId: String, connectionId: String, calendarId: String, summary: String?, start: String?, end: String?) {
        client.functions.invoke("calendar-sync/events/$eventId") {
            method = HttpMethod.Patch; setBody(PatchBody(connectionId, calendarId, summary, start, end))
        }
    }

    suspend fun deleteEvent(eventId: String, connectionId: String, calendarId: String) {
        client.functions.invoke("calendar-sync/events/$eventId") {
            method = HttpMethod.Delete
            parameter("connectionId", connectionId); parameter("calendarId", calendarId)
        }
    }
}
