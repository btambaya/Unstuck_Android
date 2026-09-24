package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.AssistantGuard.looksLikeActionClaim
import tech.csalliance.unstuck.core.logic.AssistantGuard.refersToEarlierTurn
import tech.csalliance.unstuck.core.logic.AssistantGuard.stripSelfCorrection

// Ported from lib/assistant/action-claim.test.ts, the guard half of
// lib/assistant/receipts.test.ts (verb width + turn awareness, 2026-09-05)
// and the stripSelfCorrection half of time-guard.test.ts. The claim detector
// must catch first-person completed-action claims and must NOT trip on honest
// answers about existing state.
class AssistantGuardTest {

    // ---- the period-review recap (week-review-spec §5.4): the 16 cases, each
    // with and without `recap`

    private val recapOnly = listOf(
        "You finished \"Draft chapter 3\" and the bank call.",
        "You skipped \"Stretch\" once on purpose.",
        "You've also completed \"Tax return\".",
        "You finished \"Draft chapter 3\" and skipped \"Stretch\" once.",
        "You finished \"Draft chapter 3\", then moved on to \"Tax return\".",
    )
    private val alwaysClaims = listOf(
        "Completed three tasks last week.",
        "Added \"Milk\" to Groceries.",
        "I've added \"Milk\" to your list.",
        "Moved \"Dentist\" to Friday.",
        "Done — added it.",
        "Created \"Email Sarah\" for 2pm.",
        "The task has been created.",
        "Scheduled \"Gym\" for Thursday at 6.",
        "I added \"Milk\".",
        "You finished \"Draft chapter 3\". I moved \"Tax return\" to Friday.",
    )
    private val userSubjectWrite = "You're all set — you've moved \"Dentist\" to Friday."

    @Test fun `recap neutralises the user's own past actions only on a review turn`() {
        for (c in recapOnly) {
            assertFalse("recap: $c", looksLikeActionClaim(c, recap = true))
            assertTrue("no recap: $c", looksLikeActionClaim(c))
        }
    }

    @Test fun `real claims trip with or without recap`() {
        for (c in alwaysClaims) {
            assertTrue("recap: $c", looksLikeActionClaim(c, recap = true))
            assertTrue("no recap: $c", looksLikeActionClaim(c))
        }
    }

    @Test fun `a write phrased at the user still trips without recap`() {
        assertTrue(looksLikeActionClaim(userSubjectWrite))
        // With recap it is waved through — the reason recap is scoped to review turns.
        assertFalse(looksLikeActionClaim(userSubjectWrite, recap = true))
        assertEquals(16, recapOnly.size + alwaysClaims.size + 1)
    }

    @Test fun `the neutraliser rewrites coordinated verbs to a fixpoint`() {
        assertEquals(
            "you did \"A\", did \"B\" and did \"C\".",
            tech.csalliance.unstuck.core.logic.AssistantGuard.neutraliseUserRecap("You finished \"A\", skipped \"B\" and moved \"C\"."),
        )
    }

    // ---- looksLikeActionClaim

    @Test fun `catches the observed qwen fabrications`() {
        assertTrue(looksLikeActionClaim("Done — added \"Feed the goldfish\" (5 min)."))
        assertTrue(looksLikeActionClaim("Done — added ‘Water the plants’ (10 min)."))
        assertTrue(looksLikeActionClaim("I've scheduled it for Tuesday morning."))
        assertTrue(looksLikeActionClaim("I have created “Renew passport”."))
        assertTrue(looksLikeActionClaim("All set — moved “Gym” to Thursday."))
    }

    @Test fun `lets honest schedule answers and questions through`() {
        assertFalse(looksLikeActionClaim("Your dentist appointment is scheduled for Friday at 10:00."))
        assertFalse(looksLikeActionClaim("Want me to schedule it Tuesday morning?"))
        assertFalse(looksLikeActionClaim("You have three things today — the project update at 11:00 is the anchor."))
        assertFalse(looksLikeActionClaim("Who’s Maleek — should I remember him?"))
        assertFalse(looksLikeActionClaim(""))
        assertFalse(looksLikeActionClaim(null))
    }

    // Tester-round widenings (2026-08-31)

