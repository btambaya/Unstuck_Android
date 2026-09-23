package tech.csalliance.unstuck.ui.settings

import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.csalliance.unstuck.AppGraph
import tech.csalliance.unstuck.core.model.CalendarConnection
import tech.csalliance.unstuck.core.model.CalendarProvider
import tech.csalliance.unstuck.core.model.CallRequest
import tech.csalliance.unstuck.core.model.CollectionItem
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.core.model.ProfileFact
import tech.csalliance.unstuck.core.model.ProfileFactCategory
import tech.csalliance.unstuck.core.model.ProfileFactSource
import tech.csalliance.unstuck.core.model.TagRow
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.RecordEntity
import tech.csalliance.unstuck.data.db.Tables
import tech.csalliance.unstuck.data.db.UnstuckDatabase
import tech.csalliance.unstuck.sync.WriteThrough
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.EXPORT_FAILED
import tech.csalliance.unstuck.ui.ViewModelDrain
import tech.csalliance.unstuck.ui.exportOutcomeMessage

/**
 * "Export everything" (Android audit 2026-09-23, A18). It used to build the file
 * from the ViewModel's WhileSubscribed flows, which read empty for any table no
 * screen was collecting — open the app, go straight to Settings › Export, and the
 * file had `"collections": []` and `"tags": []` under an "Exported.". Here nothing
 * collects any flow: every table must still come from the store, and anything that
 * can't be read must be named, not passed off as a full copy. Same SUT shape as
 * AppViewModelTest (in-memory Room, no coordinator, Main on a test dispatcher).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ExportEverythingTest {

    private lateinit var db: UnstuckDatabase
    private lateinit var store: LocalStore
    private lateinit var graph: AppGraph
    private val dispatcher = StandardTestDispatcher()
    private val drain = ViewModelDrain(dispatcher.scheduler)

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnstuckDatabase::class.java)
            .allowMainThreadQueries().build()
        store = LocalStore(db)
        graph = AppGraph(ApplicationProvider.getApplicationContext(), configured = false, storeOverride = store)
    }

    @After fun teardown() {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        drain.drain()
        Dispatchers.resetMain()
    }

    private fun TestScope.vm() =
        AppViewModel(graph = graph, writeOverride = WriteThrough(graph.store), currentUidProvider = { "me" }, currentNameProvider = { "Ada" })
            .also { created ->
                drain.track(created)
                backgroundScope.coroutineContext.job.invokeOnCompletion { runCatching { created.viewModelScope.cancel() } }
            }

    private suspend fun seed() {
        store.upsert(Tables.TASKS, TaskItem(id = "t1", name = "Renew passport", estimateMin = 25, createdAt = "2026-09-20T10:00:00Z", updatedAt = "2026-09-20T10:00:00Z"), TaskItem.serializer(), "t1")
        val packing = ItemCollection(
            id = "c1", name = "Packing", color = "indigo", sortOrder = 0, ownerId = "me",
            items = listOf(CollectionItem(id = "i1", body = "passport", at = "2026-09-23T08:00:00Z")),
        )
        store.upsert(Tables.COLLECTIONS, packing, ItemCollection.serializer(), "c1")
        store.upsert(Tables.TAGS, TagRow("g1", "errand", null, 0), TagRow.serializer(), "g1")
        store.upsert(Tables.LIFE_AREAS, LifeArea("a1", "Home", "teal", 0), LifeArea.serializer(), "a1")
        store.upsert(
            Tables.CALENDAR_CONNECTIONS,
            CalendarConnection("k1", CalendarProvider.GOOGLE, "ada@example.com", "Ada", listOf("primary"), 0, connectedAt = "2026-09-01T00:00:00Z"),
            CalendarConnection.serializer(), "k1",
        )
        val at = "2026-09-20T10:00:00Z"
        store.upsert(Tables.PROFILE_FACTS, ProfileFact("f1", ProfileFactCategory.PERSON, "Sister is Amina", ProfileFactSource.CHAT, createdAt = at, updatedAt = at), ProfileFact.serializer(), "f1")
        store.upsert(Tables.PROFILE_FACTS, ProfileFact("f2", ProfileFactCategory.PERSON, "Forgotten", ProfileFactSource.CHAT, active = false, createdAt = at, updatedAt = at), ProfileFact.serializer(), "f2")
        store.upsert(Tables.CALL_REQUESTS, CallRequest(id = "r1", callAt = "2026-09-24T08:00:00Z", label = "Morning plan"), CallRequest.serializer(), "r1")
    }

    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun JsonObject.ids(key: String): List<String> = getValue(key).jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }

    @Test fun `with no screen collecting anything, every table still goes into the file`() = runTest(dispatcher) {
        seed()
        val export = vm().exportJson()

        val file = parse(export.json)
        assertEquals("the lists, with their items", listOf("c1"), file.ids("collections"))
        assertEquals(listOf("i1"), file.getValue("collections").jsonArray[0].jsonObject.getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(listOf("g1"), file.ids("tags"))
        assertEquals(listOf("a1"), file.ids("lifeAreas"))
        assertEquals(listOf("t1"), file.ids("tasks"))
        assertEquals(listOf("k1"), file.ids("calendarConnections"))
        assertEquals("a forgotten fact stays forgotten", listOf("f1"), file.ids("profileFacts"))
        assertEquals(listOf("r1"), file.ids("callRequests"))
        assertEquals(emptyList<String>(), export.missing)
        assertEquals(JsonArray(emptyList()), file.getValue("incomplete"))
        assertEquals("Exported.", exportOutcomeMessage(export.missing))
    }

    @Test fun `rows that can't be read are named in the file and to the user`() = runTest(dispatcher) {
        seed()
        // A tag row this build can't decode (no name / sortOrder) — it can't go into
        // the file, so the export must not claim to be complete.
        db.records().upsertOne(RecordEntity(Tables.TAGS, "g2", """{"id":"g2"}"""))
        val export = vm().exportJson()

        assertEquals(listOf("tags"), export.missing)
        val file = parse(export.json)
        assertEquals("the readable tag still goes out", listOf("g1"), file.ids("tags"))
        assertEquals(listOf("tags"), file.getValue("incomplete").jsonArray.map { it.jsonPrimitive.content })
        val message = exportOutcomeMessage(export.missing)
        assertTrue(message, message.startsWith("Exported, but") && message.contains("tags"))
    }

    @Test fun `exportTo writes the whole file to the picked document and reports it`() = runTest(dispatcher) {
        seed()
        val uri = Uri.parse("content://test/unstuck-export.json")
        val out = java.io.ByteArrayOutputStream()
        shadowOf(graph.appContext.contentResolver).registerOutputStream(uri, out)
        val done = CompletableDeferred<Pair<String, Boolean>>()

        vm().exportTo(uri) { m, failed -> done.complete(m to failed) }

        assertEquals("Exported." to false, done.await())
        assertEquals(listOf("c1"), parse(out.toString(Charsets.UTF_8.name())).ids("collections"))
    }

    @Test fun `a write that fails says so`() = runTest(dispatcher) {
        val uri = Uri.parse("content://test/broken.json")
        shadowOf(graph.appContext.contentResolver).registerOutputStream(uri, object : java.io.OutputStream() {
            override fun write(b: Int) { throw java.io.IOException("disk full") }
        })
        val done = CompletableDeferred<Pair<String, Boolean>>()

        vm().exportTo(uri) { m, failed -> done.complete(m to failed) }

        assertEquals(EXPORT_FAILED to true, done.await())
    }
}
