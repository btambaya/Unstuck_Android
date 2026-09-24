package tech.csalliance.unstuck.core.logic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// CallCoordinatorLogic — the pure half of the Android call state machine,
// ported from iOS App/Calls/CallCoordinator.swift (the receipt rules, the
// one-outcome-per-call rule, the 30 s missed timeout, the end-reason →
// outcome mapping, the snooze tool's ack) and its `CallNotifications` copy.
// Everything Android (FCM window, the ring notification, the full-screen
// activity, the FGS, alarms, SharedPreferences) lives in :app calls/*; this
// file is what those parts agree on, and it is unit-tested in isolation.
//
//   FCM ring ──▶ decide(payload, env)
//                  ├─ Silent    — nobody signed in: no ring, no outcome, no notes
//                  ├─ Declined  — outside the user's hours / the kill-switch:
//                  │              outcome declined + "outside your call hours" notice
//                  ├─ Busy      — a focus session is live: outcome busy + notice
//                  ├─ Stale     — the anchor task/block is gone: outcome stale, silent
//                  └─ Ring      — ring; 30 s unanswered → missed; the "I called
//                                 about X" notice rides WITH the queued report
//                                 and is posted only once call-outcome answers
//                                 `retry: false` (a first miss is re-rung by
//                                 the server 5 min later — `retry: true` — and
//                                 must stay quiet; CallOutcomeReceipt.shouldNotify)
//   Answer tap ──▶ outcome answered → CallVoiceService (opening = CallScript.opening)
//   End        ──▶ endOutcome(reason): hung up → done, snoozed(n) → snoozed+n,
//                  failed(why) → done + outcomeNotes ["voice failed: why"] + notice
//
// Wire strings are the server's (supabase/functions/call-outcome/index.ts
// OUTCOMES) — the enum follows the server, never the reverse. "outside
// hours" reports as `declined` and a voice failure as `done` with a note,
// exactly like iOS; the notification KINDS are the richer local set.

/** What the phone reports back after a call attempt (call-outcome `outcome`). */
@Serializable
enum class CallOutcome(val wire: String) {
    @SerialName("answered") ANSWERED("answered"),
    @SerialName("declined") DECLINED("declined"),
    @SerialName("missed") MISSED("missed"),
    @SerialName("busy") BUSY("busy"),
    @SerialName("snoozed") SNOOZED("snoozed"),
    @SerialName("done") DONE("done"),
    @SerialName("stale") STALE("stale");

    companion object {
        fun fromWire(value: String?): CallOutcome? = entries.firstOrNull { it.wire == value }
    }
}

/** What to do with a ring that just arrived. */
@Serializable
sealed class CallDecision {
    /** Ring the phone. */
    @Serializable @SerialName("ring") data object Ring : CallDecision()
    /** Drop it without a trace — no outcome (no JWT to report with), no
     *  notification (never the previous account's notes). */
    @Serializable @SerialName("silent") data class Silent(val reason: String) : CallDecision()
    /** Outside the user's hours or the kill-switch: outcome `declined` + a notice. */
    @Serializable @SerialName("declined") data class Declined(val reason: String) : CallDecision()
    /** A focus session is live: outcome `busy` + a notice. */
    @Serializable @SerialName("busy") data object Busy : CallDecision()
    /** The anchored task/block is gone: outcome `stale`, silent. */
    @Serializable @SerialName("stale") data object Stale : CallDecision()

    val rings: Boolean get() = this is Ring
}

/** Read-only app facts the receipt rules need — cheap and synchronous, built
 *  by :app AppCallEnvironment inside the FCM window. */
data class CallEnv(
    /** Someone is signed in on this device. */
    val signedIn: Boolean,
    /** The assistant kill-switch (`assistantEnabled=false` declines calls with a notice — risk 10). */
    val assistantEnabled: Boolean,
    /** Inside the user's own allowed hours (CallSettingsLogic.withinHours at receipt). */
    val withinHours: Boolean,
    /** A focus session is live (started, paused or not). */
    val focusLive: Boolean,
    /** The task/block the call anchors to still stands: true / false, or null
     *  when unknown (no store yet — ring rather than silently drop). */
    val anchorExists: Boolean?,
    /** The per-device "Calls from Unstuck" toggle (CallSettings.enabled). */
    val callsEnabled: Boolean = true,
)

/** Why a live conversation ended, as the voice service reports it. */
sealed class CallEndReason {
    /** The user (or the model saying goodbye) ended it normally. */
    data object HungUp : CallEndReason()
    /** "Call me back in N" — handled locally: outcome `snoozed` + snoozeMinutes. */
    data class Snoozed(val minutes: Int) : CallEndReason()
    /** Voice couldn't start or dropped (socket, proxy, mic) — the user still
     *  gets the notes as a notification. */
    data class Failed(val why: String) : CallEndReason()
}

/** What an end / a decision reports: the outcome plus its extras. */
data class OutcomeReport(
    val outcome: CallOutcome,
    val snoozeMin: Int? = null,
    val outcomeNotes: List<String>? = null,
    /** The local notification to post alongside (null = nothing to say). */
    val notify: CallNotificationKind? = null,
)

