package tech.csalliance.unstuck.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

// ─────────────────────────────────────────────────────────────────────────────
// THE FRESHNESS OWNER — the single component that answers "am I in step?" and
// the ONLY thing allowed to decide that a pull is needed.
//
// Everything else reports events to it: the realtime mirror says "a row arrived"
// / "I re-subscribed", the process lifecycle says "visible" / "hidden", the
// network watcher says "we're back", auth says "the token rotated", the worker
// says "run one now". Nothing else schedules a refresh of its own. Before this,
// four different components each ran their own hydrate on their own trigger and
// none of them could tell whether the live path was actually delivering.
//
// Why it can't just trust the socket: postgres_changes has no replay (anything
// written during a gap is never broadcast again) and a channel can report
// SUBSCRIBED while being permanently deaf. So freshness is defined by what this
// client has actually pulled, never by channel status.
// ─────────────────────────────────────────────────────────────────────────────

/** Why a pull was asked for. Recorded so field logs say which trigger is
 *  carrying the load (if it is always FLOOR, the live path is not working). */
enum class FreshnessTrigger {
    /** Sign-in / initial session / cache wipe — the cold path. */
    COLD_START,
    /** The app became visible. */
    FOREGROUND,
    /** Connectivity came back. */
    NETWORK,
    /** The socket (re)connected or a channel (re)subscribed — everything written
     *  during the gap was never broadcast, so it can only come by pull. */
    REALTIME,
    /** The access token rotated: the channel may have (re)joined with a token
     *  RLS would not accept, which is silent. */
    TOKEN_REFRESH,
    /** The floor interval while visible. */
    FLOOR,
    /** Deafness suspected or proven — pull AND rebuild the subscriptions. */
    DEAF,
    /** The periodic background worker. */
    WORKER,
    /** An explicit user/app action that needs current data. */
    MANUAL,
}

/** What the owner knows about this client's freshness. The honest answer to
 *  "am I up to date?" — socket status is deliberately NOT part of it. */
data class FreshnessState(
    val pulling: Boolean = false,
    val lastPullStartedAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
    val lastTrigger: FreshnessTrigger? = null,
    val lastRealtimeEventAt: Long? = null,
    /** Pulls that found rows the live mirror should have delivered. */
    val deafConfirmed: Int = 0,
    /** Windows of visible silence long enough to distrust the socket. */
    val deafSuspected: Int = 0,
    val catchUps: Int = 0,
    val fullHydrates: Int = 0,
    val rowsApplied: Int = 0,
    val rowsDeleted: Int = 0,
)

/**
 * @param currentUserId     the user to pull for, null = nobody (no pull). Suspends:
 *                          a backgrounded process establishes the session first
 *                          (SessionGate — Android audit 2026-09-23, A2).
 * @param runFullHydrate    the existing full server-canonical pull; returns true
 *                          when it completed. Used for the first pull of each
 *                          launch and whenever there is no cursor.
 * @param runCatchUp        the cursor pull; `reconcileDeletions` asks it to also
 *                          sweep ids. Returns null when it could not run.
 * @param needsFullHydrate  "this user has no cursors yet".
 * @param rebuildSubscriptions rebuild the realtime mirror from scratch.
 */
