package tech.csalliance.unstuck.sync

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Objective
import tech.csalliance.unstuck.core.model.Priority
import tech.csalliance.unstuck.core.model.ReasonAction
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.TaskItem

// The PostgREST boundary contract: snake_case top-level columns, camelCase
// JSONB internals, explicit-null clearing, the duration_sec omit exception,
// FK uuid-or-null, and round-trips. Mirrors DbRowCodecTests.swift.
class DbRowCodecTest {

    private fun task(id: String = "11111111-1111-1111-1111-111111111111") = TaskItem(
        id = id, name = "Ship", estimateMin = 30,
        createdAt = "2026-05-21T10:00:00.000Z", updatedAt = "2026-05-21T10:00:00.000Z",
    )

    @Test fun topLevelSnakeCaseColumns() {
        val o = DbRowCodec.encodeTask(task())
        assertTrue(o.containsKey("estimate_min"))
        assertTrue(o.containsKey("total_focused"))
        assertTrue(o.containsKey("created_at"))
        assertFalse(o.containsKey("estimateMin"))
    }

    @Test fun nestedJsonbStaysCamelCase() {
        val t = task().copy(
            objectives = listOf(Objective("do", done = false, minutes = 10)),
            recurrence = Recurrence.Weekly(listOf(1, 3, 5), until = "2026-08-01"),
        )
        val o = DbRowCodec.encodeTask(t)
        // recurrence.daysOfWeek must NOT be snake_cased.
        assertEquals(listOf(1, 3, 5), o["recurrence"]!!.jsonObject["daysOfWeek"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
        assertTrue(o["objectives"]!!.jsonArray[0].jsonObject.containsKey("text"))
    }

    @Test fun explicitNullClearsCompletedAt() {
        // Un-completed task → completed_at present and explicitly null (clears the column).
        val o = DbRowCodec.encodeTask(task().copy(completedAt = null))
        assertTrue(o.containsKey("completed_at"))
        assertEquals(JsonNull, o["completed_at"])
    }

    @Test fun taskDefaultsMatchWeb() {
        val o = DbRowCodec.encodeTask(task())
        assertEquals(0, o["move_count"]!!.jsonPrimitive.content.toInt())
        assertEquals(false, o["later"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(0, o["tags"]!!.jsonArray.size)
    }

    @Test fun reasonLogOmitsDurationSecWhenNull() {
        val withNull = DbRowCodec.encodeReasonLog(ReasonLog(id = "r", reason = "Bathroom", action = ReasonAction.PAUSE, at = "2026-05-21T10:00:00.000Z", durationSec = null))
        assertFalse(withNull.containsKey("duration_sec"))
        val withVal = DbRowCodec.encodeReasonLog(ReasonLog(id = "r", reason = "Bathroom", action = ReasonAction.PAUSE, at = "2026-05-21T10:00:00.000Z", durationSec = 120))
        assertEquals(120, withVal["duration_sec"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun calBlockNonUuidTaskIdDropsToNull() {
        val b = CalBlock(id = "g_x", taskId = "placeholder", taskName = "n", startTime = "09:00", durationMinutes = 25, date = "2026-05-21", kind = CalBlockKind.TASK)
        assertEquals(JsonNull, DbRowCodec.encodeCalBlock(b)["task_id"])
    }

    @Test fun calBlockUuidTaskIdPreserved() {
        val uuid = "22222222-2222-2222-2222-222222222222"
        val b = CalBlock(id = "blk", taskId = uuid, taskName = "n", startTime = "09:00", durationMinutes = 25, date = "2026-05-21", kind = CalBlockKind.TASK)
        assertEquals(uuid, DbRowCodec.encodeCalBlock(b)["task_id"]!!.jsonPrimitive.content)
    }

    @Test fun taskRoundTripsThroughServerShape() {
        val t = task().copy(
            priority = Priority.URGENT, tags = listOf("deep-work"),
            objectives = listOf(Objective("ship", true, 30)),
            recurrence = Recurrence.Weekly(listOf(0, 6), until = null), lifeArea = "Work", done = true,
            completedAt = "2026-05-21T11:00:00.000Z",
        )
        val back = DbRowCodec.decodeTask(DbRowCodec.encodeTask(t))
        assertEquals(Priority.URGENT, back.priority)
        assertEquals(listOf("deep-work"), back.tags)
        assertEquals(Objective("ship", true, 30), back.objectives?.single())
        assertEquals(Recurrence.Weekly(listOf(0, 6)), back.recurrence)
        assertEquals("Work", back.lifeArea)
        assertEquals("2026-05-21T11:00:00.000Z", back.completedAt)
    }

    @Test fun decodeIgnoresExtraServerColumns() {
        // The server returns user_id + created_at etc. — ignoreUnknownKeys keeps decode happy.
        val withExtra = JsonObject(
            DbRowCodec.encodeTask(task()) + ("user_id" to JsonPrimitive("u1")),
        )
        assertEquals("Ship", DbRowCodec.decodeTask(withExtra).name)
    }

    @Test fun collectionSubtitleEmptyBecomesNull() {
        val o = DbRowCodec.encodeCollection(
            tech.csalliance.unstuck.core.model.ItemCollection(id = "c", name = "C", color = "indigo", subtitle = null, items = emptyList(), sortOrder = 0),
        )
        assertEquals("", o["subtitle"]!!.jsonPrimitive.content) // encoded as ""
        assertNull(DbRowCodec.decodeCollection(o).subtitle) // decoded back to null
    }
}
