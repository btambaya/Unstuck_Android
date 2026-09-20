package tech.csalliance.unstuck.ui.assistant

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import tech.csalliance.unstuck.core.logic.AssistantHarnessRules
import tech.csalliance.unstuck.core.logic.CallScript
import java.io.File
import java.security.MessageDigest

/**
 * The generated registry (ToolRegistry.generated.kt, from the web's
 * lib/assistant/tool-registry.json) is THE list of tools. This pins parity
 * both ways (docs/assistant-tooling-rules.md §5): every registry name has an
 * executor case, the executor has no case outside the registry, the voice
 * schema is the registry's voice surface, the guard's tool classes are the
 * registry's, and — when the sibling web repo is checked out — the Kotlin
 * copy's hash is the registry file's. Replaces ContractDiffTest (which scraped
 * a vendored markdown table) — 2026-09-20 tooling rewrite.
 */
class ToolRegistryParityTest {

    private fun runOnFake(name: String): String = kotlinx.coroutines.runBlocking {
        runAssistantTool(name, ToolArgs(), AssistantToolsTest().FakeApi(), TurnScratch())
    }

    @Test fun `every registry name has an executor case`() {
        assertEquals(71, ToolRegistry.NAMES.size)
        assertEquals("no duplicate names", ToolRegistry.NAMES.size, ToolRegistry.NAMES.toSet().size)
        for (name in ToolRegistry.NAMES) {
            val r = runOnFake(name)
            assertFalse("$name has no executor case: $r", r.startsWith("error: unknown tool"))
            assertTrue("$name: every result is ok: or error: — got $r", r.startsWith("ok:") || r.startsWith("error:"))
        }
    }

    @Test fun `an unknown tool names every registry tool`() {
        val r = runOnFake("no_such_tool")
        assertTrue(r, r.startsWith("error: unknown tool \"no_such_tool\". The tools are: "))
        for (name in ToolRegistry.NAMES) assertTrue(name, r.contains(name))
    }

    /** The `"name" ->` labels inside every `when (name) {` of the executor
     *  sources — the cases the executor really has, read from the code. */
    private fun executorCases(): Set<String> {
        val dir = listOf(File("src/main/kotlin/tech/csalliance/unstuck/ui/assistant"), File("app/src/main/kotlin/tech/csalliance/unstuck/ui/assistant"))
            .firstOrNull { it.isDirectory } ?: error("executor sources not found from ${File(".").absolutePath}")
        val label = Regex("^\\s*\"([a-z_]+)\"\\s*->")
        val out = LinkedHashSet<String>()
        for (f in listOf("AssistantTools.kt", "AssistantToolsSurface.kt")) {
            var depth = 0
            var inWhen = false
            for (line in File(dir, f).readLines()) {
                if (!inWhen) {
                    if (line.contains("when (name) {")) { inWhen = true; depth = 1 }
                    continue
                }
                if (depth == 1) label.find(line)?.let { out += it.groupValues[1] }
                // String templates keep their braces balanced per line, so a
                // per-line count tracks the when's own nesting well enough.
                depth += line.count { it == '{' } - line.count { it == '}' }
                if (depth <= 0) inWhen = false
            }
        }
        return out
    }

    @Test fun `the executor has no case outside the registry, and the registry has no name without a case`() {
        val cases = executorCases()
        assertTrue("source scan found the executor's cases", cases.size > 50)
        val outside = cases - ToolRegistry.NAMES.toSet()
        assertTrue("executor cases not in the registry: $outside", outside.isEmpty())
        // The call tools dispatch by set membership, not `when` labels.
        val all = cases + CallToolLogic.names + SnoozeCallTool.NAME
        val missing = ToolRegistry.NAMES.toSet() - all
        assertTrue("registry names with no executor case: $missing", missing.isEmpty())
    }

    @Test fun `the guard's tool classes are the registry's`() {
        assertEquals(ToolRegistry.READ_ONLY, READ_ONLY_TOOLS)
        assertEquals(ToolRegistry.READ_ONLY, AssistantHarnessRules.READ_ONLY_TOOLS)
        assertEquals(ToolRegistry.NAVIGATION, AssistantHarnessRules.NAVIGATION_TOOLS)
        assertEquals(ToolRegistry.STAGED, AssistantHarnessRules.STAGED_TOOLS)
        assertEquals(setOf("share_task", "share_list"), ToolRegistry.STAGED)
        for (n in ToolRegistry.READ_ONLY + ToolRegistry.NAVIGATION + ToolRegistry.STAGED + ToolRegistry.CONFIRM_FIRST) {
            assertTrue("$n is a registry tool", n in ToolRegistry.NAMES)
        }
    }

