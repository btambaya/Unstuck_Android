package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.ExternalEvent
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.time.Time
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.max

// Pure mapping between Google Calendar events and local CalBlocks. Port of
// the exported helpers in lib/sync/google-sync.ts. The pull/push
// orchestration (Edge Function calls, reconciliation) lives in :sync; only
// the value transforms are here.

// UTC ISO with milliseconds, matching the web's toISOString() output.
private val ISO_UTC_MS: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** Local YYYY-MM-DD for an ISO timestamp, anchored to the user's timezone. */
fun isoToLocalYmd(iso: String): String {
    val ms = Time.parseMillis(iso) ?: return iso.take(10)
    return Clock.dateIso(ms)
}

/** HH:MM (local, zero-padded) for an ISO timestamp. */
fun isoToLocalHHMM(iso: String): String {
    val ms = Time.parseMillis(iso) ?: return "00:00"
    return "%02d:%02d".format(Time.hourOf(ms), Time.minuteOf(ms))
}

/** Whole-minute duration between two ISO timestamps, floored at 15 (so
 *  zero/short Google events stay visible). */
fun diffMinutes(startIso: String, endIso: String): Int {
    val s = Time.parseMillis(startIso) ?: return 15
    val e = Time.parseMillis(endIso) ?: return 15
    val ms = max(0L, e - s)
    return max(15, Math.round(ms / 60_000.0).toInt())
}

/** Map a Google event to an external CalBlock. The id is derived from the
 *  Google id (g_<id>) so re-pulls overwrite the same row. `calendarId` is
 *  accepted for signature parity (the push layer resolves the target
 *  calendar from the connection). */
fun externalEventToBlock(ev: ExternalEvent, calendarId: String): CalBlock =
    CalBlock(
        id = "g_${ev.id}",
        taskId = null,
        taskName = ev.summary.ifEmpty { "(untitled)" },
        startTime = isoToLocalHHMM(ev.start),
        durationMinutes = diffMinutes(ev.start, ev.end),
        date = isoToLocalYmd(ev.start),
        externalEventId = ev.id,
        externalConnectionId = ev.connectionId,
        kind = CalBlockKind.EXTERNAL,
    )

/** Convert a block's date + HH:MM into a Google-friendly ISO start/end,
 *  anchored in local time. Port of blockToIsoRange. */
fun blockToIsoRange(b: CalBlock): Pair<String, String> {
    val dParts = b.date.split("-").map { it.toIntOrNull() }
    val tParts = b.startTime.split(":").map { it.toIntOrNull() }
    if (dParts.size != 3 || dParts.any { it == null }) return b.date to b.date
    val (y, m, d) = dParts.map { it!! }
    val hour = tParts.getOrNull(0) ?: 0
    val minute = tParts.getOrNull(1) ?: 0
    val startInstant = LocalDateTime.of(y, m, d, hour, minute)
        .atZone(ZoneId.systemDefault()).toInstant()
    val endInstant = startInstant.plusSeconds(b.durationMinutes.toLong() * 60)
    return ISO_UTC_MS.format(startInstant) to ISO_UTC_MS.format(endInstant)
}
