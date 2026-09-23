package tech.csalliance.unstuck.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import tech.csalliance.unstuck.core.logic.ProfileFactsLogic
import tech.csalliance.unstuck.core.logic.StylePreference
import tech.csalliance.unstuck.core.logic.newUuid
import tech.csalliance.unstuck.core.model.ProfileFact
import tech.csalliance.unstuck.core.model.ProfileFactCategory
import tech.csalliance.unstuck.core.model.ProfileFactSource
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// ProfileFactsService — the app-facing API for the assistant's memory (port of
// the store half of lib/assistant/profile.ts / iOS ProfileFactsService.swift).
// The local Room store is the always-on source of truth for the UI; the server
// `profile_facts` table syncs through the outbox (push), the Hydrator (pull +
// tombstones) and the RealtimeMirror (live). Every fact is visible + deletable
// in Settings — nothing is stored silently.
//
// Rules (refine-in-place, injection filter, style detection) are pure and live
// in :core ProfileFactsLogic; this type only sequences them against the store.

/** Why a profile-fact save didn't store anything (see [ProfileFactsService.store]). */
sealed class ProfileFactSaveError : Exception() {
    /** Blank after trimming — nothing to remember. */
    object Empty : ProfileFactSaveError()
    /** A MODEL-written save that reads like an instruction (the injection filter). */
    object InstructionLike : ProfileFactSaveError()
    /** The local write failed — transient, worth a retry. */
    class StoreFailed(cause: Throwable) : ProfileFactSaveError() { init { initCause(cause) } }
}

