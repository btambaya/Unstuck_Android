package tech.csalliance.unstuck.core.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import tech.csalliance.unstuck.core.model.CallKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

// IncomingCallPayload — the FCM ring for a requested call (server `send-call`,
// docs/ios-gateway-plan.md "C0-android"), 1:1 with iOS App/Calls/
// IncomingCallPayload.swift (payload + the CallSession derivations).
//
// FCM contract (data-only, every value a String):
//   { kind:"call", callKind?, callId, taskId?, title, notes?: JSON string[], scheduledAt: ISO,
//     deepLink:"unstuck://call/<callId>",
//     label, blockId?, taskName?, startTime?, endTime?, firstAction?, estimateMin?: "N",
//     captures?: JSON string[], name?, body }
// `title` == `label` (the request's label, "speak to James"); `body` is the
// fallback copy the generic renderer shows when this fails to decode.
//
// WHICH CALL THIS IS (calls build-out, migration 072): `kind` is ALWAYS the
// push-type discriminator `"call"` (CallPushHandler keys on it); the row's
// `call_requests.kind` travels as `callKind` — requested | test | morning |
// evening | after_block — and a server that instead wrote the row's kind into
// `kind` itself is accepted too ([isCallPush]). Unknown / absent ⇒
// `requested`, the only kind there was before. `endTime` (after_block) is the
// block's end, "HH:MM" local (or the ISO / "YYYY-MM-DD HH:MM" forms
// `startTime` takes), for "<task> was on till <time>".
//
// Tolerant decode (iOS rules): `callId` + a non-empty label are REQUIRED —
// null means "not a valid call push" and the caller falls through to the
// generic renderer so a ring is never silent; every array defaults to `[]`,
// blanks read as absent. `toData` round-trips for Intent extras.