    @Test fun `catches passive, verb-lead and article forms`() {
        assertTrue(looksLikeActionClaim("The task has been created."))
        assertTrue(looksLikeActionClaim("Your tasks have been moved to tomorrow."))
        assertTrue(looksLikeActionClaim("Created the task for you."))
        assertTrue(looksLikeActionClaim("I just moved the task to Friday."))
    }

    @Test fun `catches empty memory promises`() {
        assertTrue(looksLikeActionClaim("I'll take a note of it."))
        assertTrue(looksLikeActionClaim("I will make a note of that."))
        assertTrue(looksLikeActionClaim("Noted — I won't mention your name again."))
        assertTrue(looksLikeActionClaim("I'll remember that."))
    }

    @Test fun `still allows honest state answers and questions`() {
        assertFalse(looksLikeActionClaim("Your dentist task is scheduled for Friday at 10:00."))
        assertFalse(looksLikeActionClaim("Want me to move it to Tuesday?"))
        assertFalse(looksLikeActionClaim("That task was created last week by you."))
    }

    @Test fun `catches settings-style lies`() {
        assertTrue(looksLikeActionClaim("Set to 240 minutes per day — done."))
        assertTrue(looksLikeActionClaim("Reminders on, done."))
        assertTrue(looksLikeActionClaim("Set to calm ✓ enjoy the quiet"))
        assertFalse(looksLikeActionClaim("When you are done, tell me how it went."))
    }

    @Test fun `catches compliance promises that need persistence`() {
        assertTrue(looksLikeActionClaim("Sure — I'll stop using your name from now on."))
        assertTrue(looksLikeActionClaim("Okay, I will not mention it again."))
    }

    // Verb width (harness audit, 2026-09-05)

    @Test fun `catches varied openers`() {
        listOf(
            "Booked. Thursday 2pm.",
            "Skipped gym today.",
            "Reopened “Dentist”.",
            "Ticked “eggs” off Groceries.",
            "Renamed “Home” to “House”.",
            "Unscheduled “Gym”.",
            "Carried three to tomorrow.",
            "Paused your focus session.",
            "Extended the session by 10 minutes.",
            "Cancelled the call.",
            "I've forgotten that about Sam.",
            "I've remembered that you like mornings.",
            "Promoted “Buy paint” to a task.",
            "I've started a focus session on “Report”.",
            "I've set your reminders to 10 minutes.",
            "I've turned the evening sweep off.",
            "The task has been reopened.",
            "Your gym slot has been skipped.",
            "I just blocked the afternoon for it.",
        ).forEach { assertTrue(it, looksLikeActionClaim(it)) }
    }

    // 2026-09-20 tooling rewrite: the new write tools' past tenses.
    @Test fun `catches the restored, pinned and recoloured claims`() {
        listOf(
            "Restored “Call the plumber” to your inbox.",
            "I've restored that capture.",
            "Pinned “Milk” to the top of Groceries.",
            "I've pinned it for you.",
            "Recoloured “Groceries” to green.",
            "I've recolored the list.",
            "The list has been recoloured.",
            "I just pinned the first item.",
        ).forEach { assertTrue(it, looksLikeActionClaim(it)) }
        // Turn awareness still applies to the new verbs.
        assertFalse(looksLikeActionClaim("I restored it earlier — it is back in the inbox."))
        assertFalse(looksLikeActionClaim("As I said, I pinned it for you."))
    }

    // The four verbs iOS + web already had (parity with iOS build 71, 56f1f60).
    @Test fun `catches the unpinned, finished, left and switched claims`() {
        listOf(
            "I've switched it to dark mode.",
            "I've unpinned it.",
            "I've finished the focus session.",
            "I've left the list.",
            "Switched your theme to dark.",
            "Unpinned “Milk” in Groceries.",
            "The session has been finished.",
        ).forEach { assertTrue(it, looksLikeActionClaim(it)) }
    }

    @Test fun `does not trip on honest sentence-leads that share a verb`() {
        assertFalse(looksLikeActionClaim("Set aside twenty minutes for it?"))
        assertFalse(looksLikeActionClaim("Shared tasks show up under People in Settings."))
        assertFalse(looksLikeActionClaim("Started already? Tell me how far you got."))
        assertFalse(looksLikeActionClaim("Left to do: two things."))
        assertFalse(looksLikeActionClaim("Finished with that one?"))
    }

    // Turn awareness

