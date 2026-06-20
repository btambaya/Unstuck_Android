package tech.csalliance.unstuck.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.blockKind
import tech.csalliance.unstuck.core.logic.daysSinceCreated
import tech.csalliance.unstuck.core.logic.isCompletedToday
import tech.csalliance.unstuck.core.logic.isCreatedToday
import tech.csalliance.unstuck.core.logic.isTaskBlock
import tech.csalliance.unstuck.core.logic.isUuid
import tech.csalliance.unstuck.core.logic.materializeOccurrences
import tech.csalliance.unstuck.core.logic.newUuid
import tech.csalliance.unstuck.core.logic.recurrenceLabel
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.RecurrenceSerializer
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.DAY_MS
import tech.csalliance.unstuck.core.time.Time

// Bucket helpers (lib/task-bucket.ts), block-kind derivation
// (lib/cal-block-kind.ts), Recurrence JSON round-trips (the tagged union must
// match the web's JSONB shape exactly), and the UUID gate.
class CoreModelsTest {

    // --- TaskBucket ---

    @Test fun isCompletedTodayTrueForNow() = assertTrue(isCompletedToday(mkTask(completedAt = iso(NOW)), NOW))

    @Test fun isCompletedTodayFalseWhenNil() = assertFalse(isCompletedToday(mkTask(completedAt = null), NOW))

    @Test fun isCompletedTodayFalseForYesterday() = assertFalse(isCompletedToday(mkTask(completedAt = iso(NOW - DAY_MS)), NOW))

    @Test fun isCreatedTodayCases() {
        assertTrue(isCreatedToday(mkTask(createdAt = iso(NOW)), NOW))
        assertFalse(isCreatedToday(mkTask(createdAt = "2020-01-01T00:00:00.000Z"), NOW))
    }

    @Test fun daysSinceCreatedCounts() {
        assertEquals(0, daysSinceCreated(mkTask(createdAt = iso(NOW)), NOW))
        assertEquals(3, daysSinceCreated(mkTask(createdAt = iso(NOW - 3 * DAY_MS)), NOW))
    }

    // --- CalBlockKind ---

    @Test fun storedKindWins() {
        assertEquals(CalBlockKind.PLACEHOLDER, blockKind(mkBlock(taskId = "x", kind = CalBlockKind.PLACEHOLDER)))
    }

    @Test fun externalEventIdImpliesExternal() {
        val b = CalBlock(id = "b", taskId = "x", taskName = "n", startTime = "09:00", durationMinutes = 25, date = "2026-05-21", externalEventId = "g_1")
        assertEquals(CalBlockKind.EXTERNAL, blockKind(b))
        assertFalse(isTaskBlock(b))
    }

    @Test fun placeholderAndCalPrefixHeuristics() {
        assertEquals(CalBlockKind.PLACEHOLDER, blockKind(mkBlock(taskId = "placeholder")))
        assertEquals(CalBlockKind.EXTERNAL, blockKind(mkBlock(taskId = "cal-123")))
    }

    @Test fun plainTaskBlock() {
        val b = mkBlock(taskId = "real-task")
        assertEquals(CalBlockKind.TASK, blockKind(b))
        assertTrue(isTaskBlock(b))
    }

    @Test fun blockTimeEventWithNullTaskIsNotTaskBlock() {
        val b = CalBlock(id = "b", taskId = null, taskName = "Lunch", startTime = "12:00", durationMinutes = 60, date = "2026-05-21", kind = CalBlockKind.EXTERNAL)
        assertFalse(isTaskBlock(b))
    }

    // --- Recurrence JSON round-trips ---

    private fun roundTrip(r: Recurrence): Recurrence =
        Json.decodeFromString(Recurrence.serializer(), Json.encodeToString(Recurrence.serializer(), r))

    @Test fun dailyRoundTrips() {
        assertEquals(Recurrence.Daily(), roundTrip(Recurrence.Daily()))
        assertEquals(Recurrence.Daily("2026-09-01"), roundTrip(Recurrence.Daily("2026-09-01")))
    }

    @Test fun weeklyRoundTrips() {
        assertEquals(Recurrence.Weekly(listOf(1, 3, 5)), roundTrip(Recurrence.Weekly(listOf(1, 3, 5))))
    }

    @Test fun monthlyRoundTrips() {
        assertEquals(Recurrence.Monthly("2026-12-31"), roundTrip(Recurrence.Monthly("2026-12-31")))
    }

    @Test fun decodesWebJsonShape() {
        val r = Json.decodeFromString(Recurrence.serializer(), """{"kind":"weekly","daysOfWeek":[0,6],"until":null}""")
        assertEquals(Recurrence.Weekly(listOf(0, 6)), r)
    }

    // Forward-compat: an UNKNOWN recurrence kind must NOT throw (a throw aborts the
    // whole TaskItem decode and the task VANISHES). It degrades to a no-op daily that
    // RecurrenceSerializer.isUnknown() recognises and that materialises zero occurrences.
    @Test fun unknownKindDegradesGracefully() {
        val r = Json.decodeFromString(Recurrence.serializer(), """{"kind":"yearly"}""")
        assertTrue(RecurrenceSerializer.isUnknown(r))
        assertTrue(materializeOccurrences(r, Time.civil(2026, 5, 21), "09:00", 56).isEmpty())
        assertEquals("", recurrenceLabel(r))
    }

    // An unknown kind decodes inside a whole TaskItem to a USABLE task (recurrence
    // present but inert) rather than throwing and dropping the row entirely.
    @Test fun unknownKindKeepsTaskDecodable() {
        val json = """
            {"id":"t1","name":"Ship","estimateMin":25,"recurrence":{"kind":"yearly"},
             "createdAt":"2026-05-21T10:00:00.000Z","updatedAt":"2026-05-21T10:00:00.000Z"}
        """.trimIndent()
        val task = Json.decodeFromString(TaskItem.serializer(), json)
        assertEquals("Ship", task.name)
        assertTrue(RecurrenceSerializer.isUnknown(task.recurrence))
    }

    // --- UUID ---

    @Test fun newUuidIsLowercasedAndValid() {
        val u = newUuid()
        assertEquals(u, u.lowercase())
        assertTrue(isUuid(u))
    }

    @Test fun isUuidRejectsGarbage() {
        assertFalse(isUuid("not-a-uuid"))
        assertFalse(isUuid(""))
    }
}
