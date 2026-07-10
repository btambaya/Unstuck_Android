package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.Presence
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import tech.csalliance.unstuck.core.model.CoFocusPeer
import tech.csalliance.unstuck.core.model.CoFocusState

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

/** Factory the coordinator exposes; opens per-task presence sessions with the
 *  current user's identity baked in. */
class CoFocusPresence(
    private val client: SupabaseClient,
    private val auth: AuthService,
    private val scope: CoroutineScope,
) {
    /** Open a presence session on `cofocus:<taskId>` with the given initial [track]
     *  (null = observe only). Returns null when signed out. The caller MUST close()
     *  it when done (a screen closes on dispose). */
    fun open(taskId: String, track: CoFocusState?): CoFocusSession? {
        val myId = auth.currentUserId ?: return null
        val name = auth.currentName ?: "Someone"
        return CoFocusSession(client, scope, taskId, myId, name, track)
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
) {
    // Stable session-join timestamp so re-tracking (a state flip) doesn't reset it.
    private val sinceMs = System.currentTimeMillis()
    private val channel: RealtimeChannel = client.channel("cofocus:$taskId") { presence { key = myId } }

    private val present = LinkedHashMap<String, CoFocusPeer>()
    private val _peers = MutableStateFlow<List<CoFocusPeer>>(emptyList())
    /** The OTHER participants present, focusing-first then longest-present. */
    val peers: StateFlow<List<CoFocusPeer>> = _peers.asStateFlow()

    @Volatile private var track: CoFocusState? = initialTrack
    private var collectJob: Job? = null
    private var trackJob: Job? = null
    @Volatile private var closed = false

    init {
        // Collect presence diffs BEFORE subscribing (supabase-kt registers the
        // presence listener on collection — same ordering as CollabRealtime). The
        // initial state event arrives as `joins` for everyone already present.
        collectJob = channel.presenceChangeFlow()
            .onEach { action ->
                action.leaves.keys.forEach { if (it != myId) present.remove(it) }
                action.joins.forEach { (key, p) -> if (key != myId) decode(key, p)?.let { present[key] = it } }
                _peers.value = present.values.sortedWith(PEER_ORDER)
            }
            .launchIn(scope)
        trackJob = scope.launch {
            // Positional `true` == blockUntilSubscribed: wait for SUBSCRIBED, then track
            // (the web tracks inside the subscribe 'SUBSCRIBED' callback).
            runCatching { channel.subscribe(true) }
                .onFailure { println("[cofocus] subscribe failed: $it") }
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

    private suspend fun applyTrack() {
        if (closed) return
        val t = track
        runCatching {
            if (t != null) {
                channel.track(buildJsonObject {
                    put("userId", myId)
                    put("name", name)
                    put("state", t.wire)
                    put("sinceMs", sinceMs)
                })
            } else {
                channel.untrack()
            }
        }
    }

    /** Leave: untrack + tear the channel down. Idempotent. */
    fun close() {
        if (closed) return
        closed = true
        collectJob?.cancel(); collectJob = null
        trackJob?.cancel(); trackJob = null
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
        return CoFocusPeer(userId, name, state, since)
    }

    private companion object {
        // Focusing peers first, then by longest-present (ascending join time).
        val PEER_ORDER: Comparator<CoFocusPeer> =
            compareBy({ if (it.state == CoFocusState.FOCUSING) 0 else 1 }, { it.sinceMs })
    }
}
