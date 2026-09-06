package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.CallScript
import tech.csalliance.unstuck.core.logic.CallScript.LabelShape
import tech.csalliance.unstuck.core.logic.IncomingCallPayload
import java.time.Instant
import java.time.ZoneId

// CallScript vectors — the verbatim opening, the label-shape rule, the offer
// sentence, the start line, the call instructions and the live tool list —
// ported from iOS Tests/UnstuckAppTests/CallScriptTests.swift.
class CallScriptTest {
    private val now = 1_800_000_000_000L
    private val utc: ZoneId = ZoneId.of("UTC")

    private fun payload(
        name: String? = "Ahmad", label: String = "speak to James",
        notes: List<String> = listOf("Ask about the invoice", "Confirm Friday", "Send the deck"),
        taskId: String? = "t1", startTime: String? = null, firstAction: String? = null, captures: List<String> = emptyList(),
    ) = IncomingCallPayload(
        callId = "0f1e2d3c-4b5a-4697-8877-665544332211", label = label, notes = notes,
        taskId = taskId, blockId = taskId?.let { "b1" }, taskName = "Speak to James",
        startTime = startTime, firstAction = firstAction, captures = captures, name = name,
    )

    private fun opening(p: IncomingCallPayload) = CallScript.opening(p, nowMs = now, zone = utc)

    @Test fun `opening full vector`() {
        assertEquals(
            "Hi Ahmad — you asked me to ring so you'd speak to James. You wanted to remember: Ask about the invoice; Confirm Friday; Send the deck. Your first step was: open the thread. Start the timer, or ring you back in ten?",
            opening(payload(firstAction = "open the thread")),
        )
    }

    @Test fun `opening without name or notes or task offers only what applies`() {
        assertEquals(
            "Hi — you asked me to ring so you'd speak to James. Anything you want me to note?",
            opening(payload(name = null, notes = emptyList(), taskId = null)),
        )
    }

    @Test fun `opening renders the label by shape`() {
        assertTrue(opening(payload(label = "James")).startsWith("Hi Ahmad — you asked me to ring about James."))
        assertTrue(opening(payload(label = "the dentist")).startsWith("Hi Ahmad — you asked me to ring about the dentist."))
        assertTrue(opening(payload(label = "Dentist appointment")).startsWith("Hi Ahmad — you asked me to ring about Dentist appointment."))
        assertTrue(opening(payload(label = "speak to James")).startsWith("Hi Ahmad — you asked me to ring so you'd speak to James."))
        assertTrue(opening(payload(label = "to ring the bank")).startsWith("Hi Ahmad — you asked me to ring so you'd ring the bank."))
        assertFalse(opening(payload(label = "speak to James")).contains("ring about speak"))
    }

    @Test fun `labelShape and reasonPhrase`() {
        assertEquals(LabelShape.VERB_PHRASE, CallScript.labelShape("speak to James"))
        assertEquals(LabelShape.VERB_PHRASE, CallScript.labelShape("Chase the invoice"))
        assertEquals(LabelShape.VERB_PHRASE, CallScript.labelShape("to call mum"))
        assertEquals(LabelShape.VERB_PHRASE, CallScript.labelShape("Call, mum"))
        assertEquals(LabelShape.NOUN_PHRASE, CallScript.labelShape("James"))
        assertEquals(LabelShape.NOUN_PHRASE, CallScript.labelShape("the dentist"))
        assertEquals(LabelShape.NOUN_PHRASE, CallScript.labelShape("Q3 planning"))
        assertEquals(LabelShape.NOUN_PHRASE, CallScript.labelShape(""))
        assertEquals("so you'd speak to James", CallScript.reasonPhrase("  speak to James "))
        assertEquals("so you'd ring the bank", CallScript.reasonPhrase("To ring the bank"))
        assertEquals("about James", CallScript.reasonPhrase("James"))
    }

    @Test fun `leadingVerbs is a deduplicated list that drives the shape`() {
        assertEquals(CallScript.leadingVerbs.size, CallScript.leadingVerbs.toSet().size)
        assertTrue("speak" in CallScript.leadingVerbs)
        assertTrue("ring" in CallScript.leadingVerbs)
        assertFalse("dentist" in CallScript.leadingVerbs)
    }

