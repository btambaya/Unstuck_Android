package tech.csalliance.unstuck.sync

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import tech.csalliance.unstuck.core.logic.everyNWeeksDays
import tech.csalliance.unstuck.core.logic.mondayIso
import tech.csalliance.unstuck.core.logic.normalizeWeekdays
import tech.csalliance.unstuck.core.logic.recurrenceTopUp
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables

// The recurrence horizon top-up on Android (stage 2 — "same id for same day",
// Ahmad 2026-09-23; deterministic-occurrence-ids.md §3c "C21 ordering" and §f;
// port of iOS build 85's topUpRecurrenceHorizon + RecurrenceTopUpGate, with the
// web stage-2 review's server check).
//
// regenerateForTask only runs when the user touches a task, and it mints a fixed
// 8 weeks ahead, so 8 weeks after the last edit made on Android a repeating task
// had no future occurrence left — gone from Today, Upcoming and the calendar —
// unless an iOS device of the same user topped it up. This extends every
// repeating task's TAIL (recurrenceTopUp), never deletes, and never fills a date
// the series already covered.
//
// Every tail day is minted with its deterministic id, insert-if-absent WITHOUT
// rule H's retime (a stale top-up must never move a row another device's user
// retimed), so two devices extending the same tail land on one row. Each minted
// day is mirrored to Google once its insert is confirmed (rule G; "every day,
// everywhere"), one at a time on the Google worker.
//
// When it runs — RecurrenceTopUpGate's rules:
//  • only after a pull whose cal_blocks read SUCCEEDED (Hydrator.calBlocksPull;
//    the generic "pull finished" signal fires even when that read failed), and
//    one that moved since the last run. The app asks after EVERY pull, so that
//    pull itself must have moved the stamp (iOS's `pulledAfter`,
//    Hydrator.seqBeforeLatestPull): an earlier good read, say at 23:59, would
//    otherwise stand in for the first pull of a new day whose read failed
//    (stage 2 review, Ahmad 2026-09-23);
//  • never when that read hit PostgREST's row cap: a truncated store would keep
//    re-minting rows it can't see (§f; paginating the pull is the real fix);
//  • once per local day per user, and again when the time zone changes (a day
//    rollover or a zone change is picked up by the next pull — the 60 s floor
//    while visible, or the app coming back);
//  • serialised: one run in flight plus one trailing re-run.
//
// What it mints for is checked against the SERVER first (the web stage-2 review):
// the gate vouches for this device's cal_blocks, not its TASKS store, which the
// same pull may not have refreshed (the tasks read failed, or "Stop repeating" on
// another device landed its block deletes before our cal_blocks read and its task
// row after our tasks read). A series stopped elsewhere then looked lapsed here
// and was revived — up to 55 days, on every device and in Google. So one read of
// the candidate tasks goes out before any mint; a task whose server row is gone,
// done, or repeats differently from the local copy is left for the next run. If
// that read fails, nothing is minted and the day is not used up.

/** When the top-up may run (port of iOS RecurrenceTopUpGate). Pure. */
class RecurrenceTopUpGate {
    enum class Verdict {
        RUN,
        /** No cal_blocks read has succeeded in this process (for this account). */
        NO_PULL,
        /** The read hit the row cap: the store may be missing rows. */
        TRUNCATED,
        /** No new successful read since the last run, or the latest pull's own
         *  read failed ([verdict]'s pulledAfter). */
        PULL_NOT_ADVANCED,
        /** Already ran for this user today, in this time zone. */
        ALREADY_RAN_TODAY,
    }

    data class Run(val userId: String, val day: String, val timeZone: String, val pullSeq: Long)

    var lastRun: Run? = null
        private set

    /** [pulledAfter]: the stamp's seq just BEFORE the latest pull began (0 when
     *  there was none yet); that pull must have moved past it. Null skips the
     *  check (as iOS's hydrate hook, which has just pulled). */
    fun verdict(pull: Hydrator.CalBlocksPull?, userId: String, today: String, timeZone: String, pulledAfter: Long? = null): Verdict {
        if (pull == null) return Verdict.NO_PULL
        if (pulledAfter != null && pull.seq <= pulledAfter) return Verdict.PULL_NOT_ADVANCED
        if (pull.mayBeTruncated) return Verdict.TRUNCATED
        val last = lastRun
        if (last == null || last.userId != userId) return Verdict.RUN
        if (pull.seq <= last.pullSeq) return Verdict.PULL_NOT_ADVANCED
        if (last.day == today && last.timeZone == timeZone) return Verdict.ALREADY_RAN_TODAY
        return Verdict.RUN
    }

    fun recordRun(pull: Hydrator.CalBlocksPull, userId: String, today: String, timeZone: String) {
        lastRun = Run(userId, today, timeZone, pull.seq)
    }

    /** Sign-out: the next account starts fresh. */
    fun reset() { lastRun = null }
}

