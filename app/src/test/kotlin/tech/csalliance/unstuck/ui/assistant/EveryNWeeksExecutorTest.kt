package tech.csalliance.unstuck.ui.assistant

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.ReceiptArgs
import tech.csalliance.unstuck.core.logic.deriveReceipt
import tech.csalliance.unstuck.core.logic.occurrenceId
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.TaskItem
import java.io.File

/**
 * set_task_recurrence's intervalWeeks and schedule_task's off-week guard
 * (every-n-weeks spec §7.2, §7.3, §9.4), replayed from THE shared executor
 * vectors X1–X9 — the same cases web and iOS run. Zubair's morning call first:
 * create_task put today's 10:30, then "every two weeks on Thursdays".
 *
 * The vectors are read from core's generated copy (the generator's one Kotlin
 * output of lib/recurrence-vectors.json), so there is no second copy here.
 */
class EveryNWeeksExecutorTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val root: JsonObject = loadVectors()
    private val taskId = root["taskId"]!!.jsonPrimitive.content
    private val exec = root["executor"]!!.jsonObject
    private val cases = exec["cases"]!!.jsonArray.associateBy { it.str("id")!! }

    private fun loadVectors(): JsonObject {
        val rel = "core/src/test/kotlin/tech/csalliance/unstuck/core/RecurrenceVectors.generated.kt"
        val f = listOf(File("../$rel"), File(rel)).first { it.exists() }
        val src = f.readText()
        val open = "const val JSON: String = \"\"\""
        val body = src.substring(src.indexOf(open) + open.length, src.lastIndexOf("\"\"\""))
        return json.parseToJsonElement(body.replace("\${\"$\"}", "$")).jsonObject
    }

    private fun JsonElement.str(k: String): String? = (jsonObject[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement.strs(k: String): List<String> = jsonObject[k]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    private fun rule(e: JsonElement?): Recurrence? =
        if (e == null || e is JsonNull) null else json.decodeFromJsonElement(Recurrence.serializer(), e)

    private class Run(val api: AssistantToolsTest.FakeApi, val scratch: TurnScratch) {
        val state get() = api.state
    }

    private fun setup(c: JsonElement): Run {
        val api = AssistantToolsTest().FakeApi()
        val scratch = TurnScratch()
        api.state.today = c.str("today")
        val t = exec["task"]!!.jsonObject
        api.state.tasks += TaskItem(
            id = taskId, name = t["name"]!!.jsonPrimitive.content, estimateMin = 60,
            recurrence = rule(c.jsonObject["recurrence"]),
            createdAt = "2026-09-20T07:00:00Z", updatedAt = "2026-09-20T07:00:00Z",
        )
        c.jsonObject["blocks"]!!.jsonArray.forEachIndexed { i, b ->
            val o = b.jsonObject
            val id = b.str("occurrenceOf")?.let { occurrenceId(taskId, it) } ?: "plain-$i"
            api.state.blocks += CalBlock(
                id = id, taskId = taskId, taskName = "Office Focus", startTime = b.str("startTime")!!, durationMinutes = 60,
                date = b.str("date")!!, kind = CalBlockKind.TASK, done = (o["done"] as? JsonPrimitive)?.booleanOrNull == true,
            )
            if ((o["placedThisTurn"] as? JsonPrimitive)?.booleanOrNull == true) scratch.placedBlocks[taskId] = id
        }
        return Run(api, scratch)
    }

    private suspend fun Run.call(call: JsonElement): String {
        val args = LinkedHashMap<String, JsonElement>()
        args["taskId"] = JsonPrimitive(taskId)
        call.jsonObject["args"]!!.jsonObject.forEach { (k, v) -> args[k] = v }
        return runAssistantTool(call.str("tool")!!, ToolArgs(JsonObject(args)), api, scratch)
    }

    private fun check(where: String, spec: JsonElement, got: String) {
        spec.str("expect")?.let { assertEquals(where, it, got) }
        spec.str("expectPrefix")?.let { assertTrue("$where: $got", got.startsWith(it)) }
        spec.strs("expectContains").forEach { assertTrue("$where: $got", got.contains(it)) }
    }

    private fun Run.stored(): Recurrence? = state.tasks.first { it.id == taskId }.recurrence
    private fun Run.live(): List<String> = state.blocks.filter { it.taskId == taskId && !it.done && !it.skipped }.map { it.date }

    /** Runs one vector case and asserts everything it pins. */
    private suspend fun replay(id: String): Run {
        val c = cases[id]!!
        val run = c.str("after")?.let { replay(it) } ?: setup(c)
        c.jsonObject["call"]?.let { check(id, c, run.call(it)) }
        c.jsonObject["calls"]?.jsonArray?.forEachIndexed { i, call -> check("$id.$i", call, run.call(call)) }
        if (c.jsonObject.containsKey("expectRecurrence")) assertEquals("$id stored rule", rule(c.jsonObject["expectRecurrence"]), run.stored())
        for (d in c.strs("expectKept")) assertTrue("$id keeps $d", run.state.blocks.any { it.date == d && it.id.startsWith("plain-") })
        c.jsonObject["expectMints"]?.jsonArray?.forEach { m ->
            assertTrue("$id mints $m", run.state.blocks.any { it.date == m.str("date") && it.id == m.str("id") })
        }
        for (d in c.strs("expectLiveIncludes")) assertTrue("$id: $d live in ${run.live()}", d in run.live())
        for (d in c.strs("expectLiveExcludes")) assertFalse("$id: $d not live in ${run.live()}", d in run.live())
        return run
    }

    @Test fun `the vectors cover X1 to X9`() {
        assertEquals((1..9).map { "X$it" }, cases.keys.toList())
    }

    /** Zubair's turn: every 2 weeks on Thursdays from the Thursday create_task
     *  placed, today's 10:30 kept and named in the reply. */
    @Test fun X1_zubairsTurn() = runTest {
        val run = replay("X1")
        // The three mints only (today's plain block kept, no other rows).
        assertEquals(listOf("2026-09-24", "2026-10-08", "2026-10-22", "2026-11-05"), run.state.blocks.map { it.date }.sorted())
    }

    @Test fun X2_theStopIgnoresStrayWeeklyParams() = runTest { replay("X2") }

    @Test fun X3_omittedIntervalKeepsTheRhythmAndTheWeeks() = runTest {
        val run = replay("X3")
        assertTrue("Fridays on the stored weeks", run.live().filter { it > "2026-09-24" }.all { it in listOf("2026-09-25", "2026-10-09", "2026-10-23", "2026-11-06") })
    }

    @Test fun X4_intervalOneIsPlainWeekly_andSaysTheRhythmChanged() = runTest { replay("X4") }

    @Test fun X5_outOfRange() = runTest {
        val run = replay("X5")
        assertEquals(4, run.state.blocks.size)
    }

    @Test fun X6_notAWholeNumber_neverRounded() = runTest { replay("X6") }

    @Test fun X7_everyNWeeksOnlyWithWeekly() = runTest { replay("X7") }

    @Test fun X8_firstPlacementOntoAnOffWeek_reanchors() = runTest { replay("X8") }

    @Test fun X9_offWeekOnALiveSeries_refusedOnce_thenAOneOff() = runTest {
        val run = replay("X9")
        val again = run.call(cases["X9"]!!.jsonObject["calls"]!!.jsonArray[1])
        assertFalse(again, again.startsWith("error"))
    }

    // ── beyond the vectors ──────────────────────────────────────────────────

    /** The second call of X9 says what it did: a one-off in an off week. */
    @Test fun `an off-week one-off says so`() = runTest {
        val run = setup(cases["X9"]!!)
        val call = cases["X9"]!!.jsonObject["calls"]!!.jsonArray[0]
        run.call(call)
        val ok = run.call(call)
        assertTrue(ok, ok.endsWith(" — a one-off in an off week, off the weeks it repeats on"))
    }

    /** A move of the days with N omitted on a fortnightly series, and the
     *  receipt read from the RESULT: the args say "weekly", the rule is every 2. */
    @Test fun `the receipt names every N weeks from the result`() = runTest {
        val run = setup(cases["X3"]!!)
        val ok = run.call(cases["X3"]!!.jsonObject["call"]!!)
        val r = deriveReceipt("set_task_recurrence", ReceiptArgs(taskId = taskId, kind = "weekly"), ok, run.state.tasks)
        assertEquals("Repeats every 2 weeks — “Office Focus”", r!!.label)
        val weekly = deriveReceipt("set_task_recurrence", ReceiptArgs(taskId = taskId, kind = "weekly"),
            "ok: \"Office Focus\" now repeats weekly on Thu at 10:30 — every week now; it was every 2 weeks", run.state.tasks)
        assertEquals("Repeats weekly — “Office Focus”", weekly!!.label)
    }

    /** weekly → every 2 weeks with nothing placed: week one is the week of the
     *  weekly rule's next Thursday, and the reply names the change. */
    @Test fun `weekly to every 2 weeks keeps the next Thursday`() = runTest {
        val run = setup(cases["X3"]!!)
        run.state.tasks[0] = run.state.tasks[0].copy(recurrence = Recurrence.Weekly(listOf(4)))
        run.state.today = "2026-09-30"
        run.state.blocks.clear()
        for (d in listOf("2026-10-01", "2026-10-08", "2026-10-15", "2026-10-22")) {
            run.state.blocks += CalBlock(occurrenceId(taskId, d), taskId, "Office Focus", "10:30", 60, d, kind = CalBlockKind.TASK)
        }
        val ok = run.call(JsonObject(mapOf("tool" to JsonPrimitive("set_task_recurrence"), "args" to JsonObject(mapOf(
            "kind" to JsonPrimitive("weekly"), "daysOfWeek" to JsonArray(listOf(JsonPrimitive(4))), "intervalWeeks" to JsonPrimitive(2),
        )))))
        assertEquals(Recurrence.EveryNWeeks(2, listOf(4), "2026-09-28"), run.stored())
        assertTrue(ok, ok.startsWith("ok: \"Office Focus\" now repeats every 2 weeks on Thu at 10:30 — every 2 weeks now; it was every week — next Thu 1 Oct, then Thu 15 Oct"))
        assertEquals("open off-week Thursdays deleted", listOf("2026-10-01", "2026-10-15"), run.live().filter { it <= "2026-10-22" }.sorted())
    }

    /** Every N weeks with nothing to anchor on says so, like every other kind. */
    @Test fun `every 2 weeks on a task with no slot asks for one`() = runTest {
        val run = setup(cases["X3"]!!)
        run.state.tasks[0] = run.state.tasks[0].copy(recurrence = null)
        run.state.blocks.clear()
        val ok = run.call(JsonObject(mapOf("tool" to JsonPrimitive("set_task_recurrence"), "args" to JsonObject(mapOf(
            "kind" to JsonPrimitive("weekly"), "daysOfWeek" to JsonArray(listOf(JsonPrimitive(4))), "intervalWeeks" to JsonPrimitive(2),
        )))))
        assertEquals("ok: \"Office Focus\" now repeats every 2 weeks on Thu — it has no calendar slot yet; schedule_task it to place the first one", ok)
        assertEquals(Recurrence.EveryNWeeks(2, listOf(4), "2026-09-21"), run.stored())
    }

    private suspend fun Run.recur(vararg args: Pair<String, JsonElement>): String =
        call(JsonObject(mapOf("tool" to JsonPrimitive("set_task_recurrence"), "args" to JsonObject(mapOf(*args)))))

    /** The checks run in web's and iOS's order — a whole number, then weekly
     *  only, then 1…8 — so the same call gets the same line on every platform. A
     *  whole number past Int (1e10, 12345678901) is out of range, not "not whole"
     *  (web Number.isInteger, iOS Int(exactly:)). Nothing is written either way. */
    @Test fun `intervalWeeks is checked in the same order as web and iOS`() = runTest {
        val run = setup(cases["X7"]!!)
        val days = "daysOfWeek" to JsonArray(listOf(JsonPrimitive(4)))
        assertEquals("error: every N weeks only goes with kind weekly and its days — nothing changed",
            run.recur("kind" to JsonPrimitive("daily"), "intervalWeeks" to JsonPrimitive(9)))
        assertEquals("error: every N weeks only goes with kind weekly and its days — nothing changed",
            run.recur("kind" to JsonPrimitive("monthly"), "intervalWeeks" to JsonPrimitive(2.0)))
        assertEquals("error: intervalWeeks must be a whole number of weeks (2 = every other week) — nothing changed",
            run.recur("kind" to JsonPrimitive("daily"), "intervalWeeks" to JsonPrimitive(2.5)))
        for (big in listOf(JsonPrimitive(1e10), JsonPrimitive(12345678901L), JsonPrimitive(-1e18), Json.parseToJsonElement("1e300"))) {
            assertEquals("$big", "error: every N weeks goes up to every 8 weeks — nothing changed; tell the user this rhythm isn't available",
                run.recur("kind" to JsonPrimitive("weekly"), days, "intervalWeeks" to big))
        }
        // daily with 1 is just daily (1 = every week, which daily already is).
        assertTrue(run.recur("kind" to JsonPrimitive("daily"), "intervalWeeks" to JsonPrimitive(1)).startsWith("ok: \"Office Focus\" now repeats daily"))
    }

    /** A series with no timed slot names no "next" dates, only that it has no
     *  slot (web + iOS): an untimed block on a rule date is not an occurrence. */
    @Test fun `no slot names no next dates`() = runTest {
        val run = setup(cases["X3"]!!)
        run.state.tasks[0] = run.state.tasks[0].copy(recurrence = null)
        run.state.blocks.clear()
        run.state.blocks += CalBlock("untimed", taskId, "Office Focus", "", 60, "2026-10-08", kind = CalBlockKind.TASK)
        val ok = run.recur("kind" to JsonPrimitive("weekly"), "daysOfWeek" to JsonArray(listOf(JsonPrimitive(4))), "intervalWeeks" to JsonPrimitive(2))
        assertEquals("ok: \"Office Focus\" now repeats every 2 weeks on Thu — it has no calendar slot yet; schedule_task it to place the first one", ok)
    }

    /** intervalWeeks is offered on set_task_recurrence, and this build reports
     *  the capability that makes the server offer it on the text path. */
    @Test fun `the registry offers intervalWeeks and the build reports recurrence_interval`() {
        assertTrue("recurrence_interval" in ToolRegistry.CAPS)
        assertNotNull(RegistryTools.forSurface("text").firstOrNull { it.name == "set_task_recurrence" })
        assertTrue(ToolRegistry.JSON.contains("\"intervalWeeks\":{\"type\":\"integer\""))
        assertFalse("no cap keyword reaches a schema", ToolRegistry.JSON.contains("\"cap\""))
    }
    /** Voice reads only a description's first sentence (90 characters) and a
     *  param's (60): both still name every N weeks (spec §7.1). */
    @Test fun `voice compaction keeps every N weeks in the tool and the param`() {
        val tool = json.parseToJsonElement(ToolRegistry.JSON).jsonArray.first { it.str("name") == "set_task_recurrence" }.jsonObject
        val c = VoiceToolCompaction.compactTool(tool)
        val d = c["description"]!!.jsonPrimitive.content
        assertTrue(d, d.startsWith("Repeat a task daily, weekly or every 2–8 weeks on given days"))
        assertTrue(d.length <= VoiceToolCompaction.TOOL_DESCRIPTION_CAP + 1)
        val p = c["parameters"]!!.jsonObject["properties"]!!.jsonObject["intervalWeeks"]!!.jsonObject
        assertEquals("Every N weeks, 1–8 (2 = every other week / fortnightly).", p["description"]!!.jsonPrimitive.content)
    }

    /** get_tasks names the rhythm, so "how often does it repeat?" has an answer. */
    @Test fun `get_tasks says every 2 weeks`() = runTest {
        val run = setup(cases["X3"]!!)
        val out = runAssistantTool("get_tasks", ToolArgs(JsonObject(mapOf("view" to JsonPrimitive("recurring")))), run.api, run.scratch)
        assertTrue(out, out.contains("Office Focus") && out.contains(" · repeats every 2 weeks"))
    }
}
