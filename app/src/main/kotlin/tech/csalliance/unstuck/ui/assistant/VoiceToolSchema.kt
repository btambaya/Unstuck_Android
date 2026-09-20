package tech.csalliance.unstuck.ui.assistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The tool schemas the realtime voice session is configured with (session.update)
// come from ONE place: ToolRegistry.generated.kt, the Kotlin copy of
// lib/assistant/tool-registry.json that `node scripts/gen-tool-registry.mjs`
// writes for every platform. Nothing here is hand-maintained any more — before
// the 2026-09-20 tooling rewrite this file carried its own 57 ToolSpecs
// "mirroring" the web's, and the two drifted (descriptions, enums, required
// args) with only a doc-scraping test between them. Now the registry's JSON is
// parsed at first use and sliced by surface:
//
//  • a TALK session gets every tool whose `_surfaces` names "voice";
//  • a CALL session gets those plus the "call"-only tools (snooze_call),
//    filtered to core `CallScript.callTools()` in that order;
//  • the text path takes the server's schema (generated from the same registry).
//
// The executor (AssistantTools.kt) implements every name in ToolRegistry.NAMES —
// ToolRegistryParityTest pins both directions.

/** One registry tool: its realtime-shape schema (name / description /
 *  parameters — `_surfaces` stripped) and where it belongs. */
data class RegistryTool(val name: String, val surfaces: List<String>, val schema: JsonObject)

object RegistryTools {
    private val json = Json { ignoreUnknownKeys = true }

    /** Every tool in the registry, in registry order. Parsed once. */
    val all: List<RegistryTool> by lazy {
        json.parseToJsonElement(ToolRegistry.JSON).jsonArray.map { el ->
            val o = el.jsonObject
            val surfaces = o["_surfaces"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            RegistryTool(
                name = o["name"]!!.jsonPrimitive.content,
                surfaces = surfaces,
                schema = JsonObject(o.filterKeys { it != "_surfaces" }),
            )
        }
    }

    private val byName: Map<String, RegistryTool> by lazy { all.associateBy { it.name } }

    fun byName(name: String): RegistryTool? = byName[name]

    /** The tools advertised on one surface ("text" | "voice" | "call"). */
    fun forSurface(surface: String): List<RegistryTool> = all.filter { surface in it.surfaces }

    /** A string parameter's `enum`, or empty when the tool / parameter / enum is
     *  missing — the executor validates against the registry, never a second copy. */
    fun enumOf(tool: String, param: String): List<String> =
        byName[tool]?.schema?.get("parameters")?.jsonObject?.get("properties")?.jsonObject
            ?.get(param)?.jsonObject?.get("enum")?.jsonArray
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
}

/** Tool schemas for a TALK session (realtime function shape): every registry
 *  tool with the "voice" surface — the same list the web's VOICE_TOOL_SCHEMAS
 *  and iOS `ToolRegistry.tools(for: "voice")` produce. */
fun voiceToolsJson(): JsonArray = JsonArray(RegistryTools.forSurface("voice").map { it.schema })

/** Alias kept for AppViewModel.voiceTools(): the Talk session's tools. The
 *  talk-level finish_interview is a registry tool now (voice surface), so
 *  nothing is appended here. */
fun talkVoiceToolsJson(): JsonArray = voiceToolsJson()

/** The registry filtered to the tools live during a call — `callTools` is core
 *  `CallScript.callTools()` (iOS CallScript.callTools), in that order — drawn
 *  from the voice AND call surfaces (snooze_call is call-only). Unknown names
 *  are dropped (voice can never advertise a tool the executor lacks). */
fun callVoiceTools(callTools: List<String>): List<RegistryTool> {
    val live = (RegistryTools.forSurface("voice") + RegistryTools.forSurface("call")).associateBy { it.name }
    return callTools.mapNotNull { live[it] }
}

/** Tool schemas for a CALL session (realtime function shape). */
fun callVoiceToolsJson(callTools: List<String>): JsonArray = JsonArray(callVoiceTools(callTools).map { it.schema })

// ── finish_interview — the talk-level tool (2026-09-17) ──
// The opening primer runs the get-to-know-you intro aloud while the account's
// interview is pending; this closes it. Since the 2026-09-20 rewrite it is an
// ordinary registry tool with an executor case (AssistantTools.kt →
// AssistantApi.markInterviewDone), so the text harness could close the intro
// too; the in-thread interview still closes itself through its chips.
object FinishInterviewTool {
    const val NAME = "finish_interview"
    /** The tool result the model reads. */
    const val OK = "ok: intro finished — it won't be asked again"
    /** Calling it again once the intro is closed changes nothing — say so. */
    const val ALREADY = "error: the intro is already finished — nothing changed"
}
