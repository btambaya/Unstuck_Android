package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.Session

// The ONE session filter every focus number goes through (analytics decision
// D1, 2026-09-24): the Insights page, the Today week pill, get_insights,
// get_period_review and the assistant's golden hours all read sessions through
// here, so they can never disagree about what counted as focus.
//
// Why: in prod, 45% of sessions were accidental starts under a minute, and 9
// forgotten timers (up to 143.8 h) held 82% of all focus time
// (analytics cross-check P0-1 / P0-7). Stored rows are NOT changed — this is a
// read-side rule, identical on web (lib/), iOS (UnstuckCore) and Android.
//
// - a session under [MIN_COUNTED_SEC] (60 s) doesn't count at all;
// - a session counts min(actual, cap), where
//   cap = max(3 × estimate, estimate + 60 min), capped at 4 h, when the session
//   carries an estimate (> 0 min), else 4 h.

/** Sessions shorter than this are accidental starts and never count. */
const val MIN_COUNTED_SEC = 60

/** The most one session can ever count for. */
const val MAX_COUNTED_SEC = 4 * 3600

/** The most seconds a session with this estimate can count for. */
fun sessionCapSec(estimateMin: Int?): Int {
    val est = estimateMin ?: 0
    if (est <= 0) return MAX_COUNTED_SEC
    return minOf(maxOf(3 * est, est + 60) * 60, MAX_COUNTED_SEC)
}

/** Does this session count as focus at all? */
fun isCountable(s: Session): Boolean = s.actualSec >= MIN_COUNTED_SEC

/** The seconds this session counts for: 0 when it doesn't count, else its
 *  length clamped to [sessionCapSec]. */
fun countedSec(s: Session): Int = if (!isCountable(s)) 0 else minOf(s.actualSec, sessionCapSec(s.estimateMin))

/** The sessions that count, each with `actualSec` replaced by [countedSec].
 *  Idempotent: filtering an already-filtered list changes nothing. */
fun countableSessions(sessions: List<Session>): List<Session> =
    sessions.mapNotNull { s ->
        if (!isCountable(s)) null
        else countedSec(s).let { c -> if (c == s.actualSec) s else s.copy(actualSec = c) }
    }
