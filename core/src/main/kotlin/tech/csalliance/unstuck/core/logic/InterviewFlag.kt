package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.time.Time

// The cross-device "already onboarded" flag for the get-to-know-you interview
// and the pure gates around it. Port of lib/assistant/interview-flag.ts (web)
// + the static rules of iOS InterviewMachine / GatewayInterviewFlag. (The
// one-shot auto-open gate went with the Today card, 2026-09-17: the interview
// is asked inside the assistant thread / by the voice primer instead.) "Done" used to live only in one device's local
// storage, so a tester who finished on the web was asked again on the phone —
// and any device that hydrated too few synced facts re-asked (prod tester,
// 2026-09-05). The server column `user_preferences.assistant_interview_done_at`
// (migration 052) is the account-wide truth: written when the interview
// finishes on ANY platform, read on every hydrate to pin the local flag BEFORE
// the assistant may ask.

object InterviewFlag {
    /** The server column (migration 052). */
    const val DONE_COLUMN = "assistant_interview_done_at"

    /** Pure decision: does a `user_preferences` value say the interview is
     *  done? A non-empty, parseable timestamp = done; null / blank / garbage =
     *  not. PostgREST emits `+00:00` offsets and microsecond fractions — both
     *  parse. */
    fun interviewDoneFromServer(value: String?): Boolean {
        val v = value?.trim() ?: return false
        if (v.isEmpty()) return false
        return Time.parseMillis(v) != null
    }

    /** Pure: a persisted resume step → a valid index (0…max), else 0 (restart). */
    fun parseInterviewStep(raw: String?, max: Int): Int {
        if (raw.isNullOrEmpty()) return 0
        val n = raw.trim().toIntOrNull() ?: return 0
        return if (n in 0..max) n else 0
    }

    /** Onboarding by CONVERSATION counts: once ≥1 real fact exists (saved by
     *  voice/chat/settings/another device) the interview stands down — asking
     *  again reads as "the AI isn't saving anything". NEVER while the panel is
     *  open: its own answers grow the count and auto-closing mid-interview
     *  looks like a crash. And NEVER while a step PAST the first is parked:
     *  that fact may be its OWN answer from a hidden-mid-way run, and
     *  auto-completing there skipped the rest + the rituals picker. A parked
     *  step 0 (auto-opened, never answered) can't hold its own answers, so
     *  facts from elsewhere still stand it down. (interview.tsx
     *  `shouldAutoCompleteInterview`, iOS `InterviewMachine.shouldAutoComplete`.) */
    fun shouldAutoComplete(factCount: Int, isOpen: Boolean, done: Boolean, parkedStep: Int? = null): Boolean =
        !done && !isOpen && (parkedStep ?: 0) == 0 && factCount >= 1

    /** Whether to open the interview by itself: nothing learned anywhere, not
     *  done, and not parked ("Skip for now" persists a resume step — popping
     *  back open on the next launch would be the nag it exists to avoid; the
     *  pill is the way back in). Every question is skippable. */
    fun shouldAutoOpen(factCount: Int, done: Boolean, hasResumeStep: Boolean = false): Boolean =
        !done && !hasResumeStep && factCount == 0

    /** How a re-read of the persisted flag settles the card's state (iOS
     *  `GatewayInterviewFlag.apply`): "done" only ever moves towards done here
     *  (a local un-done is the scrub's job), and a done account never keeps
     *  the panel open — someone who finished elsewhere is not asked again. */
    fun apply(serverDone: Boolean, done: Boolean, open: Boolean): State =
        if (serverDone) State(done = true, open = false) else State(done, open)

    data class State(val done: Boolean, val open: Boolean)
}
