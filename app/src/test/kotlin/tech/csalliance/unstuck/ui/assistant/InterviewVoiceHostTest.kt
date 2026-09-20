package tech.csalliance.unstuck.ui.assistant

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.CallScript
import tech.csalliance.unstuck.core.logic.InterviewVoice
import tech.csalliance.unstuck.core.model.ProfileFact
import tech.csalliance.unstuck.core.model.ProfileFactCategory
import tech.csalliance.unstuck.core.model.ProfileFactSource

/**
 * The interview's VOICE host (2026-09-17, iOS AssistantToolsTests): the opening
 * primer runs the intro while the account's interview is pending — listing the
 * seven questions, saving via save_profile_fact, allowing skips, closing with
 * finish_interview — and greets by name otherwise; finish_interview is a
 * registry tool on the voice surface (an executor case since 2026-09-20), never
 * a call tool.
 */
class InterviewVoiceHostTest {

    private fun api(pending: Boolean, facts: List<ProfileFact> = emptyList()): AssistantApi {
        val fake = AssistantToolsTest().FakeApi(AssistantToolsTest.FakeState().apply { this.facts += facts })
        return object : AssistantApi by fake {
            override fun interviewPending(): Boolean = pending
        }
    }

    private fun fact(text: String, category: ProfileFactCategory = ProfileFactCategory.CONTEXT) =
        ProfileFact(id = "f-$text", category = category, fact = text, source = ProfileFactSource.CHAT,
            createdAt = "2026-09-17T10:00:00.000Z", updatedAt = "2026-09-17T10:00:00.000Z")

    @Test fun `pending and never met - the primer greets by name and runs the whole intro`() = runTest {
        val opening = buildVoiceOpening(api(pending = true))
        assertTrue(opening.contains("you have never met this person"))
        assertTrue(opening.contains("\"Hey Maya — before we start"))
        assertTrue(opening.contains("when's your head clearest, mornings, afternoons or evenings?"))
        assertTrue("the other six questions are listed", opening.contains(InterviewVoice.questionList()))
        assertTrue(opening.contains("call save_profile_fact before you speak again"))
        assertTrue(opening.contains("Any question can be skipped"))
        assertTrue(opening.contains("do that first, then come back to the next question"))
        assertTrue(opening.contains("call finish_interview"))
        assertTrue(opening.contains("This intro happens ONCE"))
    }

    @Test fun `pending but already knows a little - the primer says so and skips what the facts answer`() = runTest {
        val opening = buildVoiceOpening(api(pending = true, facts = listOf(fact("Works shifts"))))
        assertTrue(opening.contains("you know a little about this person already"))
        assertTrue(opening.contains("skip any question the facts already answer"))
        assertFalse(opening.contains("never met"))
        assertTrue(opening.contains("call finish_interview"))
    }

    @Test fun `not pending - a plain by-name hello, no intro, no finish_interview`() = runTest {
        val opening = buildVoiceOpening(api(pending = false))
        assertTrue(opening.contains("\"Hey Maya. What's on your plate?\""))
        assertFalse(opening.contains("finish_interview"))
        assertFalse(opening.contains("head clearest"))
        // Facts or not: the interview flag decides, not the fact count.
        assertEquals(opening, buildVoiceOpening(api(pending = false, facts = listOf(fact("Works shifts")))))
    }

    @Test fun `the no-name preference beats everything, pending or not`() = runTest {
        val noName = listOf(fact("Don't use their name", ProfileFactCategory.PREFERENCE))
        for (pending in listOf(true, false)) {
            val opening = buildVoiceOpening(api(pending = pending, facts = noName))
            assertTrue(opening.contains("NOT to be addressed by name"))
            assertFalse(opening.contains("Maya"))
            assertFalse(opening.contains("finish_interview"))
        }
    }

    @Test fun `finish_interview is a registry tool on the voice surface, never advertised to a call`() {
        // 2026-09-20 tooling rewrite: the Talk schema IS the registry's voice
        // surface (VoiceToolSchema.kt parses ToolRegistry.JSON), so finish_interview
        // is an ordinary registry name with an executor case, not an appended spec.
        assertTrue(FinishInterviewTool.NAME in ToolRegistry.NAMES)
        assertFalse(FinishInterviewTool.NAME in ToolRegistry.READ_ONLY)
        val talk = talkVoiceToolsJson()
        assertEquals(RegistryTools.forSurface("voice").size, talk.size)
        val spec = talk.first { it.jsonObject["name"]!!.jsonPrimitive.content == FinishInterviewTool.NAME }.jsonObject
        assertEquals("function", spec["type"]!!.jsonPrimitive.content)
        assertEquals(0, spec["parameters"]!!.jsonObject["required"]!!.jsonArray.size)
        assertTrue(spec["parameters"]!!.jsonObject["properties"]!!.jsonObject.isEmpty())
        assertFalse("the surfaces marker never reaches the session", spec.containsKey("_surfaces"))
        // A call from Unstuck never advertises it (CallScript.callTools is the list).
        assertTrue(callVoiceTools(CallScript.callTools()).none { it.name == FinishInterviewTool.NAME })
        assertTrue(FinishInterviewTool.OK.startsWith("ok"))
        assertTrue(FinishInterviewTool.ALREADY.startsWith("error"))
    }

    @Test fun `finish_interview runs through the executor - marks the seam done once, then says so`() = runTest {
        var pending = true
        val fake = AssistantToolsTest().FakeApi()
        val api = object : AssistantApi by fake {
            override fun interviewPending(): Boolean = pending
            override fun markInterviewDone(): Boolean { pending = false; return true }
        }
        assertEquals(FinishInterviewTool.OK, runAssistantTool(FinishInterviewTool.NAME, ToolArgs(), api, TurnScratch()))
        assertFalse(api.interviewPending())
        assertEquals(FinishInterviewTool.ALREADY, runAssistantTool(FinishInterviewTool.NAME, ToolArgs(), api, TurnScratch()))
        // No account to mark: an error, never an `ok:` over nothing.
        val signedOut = object : AssistantApi by fake {
            override fun interviewPending(): Boolean = true
            override fun markInterviewDone(): Boolean = false
        }
        assertTrue(runAssistantTool(FinishInterviewTool.NAME, ToolArgs(), signedOut, TurnScratch()).startsWith("error:"))
    }

    @Test fun `interviewPending defaults to false on the seam`() {
        val fake = AssistantToolsTest().FakeApi()
        assertFalse(fake.interviewPending())
    }
}
