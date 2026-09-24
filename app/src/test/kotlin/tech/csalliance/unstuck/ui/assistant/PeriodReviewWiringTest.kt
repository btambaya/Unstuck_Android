package tech.csalliance.unstuck.ui.assistant

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * get_period_review's app-side wiring (week-review-spec §5.2, §5.4, §6 (f)):
 * toolCaps rides on the TEXT request only, the voice prompt carries the
 * HOW DID IT GO rule and never toolCaps, and the voice guard's recap flag is
 * set only by an ok: review and cleared only when the app answers the user's
 * next turn (never on a speech start — echo fires those).
 */
class PeriodReviewWiringTest {
    private fun api() = AssistantToolsTest().FakeApi()

    @Test fun `the text request reports toolCaps, the shared context does not`() = runTest {
        val text = buildTextRequestContext(api())
        assertEquals(listOf("period_review"), text["toolCaps"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(ToolRegistry.CAPS, listOf("period_review"))
        // Everything else is the same snapshot.
        val shared = buildAssistantContext(api())
        assertNull(shared["toolCaps"])
        assertEquals(shared.keys, text.keys - "toolCaps")
    }

    @Test fun `the voice instructions carry the rule and never toolCaps`() = runTest {
        val v = buildVoiceInstructions(api())
        assertFalse(v.contains("toolCaps"))
        assertFalse(v.contains("period_review\""))
        assertTrue(v.contains("call get_period_review first"))
        assertTrue(v.contains("Follow any note in the result"))
        assertTrue(v.contains("never that the week was empty"))
        // The read-before-answer line sends only PLANS to get_schedule (§5.1c).
        assertTrue(v.contains("Before answering what is in a list, the inbox or what is planned for the week, or acting on an item, call get_lists, get_captures, get_schedule, get_tasks or find_tasks."))
        // Verbatim, and right after the read-before-answer sentence.
        val rule = "HOW DID IT GO: \"how has my week been\", \"how was last week\", \"what did I get done yesterday\", \"how was the week of the seventh\", \"how's this month going\" → call get_period_review first (a preset; week_of or month_of with any date in it; dates with from and to for anything else; a day or date without a year is the latest one already started; on a Monday or Tuesday \"my week\" means last_week). Never judge the past from get_schedule or the week below — an empty calendar never means nothing got done. Follow any note in the result. Answer in up to four short sentences, the one exception to two: what they got done (two or three things), focus time, what slipped, then the comparison — about them (\"you got the chapter draft done\"), never a bare verb first. Numbers and names only from the result; if nothing was logged, say so — never that the week was empty."
        assertTrue(v.contains("find_tasks. $rule "))
    }

    @Test fun `voice recap is set by an ok review only and ends when the user speaks`() {
        val g = VoiceIntegrityGuard()
        val review = "You finished \"Draft chapter 3\" and skipped \"Stretch\" once."
        // No review: the recap reads as a claim.
        g.responseCreated(); g.transcriptDelta(review)
        assertTrue(g.shouldCorrect())
        g.responseCreated(); assertFalse(g.shouldCorrect())   // the correction's follow-up
        // A failed review doesn't set it.
        g.userTookTurn()
        g.toolDispatched("get_period_review"); g.toolFinished("get_period_review", "error: unknown period \"x\"")
        assertFalse(g.recap)
        // An ok review does — and the SPOKEN review (a later response) passes.
        g.toolFinished("get_period_review", "ok: review of last week (Mon 14 Sep – Sun 20 Sep).")
        assertTrue(g.recap)
        g.responseCreated(); g.transcriptDelta(review)
        assertFalse(g.shouldCorrect())
        // Only the app answering the user's next turn ends it — the guard has no
        // speech-start hook at all (loudspeaker echo of the review fires those).
        assertTrue(g.recap)
        g.userTookTurn(); assertFalse(g.recap)
        g.responseCreated(); g.transcriptDelta(review)
        assertTrue(g.shouldCorrect())
        // A barge-in the controller confirmed (it cancels the reply) is the user's
        // new turn: a fabricated write phrased at them must trip again.
        val write = "You're all set — you've moved \"Dentist\" to Friday."
        g.responseCreated(); assertFalse(g.shouldCorrect())   // the follow-up of the correction above
        g.toolFinished("get_period_review", "ok: review of last week (Mon 14 Sep – Sun 20 Sep).")
        g.responseCreated(); g.transcriptDelta(write)
        assertFalse(g.shouldCorrect())   // still the review's turn: the recap waves it through
        g.bargeIn(); assertFalse(g.recap)
        g.responseCreated(); g.transcriptDelta(write)
        assertTrue(g.shouldCorrect())
        g.responseCreated(); assertFalse(g.shouldCorrect())   // the correction's follow-up
        // So is a hold-to-talk release (no server VAD in hold mode).
        g.toolFinished("get_period_review", "ok: review of last week (Mon 14 Sep – Sun 20 Sep).")
        assertTrue(g.recap)
        g.userTookTurn(); assertFalse(g.recap)
        // A real claim is never waved through, recap or not.
        val g2 = VoiceIntegrityGuard()
        g2.toolFinished("get_period_review", "ok: review of yesterday (Wed 23 Sep).")
        g2.responseCreated(); g2.transcriptDelta("I moved \"Tax return\" to Friday.")
        assertTrue(g2.shouldCorrect())
    }
}
