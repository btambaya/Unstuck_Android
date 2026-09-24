package tech.csalliance.unstuck.ui.assistant

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.AssistantGuard

/**
 * REPEATS (Zubair's iOS morning call, 2026-09-24): asked for "every two weeks
 * on Thursdays", the model set weekly first and only then said it couldn't do
 * fortnightly. The spoken prompt says an unsupported repeat before setting
 * anything — verbatim web `REPEATS_RULE` (lib/assistant/tools.ts, pinned there
 * by voice-register.test.ts), in the same place: with the rules of conduct,
 * after CALLS and before HOW YOU SPEAK. The registry's set_task_recurrence
 * description carries the same rule for text and voice.
 */
class VoiceRepeatsRuleTest {
    private fun api() = AssistantToolsTest().FakeApi()

    private val rule =
        "REPEATS: a task can repeat daily, weekly on chosen days, or monthly, optionally until a last date — nothing else. " +
            "If they ask for a repeat those can't express (every two weeks, every other month, the third Tuesday), say so FIRST and offer the closest options " +
            "as a question (\"Every other week isn't an option — weekly on Thursdays, or just this one?\"), never as \"I'll set it weekly…\"; " +
            "never set a different pattern before they agree to it. "

    @Test fun `the voice instructions carry the web REPEATS rule verbatim, with the rules of conduct`() = runTest {
        val v = buildVoiceInstructions(api())
        assertTrue(v.contains(rule))
        val at = v.indexOf(rule)
        assertTrue("after the CALLS rule", v.indexOf("CALLS: Unstuck can phone them.") in 0 until at)
        assertTrue("before HOW YOU SPEAK", v.indexOf("HOW YOU SPEAK") > at)
    }

    /** The guard bounces a spoken promise with no tool call, and its forced
     *  corrective makes the model run the tool: an offer worded "I'll set it
     *  weekly…" would come back as the very swap the rule forbids. The rule's
     *  example is a question the guard lets through (web d7441b7). */
    @Test fun `the offer the rule asks for is a question the integrity guard lets through`() {
        val example = Regex("\\(\"([^\"]+)\"\\)").find(rule)!!.groupValues[1]
        assertTrue(example, example.endsWith("?"))
        assertFalse(AssistantGuard.looksLikeActionClaim(example))
        assertFalse(AssistantGuard.looksLikeActionClaim("I can't do every two weeks — want it weekly on Thursdays instead, or just today?"))
        assertTrue(AssistantGuard.looksLikeActionClaim("Fortnightly isn't an option. I'll set it weekly on Thursdays if that works for you?"))
    }

    @Test fun `the registry's set_task_recurrence description says the same, for text and voice`() {
        val desc = ToolRegistry.JSON
        assertTrue(desc.contains("Those are the ONLY repeats there are"))
        assertTrue(desc.contains("SAY SO FIRST and offer the closest ones"))
        // create_task with a date and time is already on the calendar (the
        // redundant schedule_task of the same call).
        assertTrue(desc.contains("never follow it with schedule_task for the same day and time"))
    }
}
