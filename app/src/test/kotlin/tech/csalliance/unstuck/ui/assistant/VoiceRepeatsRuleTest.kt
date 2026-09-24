package tech.csalliance.unstuck.ui.assistant

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.AssistantGuard

/**
 * REPEATS (Zubair's iOS morning call, 2026-09-24): asked for "every two weeks
 * on Thursdays", the model set weekly first and only then said it couldn't do
 * fortnightly. This build executes set_task_recurrence's intervalWeeks (every-n-
 * weeks spec §7.1), so the spoken rule names every 2–8 weeks — verbatim the
 * shared vectors' prompts.voiceRepeatsRule (web REPEATS_RULE and iOS carry the
 * same text), in the same place: with the rules of conduct, after CALLS and
 * before HOW YOU SPEAK. An unsupported repeat is still said first, as a question.
 */
class VoiceRepeatsRuleTest {
    private fun api() = AssistantToolsTest().FakeApi()

    /** prompts.voiceRepeatsRule, read from core's generated vectors (no second copy). */
    private val rule: String = run {
        val rel = "core/src/test/kotlin/tech/csalliance/unstuck/core/RecurrenceVectors.generated.kt"
        val src = listOf(java.io.File("../$rel"), java.io.File(rel)).first { it.exists() }.readText()
        val open = "const val JSON: String = \"\"\""
        val body = src.substring(src.indexOf(open) + open.length, src.lastIndexOf("\"\"\"")).replace("\${\"$\"}", "$")
        kotlinx.serialization.json.Json.parseToJsonElement(body).let {
            (it as kotlinx.serialization.json.JsonObject)["prompts"]!!.let { p -> (p as kotlinx.serialization.json.JsonObject)["voiceRepeatsRule"]!! }
        }.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
    }

    @Test fun `the voice instructions carry the shared REPEATS rule verbatim, with the rules of conduct`() = runTest {
        assertTrue(rule, rule.startsWith("REPEATS: a task can repeat daily, weekly or every 2–8 weeks on chosen days"))
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
        assertFalse(AssistantGuard.looksLikeActionClaim("I can't do every other month — want it monthly instead, or just this one?"))
        assertTrue(AssistantGuard.looksLikeActionClaim("Every other month isn't an option. I'll set it monthly if that works for you?"))
    }

    /** The capable description (this build reports recurrence_interval): every
     *  2–8 weeks named within voice compaction's 90-character first sentence, and
     *  the say-so-first rule for everything else. */
    @Test fun `the registry's set_task_recurrence description says the same, for text and voice`() {
        val desc = ToolRegistry.JSON
        assertTrue(desc.contains("Repeat a task daily, weekly or every 2–8 weeks on given days, or monthly; kind=none stops it."))
        assertTrue(desc.contains("SAY SO FIRST and offer the closest as a question"))
        assertTrue(desc.contains("\"description\":\"Every N weeks, 1–8 (2 = every other week / fortnightly)."))
        // create_task with a date and time is already on the calendar (the
        // redundant schedule_task of the same call).
        assertTrue(desc.contains("never follow it with schedule_task for the same day and time"))
    }
}
