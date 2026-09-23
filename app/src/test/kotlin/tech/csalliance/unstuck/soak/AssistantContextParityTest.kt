package tech.csalliance.unstuck.soak

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.ui.assistant.buildAssistantContext
import tech.csalliance.unstuck.ui.assistant.nextLiveBlockByTask
import tech.csalliance.unstuck.ui.assistant.topByDescendingStable
import kotlin.random.Random

/**
 * BEHAVIOUR LOCK for the buildAssistantContext optimisation (perf soak,
 * 2026-09-12). Three things changed inside it and nothing else:
 *   1. the next-live-block map is a single min-pass, not a full sort of every
 *      block by an allocated `date + startTime` key,
 *   2. the newest-12 captures are selected, not sorted-then-truncated,
 *   3. `week[].name` and the live-focus task resolve through a task-by-id map
 *      instead of `tasks.firstOrNull` per row.
 * Each is compared here against the expression it replaced, over the heavy soak
 * account — and then the whole JSON payload is compared against one rebuilt
 * with the legacy expressions, key by key.
 */
class AssistantContextParityTest {

    private val tasks = SoakSeed.tasks()
    private val blocks = SoakSeed.blocks(tasks)
    private val sessions = SoakSeed.sessions(tasks)
    private val captures = SoakSeed.captures()
    private val collections = SoakSeed.collections()
    private val today = SoakSeed.today

    // ── 1. next live block per task ──────────────────────────────────────────

    /** The pre-optimisation body, verbatim. */
    private fun legacyNextLiveBlockByTask(blocks: List<CalBlock>, today: String): Map<String, CalBlock> {
        val blocksByTask = HashMap<String, CalBlock>()
        for (b in blocks.sortedBy { it.date + it.startTime }) {
            val tid = b.taskId?.takeIf { it.isNotEmpty() } ?: continue
            if (b.done || b.skipped || b.date < today) continue
            if (blocksByTask[tid] == null) blocksByTask[tid] = b
        }
        return blocksByTask
    }

    @Test fun nextLiveBlockByTask_matchesTheSortedImplementation() {
        val expected = legacyNextLiveBlockByTask(blocks, today)
        assertTrue("fixture must have live blocks", expected.size > 20)
        assertEquals(expected, nextLiveBlockByTask(blocks, today))
        // Shuffled input (ties land in a different order) must still agree.
        val shuffled = blocks.shuffled(Random(3))
        assertEquals(legacyNextLiveBlockByTask(shuffled, today), nextLiveBlockByTask(shuffled, today))
        // Heavy ties: many blocks sharing one date+startTime for one task.
        val tied = (0 until 50).map { i ->
            blocks.first { it.taskId != null }.copy(id = "tie$i", date = SoakSeed.dayIso(3), startTime = "08:00", done = false, skipped = false)
        }
        assertEquals(legacyNextLiveBlockByTask(tied, today)["t0040"]?.id, nextLiveBlockByTask(tied, today)["t0040"]?.id)
        assertEquals(legacyNextLiveBlockByTask(tied, today), nextLiveBlockByTask(tied, today))
        assertEquals(emptyMap<String, CalBlock>(), nextLiveBlockByTask(emptyList(), today))
    }

    // ── 2. stable top-n ──────────────────────────────────────────────────────

    @Test fun topByDescendingStable_matchesSortedByDescendingTake() {
        for (n in listOf(0, 1, 2, 12, 299, 300, 500)) {
            assertEquals(
                "n=$n",
                captures.sortedByDescending { it.at }.take(n).map { it.id },
                topByDescendingStable(captures, n) { it.at }.map { it.id },
            )
        }
        // Every key identical — stability is the whole question here.
        val flat = captures.map { it.copy(at = "2026-01-01T00:00:00.000Z") }
        assertEquals(
            flat.sortedByDescending { it.at }.take(12).map { it.id },
            topByDescendingStable(flat, 12) { it.at }.map { it.id },
        )
        // Random keys with deliberate collisions.
        val rnd = Random(5)
        val noisy = captures.map { it.copy(at = "2026-01-%02dT00:00:00.000Z".format(rnd.nextInt(1, 9))) }
        for (n in listOf(1, 5, 12, 40)) {
            assertEquals(
                "noisy n=$n",
                noisy.sortedByDescending { it.at }.take(n).map { it.id },
                topByDescendingStable(noisy, n) { it.at }.map { it.id },
            )
        }
        assertEquals(emptyList<String>(), topByDescendingStable(emptyList<String>(), 12) { it })
    }

    // ── 3. the whole payload ─────────────────────────────────────────────────

    @Test fun wholeContextPayloadIsUnchanged() {
        val api = SoakAppPerfTest.SoakApi(tasks, blocks, sessions, captures, collections)
        val ctx: JsonObject = runBlocking { buildAssistantContext(api) }

        // tasks[].scheduledDate/Time come from the next-live-block map.
        val legacyNext = legacyNextLiveBlockByTask(blocks, today)
        // Newest first since iOS build 79 parity (audit 2026-09-21): a stable
        // sort on the parsed createdAt, then the first 60.
        val open = tasks.filter { !it.done }
            .sortedByDescending { tech.csalliance.unstuck.core.time.Time.parseMillis(it.createdAt) ?: Long.MIN_VALUE }.take(60)
        val ctxTasks = ctx["tasks"]!!.jsonArray
        assertEquals(open.size, ctxTasks.size)
        assertTrue("fixture must schedule some of the 60", open.any { legacyNext[it.id] != null })
        open.forEachIndexed { i, t ->
            val row = ctxTasks[i].jsonObject
            assertEquals(t.id, row["id"]!!.jsonPrimitive.content)
            assertEquals(legacyNext[t.id]?.date, row["scheduledDate"]?.jsonPrimitive?.content)
            assertEquals(legacyNext[t.id]?.startTime, row["scheduledTime"]?.jsonPrimitive?.content)
        }

        // captures[] = the newest 12 by `at`, ids in that order.
        assertEquals(
            captures.sortedByDescending { it.at }.take(12).map { it.id },
            ctx["captures"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content },
        )

        // week[].name resolved via tasks.firstOrNull before, a map now.
        val weekFrom = tech.csalliance.unstuck.core.logic.IsoDate.mondayOf(today)
        val weekTo = tech.csalliance.unstuck.core.logic.addDaysIso(weekFrom, 7)
        val week = blocks.filter { it.date >= weekFrom && it.date < weekTo }.sortedBy { it.date + it.startTime }.take(60)
        val ctxWeek: JsonArray = ctx["week"]!!.jsonArray
        assertEquals(week.size, ctxWeek.size)
        assertTrue("fixture must have a week", week.isNotEmpty())
        week.forEachIndexed { i, b ->
            val t = b.taskId?.let { id -> tasks.firstOrNull { it.id == id } }
            val row = ctxWeek[i].jsonObject
            assertEquals(b.date, row["date"]!!.jsonPrimitive.content)
            assertEquals(b.taskName.ifEmpty { t?.name ?: "?" }, row["name"]!!.jsonPrimitive.content)
        }

        // The top-level key set is part of the server prompt's contract.
        assertEquals(
            listOf(
                "today", "todayWeekday", "upcoming", "now", "nowNote", "todayFree", "currentName",
                "profile", "tone", "struggle", "focusWindow", "noticed", "week", "areas", "tags", "captures",
                "people", "tasks", "lists",
            ).sorted(),
            ctx.keys.sorted(),
        )
    }
}