class ProfileFactsService(
    private val store: LocalStore,
    /** The outbox write-through; null runs local-only (previews / offline
     *  tests) exactly like the web before migration 050. */
    private val write: WriteThrough?,
    /** ISO-8601 instant source (injectable for tests). */
    private val now: () -> String = ::isoNow,
) {
    companion object {
        private val ISO_MS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /** UTC ISO-8601 with millisecond precision — the same shape the web's
         *  `new Date().toISOString()` writes into `created_at` / `updated_at`. */
        fun isoNow(): String = ISO_MS.format(Instant.now())
    }

    // ── reads ──────────────────────────────────────────────────────────────

    /** The active facts, most recently updated first. */
    suspend fun all(): List<ProfileFact> = activeSorted(store.snapshot(Tables.PROFILE_FACTS, ProfileFact.serializer()))

    /** Live stream of the active facts (newest first) for the surfaces. */
    fun observeAll(): Flow<List<ProfileFact>> = store.profileFacts().map { activeSorted(it) }

    /** The name they asked to be called, if any (beats the account name). */
    suspend fun preferredName(): String? = ProfileFactsLogic.preferredName(all())

    /** True when they've asked not to be addressed by name. */
    suspend fun noNamePreference(): Boolean = ProfileFactsLogic.noNamePreference(all())

    /** The `context.profile` block for the model (≤15 lines, newest first). */
    suspend fun contextLines(): List<String> = ProfileFactsLogic.contextLines(all())

    private fun activeSorted(rows: List<ProfileFact>): List<ProfileFact> =
        rows.filter { it.active }.sortedByDescending { it.updatedAt }

    // ── writes ─────────────────────────────────────────────────────────────

    /** Add — or refine in place — a fact (web `saveProfileFact`). Returns the
     *  stored fact, or null when the text is empty or, for MODEL-written sources
     *  (chat / derived), reads like an instruction — or when the local write
     *  failed. Callers that must tell those apart (the assistant's
     *  `save_profile_fact` reports a filter rejection and a store failure
     *  differently) use [store], which throws the reason. */
    suspend fun save(category: ProfileFactCategory, fact: String, source: ProfileFactSource, whenIso: String? = null): ProfileFact? =
        try { store(category, fact, source, whenIso) } catch (_: ProfileFactSaveError) { null }

    /** [save], with the failure reason: [ProfileFactSaveError.Empty] /
     *  [ProfileFactSaveError.InstructionLike] are rejections of the TEXT
     *  (nothing to retry), [ProfileFactSaveError.StoreFailed] is the local write
     *  failing (worth a retry). The local write lands before this returns; the
     *  server push is queued behind it. */
    suspend fun store(category: ProfileFactCategory, fact: String, source: ProfileFactSource, whenIso: String? = null): ProfileFact {
        val text = ProfileFactsLogic.prepareFact(fact) ?: throw ProfileFactSaveError.Empty
        if (ProfileFactsLogic.guardsAgainstInjection(source) && ProfileFactsLogic.isInstructionLike(text)) throw ProfileFactSaveError.InstructionLike
        val whenValid = ProfileFactsLogic.validWhenIso(whenIso)
        val nowIso = now()
        val stored = try {
            val existing = all()
            val match = ProfileFactsLogic.refine(existing, category, text)
            val next = if (match != null) {
                match.copy(fact = text, source = source, whenIso = whenValid ?: match.whenIso, updatedAt = nowIso)
            } else {
                ProfileFact(
                    id = newUuid(), category = category, fact = text, source = source, whenIso = whenValid,
                    active = true, createdAt = nowIso, updatedAt = nowIso,
                )
            }
            persist(next)
            next
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw ProfileFactSaveError.StoreFailed(e)
        }
        return stored
    }

    /** Edit ONE fact's text IN PLACE (Settings → "What Unstuck knows"): the SAME
     *  row and id, its category kept, `source` becoming `settings` (the user
     *  typed these words), `whenIso` replaced only when valid, `updatedAt`
     *  bumped so the newest-first order and the server's last-write-wins both
     *  see the edit. One upsert, one queued push — never a delete + re-add,
     *  which would strand the old id in every device's cache.
     *
     *  When the row has VANISHED (forgotten on another device, or never pulled
     *  on this one) the text is stored as a FRESH fact instead — the edit is
     *  never silently dropped. Throws [ProfileFactSaveError.Empty] on blank text
     *  and [ProfileFactSaveError.StoreFailed] when the local write fails.
     *  Port of iOS `AppModel.updateProfileFact`. */
    suspend fun update(
        id: String,
        fact: String,
        whenIso: String? = null,
        /** Category for the re-save branch only (the existing row keeps its own). */
        category: ProfileFactCategory = ProfileFactCategory.CONTEXT,
    ): ProfileFact {
        val text = ProfileFactsLogic.prepareFact(fact) ?: throw ProfileFactSaveError.Empty
        val row = try {
            store.getOne(Tables.PROFILE_FACTS, id, ProfileFact.serializer())
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw ProfileFactSaveError.StoreFailed(e)
        }
        if (row == null || !row.active) return store(category, text, ProfileFactSource.SETTINGS, whenIso)
        val next = row.copy(
            fact = text,
            source = ProfileFactSource.SETTINGS,
            whenIso = ProfileFactsLogic.validWhenIso(whenIso) ?: row.whenIso,
            updatedAt = now(),
        )
        try {
            persist(next)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw ProfileFactSaveError.StoreFailed(e)
        }
        return next
    }

    /** Persist a deterministically-detected style preference ("don't use my
     *  name" / "call me X") as the web does — a `preference` fact from the
     *  `chat` source. */
    suspend fun saveStylePreference(pref: StylePreference): ProfileFact? =
        save(pref.category, pref.fact, ProfileFactSource.CHAT)

    /** Put a fact back as [prior] had it — the Undo of an assistant save that
     *  refined it in place, which keeps the id (Android audit 2026-09-23, A17).
     *  The SAME row: [prior]'s wording, category, source and date, `updatedAt`
     *  bumped so the server's last-write-wins takes it. False when the row is
     *  gone or was forgotten since (never resurrected), or the write failed. */
    suspend fun restore(prior: ProfileFact): Boolean {
        val row = store.getOne(Tables.PROFILE_FACTS, prior.id, ProfileFact.serializer()) ?: return false
        if (!row.active) return false
        val next = row.copy(category = prior.category, fact = prior.fact, source = prior.source, whenIso = prior.whenIso, updatedAt = now())
        runCatching { persist(next) }.getOrElse { return false }
        return true
    }

    /** Forget one fact (web `removeProfileFact`): a soft delete — the row
     *  becomes a tombstone here and on the server so no device's cache can
     *  resurrect it. False when there is no active fact with that id. */
    suspend fun remove(id: String): Boolean {
        val row = store.getOne(Tables.PROFILE_FACTS, id, ProfileFact.serializer()) ?: return false
        if (!row.active) return false
        runCatching { persist(row.copy(active = false, updatedAt = now())) }.getOrElse { return false }
        return true
    }

    /** Forget everything (web `clearProfileFacts`): soft-deletes every active
     *  fact so the tombstones propagate. NOT the sign-out wipe — see [wipeLocal]. */
    suspend fun clear() {
        for (f in all()) remove(f.id)
    }

    /** Sign-out / user-switch: drop every local row (tombstones included)
     *  without touching the server. Queued pushes keep their own payloads, so
     *  the pre-sign-out outbox drain still delivers them. */
    suspend fun wipeLocal() {
        store.replace(Tables.PROFILE_FACTS, emptyList(), ProfileFact.serializer(), { it.id })
    }

    /** Local write + (when syncing) the queued server upsert — one op per fact. */
    private suspend fun persist(f: ProfileFact) {
        val w = write
        if (w != null) w.upsertProfileFact(f)
        else store.upsert(Tables.PROFILE_FACTS, f, ProfileFact.serializer(), f.id, f.updatedAt)
    }

    /** Test / debug seam: the raw rows, tombstones included. */
    internal suspend fun allIncludingTombstones(): List<ProfileFact> =
        store.profileFacts().first()
}
