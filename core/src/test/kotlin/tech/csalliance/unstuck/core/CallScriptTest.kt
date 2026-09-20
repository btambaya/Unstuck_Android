package tech.csalliance.unstuck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.CallScript
import tech.csalliance.unstuck.core.logic.CallScript.LabelShape
import tech.csalliance.unstuck.core.logic.IncomingCallPayload
import tech.csalliance.unstuck.core.model.CallKind
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
        callKind: String? = null, endTime: String? = null, taskName: String? = "Speak to James",
    ) = IncomingCallPayload(
        callId = "0f1e2d3c-4b5a-4697-8877-665544332211", label = label, notes = notes,
        taskId = taskId, blockId = taskId?.let { "b1" }, taskName = taskName,
        startTime = startTime, firstAction = firstAction, captures = captures, name = name,
        callKind = callKind, endTime = endTime,
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
        assertTrue("the call is the full assistant", i.contains("You have every tool you have in Talk"))
        assertTrue(i.contains("- kind: requested"))
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

    @Test fun `callToolNames is voice then call, de-duplicated, with the extras guaranteed`() {
        // iOS CallScriptTests: voice names first in order, every call name present, no duplicates.
        assertEquals(
            listOf("a", "b", "snooze_call", "update_call"),
            CallScript.callToolNames(voice = listOf("a", "b", "", "a"), call = listOf("snooze_call", "b")),
        )
        assertEquals(listOf("update_call", "snooze_call"), CallScript.callToolNames(emptyList(), emptyList()))
        // A registry that already carries update_call on the voice surface keeps its position.
        assertEquals(
            listOf("get_tasks", "update_call", "complete_task", "snooze_call"),
            CallScript.callToolNames(voice = listOf("get_tasks", "update_call", "complete_task"), call = listOf("snooze_call")),
        )
        assertEquals(listOf("update_call", "snooze_call"), CallScript.CALL_EXTRAS)
    }

    // ── per kind (calls build-out 2026-09-20; iOS CallScriptTests testOpeningPerKind) ──

    @Test fun `test call opening and instructions`() {
        val p = payload(callKind = "test", label = "Test call", notes = listOf("This is what a call from Unstuck sounds like"), taskId = null)
        assertEquals(CallKind.TEST, p.resolvedKind)
        assertEquals("Hi Ahmad — this is your test call from Unstuck. Everything works. Want to try something — ask me what's on today?", opening(p))
        assertEquals("Hi — this is your test call from Unstuck. Everything works. Want to try something — ask me what's on today?", opening(p.copy(name = null)))
        val i = CallScript.instructions(p, nowMs = now, zone = utc)
        assertTrue(i.startsWith("THIS IS A TEST CALL the user booked from Settings"))
        assertTrue(i.contains("then wait for their answer:\n\"" + opening(p) + "\""))
        assertTrue(i.contains("this call proves the ring works"))
        assertTrue(i.contains("- kind: test"))
        assertFalse("a test call does not read notes word for word", i.contains("read the notes word for word"))
    }

    @Test fun `morning call opening and instructions`() {
        val p = payload(callKind = "morning", label = "Morning plan", notes = emptyList(), taskId = null)
        assertEquals("Morning, Ahmad. Want to walk through today?", opening(p))
        assertEquals("Morning. Want to walk through today?", opening(p.copy(name = "  ")))
        val i = CallScript.instructions(p, nowMs = now, zone = utc)
        assertTrue(i.startsWith("THIS IS THE MORNING PLANNING CALL the user opted into"))
        assertTrue(i.contains("call get_schedule and read today back briefly"))
        assertTrue(i.contains("schedule_task / block_time"))
        assertTrue(i.contains("create_task"))
        assertTrue(i.contains("set_task_later or carry_to_tomorrow"))
        assertTrue(i.contains("- kind: morning"))
    }

    @Test fun `evening call opening and instructions`() {
        val p = payload(callKind = "evening", label = "Evening wrap-up", notes = emptyList(), taskId = null)
        assertEquals("Evening, Ahmad. Quick wrap-up?", opening(p))
        assertEquals("Evening. Quick wrap-up?", opening(p.copy(name = null)))
        val i = CallScript.instructions(p, nowMs = now, zone = utc)
        assertTrue(i.startsWith("THIS IS THE EVENING WRAP-UP CALL the user opted into"))
        assertTrue(i.contains("get_tasks(view: completed)"))
        assertTrue(i.contains("carry_to_tomorrow ONLY when they ask for it"))
        assertTrue(i.contains("- kind: evening"))
    }

    @Test fun `after-block call opening says the task and the spoken end`() {
        val p = payload(callKind = "after_block", label = "Board prep", taskName = "Board prep", notes = emptyList(), endTime = "11:30")
        assertEquals("Hi Ahmad — Board prep was on till 11:30am. How did it go?", opening(p))
        assertEquals("Hi — Board prep was on till 11:30am. How did it go?", opening(p.copy(name = null)))
        assertEquals("Hi Ahmad — Board prep was on till 2pm. How did it go?", opening(p.copy(endTime = "14:00")))
        // No usable end → "just finished"; no task name → the label.
        assertEquals("Hi Ahmad — Board prep just finished. How did it go?", opening(p.copy(endTime = null)))
        assertEquals("Hi Ahmad — the board deck just finished. How did it go?", opening(p.copy(endTime = "soon", taskName = null, label = "the board deck")))
        // An ISO end is spoken in the given zone.
        assertEquals("Hi Ahmad — Board prep was on till 3:15pm. How did it go?", opening(p.copy(endTime = "2026-09-02T15:15:00Z")))
        val i = CallScript.instructions(p, nowMs = now, zone = utc)
        assertTrue(i.startsWith("THIS IS THE CHECK-IN AFTER A BLOCK the user opted into: the block ended and its task isn't marked done"))
        assertTrue(i.contains("done → complete_task (or complete_occurrence for a recurring one)"))
        assertTrue(i.contains("skip_occurrence / set_task_later"))
        assertTrue(i.contains("schedule_task or block_time for a new slot"))
        assertTrue(i.contains("- kind: after_block"))
        val iso = CallScript.instructions(p.copy(endTime = "2026-09-02T15:15:00Z"), nowMs = now, zone = utc)
        assertTrue(iso, iso.contains("- block ended at: 2026-09-02 15:15"))
    }

    @Test fun `an unknown or absent callKind is a requested call, and the requested opening is unchanged`() {
        val plain = payload()
        assertEquals(CallKind.REQUESTED, plain.resolvedKind)
        assertEquals(opening(plain), opening(plain.copy(callKind = "banana")))
        assertTrue(opening(plain).startsWith("Hi Ahmad — you asked me to ring so you'd speak to James."))
        // A server that wrote the row's kind into the push's `kind` slot is honoured too.
        assertEquals(CallKind.MORNING, plain.copy(kind = "morning", callKind = null).resolvedKind)
        // …but callKind wins when both are set.
        assertEquals(CallKind.EVENING, plain.copy(kind = "morning", callKind = "evening").resolvedKind)
    }

    @Test fun `every kind keeps the verbatim opening and the never-claim rule, and the name is used once`() {
        for (k in CallKind.entries) {
            val p = payload(callKind = k.wire, endTime = "11:30")
            val i = CallScript.instructions(p, nowMs = now, zone = utc)
            assertTrue(k.wire, i.contains("\"" + opening(p) + "\""))
            assertTrue(k.wire, i.contains("Open by saying EXACTLY this, verbatim, before anything else"))
            assertTrue(k.wire, i.contains("Never claim an action happened without its tool result; if a tool errors, say so plainly."))
            assertTrue(k.wire, i.contains("update_call") && i.contains("snooze_call"))
            assertTrue(k.wire, i.contains(CallScript.NAME_ONCE_RULE))
            assertTrue(k.wire, i.contains("say bye"))
            // The opening itself says the name exactly once.
            assertEquals(k.wire, 1, Regex("\\bAhmad\\b").findAll(opening(p)).count())
        }
        assertEquals("Their name is in the opening — say it there once and not again during the call.", CallScript.NAME_ONCE_RULE)
    }
}
