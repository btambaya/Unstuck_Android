package tech.csalliance.unstuck.core.logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

// CallOutcomeQueue — the pure half of the call-outcome reporter, ported from
// iOS `CallsOutcomeReporter` (App/Calls/AppCallEnvironment.swift). A lost
// `missed` / `snoozed` / `done` would leave the server row in `calling`
// forever (the cron never re-rings it), so:
//   • every report is PERSISTED (toJson → SharedPreferences, by
//     :app calls/CallOutcomeStore) the moment it's queued;
//   • the queue flushes IN ORDER (answered before done, never the reverse),
//     one send at a time — `next()` is always the HEAD;
//   • a TRANSIENT failure (transport, 5xx, 401 refresh, 408, 429) keeps the
//     item at the head with attempts+1 and a backoff before the next try
//     (2 s, 5 s, 15 s, 30 s, 60 s, 120 s — capped);
//   • a PERMANENT refusal (404 the row is gone / not the caller's, 400 / 422
//     malformed — any other 4xx) drops THAT item at once and the drain
//     continues: one dead report must never block every later outcome;
//   • sign-out clears the queue — nothing left in it can be sent with the
//     next account's JWT;
//   • a `missed` report carries the "I called about X" notification WITH it
//     (`notify` + the ring `payload`, persisted too): the store posts it once
//     the server takes the report and answers `retry: false`, or refuses it
//     for good (no re-ring is coming either way) — and swallows it on
//     `retry: true`, the server's one automatic ring-back 5 min later (calls
//     build-out 2026-09-20 §5). The flag arrives asynchronously, so the ring
//     path never posts it at the 30 s timeout itself.

/** What `call-outcome` answered: `{ ok, status, retry, snoozeUntil? }` on
 *  every outcome (072). `retry: true` ⇒ the server re-rings in 5 min, so the
 *  app stays quiet about this miss. A pre-072 server answers no `retry` →
 *  false. Tolerant decode — garbage → the defaults (the report DID land). */
data class CallOutcomeReceipt(
    val ok: Boolean = true,
    val retry: Boolean = false,
    val status: String? = null,
    val snoozeUntil: String? = null,
) {
    companion object {
        val EMPTY = CallOutcomeReceipt()

        fun fromJson(raw: String?): CallOutcomeReceipt {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return EMPTY
            val o = runCatching { Json.parseToJsonElement(s).jsonObject }.getOrNull() ?: return EMPTY
            return fromJson(o)
        }

        fun fromJson(o: JsonObject): CallOutcomeReceipt = CallOutcomeReceipt(
            ok = (o["ok"] as? JsonPrimitive)?.booleanOrNull ?: true,
            retry = (o["retry"] as? JsonPrimitive)?.booleanOrNull ?: false,
            status = (o["status"] as? JsonPrimitive)?.contentOrNull,
            snoozeUntil = (o["snoozeUntil"] as? JsonPrimitive)?.contentOrNull,
        )

        /** `retry: true` ⇒ the server rings again in 5 min ⇒ stay quiet;
         *  a report refused for good ⇒ no re-ring is coming ⇒ tell them. */
        fun shouldNotify(retry: Boolean): Boolean = !retry
    }
}

@Serializable
data class PendingOutcome(
    val callId: String,
    val outcome: CallOutcome,
    val snoozeMin: Int? = null,
    /** When it was queued (epoch ms). */
    val at: Long,
    val attempts: Int = 0,
    /** What the conversation produced ("voice failed: …") — appended server-side. */
    val outcomeNotes: List<String>? = null,
    /** Not before this instant (epoch ms) — the backoff after a transient failure. */
    val notBeforeMs: Long = 0,
    /** The local notification to post once the server settles this report
     *  WITHOUT a retry (see the file header) — null = nothing to say. */
    val notify: CallNotificationKind? = null,
    /** The ring payload the notification is rendered from (IncomingCallPayload.toData). */
    val payload: Map<String, String>? = null,
) {
    /** The ring this report is about, for the deferred notification. */
    val ringPayload: IncomingCallPayload? get() = payload?.let { IncomingCallPayload.fromData(it) }
}

