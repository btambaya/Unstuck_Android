package tech.csalliance.unstuck.soak

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.InsightsSpan
import tech.csalliance.unstuck.core.logic.PeriodData
import tech.csalliance.unstuck.core.logic.insightsFacts
import tech.csalliance.unstuck.core.logic.insightsRange
import tech.csalliance.unstuck.core.logic.localToday
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.ui.today.WeekPill
import tech.csalliance.unstuck.ui.today.weekPill
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * The Today header's week pill (analytics D3, 2026-09-24): THIS week, Monday
 * 00:00 onwards, from the shared periodFacts engine — the same numbers Insights
 * shows for This week. It used to be a rolling 7 days (Ahmad's pill said 1h 35m
 * while the page it opened said "No focus sessions yet").
 *
 * It ALWAYS shows, because it is the way into Insights from home (Ahmad,
 * 2026-09-24, his Today with no pill: "Where is the insight button??"):
 * focus → "This week · 2h 5m focused →"; no focus but tasks done → "3 done
 * this week →"; nothing this week → "Your week →" — or, on a Monday or Tuesday
 * after a week with focus, "Last week · 1h 35m focused →".
 */
class WeekFocusMinutesTest {
    private val utc = ZoneId.of("UTC")
    private fun ms(s: String) = Instant.parse(s).toEpochMilli()
    private fun s(id: String, sec: Int, at: String, est: Int? = 25) = Session(id = id, taskId = null, taskName = "x", estimateMin = est, actualSec = sec, completedAt = at)
    private fun data(vararg ss: Session) = PeriodData(emptyList(), emptyList(), ss.toList(), emptyList(), emptyList())
    private fun done(id: String, at: String) = TaskItem(id = id, name = id, estimateMin = 25, done = true, completedAt = at, createdAt = "2026-09-01T08:00:00Z", updatedAt = at)
    private fun focused(m: Int) = WeekPill(WeekPill.Kind.FOCUSED, minutes = m)
    private fun lastWeek(m: Int) = WeekPill(WeekPill.Kind.LAST_WEEK, minutes = m)
    private val empty = WeekPill(WeekPill.Kind.EMPTY)

    // Thu 24 Sep 2026; the week began Mon 21 Sep.
    private val thu = ms("2026-09-24T15:30:00Z")

    @Test fun countsOnlyThisWeekSinceMonday() {
        val d = data(
            s("a", 50 * 60, "2026-09-22T10:00:00Z"),      // Tue this week
            s("b", 95 * 60, "2026-09-18T20:00:00Z"),      // last Friday — inside a rolling 7 days, not this week
        )
        assertEquals(focused(50), weekPill(d, thu, utc))
    }

    @Test fun accidentalStartsDontCountAndRunawaysAreClamped() {
        val d = data(
            s("a", 5, "2026-09-22T10:00:00Z"), s("b", 19, "2026-09-22T11:00:00Z"), s("c", 37, "2026-09-22T12:00:00Z"),
            s("d", 30 * 3600, "2026-09-23T12:00:00Z", est = 25),   // forgotten timer → 85 min
        )
        assertEquals(focused(85), weekPill(d, thu, utc))
    }

    @Test fun roundsLikeThePageAndTheReview() {
        assertEquals(2, weekPill(data(s("a", 90, "2026-09-22T10:00:00Z")), thu, utc).minutes)   // 1m 30s → 2m
        assertEquals(1, weekPill(data(s("a", 89, "2026-09-22T10:00:00Z")), thu, utc).minutes)
    }