class FreshnessOwner(
    private val scope: CoroutineScope,
    private val currentUserId: suspend () -> String?,
    private val runFullHydrate: suspend (String) -> Boolean,
    private val runCatchUp: suspend (String, Boolean) -> CatchUpOutcome?,
    private val needsFullHydrate: (String) -> Boolean,
    private val rebuildSubscriptions: suspend () -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val floorIntervalMs: Long = FLOOR_INTERVAL_MS,
    private val deafSilenceMs: Long = DEAF_SILENCE_MS,
    private val reconcileIntervalMs: Long = RECONCILE_INTERVAL_MS,
    private val log: (String) -> Unit = { println(it) },
) {
    private val _state = MutableStateFlow(FreshnessState())
    val state: StateFlow<FreshnessState> = _state.asStateFlow()

    // Coalescing: ONE pull at a time. A fire-and-forget trigger that arrives while
    // a pull is running does not start a second one — it marks a FOLLOW-UP, so
    // however many triggers land during a pull, exactly one more pull runs after
    // it. (Dropping them outright would be wrong for the trigger that matters most:
    // a channel that re-subscribes mid-pull went live AFTER that pull's snapshot,
    // so its gap would sit unclosed until the next floor tick.) A caller that needs
    // a pull to have HAPPENED queues behind the running one instead.
    private val mutex = Mutex()
    private val inFlight = AtomicBoolean(false)
    private val followUp = AtomicBoolean(false)
    @Volatile private var followUpTrigger: FreshnessTrigger? = null

    private var floorJob: Job? = null
    @Volatile private var visible = false
    @Volatile private var lastEventAtMs: Long = 0L
    @Volatile private var lastReconcileAtMs: Long = 0L
    /** Start of the current window of "visible with no realtime event". */
    @Volatile private var silenceSinceMs: Long = 0L
    /** The user this owner (one per process) has completed a full hydrate for.
     *  The first pull of each launch is a full server-canonical read even when
     *  cursors survive, as on iOS (FreshnessOwner.hasHydratedThisSession): the
     *  cursors page by stamps some writers set with their own clock, and this
     *  bounds whatever slipped behind them (Android audit 2026-09-23, A11). */
    @Volatile private var hydratedFor: String? = null

    // ── what other components report ────────────────────────────────────────

    /** ANY realtime event, any table. The only thing that proves the socket is
     *  delivering; channel status does not. */
    fun noteRealtimeEvent() {
        val t = now()
        lastEventAtMs = t
        silenceSinceMs = t
        _state.update { it.copy(lastRealtimeEventAt = t) }
    }

    /** The mirror (re)subscribed. Everything written while it was down was never
     *  broadcast, so a pull is the only way to get it. */
    fun noteSubscribed() {
        silenceSinceMs = now()
        request(FreshnessTrigger.REALTIME)
    }

    /** The app became visible: pull now and start the floor interval. */
    fun onVisible() {
        visible = true
        silenceSinceMs = now()
        startFloor()
        request(FreshnessTrigger.FOREGROUND)
    }

    /** The app went away: stop the floor interval (nothing is watching). */
    fun onHidden() {
        visible = false
        floorJob?.cancel()
        floorJob = null
    }

    /** Sign-out: forget everything (the next user starts cold). Stops the floor but
     *  leaves [visible] alone: a sign-out on screen is still on screen, and the DEAF
     *  rule reads it for the next account's session. */
    fun reset() {
        floorJob?.cancel()
        floorJob = null
        lastEventAtMs = 0L
        lastReconcileAtMs = 0L
        silenceSinceMs = 0L
        hydratedFor = null
        _state.value = FreshnessState()
    }

    // ── asking for a pull ───────────────────────────────────────────────────

    /** Fire-and-forget. Collapses into an in-flight pull. */
    fun request(trigger: FreshnessTrigger) {
        scope.launch { pull(trigger, waitIfBusy = false) }
    }

    /** For callers whose next step assumes a pull has actually happened (sign-in,
     *  the worker, connecting a calendar). Queues behind a running pull. True when
     *  this pull completed cleanly; false when nobody was signed in or it failed
     *  (the SyncWorker's Result.retry() — it could never fire while every failure
     *  was swallowed here, Android audit 2026-09-23 A2). */
    suspend fun requestAndWait(trigger: FreshnessTrigger): Boolean = pull(trigger, waitIfBusy = true)

    private suspend fun pull(trigger: FreshnessTrigger, waitIfBusy: Boolean): Boolean {
        val claimed = inFlight.compareAndSet(false, true)
        if (!claimed) {
            if (!waitIfBusy) {
                followUpTrigger = trigger
                followUp.set(true)
                return false
            }
            return mutex.withLock { runOnce(trigger) }
        }
        try {
            val ok = mutex.withLock { runOnce(trigger) }
            // Whatever asked while we were busy gets exactly one more pass — a
            // snapshot taken before their trigger cannot be an answer to it.
            var extra = 0
            while (followUp.compareAndSet(true, false) && extra < MAX_FOLLOW_UPS) {
                extra++
                mutex.withLock { runOnce(followUpTrigger ?: FreshnessTrigger.FLOOR) }
            }
            return ok
        } finally {
            inFlight.set(false)
        }
    }

    /** One pull. True when it completed with every table read. */
    private suspend fun runOnce(trigger: FreshnessTrigger): Boolean {
        val uid = currentUserId() ?: return false
        val startedAt = now()
        _state.update { it.copy(pulling = true, lastPullStartedAt = startedAt, lastTrigger = trigger) }
        try {
            if (hydratedFor != uid || needsFullHydrate(uid)) {
                val ok = runFullHydrate(uid)
                if (ok) hydratedFor = uid
                lastReconcileAtMs = now()   // a full replace reconciles deletions itself
                _state.update {
                    if (ok) it.copy(pulling = false, lastSuccessAt = now(), fullHydrates = it.fullHydrates + 1)
                    else it.copy(pulling = false, lastFailureAt = now())
                }
                return ok
            }
            val sweep = trigger == FreshnessTrigger.COLD_START ||
                trigger == FreshnessTrigger.DEAF ||
                trigger == FreshnessTrigger.WORKER ||
                (now() - lastReconcileAtMs) > reconcileIntervalMs
            val outcome = runCatchUp(uid, sweep)
            if (sweep) lastReconcileAtMs = now()
            if (outcome == null) {
                _state.update { it.copy(pulling = false, lastFailureAt = now()) }
                return false
            }
            _state.update {
                it.copy(
                    pulling = false,
                    lastSuccessAt = if (outcome.failed.isEmpty()) now() else it.lastSuccessAt,
                    lastFailureAt = if (outcome.failed.isEmpty()) it.lastFailureAt else now(),
                    catchUps = it.catchUps + 1,
                    rowsApplied = it.rowsApplied + outcome.appliedCount,
                    rowsDeleted = it.rowsDeleted + outcome.deleted,
                )
            }
            if (outcome.appliedCount > 0 || outcome.deleted > 0) {
                log("[freshness] $trigger caught up ${outcome.appliedCount} row(s), dropped ${outcome.deleted}${if (outcome.blocked.isEmpty()) "" else ", blocked on ${outcome.blocked}"}")
            }
            // DEAFNESS, PROVEN: the pull brought changes this device did not have,
            // old enough that the socket should have delivered them, and the socket
            // delivered nothing while we pulled. Rebuild rather than trust status.
            // Only on screen: in the background the socket is closed on purpose
            // (pauseRealtime), so every remote change arrives only by pull — and now
            // that a backgrounded worker's pull runs (the session gate), a rebuild
            // there opened the websocket and left it up until the next foreground
            // (Android audit 2026-09-23, A2 — second pass). resume() re-subscribes.
            val heardDuringPull = lastEventAtMs >= startedAt
            if (visible && outcome.provenMissed > 0 && !heardDuringPull && trigger != FreshnessTrigger.COLD_START) {
                _state.update { it.copy(deafConfirmed = it.deafConfirmed + 1) }
                log("[freshness] DEAF channel: ${outcome.provenMissed} change(s) arrived only by pull — rebuilding subscriptions (${_state.value.deafConfirmed} so far)")
                rebuildSubscriptions()
                silenceSinceMs = now()
            }
            return outcome.failed.isEmpty()
        } catch (t: CancellationException) {
            _state.update { it.copy(pulling = false) }
            throw t
        } catch (t: Throwable) {
            _state.update { it.copy(pulling = false, lastFailureAt = now()) }
            log("[freshness] pull ($trigger) failed: $t")
            return false
        }
    }

    // ── the floor: a pull every [floorIntervalMs] while visible ─────────────

    private fun startFloor() {
        if (floorJob?.isActive == true) return
        floorJob = scope.launch {
            while (isActive) {
                delay(floorIntervalMs)
                if (!visible) continue
                if (silentTooLong()) {
                    // Nothing has arrived on a socket that claims to be healthy.
                    // Distrust it: pull AND rebuild the subscriptions.
                    _state.update { it.copy(deafSuspected = it.deafSuspected + 1) }
                    log("[freshness] no realtime event for ${deafSilenceMs / 1000}s while visible — pulling and rebuilding (suspected ${_state.value.deafSuspected})")
                    silenceSinceMs = now()
                    pull(FreshnessTrigger.DEAF, waitIfBusy = false)
                    runCatching { rebuildSubscriptions() }
                        .onFailure { if (it is CancellationException) throw it; log("[freshness] rebuild failed: $it") }
                } else {
                    pull(FreshnessTrigger.FLOOR, waitIfBusy = false)
                }
            }
        }
    }

    private fun silentTooLong(): Boolean {
        val since = silenceSinceMs
        return since > 0L && (now() - since) >= deafSilenceMs
    }

    companion object {
        /** The floor pull while visible — the existing mobile figure, kept. */
        const val FLOOR_INTERVAL_MS = 60_000L
        /** Visible silence that makes the socket suspect. Long enough that a
         *  genuinely quiet account doesn't churn its channels. */
        const val DEAF_SILENCE_MS = 10 * 60_000L
        /** How often the deletion sweep runs when nothing forces it. */
        const val RECONCILE_INTERVAL_MS = 5 * 60_000L
        /** Bound on the follow-up chain, so a trigger storm can't pull for ever. */
        internal const val MAX_FOLLOW_UPS = 2
    }
}
