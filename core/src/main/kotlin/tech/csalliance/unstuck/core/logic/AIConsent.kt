package tech.csalliance.unstuck.core.logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// AI data-sharing consent — the Android port of iOS UnstuckCore/AIConsent.swift
// and the web's lib/assistant/ai-consent.ts (the slim-settings plan puts the
// switch in Settings → Assistant & privacy on every platform). Before anything
// the user types or says reaches the AI provider, the app says so plainly and
// asks. The contract is shared with iOS and the web, word for word:
//
//   • the OK lives in Supabase auth user_metadata
//       { ai_consent_at: <ISO timestamp>, ai_consent_version: "2026-09-24" }
//     (auth.updateUser { data }), so it follows the account to every device.
//     Each device keeps a copy so the gate answers offline and a ringing call
//     can be judged before the app is up;
//   • it counts only while ai_consent_version == [AIConsent.VERSION] — a new
//     AI provider bumps it and every surface asks again;
//   • it is asked for once: before the FIRST assistant use of any kind (a
//     message, Talk) and before Calls are switched on — and on app open when
//     Calls are already on without it, where "Not now" turns Calls off;
//   • "Not now" blocks nothing else: that one action doesn't happen and a
//     short line says why;
//   • Settings shows it and can turn it off (clears ai_consent_at and turns
//     Calls off) or back on (the same sheet).
//
// No server-side enforcement yet — older builds must keep working. This file
// is the pure part: the copy, which records count, what "Not now" does, when
// app open asks, and how the device copy follows the account.

object AIConsent {
    /** Bump when the provider (or what is sent) changes: every OK given under
     *  an older version stops counting and the sheet shows again. */
    const val VERSION = "2026-09-24"
    /** The user_metadata keys. */
    const val AT_KEY = "ai_consent_at"
    const val VERSION_KEY = "ai_consent_version"

    // ── copy — exactly what iOS and the web show ──

    const val TITLE = "Your assistant uses OpenAI"
    const val BODY = "To answer you, Unstuck sends what you type or say to the assistant — including your voice in Talk and calls — with the tasks, calendar and notes it needs, to OpenAI, our AI provider. OpenAI uses it to reply and doesn't train its models on it. You can turn this off any time in Settings."
    const val PRIVACY_LINK_LABEL = "Privacy policy"
    /** Section 9 of the published policy, "The AI Assistant". */
    const val PRIVACY_URL = "https://unstucknow.io/privacy#s9"
    const val AGREE_LABEL = "Agree and continue"
    const val DECLINE_LABEL = "Not now"

    /** The OK as user_metadata carries it. Both null = never given (or turned
     *  off, which clears [at]). */
    @Serializable
    data class Record(val at: String? = null, val version: String? = null) {
        val isGranted: Boolean get() = isGranted(at, version)

        companion object {
            val NONE = Record()
        }
    }

    /** An OK counts when it has a time and was given for THIS version. */
    fun isGranted(at: String?, version: String?): Boolean =
        !at.isNullOrBlank() && version == VERSION

    private val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** What "Agree and continue" writes: now, in the web's toISOString shape. */
    fun grant(nowMs: Long): Record = Record(at = ISO.format(Instant.ofEpochMilli(nowMs)), version = VERSION)

    /** What turning it off leaves: [Record.at] cleared (the server deletes the
     *  key); the version stays, and on its own it means nothing. */
    fun revoked(record: Record): Record = Record(at = null, version = record.version)

    // ── the gate ──

    /** What the user was doing when the gate stopped them. */
    enum class Action {
        /** A typed or dictated message, a suggestion chip. */
        CHAT,
        /** Starting Talk (realtime voice). */
        TALK,
        /** Switching Calls or a proactive call on, a test call, "Call me about this". */
        CALLS_ON,
        /** App open with Calls already on and no OK. */
        CALLS_ON_OPEN,
        /** Settings → AI data sharing → on. */
        SETTINGS,
    }

    /** What "Not now" does: the action never happens, and [note] says why. */
    data class Decline(val turnCallsOff: Boolean, val note: String)

