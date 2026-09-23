package tech.csalliance.unstuck.sync

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.csalliance.unstuck.core.logic.externalEventToBlock
import tech.csalliance.unstuck.core.logic.incomingEventsToMirror
import tech.csalliance.unstuck.core.logic.staleExternalBlockIds
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.CalendarConnection
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Google Calendar pull: /connections, then /events for [-7d, +30d], reconciled into
 * local EXTERNAL `g_` blocks. SyncCoordinator owns one and delegates to it; it lives apart
 * so the reconcile runs in tests against a real LocalStore and a fake calendar-sync
 * (parity with iOS build 81, audit 2026-09-22 C18).
 *
 * The server reports per-connection FAILURES (`failures[]`, contract 2026-09) and marks a
 * connection `needs_reauth` on 401 / invalid_grant: a failed connection's missing events
 * are "unknown", never "deleted" — its blocks are kept — and on 429 we back off. (Before,
 * a lapsed refresh token came back as `events: []` and every meeting was reconciled away.)
 *
 * Pulls overlap (sign-in, the worker, "Sync now", the post-connect and post-disconnect
 * pulls) and each reads for seconds, so their answers can land out of order: only an
 * answer newer than everything already applied is written, and a disconnect's local
 * purge ([exclusive]) supersedes every read still in flight. Otherwise an answer read
 * before the revoke put the account's meetings back after the purge. This is iOS's
 * CalendarConnectionsReadGate, checked at write time so it covers the meeting writes
 * too; the reads themselves never hold the lock (C18f).
 *
 * The write phase also holds the catch-up's lock ([hydrateLock]): hydrateCalBlocks
 * snapshots the local g_ rows, then replaces cal_blocks with them, so a pull write
 * landing in between was undone (a purged meeting back, a fresh import dropped) until
 * the next pull.
 */