data class IncomingCallPayload(
    val callId: String,
    /** What the call is about — the request's label ("speak to James"). */
    val label: String,
    /** What to remind them about, verbatim — read back when the call opens. */
    val notes: List<String> = emptyList(),
    val taskId: String? = null,
    val blockId: String? = null,
    val taskName: String? = null,
    /** The anchored block's start — ISO-8601, or "HH:MM" (today) / "YYYY-MM-DD HH:MM" (local). */
    val startTime: String? = null,
    /** after_block: the block's end (same forms as [startTime]). */
    val endTime: String? = null,
    val firstAction: String? = null,
    val estimateMin: Int? = null,
    val captures: List<String> = emptyList(),
    /** The user's preferred name (what the call opens with). */
    val name: String? = null,
    /** When this ring was due (`snooze_until ?? call_at`), epoch ms. */
    val scheduledAtMs: Long? = null,
    val deepLink: String = "$DEEP_LINK_PREFIX$callId",
    /** The `call_requests.kind` this ring is for, as sent (null ⇒ requested). */
    val callKind: String? = null,
    /** The push's own `kind` slot as received — `"call"` normally; a server
     *  that put the row's kind there is tolerated ([resolvedKind]). */
    val kind: String? = KIND,
) {
    /** Who booked the call — what the script opens with: `callKind` when it
     *  names a kind, else `kind` when the server put the row's kind there,
     *  else requested. */
    val resolvedKind: CallKind
        get() = CallKind.strict(callKind) ?: CallKind.strict(kind) ?: CallKind.REQUESTED

    /** The name the call opens with — the server's preferred name, first
     *  token only (a full "Ahmad Tambaya" reads wrong on a phone call). */
    val preferredName: String?
        get() = name?.trim()?.takeIf { it.isNotEmpty() }?.split(' ')?.firstOrNull { it.isNotEmpty() }

    /** The anchored block's start as epoch ms (see [startTime]); null when the
     *  call isn't anchored to a timed block. `receivedAtMs` anchors a bare
     *  "HH:MM" to the day the push arrived. */
    fun startMs(receivedAtMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? =
        parseStart(startTime, receivedAtMs, zone)

    /** Whole minutes from `nowMs` until the block starts (negative once it has
     *  started); null when the call isn't anchored to a timed block. */
    fun minutesUntilStart(nowMs: Long, receivedAtMs: Long = nowMs, zone: ZoneId = ZoneId.systemDefault()): Int? {
        val s = startMs(receivedAtMs, zone) ?: return null
        return roundHalfAwayFromZero((s - nowMs) / 60_000.0)
    }

    /** after_block: the block's end as epoch ms (see [endTime]); null when the
     *  push carried none. A bare "HH:MM" anchors to the day the push arrived. */
    fun endMs(receivedAtMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? =
        parseStart(endTime, receivedAtMs, zone)

    /** after_block: "11:30am" — the end the way people say it (iOS
     *  `CallSession.spokenEnd`); null when the push carried no usable end. */
    fun spokenEnd(receivedAtMs: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        val raw = endTime?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (CallSettingsLogic.minutesOfDay(raw) != null) return CallSettingsLogic.spokenTime(raw)
        val ms = endMs(receivedAtMs, zone) ?: return null
        return CallSettingsLogic.spokenTime(CallSettingsLogic.hhmm(ms, zone))
    }

    /** The payload as string extras (Intent / notification extras / a persisted
     *  ring). `fromData(toData())` is the identity. */
    fun toData(): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m[KEY_KIND] = KIND
        // The kind rides as callKind only when the push named one (in either
        // slot) — a plain requested push round-trips unchanged, and a kind a
        // server put into `kind` is not lost behind the `call` discriminator.
        if (callKind != null || CallKind.strict(kind) != null) m[KEY_CALL_KIND] = resolvedKind.wire
        m[KEY_CALL_ID] = callId
        m[KEY_LABEL] = label
        m[KEY_TITLE] = label
        if (notes.isNotEmpty()) m[KEY_NOTES] = encodeList(notes)
        taskId?.let { m[KEY_TASK_ID] = it }
        blockId?.let { m[KEY_BLOCK_ID] = it }
        taskName?.let { m[KEY_TASK_NAME] = it }
        startTime?.let { m[KEY_START_TIME] = it }
        endTime?.let { m[KEY_END_TIME] = it }
        firstAction?.let { m[KEY_FIRST_ACTION] = it }
        estimateMin?.let { m[KEY_ESTIMATE_MIN] = it.toString() }
        if (captures.isNotEmpty()) m[KEY_CAPTURES] = encodeList(captures)
        name?.let { m[KEY_NAME] = it }
        scheduledAtMs?.let { m[KEY_SCHEDULED_AT] = Instant.ofEpochMilli(it).toString() }
        m[KEY_DEEP_LINK] = deepLink
        return m
    }

    companion object {
        const val KIND = "call"
        const val DEEP_LINK_PREFIX = "unstuck://call/"

        const val KEY_KIND = "kind"
        const val KEY_CALL_KIND = "callKind"
        const val KEY_END_TIME = "endTime"
        const val KEY_CALL_ID = "callId"
        const val KEY_TITLE = "title"
        const val KEY_LABEL = "label"
        const val KEY_NOTES = "notes"
        const val KEY_TASK_ID = "taskId"
        const val KEY_BLOCK_ID = "blockId"
        const val KEY_TASK_NAME = "taskName"
        const val KEY_START_TIME = "startTime"
        const val KEY_FIRST_ACTION = "firstAction"
        const val KEY_ESTIMATE_MIN = "estimateMin"
        const val KEY_CAPTURES = "captures"
        const val KEY_NAME = "name"
        const val KEY_SCHEDULED_AT = "scheduledAt"
        const val KEY_DEEP_LINK = "deepLink"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** A push's `kind` names a call: the `"call"` discriminator, or one of
         *  the five call kinds (a server that wrote the row's kind into `kind`).
         *  Blank / null ⇒ false — the caller decides what an untagged push is. */
        fun isCallPush(kind: String?): Boolean {
            val k = kind?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
            return k == KIND || CallKind.strict(k) != null
        }

        /** Decode the FCM `data` map (or the extras [toData] produced). null ⇒
         *  not a valid call push: `kind` present and neither "call" nor a call
         *  kind, or a blank `callId` / label. `label` wins over `title`; either
         *  satisfies the rule. */
        fun fromData(data: Map<String, String>): IncomingCallPayload? {
            val kind = data[KEY_KIND]?.trim()
            if (kind != null && kind.isNotEmpty() && !isCallPush(kind)) return null
            val callId = data[KEY_CALL_ID]?.trim().orEmpty()
            val label = (blankToNull(data[KEY_LABEL]) ?: blankToNull(data[KEY_TITLE])).orEmpty()
            if (callId.isEmpty() || label.isEmpty()) return null
            return IncomingCallPayload(
                callId = callId,
                label = label,
                notes = parseList(data[KEY_NOTES]),
                taskId = blankToNull(data[KEY_TASK_ID]),
                blockId = blankToNull(data[KEY_BLOCK_ID]),
                taskName = blankToNull(data[KEY_TASK_NAME]),
                startTime = blankToNull(data[KEY_START_TIME]),
                endTime = blankToNull(data[KEY_END_TIME]),
                firstAction = blankToNull(data[KEY_FIRST_ACTION]),
                estimateMin = blankToNull(data[KEY_ESTIMATE_MIN])?.toDoubleOrNull()?.toInt()?.takeIf { it >= 0 },
                captures = parseList(data[KEY_CAPTURES]),
                name = blankToNull(data[KEY_NAME]),
                scheduledAtMs = blankToNull(data[KEY_SCHEDULED_AT])?.let { parseIsoMs(it) },
                deepLink = blankToNull(data[KEY_DEEP_LINK]) ?: "$DEEP_LINK_PREFIX$callId",
                callKind = blankToNull(data[KEY_CALL_KIND])?.lowercase(),
                kind = blankToNull(kind)?.lowercase() ?: KIND,
            )
        }

        /** `notes` / `captures` ride as a JSON string array; a bare non-JSON
         *  string is kept as a single note (never lose what the user asked to
         *  be reminded of). Lines are trimmed, blanks dropped. */
        fun parseList(raw: String?): List<String> {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return emptyList()
            val parsed = runCatching {
                json.parseToJsonElement(s).jsonArray.map { it.jsonPrimitive.content }
            }.getOrNull()
            return cleanLines(parsed ?: listOf(s))
        }

        fun encodeList(lines: List<String>): String =
            JsonArray(lines.map { JsonPrimitive(it) }).toString()

        fun cleanLines(lines: List<String>): List<String> = lines.map { it.trim() }.filter { it.isNotEmpty() }

        fun blankToNull(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() }

        /** ISO-8601 with an offset (`Z` / `+00:00`, fractional or not) → epoch ms. */
        fun parseIsoMs(s: String): Long? =
            runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()

        /** ISO-8601 → absolute; "YYYY-MM-DD HH:MM" / "YYYY-MM-DDTHH:MM" → local;
         *  "HH:MM" → that time on the day the push arrived (local). Port of
         *  iOS `CallSession.parseStart`. */
        fun parseStart(raw: String?, anchorMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return null
            parseIsoMs(s)?.let { return it }
            val parts = s.replace('T', ' ').split(' ').filter { it.isNotEmpty() }
            var day: LocalDate = Instant.ofEpochMilli(anchorMs).atZone(zone).toLocalDate()
            var timePart: String? = parts.firstOrNull()
            if (parts.size >= 2) {
                val ymd = parts[0].split('-').mapNotNull { it.toIntOrNull() }
                if (ymd.size != 3) return null
                day = runCatching { LocalDate.of(ymd[0], ymd[1], ymd[2]) }.getOrNull() ?: return null
                timePart = parts[1]
            }
            val t = timePart ?: return null
            val hm = t.split(':').mapNotNull { it.toIntOrNull() }
            if (hm.size < 2 || hm[0] !in 0..23 || hm[1] !in 0..59) return null
            return LocalDateTime.of(day.year, day.monthValue, day.dayOfMonth, hm[0], hm[1], 0)
                .atZone(zone).toInstant().toEpochMilli()
        }

        /** Swift `.rounded()` (schoolbook: halves away from zero), not Kotlin's half-even. */
        internal fun roundHalfAwayFromZero(x: Double): Int =
            if (x < 0) -Math.round(-x).toInt() else Math.round(x).toInt()
    }
}
