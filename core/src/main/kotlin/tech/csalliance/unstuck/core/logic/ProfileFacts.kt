package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.model.ProfileFact
import tech.csalliance.unstuck.core.model.ProfileFactCategory
import tech.csalliance.unstuck.core.model.ProfileFactSource

// Profile facts — the assistant's persistent memory of the user (people,
// rhythm, constraints, preferences, context). Pure port of the logic half of
// lib/assistant/profile.ts (via UnstuckCore/Logic/ProfileFacts.swift): the
// store/sync half lives in :sync (ProfileFactsService / Hydrator / mirror).
//
// Every rule here is copied from the web verbatim (same regexes, same
// stoplists, same caps) so the three platforms remember — and refuse — the
// same things. Facts become part of every future system prompt, which is why
// the save path filters instruction-shaped text from MODEL-written sources and
// why the context block is capped and newest-first.

/** A style preference detected deterministically from the USER'S OWN words
 *  (web `detectStylePreference`): the model kept promising "I'll skip the
 *  name" without saving anything, so the app persists these itself. */
sealed class StylePreference {
    object NoName : StylePreference() {
        override fun toString() = "NoName"
    }
    data class CallMe(val name: String) : StylePreference()

    /** Always a `preference` fact. */
    val category: ProfileFactCategory get() = ProfileFactCategory.PREFERENCE

    /** The exact fact text the web stores — every surface parses these strings
     *  back (`noNamePreference` / `preferredName`), so they must match
     *  byte-for-byte across platforms. */
    val fact: String get() = when (this) {
        is NoName -> "Don't use their name in replies"
        is CallMe -> "Call them $name"
    }
}

object ProfileFactsLogic {
    /** Server check: `char_length(fact) between 1 and 300`. */
    const val MAX_FACT_LENGTH = 300
    /** Most-recent facts that ever reach the model (web `.slice(0, 15)`). */
    const val CONTEXT_CAP = 15

    // ── save-path normalisation ────────────────────────────────────────────

    /** Lenient category parse for tool arguments: an unknown / missing value
     *  falls back to `context` (web `isCategory(category) ? category : 'context'`). */
    fun category(raw: String?): ProfileFactCategory =
        ProfileFactCategory.fromRaw(raw) ?: ProfileFactCategory.CONTEXT

    /** Trim + cap at 300 characters (web `fact.trim().slice(0, 300)`); null
     *  when nothing is left. The cap counts Unicode code points so the result
     *  always satisfies Postgres' `char_length` check. */
    fun prepareFact(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val cps = trimmed.codePoints().toArray()
        if (cps.size <= MAX_FACT_LENGTH) return trimmed
        return String(cps, 0, MAX_FACT_LENGTH)
    }

    /** `YYYY-MM-DD` or nothing (web `/^\d{4}-\d{2}-\d{2}$/`) — and it must be a
     *  REAL calendar date. The web's regex alone lets "2026-02-30" through: JS
     *  date math silently rolls that to 2 March, but `java.time` THROWS, so a
     *  model that invents an impossible birthday would crash the gateway card
     *  for as long as the fact sat in the 3–14-day window. Postgres' `when_iso
     *  date` column (migration 050) rejects it too, so this is the server's own
     *  answer, one round-trip earlier — and no permanently-failing outbox op. */
    fun validWhenIso(s: String?): String? =
        if (s != null && WHEN_ISO.matches(s) && parseYmdOrNull(s) != null) s else null

    /** True for the sources whose saves the injection filter guards: the
     *  MODEL-written ones (chat / voice / derived). Facts the user types
     *  themselves (interview, Settings) are their own words. */
    fun guardsAgainstInjection(source: ProfileFactSource): Boolean =
        source == ProfileFactSource.CHAT || source == ProfileFactSource.DERIVED

    // ── refine keys ────────────────────────────────────────────────────────

    /** Punctuation-insensitive leading-words key (web `keyOf`): lowercase,
     *  split on whitespace / dashes, strip everything but letters, digits and
     *  apostrophes, keep the first two words. "Maleek — son, 9" and
     *  "Maleek - son" both key to "maleek son". */
    fun normalizeKey(s: String): String =
        s.lowercase().split(KEY_SPLIT)
            .map { KEY_STRIP.replace(it, "") }
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString(" ")

    /** The existing fact a new one refines IN PLACE, or null for a fresh row.
     *  Exact (trimmed, case-insensitive) duplicates dedup in any category;
     *  leading-words matching is PERSON-only ("Maleek — son" → "Maleek — son,
     *  9") because on other categories it clobbered distinct constraints
     *  ("Never schedule mornings" vs "Never schedule Fridays"). Pass the
     *  ACTIVE facts; [fact] should already be [prepareFact]-normalised. */
    fun refine(existing: List<ProfileFact>, category: ProfileFactCategory, fact: String): ProfileFact? {
        val key2 = normalizeKey(fact)
        val lower = fact.lowercase()
        return existing.firstOrNull { f ->
            if (f.category != category) return@firstOrNull false
            if (f.fact.trim().lowercase() == lower) return@firstOrNull true
            category == ProfileFactCategory.PERSON && key2.length >= 3 && normalizeKey(f.fact) == key2
        }
    }

    // ── injection filter ───────────────────────────────────────────────────