    fun decline(action: Action): Decline = when (action) {
        Action.CHAT, Action.TALK -> Decline(false, "Nothing was sent. The assistant needs your OK before it can answer.")
        Action.CALLS_ON -> Decline(false, "Calls use the assistant, so they stay off until you agree.")
        Action.CALLS_ON_OPEN -> Decline(true, CALLS_TURNED_OFF_NOTE)
        Action.SETTINGS -> Decline(false, "Still off. The assistant will ask before it's used.")
    }

    /** App open, "Not now": Calls were on, and now they're off. */
    const val CALLS_TURNED_OFF_TITLE = "Calls are off"
    const val CALLS_TURNED_OFF_NOTE = "Calls use the assistant, so they're off for now. You can switch them back on in Settings › Notifications & calls."

    /** Settings → AI data sharing → off. Calls go off with it. */
    const val REVOKED_NOTE = "Calls are off too. The assistant will ask again before it's used."

    /** Calls count as ON for this account when this phone takes them and
     *  something can ring: a proactive call switched on, or a call booked.
     *  The phone's switch alone is on by default, so it isn't enough. */
    fun callsAreOn(deviceSwitch: Boolean, proactiveOn: Boolean, hasLiveCall: Boolean): Boolean =
        deviceSwitch && (proactiveOn || hasLiveCall)

    /** App open: ask once per launch, only when Calls are on without an OK. */
    fun asksOnOpen(granted: Boolean, callsOn: Boolean, askedThisLaunch: Boolean): Boolean =
        !granted && callsOn && !askedThisLaunch

    // ── the device copy ──

    /** This device's copy of the account's OK. [pending] = a change made here
     *  that hasn't reached user_metadata yet: it is sent again on the next
     *  open instead of letting the account's older answer win. */
    @Serializable
    data class Cache(val userId: String, val record: Record, val pending: Boolean)

    /** Where the account's answer came from. */
    enum class Source {
        /** Straight from the server: a /user read, a sign-in, our own update. */
        FRESH,
        /** The session saved on this device at launch — it can predate a
         *  change made on the web since. */
        STORED,
    }

    /** The device copy after the account's answer lands. A change made here
     *  that is still on its way wins; so does the copy we have over a saved
     *  session's older view of the same account. Anything else follows the
     *  account. */
    fun merge(cache: Cache?, server: Record, userId: String, source: Source): Cache {
        if (cache != null && cache.userId == userId) {
            if (cache.pending || source == Source.STORED) return cache
        }
        return Cache(userId = userId, record = server, pending = false)
    }

    /** Does the device copy say yes for this account? [userId] null = not known
     *  yet (a call ringing before the app is up) — then the copy is trusted;
     *  sign-out wipes it, so it can only be the last account's. */
    fun grantedFor(cache: Cache?, userId: String?): Boolean {
        if (cache == null) return false
        if (userId != null && cache.userId != userId) return false
        return cache.record.isGranted
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(cache: Cache): String = json.encodeToString(Cache.serializer(), cache)

    fun decode(raw: String?): Cache? =
        raw?.let { runCatching { json.decodeFromString(Cache.serializer(), it) }.getOrNull() }
}

/** What the Calls block in Notifications & calls shows (iOS CallsBlockState). */
enum class CallsBlockState {
    /** The Assistant or AI data sharing is off: calls can't connect, so the
     *  whole block is one line. */
    NEEDS_ASSISTANT,
    /** This phone's switch is off: just the switch. */
    OFF,
    /** The switch and everything under it. */
    ON;

    companion object {
        fun resolve(assistantOn: Boolean, aiSharingOn: Boolean, phoneSwitchOn: Boolean): CallsBlockState = when {
            !assistantOn || !aiSharingOn -> NEEDS_ASSISTANT
            phoneSwitchOn -> ON
            else -> OFF
        }

        /** The one line shown for [NEEDS_ASSISTANT], naming only what's missing. */
        fun needsLine(assistantOn: Boolean, aiSharingOn: Boolean): String = when {
            !assistantOn && !aiSharingOn -> "Calls need the Assistant and AI data sharing."
            !assistantOn -> "Calls need the Assistant, which is off."
            else -> "Calls need AI data sharing, which is off."
        }
    }
}
