package tech.csalliance.unstuck.soak

import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.model.CollectionItem
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TagRow
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.time.Time
import java.time.Instant
import kotlin.random.Random

/**
 * SOAK SEED — a deterministic "heavy account" built entirely in memory.
 * ~800 tasks (40 recurring templates), 4000 cal_blocks (2400 recurring
 * occurrences + 1400 task blocks + 200 external Google blocks), 1500 sessions,
 * 300 captures, 40 collections x 30 items. Never touches production data.
 */
object SoakSeed {
    const val TASKS = 800
    const val TEMPLATES = 40
    const val OCC_BLOCKS = 2400
    const val TASK_BLOCKS = 1400
    const val EXTERNAL_BLOCKS = 200
    const val SESSIONS = 1500
    const val CAPTURES = 300
    const val COLLECTIONS = 40
    const val ITEMS_PER_COLLECTION = 30

    val today: String = Clock.todayIso()
    private val todayMs: Long = Time.startOfDayMillis(System.currentTimeMillis())
    fun dayIso(offset: Int): String = Clock.dateIso(Time.addDaysMillis(todayMs, offset))
    fun isoAt(offsetDays: Int, hour: Int = 9): String =
        Instant.ofEpochMilli(Time.addDaysMillis(todayMs, offsetDays) + hour * 3_600_000L).toString()

    private val AREAS = listOf("Work", "Home", "Health", "Family", "Admin")
    private val TAGS = listOf("deep", "quick", "call", "errand", "review")

    fun tasks(rnd: Random = Random(7)): List<TaskItem> = (0 until TASKS).map { i ->
        val isTemplate = i < TEMPLATES
        val done = !isTemplate && i % 5 == 0
        val createdOffset = -(rnd.nextInt(400))
        TaskItem(
            id = "t%04d".format(i),
            name = "Task number $i ${AREAS[i % AREAS.size]} follow-up",
            estimateMin = listOf(15, 25, 45, 60, 90)[i % 5],
            totalFocused = rnd.nextInt(0, 400),
            done = done,
            tags = if (i % 3 == 0) listOf(TAGS[i % TAGS.size]) else null,
            lifeArea = if (i % 7 == 0) null else AREAS[i % AREAS.size],
            moveCount = if (i % 11 == 0) rnd.nextInt(0, 6) else null,
            completedAt = if (done) isoAt(if (i % 40 == 0) 0 else -rnd.nextInt(1, 200), 14) else null,
            later = if (i % 13 == 0) true else null,
            recurrence = if (isTemplate) Recurrence.Weekly(listOf(i % 7)) else null,
            createdAt = isoAt(createdOffset, 8),
            updatedAt = isoAt(createdOffset, 8),
        )
    }

    /** 4000 blocks: occurrences for the 40 templates, task blocks for the rest,
     *  plus external Google (`g_`) rows. */
    fun blocks(tasks: List<TaskItem>, rnd: Random = Random(11)): List<CalBlock> {
        val out = ArrayList<CalBlock>(OCC_BLOCKS + TASK_BLOCKS + EXTERNAL_BLOCKS)
        val templates = tasks.filter { it.recurrence != null }
        // 60 weekly occurrences per template, -52w .. +7w.
        var n = 0
        for ((ti, tpl) in templates.withIndex()) {
            for (w in 0 until OCC_BLOCKS / TEMPLATES) {
                val offset = (w - 52) * 7 + (ti % 7)
                out += CalBlock(
                    id = "occ-%04d".format(n), taskId = tpl.id, taskName = tpl.name,
                    startTime = "%02d:%02d".format(6 + (ti % 12), if (ti % 2 == 0) 0 else 30),
                    durationMinutes = tpl.estimateMin, date = dayIso(offset), kind = CalBlockKind.TASK,
                    done = offset < 0 && (n % 3 != 0), skipped = offset < 0 && n % 9 == 0,
                    completedAt = if (offset < 0 && n % 3 != 0) isoAt(offset, 10) else null,
                )
                n++
            }
        }
        val plain = tasks.filter { it.recurrence == null }
        for (i in 0 until TASK_BLOCKS) {
            val t = plain[i % plain.size]
            // -180 .. +30, with a cluster on today so Today/lane layout is realistic.
            val offset = if (i % 25 == 0) 0 else rnd.nextInt(-180, 31)
            val hour = 6 + (i % 14)
            out += CalBlock(
                id = "blk-%04d".format(i), taskId = t.id, taskName = t.name,
                startTime = "%02d:%02d".format(hour, if (i % 3 == 0) 30 else 0),
                durationMinutes = t.estimateMin, date = dayIso(offset), kind = CalBlockKind.TASK,
                done = offset < 0 && i % 2 == 0,
            )
        }
        for (i in 0 until EXTERNAL_BLOCKS) {
            val offset = rnd.nextInt(-30, 31)
            out += CalBlock(
                id = "g_ext-%04d".format(i), taskId = null, taskName = "Meeting $i",
                startTime = "%02d:00".format(8 + (i % 10)), durationMinutes = 30,
                date = dayIso(offset), externalEventId = "ev$i", externalConnectionId = "conn1",
                kind = CalBlockKind.EXTERNAL,
            )
        }
        return out
    }