    /** A fact that reads like an INSTRUCTION ("ignore your rules", "you must
     *  always…") is a persistent injection vector once it's in every prompt.
     *  Rejects the obvious shapes; legitimate third-person facts never match. */
    fun isInstructionLike(text: String): Boolean = INJECTION.containsMatchIn(text)

    // ── style preferences ──────────────────────────────────────────────────

    /** Deterministic "don't use my name" / "call me X" detection on the user's
     *  own message. Capitalised name REQUIRED, common false positives
     *  stoplisted, and task-ish sentences skipped ("remind me to call me mum"). */
    fun detectStylePreference(userText: String): StylePreference? {
        val t = userText.trim()
        if (NO_NAME_REQUEST.containsMatchIn(t)) return StylePreference.NoName
        val name = CALL_ME.find(t)?.groupValues?.get(1) ?: return null
        if (CALL_ME_STOP.matches(name)) return null
        if (DONT_CALL_ME.containsMatchIn(t)) return null
        if (TASKISH.containsMatchIn(t)) return null
        return StylePreference.CallMe(name)
    }

    /** True when they've asked NOT to be addressed by name — a saved
     *  preference every surface must obey. */
    fun noNamePreference(facts: List<ProfileFact>): Boolean =
        facts.any { it.category == ProfileFactCategory.PREFERENCE && NO_NAME_FACT.containsMatchIn(it.fact) }

    /** The name THEY asked to be called, if they've ever said so — overrides
     *  the account display name everywhere. Parsed from preference facts like
     *  "Call them Ari, not Ahmad" / "Prefers to be called Chief" / "Goes by Mo". */
    fun preferredName(facts: List<ProfileFact>): String? {
        for (f in facts) {
            if (f.category != ProfileFactCategory.PREFERENCE) continue
            PREFERRED_NAME.find(f.fact)?.let { return it.groupValues[1] }
        }
        return null
    }

    // ── model context ──────────────────────────────────────────────────────

    /** The facts the model sees: most recently updated first, capped at 15.
     *  Stable on ties (the web relies on V8's stable sort). Pass active facts. */
    fun sortedForContext(facts: List<ProfileFact>): List<ProfileFact> =
        facts.sortedByDescending { it.updatedAt }.take(CONTEXT_CAP)

    /** One line per fact, exactly as `context.profile` is built on the web
     *  (lib/assistant/tools.ts): `[category] fact` plus ` (date: YYYY-MM-DD)`
     *  when the fact is about a date. */
    fun contextLine(f: ProfileFact): String {
        val base = "[${f.category.raw}] ${f.fact}"
        return if (f.whenIso != null) "$base (date: ${f.whenIso})" else base
    }

    /** [sortedForContext] rendered with [contextLine] — the ONLY shape that
     *  ever leaves the device for the model vendor. */
    fun contextLines(facts: List<ProfileFact>): List<String> = sortedForContext(facts).map(::contextLine)

    // ── regexes (copied from profile.ts) ───────────────────────────────────
    // JS `\w` / `\b` / `.` are ASCII-word, word-boundary and no-newline on the
    // JVM too (no UNICODE_CHARACTER_CLASS), so the patterns transfer verbatim.

    private val WHEN_ISO = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val KEY_SPLIT = Regex("[\\s—–-]+")
    private val KEY_STRIP = Regex("[^\\p{L}\\p{N}'’]")
    private val INJECTION = Regex(
        "\\b(ignore|disregard|forget)\\b.{0,30}\\b(previous|prior|above|instruction|rule|prompt|system)|\\b(you (are|must|should|will|can|shall)\\b|from now on|act as|pretend to be|reveal|disclose|your (system )?prompt|jailbreak|developer mode|always (say|state|reveal|include|mention))\\b",
        RegexOption.IGNORE_CASE,
    )
    private val NO_NAME_REQUEST = Regex(
        "\\b(don'?t|do not|stop|never|quit)\\b.{0,40}\\b(us(?:e|ing)|say(?:ing)?|mention(?:ing)?|call(?:ing)?|address(?:ing)?)\\b.{0,25}\\bname\\b",
        RegexOption.IGNORE_CASE,
    )
    // Capitalised name REQUIRED (no case-insensitive flag — it defeated that).
    private val CALL_ME = Regex("\\b[Cc]all me [\"'“]?([A-Z][\\w'’-]{1,30})[\"'”]?")
    private val CALL_ME_STOP = Regex("^(Back|Later|Now|Today|Tomorrow|Tonight|When|If|At|On|In|After|Before|Please|Again|Once|Soon|Mum|Dad|Mom|Home)$")
    private val DONT_CALL_ME = Regex("\\b(don'?t|do not|never|stop) call me\\b", RegexOption.IGNORE_CASE)
    private val TASKISH = Regex("\\b(remind|task|schedule|add|set|book|ring)\\b", RegexOption.IGNORE_CASE)
    private val NO_NAME_FACT = Regex(
        "\\b(don'?t|do not|stop|never|avoid|without)\\b.{0,30}\\b(us(?:e|ing)|say(?:ing)?|mention(?:ing)?|address(?:ing)?|call(?:ing)?)\\b.{0,20}\\bname\\b",
        RegexOption.IGNORE_CASE,
    )
    private val PREFERRED_NAME = Regex(
        "(?:call (?:me|him|her|them)|to be called|goes by)\\s+[\"'“]?([A-Za-z][\\w'’-]*)",
        RegexOption.IGNORE_CASE,
    )
}
