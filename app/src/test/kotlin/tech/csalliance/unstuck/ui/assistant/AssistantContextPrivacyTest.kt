package tech.csalliance.unstuck.ui.assistant

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.model.CollectionItem
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.core.model.TagRow
import tech.csalliance.unstuck.core.model.TaskItem

/**
 * What leaves the device on every assistant turn.
 *
 * [buildAssistantContext] is serialised and sent to a third-party model
 * provider with EVERY turn (and injected into the realtime voice session's
 * instructions), so its shape is a privacy contract, not just a prompt detail.
 * The published privacy policy (web components/marketing/privacy-content.ts
 * §9.1) describes exactly what these tests assert; if one of them has to
 * change, the policy paragraph changes with it.
 *
 * Privacy audit, 2026-09-12: the snapshot used to carry capture text, every
 * list item's body (including items authored by OTHER people in a list shared
 * with this user) and the names of the user's trusted circle — for a turn as
 * small as "hi". It is now an inventory: ids, names and counts. Contents are
 * fetched on demand through the read tools (get_captures / get_lists).
 *
 * Port of the web's lib/assistant/context-privacy.test.ts, 1:1.
 */
class AssistantContextPrivacyTest {

    private val AT = "2026-09-01T09:00:00.000Z"
    private val CAPTURE_TEXT = "ring the clinic about the biopsy results"
    private val MY_ITEM_TEXT = "oat milk and paracetamol"
    private val THEIR_ITEM_TEXT = "Nadia's divorce solicitor — call Tuesday"
    private val CIRCLE_NAME = "Zubair Ahmed"

    private fun list(id: String, name: String, body: String, mine: Boolean) = ItemCollection(
        id = id, name = name, color = "indigo", subtitle = null,
        items = listOf(
            CollectionItem(id = "$id-1", body = body, at = AT),
            CollectionItem(id = "$id-2", body = "$body (2)", done = true, at = AT),
        ),
        sortOrder = 0, myRole = if (mine) "owner" else "viewer",
    )

    /** The shared in-memory fake, seeded with one of everything that used to
     *  leak. [over] runs last, so a test can empty a table. */
    private fun api(over: AssistantToolsTest.FakeState.() -> Unit = {}): AssistantApi {
        val state = AssistantToolsTest.FakeState().apply {
            tasks += TaskItem(id = "t1", name = "Renew passport", estimateMin = 25, totalFocused = 0, done = false,
                createdAt = AT, updatedAt = AT)
            captures += Capture(id = "cap1", taskId = null, sessionId = null, tag = CaptureTag.IDEA, body = CAPTURE_TEXT, at = AT)
            collections += list("l1", "Shopping", MY_ITEM_TEXT, mine = true)
            collections += list("l2", "Nadia · handover", THEIR_ITEM_TEXT, mine = false)
            people += CirclePerson(CIRCLE_NAME, "active")
            people += CirclePerson("Ana", "invited")
            areas += LifeArea("ar1", "Work", "indigo", 0)
            tags += TagRow("tg1", "errand", null, 0)
            over()
        }
        return AssistantToolsTest().FakeApi(state)
    }

    private suspend fun ctx(over: AssistantToolsTest.FakeState.() -> Unit = {}): JsonObject = buildAssistantContext(api(over))
    private suspend fun json(over: AssistantToolsTest.FakeState.() -> Unit = {}): String = ctx(over).toString()

    private fun JsonObject.rows(key: String): List<Map<String, String>> =
        this[key]!!.jsonArray.map { row -> row.jsonObject.entries.associate { (k, v) -> k to v.jsonPrimitive.content } }

    @Test fun `never carries capture text`() = runTest {
        assertFalse(json().contains(CAPTURE_TEXT))
        assertEquals(listOf(mapOf("id" to "cap1", "tag" to "idea")), ctx().rows("captures"))
    }

    @Test fun `never carries list item text - the user's own OR another person's`() = runTest {
        val out = json()
        assertFalse(out.contains(MY_ITEM_TEXT))
        assertFalse(out.contains(THEIR_ITEM_TEXT))
    }

    @Test fun `never carries the names of people in the trusted circle`() = runTest {
        assertFalse(json().contains(CIRCLE_NAME))
        val people = ctx()["people"]!!.jsonObject
        assertEquals(1, people["active"]!!.jsonPrimitive.int)
        assertEquals(1, people["pending"]!!.jsonPrimitive.int)
    }

    @Test fun `still tells the model what exists - list names, counts, and which are shared with them`() = runTest {
        assertEquals(
            listOf(
                mapOf("id" to "l1", "name" to "Shopping", "items" to "2", "open" to "1"),
                mapOf("id" to "l2", "name" to "Nadia · handover", "items" to "2", "open" to "1", "sharedWithYou" to "true"),
            ),
            ctx().rows("lists"),
        )
    }

    @Test fun `still carries the open tasks the assistant reasons over`() = runTest {
        assertEquals(listOf(mapOf("id" to "t1", "name" to "Renew passport", "estimateMin" to "25")), ctx().rows("tasks"))
    }

    @Test fun `is empty-safe (a brand-new account sends no inventory it does not have)`() = runTest {
        val c = ctx { collections.clear(); captures.clear(); people.clear(); tasks.clear() }
        assertEquals(emptyList<Map<String, String>>(), c.rows("lists"))
        assertEquals(emptyList<Map<String, String>>(), c.rows("captures"))
        assertEquals(0, c["people"]!!.jsonObject["active"]!!.jsonPrimitive.int)
        assertEquals(0, c["people"]!!.jsonObject["pending"]!!.jsonPrimitive.int)
    }
}