    @Test fun `offerSentence is one question chosen by what applies`() {
        assertEquals("Start the timer, or ring you back in ten?", CallScript.offerSentence(hasTask = true, hasNotes = true))
        assertEquals("Start the timer, or ring you back in ten?", CallScript.offerSentence(hasTask = true, hasNotes = false))
        assertEquals("Anything to add?", CallScript.offerSentence(hasTask = false, hasNotes = true))
        assertEquals("Anything you want me to note?", CallScript.offerSentence(hasTask = false, hasNotes = false))
        val notesOnly = opening(payload(notes = listOf("A"), taskId = null))
        assertFalse(notesOnly.contains("timer"))
        assertTrue(notesOnly.contains("You wanted to remember: A."))
        assertTrue(notesOnly.endsWith("Anything to add?"))
        val taskOnly = opening(payload(notes = emptyList(), taskId = "t1"))
        assertFalse(taskOnly.contains("tick any off"))
        assertFalse(taskOnly.contains("notes"))
        assertTrue(taskOnly.contains("Start the timer"))
        for ((t, n) in listOf(true to true, true to false, false to true, false to false)) {
            assertEquals(1, CallScript.offerSentence(t, n).count { it == '?' })
        }
    }

    @Test fun `opening uses the first name only`() {
        assertTrue(opening(payload(name = "Ahmad Tambaya")).startsWith("Hi Ahmad — "))
        assertTrue(opening(payload(name = "   ")).startsWith("Hi — "))
        assertTrue(CallScript.opening(payload(), preferredName = "Zed", nowMs = now).startsWith("Hi Zed — "))
    }

    @Test fun `opening says when it starts`() {
        fun iso(deltaSec: Long) = Instant.ofEpochMilli(now + deltaSec * 1000).toString()
        val s = payload(startTime = iso(12 * 60))
        assertTrue(opening(s), opening(s).contains("It starts in 12 minutes."))
        assertEquals("It starts in a minute.", CallScript.startLine(payload(startTime = iso(60)), now))
        assertEquals("It starts now.", CallScript.startLine(payload(startTime = iso(0)), now))
        assertEquals("It started a minute ago.", CallScript.startLine(payload(startTime = iso(-60)), now))
        assertEquals("It started 5 minutes ago.", CallScript.startLine(payload(startTime = iso(-5 * 60)), now))
        assertNull(CallScript.startLine(payload(), now))
        // A bare "HH:MM" anchors to the day the push arrived.
        val nowUtc = Instant.ofEpochMilli(now).atZone(utc)
        val hm = "%02d:%02d".format(nowUtc.hour, (nowUtc.minute + 3) % 60)
        if (nowUtc.minute + 3 < 60) {
            assertEquals("It starts in 3 minutes.", CallScript.startLine(payload(startTime = hm), now, now, utc))
        }
    }

    @Test fun `notesSentence trims, drops blanks and is null when empty`() {
        assertEquals("You wanted to remember: A; B.", CallScript.notesSentence(listOf(" A ", "", "B")))
        assertNull(CallScript.notesSentence(emptyList()))
        assertNull(CallScript.notesSentence(listOf("  ", "")))
    }

    @Test fun `instructions carry the opening verbatim, the tools and the guards`() {
        val s = payload(captures = listOf("ping Sam"), startTime = "2026-09-02T14:45:00Z")
        val i = CallScript.instructions(s, nowMs = now, zone = utc)
        assertTrue("opening quoted verbatim", i.contains("\"" + opening(s) + "\""))
        assertTrue(i.contains("verbatim"))
        assertTrue(i.contains("Never claim an action happened without its tool result"))
        assertTrue(i.contains("English"))
        assertTrue(i.contains("times the way people say them"))
        assertTrue(i.contains("say bye"))
        for (t in listOf("complete_task", "add_capture", "schedule_task", "start_focus", "update_call", "snooze_call")) {
            assertTrue(t, i.contains(t))
        }
        assertTrue(i.contains("- task: Speak to James [id=t1]"))
        assertTrue(i.contains("- block: b1"))
        assertTrue(i.contains("- starts at: 2026-09-02 14:45"))
        assertTrue(i.contains("- notes (verbatim): \"Ask about the invoice\", \"Confirm Friday\", \"Send the deck\""))
        assertTrue(i.contains("- recent captures on it: \"ping Sam\""))
        assertTrue(i.contains("call id 0f1e2d3c-4b5a-4697-8877-665544332211"))
        assertTrue(i.startsWith("THIS IS A PHONE CALL"))
        val bare = CallScript.instructions(payload(notes = emptyList(), taskId = null), nowMs = now)
        assertTrue(bare.contains("- notes (verbatim): (none)"))
        assertFalse(bare.contains("- task:"))
        assertFalse(bare.contains("recent captures"))
    }

    @Test fun `callTools list`() {
        assertEquals(
            listOf("complete_task", "add_capture", "schedule_task", "start_focus", "update_call", "snooze_call"),
            CallScript.callTools(),
        )
        assertEquals(CallScript.CALL_TOOLS, CallScript.callTools())
    }
}