class RecurrenceHorizonTopUp(
    private val store: LocalStore,
    private val write: WriteThrough,
    private val remote: SyncRemote,
    /** The last successful cal_blocks read (Hydrator.calBlocksPull). */
    private val pull: () -> Hydrator.CalBlocksPull?,
    /** Its seq just before the latest pull began (Hydrator.seqBeforeLatestPull):
     *  the run needs THAT pull's read to have succeeded. Null = no such check. */
    private val pulledAfter: () -> Long? = { null },
    private val currentUserId: () -> String?,
    private val today: () -> String,
    private val timeZone: () -> String,
    private val log: (String) -> Unit = { println(it) },
) {
    private val gate = RecurrenceTopUpGate()
    private val running = AtomicBoolean(false)
    @Volatile private var again = false

    /** What the last run did (tests / logs). */
    @Volatile var lastMinted: Int = 0
        private set

    /** Extend every repeating task's tail for [userId] — see the header. A call
     *  while a run is in flight asks for ONE trailing run and returns; the run in
     *  flight does it. */
    suspend fun request(userId: String) {
        again = true
        while (again && running.compareAndSet(false, true)) {
            try {
                while (again) {
                    again = false
                    try {
                        runOnce(userId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        log("[recurrence] horizon top-up failed: $e")
                    }
                }
            } finally {
                running.set(false)
            }
            // A request that landed between the last check and the release: loop and
            // take it (the compareAndSet decides who does).
        }
    }

    /** Sign-out: the gate forgets the last run. */
    fun reset() {
        gate.reset()
        lastMinted = 0
    }

    private suspend fun runOnce(userId: String) {
        if (currentUserId() != userId) return
        // The stamp first: a pull starting in between then reads as not advanced
        // (its own completion asks again), never as an advance it hasn't made.
        val pull = pull()
        val before = pulledAfter()
        val day = today()
        val zone = timeZone()
        val verdict = gate.verdict(pull, userId, day, zone, before)
        if (verdict != RecurrenceTopUpGate.Verdict.RUN || pull == null) {
            if (verdict == RecurrenceTopUpGate.Verdict.TRUNCATED) log("[recurrence] horizon top-up skipped: the cal_blocks read hit the row cap")
            return
        }
        val candidates = plan(day)
        if (candidates.isEmpty()) {
            gate.recordRun(pull, userId, day, zone)
            lastMinted = 0
            return
        }
        // The server's word on each candidate series before anything is minted
        // (see the header). A failed read uses nothing up: the next pull retries.
        val server = serverSeries(candidates.map { it.first.id }) ?: run {
            log("[recurrence] horizon top-up deferred: could not check the series against the server")
            return
        }
        if (currentUserId() != userId) return
        gate.recordRun(pull, userId, day, zone)
        // Planned again from the store as it is NOW: the user may have edited a
        // series while the check was on the wire.
        var minted = 0
        var skipped = 0
        for ((task, add) in plan(day)) {
            if (!serverAgrees(task, server[task.id])) { skipped++; continue }
            for (b in add) {
                if (currentUserId() != userId) return
                if (write.insertCalBlockIfAbsent(b, retimeIfTaken = false) == WriteThrough.MintOutcome.INSERTED) minted++
            }
        }
        lastMinted = minted
        if (skipped > 0) log("[recurrence] horizon top-up: $skipped series changed on the server — left for the next run")
        if (minted > 0) log("[recurrence] horizon top-up: $minted occurrence(s)")
    }

    /** Each open repeating task's tail days, from the store as it is now. A done
     *  template is an ended series (no reminders, no top-up). */
    private suspend fun plan(day: String): List<Pair<TaskItem, List<CalBlock>>> {
        val templates = store.snapshot(Tables.TASKS, TaskItem.serializer()).filter { it.recurrence != null && !it.done }
        if (templates.isEmpty()) return emptyList()
        // Grouped once: a per-template filter over every block is O(n·m).
        val byTask = store.snapshot(Tables.CAL_BLOCKS, CalBlock.serializer()).groupBy { it.taskId }
        return templates
            .map { it to recurrenceTopUp(it, byTask[it.id].orEmpty(), day) }
            .filter { it.second.isNotEmpty() }
    }

    /** The server's rows for these task ids (a missing id: the task is gone); null
     *  when the read failed. Chunked so the `in` filter stays a short URL. */
    private suspend fun serverSeries(ids: List<String>): Map<String, TaskItem>? = try {
        val out = HashMap<String, TaskItem>()
        for (chunk in ids.chunked(SERIES_READ_CHUNK)) {
            for (row in remote.fetchByIds(Tables.TASKS, chunk)) {
                val t = runCatching { DbRowCodec.decodeTask(row) }.getOrNull() ?: continue
                out[t.id] = t
            }
        }
        out
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

    companion object {
        private const val SERIES_READ_CHUNK = 100

        /** Does the server still say this task repeats exactly as the local copy
         *  does (not gone, not done, same rule)? Weekly days compare as a set. */
        internal fun serverAgrees(local: TaskItem, server: TaskItem?): Boolean {
            if (server == null || server.done) return false
            val a = local.recurrence ?: return false
            val b = server.recurrence ?: return false
            return when {
                a is Recurrence.Daily && b is Recurrence.Daily -> a.until == b.until
                a is Recurrence.Monthly && b is Recurrence.Monthly -> a.until == b.until
                a is Recurrence.Weekly && b is Recurrence.Weekly ->
                    a.until == b.until && normalizeWeekdays(a.daysOfWeek).toSet() == normalizeWeekdays(b.daysOfWeek).toSet()
                // Every N weeks (every-n-weeks spec §8.1): the same interval, days as a
                // set, the same week one (anchors compared by their Monday) and until.
                // Without this arm `else -> false` skipped every N-week series for good,
                // as "changed on the server".
                a is Recurrence.EveryNWeeks && b is Recurrence.EveryNWeeks ->
                    a.interval == b.interval && a.until == b.until &&
                        everyNWeeksDays(a).toSet() == everyNWeeksDays(b).toSet() &&
                        mondayIso(a.anchor) == mondayIso(b.anchor)
                else -> false
            }
        }
    }
}
