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

    /** Records the order writes reach the store: "task <anchor>" / "block <date>". */
    private class Recording(val inner: AssistantApi) : AssistantApi by inner {
        val log = ArrayList<String>()
        override suspend fun upsertTask(t: TaskItem) {
            log += "task ${(t.recurrence as? Recurrence.EveryNWeeks)?.anchor ?: ""}"
            inner.upsertTask(t)
        }
        override suspend fun upsertBlock(b: CalBlock) { log += "block ${b.date}"; inner.upsertBlock(b) }
        override suspend fun insertBlockIfAbsent(b: CalBlock, retimeIfTaken: Boolean): Boolean {
            log += "block ${b.date}"
            return inner.insertBlockIfAbsent(b, retimeIfTaken)
        }
        override suspend fun deleteBlock(id: String) { log += "block -$id"; inner.deleteBlock(id) }
    }

    private class Run(val api: AssistantToolsTest.FakeApi, val scratch: TurnScratch) {
        val state get() = api.state
        /** Every tool call goes through here, so the write order is on record. */
        val rec = Recording(api)
    }

    private fun setup(c: JsonElement): Run {
        val api = AssistantToolsTest().FakeApi()
        val scratch = TurnScratch()
        api.state.today = c.str("today")
        val t = exec["task"]!!.jsonObject
        api.state.tasks += TaskItem(
            id = taskId, name = t["name"]!!.jsonPrimitive.content, estimateMin = 60,
            recurrence = rule(c.jsonObject["recurrence"]),
            // taskDone: the task row starts done (no completedAt).
            done = (c.jsonObject["taskDone"] as? JsonPrimitive)?.booleanOrNull == true,
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
        return runAssistantTool(call.str("tool")!!, ToolArgs(JsonObject(args)), rec, scratch)
    }

    private fun check(where: String, spec: JsonElement, got: String) {
        spec.str("expect")?.let { assertEquals(where, it, got) }
        spec.str("expectPrefix")?.let { assertTrue("$where: $got", got.startsWith(it)) }
        spec.strs("expectContains").forEach { assertTrue("$where: $got", got.contains(it)) }
    }

    private fun Run.stored(): Recurrence? = state.tasks.first { it.id == taskId }.recurrence
    private fun Run.live(): List<String> = state.blocks.filter { it.taskId == taskId && !it.done && !it.skipped }.map { it.date }

    /** What a case pins about the world after its call(s). */
    private fun checkWorld(id: String, c: JsonElement, run: Run) {
        // expectFirstWrite "task": the first write is the task row (spec §5 write
        // order, web review fix 1) — the re-anchored rule before any block.
        c.str("expectFirstWrite")?.let { assertTrue("$id first write: ${run.rec.log}", run.rec.log.firstOrNull()?.startsWith(it) == true) }
        if (c.jsonObject.containsKey("expectRecurrence")) assertEquals("$id stored rule", rule(c.jsonObject["expectRecurrence"]), run.stored())
        for (d in c.strs("expectKept")) assertTrue("$id keeps $d", run.state.blocks.any { it.date == d && it.id.startsWith("plain-") })
        c.jsonObject["expectMints"]?.jsonArray?.forEach { m ->
            assertTrue("$id mints $m", run.state.blocks.any { it.date == m.str("date") && it.id == m.str("id") })
        }
        for (d in c.strs("expectLiveIncludes")) assertTrue("$id: $d live in ${run.live()}", d in run.live())
        for (d in c.strs("expectLiveExcludes")) assertFalse("$id: $d not live in ${run.live()}", d in run.live())
    }

    /** Runs one vector case and asserts everything it pins — web's harness
     *  (every-n-weeks.test.ts): `after` runs on the world the named case left;
     *  `calls` each start from the case's own world (an `error:` writes
     *  nothing), unless `sequential`, where they run in order in ONE turn. */
    private suspend fun replay(id: String): Run {
        val c = cases[id]!!
        c.str("after")?.let { prev ->
            val run = replay(prev)
            check(id, c, run.call(c.jsonObject["call"]!!))
            checkWorld(id, c, run)
            return run
        }
        c.jsonObject["call"]?.let { call ->
            val run = setup(c)
            check(id, c, run.call(call))
            checkWorld(id, c, run)
            return run
        }
        val calls = c.jsonObject["calls"]!!.jsonArray
        if ((c.jsonObject["sequential"] as? JsonPrimitive)?.booleanOrNull == true) {
            val run = setup(c)
            calls.forEachIndexed { i, call -> check("$id.$i", call, run.call(call)) }
            checkWorld(id, c, run)
            return run
        }
        var last: Run? = null
        calls.forEachIndexed { i, call ->
            val run = setup(c)
            val tasksBefore = run.state.tasks.toList()
            val blocksBefore = run.state.blocks.toList()
            val line = run.call(call)
            check("$id.$i", call, line)
            if (line.startsWith("error:")) {
                assertEquals("$id.$i wrote nothing", tasksBefore, run.state.tasks.toList())
                assertEquals("$id.$i wrote nothing", blocksBefore, run.state.blocks.toList())
            }
            checkWorld("$id.$i", c, run)
            last = run
        }
        return last!!
    }

    @Test fun `the vectors cover X1 to X16`() {
        assertEquals((1..16).map { "X$it" }, cases.keys.toList())
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

    /** Web review fix 3: the week is judged without until. */
    @Test fun X10_theOffWeekGuardJudgesTheWeekWithoutUntil() = runTest { replay("X10") }

    /** The ok line in full: how, at, until, rhythm note, next dates, done note. */
    @Test fun X11_theOkLineOrder() = runTest { replay("X11") }

    @Test fun X12_aDoneTaskMadeFortnightly_nextDatesBeforeTheDoneNote() = runTest { replay("X12") }

    @Test fun X13_intervalWeeksCheckOrder_andHugeWholeNumbers() = runTest { replay("X13") }

    @Test fun X14_aNonMondayStoredAnchorIsWrittenBackAsItsMonday() = runTest { replay("X14") }

    /** Week one from no repeat with only a PAST block counts from today (web). */
    @Test fun X15_aPastBlockNeverGivesAPastWeekOne() = runTest { replay("X15") }

    @Test fun X16_noSlot_noNextDates() = runTest { replay("X16") }

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

    // ── web review parity (17181ed) ────────────────────────────────────────

    /** Web review fix 1: a first placement that re-anchors writes the task row
     *  BEFORE any block — the placed one, the fill's, the moved one — so no
     *  reader (the fill, another device's top-up on the placed block's echo)
     *  runs the old weeks from it. With an open block left behind today, that
     *  block is the one moved, and the move-count bump carries the new rule. */
    @Test fun `X8 writes the re-anchored task row before any block`() = runTest {
        for (withLive in listOf(false, true)) {
            val run = setup(cases["X8"]!!)
            if (withLive) run.state.blocks += CalBlock(occurrenceId(taskId, "2026-09-30"), taskId, "Office Focus", "10:30", 60, "2026-09-30", kind = CalBlockKind.TASK)
            val rec = run.rec
            val ok = run.call(cases["X8"]!!.jsonObject["call"]!!)
            assertTrue(ok, ok.startsWith("ok:"))
            assertEquals("withLive=$withLive: ${rec.log}", "task 2026-10-12", rec.log.first())
            assertTrue("withLive=$withLive: ${rec.log}", rec.log.filter { it.startsWith("task") }.all { it == "task 2026-10-12" })
            assertTrue(rec.log.any { it == "block 2026-10-15" })
            assertEquals(rule(cases["X8"]!!.jsonObject["expectRecurrence"]), run.stored())
            if (withLive) assertEquals(1, run.state.tasks.first { it.id == taskId }.moveCount)
        }
    }

    /** …and a first placement onto a day whose occurrence is already DONE places
     *  nothing, so nothing starts there: the weeks stay. */
    @Test fun `a first placement onto a done day leaves the weeks alone`() = runTest {
        val run = setup(cases["X8"]!!)
        run.state.blocks += CalBlock(occurrenceId(taskId, "2026-10-15"), taskId, "Office Focus", "10:30", 60, "2026-10-15", kind = CalBlockKind.TASK, done = true)
        val out = runAssistantTool("schedule_task", ToolArgs(JsonObject(mapOf(
            "taskId" to JsonPrimitive(taskId), "date" to JsonPrimitive("2026-10-15"), "startTime" to JsonPrimitive("10:30"),
        ))), run.api, run.scratch)
        assertTrue(out, out.startsWith("error: \"Office Focus\" is already done on 2026-10-15"))
        assertEquals(rule(cases["X8"]!!.jsonObject["recurrence"]), run.stored())
    }

    /** Week one from no repeat when the task's only block is PAST: counted from
     *  today (web's rule), never from the past block's week. Only block Mon
     *  14 Sep; today Thu 24 Sep → Thursdays every 2 weeks from w/c 21 Sep. */
    @Test fun `from no repeat with only a past block week one is this week`() = runTest {
        val run = setup(cases["X3"]!!)
        run.state.tasks[0] = run.state.tasks[0].copy(recurrence = null)
        run.state.blocks.clear()
        run.state.blocks += CalBlock("past", taskId, "Office Focus", "10:30", 60, "2026-09-14", kind = CalBlockKind.TASK, done = true)
        val ok = run.recur("kind" to JsonPrimitive("weekly"), "daysOfWeek" to JsonArray(listOf(JsonPrimitive(4))), "intervalWeeks" to JsonPrimitive(2))
        assertEquals(Recurrence.EveryNWeeks(2, listOf(4), "2026-09-21"), run.stored())
        assertTrue(ok, ok.startsWith("ok: \"Office Focus\" now repeats every 2 weeks on Thu at 10:30"))
        assertFalse("never the past block's week (w/c 14 Sep → 1 Oct on)", "2026-10-01" in run.live())
    }

    /** The context says how often (web: repeatsEveryWeeks), so a later turn can
     *  answer "how often?"; plain weekly carries only repeats. */
    @Test fun `the context says every N weeks`() = runTest {
        val run = setup(cases["X3"]!!)
        val ctx = buildAssistantContext(run.api)
        val t = ctx["tasks"]!!.jsonArray.first { it.str("id") == taskId }.jsonObject
        assertEquals("true", t["repeats"]!!.jsonPrimitive.content)
        assertEquals(2, t["repeatsEveryWeeks"]!!.jsonPrimitive.content.toInt())
        run.state.tasks[0] = run.state.tasks[0].copy(recurrence = Recurrence.Weekly(listOf(4)))
        val weekly = buildAssistantContext(run.api)["tasks"]!!.jsonArray.first { it.str("id") == taskId }.jsonObject
        assertFalse(weekly.containsKey("repeatsEveryWeeks"))
    }

    /** get_tasks names the rhythm, so "how often does it repeat?" has an answer. */
    @Test fun `get_tasks says every 2 weeks`() = runTest {
        val run = setup(cases["X3"]!!)
        val out = runAssistantTool("get_tasks", ToolArgs(JsonObject(mapOf("view" to JsonPrimitive("recurring")))), run.api, run.scratch)
        assertTrue(out, out.contains("Office Focus") && out.contains(" · repeats every 2 weeks"))
    }
}