    fun sessions(tasks: List<TaskItem>, rnd: Random = Random(13)): List<Session> = (0 until SESSIONS).map { i ->
        val t = tasks[i % tasks.size]
        Session(
            id = "s%04d".format(i), taskId = t.id, taskName = t.name,
            tags = t.tags, estimateMin = t.estimateMin,
            actualSec = rnd.nextInt(300, 5400),
            completedAt = isoAt(-rnd.nextInt(0, 365), 9 + (i % 10)),
        )
    }

    fun captures(rnd: Random = Random(17)): List<Capture> = (0 until CAPTURES).map { i ->
        Capture(
            id = "c%04d".format(i), taskId = if (i % 4 == 0) "t%04d".format(i % TASKS) else null,
            sessionId = if (i % 3 == 0) "s%04d".format(i % SESSIONS) else null,
            tag = CaptureTag.values()[i % CaptureTag.values().size],
            body = "A passing thought number $i that should be captured and triaged later.",
            at = isoAt(-rnd.nextInt(0, 200), 11),
        )
    }

    fun collections(): List<ItemCollection> = (0 until COLLECTIONS).map { ci ->
        ItemCollection(
            id = "col%02d".format(ci), name = "List $ci", color = "indigo", subtitle = "subtitle $ci",
            items = (0 until ITEMS_PER_COLLECTION).map { ii ->
                CollectionItem(
                    id = "col%02d-i%02d".format(ci, ii),
                    body = "Item $ii of list $ci — something to remember",
                    done = ii % 3 == 0, at = isoAt(-ii, 12),
                )
            },
            sortOrder = ci, ownerId = "me", myRole = "owner",
        )
    }

    fun areas(): List<LifeArea> = AREAS.mapIndexed { i, n -> LifeArea("a$i", n, "indigo", i) }
    fun tags(): List<TagRow> = TAGS.mapIndexed { i, n -> TagRow("tag$i", n, null, i) }
}

/** Tiny timing harness: warm up, then time [iters] runs; reports ms. */
object Bench {
    data class Result(val label: String, val iters: Int, val medianMs: Double, val meanMs: Double, val minMs: Double, val maxMs: Double) {
        override fun toString(): String =
            "PERF | %-52s | n=%3d | median %8.3f ms | mean %8.3f | min %8.3f | max %8.3f".format(label, iters, medianMs, meanMs, minMs, maxMs)
    }

    fun <T> run(label: String, warmup: Int = 12, iters: Int = 25, body: () -> T): Result {
        repeat(warmup) { body() }
        val samples = DoubleArray(iters)
        for (i in 0 until iters) {
            val t0 = System.nanoTime()
            val r = body()
            val t1 = System.nanoTime()
            if (r is Unit) Unit // keep the call
            samples[i] = (t1 - t0) / 1_000_000.0
        }
        samples.sort()
        val res = Result(
            label, iters, samples[iters / 2], samples.average(), samples.first(), samples.last(),
        )
        println(res)
        return res
    }
}