    /** The three states, as read. */
    @Test fun theThreeStates_withTheirWords() {
        // 1. Focus this week (today's format).
        val f = weekPill(data(s("a", 125 * 60, "2026-09-22T10:00:00Z", est = 180)), thu, utc)
        assertEquals(focused(125), f)
        assertEquals("This week · 2h 5m focused →", f.text)
        assertEquals("This week · " to "2h 5m focused", f.lead to f.value)
        // 2. No focus, but tasks done this week.
        val three = PeriodData(listOf(done("a", "2026-09-21T09:00:00Z"), done("b", "2026-09-22T09:00:00Z"), done("c", "2026-09-24T09:00:00Z")), emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(WeekPill(WeekPill.Kind.DONE, done = 3), weekPill(three, thu, utc))
        assertEquals("3 done this week →", weekPill(three, thu, utc).text)
        val one = PeriodData(listOf(done("a", "2026-09-23T09:00:00Z")), emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals("1 done this week →", weekPill(one, thu, utc).text)
        // 3. Nothing this week — and last week's focus is not offered mid-week.
        val nothing = weekPill(data(s("b", 95 * 60, "2026-09-18T20:00:00Z")), thu, utc)
        assertEquals(empty, nothing)
        assertEquals("Your week →", nothing.text)
        assertEquals(empty, weekPill(data(), thu, utc))
        assertFalse("opens Insights on THIS week", nothing.lastWeek)
    }

    /** Focus wins over done; a task done LAST week or later today (after now's
     *  minute) is not this week's. */
    @Test fun focusFirst_thenThisWeeksDoneOnly() {
        val d = PeriodData(listOf(done("a", "2026-09-22T09:00:00Z")), emptyList(), listOf(s("x", 30 * 60, "2026-09-22T10:00:00Z")), emptyList(), emptyList())
        assertEquals(focused(30), weekPill(d, thu, utc))
        val notThisWeek = PeriodData(listOf(done("a", "2026-09-19T09:00:00Z"), done("b", "2026-09-24T18:00:00Z")), emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(empty, weekPill(notThisWeek, thu, utc))
    }

    /** The done number IS the Insights page's This week Done: plain tasks by
     *  completedAt, and a repeating task's ticked occurrences (not the
     *  template itself). */
    @Test fun theDoneCountMatchesTheInsightsPage() {
        val tpl = TaskItem(id = "gym", name = "Gym", estimateMin = 30, recurrence = Recurrence.Daily(), createdAt = "2026-09-01T08:00:00Z", updatedAt = "2026-09-01T08:00:00Z")
        fun occ(date: String, done: Boolean) = CalBlock("gym-$date", "gym", "Gym", "07:00", 30, date, kind = CalBlockKind.TASK, done = done, completedAt = if (done) "${date}T07:30:00Z" else null)
        val d = PeriodData(
            listOf(tpl, done("a", "2026-09-22T09:00:00Z"), done("old", "2026-09-10T09:00:00Z")),
            listOf(occ("2026-09-21", true), occ("2026-09-22", false), occ("2026-09-23", true), occ("2026-09-17", true)),
            emptyList(), emptyList(), emptyList(),
        )
        val pill = weekPill(d, thu, utc)
        val page = insightsFacts(d, insightsRange(InsightsSpan.WEEK, 0, localToday(thu, utc), null), thu, utc)
        assertEquals(3, page.doneCount)
        assertEquals(WeekPill(WeekPill.Kind.DONE, done = page.doneCount), pill)
        assertEquals("3 done this week →", pill.text)
    }

    @Test fun mondayWithNothingYetOffersLastWeek() {
        val mon = ms("2026-09-21T09:00:00Z")
        val d = data(s("b", 95 * 60, "2026-09-18T20:00:00Z", est = 90))
        assertEquals(lastWeek(95), weekPill(d, mon, utc))
        assertEquals("Last week · 1h 35m focused →", weekPill(d, mon, utc).text)
        assertTrue(weekPill(d, mon, utc).lastWeek)
        val tue = ms("2026-09-22T09:00:00Z")
        assertEquals(lastWeek(95), weekPill(d, tue, utc))
        val wed = ms("2026-09-23T09:00:00Z")
        assertEquals(empty, weekPill(d, wed, utc))
        // A Monday with nothing last week either: "Your week".
        assertEquals(empty, weekPill(data(), mon, utc))
        // Something done already this Monday: this week's, not last week's.
        val doneMon = PeriodData(listOf(done("a", "2026-09-21T08:00:00Z")), emptyList(), d.sessions, emptyList(), emptyList())
        assertEquals("1 done this week →", weekPill(doneMon, mon, utc).text)
    }

    /** THE shared pill vectors (lib/assistant/period-review-vectors.json →
     *  weekPill, P1–P10), the cases web and iOS run: every state, done beating
     *  last week, local days and the "so far" cut, odd timestamps. Read from
     *  core's generated copy (app tests can't see core's test classes). */
    @Test fun sharedWeekPillVectors() {
        val json = Json { ignoreUnknownKeys = true }
        val rel = "core/src/test/kotlin/tech/csalliance/unstuck/core/PeriodReviewVectors.generated.kt"
        val src = listOf(File("../$rel"), File(rel)).first { it.exists() }.readText()
        val open = "const val JSON: String = \"\"\""
        val body = src.substring(src.indexOf(open) + open.length, src.lastIndexOf("\"\"\"")).replace("\${\"$\"}", "$")
        val root = json.parseToJsonElement(body).jsonObject
        val datasets = root["datasets"]!!.jsonObject
        val vectors = root["weekPill"]!!.jsonObject["vectors"]!!.jsonArray
        assertEquals((1..10).map { "P$it" }, vectors.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        for (v in vectors) {
            val o = v.jsonObject
            val id = o["id"]!!.jsonPrimitive.content
            val zone = ZoneId.of(o["tz"]!!.jsonPrimitive.content)
            val now = ms(o["now"]!!.jsonPrimitive.content)
            val d = datasets[o["dataset"]!!.jsonPrimitive.content]!!.jsonObject
            val data = PeriodData(
                json.decodeFromJsonElement(ListSerializer(TaskItem.serializer()), d["tasks"]!!),
                json.decodeFromJsonElement(ListSerializer(CalBlock.serializer()), d["blocks"]!!),
                json.decodeFromJsonElement(ListSerializer(Session.serializer()), d["sessions"]!!),
                emptyList(), emptyList(),
            )
            val pill = weekPill(data, now, zone)
            val want = o["expect"]!!.jsonObject
            val kind = when (pill.kind) {
                WeekPill.Kind.FOCUSED, WeekPill.Kind.LAST_WEEK -> "focus"
                WeekPill.Kind.DONE -> "done"
                WeekPill.Kind.EMPTY -> "empty"
            }
            assertEquals(id, want["kind"]!!.jsonPrimitive.content, kind)
            assertEquals(id, want["text"]!!.jsonPrimitive.content, pill.words)
            // `at`: the week the tap opens — last week's Monday, else this week (null).
            val at = if (pill.lastWeek) insightsRange(InsightsSpan.WEEK, -1, localToday(now, zone), null).from else null
            assertEquals(id, want["at"]!!.jsonPrimitive.contentOrNull, at)
        }
    }

    @Test fun mondayAnchorFollowsTheZone() {
        // 23:30 Sunday in New York is already Monday in UTC.
        val ny = ZoneId.of("America/New_York")
        val d = data(s("a", 30 * 60, "2026-09-21T03:30:00Z"))   // Sun 20 Sep 23:30 NY
        assertEquals(focused(30), weekPill(d, ms("2026-09-22T15:00:00Z"), utc))
        assertEquals(lastWeek(30), weekPill(d, ms("2026-09-22T15:00:00Z"), ny))
    }
}
