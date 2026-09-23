package tech.csalliance.unstuck.sync

import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import tech.csalliance.unstuck.core.time.Time
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables

// ─────────────────────────────────────────────────────────────────────────────
// THE CATCH-UP PULL — the correctness path.
//
// postgres_changes has NO replay: anything written while the socket is down is
// lost to that subscriber for ever, and a channel can report SUBSCRIBED while
// being permanently deaf. So the live mirror is an OPTIMISATION, and this is the
// mechanism that actually keeps the device in step.
//
// Per table we keep a HIGH-WATER MARK — the newest stamp this client has
// accepted — and ask for rows AT or after it, ordered by (stamp, id) and paged.
// That is bounded work (usually just the row the mark sits on) instead of the
// full-table replace the 60s foreground pull used to do.
//
// What a cursor pull CANNOT see, and what covers it:
//  • a HARD DELETE — no row comes back to tell us. [reconcileDeletions] fetches
//    id-only pages and drops what the server no longer has.
//  • a row stamped by its WRITER's clock: a task INSERT keeps the client's
//    `updated_at` (the touch trigger is BEFORE UPDATE only) and profile_facts has
//    no trigger at all, so a row created offline elsewhere can land behind the
//    mark, and a fast clock can drag the mark past real server edits. The same
//    id sweep takes what the server has that this device doesn't hold, or holds
//    an older stamp of; and each launch starts with a full hydrate, as on iOS
//    (Android audit 2026-09-23, A11).
//  • tables with no monotonic column whatsoever (cal_blocks, calendar_connections)
//    — [NON_CURSOR_TABLES]; the catch-up pulls those the old way (full replace),
//    which is still strictly less work than replacing every table.
// ─────────────────────────────────────────────────────────────────────────────

/** How faithfully a table's cursor column tracks changes. */
internal enum class CursorKind {
    /** A real `updated_at`, moved by the server on every write. */
    EXACT,
}

internal data class CursorSpec(
    val table: String,
    val column: String,
    val kind: CursorKind,
    /** The column whose value the local store keeps as this table's row stamp
     *  (RowMeta.updatedAt, the LWW guard), or null when it keeps none. Only when
     *  it IS [column] can a local row's stamp be compared with the server's. */
    val localStamp: String? = column,
    /** Where the mark is stored. A table whose cursor column changed gets a new
     *  key, so a mark measured on the old column is never read as the new one. */
    val cursorKey: String = table,
)

/** The outcome of one catch-up pass — what the freshness owner reasons about. */
data class CatchUpOutcome(
    /** Rows written to the local store, per table. */
    val applied: Map<String, Int> = emptyMap(),
    /** Rows dropped because the server no longer has them. */
    val deleted: Int = 0,
    /** Rows this device PROVABLY did not have (absent locally, or older than the
     *  server's copy by more than the live-delivery grace) on a table the realtime
     *  mirror subscribes. Non-zero with no realtime event in the same window means
     *  the channel is deaf — evidence, not a guess. */
    val provenMissed: Int = 0,
    /** Tables whose cursor did NOT advance because a row with a pending local
     *  write sat in the way — they are re-offered on the next pass. */
    val blocked: Set<String> = emptySet(),
    /** Tables whose read failed (offline, 5xx) — left intact, retried next pass. */
    val failed: Set<String> = emptySet(),
    /** True when this pass was a full hydrate rather than a cursor pull. */
    val wasFullHydrate: Boolean = false,
    /** A `collections` row came back (applied, kept by the local store, or skipped
     *  for a queued write). The server row carries no membership, and migration 056
     *  §4 bumps `updated_at` on every collection_members change, so this is what
     *  tells the catch-up to re-read membership (audit 2026-09-22 C8). The re-read
     *  of the row the mark sits on doesn't count, so a quiet tick never sets it. */
    val collectionsChanged: Boolean = false,
) {
    val appliedCount: Int get() = applied.values.sum()
}

/**
 * The per-user, per-table high-water marks. Deliberately a tiny interface: the
 * production store is SharedPreferences, the tests use the in-memory one.
 */
