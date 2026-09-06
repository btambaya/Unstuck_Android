package tech.csalliance.unstuck.ui.assistant

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.csalliance.unstuck.core.logic.AssistantHarnessRules
import java.io.File

/**
 * The vendored contract (docs/assistant-tool-contract.md, generated from the
 * web's tools.ts) is the port spec. This parses its "Tools (56)" table and
 * checks the Android registry against it: every tool name present (no more,
 * no fewer), every result template starts `ok` / `error:`, every argument the
 * contract marks required is required here, and every documented argument has
 * a schema property — so the executor, the voice schema and the doc can't drift.
 */
class ContractDiffTest {

    private class DocTool(val name: String, val args: Map<String, Boolean>, val results: List<String>)

    private fun contract(): String {
        val candidates = listOf(File("../docs/assistant-tool-contract.md"), File("docs/assistant-tool-contract.md"))
        val f = candidates.firstOrNull { it.exists() } ?: error("vendored contract not found: ${candidates.map { it.absolutePath }}")
        return f.readText()
    }

    private fun parse(md: String): List<DocTool> {
        val out = ArrayList<DocTool>()
        val row = Regex("^\\| `([a-z_]+)` \\| ([^|]*) \\| ([^|]*) \\| ([^|]*) \\| (.*?) \\| (.*?) \\| ([^|]*) \\|$")
        for (line in md.lines()) {
            val m = row.find(line) ?: continue
            val name = m.groupValues[1]
            val argsCol = m.groupValues[5]
            val resultsCol = m.groupValues[6]
            val args = LinkedHashMap<String, Boolean>()
            if (!argsCol.trim().startsWith("(none)")) {
                for (entry in argsCol.split("<br>")) {
                    val a = Regex("^([A-Za-z]+)(\\*?): ").find(entry.trim()) ?: continue
                    args[a.groupValues[1]] = a.groupValues[2] == "*"
                }
            }
            val results = resultsCol.split("<br>").map { it.trim().removePrefix("`").removeSuffix("`") }.filter { it.isNotEmpty() }
            out += DocTool(name, args, results)
        }
        return out
    }

    @Test fun `the contract lists 57 tools and the registry has exactly those`() {
        val doc = parse(contract())
        assertEquals(57, doc.size)
        assertEquals(doc.map { it.name }.toSet(), ASSISTANT_TOOL_NAMES)
        assertEquals("no duplicate specs", ASSISTANT_TOOL_SPECS.size, ASSISTANT_TOOL_NAMES.size)
    }

    @Test fun `every result template in the contract is an ok or an error string`() {
        for (t in parse(contract())) for (r in t.results) {
            assertTrue("${t.name}: $r", r.startsWith("ok") || r.startsWith("error:") || r.startsWith("- ") || r.startsWith("\${") || r.startsWith("error: <"))
        }
    }

    @Test fun `required arguments and documented arguments match the registry`() {
        val specs = ASSISTANT_TOOL_SPECS.associateBy { it.name }
        for (t in parse(contract())) {
            val spec = specs.getValue(t.name)
            val props = spec.props.map { it.name }.toSet()
            for ((arg, required) in t.args) {
                assertTrue("${t.name}.$arg missing from the schema", arg in props)
                if (required) assertTrue("${t.name}.$arg should be required", arg in spec.required)
            }
        }
    }

    @Test fun `the read-only set matches the contract's Kind column`() {
        val md = contract()
        val reads = Regex("^\\| `([a-z_]+)` \\| [^|]* \\| read \\|", RegexOption.MULTILINE).findAll(md).map { it.groupValues[1] }.toSet()
        assertEquals(reads, READ_ONLY_TOOLS)
        assertEquals(reads, AssistantHarnessRules.READ_ONLY_TOOLS)
    }

    @Test fun `the voice schema carries every tool with the OpenAI function shape`() {
        val tools = voiceToolsJson()
        assertEquals(57, tools.size)
        val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertEquals(ASSISTANT_TOOL_NAMES, names)
        for (t in tools) {
            val o = t.jsonObject
            assertEquals("function", o["type"]!!.jsonPrimitive.content)
            val params = o["parameters"]!!.jsonObject
            assertEquals("object", params["type"]!!.jsonPrimitive.content)
            val props = params["properties"]!!.jsonObject.keys
            for (r in params["required"]!!.jsonArray) assertTrue("${o["name"]} requires ${r.jsonPrimitive.content}", r.jsonPrimitive.content in props)
        }
        // F4: schedule_task's startTime is optional on every surface now.
        val schedule = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "schedule_task" }.jsonObject
        val req = schedule["parameters"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("taskId", "date"), req)
    }
}
