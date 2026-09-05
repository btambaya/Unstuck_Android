package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.ShareSlot
import tech.csalliance.unstuck.core.model.SharedBlock
import tech.csalliance.unstuck.core.model.SharedWithMe
import tech.csalliance.unstuck.core.time.Time
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

// Port of lib/shared-blocks.ts — the pure helpers behind "a shared task behaves
// like your own task, placed by the OWNER's next live block" (migration 052).
// Two projections feed them:
//   • SharedWithMe.next*  — one slot per share (the owner's next live block, else
//     its most recent past block) → list bucketing (Today / Upcoming / Backlog)
//     + the "Sat 04:30 · 45m" row label + the detail sheet's "Planned …" line.
//   • SharedBlock[]       — every block inside a date window → the calendars,
//     converted to the CalBlock shape with a `shared:` id prefix so every
//     renderer can keep them OUT of its drag / resize / edit / delete paths.
// Nothing here writes. Shared blocks never enter the local cal_blocks store, so
// they can't count toward the user's own load, free-slot search or the brief.

// ── Date-window helpers (shared_task_blocks caps a window at 62 days) ─────────

const val SHARED_BLOCKS_MAX_DAYS = 62

/** An inclusive 'YYYY-MM-DD' window. */
data class IsoRange(val from: String, val to: String)

private fun parseYmd(iso: String): LocalDate? = runCatching { LocalDate.parse(iso) }.getOrNull()

/** 'YYYY-MM-DD' + n days (n may be negative). Unparseable input is returned as-is. */
fun addDaysIso(iso: String, n: Int): String = parseYmd(iso)?.plusDays(n.toLong())?.toString() ?: iso

/** Inclusive day count of [from, to]; 0 when the range is inverted / invalid. */
fun daysInRange(r: IsoRange): Int {
    val a = parseYmd(r.from) ?: return 0
    val b = parseYmd(r.to) ?: return 0
    if (b.isBefore(a)) return 0
    return (b.toEpochDay() - a.toEpochDay()).toInt() + 1
}

/** Cap a window so the RPC never rejects it (`range_too_wide` / `bad_range`). */
fun clampSharedRange(r: IsoRange): IsoRange {
    val n = daysInRange(r)
    if (n == 0) return IsoRange(r.from, r.from)
    if (n <= SHARED_BLOCKS_MAX_DAYS) return r
    return IsoRange(r.from, addDaysIso(r.from, SHARED_BLOCKS_MAX_DAYS - 1))
}

/** The Monday-anchored week containing [iso] — the Day + Week calendars share this
 *  window so flipping between them (or day-to-day inside a week) is a cache hit. */
fun weekRangeContaining(iso: String): IsoRange {
    val d = parseYmd(iso) ?: return IsoRange(iso, iso)
    val monday = d.minusDays(((d.dayOfWeek.value + 6) % 7).toLong())
    return IsoRange(monday.toString(), monday.plusDays(6).toString())
}

/** First → last day of the (1-based) month — what the Month grid paints. */
fun monthRange(year: Int, month: Int): IsoRange {
    val first = LocalDate.of(year, month, 1)
    return IsoRange(first.toString(), first.withDayOfMonth(first.lengthOfMonth()).toString())
}

// ── Bucketing by the owner's next block ──────────────────────────────────────

enum class ShareBucket {
    DONE, TODAY, UPCOMING, OVERDUE, UNSCHEDULED,
    /** The owner parked it in Later (migration 053) — only All shows it. */
    LATER,
    /** The owner's latest block already ran AND was ticked done while the task
     *  stays open (a multi-session task between sessions). Not a live plan, not
     *  overdue: it sits in All only — never in Today (cross-platform rule, 2026-09). */
    FINISHED,
}

/** Where a share lands, given the local [todayIso]:
 *  done → DONE; the owner's Later → LATER; no block → UNSCHEDULED (lives in Today,
 *  like a task that arrived without a plan); block today → TODAY; later → UPCOMING;
 *  a past block still open → OVERDUE (Backlog); a past block already FINISHED →
 *  FINISHED (All only). ISO dates compare lexicographically. [item]'s date is the
 *  recipient-local one the sync client resolved from `next_start_at`. */
