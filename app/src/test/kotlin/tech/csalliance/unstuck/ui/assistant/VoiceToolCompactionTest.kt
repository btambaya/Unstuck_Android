package tech.csalliance.unstuck.ui.assistant

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The voice tool schemas are ~75 % of what a realtime reply re-reads every
// time, and that prefix is what fills the account's tokens-per-minute bucket —
// so it decides how many replies a conversation gets before the assistant goes
// quiet (beta audit 2026-09-21). Compaction shortens the PROSE and nothing
// else: every tool, parameter, type, enum and required flag must survive, or
// the model loses the ability to call something. A port of iOS
// Tests/UnstuckAppTests/VoiceToolCompactionTests.swift (build 80).
class VoiceToolCompactionTest {

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    private fun strings(e: JsonElement?) = (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.sorted()
    private val empty = JsonObject(emptyMap())

    /** The registry's voice surface, verbatim — what compaction starts from. */
    private val registryVoice: JsonArray get() = JsonArray(RegistryTools.forSurface("voice").map { it.schema })

    // ── the contract — nothing that matters is lost ──

    @Test fun `every tool, parameter, type, enum and required flag survives`() {
        val before = registryVoice
        val after = voiceToolsJson()   // what a Talk session is configured with
        assertEquals("no tool is dropped", before.size, after.size)
        assertEquals("same tools, same order", before.map { it.jsonObject.str("name") }, after.map { it.jsonObject.str("name") })
        for ((bEl, aEl) in before.zip(after)) {
            val b = bEl.jsonObject
            val a = aEl.jsonObject
            val name = b.str("name") ?: "?"
            assertEquals(name, b.str("type"), a.str("type"))
            val bp = b.obj("parameters") ?: empty
            val ap = a.obj("parameters") ?: empty
            assertEquals("$name: required", strings(bp["required"]), strings(ap["required"]))
            val bProps = bp.obj("properties") ?: empty
            val aProps = ap.obj("properties") ?: empty
            assertEquals("$name: same parameters", bProps.keys, aProps.keys)
            for ((k, rawB) in bProps) {
                val pB = rawB as JsonObject
                val pA = aProps[k] as JsonObject
                assertEquals("$name.$k: type", pB.str("type"), pA.str("type"))
                assertEquals("$name.$k: enum values", strings(pB["enum"]), strings(pA["enum"]))
                assertEquals("$name.$k: array item type", pB.obj("items")?.str("type"), pA.obj("items")?.str("type"))
                // Stricter than iOS: everything but the prose is untouched.
                assertEquals("$name.$k: only the description may change", JsonObject(pB - "description"), JsonObject(pA - "description"))
            }
            assertEquals("$name: only the prose may change", JsonObject(bp - "properties"), JsonObject(ap - "properties"))
            // Never leave a tool undescribed: an unlabelled tool is harder to
            // choose than a blunt one.
            val d = a.str("description").orEmpty()
            assertFalse("$name: still described", d.isEmpty())
            assertTrue("$name: $d", d.length <= VoiceToolCompaction.TOOL_DESCRIPTION_CAP + 1)
        }
        assertEquals("Talk and the view model's alias are the same compacted list", after, talkVoiceToolsJson())
    }

    @Test fun `a call's tools are compacted too, every one of them kept`() {
        val names = callToolNames()
        val after = callVoiceToolsJson()
        assertEquals(names, after.map { it.jsonObject.str("name") })
        for (t in after) {
            val d = t.jsonObject.str("description").orEmpty()
            assertFalse("${t.jsonObject.str("name")}: still described", d.isEmpty())
            assertTrue("${t.jsonObject.str("name")}: $d", d.length <= VoiceToolCompaction.TOOL_DESCRIPTION_CAP + 1)
        }
    }

    @Test fun `it actually saves a meaningful share of the prefix`() {
        val before = VoiceToolCompaction.jsonBytes(registryVoice)
        val after = VoiceToolCompaction.jsonBytes(voiceToolsJson())
        val saved = (before - after).toDouble() / before
        // Measured at ~36 % on iOS when written. The floor guards against someone
        // re-lengthening the descriptions without noticing what it costs.
        assertTrue("compaction saved only ${(saved * 100).toInt()}% ($before → $after bytes)", saved > 0.30)
        println("voice tool schemas: $before → $after bytes (${(saved * 100).toInt()}% smaller)")
    }

    // ── shortening rules ──

    @Test fun `keeps the first sentence and caps it`() {
        val long = "Move today's unfinished scheduled tasks to tomorrow — all of them, or only taskIds. The result says which were moved and which could not be: repeat that faithfully."
        val s = VoiceToolCompaction.shorten(long, VoiceToolCompaction.TOOL_DESCRIPTION_CAP)
        assertTrue(s, s.startsWith("Move today's unfinished scheduled tasks to tomorrow"))
        assertFalse("the elaboration goes", s.contains("repeat that faithfully"))
        assertTrue(s.length <= VoiceToolCompaction.TOOL_DESCRIPTION_CAP + 1)
    }

    @Test fun `does not split on an abbreviation`() {
        val s = VoiceToolCompaction.shorten("Pick a day, e.g. Monday, for the slot. Then confirm it.", 200)
        assertEquals("e.g. is not the end of a sentence", "Pick a day, e.g. Monday, for the slot.", s)
    }

    @Test fun `short text and edge cases are left alone`() {
        assertEquals("Pause the running focus session.", VoiceToolCompaction.shorten("Pause the running focus session.", 140))
        assertEquals("", VoiceToolCompaction.shorten("", 140))
        assertEquals("", VoiceToolCompaction.shorten("   ", 140))
        // A single very long word still gets cut rather than blowing the cap.
        assertTrue(VoiceToolCompaction.shorten("x".repeat(300), 40).length <= 41)
    }

    @Test fun `a parameter keeps its prose ONLY when it carries a format or default`() {
        // These change what the model sends, so they stay.
        for (keep in listOf(
            "Local 'YYYY-MM-DD HH:MM'.", "Defaults to today.", "0 (off), 5, 10 or 15.",
            "Their reminders VERBATIM, one per note.", "Omit for all of today's unfinished ones.",
            "Minutes, 1 to 180.",
        )) assertTrue(keep, VoiceToolCompaction.carriesFormatOrDefault(keep))
        // These merely restate the parameter's own name, so they go.
        for (drop in listOf(
            "The task's title.", "Only tasks with this tag.", "Words from the task's title.",
            "The smallest concrete first step.", "A short label.",
        )) assertFalse(drop, VoiceToolCompaction.carriesFormatOrDefault(drop))
        // And the real registry keeps the date/time formats the tools need.
        val byName = voiceToolsJson().associateBy { it.jsonObject.str("name") }
        fun prose(tool: String, param: String): String? =
            byName[tool]?.jsonObject?.obj("parameters")?.obj("properties")?.obj(param)?.str("description")
        assertNotNull("a call time's format must survive", prose("request_call", "when"))
        assertNotNull("a date's format must survive", prose("create_task", "date") ?: prose("schedule_task", "date"))
        assertNull("but a restatement of the name does not", prose("create_task", "name"))
    }

    @Test fun `a parameter documented by its enum loses its prose`() {
        val tool = buildJsonObject {
            put("type", "function"); put("name", "get_tasks"); put("description", "Read tasks. Long tail of explanation.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonArray("required") { add("view") }
                putJsonObject("properties") {
                    putJsonObject("view") {
                        put("type", "string")
                        putJsonArray("enum") { add("today"); add("backlog") }
                        put("description", "Which tasks to list.")
                    }
                    putJsonObject("area") {
                        put("type", "string")
                        put("description", "Only tasks in this life area, taken from context.areas which lists them all.")
                    }
                }
            }
        }
        val out = VoiceToolCompaction.compactTool(tool)
        assertEquals("Read tasks.", out.str("description"))
        val props = out.obj("parameters")?.obj("properties") ?: empty
        val view = props.obj("view") ?: empty
        assertNull("the enum values are the documentation", view["description"])
        assertEquals("but the values themselves stay", 2, (view["enum"] as JsonArray).size)
        val area = props.obj("area") ?: empty
        assertNull("prose that only restates the parameter name goes too", area["description"])
    }
}
