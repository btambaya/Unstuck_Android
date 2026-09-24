package tech.csalliance.unstuck.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.derivePatterns
import tech.csalliance.unstuck.core.logic.everyNWeeksDays
import tech.csalliance.unstuck.core.logic.isOffWeekOnly
import tech.csalliance.unstuck.core.logic.liveRuleDates
import tech.csalliance.unstuck.core.logic.materializeOccurrences
import tech.csalliance.unstuck.core.logic.mondayIso
import tech.csalliance.unstuck.core.logic.nWeeksAnchor
import tech.csalliance.unstuck.core.logic.nWeeksBase
import tech.csalliance.unstuck.core.logic.nextRuleDate
import tech.csalliance.unstuck.core.logic.occurrenceId
import tech.csalliance.unstuck.core.logic.occurrenceReach
import tech.csalliance.unstuck.core.logic.reanchorForSchedule
import tech.csalliance.unstuck.core.logic.recurrenceEditStart
import tech.csalliance.unstuck.core.logic.recurrenceLabel
import tech.csalliance.unstuck.core.logic.recurrenceTopUp
import tech.csalliance.unstuck.core.logic.regenerateForTask
import tech.csalliance.unstuck.core.logic.rejectOffSeriesDay
import tech.csalliance.unstuck.core.logic.sameSeriesWeeks
import tech.csalliance.unstuck.core.logic.seriesAnchor
import tech.csalliance.unstuck.core.logic.shortDayLabel
import tech.csalliance.unstuck.core.logic.startsChips
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.RecurrenceSerializer
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Time
import java.util.TimeZone

/**
 * Every N weeks (every-n-weeks spec, Ahmad 2026-09-24): THE shared vectors
 * (lib/recurrence-vectors.json → RecurrenceVectors.generated.kt), byte for byte
 * as web and iOS run them — materialize V1–V18, reach, top-up T1–T4 with their
 * deterministic ids, regenerate E1–E3 (and the draft's plan as the regression
 * each prevents), the anchor helpers, the "Starts" chips, labels and the codec.
 *
 * The materialize list also runs under every zone in `timeZones` (New York and
 * Auckland): Gradle pins the JVM to UTC, where a floor of millisecond
 * differences would pass unseen (it fails V10 in New York). The zone is set for
 * one block and restored, so the JVM default stays UTC for every other test.
 */
class EveryNWeeksTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val root: JsonObject = json.parseToJsonElement(RecurrenceVectors.JSON).jsonObject
    private val taskId = root["taskId"]!!.jsonPrimitive.content

    private fun arr(k: String): JsonArray = root[k]!!.jsonArray
    private fun JsonElement.str(k: String): String? = (jsonObject[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement.strs(k: String): List<String> = jsonObject[k]!!.jsonArray.map { it.jsonPrimitive.content }
    private fun JsonElement.ints(k: String): List<Int> = jsonObject[k]!!.jsonArray.map { it.jsonPrimitive.int }
    private fun rule(e: JsonElement?): Recurrence? =
        if (e == null || e is JsonNull) null else json.decodeFromJsonElement(Recurrence.serializer(), e)
    private fun ymdMs(iso: String): Long = iso.split("-").map { it.toInt() }.let { Time.civil(it[0], it[1], it[2]) }

    private fun <T> inZone(id: String, block: () -> T): T {
        val saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(id))
        try { return block() } finally { TimeZone.setDefault(saved) }
    }

    private fun task(r: Recurrence?): TaskItem = TaskItem(
        id = taskId, name = "Office Focus", estimateMin = 60, recurrence = r,
        createdAt = "2026-09-24T07:02:02Z", updatedAt = "2026-09-24T07:02:02Z",
    )

    /** A vector block: `occurrenceOf` names the day whose deterministic id it
     *  carries; `plain` blocks get an id of their own. */
    private fun blocks(e: JsonElement): List<CalBlock> = e.jsonObject["blocks"]!!.jsonArray.mapIndexed { i, b ->
        val o = b.jsonObject
        val id = b.str("occurrenceOf")?.let { occurrenceId(taskId, it) } ?: "plain-$i"
        CalBlock(
            id = id, taskId = taskId, taskName = "Office Focus", startTime = b.str("startTime")!!, durationMinutes = 60,
            date = b.str("date")!!, kind = CalBlockKind.TASK,
            done = (o["done"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    // ── 9.1 materialize ─────────────────────────────────────────────────────

    private fun runMaterialize(zone: String) {
        for (v in arr("materialize")) {
            val id = v.str("id")
            val r = rule(v.jsonObject["recurrence"])!!
            val got = materializeOccurrences(r, ymdMs(v.str("startDate")!!), "10:30", v.jsonObject["horizonDays"]!!.jsonPrimitive.int).map { it.date }
            assertEquals("$id in $zone", v.strs("expect"), got)
            v.jsonObject["wrong"]?.let { w -> assertNotEquals("$id in $zone is not the wrong answer", w.jsonArray.map { it.jsonPrimitive.content }, got) }
        }
    }

    @Test fun materializeVectors_defaultZone() {
        assertEquals("the JVM default stays UTC", "UTC", TimeZone.getDefault().id)
        assertEquals(20, arr("materialize").size)
        runMaterialize("UTC")
    }

    @Test fun materializeVectors_inEveryDstZone() {
        val zones = arr("timeZones").map { it.jsonPrimitive.content }
        assertEquals(listOf("America/New_York", "Pacific/Auckland"), zones)
        for (z in zones) inZone(z) { runMaterialize(z) }
        assertEquals("restored", "UTC", TimeZone.getDefault().id)
    }

    /** A floor of millisecond differences between local midnights gives the
     *  wrong list for V10 in New York — the trap the civil-date rule avoids. */
    @Test fun floorOfMillisecondsIsWrongInNewYork_theCivilRuleIsNot() {
        val v10 = arr("materialize").first { it.str("id") == "V10" }
        inZone("America/New_York") {
            val anchor = ymdMs("2027-03-08")
            // Whole days between the two local-midnight MONDAYS, floored: 6.958 → 6.
            fun mondayMs(ms: Long) = Time.addDaysMillis(ms, -((Time.dayOfWeekJs(ms) + 6) % 7))
            val floored = (0 until 28).map { Time.addDaysMillis(anchor, it) }
                .filter { Time.dayOfWeekJs(it) == 0 && Math.floorMod(Math.floorDiv(mondayMs(it) - mondayMs(anchor), 86_400_000L) / 7, 2L) == 0L }
                .map { tech.csalliance.unstuck.core.time.Clock.dateIso(it) }
            assertEquals(v10.strs("wrong"), floored)
            val r = rule(v10.jsonObject["recurrence"])!!
            assertEquals(v10.strs("expect"), materializeOccurrences(r, anchor, "10:30", 28).map { it.date })
        }
    }

    // ── 9.2 reach ───────────────────────────────────────────────────────────

    @Test fun reachVectors() {
        for (v in arr("reach")) {
            val r = rule(v.jsonObject["recurrence"])!!
            assertEquals(v.toString(), v.jsonObject["expect"]!!.jsonPrimitive.int, occurrenceReach(r))
        }
    }

    @Test fun reachWithOneWeek_equalsWeekly_forAll127DaySets() {
        for (mask in 1 until 128) {
            val days = (0..6).filter { mask and (1 shl it) != 0 }
            assertEquals("days $days", occurrenceReach(Recurrence.Weekly(days)),
                occurrenceReach(Recurrence.EveryNWeeks(1, days, "2026-09-21")))
        }
    }

    // ── 9.3 top-up ──────────────────────────────────────────────────────────

    @Test fun topUpVectors_T1toT4_datesAndIds() {
        assertEquals(4, arr("topUp").size)
        for (v in arr("topUp")) {
            val r = rule(v.jsonObject["recurrence"])!!
            val got = recurrenceTopUp(task(r), blocks(v), v.str("today")!!, horizonDays = v.jsonObject["horizonDays"]!!.jsonPrimitive.int)
            val want = v.jsonObject["expect"]!!.jsonArray.map { it.str("date")!! to it.str("id")!! }
            assertEquals(v.str("id"), want, got.map { it.date to it.id })
            assertTrue(got.all { it.startTime == v.str("startTime") })
        }
    }

    /** id(09-24) is stage 2's vector #1: cross-checks the UUIDv5. */
    @Test fun topUpIdsCrossCheckStage2() {
        assertEquals("f8f5c8e7-0bb2-58d7-bc30-2701a2c9e1be", occurrenceId(taskId, "2026-09-24"))
    }

    // ── 9.3b repeat edits ───────────────────────────────────────────────────

    private data class Plan(val toUpsert: List<String>, val toRetime: List<Triple<String, String, String>>, val toDelete: List<String>)

    private fun plan(v: JsonElement, after: Recurrence, startDate: String): Plan {
        val bs = blocks(v)
        val p = regenerateForTask(task(after), after, bs, v.str("today")!!, v.str("startTime")!!, ymdMs(startDate), v.jsonObject["horizonDays"]!!.jsonPrimitive.int)
        val dateOf = bs.associate { it.id to it.date }
        val occOf = v.jsonObject["blocks"]!!.jsonArray.mapNotNull { b -> b.str("occurrenceOf")?.let { occurrenceId(taskId, it) to it } }.toMap()
        return Plan(
            p.toUpsert.map { it.date }.sorted(),
            p.toRetime.map { Triple(occOf[it.id] ?: it.id, dateOf[it.id]!!, it.date) },
            p.toDelete.map { dateOf[it]!! }.sorted(),
        )
    }

    private fun expected(e: JsonObject): Plan = Plan(
        e["toUpsert"]!!.jsonArray.map { it.jsonPrimitive.content }.sorted(),
        e["toRetime"]!!.jsonArray.map { Triple(it.str("occurrenceOf")!!, it.str("from")!!, it.str("to")!!) },
        e["toDelete"]!!.jsonArray.map { it.jsonPrimitive.content }.sorted(),
    )

    @Test fun regenerateVectors_E1toE3_withTheSpecStart_andTheDraftRegression() {
        assertEquals(3, arr("regenerate").size)
        for (v in arr("regenerate")) {
            val id = v.str("id")
            val before = rule(v.jsonObject["before"])!! as Recurrence.EveryNWeeks
            val after = rule(v.jsonObject["after"])!! as Recurrence.EveryNWeeks
            val today = v.str("today")!!
            // The start the app computes is the vector's: today, 56 days, the series' time.
            val start = recurrenceEditStart(taskId, after, blocks(v), today)!!
            assertEquals(id, v.str("startDate"), start.date)
            assertEquals(id, v.str("startTime"), start.startTime)
            assertEquals(id, 56, start.horizonDays)
            // …and the anchor it writes.
            assertEquals(id, after.anchor, nWeeksAnchor(before, everyNWeeksDays(after), after.interval, today, today))
            assertEquals(id, expected(v.jsonObject["expect"]!!.jsonObject), plan(v, after, start.date))
            v.jsonObject["draft"]?.jsonObject?.let { d ->
                val draftAfter = rule(d["after"])!!
                val draftPlan = plan(v, draftAfter, d["startDate"]!!.jsonPrimitive.content)
                assertEquals("$id: the draft's plan (the regression)", expected(d["plan"]!!.jsonObject), draftPlan)
                assertNotEquals("$id: the spec's plan is not the draft's", draftPlan, plan(v, after, start.date))
            }
        }
    }

    // ── 9.4 anchors, Starts chips, labels ───────────────────────────────────

    @Test fun seriesAnchorVectors() {
        for (v in arr("seriesAnchor")) assertEquals(v.toString(), v.str("expect"), seriesAnchor(v.ints("daysOfWeek"), v.str("from")!!))
    }

    @Test fun nextRuleDateVectors() {
        for (v in arr("nextRuleDate")) assertEquals(v.toString(), v.str("expect"), nextRuleDate(rule(v.jsonObject["recurrence"]), v.str("from")!!))
    }

    @Test fun editAnchorVectors() {
        for (v in arr("editAnchor")) {
            val today = v.str("today")!!
            val got = nWeeksAnchor(rule(v.jsonObject["current"]), v.ints("newDaysOfWeek"), v.jsonObject["newInterval"]!!.jsonPrimitive.int, today, v.str("startDate") ?: today)
            assertEquals(v.str("about"), v.str("expect"), got)
        }
    }

    /** The Starts row of an edit (startsBase → chips → the selected one), web's
     *  harness line for line: startDate is the task's recurrenceAnchor date (a
     *  past one never counts); the selected chip is the stored weeks' when N is
     *  kept, else the default week one's. */
    @Test fun startsBaseVectors() {
        assertEquals(7, arr("startsBase").size)
        for (v in arr("startsBase")) {
            val about = v.str("about")
            val cur = rule(v.jsonObject["current"])
            val days = v.ints("newDaysOfWeek")
            val n = v.jsonObject["newInterval"]!!.jsonPrimitive.int
            val today = v.str("today")!!
            val start = v.str("startDate") ?: today
            val base = nWeeksBase(cur, n, today, start, days)
            assertEquals(about, v.str("expect"), base)
            val chips = startsChips(days, n, base)
            assertEquals(about, v.strs("expectChips"), chips.map { it.date })
            val keeps = cur is Recurrence.EveryNWeeks && cur.interval == n
            val def = nWeeksAnchor(cur, days, n, today, start)
            val selected = if (keeps) chips.firstOrNull { sameSeriesWeeks(it.anchor, (cur as Recurrence.EveryNWeeks).anchor, n) }
                else chips.firstOrNull { it.anchor == def }
            assertEquals(about, v.str("expectSelected"), selected?.date)
        }
    }

    @Test fun scheduleAnchorVectors_reanchorOnlyWhenTheWeeksMove() {
        for (v in arr("scheduleAnchor")) {
            val r = rule(v.jsonObject["recurrence"])!! as Recurrence.EveryNWeeks
            val chosen = v.str("chosenDate")!!
            val changes = v.jsonObject["changesWeeks"]!!.jsonPrimitive.booleanOrNull!!
            assertEquals(v.str("about"), v.str("expect"), seriesAnchor(r.daysOfWeek, chosen))
            val re = reanchorForSchedule(r, chosen)
            if (changes) assertEquals(v.str("about"), r.copy(anchor = v.str("expect")!!), re)
            else {
                assertNull(v.str("about"), re)
                assertTrue(sameSeriesWeeks(v.str("expect")!!, r.anchor, r.interval))
            }
        }
        assertNull("another kind is never re-anchored", reanchorForSchedule(Recurrence.Weekly(listOf(4)), "2026-10-15"))
    }

    @Test fun startsChipVectors() {
        for (v in arr("startsChips")) {
            val chips = startsChips(v.ints("daysOfWeek"), v.jsonObject["interval"]!!.jsonPrimitive.int, v.str("base")!!)
            assertEquals(v.toString(), v.strs("expect"), chips.map { it.date })
            assertEquals(v.toString(), v.strs("expectAnchors"), chips.map { it.anchor })
        }
    }

    /** §6: on an edit that keeps N, the base is the rule's next date, so the
     *  stored weeks are one of the chips (the pre-selected one). */
    @Test fun editBase_keepsTheStoredWeeksAmongTheChips() {
        val v1 = Recurrence.EveryNWeeks(2, listOf(4), "2026-09-21")
        assertEquals("2026-10-08", nWeeksBase(v1, 2, "2026-09-25", "2026-09-25"))
        val chips = startsChips(listOf(4), 2, nWeeksBase(v1, 2, "2026-09-25", "2026-09-25"))
        assertEquals(1, chips.count { sameSeriesWeeks(it.anchor, v1.anchor, 2) })
        assertTrue(sameSeriesWeeks(chips.first().anchor, v1.anchor, 2))
        // Weekly → every 2 weeks on a Wednesday: the next Thursday's week.
        assertEquals("2026-09-30", nWeeksBase(Recurrence.Weekly(listOf(4)), 2, "2026-09-30", "2026-09-30"))
        // No repeat: the edit's own start.
        assertEquals("2026-10-02", nWeeksBase(null, 2, "2026-09-30", "2026-10-02"))
    }

    @Test fun labelVectors() {
        for (v in arr("labels")) assertEquals(v.toString(), v.str("expect"), recurrenceLabel(rule(v.jsonObject["recurrence"])))
    }

    // ── 9.4 codec ───────────────────────────────────────────────────────────

    private val codec get() = root["codec"]!!.jsonObject

    @Test fun codecCanonical_roundTripsInCanonicalKeyOrder() {
        for (c in codec["canonical"]!!.jsonArray) {
            val r = Json.decodeFromString(Recurrence.serializer(), c.str("json")!!)
            assertTrue(c.str("about"), r is Recurrence.EveryNWeeks)
            assertEquals(c.str("about"), c.str("expect"), Json.encodeToString(Recurrence.serializer(), r))
        }
    }

    @Test fun codecUnreadable_decodesToTheSentinel_neverThrows() {
        val sentinel = codec["sentinel"]!!.jsonPrimitive.content
        assertEquals(22, codec["unreadable"]!!.jsonArray.size)
        for (c in codec["unreadable"]!!.jsonArray) {
            val r = Json.decodeFromString(Recurrence.serializer(), c.str("json")!!)
            assertTrue(c.str("about"), RecurrenceSerializer.isUnknown(r))
            assertEquals(c.str("about"), sentinel, Json.encodeToString(Recurrence.serializer(), r))
            assertEquals("", recurrenceLabel(r))
        }
    }

    /** The whole task row survives an unreadable rule (a throw would drop it). */
    @Test fun codecUnreadable_insideATaskRow_keepsTheTask() {
        val row = """{"id":"t1","name":"Ship","estimateMin":25,"recurrence":{"kind":"everyNWeeks","interval":"2","daysOfWeek":[4],"anchor":"2026-09-21"},"createdAt":"2026-09-24T00:00:00Z","updatedAt":"2026-09-24T00:00:00Z"}"""
        val t = Json.decodeFromString(TaskItem.serializer(), row)
        assertTrue(RecurrenceSerializer.isUnknown(t.recurrence))
    }

    /** Readers are total (spec §0 rule 4), `until` included: a number, an array,
     *  an object or a boolean there makes the every-N-weeks rule unreadable —
     *  inert, as iOS decodes it — never a throw that drops the task (an array or
     *  object did: `jsonPrimitive`), and never a number read as an end date no
     *  YYYY-MM-DD passes. A null until is no end. */
    @Test fun codecMalformedUntil_isTheSentinel_neverAThrow() {
        for (u in listOf("20261231", "[\"2026-12-31\"]", "{\"d\":\"2026-12-31\"}", "true")) {
            val s = """{"kind":"everyNWeeks","interval":2,"daysOfWeek":[4],"anchor":"2026-09-21","until":$u}"""
            val r = Json.decodeFromString(Recurrence.serializer(), s)
            assertTrue(u, RecurrenceSerializer.isUnknown(r))
            assertEquals(u, emptyList<Any>(), materializeOccurrences(r, ymdMs("2026-09-24"), "10:30", 56))
            val row = """{"id":"t1","name":"Ship","estimateMin":25,"recurrence":$s,"createdAt":"2026-09-24T00:00:00Z","updatedAt":"2026-09-24T00:00:00Z"}"""
            assertEquals(u, "Ship", Json.decodeFromString(TaskItem.serializer(), row).name)
        }
        assertEquals(Recurrence.EveryNWeeks(2, listOf(4), "2026-09-21"),
            Json.decodeFromString(Recurrence.serializer(), """{"kind":"everyNWeeks","interval":2,"daysOfWeek":[4],"anchor":"2026-09-21","until":null}"""))
    }

    /** Writers store week one as a Monday (spec §0 rule 3). An edit that keeps N
     *  keeps the stored WEEKS — and writes a non-Monday anchor another writer
     *  left (V7's 2026-09-24) as its Monday; the dates are the same. */
    @Test fun anEditKeepingN_writesTheStoredWeeksAsTheirMonday() {
        val v7 = Recurrence.EveryNWeeks(2, listOf(4), "2026-09-24")
        assertEquals("2026-09-21", nWeeksAnchor(v7, listOf(5), 2, "2026-09-24", "2026-09-24"))
        val fri = Recurrence.EveryNWeeks(2, listOf(5), nWeeksAnchor(v7, listOf(5), 2, "2026-09-24", "2026-09-24"))
        assertEquals(materializeOccurrences(v7.copy(daysOfWeek = listOf(5)), ymdMs("2026-09-24"), "10:30", 56),
            materializeOccurrences(fri, ymdMs("2026-09-24"), "10:30", 56))
    }

    /** The installed builds' decoder + encoder — vc107, and vc108 (Models.kt at
     *  7042ea9, the same code): the input to migration 081's test. It turns V1
     *  into the sentinel, a fixed point. */
    private fun vc107Decode(s: String): JsonObject {
        val o = Json.parseToJsonElement(s).jsonObject
        val until = o["until"]?.jsonPrimitive?.contentOrNull
        return when (o["kind"]?.jsonPrimitive?.content) {
            "daily" -> buildJsonObject { put("kind", "daily"); until?.let { put("until", it) } }
            "monthly" -> buildJsonObject { put("kind", "monthly"); until?.let { put("until", it) } }
            "weekly" -> buildJsonObject {
                put("kind", "weekly"); put("daysOfWeek", o["daysOfWeek"] ?: JsonArray(emptyList())); until?.let { put("until", it) }
            }
            else -> buildJsonObject { put("kind", "daily"); put("until", RecurrenceSerializer.UNKNOWN_UNTIL) }
        }
    }

    @Test fun oldCodecSimulation_V1BecomesTheSentinel_aFixedPoint() {
        val old = codec["oldCodec"]!!.jsonObject
        val once = vc107Decode(old["input"]!!.jsonPrimitive.content).toString()
        assertEquals(old["expect"]!!.jsonPrimitive.content, once)
        assertEquals(once, vc107Decode(once).toString())
        assertEquals(codec["sentinel"]!!.jsonPrimitive.content, once)
        // …and this build reads the sentinel as the sentinel, not as a rule.
        assertTrue(RecurrenceSerializer.isUnknown(Json.decodeFromString(Recurrence.serializer(), once)))
    }

    // ── helpers the executor and the guards use ─────────────────────────────

    @Test fun liveRuleDates_countTodaysOpenOccurrence_andOnlyOnRuleDates() {
        val v1 = Recurrence.EveryNWeeks(2, listOf(4), "2026-09-21")
        fun b(id: String, date: String, done: Boolean = false) = CalBlock(id, taskId, "Office Focus", "10:30", 60, date, done = done)
        val blocks = listOf(b("a", "2026-09-24"), b("m", "2026-10-15"), b("c", "2026-10-08"), b("d", "2026-10-22"))
        assertEquals(listOf("2026-09-24", "2026-10-08"), liveRuleDates(v1, taskId, blocks, "2026-09-24"))
        val doneToday = listOf(b("a", "2026-09-24", done = true)) + blocks.drop(1)
        assertEquals("a done day is not next; a block moved into an off week is not a rule date",
            listOf("2026-10-08", "2026-10-22"), liveRuleDates(v1, taskId, doneToday, "2026-09-24"))
    }

    @Test fun offWeekGuard_X9_andTheFirstPlacementSkip() {
        val v1 = Recurrence.EveryNWeeks(2, listOf(4), "2026-09-21")
        val refusal = rejectOffSeriesDay("Office Focus", v1, "2026-10-15", "2026-09-30")!!
        assertTrue(refusal, refusal.startsWith(
            "error: \"Office Focus\" repeats every 2 weeks on Thursday, and Thu 15 Oct is an off week — nothing was scheduled. " +
                "The nearest Thursdays it repeats on are Thu 8 Oct (2026-10-08) and Thu 22 Oct (2026-10-22)",
        ))
        assertTrue(isOffWeekOnly(v1, "2026-10-15"))
        assertNull("on-week Thursday", rejectOffSeriesDay("Office Focus", v1, "2026-10-22", "2026-09-30"))
        assertNull("a first placement re-anchors: every week is valid", rejectOffSeriesDay("Office Focus", v1, "2026-10-15", "2026-09-30", placesSeries = true))
        // The weekday part still applies to a first placement, and the dates it
        // names are the rule's own on-week ones (web's nearestRuleDates, 17181ed).
        val fri = rejectOffSeriesDay("Office Focus", v1, "2026-10-16", "2026-09-30", placesSeries = true)!!
        assertTrue(fri, fri.contains("but 2026-10-16 is a Friday"))
        assertTrue(fri, fri.contains("Thu 8 Oct (2026-10-08) and Thu 22 Oct (2026-10-22)"))
        assertFalse(isOffWeekOnly(v1, "2026-10-16"))
        // Every 4 weeks: the nearest dates are up to 28 days away, still named.
        val n4 = Recurrence.EveryNWeeks(4, listOf(4), "2026-09-21")
        val far = rejectOffSeriesDay("Office Focus", n4, "2026-10-08", "2026-09-24")!!
        assertTrue(far, far.contains("Thu 24 Sep (2026-09-24) and Thu 22 Oct (2026-10-22)"))
        assertEquals("Thu 8 Oct", shortDayLabel("2026-10-08"))
    }

    /** Web review fix 3 (17181ed): the WEEK is judged without `until` — an
     *  on-week Thursday past the series' end is not "an off week" (weekly never
     *  checks until there either) — while the nearest dates still respect it. */
    @Test fun offWeekGuard_judgesTheWeekWithoutUntil_andNamesDatesWithinIt() {
        val ended = Recurrence.EveryNWeeks(2, listOf(4), "2026-09-21", until = "2026-10-08")
        assertNull("an on-week Thursday past until is not an off week", rejectOffSeriesDay("Office Focus", ended, "2026-10-22", "2026-09-30"))
        assertFalse(isOffWeekOnly(ended, "2026-10-22"))
        // An off-week Thursday past until is still one; only 8 Oct (within until) is named.
        val off = rejectOffSeriesDay("Office Focus", ended, "2026-10-15", "2026-09-30")!!
        assertTrue(off, off.contains("and Thu 15 Oct is an off week"))
        assertTrue(off, off.contains("The nearest Thursday it repeats on is Thu 8 Oct (2026-10-08). "))
        assertFalse(off, off.contains("2026-10-22"))
    }

    // ── §8.2 patterns ───────────────────────────────────────────────────────

    /** A fortnightly Sunday (4 Oct, 18 Oct, 1 Nov) has 3 distinct weeks of history
     *  on Sun 8 Nov; as weekly that is a pattern whose gap today would raise "still
     *  on for Sunday?" in an OFF week. Every-N-weeks templates are skipped. */
    @Test fun derivePatterns_skipsEveryNWeeksTemplates() {
        fun b(date: String) = CalBlock("b$date", taskId, "Run", "08:00", 30, date)
        val blocks = listOf(b("2026-10-04"), b("2026-10-18"), b("2026-11-01"))
        val weekly = task(Recurrence.Weekly(listOf(0))).copy(name = "Run")
        assertEquals(1, derivePatterns(listOf(weekly), blocks, "2026-11-08").size)
        val fortnightly = task(Recurrence.EveryNWeeks(2, listOf(0), "2026-09-28")).copy(name = "Run")
        assertEquals(emptyList<Any>(), derivePatterns(listOf(fortnightly), blocks, "2026-11-08"))
    }

    /** Web review fix 2 (17181ed): a same-N edit that changes only the days
     *  keeps the stored anchor, so the chips count from the rule's next date ON
     *  THE NEW DAYS. Thu → Mon on Wed 30 Sep: the series' first Monday is 5 Oct
     *  (the stored weeks), and that chip is the pre-selected first one — counted
     *  on the old Thursday it read "Mon 19 Oct" (a whole cycle late). */
    @Test fun editBase_sameN_countsOnTheEditedDays() {
        val v1 = Recurrence.EveryNWeeks(2, listOf(4), "2026-09-21")
        assertEquals("2026-10-05", nWeeksBase(v1, 2, "2026-09-30", "2026-09-30", newDays = listOf(1)))
        val kept = Recurrence.EveryNWeeks(2, listOf(1), nWeeksAnchor(v1, listOf(1), 2, "2026-09-30", "2026-09-30"))
        assertEquals("2026-09-21", kept.anchor)
        val chips = startsChips(listOf(1), 2, nWeeksBase(v1, 2, "2026-09-30", "2026-09-30", newDays = listOf(1)))
        assertEquals(listOf("2026-10-05", "2026-10-12"), chips.map { it.date })
        assertTrue("the stored weeks are the first chip", sameSeriesWeeks(chips.first().anchor, kept.anchor, 2))
        assertEquals("its date is the series' real first Monday", "2026-10-05", nextRuleDate(kept, "2026-09-30"))
        // The old base (days unchanged / not given) is the spec's nextRuleDate(stored, today).
        assertEquals("2026-10-08", nWeeksBase(v1, 2, "2026-09-30", "2026-09-30"))
        assertEquals("2026-10-08", nWeeksBase(v1, 2, "2026-09-30", "2026-09-30", newDays = listOf(4)))
    }

    /** Week one from no repeat / daily / monthly (web's rule, canonical where the
     *  spec is silent): the series' block day only when it is AHEAD, else today —
     *  a task whose only block is past never gets a past week one. */
    @Test fun editAnchor_fromNoRepeat_aPastBlockCountsFromToday() {
        // Only block: Mon 14 Sep (past). Today Thu 24 Sep. Thursdays every 2 weeks.
        assertEquals("2026-09-24", nWeeksBase(null, 2, "2026-09-24", "2026-09-14"))
        assertEquals("2026-09-21", nWeeksAnchor(null, listOf(4), 2, "2026-09-24", "2026-09-14"))
        assertEquals("2026-09-21", nWeeksAnchor(Recurrence.Daily(), listOf(4), 2, "2026-09-24", "2026-09-10"))
        assertEquals("2026-09-21", nWeeksAnchor(Recurrence.Monthly(), listOf(4), 2, "2026-09-24", "2026-09-17"))
        // A block ahead still counts: Fri 2 Oct → the next Thursday's week (8 Oct).
        assertEquals("2026-10-05", nWeeksAnchor(null, listOf(4), 2, "2026-09-24", "2026-10-02"))
    }

    /** Readers accept any whole N ≥ 1; a hand-edited or corrupt huge interval
     *  must neither freeze the editor (one chip per week) nor give a negative
     *  reach (7·N wrapping an Int). Writers keep 2…8. */
    @Test fun hugeStoredIntervals_stayBounded() {
        for (n in listOf(9, 520, 1_000_000, Int.MAX_VALUE)) {
            val chips = startsChips(listOf(4), n, "2026-09-24")
            assertEquals("$n", 8, chips.size)
            val r = Recurrence.EveryNWeeks(n, listOf(4), "2026-09-21")
            // (7·min(N, 10 000) − 1) / 2: the cycle is clamped as on web and iOS.
            assertEquals("$n", (7 * minOf(n, 10_000) - 1) / 2, occurrenceReach(r))
            assertEquals("2026-09-24", nextRuleDate(r, "2026-09-24"))
            assertNull(rejectOffSeriesDay("Office Focus", r, "2026-09-24", "2026-09-24"))
            assertTrue(rejectOffSeriesDay("Office Focus", r, "2026-10-01", "2026-09-24")!!.contains("off week"))
        }
        assertEquals(listOf("2026-09-24", "2026-10-01"), startsChips(listOf(4), 2, "2026-09-24").map { it.date })
        assertEquals(27, occurrenceReach(Recurrence.EveryNWeeks(8, listOf(4), "2026-09-21")))
    }

    @Test fun mondayAndWeeksHelpers() {
        assertEquals("2026-09-21", mondayIso("2026-09-27"))
        assertEquals("2026-12-28", mondayIso("2027-01-03"))
        assertTrue(sameSeriesWeeks("2026-09-21", "2026-10-05", 2))
        assertFalse(sameSeriesWeeks("2026-09-21", "2026-09-28", 2))
        assertTrue("weeks before the anchor count backwards", sameSeriesWeeks("2026-09-07", "2026-09-21", 2))
    }
}