fun shareBucket(item: ShareSlot, todayIso: String): ShareBucket {
    if (item.done) return ShareBucket.DONE
    if (item.later) return ShareBucket.LATER
    val d = item.nextDate ?: return ShareBucket.UNSCHEDULED
    if (d == todayIso) return ShareBucket.TODAY
    if (d > todayIso) return ShareBucket.UPCOMING
    return if (item.nextDone == true) ShareBucket.FINISHED else ShareBucket.OVERDUE
}

// ── Recipient-zone resolution (migration 053) ────────────────────────────────

/** A slot's civil date + time as the RECIPIENT sees it. */
data class LocalSlot(val date: String?, val time: String?)

/** Resolve the owner's slot into [zone]: when the server sent [startAt] (an ISO
 *  instant computed from the owner's date + time in the OWNER's zone) it wins and
 *  is re-expressed as the recipient's local 'YYYY-MM-DD' + 'HH:MM' — so a 09:00
 *  London block reads 10:00 in Berlin and buckets on the right day across midnight.
 *  FORGIVING: an absent / unparseable instant (pre-053 server, garbage) falls back
 *  to the owner's raw [date] / [time] untouched, never to a blank slot. */
fun resolveSharedSlot(startAt: String?, date: String?, time: String?, zone: ZoneId = ZoneId.systemDefault()): LocalSlot {
    val ms = startAt?.takeIf { it.isNotBlank() }?.let { Time.parseMillis(it) } ?: return LocalSlot(date, time)
    val local = runCatching { Instant.ofEpochMilli(ms).atZone(zone) }.getOrNull() ?: return LocalSlot(date, time)
    val d = "%04d-%02d-%02d".format(local.year, local.monthValue, local.dayOfMonth)
    val t = "%02d:%02d".format(local.hour, local.minute)
    return LocalSlot(d, t)
}

/** Chronological: earliest slot first; unscheduled rows sink to the end. Equal
 *  slots compare 0, so a stable sort keeps the projection's own order for them. */
fun compareShareSlot(a: ShareSlot, b: ShareSlot): Int {
    val ad = a.nextDate
    val bd = b.nextDate
    if (ad == null && bd == null) return 0
    if (ad == null) return 1
    if (bd == null) return -1
    if (ad != bd) return if (ad < bd) -1 else 1
    val at = a.nextStartTime ?: ""
    val bt = b.nextStartTime ?: ""
    return at.compareTo(bt).coerceIn(-1, 1)
}

val shareSlotComparator: Comparator<ShareSlot> = Comparator { a, b -> compareShareSlot(a, b) }

/** Life-area filter for shared rows — mirrors the Delegated group, except a share
 *  with NO area still shows under any filter (the owner's vocabulary isn't ours;
 *  hiding area-less shares would make them vanish for no visible reason). The
 *  "Unassigned" sentinel narrows to exactly those area-less shares. */
fun shareMatchesArea(lifeArea: String?, activeArea: String?): Boolean {
    if (activeArea.isNullOrEmpty()) return true
    if (activeArea == UNASSIGNED_AREA) return lifeArea.isNullOrEmpty()
    return lifeArea.isNullOrEmpty() || lifeArea == activeArea
}

// ── Labels ───────────────────────────────────────────────────────────────────

