package tech.csalliance.unstuck.ui.notifications

import tech.csalliance.unstuck.core.model.CallRequest
import tech.csalliance.unstuck.core.time.Time
import tech.csalliance.unstuck.surface.NotificationLog
import tech.csalliance.unstuck.sync.NotificationsClient

/**
 * The bell's server cards: the `notification_queue` rows of every moment the
 * bell can show, folded into the local notification log. Call cards are
 * matched to their call in the local `call_requests` mirror by label (a card
 * carries no call id); every other card (a list or task shared with you,
 * "<Name> updated <list>", "<Name> finished <item>", a late item, an invite,
 * the brief, a recap) is shown as the server wrote it and opens the link its
 * push carried. Before this the bell showed only what THIS phone logged, so an
 * event whose push went to another device, was held back (Calm, cooldown), or
 * never rang had no in-app record. 1:1 with iOS NotificationQueueCards and the
 * web's useNotificationQueue / mergeRecent. Pure.
 */
object NotificationQueueCards {
    const val CALL_MOMENT = "call"

    /** notification_queue.moment → the bell's kind. The kind is the push's
     *  `data.kind` for that moment, so a card and the local log entry its push
     *  left carry the same kind (what [mergeRecent] pairs on when the copy
     *  differs — collection_activity's push is shorter than its card). The
     *  collection senders all push as `collection_share` (the web's
     *  MOMENT_KIND). Reminders (`task_reminder` / `task_starting`) are NOT
     *  read: this phone schedules its own reminder with its own copy, so the
     *  server's card would be a second line for the same reminder. */
    val MOMENT_KIND: Map<String, String> = linkedMapOf(
        CALL_MOMENT to "call",
        "task_share" to "task_share",
        "shared_task_done" to "shared_task_done",
        "shared_session_start" to "shared_session_start",
        "shared_session_end" to "shared_session_end",
        "collection_share" to "collection_share",
        "collection_task_done" to "collection_share",
        "collection_activity" to "collection_share",
        "collection_late" to "collection_share",
        "circle_invite" to "circle_invite",
        "invite_claimed" to "invite_claimed",
        "morning_brief" to "morning_brief",
        "session_recap" to "session_recap",
    )

    /** The moments the bell reads from the server. */
    val BELL_MOMENTS: List<String> = MOMENT_KIND.keys.toList()

    /** Where a card with no stored link opens (a row written before 084, or by
     *  a sender that passes none): the coarse version of the push's link, the
     *  same places the router sends those pushes (the web's MOMENT_DEEP_LINK). */
    fun fallbackLink(moment: String): String = when {
        moment.startsWith("collection") -> "unstuck://collections"
        moment == "task_share" || moment.startsWith("shared_") -> "unstuck://tasks"
        moment == "circle_invite" || moment == "invite_claimed" -> "unstuck://settings?section=People"
        else -> "unstuck://today"
    }
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
        val moment = card.moment ?: CALL_MOMENT
        if (moment != CALL_MOMENT) {
            return NotificationLog.Entry(
                id = "q_${card.id}",
                kind = MOMENT_KIND[moment] ?: moment,
                title = card.title.orEmpty(),
                body = card.body.orEmpty(),
                deepLink = card.deepLink?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackLink(moment),
                at = at,
            )
        }
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

    /** Fold the server cards into the local log: newest first, capped, and a
     *  server card that is the same event as a local entry dropped. Each local
     *  entry absorbs at most ONE card, nearest in time within 5 min, in three
     *  passes: the same copy; both recaps; then (not calls) the same kind — a
     *  push whose copy is shorter than its card (collection_activity) is still
     *  that card. One-to-one, so a second event of the same kind whose push was
     *  held back keeps its card. A local "I called about X" and the server's
     *  "Unstuck called you about X" differ in copy on purpose — the local one
     *  is the miss, the card is the record — so both stay. */
    fun mergeRecent(local: List<NotificationLog.Entry>, queue: List<NotificationLog.Entry>, cap: Int = CAP): List<NotificationLog.Entry> {
        val usedLocal = HashSet<Int>()
        val dropped = HashSet<Int>()
        val passes: List<(NotificationLog.Entry, NotificationLog.Entry) -> Boolean> = listOf(
            { l, q -> l.title == q.title && l.body == q.body },
            { l, q -> l.kind == "session_recap" && q.kind == "session_recap" },
            { l, q -> l.kind == q.kind && q.kind != CALL_MOMENT },
        )
        for (same in passes) {
            val pairs = ArrayList<Triple<Long, Int, Int>>()
            for ((qi, q) in queue.withIndex()) {
                if (qi in dropped) continue
                for ((li, l) in local.withIndex()) {
                    if (li in usedLocal) continue
                    val d = Math.abs(l.at - q.at)
                    if (d < NEAR_MS && same(l, q)) pairs.add(Triple(d, qi, li))
                }
            }
            for ((_, qi, li) in pairs.sortedBy { it.first }) {
                if (qi in dropped || li in usedLocal) continue
                dropped.add(qi); usedLocal.add(li)
            }
        }
        val deduped = queue.filterIndexed { i, _ -> i !in dropped }
        val seen = HashSet<String>()
        return (local + deduped).filter { seen.add(it.id) }.sortedByDescending { it.at }.take(cap)
    }
}