internal class GoogleCalendarPull(
    private val store: LocalStore,
    private val currentUserId: () -> String?,
    private val listConnections: suspend () -> List<CalendarConnection>,
    private val pullEvents: suspend (from: String, to: String) -> CalendarClient.EventsResponse,
    private val upsertBlock: suspend (CalBlock) -> Unit,
    private val deleteBlock: suspend (String) -> Unit,
    /** SyncCoordinator's hydrateMutex, taken inside [writeLock] (the disconnect's order). */
    private val hydrateLock: Mutex = Mutex(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    // The write phase of each pull, and a disconnect's purge, run one at a time.
    private val writeLock = Mutex()
    private val started = AtomicInteger(0)   // pulls begun (each one's generation)
    private var applied = 0                  // newest generation written or superseded; under writeLock

    // 429 back-off: no pulls or pushes until this instant (the provider / function
    // rate-limited us).
    @Volatile private var backoffUntilMs: Long = 0L

    /** A 429 is being waited out — "Sync now" picks the "Google is busy" caption. */
    val backedOff: Boolean get() = nowMs() < backoffUntilMs

    fun backOff() {
        backoffUntilMs = nowMs() + BACKOFF_MS
        Log.w(TAG, "calendar rate-limited (429) — backing off ${BACKOFF_MS / 1000}s")
    }

    /** Run a local calendar_connections write ([block]) between pulls' writes. Every
     *  pull already reading was answered before it, so none of them is applied after. */
    suspend fun <T> exclusive(block: suspend () -> T): T = writeLock.withLock {
        applied = started.get()
        block()
    }

    /** Run [writes] for the pull of [generation], unless a newer pull or a local write
     *  already covered it (it would put an older state back). */
    private suspend inline fun applyIfNewest(generation: Int, writes: () -> Unit) {
        writeLock.withLock {
            if (generation <= applied) return
            applied = generation
            hydrateLock.withLock { writes() }
        }
    }

    /** Pull and reconcile. [manual] (Sync now, the post-connect / post-disconnect pull)
     *  forgets any 429 back-off first, as iOS pullGoogleCalendar does: an explicit ask
     *  must actually reach Google, not end as a silent no-op. Returns false when
     *  /connections or /events could not be read, on a 429, or when Google answered for
     *  none of the connections, so the bar can say so instead of ending silently
     *  (C18e). True when done, or when there was nothing to do. */
    suspend fun pull(manual: Boolean = false): Boolean {
        val uid = currentUserId() ?: return true
        if (manual) backoffUntilMs = 0L
        if (backedOff) {
            Log.i(TAG, "calendar pull skipped — backing off after a 429")
            return true
        }
        val generation = started.incrementAndGet()
        // Every write below re-checks the user: a sign-out or a switch to another account
        // can land at any suspension (the requests, each write), and the old account's
        // meetings — local-only g_ rows every hydrate keeps — must never land in, or be
        // deleted from, the next user's store (C18g).
        fun switched() = currentUserId() != uid
        val conns = try {
            listConnections()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "calendar listConnections failed", e); return false
        }
        if (switched()) return true
        if (conns.isEmpty()) {
            // No connection left (disconnected on web / iOS): the reconcile below never
            // runs without one, so the imported meetings sat on the grid for good, filling
            // free slots and steering the assistant. Only g_ ids — the Google import,
            // deleted locally, never through the outbox (C18h).
            applyIfNewest(generation) {
                for (b in store.blocks().first()) {
                    if (b.kind != CalBlockKind.EXTERNAL || !b.id.startsWith("g_")) continue
                    if (switched()) return true
                    deleteBlock(b.id)
                }
            }
            return true
        }
        val today = Instant.ofEpochMilli(nowMs()).atZone(zone()).toLocalDate()
        val fromDate = today.minusDays(7)
        val toDate = today.plusDays(30)
        // Google's events.list requires RFC3339 timestamps for timeMin/timeMax — a bare
        // YYYY-MM-DD is rejected (400) and silently yields zero events. Send full instants
        // (like the web's .toISOString()); reconcile locally with the date-only bounds.
        val fromIso = fromDate.atStartOfDay(zone()).toInstant().toString()
        val toIso = toDate.plusDays(1).atStartOfDay(zone()).toInstant().toString()
        val resp = try {
            pullEvents(fromIso, toIso)
        } catch (e: CalendarRateLimited) {
            if (switched()) return true
            backOff(); return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "calendar pullEvents failed", e); return false
        }
        if (switched()) return true
        val failedConnIds = resp.failures.map { it.connectionId }.toSet()
        if (resp.failures.any { it.status == 429 }) backOff()
        // Surface needs_reauth NOW (the bar offers "Reconnect Google") rather than on the
        // next full hydrate: re-read the connection rows the server just stamped.
        val stamped = if (resp.failures.isEmpty()) emptyList() else {
            Log.w(TAG, "calendar pull: ${resp.failures.size} connection(s) failed — keeping their blocks: ${resp.failures}")
            runCatching { listConnections() }.getOrNull().orEmpty()
        }
        applyIfNewest(generation) {
            for (c in stamped) {
                if (switched()) return true
                store.upsert(Tables.CALENDAR_CONNECTIONS, c, CalendarConnection.serializer(), c.id, c.connectedAt)
            }
            // Don't mirror events we pushed ourselves (the originating task block already
            // represents them) nor all-day events (no lane on the time grid yet). The
            // server stamps `allDay: true` — the old `contains('T')` check alone was dead
            // because the server normalises all-day starts to an ISO instant.
            val local = store.blocks().first()
            val ownEventIds = local
                .filter { it.kind == CalBlockKind.TASK && !it.externalEventId.isNullOrBlank() }
                .mapNotNull { it.externalEventId }.toSet()
            val blocks = incomingEventsToMirror(resp.events, ownEventIds).map { externalEventToBlock(it, it.calendarId) }
            val keep = blocks.map { it.id }.toSet()
            // Write only the meetings that changed: every upsert invalidates cal_blocks and
            // re-runs each observer's query, once per meeting per pull (C18i).
            val localById = local.associateBy { it.id }
            for (b in blocks) {
                if (localById[b.id] == b) continue
                if (switched()) return true
                upsertBlock(b)
            }
            // Reconcile deletions: drop in-window EXTERNAL blocks Google no longer returns —
            // EXCEPT for connections whose fetch failed (unknown ≠ deleted).
            for (id in staleExternalBlockIds(store.blocks().first(), keep, fromDate.toString(), toDate.toString(), failedConnIds)) {
                if (switched()) return true
                deleteBlock(id)
            }
        }
        // calendar-sync answers 200 even when Google failed every connection (429 / 5xx /
        // 403 / unreachable), so this used to end on "Synced" with no meetings.
        return !resp.readNothing(conns)
    }

    companion object {
        private const val TAG = "UnstuckSync"
        // After a 429 from the calendar function / provider, no pulls or pushes for this long.
        internal const val BACKOFF_MS = 5 * 60_000L
    }
}