/** "45m" / "1h" / "1h 30m"; null for a missing / non-positive duration. */
fun fmtDuration(min: Int?): String? {
    if (min == null || min <= 0) return null
    val h = min / 60
    val m = min % 60
    return when {
        h == 0 -> "${m}m"
        m == 0 -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

private fun weekdayShort(iso: String): String =
    parseYmd(iso)?.dayOfWeek?.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) ?: iso

private fun monthDay(iso: String): String =
    parseYmd(iso)?.let { "${it.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${it.dayOfMonth}" } ?: iso

/** The row chip: "Today 04:30 · 45m" / "Sat 04:30 · 45m" (inside the next 6 days)
 *  / "Sat Sep 12 04:30 · 45m" (further out, or in the past). Null when the owner
 *  hasn't scheduled it — callers fall back to the estimate. */
fun shareSlotLabel(item: ShareSlot, todayIso: String): String? {
    val d = item.nextDate ?: return null
    val time = item.nextStartTime
    val day = when {
        d == todayIso -> "Today"
        d > todayIso && d <= addDaysIso(todayIso, 6) -> weekdayShort(d)
        else -> "${weekdayShort(d)} ${monthDay(d)}"
    }
    return listOfNotNull(if (time != null) "$day $time" else day, fmtDuration(item.nextDurationMinutes)).joinToString(" · ")
}

/** The detail-sheet line: "Planned Sat, Sep 5 · 04:30 · 45m" (+ " · overdue" when
 *  the slot has passed and the task is still open). Null when nothing is planned. */
fun plannedLabel(item: ShareSlot, todayIso: String): String? {
    val d = item.nextDate ?: return null
    val whenLabel = if (d == todayIso) "today" else "${weekdayShort(d)}, ${monthDay(d)}"
    val parts = mutableListOf("Planned $whenLabel")
    item.nextStartTime?.let { parts += it }
    fmtDuration(item.nextDurationMinutes)?.let { parts += it }
    // A past block the owner already ticked done (task still open) reads "finished",
    // not "overdue" — nothing is due (web wording).
    if (!item.done && d < todayIso) parts += if (item.nextDone == true) "finished" else "overdue"
    return parts.joinToString(" · ")
}

/** "anna" for "anna@example.com"; "Someone" for a blank name. */
fun shareFirstName(name: String?): String = (name?.takeIf { it.isNotBlank() } ?: "Someone").substringBefore('@')

// ── Calendar shape ───────────────────────────────────────────────────────────

/** Prefix on the CalBlock id of every shared block on a calendar. A shared block's
 *  id can therefore NEVER collide with (or be looked up as) one of the user's own
 *  cal_blocks, and any mutating path can cheaply refuse it via [isSharedBlockId]. */
const val SHARED_BLOCK_ID_PREFIX = "shared:"

fun isSharedBlockId(id: String): Boolean = id.startsWith(SHARED_BLOCK_ID_PREFIX)

/** A shared block in the calendars' CalBlock shape so it can share the lane layout
 *  with the user's own blocks. Deliberately NOT a task block (taskId null, kind
 *  PLACEHOLDER): [isTaskBlock] is false, so even a renderer that forgets the
 *  shared check never offers edit / drag / focus on it — the worst fall-through is
 *  the display-only placeholder path. Renderers branch on [isSharedBlockId] FIRST. */
fun SharedBlock.asCalBlock(): CalBlock = CalBlock(
    id = SHARED_BLOCK_ID_PREFIX + blockId,
    taskId = null,
    taskName = title,
    startTime = startTime,
    durationMinutes = durationMinutes,
    date = date,
    kind = CalBlockKind.PLACEHOLDER,
    done = done,
    skipped = skipped,
)

/** The row the shared-task detail sheet takes, synthesized from a tapped calendar
 *  block (the sheet then fetches shared_task_detail for the live task state). The
 *  block's own slot seeds `next*` so the "Planned …" line is right immediately, and
 *  [SharedWithMe.openedFrom] pins the sheet to THIS occurrence even after the live
 *  detail (with the task's NEXT block) lands — the sheet describes what was tapped,
 *  as on the web. `done` is the BLOCK's state (the best this projection knows) —
 *  callers prefer the live SharedWithMe row for the same task when they have one
 *  (see [openedFrom] to keep the tapped slot in that case). */
fun SharedBlock.asSharedWithMe(): SharedWithMe = SharedWithMe(
    shareId = shareId, taskId = taskId, ownerName = ownerName, level = level, title = title,
    done = done, nextBlockId = blockId, nextDate = date, nextStartTime = startTime,
    nextDurationMinutes = durationMinutes, nextDone = done, openedFrom = this,
)

/** The live list row for a task, pinned to the calendar occurrence it was opened
 *  from: `sharedWithMe.firstOrNull { it.taskId == b.taskId }?.openedFrom(b) ?: b.asSharedWithMe()`.
 *  The detail sheet then shows the tapped slot, not the task's next one. */
fun SharedWithMe.openedFrom(block: SharedBlock): SharedWithMe = copy(openedFrom = block)

/** The renderable set: skipped occurrences are cancelled for that day (the same
 *  rule the own-block surfaces apply) and external blocks never arrive from the
 *  RPC (dropped here anyway). Sorted by date, then start. */
fun liveSharedBlocks(blocks: List<SharedBlock>): List<SharedBlock> =
    blocks.filter { !it.skipped && it.kind != "external" }
        .sortedWith(compareBy({ it.date }, { it.startTime }))

/** "Anna · London weekend" — the owner leads so a glance says whose it is. */
fun sharedBlockLabel(b: SharedBlock): String = "${shareFirstName(b.ownerName)} · ${b.title}"
