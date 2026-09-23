package tech.csalliance.unstuck.ui.notifications

import tech.csalliance.unstuck.core.model.CallRequest
import tech.csalliance.unstuck.core.time.Time
import tech.csalliance.unstuck.surface.NotificationLog
import tech.csalliance.unstuck.sync.NotificationsClient

/**
 * The bell's "Unstuck called you about X" cards: the server's
 * `notification_queue` rows of moment `call`, matched to their call in the
 * local `call_requests` mirror by label (a card carries no call id), then
 * folded into the local notification log. Before this the bell showed only
 * what THIS phone logged, so a call the server rang on another device, or one
 * this phone couldn't take, never appeared. 1:1 with iOS NotificationQueueCards
 * (build 72) and the web's useNotificationQueue / mergeRecent. Pure.
 */
object NotificationQueueCards {
    const val CALL_MOMENT = "call"
    /** send-call writes `body = "Unstuck is calling about <label>"` on the
     *  card (the push's copy); the label is what the mirror is keyed on. */
    const val CALL_BODY_PREFIX = "Unstuck is calling about "
    /** Local entries and server cards describing the same event collapse when
     *  the copy matches within 5 min, or both are recaps within 5 min. */
    const val NEAR_MS = 5 * 60 * 1000L
    const val CAP = 20

    /** "Unstuck is calling about speak to James" → "speak to James"; null for any other body. */
    fun callLabel(body: String): String? {
        val t = body.trim()
        if (!t.startsWith(CALL_BODY_PREFIX)) return null
        return t.removePrefix(CALL_BODY_PREFIX).trim().ifEmpty { null }
    }

    /** The mirrored call a card is about: the same label (case-insensitive),
     *  the one whose ring time is nearest the card's creation (a label can be
     *  booked more than once over time). */
    fun matchingCall(label: String, cardAtMs: Long?, calls: List<CallRequest>): CallRequest? {
        val key = label.lowercase()
        val same = calls.filter { it.label.trim().lowercase() == key }
        if (cardAtMs == null) return same.firstOrNull()
        return same.minByOrNull { c -> c.effectiveAtMs?.let { Math.abs(it - cardAtMs) } ?: Long.MAX_VALUE }
    }

    /** A queue row → the bell's entry: kind `call`, "Unstuck called you about
     *  <label>", the call's notes as the body (or the card's own copy when no
     *  call matches), the anchored task as the destination (else Today). */
    fun entry(card: NotificationsClient.QueueCard, calls: List<CallRequest>): NotificationLog.Entry {
        val at = card.createdAt?.let { Time.parseMillis(it) } ?: 0L
        val cardBody = card.body.orEmpty()
        val label = callLabel(cardBody)
        val call = label?.let { matchingCall(it, at, calls) }
        val title = label?.let { "Unstuck called you about $it" } ?: card.title?.takeIf { it.isNotEmpty() } ?: "Unstuck called you"
        val body = when {
            call != null && call.notes.isNotEmpty() -> call.notes.joinToString("\n")
            call != null && (call.status == "missed" || call.status == "declined") -> "You missed it — no notes on this one."
            else -> cardBody
        }
        val link = call?.taskId?.let { "unstuck://task/$it" } ?: "unstuck://today"
        return NotificationLog.Entry(id = "q_${card.id}", kind = "call", title = title, body = body, deepLink = link, at = at)
    }

    /** Fold the server cards into the local log: newest first, capped, a server
     *  card that duplicates a local entry (same copy within 5 min, or both
     *  recaps within 5 min) dropped. A local "I called about X" and the server's
     *  "Unstuck called you about X" differ in copy on purpose — the local one is
     *  the miss, the card is the record — so both stay. */
    fun mergeRecent(local: List<NotificationLog.Entry>, queue: List<NotificationLog.Entry>, cap: Int = CAP): List<NotificationLog.Entry> {
        val deduped = queue.filter { q ->
            local.none { l ->
                if (Math.abs(l.at - q.at) >= NEAR_MS) return@none false
                if (l.kind == "session_recap" && q.kind == "session_recap") return@none true
                l.title == q.title && l.body == q.body
            }
        }
        val seen = HashSet<String>()
        return (local + deduped).filter { seen.add(it.id) }.sortedByDescending { it.at }.take(cap)
    }
}
