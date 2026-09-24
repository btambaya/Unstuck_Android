package tech.csalliance.unstuck.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Profile facts — the assistant's persistent memory of the user. Mirrors
// lib/assistant/profile.ts (web) + UnstuckCore/Logic/ProfileFacts.swift (iOS).
// @SerialName values are the server's check-constraint strings (migration 050).

@Serializable
enum class ProfileFactCategory {
    @SerialName("person") PERSON,
    @SerialName("rhythm") RHYTHM,
    @SerialName("constraint") CONSTRAINT,
    @SerialName("preference") PREFERENCE,
    @SerialName("context") CONTEXT;

    /** The server / web string ("person", …). */
    val raw: String get() = when (this) {
        PERSON -> "person"
        RHYTHM -> "rhythm"
        CONSTRAINT -> "constraint"
        PREFERENCE -> "preference"
        CONTEXT -> "context"
    }

    /** The plain name Settings → What Unstuck remembers shows (never the
     *  internal id) — the same words on iOS and the web. */
    val plainLabel: String get() = when (this) {
        PERSON -> "About me"
        RHYTHM -> "Routine"
        CONSTRAINT -> "Limits"
        PREFERENCE -> "Likes"
        CONTEXT -> "Other"
    }

    companion object {
        fun fromRaw(s: String?): ProfileFactCategory? = entries.firstOrNull { it.raw == s }
    }
}

@Serializable
enum class ProfileFactSource {
    @SerialName("interview") INTERVIEW,
    @SerialName("chat") CHAT,
    @SerialName("derived") DERIVED,
    @SerialName("settings") SETTINGS;

    val raw: String get() = when (this) {
        INTERVIEW -> "interview"
        CHAT -> "chat"
        DERIVED -> "derived"
        SETTINGS -> "settings"
    }

    companion object {
        fun fromRaw(s: String?): ProfileFactSource? = entries.firstOrNull { it.raw == s }
    }
}

/** One remembered fact. `active == false` is a soft-delete TOMBSTONE — the row
 *  stays (locally and on the server) so another device's cache can't resurrect
 *  a forgotten fact; every read surface filters on `active`.
 *
 *  NO field carries a Kotlin default except the two the server defaults
 *  (`whenIso` nullable, `active` true): the local-store Json has
 *  encodeDefaults=true so both always round-trip through `records`. */
@Serializable
data class ProfileFact(
    /** Lowercase RFC-4122 uuid (the server column is `uuid`). */
    val id: String,
    val category: ProfileFactCategory,
    val fact: String,
    val source: ProfileFactSource,
    /** `YYYY-MM-DD` the fact refers to (a birthday, a show, a deadline) —
     *  powers "Dates that matter". Most facts carry none. */
    val whenIso: String? = null,
    val active: Boolean = true,
    /** ISO-8601 instants (strings, compared as instants where it matters). */
    val createdAt: String,
    val updatedAt: String,
)
