package tech.csalliance.unstuck.core.logic

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// The ONE endless assistant thread (redesign 2026-08-02, web parity with
// lib/assistant/use-assistant.ts): display history persists LONG, while the
// model window stays SHORT. There is no "new chat" — the conversation just
// continues, with day dividers marking where each day's turns begin.

/** Display persistence: how many turns survive an app restart. */
const val ASSISTANT_MAX_PERSISTED = 200

/** How many turns the model ever sees. Matches the web's MAX_MODEL_WINDOW. */
const val ASSISTANT_MAX_MODEL_WINDOW = 40

/** The bit of a conversation turn the windowing rule needs. Implemented by the
 *  wire/persistence type (:sync ChatMessage) so this stays pure and testable. */
interface AssistantWindowTurn {
    val role: String
    /** Locally-injected display turn (the daily check-in) — never sent. */
    val local: Boolean
}

/**
 * The per-request model window: the non-local tail, aligned to START at a user
 * turn so the model never resumes from a dangling tool/assistant turn (an
 * orphaned `tool` message with no preceding `assistant` tool_calls is rejected
 * by the OpenAI-compatible upstream).
 */
fun <T : AssistantWindowTurn> assistantModelWindow(
    history: List<T>,
    max: Int = ASSISTANT_MAX_MODEL_WINDOW,
): List<T> {
    val win = history.filter { !it.local }.takeLast(max)
    val firstUser = win.indexOfFirst { it.role == "user" }
    return if (firstUser > 0) win.drop(firstUser) else win
}

/** What actually gets written to disk: the display tail, capped. Unlike the
 *  model window this KEEPS local turns (they're part of the visible history). */
fun <T> assistantPersistWindow(history: List<T>, max: Int = ASSISTANT_MAX_PERSISTED): List<T> =
    history.takeLast(max)

private val DAY_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

/** Divider label for a turn: "Today" / "Yesterday" / "Mon 28 Jul". Null when the
 *  turn carries no timestamp (legacy persisted turns) — those get no divider. */
fun assistantDayLabel(atMs: Long?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
    if (atMs == null || atMs <= 0L) return null
    val day = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DAY_FMT)
    }
}

/** The check-in is offered once per LOCAL day. [lastIso] is the stored
 *  "YYYY-MM-DD" of the last check-in (null = never). */
fun shouldCheckIn(lastIso: String?, todayIso: String): Boolean = lastIso != todayIso