/** The local notifications the call path posts (CallNotifications in :app
 *  renders them; the copy is [CallNotificationCopy]). */
enum class CallNotificationKind { MISSED, BUSY, OUTSIDE_HOURS, VOICE_FAILED }

/** A local notification the ring path wants posted — pure copy, ported from
 *  iOS `CallNotifications`. `hasActions` ⇒ Start / Reschedule (a task is anchored). */
data class CallNotificationSpec(
    val kind: CallNotificationKind,
    /** Stable per call + kind: a retry updates in place. */
    val id: String,
    val title: String,
    val body: String,
    val deepLink: String,
    val callId: String,
    val taskId: String?,
    val blockId: String?,
    val taskName: String?,
) {
    val hasActions: Boolean get() = taskId != null
}

object CallNotificationCopy {
    const val NO_NOTES = "No notes on this one."
    const val HOURS_HINT = "(outside your call hours — Settings › Notifications & calls)"
    const val VOICE_FAILED_TITLE = "Couldn't start the call — here's what it was about"

    /** Unanswered after 30 s: "I called about <label>" with the notes as
     *  lines; Start / Reschedule when a task is anchored. */
    fun missed(p: IncomingCallPayload) =
        make(p, CallNotificationKind.MISSED, "unstuck.call.missed.${p.callId}", "I called about ${p.label}", body(p.notes))
    fun busy(p: IncomingCallPayload) =
        make(p, CallNotificationKind.BUSY, "unstuck.call.busy.${p.callId}", "I called about ${p.label} — you were mid-focus", body(p.notes))
    fun outsideHours(p: IncomingCallPayload) =
        make(p, CallNotificationKind.OUTSIDE_HOURS, "unstuck.call.hours.${p.callId}", "I called about ${p.label}", body(p.notes) + "\n" + HOURS_HINT)
    fun voiceFailed(p: IncomingCallPayload) =
        make(p, CallNotificationKind.VOICE_FAILED, "unstuck.call.failed.${p.callId}", VOICE_FAILED_TITLE, body(p.notes))

    fun of(kind: CallNotificationKind, p: IncomingCallPayload): CallNotificationSpec = when (kind) {
        CallNotificationKind.MISSED -> missed(p)
        CallNotificationKind.BUSY -> busy(p)
        CallNotificationKind.OUTSIDE_HOURS -> outsideHours(p)
        CallNotificationKind.VOICE_FAILED -> voiceFailed(p)
    }

    fun body(notes: List<String>): String = if (notes.isEmpty()) NO_NOTES else notes.joinToString("\n")

    private fun make(p: IncomingCallPayload, kind: CallNotificationKind, id: String, title: String, body: String) =
        CallNotificationSpec(
            kind = kind, id = id, title = title, body = body,
            // The call's task itself — a series opens its own editor, where the
            // call's "Call me" row lives (owner decision, audit 2026-09-22 C3).
            deepLink = p.taskId?.let(::exactTaskLink) ?: "unstuck://today",
            callId = p.callId, taskId = p.taskId, blockId = p.taskId?.let { p.blockId },
            taskName = p.taskId?.let { p.taskName ?: p.label },
        )
}

/** Persisted coordinator state for ONE call — written the moment a ring is
 *  decided so a process killed mid-ring still reports `missed` on the next
 *  launch (risk 5), and the guard for the one-outcome-per-call rule. */