    @Test fun `the voice schema is the registry's voice surface in the realtime function shape`() {
        val tools = voiceToolsJson()
        val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(RegistryTools.forSurface("voice").map { it.name }, names)
        // Everything but the call-only snooze_call is spoken.
        assertEquals(ToolRegistry.NAMES - SnoozeCallTool.NAME, names)
        for (t in tools) {
            val o = t.jsonObject
            assertEquals("function", o["type"]!!.jsonPrimitive.content)
            assertFalse("_surfaces is stripped", o.containsKey("_surfaces"))
            val params = o["parameters"]!!.jsonObject
            assertEquals("object", params["type"]!!.jsonPrimitive.content)
            val props = params["properties"]!!.jsonObject.keys
            for (r in params["required"]!!.jsonArray) assertTrue("${o["name"]} requires ${r.jsonPrimitive.content}", r.jsonPrimitive.content in props)
        }
        // schedule_task's startTime is optional on every surface.
        val schedule = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "schedule_task" }.jsonObject
        assertEquals(listOf("taskId", "date"), schedule["parameters"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(talkVoiceToolsJson(), tools)
    }

    @Test fun `the call schema is every voice tool plus the call surface, snooze_call included`() {
        val names = callToolNames()
        assertEquals("every call tool resolves", names, callVoiceTools(names).map { it.name })
        assertEquals("the default is the full list", names, callVoiceTools().map { it.name })
        assertEquals(RegistryTools.forSurface("voice").map { it.name } + listOf("snooze_call"), names)
        assertEquals(names, CallScript.callToolNames(RegistryTools.forSurface("voice").map { it.name }, RegistryTools.forSurface("call").map { it.name }))
        assertEquals(names.size, callVoiceToolsJson().size)
        val json = callVoiceToolsJson(listOf("snooze_call", "complete_task", "no_such_tool"))
        assertEquals(listOf("snooze_call", "complete_task"), json.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        val snooze = json[0].jsonObject
        val minutes = snooze["parameters"]!!.jsonObject["properties"]!!.jsonObject["minutes"]!!.jsonObject
        assertEquals("integer", minutes["type"]!!.jsonPrimitive.content)
        assertEquals(10, minutes["default"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf("call"), RegistryTools.byName("snooze_call")!!.surfaces)
        assertTrue("plain Talk never advertises snooze_call", voiceToolsJson().none { it.jsonObject["name"]!!.jsonPrimitive.content == "snooze_call" })
    }

    @Test fun `open_screen's vocabulary is the registry enum and every screen routes`() {
        val screens = RegistryTools.enumOf("open_screen", "screen")
        assertEquals(screens, AssistantScreens.registry)
        assertTrue("areas" in screens)
        for (s in screens) assertTrue(s, assistantScreenLink(s, null).startsWith("unstuck://"))
        val links = screens.filterNot { it == "day" }.map { assistantScreenLink(it, null) }
        assertEquals("each screen needs its own link (day shares calendar's)", links.size, links.toSet().size)
    }

    @Test fun `the Kotlin copy's hash is the web registry's when the sibling repo is here`() {
        val candidates = listOf(
            File("../../unstuck/lib/assistant/tool-registry.json"),
            File("../unstuck/lib/assistant/tool-registry.json"),
        )
        val f = candidates.firstOrNull { it.exists() }
        // The web repo is a sibling on a developer machine and absent in CI;
        // the assumption says so in the log instead of a silent green tick.
        Assume.assumeTrue("sibling web repo not checked out — the hash half did not run", f != null)
        val hash = MessageDigest.getInstance("SHA-256").digest(f!!.readBytes()).joinToString("") { "%02x".format(it) }.take(16)
        assertEquals("regenerate with `node scripts/gen-tool-registry.mjs`", hash, ToolRegistry.HASH)
    }

    @Test fun `every executor result on the fake keeps the ok or error prefix under empty args`() = runTest {
        // A registry name must never throw for empty arguments — the harness
        // would turn it into `error:`, but the voice path shows the throw.
        val api = AssistantToolsTest().FakeApi()
        for (name in ToolRegistry.NAMES) {
            val r = runAssistantTool(name, ToolArgs(), api, TurnScratch())
            assertTrue("$name → $r", r.startsWith("ok:") || r.startsWith("error:"))
        }
    }
}
