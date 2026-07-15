package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.Presence
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import tech.csalliance.unstuck.core.model.CoFocusPeer
import tech.csalliance.unstuck.core.model.CoFocusState
import tech.csalliance.unstuck.core.model.CoFocusTimer

// Co-focus / body-doubling presence — the Android port of lib/cofocus-presence.ts,
// the FIRST use of Supabase Realtime PRESENCE in the app (the rest of realtime is
// postgres_changes). Both sides key the channel off the SAME task id (the owner's
// task id, which the recipient also sees via tasks_shared_with_me), so a partner and
// the owner meet on `cofocus:<taskId>`.
//
// `track` controls whether YOU appear to others:
//   FOCUSING — you're in a focus session on this task (owner side).
//   HERE     — you're sitting with them / body-doubling (recipient "Sit with them").
//   null     — observe only: you see who's present but don't broadcast yourself.
//
// supabase-kt gives DIFFS (presenceChangeFlow → joins/leaves) rather than the web's
// full presenceState(), so a session keeps its own `present` map and applies each
// diff. Peers exclude yourself. Idempotent + self-cleaning: close() untracks + tears
// the channel down (Realtime.removeChannel), so nothing leaks past the screen.
//
// PRESENCE carries who's here + identity + the INITIAL focus timer. The MUTABLE
// focus timer (pause / resume / extend) travels by BROADCAST, not a presence
// re-track: Supabase Realtime presence does NOT propagate a metadata update to an
// already-present key — a repeat track() sticks at the first payload on every
// observer (verified against prod), so a partner never saw a pause. Broadcast is
// reliable per event; the observer overlays the latest broadcast timer onto the
// peer's presence session, and the focuser re-announces on a new peer join so
// late joiners converge.

/** Factory the coordinator exposes; opens per-task presence sessions with the
 *  current user's identity baked in. */
class CoFocusPresence(
    private val client: SupabaseClient,
    private val auth: AuthService,
    private val scope: CoroutineScope,
) {
    /** Open a presence session on `cofocus:<taskId>` with the given initial [track]
     *  (null = observe only) and, when focusing, an initial [timer] to broadcast so the
     *  other side sees the SAME live mm:ss. Returns null when signed out. The caller MUST
     *  close() it when done (a screen closes on dispose). */
    fun open(taskId: String, track: CoFocusState?, timer: CoFocusTimer? = null): CoFocusSession? {
        val myId = auth.currentUserId ?: return null
        val name = auth.currentName ?: "Someone"
        return CoFocusSession(client, scope, taskId, myId, name, track, timer)
    }
}

/** One live subscription to a co-focus channel. Exposes the OTHER peers as a
 *  StateFlow; lets the recipient flip observe → here in place without a teardown. */
