package tech.csalliance.unstuck.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.PeriodReviewArgs
import tech.csalliance.unstuck.core.logic.capPeriodReview
import tech.csalliance.unstuck.core.logic.periodCleanName
import tech.csalliance.unstuck.core.logic.periodStampMs
import tech.csalliance.unstuck.core.logic.renderPeriodReview
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.ReasonLog
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TaskItem
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

// The shared get_period_review vectors (week-review-spec.md §6), byte for byte.
// Each vector runs in its own zone passed as `zone`, while the JVM default
// stays UTC (Gradle's -Duser.timezone=UTC): a stray ZoneId.systemDefault()
// (Clock.*, Time.*, Time.parseMillis) makes the New York vectors fail.
class PeriodReviewTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val root: JsonObject = json.parseToJsonElement(PeriodReviewVectors.JSON).jsonObject

    private class Dataset(val tasks: List<TaskItem>, val blocks: List<CalBlock>, val sessions: List<Session>, val captures: List<Capture>, val reasons: List<ReasonLog>)

    private fun dataset(name: String): Dataset {
        val d = root["datasets"]!!.jsonObject[name]!!.jsonObject
        return Dataset(
            json.decodeFromJsonElement(ListSerializer(TaskItem.serializer()), d["tasks"]!!),
            json.decodeFromJsonElement(ListSerializer(CalBlock.serializer()), d["blocks"]!!),
            json.decodeFromJsonElement(ListSerializer(Session.serializer()), d["sessions"]!!),
            json.decodeFromJsonElement(ListSerializer(Capture.serializer()), d["captures"]!!),
            json.decodeFromJsonElement(ListSerializer(ReasonLog.serializer()), d["reasons"]!!),
        )
    }

    /** A non-string argument counts as absent (§3.2), as `args.str()` does. */
    private fun str(o: JsonObject, k: String): String? {
        val e: JsonElement = o[k] ?: return null
        val p = e as? JsonPrimitive ?: return null
        return if (p.isString) p.content else null
    }

    @Test fun jvmDefaultZoneStaysUtc() {
        assertEquals("UTC", TimeZone.getDefault().id)
    }

    @Test fun everySharedVectorMatchesExactly() {
        val vectors = root["vectors"]!!.jsonArray
        assertEquals(25, vectors.size)
        val failures = ArrayList<String>()
        for (e in vectors) {
            val v = e.jsonObject
            val id = v["id"]!!.jsonPrimitive.content
            val zone = ZoneId.of(v["tz"]!!.jsonPrimitive.content)
            val nowMs = Instant.parse(v["now"]!!.jsonPrimitive.content).toEpochMilli()
            val ds = dataset(v["dataset"]!!.jsonPrimitive.content)
            val args = v["args"]!!.jsonObject
            val out = renderPeriodReview(
                PeriodReviewArgs(str(args, "period"), str(args, "date"), str(args, "from"), str(args, "to")),
                ds.tasks, ds.blocks, ds.sessions, ds.captures, ds.reasons,
                nowMs, zone,
                historyFloor = (v["historyFloor"] as? JsonPrimitive)?.contentOrNull,
                blocksPartial = (v["blocksPartial"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
            val expect = v["expect"]!!.jsonPrimitive.content
            if (out != expect) failures += "$id\n--- expected\n$expect\n--- got\n$out"
        }
        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
        assertEquals("UTC", TimeZone.getDefault().id)
    }

    @Test fun everyVectorResultFitsTheCap() {
        for (e in root["vectors"]!!.jsonArray) assertTrue(e.jsonObject["expect"]!!.jsonPrimitive.content.length <= 1600)
    }

    // §6 (a): the hard cut never splits a surrogate pair and never touches the tail.
    @Test fun hardCutDropsADanglingHighSurrogateAndKeepsTheTail() {
        val tail = listOf("Before that (x): nothing done and no focus logged.")
        val max = 120
        val room = max - 1 - (tail[0].length + 1)
        // The cut at `room` units lands between the emoji's two halves.
        val head = listOf("a".repeat(room - 1) + "😀" + "zzzz")
        val capped = capPeriodReview(head, tail, null, maxChars = max)
        assertEquals("a".repeat(room - 1) + "…\n" + tail[0], capped)
        assertTrue(capped.length <= max)
        assertFalse(capped.any { Character.isHighSurrogate(it) })
    }

    @Test fun capDropsByAreaThenAlsoBeforeCutting() {
        val area = "By area: " + "x".repeat(50) + "."
        val also = "Also: " + "y".repeat(50) + "."
        val head = listOf("ok: review.", "Done: nothing marked done.", also, area)
        val tail = listOf("Before that (x): nothing done and no focus logged.")
        val full = (head + tail).joinToString("\n")
        val noArea = capPeriodReview(head, tail, area, maxChars = full.length - 1)
        assertFalse(noArea.contains("By area"))
        assertTrue(noArea.contains("Also:"))
        val noAlso = capPeriodReview(head, tail, area, maxChars = full.length - area.length - 2)
        assertFalse(noAlso.contains("Also:"))
        assertTrue(noAlso.endsWith(tail[0]))
    }

    // §6 (e): the stamp grammar on this platform's parser.
    @Test fun stampGrammar() {
        val ny = ZoneId.of("America/New_York")
        val utc = ZoneId.of("UTC")
        fun ms(s: String) = Instant.parse(s).toEpochMilli()
        assertEquals(ms("2026-09-15T14:00:00.123Z"), periodStampMs("2026-09-15T14:00:00.123456+00:00", ny))
        assertEquals(ms("2026-09-21T03:30:00Z"), periodStampMs("2026-09-21T09:00:00+05:30", ny))
        assertEquals(ms("2026-09-21T05:00:00Z"), periodStampMs("2026-09-21T01:00:00", ny))       // zone-less = LOCAL
        assertEquals(ms("2026-09-21T01:00:00Z"), periodStampMs("2026-09-21T01:00:00", utc))
        assertNull(periodStampMs("2026-09-16", ny))                     // bare date
        assertNull(periodStampMs("2026-09-16 10:00:00+00", ny))         // space for T
        assertNull(periodStampMs("2026-09-31T10:00:00Z", ny))           // roll-over
        assertNull(periodStampMs("2026-09-16T24:00:00Z", ny))
        assertNull(periodStampMs("2026-09-16T10:00:00+24:00", ny))
        assertNull(periodStampMs("2026-02-29T10:00:00Z", ny))
        assertEquals(ms("2028-02-29T10:00:00Z"), periodStampMs("2028-02-29T10:00:00Z", ny))
        assertEquals(ms("2026-09-16T10:00:00.100Z"), periodStampMs("2026-09-16T10:00:00.1Z", ny))
        assertEquals(ms("2026-09-16T10:00:00.123Z"), periodStampMs("2026-09-16T10:00:00.123456789Z", ny))
        assertNull(periodStampMs("2026-09-16T10:00:00.1234567890Z", ny))
        assertNull(periodStampMs("2026-09-16t10:00:00Z", ny))
        assertNull(periodStampMs("2026-09-16T10:00:00z", ny))
        assertNull(periodStampMs("2026-09-16T10:00:00Z\n", ny))
        assertNull(periodStampMs("1899-12-31T10:00:00Z", ny))
        assertEquals(ms("2026-09-16T10:00:00Z"), periodStampMs("2026-09-16T12:00+02", ny))
        assertEquals(ms("2026-09-16T10:00:00Z"), periodStampMs("2026-09-16T12:00+0200", ny))
        // New York DST: a skipped wall time moves forward, a repeated one takes the earlier offset.
        assertEquals(ms("2026-03-08T07:30:00Z"), periodStampMs("2026-03-08T02:30:00", ny))
        assertEquals(ms("2026-11-01T05:30:00Z"), periodStampMs("2026-11-01T01:30:00", ny))
    }

    @Test fun cleanNameRules() {
        assertEquals("(untitled)", periodCleanName(" \t\n"))
        assertEquals("a b", periodCleanName(" a \t b "))
        val long = "x".repeat(41)
        assertEquals("x".repeat(39) + "…", periodCleanName(long))
        assertEquals("x".repeat(40), periodCleanName("x".repeat(40)))
        // Code points, not UTF-16 units: 40 emoji stay whole.
        val emoji = "😀".repeat(40)
        assertEquals(emoji, periodCleanName(emoji))
    }
}
