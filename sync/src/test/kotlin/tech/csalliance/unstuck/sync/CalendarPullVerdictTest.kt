package tech.csalliance.unstuck.sync

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.model.CalendarConnection
import tech.csalliance.unstuck.core.model.CalendarProvider

// Ports iOS CalendarClientVerdictTests (build 81): calendar-sync answers 200 even when
// Google failed every calendar, so the pull must read "Google answered for nobody" out
// of `failures` — else "Sync now" ends on "Synced" with no meetings (audit 2026-09-22 C18).
class CalendarPullVerdictTest {
    private val team = "team@group.calendar.google.com"
    private val c1 = conn("c1", listOf("primary", team))
    private val c2 = conn("c2", listOf("primary"))

    private fun conn(id: String, calendars: List<String>) = CalendarConnection(
        id = id, provider = CalendarProvider.GOOGLE, accountEmail = "$id@x.y", displayName = "$id@x.y",
        selectedCalendarIds = calendars, colorSlot = 0, connectedAt = "2026-09-01T00:00:00Z",
    )
    private fun f(conn: String, cal: String? = null, status: Int? = null, reason: String? = null) =
        CalendarClient.EventFailure(conn, cal, status, reason)
    private fun pull(vararg failures: CalendarClient.EventFailure) = CalendarClient.EventsResponse(emptyList(), failures.toList())

    @Test fun pullFailureFlags() {
        assertTrue(f("c", status = 401).needsReauth)
        assertTrue(f("c", status = 400, reason = "invalid_grant").needsReauth)
        assertFalse(f("c", status = 503, reason = "upstream").needsReauth)
    }

    @Test fun readNothingOnlyWhenGoogleAnsweredForNoConnection() {
        assertFalse("a clean pull", pull().readNothing(listOf(c1)))
        assertFalse("one calendar failing next to a readable one still imported",
            pull(f("c1", team, 404, "not_found")).readNothing(listOf(c1)))
        assertTrue("Google rate-limited every selected calendar",
            pull(f("c1", "primary", 429, "rate_limited"), f("c1", team, 429, "rate_limited")).readNothing(listOf(c1)))
        assertTrue("the whole connection failed (token mint / network)",
            pull(f("c1", "*", 0, "unreachable")).readNothing(listOf(c1)))
        assertTrue("no calendarId = the whole connection", pull(f("c1", status = 503)).readNothing(listOf(c1)))
        assertFalse("c2 was read", pull(f("c1", "*", 503, "http_503")).readNothing(listOf(c1, c2)))
        assertTrue(pull(f("c1", "*", 503, "http_503"), f("c2", "primary", 403, "forbidden")).readNothing(listOf(c1, c2)))
        assertFalse("a dead token alone is the bar's 'Reconnect Google', not a sync failure",
            pull(f("c1", "*", 400, "invalid_grant")).readNothing(listOf(c1)))
        assertTrue("a dead token next to an outage still read nothing",
            pull(f("c1", "*", 400, "invalid_grant"), f("c2", "*", 503, "http_503")).readNothing(listOf(c1, c2)))
        assertFalse(pull(f("c1", "*", 503)).readNothing(emptyList()))
    }

    /** The added getter must not become a wire field (kotlinx only serializes backing fields). */
    @Test fun failuresStillDecodeFromTheServerShape() {
        val json = Json { ignoreUnknownKeys = true }
        val resp = json.decodeFromString(CalendarClient.EventsResponse.serializer(),
            """{"events":[],"failures":[{"connectionId":"c1","calendarId":"primary","status":401,"reason":"invalid_grant"}]}""")
        assertEquals(listOf(f("c1", "primary", 401, "invalid_grant")), resp.failures)
        assertTrue(resp.failures.single().needsReauth)
    }
}