class CoFocusSession internal constructor(
    client: SupabaseClient,
    private val scope: CoroutineScope,
    taskId: String,
    private val myId: String,
    private val name: String,
    initialTrack: CoFocusState?,
    initialTimer: CoFocusTimer? = null,
) {
    // Stable session-join timestamp so re-tracking (a state flip) doesn't reset it.
    private val sinceMs = System.currentTimeMillis()
    private val channel: RealtimeChannel = client.channel("cofocus:$taskId") { presence { key = myId } }

    private val present = LinkedHashMap<String, CoFocusPeer>()
    // Latest live-timer BROADCAST per peer (userId → timer). Authoritative for the
    // mutable timer (pause/resume/extend); overlays the presence session. See the
    // file header for why a presence re-track can't carry this.
    private val broadcastTimers = LinkedHashMap<String, CoFocusTimer>()
    private val _peers = MutableStateFlow<List<CoFocusPeer>>(emptyList())
    /** The OTHER participants present, focusing-first then longest-present. */
    val peers: StateFlow<List<CoFocusPeer>> = _peers.asStateFlow()

    @Volatile private var track: CoFocusState? = initialTrack
    // The live focus-session timer to broadcast while FOCUSING (null when not focusing).
    @Volatile private var timer: CoFocusTimer? = initialTimer
    private var collectJob: Job? = null
    private var broadcastJob: Job? = null
    private var helloJob: Job? = null
    private var trackJob: Job? = null
    @Volatile private var closed = false

    init {
        // Collect presence diffs BEFORE subscribing (supabase-kt registers the
        // presence listener on collection — same ordering as CollabRealtime). The
        // initial state event arrives as `joins` for everyone already present.
        collectJob = channel.presenceChangeFlow()
            .onEach { action ->
                action.leaves.keys.forEach { if (it != myId) { present.remove(it); broadcastTimers.remove(it) } }
                action.joins.forEach { (key, p) -> if (key != myId) decode(key, p)?.let { present[key] = it } }
                emitPeers()
            }
            .launchIn(scope)
        // A focusing peer's live timer arrives by BROADCAST (reliable per event),
        // NOT a presence re-track. Overlay it onto that peer + re-emit.
        broadcastJob = channel.broadcastFlow<TimerWire>("timer")
            .onEach { wire ->
                val uid = wire.userId ?: return@onEach
                if (uid == myId) return@onEach
                val start = wire.sessionStartMs ?: return@onEach
                broadcastTimers[uid] = CoFocusTimer(
                    sessionStartMs = start,
                    paused = wire.paused ?: false,
                    pausedAtMs = wire.pausedAtMs,
                    estimateMin = wire.estimateMin ?: 25,
                )
                emitPeers()
            }
            .launchIn(scope)
        // A joining peer announces itself with `hello`; a focuser replies with its
        // current timer so a LATE joiner converges — including an observe-only peer
        // that never tracks presence (a presence-join re-announce can't see it).
        helloJob = channel.broadcastFlow<HelloWire>("hello")
            .onEach { wire -> if (wire.userId != myId) broadcastTimer() }
            .launchIn(scope)
        trackJob = scope.launch {
            // Positional `true` == blockUntilSubscribed: wait for SUBSCRIBED, then track
            // (the web tracks inside the subscribe 'SUBSCRIBED' callback).
            runCatching { channel.subscribe(true) }
                .onFailure { println("[cofocus] subscribe failed: $it") }
            // Announce ourselves so any focuser re-broadcasts its timer to us
            // (works whether or not we track presence).
            runCatching { channel.broadcast("hello", HelloWire(userId = myId)) }
            applyTrack()
        }
    }

    /** Flip whether / how I appear (observe → here → focusing) WITHOUT tearing the
     *  channel down — mirrors the web's in-place re-track. */
    fun setTrack(next: CoFocusState?) {
        if (closed || next == track) return
        track = next
        trackJob = scope.launch { applyTrack() }
    }

    /** Update the broadcast focus-session timer (pause / resume / extend / start) and
     *  re-track in place so the partner's shared view stays live. No-op if unchanged. */
    fun setTimer(next: CoFocusTimer?) {
        if (closed || next == timer) return
        timer = next
        trackJob = scope.launch { applyTrack() }
    }

    private suspend fun applyTrack() {
        if (closed) return
        val t = track
        val tm = timer
        runCatching {
            if (t != null) {
                channel.track(buildJsonObject {
                    put("userId", myId)
                    put("name", name)
                    put("state", t.wire)
                    put("sinceMs", sinceMs)
                    // A focusing peer also carries its live session's INITIAL timer so a
                    // fresh joiner renders the SAME running/paused mm:ss immediately
                    // (identical wire fields on web + iOS + Android). Omitted for HERE /
                    // observe. Subsequent pause/resume/extend travel by broadcast below.
                    if (t == CoFocusState.FOCUSING && tm != null) {
                        put("sessionStartMs", tm.sessionStartMs)
                        put("paused", tm.paused)
                        tm.pausedAtMs?.let { put("pausedAtMs", it) }
                        put("estimateMin", tm.estimateMin)
                    }
                })
            } else {
                channel.untrack()
            }
        }
        broadcastTimer()
    }

    /** Broadcast the live focus timer (reliable per event) so an ALREADY-present peer
     *  sees pause/resume/extend — which a presence re-track would silently drop.
     *  No-op unless we're focusing with a timer. */
    private suspend fun broadcastTimer() {
        if (closed || track != CoFocusState.FOCUSING) return
        val tm = timer ?: return
        runCatching {
            channel.broadcast(
                "timer",
                TimerWire(
                    userId = myId,
                    sessionStartMs = tm.sessionStartMs,
                    paused = tm.paused,
                    pausedAtMs = tm.pausedAtMs,
                    estimateMin = tm.estimateMin,
                ),
            )
        }
    }

    /** Emit the OTHER peers, overlaying each focusing peer's latest broadcast timer
     *  (authoritative for pause/resume/extend) onto its presence session. */
    private fun emitPeers() {
        _peers.value = present.values
            .map { peer ->
                val bt = if (peer.state == CoFocusState.FOCUSING) broadcastTimers[peer.userId] else null
                if (bt != null) peer.copy(timer = bt) else peer
            }
            .sortedWith(PEER_ORDER)
    }

    /** Leave: untrack + tear the channel down. Idempotent. */
    fun close() {
        if (closed) return
        closed = true
        collectJob?.cancel(); collectJob = null
        broadcastJob?.cancel(); broadcastJob = null
        helloJob?.cancel(); helloJob = null
        trackJob?.cancel(); trackJob = null
        broadcastTimers.clear()
        _peers.value = emptyList()
        scope.launch {
            runCatching { channel.untrack() }
            runCatching { channel.realtime.removeChannel(channel) }
        }
    }

    private fun decode(key: String, p: Presence): CoFocusPeer? {
        val st = p.state
        val userId = st["userId"]?.jsonPrimitive?.contentOrNull ?: key
        val name = st["name"]?.jsonPrimitive?.contentOrNull ?: "Someone"
        val state = CoFocusState.fromWire(st["state"]?.jsonPrimitive?.contentOrNull)
        val since = st["sinceMs"]?.jsonPrimitive?.longOrNull ?: 0L
        // A focusing peer carries its live-session timer (sessionStartMs present) so we can
        // render the shared mm:ss. Absent for HERE / observe, or a peer on an older build.
        val startMs = st["sessionStartMs"]?.jsonPrimitive?.longOrNull
        val timer = if (state == CoFocusState.FOCUSING && startMs != null) {
            CoFocusTimer(
                sessionStartMs = startMs,
                paused = st["paused"]?.jsonPrimitive?.booleanOrNull ?: false,
                pausedAtMs = st["pausedAtMs"]?.jsonPrimitive?.longOrNull,
                estimateMin = st["estimateMin"]?.jsonPrimitive?.intOrNull ?: 25,
            )
        } else null
        return CoFocusPeer(userId, name, state, since, timer)
    }

    /** The live-timer BROADCAST payload (event `timer`). Same fields as the presence
     *  timer, plus `userId` so the observer keys it. All nullable/defaulted so the
     *  receiver tolerates partial payloads AND kotlinx serializes the set fields even
     *  with encodeDefaults off (the sender passes concrete values, only pausedAtMs is
     *  omitted when null). Field names match web + iOS byte-for-byte. */
    @Serializable
    private data class TimerWire(
        val userId: String? = null,
        val sessionStartMs: Long? = null,
        val paused: Boolean? = null,
        val pausedAtMs: Long? = null,
        val estimateMin: Int? = null,
    )

    /** The `hello` join-announcement payload — just who joined (a focuser replies
     *  with its `timer`, so late joiners converge without a presence re-track). */
    @Serializable
    private data class HelloWire(val userId: String)

    private companion object {
        // Focusing peers first, then by longest-present (ascending join time).
        val PEER_ORDER: Comparator<CoFocusPeer> =
            compareBy({ if (it.state == CoFocusState.FOCUSING) 0 else 1 }, { it.sinceMs })
    }
}