@Serializable
data class CoordinatorState(
    val callId: String,
    val decision: CallDecision,
    /** The single outcome this call has reported (null = none yet). */
    val outcomeReported: Boolean = false,
    val outcome: CallOutcome? = null,
    val ringStartedMs: Long,
    /** The ring has been ANSWERED (the conversation is up or starting) — a
     *  late missed-alarm must not fire, and the end maps through [CallCoordinatorLogic.endOutcome]. */
    val answered: Boolean = false,
) {
    val isRinging: Boolean get() = decision.rings && !answered && !outcomeReported

    /** The one-outcome rule for the RING phase: the first of missed / declined /
     *  answered-then-… wins; a second report is refused (null). `answered` is
     *  NOT terminal — it marks the state answered and the end still reports
     *  once more (done / snoozed), exactly like iOS (answered, then done). */
    fun firstOutcomeWins(outcome: CallOutcome): CoordinatorState? {
        if (outcomeReported) return null
        if (outcome == CallOutcome.ANSWERED) {
            if (answered) return null
            return copy(answered = true)
        }
        return copy(outcomeReported = true, outcome = outcome)
    }

    /** The ring timed out: still ringing and 30 s have passed. */
    fun isMissed(nowMs: Long): Boolean =
        isRinging && nowMs - ringStartedMs >= CallCoordinatorLogic.MISSED_AFTER_MS

    fun toJson(): String = CallCoordinatorLogic.json.encodeToString(serializer(), this)

    companion object {
        fun fromJson(s: String?): CoordinatorState? =
            s?.takeIf { it.isNotBlank() }?.let { runCatching { CallCoordinatorLogic.json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

object CallCoordinatorLogic {
    /** Ring for this long before giving up. */
    const val MISSED_AFTER_MS: Long = 30_000
    /** `snooze_call` minutes are clamped to this (the server clamps too). */
    const val SNOOZE_MIN = 1
    const val SNOOZE_MAX = 180
    const val DEFAULT_SNOOZE_MIN = 10
    /** A ring older than this is not worth ringing (dispatch_calls' stale rule
     *  is 10 min; FCM's ttl is 120 s — belt and braces for a Doze-delayed push). */
    const val STALE_RING_AFTER_MS: Long = 10 * 60_000
    const val NO_ACTIVE_CALL = "error: no call is active"

    internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }

    /** The receipt rules, in iOS order: signed in → kill-switches → hours →
     *  focus → anchor → ring. `anchorExists == null` (no store yet) rings. */
    fun decide(p: IncomingCallPayload, env: CallEnv): CallDecision {
        if (!env.signedIn) return CallDecision.Silent("not signed in")
        if (!env.assistantEnabled) return CallDecision.Declined("assistant off")
        if (!env.callsEnabled) return CallDecision.Declined("calls off")
        if (!env.withinHours) return CallDecision.Declined("outside hours")
        if (env.focusLive) return CallDecision.Busy
        if (p.taskId != null && env.anchorExists == false) return CallDecision.Stale
        return CallDecision.Ring
    }

    /** A ring whose due time is more than [STALE_RING_AFTER_MS] ago (a Doze-
     *  delayed push): report stale rather than ring minutes late. Unknown
     *  `scheduledAtMs` ⇒ not late. */
    fun isLate(p: IncomingCallPayload, nowMs: Long): Boolean =
        p.scheduledAtMs?.let { nowMs - it > STALE_RING_AFTER_MS } ?: false

    /** What a non-ringing decision reports + shows. Ring / Silent ⇒ null. */
    fun reportFor(decision: CallDecision): OutcomeReport? = when (decision) {
        CallDecision.Ring -> null
        is CallDecision.Silent -> null
        is CallDecision.Declined -> OutcomeReport(CallOutcome.DECLINED, notify = CallNotificationKind.OUTSIDE_HOURS)
        CallDecision.Busy -> OutcomeReport(CallOutcome.BUSY, notify = CallNotificationKind.BUSY)
        CallDecision.Stale -> OutcomeReport(CallOutcome.STALE)
    }

    /** The ring timed out (30 s) or the notification could not be shown. The
     *  notice is DEFERRED: it goes into the persisted queue with the report and
     *  is posted only when the server answers without `retry` (072). */
    fun missedReport(): OutcomeReport = OutcomeReport(CallOutcome.MISSED, notify = CallNotificationKind.MISSED)

    /** Declined from the ring UI. Logged; no notification — they saw it. */
    fun declinedReport(): OutcomeReport = OutcomeReport(CallOutcome.DECLINED)

    /** The Answer tap. */
    fun answeredReport(): OutcomeReport = OutcomeReport(CallOutcome.ANSWERED)

    /** "Snooze 10" from the ring UI (no conversation): snoozed + minutes. */
    fun ringSnoozeReport(minutes: Int = DEFAULT_SNOOZE_MIN): OutcomeReport =
        OutcomeReport(CallOutcome.SNOOZED, snoozeMin = clampSnooze(minutes))

    /** How an answered call's conversation that ended ON ITS OWN ends: a
     *  snooze already in flight stays that snooze; otherwise an error — the
     *  transport's, or a provider error mid-call, which used to be dropped and
     *  left dead air until the length cap (parity with iOS build 78) — is a
     *  voice failure, and a clean close is the model's goodbye. */
    fun endedOnItsOwn(error: String?, pendingSnoozeMin: Int?): CallEndReason = when {
        pendingSnoozeMin != null -> CallEndReason.Snoozed(pendingSnoozeMin)
        error != null -> CallEndReason.Failed(error)
        else -> CallEndReason.HungUp
    }

    /** How an ANSWERED call's end reports, 1:1 with iOS `performEnd`. */
    fun endOutcome(reason: CallEndReason): OutcomeReport = when (reason) {
        CallEndReason.HungUp -> OutcomeReport(CallOutcome.DONE)
        is CallEndReason.Snoozed -> OutcomeReport(CallOutcome.SNOOZED, snoozeMin = clampSnooze(reason.minutes))
        is CallEndReason.Failed -> OutcomeReport(
            CallOutcome.DONE, outcomeNotes = listOf("voice failed: ${reason.why}"), notify = CallNotificationKind.VOICE_FAILED,
        )
    }

    fun clampSnooze(minutes: Int): Int = minutes.coerceIn(SNOOZE_MIN, SNOOZE_MAX)

    /** The `snooze_call` tool result the model reads, for an ACTIVE call.
     *  `pendingSnoozeMin` = a snooze already in flight: the second snooze
     *  repeats the first — it can't change it (one end, one outcome). */
    fun snoozeAck(minutes: Int, pendingSnoozeMin: Int? = null): String {
        val m = pendingSnoozeMin ?: clampSnooze(minutes)
        return "ok: I'll call back in $m minutes — say a quick goodbye; the call ends now"
    }
}
