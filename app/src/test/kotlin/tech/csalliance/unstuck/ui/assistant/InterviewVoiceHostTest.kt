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
 * talk-level tool (58 with the 57 contract tools), never a contract or call tool.
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

    @Test fun `finish_interview is a talk-level tool - in the Talk schema, never a contract or call tool`() {
        assertFalse(FinishInterviewTool.NAME in ASSISTANT_TOOL_NAMES)
        assertEquals(57, voiceToolsJson().size)
        assertTrue(voiceToolsJson().none { it.jsonObject["name"]!!.jsonPrimitive.content == FinishInterviewTool.NAME })
        val talk = talkVoiceToolsJson()
        assertEquals(58, talk.size)
        val spec = talk.last().jsonObject
        assertEquals(FinishInterviewTool.NAME, spec["name"]!!.jsonPrimitive.content)
        assertEquals("function", spec["type"]!!.jsonPrimitive.content)
        assertEquals(FinishInterviewTool.DESCRIPTION, spec["description"]!!.jsonPrimitive.content)
        assertEquals(0, spec["parameters"]!!.jsonObject["required"]!!.jsonArray.size)
        assertTrue(spec["parameters"]!!.jsonObject["properties"]!!.jsonObject.isEmpty())
        assertTrue("every contract tool is still there", talk.map { it.jsonObject["name"]!!.jsonPrimitive.content }.containsAll(ASSISTANT_TOOL_NAMES))
        // A call from Unstuck never advertises it (CallScript.callTools is the list).
        assertTrue(callVoiceToolSpecs(CallScript.callTools() + FinishInterviewTool.NAME).none { it.name == FinishInterviewTool.NAME })
        assertFalse(FINISH_INTERVIEW_SPEC.readOnly)
        assertTrue(FinishInterviewTool.OK.startsWith("ok"))
    }

    @Test fun `interviewPending defaults to false on the seam`() {
        val fake = AssistantToolsTest().FakeApi()
        assertFalse(fake.interviewPending())
    }
}