interface SyncCursors {
    fun get(userId: String, table: String): String?
    fun put(userId: String, table: String, value: String)
    fun clear(userId: String)
    /** True when this user has at least one cursor — i.e. a full hydrate has
     *  completed at least once and a cursor pull is meaningful. */
    fun hasAny(userId: String): Boolean
}

class InMemorySyncCursors : SyncCursors {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun key(u: String, t: String) = "$u|$t"
    override fun get(userId: String, table: String): String? = map[key(userId, table)]
    override fun put(userId: String, table: String, value: String) { map[key(userId, table)] = value }
    override fun clear(userId: String) { map.keys.removeAll { it.startsWith("$userId|") } }
    override fun hasAny(userId: String): Boolean = map.keys.any { it.startsWith("$userId|") }
}

/** SharedPreferences-backed cursors (`cursor.<uid>.<table>`). Survives a process
 *  death (each launch's full hydrate then re-seeds them); a sign-out / user
 *  switch clears them. */
class PrefsSyncCursors(private val prefs: SharedPreferences) : SyncCursors {
    private fun key(u: String, t: String) = "cursor.$u.$t"
    override fun get(userId: String, table: String): String? = prefs.getString(key(userId, table), null)
    override fun put(userId: String, table: String, value: String) {
        prefs.edit().putString(key(userId, table), value).apply()
    }
    override fun clear(userId: String) {
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith("cursor.$userId.") }.forEach { e.remove(it) }
        e.apply()
    }
    override fun hasAny(userId: String): Boolean = prefs.all.keys.any { it.startsWith("cursor.$userId.") }
}

/**
 * Runs the cursor pull and the deletion reconcile. Stateless apart from the
 * cursors; every write goes through [LocalStore] (so the per-table version
 * counters bump and the UI re-reads) and through [RowApply] (so the live mirror
 * and this path can never disagree about how a row is applied).
 */
