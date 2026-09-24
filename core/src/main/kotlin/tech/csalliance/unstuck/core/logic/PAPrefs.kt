package tech.csalliance.unstuck.core.logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull


// Personal-assistant prefs — WHICH recurring PA moments run (`RitualPrefs`)
// and which moment ids the user has dismissed. Pure port of the web's
// lib/assistant/pa-prefs.ts (+ RitualPrefs from moments.ts) and the storage
// rules of iOS App/Features/PAPrefs.swift; the SharedPreferences adapter lives
// in the app layer (AppViewModel), keyed per account.
//
// The RITUAL toggles are account-wide: `user_preferences.pa_rituals`
// (migration 053) is the source of truth — the local copy is a cache that wins
// until the first hydrate, after which the server wins; a toggle whose push
// failed is flagged pending so the next hydrate re-pushes instead of pulling
// the server's older value over it. Moment DISMISSALS stay DEVICE-LOCAL by
// design: "not now" on the phone must not hide the card on the laptop.

@Serializable
enum class RitualKey(val raw: String) {
    MORNING("morning"), EVENING("evening"), FRIDAY("friday"), SUNDAY("sunday");

    companion object {
        /** The `set_ritual` tool's name → key ("morning" | "evening" | "friday" | "sunday"). */
        fun fromRaw(s: String?): RitualKey? = entries.firstOrNull { it.raw == s }
    }
}

/** Which recurring PA moments run — itself a personalisation choice. Asked in
 *  the interview, changeable in Settings, read by the moments engine. morning +
 *  evening default ON, the weekly ones opt-in. JSON shape matches the web's
 *  `unstuck-pa-rituals` / the `pa_rituals` column (missing keys → defaults).
 *
 *  Every field has a default, so NEVER hand this to a Json with
 *  encodeDefaults=false (the supabase-kt client's) — a `true` that matches the
 *  default would vanish from the payload. Use [PAPrefsLogic.encodeRituals] /
 *  [PAPrefsLogic.ritualsJson], which always emit all four keys. */
@Serializable
data class RitualPrefs(
    val morning: Boolean = true,
    val evening: Boolean = true,
    val friday: Boolean = false,
    val sunday: Boolean = false,
) {
    operator fun get(key: RitualKey): Boolean = when (key) {
        RitualKey.MORNING -> morning
        RitualKey.EVENING -> evening
        RitualKey.FRIDAY -> friday
        RitualKey.SUNDAY -> sunday
    }

    fun with(key: RitualKey, on: Boolean): RitualPrefs = when (key) {
        RitualKey.MORNING -> copy(morning = on)
        RitualKey.EVENING -> copy(evening = on)
        RitualKey.FRIDAY -> copy(friday = on)
        RitualKey.SUNDAY -> copy(sunday = on)
    }

    companion object {
        val DEFAULTS = RitualPrefs()
    }
}

/** The interview picker's copy (web RITUAL_LABELS). The names are the web
 *  assistant panel's "Routines" (slim settings, 2026-09-24), so the morning
 *  one no longer shares a name with the morning summary or the morning call. */
data class RitualLabel(val key: RitualKey, val label: String, val sub: String)

val RITUAL_LABELS: List<RitualLabel> = listOf(
    RitualLabel(RitualKey.MORNING, "Morning plan", "Your day, one decision, at your first open"),
    RitualLabel(RitualKey.EVENING, "Evening wind-down", "Carry what didn’t happen — no guilt attached"),
    RitualLabel(RitualKey.FRIDAY, "Friday look-back", "Your week in three minutes, one question"),
    RitualLabel(RitualKey.SUNDAY, "Sunday plan-ahead", "A look at next week before it lands on you"),
)

object PAPrefsLogic {
    /** The server column (migration 053). */
    const val RITUALS_COLUMN = "pa_rituals"
    /** The web keeps the newest 200 dismissals (gateway-card.tsx). */
    const val MAX_DISMISSED = 200

    /** Local-storage keys shared in vocabulary with the web's localStorage /
     *  iOS UserDefaults (the app layer suffixes them per account). */
    const val RITUALS_KEY = "unstuck-pa-rituals"
    const val DISMISSED_KEY = "unstuck-pa-dismissed"
    /** Set while a ritual toggle made here hasn't reached `pa_rituals` yet. */
    const val PENDING_PUSH_KEY = "unstuck-pa-rituals-pending-push"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    // ── rituals ────────────────────────────────────────────────────────────

    /** Pure (web `parseRitualPrefs`): coerce a server/local JSON value into a
     *  full RitualPrefs (unknown keys dropped, missing keys defaulted,
     *  non-booleans defaulted). Null when the value carries no usable ritual at
     *  all — a null column, an array, a scalar, `{}`. */
    fun parseRitualPrefs(value: JsonElement?): RitualPrefs? {
        val o = value as? JsonObject ?: return null
        var any = false
        var out = RitualPrefs.DEFAULTS
        for (k in RitualKey.entries) {
            val b = (o[k.raw] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: continue
            out = out.with(k, b)
            any = true
        }
        return if (any) out else null
    }

    /** Same, from raw JSON text (a stored cache). Unparseable → null. */
    fun parseRitualPrefs(raw: String?): RitualPrefs? {
        if (raw.isNullOrBlank()) return null
        val el = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        return parseRitualPrefs(el)
    }

    /** The cache read (iOS `getRitualPrefs`): `{ ...DEFAULTS, ...JSON.parse(raw) }`,
     *  defaults when nothing usable is stored. */
    fun decodeRituals(raw: String?): RitualPrefs = parseRitualPrefs(raw) ?: RitualPrefs.DEFAULTS

    /** All four keys, always (safe for any encoder). */
    fun ritualsJson(prefs: RitualPrefs): JsonObject = JsonObject(
        RitualKey.entries.associate { it.raw to JsonPrimitive(prefs[it]) },
    )

    fun encodeRituals(prefs: RitualPrefs): String = ritualsJson(prefs).toString()

    // ── dismissed moment ids ───────────────────────────────────────────────

    fun parseDismissed(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
    }

    /** Capped to the newest [MAX_DISMISSED] (web: `.slice(-200)`). */
    fun encodeDismissed(ids: List<String>): String =
        JsonArray(ids.takeLast(MAX_DISMISSED).map { JsonPrimitive(it) }).toString()

    /** Record a dismissal (idempotent; keeps the newest 200). */
    fun appendDismissed(ids: List<String>, momentId: String): List<String> =
        if (momentId in ids) ids else (ids + momentId).takeLast(MAX_DISMISSED)

    /** The `set_ritual` tool's entry point: a ritual NAME from the model.
     *  Null for an unknown name so the executor can answer with the
     *  contract's error. */
    fun setRitual(prefs: RitualPrefs, name: String, on: Boolean): RitualPrefs? =
        RitualKey.fromRaw(name)?.let { prefs.with(it, on) }
}
