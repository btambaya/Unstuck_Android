package tech.csalliance.unstuck

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tech.csalliance.unstuck.core.logic.FocusTimer
import tech.csalliance.unstuck.sync.SharedFocusLogResult
import tech.csalliance.unstuck.sync.CircleClient
import java.time.Instant

// SharedFocusLedger — durable retry for log_shared_focus (one-true-shared-session,
// docs/shared-session-spec.md "Accrual — exactly once"). A partner-shared session's
// total_focused accrues EXCLUSIVELY via the ledger RPC, so a finalize that fails
// transiently (offline finish, 5xx, signed-out token refresh) must persist a
// pending record and drain later — with a single fire-and-forget attempt the
// minutes were silently lost. The server dedups per sessionId (PK, on conflict do
// nothing), so retrying a record that actually landed is a no-op — records err on
// the side of being kept.
//
// Drained from UnstuckApp on every process foreground (covers relaunch too).
// NOT_ALLOWED is terminal (the share was revoked mid-session): the OWNER keeps the
// minutes via the durable direct totalFocused bump (outbox-backed upsertTask); a
// recipient's record is dropped (the task isn't theirs to write).
object SharedFocusLedger {

    /** One failed accrual: everything logSharedFocus needs to re-fire it. */
    @Serializable
    data class Pending(val sessionId: String, val taskId: String, val sec: Int, val estimateMin: Int)

    private val json = Json { ignoreUnknownKeys = true }
    // One mutex guards the prefs read-modify-write AND serializes drains (a foreground
    // drain racing a finalize's queue write must not drop either record).
    private val mutex = Mutex()

    /** Fire the ledger RPC; on a TRANSIENT failure persist the record for the next
     *  drain. Returns the RPC result so the caller can apply its own terminal
     *  handling (owner NOT_ALLOWED → direct bump). A null [circle] (signed out /
     *  not configured) queues too — the drain retries once a client exists. */
    suspend fun logOrQueue(
        settings: SettingsStore,
        circle: CircleClient?,
        taskId: String,
        actualSec: Int,
        sessionId: String,
        estimateMin: Int,
    ): SharedFocusLogResult {
        val r = circle?.logSharedFocus(taskId, actualSec, sessionId, estimateMin)
            ?: SharedFocusLogResult.FAILED
        when (r) {
            SharedFocusLogResult.FAILED ->
                mutex.withLock { save(settings, load(settings).filter { it.sessionId != sessionId } + Pending(sessionId, taskId, actualSec, estimateMin)) }
            // Terminal outcomes clear any stale retry for the same session.
            else ->
                mutex.withLock { save(settings, load(settings).filter { it.sessionId != sessionId }) }
        }
        return r
    }

    /** Retry every pending record (foreground / relaunch), idempotent per sessionId.
     *  LOGGED/SKIPPED → done; NOT_ALLOWED → terminal — if the task is in MY store
     *  (owner side) keep the minutes via the durable direct bump; FAILED → keep the
     *  record for the next drain. */
    suspend fun drain(graph: AppGraph) {
        val circle = graph.coordinator?.circle ?: return
        mutex.withLock {
            val pending = load(graph.settings)
            if (pending.isEmpty()) return
            val keep = ArrayList<Pending>(pending.size)
            for (rec in pending) {
                when (circle.logSharedFocus(rec.taskId, rec.sec, rec.sessionId, rec.estimateMin)) {
                    SharedFocusLogResult.LOGGED, SharedFocusLogResult.SKIPPED -> {}
                    SharedFocusLogResult.FAILED -> keep.add(rec)
                    SharedFocusLogResult.NOT_ALLOWED -> {
                        // Share revoked before the retry landed. Owner-only fallback:
                        // the task being in MY store IS the owner test (a recipient's
                        // shared task never is). Same clamp the ledger would apply.
                        val mine = runCatching {
                            graph.store.tasks().first().firstOrNull { it.id == rec.taskId }
                        }.getOrNull()
                        val add = FocusTimer.clampSharedElapsedSec(rec.sec, rec.estimateMin)
                        if (mine != null && add > 0) {
                            runCatching {
                                graph.coordinator?.write?.upsertTask(
                                    mine.copy(totalFocused = mine.totalFocused + add, updatedAt = Instant.now().toString()),
                                )
                            }
                        }
                    }
                }
            }
            save(graph.settings, keep)
        }
    }

    private fun load(settings: SettingsStore): List<Pending> =
        settings.loadPendingSharedFocusRaw()?.let { raw ->
            runCatching { json.decodeFromString<List<Pending>>(raw) }.getOrElse { emptyList() }
        } ?: emptyList()

    private fun save(settings: SettingsStore, records: List<Pending>) {
        settings.savePendingSharedFocusRaw(if (records.isEmpty()) null else json.encodeToString(records))
    }
}