class CatchUpPuller(
    private val remote: SyncRemote,
    private val store: LocalStore,
    private val cursors: SyncCursors,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = { println(it) },
    /** The collections membership re-read, run right after the collections pull:
     *  `(userId, collectionsChanged)`, and the Hydrator decides (it also retries a
     *  membership read that failed earlier). Parity with iOS build 81, audit
     *  2026-09-22 C8. */
    private val refreshMembership: suspend (String, Boolean) -> Unit = { _, _ -> },
) {

    /**
     * Pull everything newer than the marks and merge it. Rows with a QUEUED local
     * write are skipped (the same rule [Hydrator] honours) and freeze that
     * table's cursor so the server's copy is re-offered once the write lands.
     */
    suspend fun catchUp(userId: String): CatchUpOutcome {
        val applied = LinkedHashMap<String, Int>()
        val blocked = mutableSetOf<String>()
        val failed = mutableSetOf<String>()
        var provenMissed = 0
        var collectionsChanged = false
        for (spec in CURSOR_TABLES) {
            val since = cursors.get(userId, spec.cursorKey) ?: EPOCH
            try {
                val result = pullTable(userId, spec, since)
                if (result.count > 0) applied[spec.table] = result.count
                provenMissed += result.provenMissed
                if (result.blocked) blocked += spec.table
                if (spec.table == Tables.COLLECTIONS && result.seen) collectionsChanged = true
                result.cursor?.let { cursors.put(userId, spec.cursorKey, it) }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                failed += spec.table
                log("[catchup] ${spec.table} failed, keeping local + cursor: $t")
            }
            // Right after the collections pull, not after every table: an edit made
            // while the rest of the pull runs would still route on the stale
            // membership.
            if (spec.table == Tables.COLLECTIONS) {
                try {
                    refreshMembership(userId, collectionsChanged)
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    log("[catchup] collection membership re-read failed: $t")
                }
            }
        }
        return CatchUpOutcome(
            applied = applied, blocked = blocked, failed = failed, provenMissed = provenMissed,
            collectionsChanged = collectionsChanged,
        )
    }

    /** [seen]: at least one row this device had not seen came back and was taken
     *  or deliberately skipped (not one that failed to apply). */
    private data class TablePull(val count: Int, val cursor: String?, val blocked: Boolean, val provenMissed: Int, val seen: Boolean)

    private suspend fun pullTable(userId: String, spec: CursorSpec, since: String): TablePull {
        var cursor: String? = null
        var applied = 0
        var provenMissed = 0
        var blocked = false
        var seen = false
        // The pass starts AT the mark, not strictly after it: a row sharing the
        // mark's stamp that was held back last pass, or the tail of a tie the page
        // cap cut short, is still owed (Android audit 2026-09-23, A11). The row the
        // mark sits on therefore comes back every pass; that re-read is applied
        // (RowApply is idempotent) but is not news. `taken` keeps a row that a tie
        // page repeats from being handled twice.
        val sinceInstant = if (since == EPOCH) null else instantOf(since)
        val taken = HashSet<String>()
        var at = since
        var inclusive = sinceInstant != null
        var tieAfterId: String? = null   // set while finishing a run of rows stamped `at`, by id
        var pages = 0
        var capped = false
        while (true) {
            if (pages >= MAX_PAGES) { capped = true; break }
            pages++
            val rows = when {
                tieAfterId != null -> remote.fetchTie(spec.table, spec.column, at, tieAfterId, PAGE_SIZE)
                inclusive -> remote.fetchSince(spec.table, spec.column, justBefore(at), PAGE_SIZE)
                else -> remote.fetchSince(spec.table, spec.column, at, PAGE_SIZE)
            }
            for (row in rows) {
                val stamp = stampOf(row, spec.column)
                val id = (row["id"] as? JsonPrimitive)?.contentOrNull
                if (id == null) { blocked = true; continue }
                if (!taken.add(id)) continue
                val reRead = sinceInstant != null && stamp != null && instantOf(stamp) == sinceInstant &&
                    store.rowMeta(spec.table, id).exists
                // A row this device has a queued write for must not be clobbered
                // (the rule the hydrate already honours). Don't advance past it
                // either: once the write lands, the server's copy is re-offered.
                if (store.latestPendingUpsert(spec.table, id) != null) {
                    blocked = true
                    if (!reRead) seen = true
                    continue
                }
                // …and a row we DELETED here whose delete hasn't landed yet is
                // still on the server. Re-applying it would resurrect something
                // the user removed; hold the cursor so the server's copy is
                // re-offered if that delete is ever abandoned.
                if (store.hasPendingDelete(spec.table, id)) {
                    blocked = true
                    if (!reRead) seen = true
                    continue
                }
                // Was this row genuinely missing from this device? (Absent, or our
                // copy is older than the server's by more than the grace the live
                // mirror gets to deliver it.) That is the deafness evidence.
                val missed = !reRead && provablyMissed(spec, id, stamp)
                val attempt = runCatching { RowApply.apply(spec.table, row, store, userId) }
                if (attempt.isFailure) {
                    val t = attempt.exceptionOrNull()
                    if (t is CancellationException) throw t
                    // We could NOT take this row (a decode regression, a store
                    // error). Moving the mark past it would lose that change for
                    // good, which is precisely the failure this whole mechanism
                    // exists to prevent — so freeze the table's cursor and take
                    // it again next pass.
                    log("[catchup] ${spec.table} row $id could not be applied, holding the cursor: $t")
                    blocked = true
                    continue
                }
                val ok = attempt.getOrDefault(false)
                if (!reRead) seen = true
                // A row the local store deliberately kept (its copy is newer) still
                // counts as SEEN — freezing the cursor on it would re-pull for ever
                // on a device whose clock runs ahead. Only a row we could not
                // apply, or one with a pending local write, blocks.
                if (ok && !reRead) {
                    applied++
                    if (missed) provenMissed++
                }
                if (!blocked && stamp != null) cursor = stamp
            }
            // Past a row we could not take there is nothing to advance to; the next
            // pass starts again from the mark.
            if (blocked) break
            if (tieAfterId != null) {
                // The run is finished once a page comes back short; carry on
                // strictly after its stamp.
                if (rows.size < PAGE_SIZE) { tieAfterId = null; inclusive = false; continue }
                tieAfterId = (rows.last()["id"] as? JsonPrimitive)?.contentOrNull ?: break
                continue
            }
            if (rows.size < PAGE_SIZE) break
            // A full page can end part-way through a run of rows sharing its last
            // stamp (one server statement stamps them all alike). Finish that run by
            // id before asking for anything strictly newer, or its tail is skipped
            // for good (Android audit 2026-09-23, A11).
            val last = rows.last()
            at = stampOf(last, spec.column) ?: break
            tieAfterId = (last["id"] as? JsonPrimitive)?.contentOrNull ?: break
            inclusive = false
        }
        if (capped) log("[catchup] ${spec.table} hit the page cap — the next pass carries on from the mark")
        return TablePull(applied, cursor, blocked, provenMissed, seen)
    }

    /** True when the local store demonstrably did NOT have this row's change and
     *  the live mirror had long enough to deliver it. Conservative on purpose: an
     *  unstamped local row, a stamp we can't parse, or a change younger than
     *  [REALTIME_GRACE_MS] proves nothing. */
    private suspend fun provablyMissed(spec: CursorSpec, id: String, stamp: String?): Boolean {
        val serverMs = stamp?.let { Time.parseMillis(it) } ?: return false
        if (now() - serverMs < REALTIME_GRACE_MS) return false
        val meta = store.rowMeta(spec.table, id)
        if (!meta.exists) return true
        // The local store stamps this table with another column (sessions keep
        // completed_at, captures and reason_logs `at`): nothing to compare.
        if (spec.localStamp != spec.column) return false
        val localMs = meta.updatedAt?.let { Time.parseMillis(it) } ?: return false
        return serverMs > localMs
    }

    /**
     * DELETIONS. A cursor pull cannot see a hard delete, so fetch id-only pages
     * and drop local rows the server no longer has. Never touches a row with a
     * pending local write (an offline create the server hasn't seen).
     *
     * Refuses to act on a wholesale empty answer: if EVERY reconciled table comes
     * back with zero ids while the device holds rows in more than one table, that
     * is far more likely an unauthenticated / RLS-empty read than a user who
     * deleted their whole account's data — exactly the shape that has wiped
     * devices before. It logs and does nothing.
     *
     * The same sweep then takes what the cursor could not see ([repairUnseen]).
     */
    suspend fun reconcileDeletions(userId: String): Int {
        val server = LinkedHashMap<CursorSpec, Map<String, String?>>()
        for (spec in CURSOR_TABLES) {
            if (spec.table !in RECONCILE_TABLES) continue
            val rows = try {
                fetchAllIdStamps(spec)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                log("[catchup] id sweep of ${spec.table} failed, skipping: $t")
                continue
            }
            server[spec] = rows
        }
        if (server.isEmpty()) return 0
        if (server.values.all { it.isEmpty() }) {
            val populated = server.keys.count { store.countRows(it.table) > 0 }
            if (populated > 1) {
                log("[catchup] id sweep returned NOTHING for every table while $populated hold rows — refusing to delete")
                return 0
            }
        }
        var dropped = 0
        for ((spec, rows) in server) {
            val preserve = if (spec.table == Tables.CAL_BLOCKS) EXTERNAL_BLOCK_PREFIX else null
            dropped += store.retainIds(spec.table, rows.keys, preserve)
        }
        if (dropped > 0) log("[catchup] reconciled $dropped row(s) the server no longer has")
        var repaired = 0
        for ((spec, rows) in server) repaired += repairUnseen(userId, spec, rows)
        if (repaired > 0) log("[catchup] took $repaired row(s) the cursor could not see")
        return dropped
    }

    /** id → [CursorSpec.column] for every server row of [spec]'s table; the stamp
     *  is only read where the local store keeps the same column to compare with. */
    private suspend fun fetchAllIdStamps(spec: CursorSpec): Map<String, String?> {
        val column = spec.column.takeIf { spec.localStamp == it }
        val out = LinkedHashMap<String, String?>()
        var offset = 0
        while (offset < MAX_IDS) {
            val page = remote.fetchIdStamps(spec.table, column, offset, ID_PAGE_SIZE)
            for ((id, stamp) in page) out[id] = stamp
            if (page.size < ID_PAGE_SIZE) return out
            offset += ID_PAGE_SIZE
        }
        // Bigger than we are willing to page: the id set is incomplete, so using
        // it would delete real rows. Signal "unknown" by throwing.
        throw IllegalStateException("${spec.table} has more than $MAX_IDS rows — id sweep abandoned")
    }

    /**
     * What the cursor cannot see (Android audit 2026-09-23, A11). The mark pages by
     * stamps, and a task INSERT or a profile_facts write carries its WRITER's
     * clock: a row created (or a fact forgotten) offline on another device lands
     * behind this device's mark, and a row stamped in the future drags the mark
     * past real server edits. The id sweep already lists every row the server has,
     * so take the ones this device doesn't hold and — where both sides keep the
     * same stamp — the ones whose server stamp is newer than ours. Same guards as
     * the pull: a row with a queued local write or delete is left alone.
     */
    private suspend fun repairUnseen(userId: String, spec: CursorSpec, server: Map<String, String?>): Int {
        val local = store.rowStamps(spec.table)
        val want = ArrayList<String>()
        for ((id, serverStamp) in server) {
            if (id in local) {
                val serverMs = serverStamp?.let { Time.parseMillis(it) } ?: continue
                val localMs = local[id]?.let { Time.parseMillis(it) } ?: continue
                if (serverMs <= localMs) continue
            }
            if (store.latestPendingUpsert(spec.table, id) != null || store.hasPendingDelete(spec.table, id)) continue
            want += id
        }
        if (want.isEmpty()) return 0
        var taken = 0
        try {
            for (chunk in want.take(MAX_REPAIR_ROWS).chunked(REPAIR_CHUNK)) {
                for (row in remote.fetchByIds(spec.table, chunk)) {
                    val id = (row["id"] as? JsonPrimitive)?.contentOrNull ?: continue
                    if (store.latestPendingUpsert(spec.table, id) != null || store.hasPendingDelete(spec.table, id)) continue
                    val ok = try {
                        RowApply.apply(spec.table, row, store, userId)
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Throwable) {
                        log("[catchup] ${spec.table} row $id could not be applied by the sweep: $t")
                        false
                    }
                    if (ok) taken++
                }
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            log("[catchup] ${spec.table} repair read failed, retried next sweep: $t")
        }
        return taken
    }

    /** After a FULL hydrate: adopt the server stamps that hydrate actually saw as
     *  the new marks. Called with the Hydrator's own per-table maxima, so the mark
     *  is always a value the server produced — never this device's clock. */
    fun seedCursors(userId: String, serverMaxima: Map<String, String>) {
        for (spec in CURSOR_TABLES) {
            val seed = serverMaxima[spec.table] ?: continue
            cursors.put(userId, spec.cursorKey, seed)
        }
    }

    fun clearCursors(userId: String) = cursors.clear(userId)

    fun hasCursors(userId: String): Boolean = cursors.hasAny(userId)

    companion object {
        const val EPOCH = "1970-01-01T00:00:00Z"
        internal const val PAGE_SIZE = 200
        internal const val MAX_PAGES = 10
        internal const val ID_PAGE_SIZE = 1000
        internal const val MAX_IDS = 20_000
        /** Rows the sweep's repair reads per request (an `in` list of ids) and per
         *  table per sweep; the rest follow on the next sweep. */
        internal const val REPAIR_CHUNK = 50
        internal const val MAX_REPAIR_ROWS = 1_000
        internal const val EXTERNAL_BLOCK_PREFIX = "g_"
        /** How long the live mirror gets to deliver a change before a catch-up
         *  that finds it counts as evidence the channel is deaf. */
        internal const val REALTIME_GRACE_MS = 5_000L

        /** Tables a catch-up can page by a stamp. */
        internal val CURSOR_TABLES: List<CursorSpec> = listOf(
            CursorSpec(Tables.TASKS, "updated_at", CursorKind.EXACT),
            CursorSpec(Tables.COLLECTIONS, "updated_at", CursorKind.EXACT, localStamp = null),
            CursorSpec(Tables.TAGS, "updated_at", CursorKind.EXACT, localStamp = null),
            CursorSpec(Tables.LIFE_AREAS, "updated_at", CursorKind.EXACT, localStamp = null),
            CursorSpec(Tables.PROFILE_FACTS, "updated_at", CursorKind.EXACT),
            CursorSpec(Tables.CALL_REQUESTS, "updated_at", CursorKind.EXACT),
            // Migration 064 gave these three a server-stamped updated_at (default
            // now() on insert; no client sends it) and a touch trigger. Their old
            // cursors — completed_at / at, set by the writer's clock and never
            // moved by an edit — missed sessions logged offline elsewhere and a
            // capture promoted to a task on the web (Android audit 2026-09-23,
            // A11). The local store still stamps them with those columns.
            CursorSpec(Tables.SESSIONS, "updated_at", CursorKind.EXACT, localStamp = "completed_at", cursorKey = "sessions.updated_at"),
            CursorSpec(Tables.CAPTURES, "updated_at", CursorKind.EXACT, localStamp = "at", cursorKey = "captures.updated_at"),
            CursorSpec(Tables.REASON_LOGS, "updated_at", CursorKind.EXACT, localStamp = "at", cursorKey = "reason_logs.updated_at"),
        )

        /** Tables with NO monotonic column at all — a catch-up still has to pull
         *  these the old way (a full replace of that one table). */
        internal val NON_CURSOR_TABLES: List<String> = listOf(Tables.CAL_BLOCKS, Tables.CALENDAR_CONNECTIONS)

        /** Tables the deletion sweep covers: everything the cursor pull owns
         *  (the non-cursor tables are full-replaced, which already drops deletions). */
        internal val RECONCILE_TABLES: List<String> = CURSOR_TABLES.map { it.table }

        internal fun cursorColumn(table: String): String? =
            CURSOR_TABLES.firstOrNull { it.table == table }?.column

        /** The row's stamp, normalised to a `Z`-suffixed instant at the SERVER's
         *  full precision, or null when absent/unparseable.
         *
         *  PostgREST hands back `2026-09-07T11:56:58.473251+00:00`. That `+` is a
         *  space once it is a query-string value, so sending it back verbatim as
         *  `updated_at=gt.…` risks `invalid input syntax for type timestamp`
         *  (observed against production while probing this). `…473251Z` is the
         *  identical instant with no reserved character and NO loss of precision —
         *  rounding to whole milliseconds instead would re-offer the newest row on
         *  every single pass. */
        internal fun stampOf(row: JsonObject, column: String): String? {
            val raw = (row[column] as? JsonPrimitive)?.contentOrNull ?: return null
            return normalizeStamp(raw)
        }

        internal fun normalizeStamp(raw: String): String? =
            runCatching { java.time.OffsetDateTime.parse(raw).toInstant().toString() }.getOrNull()
                ?: runCatching { java.time.Instant.parse(raw).toString() }.getOrNull()
                ?: raw.takeIf { Time.parseMillis(it) != null }

        internal fun instantOf(stamp: String): java.time.Instant? =
            normalizeStamp(stamp)?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }

        /** One microsecond before [stamp] — Postgres' timestamp resolution — so a
         *  strictly-greater-than read returns the rows AT [stamp] too. */
        internal fun justBefore(stamp: String): String =
            instantOf(stamp)?.minusNanos(1_000)?.toString() ?: stamp

        /** The newest stamp in a fetched page, compared as instants (never as
         *  strings: "…+00:00" and "…Z" don't sort together). */
        internal fun maxStamp(rows: List<JsonObject>, column: String): String? =
            rows.mapNotNull { stampOf(it, column) }.maxByOrNull { Time.parseMillis(it) ?: Long.MIN_VALUE }
    }
}
