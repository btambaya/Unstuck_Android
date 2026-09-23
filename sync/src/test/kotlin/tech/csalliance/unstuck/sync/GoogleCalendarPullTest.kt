package tech.csalliance.unstuck.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.CalendarConnection
import tech.csalliance.unstuck.core.model.CalendarProvider
import tech.csalliance.unstuck.core.model.ExternalEvent
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset

// The Google Calendar pull against a real (in-memory) LocalStore and a fake
// calendar-sync — the Android half of iOS build 81's C18 fixes (audit 2026-09-22):
// the Boolean verdict "Sync now" reports (C18e), a disconnect's purge never undone by
// a pull in flight (C18f), no writes after a user switch (C18g), imported meetings
// cleared once no connection is left (C18h), unchanged meetings not rewritten (C18i).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleCalendarPullTest {

    private lateinit var store: LocalStore
    private lateinit var write: WriteThrough

    private var uid: String? = "me"
    private var connections: List<CalendarConnection> = listOf(conn("c1"))
    private var listFails = false
    private var listCalls = 0
    private var events: suspend () -> CalendarClient.EventsResponse = { CalendarClient.EventsResponse(emptyList()) }
    private val upserts = mutableListOf<String>()
    private val deletes = mutableListOf<String>()
    private var nowMs = Instant.parse("2026-09-22T12:00:00Z").toEpochMilli()

    @Before fun setup() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnstuckDatabase::class.java)
            .allowMainThreadQueries().build()
        store = LocalStore(db)
        write = WriteThrough(store)
    }

    private fun conn(id: String) = CalendarConnection(
        id = id, provider = CalendarProvider.GOOGLE, accountEmail = "$id@x.y", displayName = "$id@x.y",
        selectedCalendarIds = listOf("primary"), colorSlot = 0, connectedAt = "2026-09-01T00:00:00Z",
    )

    private fun event(id: String, connectionId: String = "c1", summary: String = "Standup") = ExternalEvent(
        id = id, connectionId = connectionId, calendarId = "primary", summary = summary,
        start = "2026-09-23T10:00:00.000Z", end = "2026-09-23T10:30:00.000Z",
    )

    private fun respond(vararg evs: ExternalEvent, failures: List<CalendarClient.EventFailure> = emptyList()) {
        events = { CalendarClient.EventsResponse(evs.toList(), failures) }
    }

    private fun gBlock(id: String, connectionId: String = "c1", date: String = "2026-09-23") = CalBlock(
        id = id, taskId = null, taskName = "Meeting", startTime = "10:00", durationMinutes = 30, date = date,
        externalEventId = id.removePrefix("g_"), externalConnectionId = connectionId, kind = CalBlockKind.EXTERNAL,
    )

    private fun newPull() = GoogleCalendarPull(
        store = store,
        currentUserId = { uid },
        listConnections = { listCalls++; if (listFails) throw IOException("offline"); connections },
        pullEvents = { _, _ -> events() },
        upsertBlock = { upserts += it.id; write.upsertCalBlock(it) },
        deleteBlock = { deletes += it; write.deleteCalBlock(it) },
        nowMs = { nowMs },
        zone = { ZoneOffset.UTC },
    )

    private suspend fun seed(b: CalBlock) = store.upsert(Tables.CAL_BLOCKS, b, CalBlock.serializer(), b.id)
    private suspend fun ids() = store.blocks().first().map { it.id }.toSet()

    @Test fun aCleanPullImportsTheMeetingsAndSaysSo() = runTest {
        respond(event("e1"), event("e2"))
        assertTrue(newPull().pull())
        assertEquals(setOf("g_e1", "g_e2"), ids())
    }

    @Test fun unchangedMeetingsAreNotRewrittenButAChangedOneIs() = runTest {
        val pull = newPull()
        respond(event("e1"), event("e2"))
        pull.pull()
        assertEquals(listOf("g_e1", "g_e2"), upserts)
        upserts.clear()
        pull.pull()
        assertTrue("an unchanged meeting is not written again", upserts.isEmpty())
        respond(event("e1", summary = "Moved standup"), event("e2"))
        pull.pull()
        assertEquals(listOf("g_e1"), upserts)
        assertEquals("Moved standup", store.blocks().first().first { it.id == "g_e1" }.taskName)
    }

    @Test fun noConnectionLeftClearsOnlyTheImportedMeetings() = runTest {
        seed(gBlock("g_e1"))
        seed(gBlock("g_e2", connectionId = "c9"))
        val task = CalBlock(id = "b1", taskId = "t1", taskName = "Write", startTime = "09:00", durationMinutes = 25,
            date = "2026-09-23", externalEventId = "ev-pushed", kind = CalBlockKind.TASK)
        seed(task)
        connections = emptyList()
        assertTrue(newPull().pull())
        assertEquals("the task block (even one pushed to Google) stays", setOf("b1"), ids())
        assertTrue("the g_ purge never reaches the outbox", store.pending().isEmpty())
    }

    @Test fun anUnreadableConnectionsListReportsFalseAndDeletesNothing() = runTest {
        seed(gBlock("g_e1"))
        listFails = true
        assertFalse(newPull().pull())
        assertEquals(setOf("g_e1"), ids())
    }

    @Test fun anUnreadableEventsCallReportsFalseAndKeepsTheMeetings() = runTest {
        seed(gBlock("g_e1"))
        events = { throw IOException("offline") }
        assertFalse(newPull().pull())
        assertEquals(setOf("g_e1"), ids())
    }

    @Test fun googleFailingForEveryConnectionReportsFalseAndKeepsItsMeetings() = runTest {
        seed(gBlock("g_e1"))
        respond(failures = listOf(CalendarClient.EventFailure("c1", "*", 503, "http_503")))
        assertFalse(newPull().pull())
        assertEquals("unknown is not deleted", setOf("g_e1"), ids())
    }

    @Test fun aDeadTokenAloneIsNotASyncFailure() = runTest {
        respond(failures = listOf(CalendarClient.EventFailure("c1", "*", 401, "invalid_grant")))
        assertTrue(newPull().pull())
    }

    @Test fun aRateLimitBacksOffAndOnlyAManualPullGetsThrough() = runTest {
        val pull = newPull()
        events = { throw CalendarRateLimited() }
        assertFalse(pull.pull())
        assertTrue(pull.backedOff)
        listCalls = 0
        assertTrue("a background pull while backing off is a quiet no-op", pull.pull())
        assertEquals(0, listCalls)
        respond(event("e1"))
        assertTrue("Sync now forgets the back-off and actually pulls", pull.pull(manual = true))
        assertEquals(1, listCalls)
        assertFalse(pull.backedOff)
        assertEquals(setOf("g_e1"), ids())
    }

    @Test fun aGoogleRateLimitInsideTheAnswerAlsoBacksOff() = runTest {
        val pull = newPull()
        respond(failures = listOf(CalendarClient.EventFailure("c1", "primary", 429, "rate_limited")))
        assertFalse(pull.pull())
        assertTrue("picks the 'Google is busy' caption", pull.backedOff)
        nowMs += GoogleCalendarPull.BACKOFF_MS + 1
        assertFalse(pull.backedOff)
    }

    @Test fun aUserSwitchDuringThePullWritesNothingIntoTheNextAccount() = runTest {
        events = { uid = "someone-else"; CalendarClient.EventsResponse(listOf(event("e1")), emptyList()) }
        assertTrue(newPull().pull())
        assertTrue(ids().isEmpty())
        assertTrue(upserts.isEmpty())
    }

    @Test fun aUserSwitchBeforeTheEmptyListNeverPurgesTheNextAccountsMeetings() = runTest {
        seed(gBlock("g_theirs"))
        connections = emptyList()
        val pull = GoogleCalendarPull(
            store = store,
            currentUserId = { uid },
            listConnections = { uid = "someone-else"; emptyList() },
            pullEvents = { _, _ -> error("not reached") },
            upsertBlock = { write.upsertCalBlock(it) },
            deleteBlock = { deletes += it; write.deleteCalBlock(it) },
        )
        assertTrue(pull.pull())
        assertEquals(setOf("g_theirs"), ids())
        assertTrue(deletes.isEmpty())
    }

    @Test fun signedOutIsNothingToDo() = runTest {
        uid = null
        assertTrue(newPull().pull())
        assertEquals(0, listCalls)
    }

    /** A disconnect's purge supersedes every pull still reading: an /events answer read
     *  before the revoke is never applied after it (the purge itself never waits on the
     *  network), and the next pull applies as usual. */
    @Test fun aPullReadBeforeADisconnectIsNeverAppliedAfterItsPurge() = runTest {
        seed(gBlock("g_e1"))
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        events = {
            reached.complete(Unit)
            release.await()
            CalendarClient.EventsResponse(listOf(event("e1"), event("e2")), emptyList())
        }
        val pull = newPull()
        val pulling = launch { pull.pull() }
        reached.await()
        var purged = false
        pull.exclusive {
            store.blocks().first().filter { it.externalConnectionId == "c1" }.forEach { write.deleteCalBlock(it.id) }
            purged = true
        }
        assertTrue("the purge ran while the pull was still reading", purged)
        release.complete(Unit)
        pulling.join()
        assertTrue("the stale answer was dropped", ids().isEmpty())
        assertTrue(upserts.isEmpty())

        respond(event("e3"))
        assertTrue(pull.pull())
        assertEquals("a pull begun after the purge applies", setOf("g_e3"), ids())
    }

    /** Two pulls overlap and the newer answer lands first: the older one must not put
     *  back what the newer one removed, nor remove what it imported. */
    @Test fun anOlderAnswerLandingAfterANewerOneIsDropped() = runTest {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        events = {
            if (++calls == 1) {
                reached.complete(Unit)
                release.await()
                CalendarClient.EventsResponse(listOf(event("old")), emptyList())
            } else {
                CalendarClient.EventsResponse(listOf(event("new")), emptyList())
            }
        }
        val pull = newPull()
        val older = launch { pull.pull() }
        reached.await()
        assertTrue(pull.pull())
        assertEquals(setOf("g_new"), ids())
        release.complete(Unit)
        older.join()
        assertEquals("the older answer changed nothing", setOf("g_new"), ids())
    }
}
