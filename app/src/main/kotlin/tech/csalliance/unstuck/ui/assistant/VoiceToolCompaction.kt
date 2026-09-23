package tech.csalliance.unstuck.ui.assistant

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Compacting the tool schemas for a SPOKEN session — a runtime port of iOS
// App/Features/VoiceToolCompaction.swift (build 80), rules and constants 1:1.
//
// Every realtime reply re-reads the whole session prefix, and the 70 tool
// schemas are about three quarters of it: ~34 KB of JSON, ~9,000 of the
// ~11,000 tokens a reply costs (measured, beta audit 2026-09-21). That prefix
// is also what fills the account's tokens-per-minute bucket, so it decides how
// many replies a conversation gets before the assistant goes quiet — which is
// what two testers actually hit.
//
// Half of those bytes are prose. The descriptions are written for the TEXT
// assistant, which reads a long paragraph once per turn; a voice turn pays for
// them again on every single reply. So voice gets a shorter copy of the SAME
// tools. Nothing is removed: every tool, every parameter and every enum
// survives, because the model must still be able to call all of them.
//
// What goes:
//   • a tool description keeps its FIRST SENTENCE, capped — the rest is
//     elaboration the model does not need to choose the tool;
//   • a parameter description is dropped when the parameter already states its
//     own values through `enum` — the values ARE the documentation;
//   • a parameter description is dropped when it merely restates the parameter
//     name ("name: The task's title", "tag: Only tasks with this tag");
//   • it is KEPT, first sentence only, when it carries something the name and
//     type cannot: a format, a default, a unit, a permitted value. Those are
//     the ones that change what the model sends ("local 'YYYY-MM-DD HH:MM'",
//     "defaults to today", "0 = off"), and losing them produces wrong calls.
//
// Deliberately at RUNTIME, not in `scripts/gen-tool-registry.mjs`: that
// generator also writes the iOS copy, which already compacts at runtime (it
// would be compacted twice), and the web and server copies, which are not
// being changed. Once every platform compacts, it moves into the generator as
// a separate voice emission and both runtime copies go.

object VoiceToolCompaction {
    /** A tool description past this many characters is cut to its first
     *  sentence; the first sentence is then hard-capped. */
    const val TOOL_DESCRIPTION_CAP = 90
    /** Parameter prose is terser still — it is read once the tool is chosen. */
    const val PARAM_DESCRIPTION_CAP = 60

    private val FORMAT_HINTS = listOf(
        "yyyy", "hh:mm", "default", "omit", "verbatim", "minute", "local",
        "leave", "blank", "null", "true", "false", "iso", "format",
    )
    /** A ". " after one of these does not end the first sentence. */
    private val NOT_A_SENTENCE_END = setOf("e.g", "i.e", "vs", "etc", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** A parameter description EARNS its place when it says something the name
     *  and type cannot: a format, a default, a unit, a permitted value. Those
     *  change what the model sends. Everything else is a restatement of the
     *  name and is dropped. */
    fun carriesFormatOrDefault(description: String): Boolean {
        val d = description.lowercase()
        if (d.any { it.isDigit() }) return true
        return FORMAT_HINTS.any { it in d }
    }

    /** The realtime tool list for a spoken session, compacted. */
    fun compact(tools: JsonArray): JsonArray = JsonArray(tools.map { (it as? JsonObject)?.let(::compactTool) ?: it })

    fun compactTool(tool: JsonObject): JsonObject {
        val t = tool.toMutableMap()
        (tool["description"] as? JsonPrimitive)?.takeIf { it.isString }?.let {
            t["description"] = JsonPrimitive(shorten(it.content, TOOL_DESCRIPTION_CAP))
        }
        val params = tool["parameters"] as? JsonObject
        val props = params?.get("properties") as? JsonObject
        if (params != null && props != null) {
            val compacted = props.mapValues { (_, raw) ->
                val p = raw as? JsonObject ?: return@mapValues raw
                val out = p.toMutableMap()
                val d = (p["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (p["enum"] != null) {
                    // The allowed values say what it is; the prose repeats them.
                    out.remove("description")
                } else if (d != null) {
                    if (carriesFormatOrDefault(d)) out["description"] = JsonPrimitive(shorten(d, PARAM_DESCRIPTION_CAP))
                    else out.remove("description")
                }
                JsonObject(out)
            }
            t["parameters"] = JsonObject(params.toMutableMap().apply { put("properties", JsonObject(compacted)) })
        }
        return JsonObject(t)
    }

    /** First sentence, then a hard cap on a word boundary. Never empty: a tool
     *  with no description at all is harder to choose than a blunt one. */
    fun shorten(text: String, cap: Int): String {
        val flat = text.replace("\n", " ").trim()
        if (flat.isEmpty()) return flat
        var s = flat
        // First sentence: ". " ends one, but "e.g. " and "i.e. " do not.
        var from = 0
        while (true) {
            val dot = s.indexOf(". ", from)
            if (dot < 0) break
            val lastWord = s.substring(0, dot).split(' ').lastOrNull { it.isNotEmpty() } ?: ""
            if (lastWord !in NOT_A_SENTENCE_END) {
                s = s.substring(0, dot + 1)
                break
            }
            from = dot + 2
        }
        s = s.trim()
        if (s.length <= cap) return s
        val cut = s.take(cap)
        val lastSpace = cut.lastIndexOf(' ')
        return if (lastSpace >= 0) cut.substring(0, lastSpace).trim() + "…" else "$cut…"
    }

    /** Bytes of JSON a tool list serialises to — how the saving is measured. */
    fun jsonBytes(tools: JsonArray): Int = tools.toString().toByteArray(Charsets.UTF_8).size
}
