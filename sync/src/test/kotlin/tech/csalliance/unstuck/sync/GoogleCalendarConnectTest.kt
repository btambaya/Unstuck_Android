package tech.csalliance.unstuck.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.core.model.CalendarConnection
import tech.csalliance.unstuck.core.model.CalendarProvider
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import java.io.IOException

// The in-app Google connect against a real (in-memory) LocalStore and a fake
// calendar-sync: every way the consent can come back reaches the calendar bar, only a
// code for the state we minted is exchanged, the new connection is seeded locally, and
// a result never lands on the next account (parity with iOS build 81, audit 2026-09-22
// C18).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleCalendarConnectTest {

    private lateinit var store: LocalStore

    private var uid: String? = "me"
    private val exchanged = mutableListOf<Pair<String, String>>()
    private var exchange: suspend () -> CalendarClient.ConnectResponse = { answer() }
    private var firstSync: suspend () -> Boolean = { true }

    @Before fun setup() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnstuckDatabase::class.java)
            .allowMainThreadQueries().build()
        store = LocalStore(db)
    }

    private fun answer(id: String = "c1", calendars: List<CalendarClient.GoogleCalendar> = listOf(CalendarClient.GoogleCalendar("primary", "Me"), CalendarClient.GoogleCalendar("team", "Team"))) =
        CalendarClient.ConnectResponse(id = id, accountEmail = "me@x.y", calendars = calendars, colorSlot = 2)

    private fun newConnect() = GoogleCalendarConnect(
        store = store,
        currentUserId = { uid },
        authorize = { CalendarClient.AuthorizeResponse(url = "https://accounts.google.com/o/oauth2/auth?x", state = "s1") },
        exchange = { code, state -> exchanged += code to state; exchange() },
        firstSync = { firstSync() },
        nowIso = { "2026-09-23T08:00:00Z" },
    )

    private suspend fun conns() = store.connections().first()

    @Test fun aGoodConsentConnectsSeedsTheRowAndSaysSo() = runTest {
        val connect = newConnect()
        assertEquals("https://accounts.google.com/o/oauth2/auth?x", connect.begin())
        assertEquals(CalendarConnectOutcome.CONNECTED, connect.complete("code", "s1"))
        assertEquals(listOf("code" to "s1"), exchanged)
        assertEquals(CalendarConnectOutcome.CONNECTED, connect.outcome.value)
        val row = conns().single()
        assertEquals("c1", row.id)
        assertEquals(CalendarProvider.GOOGLE, row.provider)
        assertEquals("me@x.y", row.accountEmail)
        assertEquals("every readable calendar, as the server selects", listOf("primary", "team"), row.selectedCalendarIds)
        assertEquals(2, row.colorSlot)
    }

    @Test fun aFirstSyncThatFailsStillLeavesTheConnectionOnTheBar() = runTest {
        firstSync = { false }
        val connect = newConnect()
        connect.begin()
        assertEquals(CalendarConnectOutcome.FIRST_SYNC_FAILED, connect.complete("code", "s1"))
        assertEquals("the bar offers the Sync now its caption asks for", listOf("c1"), conns().map { it.id })
    }

    @Test fun aReconnectKeepsTheStoredRow() = runTest {
        val stored = CalendarConnection(
            id = "c1", provider = CalendarProvider.GOOGLE, accountEmail = "me@x.y", displayName = "Me",
            selectedCalendarIds = listOf("primary"), colorSlot = 4, connectedAt = "2026-09-01T00:00:00Z", needsReauth = true,
        )
        store.upsert(Tables.CALENDAR_CONNECTIONS, stored, CalendarConnection.serializer(), stored.id, stored.connectedAt)
        val connect = newConnect()
        connect.begin()
        assertEquals(CalendarConnectOutcome.CONNECTED, connect.complete("code", "s1"))
        assertEquals("the catch-up brings the server's row; the seed doesn't overwrite", listOf(stored), conns())
    }

    @Test fun aServerThatNamesNoCalendarSeedsPrimary() {
        assertEquals(listOf("primary"), answer(calendars = emptyList()).localConnection("t").selectedCalendarIds)
        assertEquals("a missing slot is 0", 0, answer().copy(colorSlot = null).localConnection("t").colorSlot)
    }

    @Test fun aFailedExchangeSaysCouldntConnectAndSeedsNothing() = runTest {
        exchange = { throw IOException("409 already connected") }
        val connect = newConnect()
        connect.begin()
        assertEquals(CalendarConnectOutcome.FAILED, connect.complete("code", "s1"))
        assertEquals(CalendarConnectOutcome.FAILED, connect.outcome.value)
        assertTrue(conns().isEmpty())
    }

    /** A denied or cancelled consent comes back as `?error=access_denied&state=…`. */
    @Test fun aConsentWithNoCodeSaysCouldntConnect() = runTest {
        val connect = newConnect()
        connect.begin()
        assertEquals(CalendarConnectOutcome.FAILED, connect.complete(null, "s1"))
        assertEquals(CalendarConnectOutcome.FAILED, connect.outcome.value)
        assertTrue(exchanged.isEmpty())
        assertEquals("the consent is over: a replay of it is not ours", CalendarConnectOutcome.FAILED, connect.complete("code", "s1"))
        assertTrue(exchanged.isEmpty())
    }

    /** The state lives in memory: once Android killed the process behind the consent tab,
     *  the callback finds none. Same as an unsolicited link — never exchanged — but the
     *  bar still says the connect didn't happen. */
    @Test fun aCallbackWithNoPendingConsentIsReportedButNeverExchanged() = runTest {
        val connect = newConnect()
        assertEquals(CalendarConnectOutcome.FAILED, connect.complete("code", "s1"))
        assertEquals(CalendarConnectOutcome.FAILED, connect.outcome.value)
        assertTrue(exchanged.isEmpty())
        assertTrue(conns().isEmpty())
    }

    @Test fun aMismatchedStateIsNeverExchangedAndKeepsTheRealConsentOpen() = runTest {
        val connect = newConnect()
        connect.begin()
        assertEquals(CalendarConnectOutcome.FAILED, connect.complete("forged", "other"))
        assertTrue(exchanged.isEmpty())
        assertEquals("the user's own callback still completes", CalendarConnectOutcome.CONNECTED, connect.complete("code", "s1"))
        assertEquals(listOf("code" to "s1"), exchanged)
    }

    @Test fun aSwitchToAnotherAccountMidConnectNeverReachesItsBar() = runTest {
        firstSync = { uid = "someone-else"; false }
        val connect = newConnect()
        connect.begin()
        assertEquals(CalendarConnectOutcome.FIRST_SYNC_FAILED, connect.complete("code", "s1"))
        assertNull("B's bar never shows A's result", connect.outcome.value)
    }

    @Test fun aSwitchBeforeTheSeedWritesNothingIntoTheNextAccount() = runTest {
        exchange = { uid = "someone-else"; answer() }
        val connect = newConnect()
        connect.begin()
        connect.complete("code", "s1")
        assertTrue(conns().isEmpty())
        assertNull(connect.outcome.value)
    }

    @Test fun signOutDropsTheHeldResultAndThePendingConsent() = runTest {
        val connect = newConnect()
        connect.begin()
        connect.complete(null, "s1")
        connect.begin()
        connect.signedOut()
        assertNull(connect.outcome.value)
        uid = "next"
        assertEquals(CalendarConnectOutcome.FAILED, connect.complete("code", "s1"))
        assertTrue("the old account's consent is not finished for the next one", exchanged.isEmpty())
    }

    @Test fun theBarConsumesTheResultOnce() = runTest {
        val connect = newConnect()
        connect.begin()
        connect.complete("code", "s1")
        connect.consume()
        assertNull(connect.outcome.value)
    }
}