class CallOutcomeQueue(items: List<PendingOutcome> = emptyList()) {
    private val list: MutableList<PendingOutcome> = items.toMutableList()

    val items: List<PendingOutcome> get() = list.toList()
    val isEmpty: Boolean get() = list.isEmpty()
    val size: Int get() = list.size

    /** Append (tail). Order is delivery order. */
    fun enqueue(item: PendingOutcome) { list.add(item) }

    fun enqueue(
        callId: String, outcome: CallOutcome, snoozeMin: Int? = null, outcomeNotes: List<String>? = null,
        nowMs: Long = System.currentTimeMillis(),
        notify: CallNotificationKind? = null, payload: Map<String, String>? = null,
    ): PendingOutcome {
        val item = PendingOutcome(
            callId = callId, outcome = outcome, snoozeMin = snoozeMin, at = nowMs, outcomeNotes = outcomeNotes,
            notify = notify, payload = payload,
        )
        enqueue(item)
        return item
    }

    /** The next item to send: the HEAD, once its backoff has elapsed; null
     *  when the queue is empty or the head is still backing off (never skips
     *  ahead — ordering is the point). */
    fun next(nowMs: Long = System.currentTimeMillis()): PendingOutcome? {
        val head = list.firstOrNull() ?: return null
        return if (head.notBeforeMs <= nowMs) head else null
    }

    /** When the head may be sent (epoch ms), or null when empty. For the
     *  store's "flush again after N ms" scheduling. */
    fun nextAttemptAt(): Long? = list.firstOrNull()?.notBeforeMs

    /** The server accepted the head for `callId`: remove it. Removes the head
     *  when it matches, else the first item with that id (a straggler). */
    fun markSent(callId: String): Boolean {
        val i = if (list.firstOrNull()?.callId == callId) 0 else list.indexOfFirst { it.callId == callId }
        if (i < 0) return false
        list.removeAt(i)
        return true
    }

    /** A send for `callId` failed. `permanent` (a 4xx that no retry can fix)
     *  → dropped so the drain continues; transient → attempts+1 and a backoff
     *  before the next try, still at the head (re-enqueued, never dropped).
     *  Returns the item as it now stands (null when dropped / unknown). */
    fun markFailed(callId: String, permanent: Boolean, nowMs: Long = System.currentTimeMillis()): PendingOutcome? {
        val i = if (list.firstOrNull()?.callId == callId) 0 else list.indexOfFirst { it.callId == callId }
        if (i < 0) return null
        if (permanent) { list.removeAt(i); return null }
        val cur = list[i]
        val attempts = cur.attempts + 1
        val updated = cur.copy(attempts = attempts, notBeforeMs = nowMs + backoffMs(attempts))
        list[i] = updated
        return updated
    }

    /** Sign-out: forget everything (the caller persists the empty state). */
    fun clear() { list.clear() }

    fun toJson(): String = json.encodeToString(ListSerializer(PendingOutcome.serializer()), list)

    companion object {
        /** Delay before the Nth retry (attempts = 1 → 2 s, 2 → 5 s, …), capped at the last. */
        val BACKOFF_MS: List<Long> = listOf(2_000, 5_000, 15_000, 30_000, 60_000, 120_000)

        fun backoffMs(attempts: Int): Long = BACKOFF_MS[(attempts - 1).coerceIn(0, BACKOFF_MS.size - 1)]

        /** call-outcome refused for GOOD: the 4xx family minus the three that
         *  mean "later" — 401 (token refresh), 408, 429. */
        fun isPermanentStatus(status: Int): Boolean = status in 400..499 && status !in setOf(401, 408, 429)

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Tolerant: bad / blank JSON → an empty queue (never crash a launch
         *  over a corrupt preference). */
        fun fromJson(s: String?): CallOutcomeQueue {
            if (s.isNullOrBlank()) return CallOutcomeQueue()
            val items = runCatching { json.decodeFromString(ListSerializer(PendingOutcome.serializer()), s) }.getOrNull()
            return CallOutcomeQueue(items ?: emptyList())
        }
    }
}
