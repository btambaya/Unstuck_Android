package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.INTERVIEW_QUESTIONS
import tech.csalliance.unstuck.core.logic.InterviewCopy
import tech.csalliance.unstuck.core.logic.InterviewThreadCopy
import tech.csalliance.unstuck.core.logic.InterviewVoice

// The interview's two NEW hosts (2026-09-17): the assistant thread and the
// voice opening primer. Mirrors iOS InterviewThreadTests (copy) +
// AssistantToolsTests (voice opening branches).
class InterviewVoiceTest {

    @Test fun `every question in the script has a spoken line, keyed by the script`() {
        for (q in INTERVIEW_QUESTIONS) assertTrue(q.key, InterviewVoice.spoken.containsKey(q.key))
        assertEquals(INTERVIEW_QUESTIONS.size, InterviewVoice.spoken.size)
    }

    @Test fun `the spoken list starts after the first question, which the primer says verbatim`() {
        val list = InterviewVoice.questionList()
        assertFalse(list.contains("head's clearest"))
        assertTrue(list.startsWith("what their work days look like"))
        assertTrue(list.endsWith("gently, kept honest, or barely at all"))
        assertEquals(INTERVIEW_QUESTIONS.size - 1, list.split("; ").size)
        assertEquals("", InterviewVoice.questionList(from = INTERVIEW_QUESTIONS.size))
        assertTrue(InterviewVoice.questionList(from = 0).startsWith("when their head's clearest"))
    }

    @Test fun `the thread greeting is the web greeting plus the disclosure, and the closing lines are iOS verbatim`() {
        assertEquals(InterviewCopy.greeting("Maya") + "\n\n" + InterviewCopy.DISCLOSURE, InterviewThreadCopy.greeting("Maya"))
        assertTrue(InterviewThreadCopy.greeting(null).startsWith("Hey. A few quick questions"))
        assertEquals("Last one — which moments should I run for you? All optional, all changeable in Settings.", InterviewThreadCopy.PICKER_QUESTION)
        assertEquals("That’s everything — I’ll plan around it. Change any of it in Settings → What Unstuck knows.", InterviewThreadCopy.CLOSING)
    }
}
