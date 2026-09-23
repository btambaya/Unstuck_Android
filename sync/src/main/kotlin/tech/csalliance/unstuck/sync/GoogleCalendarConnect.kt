package tech.csalliance.unstuck.sync

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.csalliance.unstuck.core.model.CalendarConnection
import tech.csalliance.unstuck.data.LocalStore
import tech.csalliance.unstuck.data.db.Tables
import java.time.Instant

/** How the in-app Google connect ended. The calendar bar shows nothing for
 *  [CONNECTED]; the other two get the iOS captions. */
enum class CalendarConnectOutcome { CONNECTED, FIRST_SYNC_FAILED, FAILED }

/**
 * The in-app Google connect: consent in a Custom Tab, finished from the
 * `unstuck://calendar-callback` deep link. SyncCoordinator owns one and delegates to it;
 * it lives apart so every way the callback can come back runs in tests (parity with iOS
 * build 81, audit 2026-09-22 C18).
 *
 * The consent tab stops MainActivity, whose ON_STOP sends the app back to Today, so the
 * calendar bar is usually off screen when the callback lands: the [outcome] is HELD until
 * the bar shows it. Every callback reports one, as iOS's GoogleConnectController does for
 * its no-code / state-mismatch / cancelled paths: a failed connect used to show nothing.
 */
internal class GoogleCalendarConnect(
    private val store: LocalStore,
    private val currentUserId: () -> String?,
    private val authorize: suspend () -> CalendarClient.AuthorizeResponse,
    private val exchange: suspend (code: String, state: String) -> CalendarClient.ConnectResponse,
    /** The catch-up and the first Google pull; false = that pull didn't finish. */
    private val firstSync: suspend () -> Boolean,
    /** SyncCoordinator's hydrateMutex: a catch-up replaces calendar_connections whole,
     *  so one that read before the server stored the connection can't drop the seed. */
    private val hydrateLock: Mutex = Mutex(),
    private val nowIso: () -> String = { Instant.now().toString() },
) {
    private var pendingState: String? = null

    private val _outcome = MutableStateFlow<CalendarConnectOutcome?>(null)
    val outcome: StateFlow<CalendarConnectOutcome?> = _outcome
    fun consume() { _outcome.value = null }

    /** Sign-out: the next account never sees this one's result, nor finishes its consent. */
    fun signedOut() {
        pendingState = null
        _outcome.value = null
    }

    /** Start the OAuth consent: returns the Google authorize URL to open in a Custom Tab. */
    suspend fun begin(): String? = runCatching {
        val r = authorize()
        pendingState = r.state
        r.url
    }.onFailure { Log.w(TAG, "calendar authorize failed", it) }.getOrNull()

    /** Finish consent from the callback. [code] is null when the user denied or cancelled
     *  it (Google redirects with `?error=access_denied&state=…`). */
    suspend fun complete(code: String?, state: String?): CalendarConnectOutcome {
        val uid = currentUserId()
        val expected = pendingState
        val outcome = when {
            // CSRF guard: only honor a callback when WE initiated the consent flow AND the
            // returned state matches the one minted for it. A null pendingState means no
            // connect is in flight — an unsolicited deep link (the callback is BROWSABLE,
            // reachable from any web page/app) must never have its code exchanged. It is
            // also what a callback looks like once Android killed the process behind the
            // consent tab (the state lived in memory), so the bar still says it failed.
            expected == null || expected != state -> {
                Log.w(TAG, "calendar connect: no pending consent or state mismatch — not exchanging the code")
                CalendarConnectOutcome.FAILED
            }
            code == null || uid == null -> {
                pendingState = null
                Log.w(TAG, "calendar connect: consent returned no code (denied or cancelled)")
                CalendarConnectOutcome.FAILED
            }
            else -> {
                pendingState = null   // single-use: a replayed deep link can't be honored twice
                try {
                    val conn = exchange(code, expected)
                    seed(uid, conn)
                    if (firstSync()) CalendarConnectOutcome.CONNECTED else CalendarConnectOutcome.FIRST_SYNC_FAILED
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "calendar connect failed", e)
                    CalendarConnectOutcome.FAILED
                }
            }
        }
        // The exchange, catch-up and pull take seconds: a sign-out (or a switch to another
        // account) in that window must not leave this account's result on the next one's
        // bar — the pull's own user check (C18g).
        if (uid != null && currentUserId() == uid) _outcome.value = outcome
        return outcome
    }

    /** Save the new connection locally before the catch-up brings the server's row, so the
     *  bar flips to "Synced" (and offers the "Sync now" the first-sync caption asks for)
     *  and new blocks mirror to Google even when that catch-up fails. Only when the id
     *  isn't stored yet: a reconnect keeps the stored selection (iOS calendarDidConnect). */
    private suspend fun seed(uid: String, conn: CalendarClient.ConnectResponse) {
        hydrateLock.withLock {
            if (currentUserId() != uid) return
            if (store.getOne(Tables.CALENDAR_CONNECTIONS, conn.id, CalendarConnection.serializer()) != null) return
            val row = conn.localConnection(nowIso())
            store.upsert(Tables.CALENDAR_CONNECTIONS, row, CalendarConnection.serializer(), row.id, row.connectedAt)
        }
    }

    private companion object {
        const val TAG = "UnstuckSync"
    }
}