    @Test fun `passes truthful references to a previous turn`() {
        assertFalse(looksLikeActionClaim("Yes — I added “Buy milk” earlier, it is on your list."))
        assertFalse(looksLikeActionClaim("I moved it earlier — it is on Friday now."))
        assertFalse(looksLikeActionClaim("As I said, I scheduled it for Friday at 9."))
        assertFalse(looksLikeActionClaim("I've already added that one."))
        assertFalse(looksLikeActionClaim("I created it a moment ago when you asked."))
        assertTrue(refersToEarlierTurn("Like I mentioned, the task has been created."))
    }

    @Test fun `still bounces a this-turn claim sitting next to a recap`() {
        assertTrue(looksLikeActionClaim("I moved the dentist earlier. Done — added “Buy milk” too."))
        assertTrue(looksLikeActionClaim("Sure. I've added “Buy milk” to your tasks."))
    }

    @Test fun `this-turn phrasings that mention time are still claims`() {
        assertTrue(looksLikeActionClaim("I've moved it to 9, before your meeting."))
        assertTrue(looksLikeActionClaim("I've scheduled it for this morning."))
    }

    // ---- stripSelfCorrection (tester: "it trips itself", 2026-09-02)

    @Test fun `drops the apology aimed at the hidden integrity check`() {
        assertEquals(
            "Captured \"ask Sam\" on Project check-in.",
            stripSelfCorrection("Oh sorry — I said I added a capture but I didn't. Let me add it now. Captured \"ask Sam\" on Project check-in."),
        )
        assertEquals(
            "Done — added the capture.",
            stripSelfCorrection("Sorry, that wasn't actually done. Actually, I hadn't added it yet! Done — added the capture."),
        )
        assertEquals(
            "Added \"Walk the dog\" for tomorrow at 9.",
            stripSelfCorrection("My mistake. Added \"Walk the dog\" for tomorrow at 9."),
        )
    }

    @Test fun `leaves ordinary answers alone`() {
        assertEquals(
            "Done — added the capture to Project check-in.",
            stripSelfCorrection("Done — added the capture to Project check-in."),
        )
        assertTrue(stripSelfCorrection("Sorry to hear the day was rough. Want me to move the report to tomorrow?").startsWith("Sorry to hear"))
    }

    @Test fun `strips up to four leading apology sentences`() {
        // The web's loop runs four passes; every leading apology form goes.
        assertEquals(
            "Saved it now.",
            stripSelfCorrection("Sorry. My mistake. Correction: it wasn't saved. Apologies again. Saved it now."),
        )
    }

    @Test fun `keeps a truthful leading Actually or Let me when no apology precedes it`() {
        val t = "Actually, I scheduled it for Friday at 9 since mornings are your best. Want a reminder?"
        assertEquals(t, stripSelfCorrection(t))
        assertEquals("Let me add that now. Added “Gym” for tomorrow.", stripSelfCorrection("Let me add that now. Added “Gym” for tomorrow."))
    }

    @Test fun `strips a trailing apology the user has no context for`() {
        assertEquals("Added “Gym” for tomorrow at 9.", stripSelfCorrection("Added “Gym” for tomorrow at 9. Sorry for the confusion earlier."))
        assertEquals("Added “Gym” for tomorrow at 9.", stripSelfCorrection("Added “Gym” for tomorrow at 9. My mistake earlier!"))
    }

    @Test fun `never blanks a reply that is only an apology`() {
        assertEquals("Sorry about that.", stripSelfCorrection("Sorry about that."))
    }

    @Test fun promisesOfAnActionAreClaims() {
        // Voice, 2026-09-20: "call me in one minute" → "I'll set a reminder for
        // one minute from now" and no request_call. A promise IS a claim.
        assertTrue(looksLikeActionClaim("Sure, just testing — I'll set a reminder for one minute from now."))
        assertTrue(looksLikeActionClaim("I'll call you at three about James."))
        assertTrue(looksLikeActionClaim("I'm going to add that to your list."))
        assertTrue(looksLikeActionClaim("I will remind you before the dentist."))
        assertFalse(looksLikeActionClaim("Do you want me to call you before the dentist?"))
        assertFalse(looksLikeActionClaim("I can't book that outside your call hours — want 9am tomorrow?"))
    }
}
