package tech.csalliance.unstuck.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase

/**
 * The read-only `call_requests` mirror (C1-android): hydrate replaces it
 * server-canonically (a bad row is dropped, the rest land), the realtime path
 * is last-write-wins by updated_at (a stale echo can't overwrite a newer row),
 * the local reads the assistant / task editor use (live, get, forTask) come
 * from it, a write's returned row is absorbed at once, and — risk 8 — every
 * hydrate mirrors the device zone through `set_timezone` once per zone.
 * No network: a fake SyncRemote stands in for the gateway.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallRequestsMirrorTest {

    private lateinit var db: UnstuckDatabase
    private lateinit var store: LocalStore
    private lateinit var remote: FakeRemote
    private lateinit var hydrator: Hydrator
    private lateinit var mirror: CallRequestsMirror

    private class FakeRemote : SyncRemote {
        var rows: MutableMap<String, List<JsonObject>> = mutableMapOf()
        val rpcs = mutableListOf<Pair<String, JsonObject>>()
        var failRpc: Throwable? = null
        override suspend fun fetchAll(table: String): List<JsonObject> = rows[table].orEmpty()
        override suspend fun upsert(table: String, row: JsonObject, userId: String) {}
        override suspend fun delete(table: String, id: String) {}
        override suspend fun rpc(fn: String, params: JsonObject) {
            failRpc?.let { throw it }
            rpcs += fn to params
        }
    }

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnstuckDatabase::class.java)
            .allowMainThreadQueries().build()
        store = LocalStore(db)
        remote = FakeRemote()
        hydrator = Hydrator(remote, store)
        hydrator.zoneId = { "Europe/London" }
        mirror = CallRequestsMirror(store)
    }

    @After fun teardown() = db.close()

    private fun row(
        id: String, status: String = "scheduled", callAt: String = "2026-09-02T14:45:00+00:00",
        taskId: String? = null, updatedAt: String = "2026-09-01T10:00:00+00:00", snoozeUntil: String? = null,
        label: String = "speak to James",
    ): JsonObject = Json.parseToJsonElement(
        """{"id":"$id","user_id":"u","task_id":${taskId?.let { "\"$it\"" } ?: "null"},"block_id":null,
           "call_at":"$callAt","lead_min":null,"label":"$label","notes":["ask about the deck"],"status":"$status",
           "snooze_until":${snoozeUntil?.let { "\"$it\"" } ?: "null"},"outcome_notes":null,"call_id":null,
           "attempts":0,"created_at":"2026-09-01T10:00:00+00:00","updated_at":"$updatedAt"}""",
    ).jsonObject

    @Test fun `hydrate replaces the mirror server-canonically and drops only a bad row`() = runTest {
        // A stale local row that the server no longer has must vanish.
        store.upsert(Tables.CALL_REQUESTS, CallRequest(id = "gone", callAt = "2026-09-02T14:45:00.000Z"), CallRequest.serializer(), "gone")
        remote.rows[Tables.CALL_REQUESTS] = listOf(row("c1"), row("c2", status = "done"), Json.parseToJsonElement("""{"label":"no id"}""").jsonObject)
        hydrator.hydrate("u")
        val all = mirror.all().map { it.id }.toSet()
        assertEquals(setOf("c1", "c2"), all)
        assertNull(mirror.get("gone"))
        assertEquals(listOf("ask about the deck"), mirror.get("c1")!!.notes)
        assertEquals(emptyList<String>(), mirror.get("c1")!!.outcomeNotes)
    }

    @Test fun `a realtime echo older than the local row is skipped, a newer one lands, a delete removes`() = runTest {
        val newer = DbRowCodec.decodeCallRequest(row("c1", status = "snoozed", updatedAt = "2026-09-01T12:00:00+00:00", snoozeUntil = "2026-09-02T15:05:00+00:00"))
        store.upsertIfNewer(Tables.CALL_REQUESTS, newer, CallRequest.serializer(), newer.id, newer.updatedAt)
        val stale = DbRowCodec.decodeCallRequest(row("c1", status = "scheduled", updatedAt = "2026-09-01T11:00:00+00:00"))
        assertFalse(store.upsertIfNewer(Tables.CALL_REQUESTS, stale, CallRequest.serializer(), stale.id, stale.updatedAt))
        assertEquals("snoozed", mirror.get("c1")!!.status)
        val cancelled = DbRowCodec.decodeCallRequest(row("c1", status = "cancelled", updatedAt = "2026-09-01T13:00:00+00:00"))
        assertTrue(store.upsertIfNewer(Tables.CALL_REQUESTS, cancelled, CallRequest.serializer(), cancelled.id, cancelled.updatedAt))
        assertEquals("cancelled", mirror.get("c1")!!.status)
        store.delete(Tables.CALL_REQUESTS, "c1")
        assertNull(mirror.get("c1"))
    }

    @Test fun `live reads are the live statuses soonest by effective time, forTask finds the anchor`() = runTest {
        remote.rows[Tables.CALL_REQUESTS] = listOf(
            row("late", callAt = "2026-09-02T16:00:00+00:00"),
            row("soon", callAt = "2026-09-02T14:00:00+00:00", taskId = "t1"),
            row("snoozed", status = "snoozed", callAt = "2026-09-02T13:00:00+00:00", snoozeUntil = "2026-09-02T15:00:00+00:00"),
            row("ringing", status = "calling", callAt = "2026-09-02T14:30:00+00:00"),
            row("done", status = "done", callAt = "2026-09-01T09:00:00+00:00", taskId = "t1"),
            row("cancelled", status = "cancelled", callAt = "2026-09-02T12:00:00+00:00"),
        )
        hydrator.hydrate("u")
        assertEquals(listOf("soon", "ringing", "snoozed", "late"), mirror.live().map { it.id })
        assertEquals("soon", mirror.forTask("t1")!!.id)
        assertNull(mirror.forTask("t9"))
        assertEquals(6, mirror.all().size)
        assertEquals(listOf("soon", "ringing", "snoozed", "late"), mirror.observe().first().filter { it.isLive }.sortedBy { it.effectiveAtMs }.map { it.id })
    }

    @Test fun `absorb writes the server's returned row unless the local copy is newer`() = runTest {
        val booked = CallRequest(id = "c1", callAt = "2026-09-02T14:45:00.000Z", label = "speak to James", updatedAt = "2026-09-01T10:00:00.000Z")
        mirror.absorb(booked)
        assertEquals("speak to James", mirror.get("c1")!!.label)
        // A realtime echo overtook the write response: the response must not roll it back.
        val echoed = booked.copy(status = "calling", updatedAt = "2026-09-01T10:00:05.000Z")
        mirror.absorb(echoed)
        mirror.absorb(booked)
        assertEquals("calling", mirror.get("c1")!!.status)
    }

    @Test fun `every hydrate mirrors the device zone once per zone, and a failed push is retried`() = runTest {
        hydrator.hydrate("u")
        hydrator.hydrate("u")
        assertEquals(1, remote.rpcs.size)
        assertEquals(Hydrator.SET_TIMEZONE_RPC, remote.rpcs[0].first)
        assertEquals("Europe/London", remote.rpcs[0].second[Hydrator.SET_TIMEZONE_PARAM]!!.jsonPrimitive.content)
        assertEquals("Europe/London", hydrator.timezoneSent)
        // The user flew: a new zone is pushed once more.
        hydrator.zoneId = { "America/New_York" }
        hydrator.hydrate("u")
        assertEquals(2, remote.rpcs.size)
        assertEquals("America/New_York", remote.rpcs[1].second[Hydrator.SET_TIMEZONE_PARAM]!!.jsonPrimitive.content)
        // Offline / a pre-053 server: the push fails, the pull itself is unaffected, and the next pull retries.
        hydrator.zoneId = { "Asia/Tokyo" }
        remote.failRpc = RpcRejected(404, "no such function")
        hydrator.hydrate("u")
        assertEquals("America/New_York", hydrator.timezoneSent)
        remote.failRpc = null
        hydrator.hydrate("u")
        assertEquals("Asia/Tokyo", hydrator.timezoneSent)
        assertEquals(3, remote.rpcs.size)
    }
}
