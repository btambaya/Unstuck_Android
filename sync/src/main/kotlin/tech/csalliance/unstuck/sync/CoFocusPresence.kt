package tech.csalliance.unstuck.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.Presence
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tech.csalliance.unstuck.core.logic.SharedSessionState
import tech.csalliance.unstuck.core.logic.adoptable
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

    /** One-shot probe for a LIVE shared session on this task (join-or-mint's "join"):
     *  join the channel observe-only, announce `hello` (a focuser replies with its full
     *  state), wait up to [timeoutMs] for an ADOPTABLE `timer` message, close, return it
     *  (null = nothing live → the caller mints). Old builds broadcast no sessionId, so
     *  they never produce an adoptable message — mixed versions degrade to mint.
     *  The hello ships a RANDOM id, not [auth]'s: a focuser only replies to a hello
     *  from someone ELSE, so probing with our own uid would silence a same-user
     *  other-device focuser and fork a second session (multi-device adoption). */
    suspend fun probe(taskId: String, timeoutMs: Long = 1_500): SharedSessionState? {
        val myId = auth.currentUserId ?: return null
        val name = auth.currentName ?: "Someone"
        val session = CoFocusSession(
            client, scope, taskId, myId, name, initialTrack = null,
            helloId = tech.csalliance.unstuck.core.logic.newUuid(),
        )
        return try {
            withTimeoutOrNull(timeoutMs) {
                session.controls.first { adoptable(it.state, System.currentTimeMillis()) }.state
            }
        } finally {
            session.close()
        }
    }
}

/** Process-wide per-topic bookkeeping for co-focus channels. supabase-kt 3.0.3's
 *  client keeps ONE dispatch entry per topic with put/remove OVERWRITE semantics:
 *  `channel()` builds a NEW instance every call, subscribe() PUTs it over whatever
 *  held the topic, and removeChannel() unsubscribes + REMOVES BY TOPIC — whichever
 *  instance is registered. Two rules keep that safe with our multiple holders
 *  (session-lifetime VM channel, probe, PartnerPresence rows):
 *   1. open-after-close is SERIALIZED — a new subscribe on a topic awaits the
 *      pending teardown (untrack + removeChannel) of the previous instance, so the
 *      teardown's topic-keyed remove can't drop the new registration;
 *   2. close-after-supersede is a NO-OP on the shared registration — an instance
 *      that is no longer the topic's registered owner (another instance subscribed
 *      after it) must NOT call removeChannel/untrack, or it would evict the LIVE
 *      channel's dispatch entry and presence. */
internal object CoFocusTopics {
    private val lock = Any()
    private val owners = HashMap<String, CoFocusSession>()
    private val teardowns = HashMap<String, Job>()

    /** Mark [s] the topic's registered instance (called right before subscribe). */
    fun claim(topic: String, s: CoFocusSession) = synchronized(lock) { owners[topic] = s }

    /** Is [s] still the topic's registered instance (nobody subscribed after it)? */
    fun isOwner(topic: String, s: CoFocusSession) = synchronized(lock) { owners[topic] === s }

    fun release(topic: String, s: CoFocusSession) {
        synchronized(lock) { if (owners[topic] === s) owners.remove(topic) }
    }

    /** Record the in-flight teardown for [topic]; self-clears on completion. */
    fun setTeardown(topic: String, job: Job) {
        synchronized(lock) { teardowns[topic] = job }
        job.invokeOnCompletion { synchronized(lock) { if (teardowns[topic] === job) teardowns.remove(topic) } }
    }

    fun pendingTeardown(topic: String): Job? = synchronized(lock) { teardowns[topic] }
}

/** A received full-state `timer` control (one-true-shared-session): who sent it (with
 *  their presence display name when known) + the complete shared state. Only messages
 *  that carry a `sessionId` become controls — an old build's timer stays display-only. */
data class CoFocusControl(val userId: String, val name: String?, val state: SharedSessionState)

/** The channel surface the SESSION-LIFETIME owner (AppViewModel) drives — extracted
 *  as an interface purely so unit tests can fake the channel (the concrete
 *  [CoFocusSession] needs a real SupabaseClient + socket). Production code only
 *  ever holds the real implementation. */
interface CoFocusChannel {
    /** The OTHER participants present, focusing-first then longest-present. */
    val peers: StateFlow<List<CoFocusPeer>>
    /** Received `timer` controls carrying a sessionId. */
    val controls: SharedFlow<CoFocusControl>
    /** Channel re-joined after a socket drop — re-exchange on each emission. */
    val rejoins: SharedFlow<Unit>
    /** The socket VISIBLY left CONNECTED (rejoin v2, undetected-drop belt): the
     *  owner marks the live partner-shared session diverged even when no control
     *  send ever failed — criterion 3's offline RUNNER must not be rewound by the
     *  partner's mid-outage pause on rejoin. */
    val socketDrops: SharedFlow<Unit>
    /** Has ≥1 presence sync landed since the LAST rejoin (or observed drop)?
     *  While false, the grace fallback must NOT conclude "alone" — an unsynced
     *  presence map counts as a focusing peer (rejoin v2, rule 4). */
    fun presenceSyncedSinceRejoin(): Boolean
    /** Broadcast the FULL shared state now; returns whether plausibly DELIVERED. */
    suspend fun broadcastShared(state: SharedSessionState): Boolean
    /** Re-announce ourselves with `hello`; [diverged] rides the wire so a focuser
     *  answers even while itself diverged (spec amendments). */
    suspend fun sendHello(diverged: Boolean = false)
    /** Best-effort socket-liveness poke for the foreground re-exchange. */
    suspend fun nudgeSocket()
    fun setSuppressAnnounce(suppress: Boolean)
    fun setSharedCurrent(state: SharedSessionState?)
    fun sharedCurrent(): SharedSessionState?
    fun latestControl(): CoFocusControl?
    fun close()
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
    // The id announced in our `hello`. Defaults to [myId]; a PROBE passes a random
    // uuid so a focuser on ANOTHER DEVICE of the same user still answers (the hello
    // responder ignores hellos from itself by id).
    private val helloId: String = myId,
) : CoFocusChannel {
    // Stable session-join timestamp so re-tracking (a state flip) doesn't reset it.
    private val sinceMs = System.currentTimeMillis()
    private val topic = "cofocus:$taskId"
    private val channel: RealtimeChannel = client.channel(topic) { presence { key = myId } }

    private val present = LinkedHashMap<String, CoFocusPeer>()
    // Latest live-timer BROADCAST per peer (userId → timer). Authoritative for the
    // mutable timer (pause/resume/extend); overlays the presence session. See the
    // file header for why a presence re-track can't carry this.
    private val broadcastTimers = LinkedHashMap<String, CoFocusTimer>()
    private val _peers = MutableStateFlow<List<CoFocusPeer>>(emptyList())
    /** The OTHER participants present, focusing-first then longest-present. */
    override val peers: StateFlow<List<CoFocusPeer>> = _peers.asStateFlow()

    // Full-state control messages received from peers (one-true-shared-session).
    // replay=1 so a probe that attaches a beat after the hello reply still sees it.
    private val _controls = MutableSharedFlow<CoFocusControl>(
        replay = 1, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Received `timer` controls carrying a sessionId — the session-lifetime owner
     *  (AppViewModel) applies them via the pure sharedSessionStep reducer. */
    override val controls: SharedFlow<CoFocusControl> = _controls.asSharedFlow()

    // Fires on every channel REJOIN after the first subscribe. supabase-kt 3.0.3
    // auto-rejoins: a socket drop (receive error / heartbeat timeout) triggers
    // disconnect() + connect(reconnect=true) → rejoinChannels() re-subscribes every
    // registered channel. We watch the SOCKET status (its DISCONNECTED window is
    // ≥ reconnectDelay — the channel's own SUBSCRIBING blip could conflate away on
    // a StateFlow), then await the channel's re-SUBSCRIBED before signalling, so
    // the owner's re-exchange ships over the fresh join. The FIRST connect is our
    // own subscribe — not a rejoin.
    private val _rejoins = MutableSharedFlow<Unit>(
        extraBufferCapacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Channel re-joined after a socket drop — the session-lifetime owner re-exchanges
     *  (hello-ONLY, rejoin v2) on each emission. */
    override val rejoins: SharedFlow<Unit> = _rejoins.asSharedFlow()

    // The socket visibly transitioned off CONNECTED (rejoin v2, undetected-drop
    // belt). Distinct from [rejoins]: this fires at the DROP edge, before any
    // reconnect, so the owner can flag divergence while the outage is live.
    private val _socketDrops = MutableSharedFlow<Unit>(
        extraBufferCapacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** The socket visibly left CONNECTED — the owner marks the live partner-shared
     *  session diverged even without a failed control send. */
    override val socketDrops: SharedFlow<Unit> = _socketDrops.asSharedFlow()

    // Presence-sync tracking (rejoin v2, rule 4): true once ANY presence action
    // (the initial `joins` state event or a diff) lands after the last rejoin —
    // the VM channel always tracks FOCUSING, so even an ALONE client's own join
    // produces one. Cleared at every drop/rejoin edge; while false the grace
    // fallback treats presence as "focusing peer present" (bounded retry) instead
    // of concluding "alone" off a stale pre-drop map.
    @Volatile private var presenceSynced = false

    @Volatile private var track: CoFocusState? = initialTrack
    // The live focus-session timer to broadcast while FOCUSING (null when not focusing).
    @Volatile private var timer: CoFocusTimer? = initialTimer
    // The full shared-session state (one-true-shared-session). When set it supersedes
    // [timer] as the broadcast payload (incl. hello re-announces), so late joiners get
    // the sessionId + LWW cursor and can adopt / order controls.
    @Volatile private var shared: SharedSessionState? = null
    // Offline-divergence gate (docs/shared-session-spec.md, "Offline & reconnect"):
    // while the owner's live session is diverged, the AUTOMATIC re-broadcasts (hello
    // replies / applyTrack) are suppressed — a diverged client must not fight the
    // channel with stale state. Explicit broadcastShared() calls are NOT gated (the
    // owner decides; `ended` and the convergence control must always ship).
    @Volatile private var suppressAnnounce = false
    private var collectJob: Job? = null
    private var broadcastJob: Job? = null
    private var helloJob: Job? = null
    private var rejoinJob: Job? = null
    private var trackJob: Job? = null
    @Volatile private var closed = false

    init {
        // Collect presence diffs BEFORE subscribing (supabase-kt registers the
        // presence listener on collection — same ordering as CollabRealtime). The
        // initial state event arrives as `joins` for everyone already present.
        collectJob = channel.presenceChangeFlow()
            .onEach { action ->
                presenceSynced = true   // a fresh sync landed (rejoin v2, rule 4)
                action.leaves.keys.forEach { if (it != myId) { present.remove(it); broadcastTimers.remove(it) } }
                action.joins.forEach { (key, p) -> if (key != myId) decode(key, p)?.let { present[key] = it } }
                emitPeers()
            }
            .launchIn(scope)
        // A focusing peer's live timer arrives by BROADCAST (reliable per event),
        // NOT a presence re-track. Decoded as a raw JsonObject (not a typed class)
        // so the epoch-ms fields tolerate a DECIMAL — iOS's source is a fractional
        // Double (Date()*1000) that serializes as a JSON decimal, which a strict
        // Long decode would reject and drop the whole timer. doubleOrNull?.toLong()
        // accepts both an integer and a decimal. Overlay onto that peer + re-emit.
        broadcastJob = channel.broadcastFlow<JsonObject>("timer")
            .onEach { obj ->
                val uid = obj["userId"]?.jsonPrimitive?.contentOrNull ?: return@onEach
                val state = decodeSharedTimerMessage(obj)
                if (uid != myId) {
                    val timer = decodeCoFocusTimerMessage(obj)
                    // Display overlay (unchanged from the shared-view era): overlay the
                    // latest broadcast timer onto that peer's presence session. An `ended`
                    // control isn't a running timer — DROP the overlay (a stale display
                    // timer would keep ticking on a session that just finalized).
                    if (state?.ended == true) {
                        if (broadcastTimers.remove(uid) != null) emitPeers()
                    } else if (timer != null) {
                        broadcastTimers[uid] = timer
                        emitPeers()
                    }
                }
                // One-true-shared-session control: a message WITH a sessionId is the
                // session's full shared state — surface it to the session-lifetime
                // owner. Without one (an old build) it stays display-only above.
                // userId == self is a control TOO (the same user's OTHER DEVICE — the
                // LWW/echo cursors make our own echoes no-ops), it is only excluded
                // from the peers display above.
                if (state != null) _controls.tryEmit(CoFocusControl(uid, present[uid]?.name, state))
            }
            .launchIn(scope)
        // A joining peer announces itself with `hello`; a focuser replies with its
        // current timer so a LATE joiner converges — including an observe-only peer
        // that never tracks presence (a presence-join re-announce can't see it).
        // Compared against [helloId], not myId: a probe's random hello id must be
        // answered even by the same user's other device. A hello carrying
        // `diverged: true` (spec amendments) BYPASSES the suppress gate for exactly
        // this reply: a focuser always answers a diverged hello — even while itself
        // diverged — or two mutually-diverged sides deadlock, each waiting for the
        // other's state (safe: a diverged receiver resolves via most-ahead, never
        // plain LWW). Decoded as a raw JsonObject so old builds' {userId}-only
        // hellos keep working.
        helloJob = channel.broadcastFlow<JsonObject>("hello")
            .onEach { obj ->
                val hello = decodeHelloMessage(obj) ?: return@onEach
                if (hello.userId != helloId) broadcastTimer(bypassSuppress = hello.diverged)
            }
            .launchIn(scope)
        // Reconnect detection (see [rejoins]): every socket re-CONNECT after the
        // first is a drop+auto-rejoin. Await the channel's own re-SUBSCRIBED (the
        // SDK's rejoinChannels() has re-sent the join by then) so the re-exchange
        // rides the fresh join, bounded so a failed rejoin can't wedge the signal.
        rejoinJob = scope.launch {
            var everConnected = false
            var prevConnected = false
            channel.realtime.status.collect { st ->
                if (st != Realtime.Status.CONNECTED) {
                    if (prevConnected) {
                        // Undetected-drop belt (rejoin v2): the socket VISIBLY left
                        // CONNECTED (heartbeat timeout / receive error / explicit
                        // disconnect). The pre-drop presence map is stale now, and
                        // any state this side accrues until re-exchange is suspect
                        // — signal the owner so it can flag divergence even though
                        // no control send ever failed (the silent-window sends were
                        // fire-and-forget "delivered").
                        prevConnected = false
                        presenceSynced = false
                        _socketDrops.tryEmit(Unit)
                    }
                    return@collect
                }
                if (everConnected) {
                    // STALE-SUBSCRIBED guard: after a silent drop the channel's
                    // status StateFlow still holds the PRE-DROP `SUBSCRIBED` —
                    // rejoinChannels() only flips it to SUBSCRIBING a beat after
                    // the socket reports CONNECTED. Awaiting SUBSCRIBED directly
                    // could return INSTANTLY on that stale value and ship the
                    // re-exchange onto a not-yet-joined topic (dropped server-
                    // side). Observe the SUBSCRIBING dip FIRST, then await the
                    // fresh SUBSCRIBED.
                    val dipped = withTimeoutOrNull(REJOIN_DIP_WAIT_MS) {
                        channel.status.first { it != RealtimeChannel.Status.SUBSCRIBED }
                    } != null
                    withTimeoutOrNull(REJOIN_SUBSCRIBE_WAIT_MS) {
                        channel.status.first { it == RealtimeChannel.Status.SUBSCRIBED }
                    }
                    // A rejoin resets the presence-sync marker (rejoin v2, rule 4):
                    // "alone" may only be concluded from a map synced AFTER this
                    // point. A fresh state event racing in just before is re-earned
                    // within a beat (bounded grace retry absorbs the blink).
                    presenceSynced = false
                    _rejoins.tryEmit(Unit)
                    if (!dipped) {
                        // The dip was never observed (missed via StateFlow
                        // conflation, or the signal fired on the stale value):
                        // re-signal once after a short delay so the re-exchange
                        // also rides the REAL fresh join. The re-exchange is
                        // idempotent (hello-only), so a duplicate is harmless.
                        scope.launch {
                            delay(REJOIN_RETRY_DELAY_MS)
                            if (!closed) _rejoins.tryEmit(Unit)
                        }
                    }
                }
                everConnected = true
                prevConnected = true
            }
        }
        trackJob = scope.launch {
            // Serialize open-after-close on the SAME topic: the previous instance's
            // teardown removes the topic's dispatch entry (keyed by topic, not
            // instance) — subscribing before it completes would let that remove drop
            // OUR fresh registration. Await it, then claim the topic. (join() is
            // deliberately unwrapped: cancellation must abort, not fall through.)
            CoFocusTopics.pendingTeardown(topic)?.join()
            if (closed) return@launch
            CoFocusTopics.claim(topic, this@CoFocusSession)
            // Positional `true` == blockUntilSubscribed: wait for SUBSCRIBED, then track
            // (the web tracks inside the subscribe 'SUBSCRIBED' callback).
            runCatching { channel.subscribe(true) }
                .onFailure { println("[cofocus] subscribe failed: $it") }
            // Announce ourselves so any focuser re-broadcasts its timer to us
            // (works whether or not we track presence). Carries the CURRENT
            // suppress mirror as `diverged` — a channel opened mid-diverged
            // (process restart) must already ask with the flag set.
            runCatching { channel.broadcast("hello", helloJson(helloId, suppressAnnounce)) }
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

    /** Refresh the session's CURRENT shared state WITHOUT broadcasting — after applying
     *  a remote control (re-announcing it would echo), and on channel open, so a later
     *  `hello` reply carries the authoritative full state. Mirrors into [timer] so the
     *  presence-carried initial render stays in step. */
    override fun setSharedCurrent(state: SharedSessionState?) {
        shared = state
        if (state != null) {
            timer = CoFocusTimer(state.sessionStartMs, state.paused, state.pausedAtMs, state.estimateMin)
        }
    }

    /** Broadcast the FULL shared-session state now (a local control, the terminal
     *  `ended`, or a convergence control). Bypasses the track==FOCUSING gate so
     *  `ended` still ships while tearing down, and is NOT gated by the diverged
     *  suppress flag (the owner decides what ships).
     *
     *  Returns whether the message was plausibly DELIVERED — the owner marks the live
     *  session diverged on false. supabase-kt 3.0.3 has two send paths:
     *   - channel SUBSCRIBED → a raw websocket send (`ws?.send`): after a silent
     *     socket drop the channel status STAYS "subscribed" while `ws` is null, and
     *     the send silently no-ops — so a ws-path send only counts as delivered when
     *     the socket still reports CONNECTED;
     *   - channel NOT subscribed → the SDK falls back to the HTTP broadcast API,
     *     which genuinely delivers on success and THROWS offline / on a non-2xx.
     *  A half-dead TCP connection (drop not yet noticed by the ≤15s heartbeat) can
     *  still swallow a "delivered" send — the reconnect re-exchange covers that
     *  window (both sides re-announce on rejoin and converge). */
    override suspend fun broadcastShared(state: SharedSessionState): Boolean {
        if (closed) return false
        setSharedCurrent(state)
        val viaSocket = channel.status.value == RealtimeChannel.Status.SUBSCRIBED
        val sent = runCatching { channel.broadcast("timer", sharedTimerJson(myId, state)) }.isSuccess
        return sent && (!viaSocket || channel.realtime.status.value == Realtime.Status.CONNECTED)
    }

    /** Re-announce ourselves with `hello` (any focuser replies with its full state) —
     *  the reconnect/foreground re-exchange solicitation. Unlike the state
     *  re-announce this is NEVER suppressed while diverged: a diverged client still
     *  ASKS for the channel's current state (that reply is what resolves it).
     *  [diverged] rides the wire (spec amendments) so a focuser answers even while
     *  itself diverged — the both-diverged deadlock breaker. */
    override suspend fun sendHello(diverged: Boolean) {
        if (closed) return
        runCatching { channel.broadcast("hello", helloJson(helloId, diverged)) }
    }

    /** Best-effort socket-liveness poke for the FOREGROUND re-exchange: a socket
     *  that died while the app was backgrounded (doze / NAT expiry) can sit
     *  "CONNECTED" until the SDK's ≤15s heartbeat notices, and a re-exchange sent
     *  in that window silently vanishes. Write a no-op `nudge` broadcast onto the
     *  wire (only when the ws path would be taken — CONNECTED + SUBSCRIBED, else
     *  there is nothing to verify): on a detectably-broken connection the failed
     *  write tears the WS session → the SDK's message loop observes the close →
     *  auto-reconnect → rejoin → [rejoins] fires and the owner re-exchanges over
     *  the FRESH join (the "retry after the next CONNECTED edge" path). Every
     *  platform ignores unknown broadcast events (`timer`/`hello` are the only
     *  listeners), so the nudge is invisible to peers. Never throws. */
    override suspend fun nudgeSocket() {
        if (closed) return
        if (channel.realtime.status.value != Realtime.Status.CONNECTED) return
        if (channel.status.value != RealtimeChannel.Status.SUBSCRIBED) return
        runCatching { channel.broadcast("nudge", buildJsonObject { }) }
    }

    /** Gate the AUTOMATIC state re-announces (hello replies / applyTrack) while the
     *  owner's live session is offline-diverged. The owner mirrors the persisted
     *  LiveSession.divergedOffline flag into this on every observation, so a fresh
     *  channel opened mid-diverged (process restart) is gated from the start. */
    override fun setSuppressAnnounce(suppress: Boolean) {
        suppressAnnounce = suppress
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
     *  No-op unless we're focusing with a timer. The full shared state (when set)
     *  supersedes the bare timer so late joiners get the sessionId + LWW cursor.
     *  [bypassSuppress] is set for EXACTLY one caller: the reply to a DIVERGED
     *  hello (spec amendments) — a focuser must answer it even while itself
     *  diverged, or two mutually-diverged sides suppress each other forever. */
    private suspend fun broadcastTimer(bypassSuppress: Boolean = false) {
        if (closed || track != CoFocusState.FOCUSING || (suppressAnnounce && !bypassSuppress)) return
        val st = shared
        if (st != null) {
            runCatching { channel.broadcast("timer", sharedTimerJson(myId, st)) }
            return
        }
        val tm = timer ?: return
        runCatching {
            // Sent as a JsonObject with Long epoch-ms → integer literals on the wire
            // (every platform parses those). Field names match web + iOS byte-for-byte.
            channel.broadcast(
                "timer",
                buildJsonObject {
                    put("userId", myId)
                    put("sessionStartMs", tm.sessionStartMs)
                    put("paused", tm.paused)
                    tm.pausedAtMs?.let { put("pausedAtMs", it) }
                    put("estimateMin", tm.estimateMin)
                },
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

    /** The session's CURRENT shared snapshot (what a hello re-announce would carry) —
     *  lets the session-lifetime owner answer join-or-mint WITHOUT a probe when it
     *  already holds this topic (a probe would evict its dispatch registration). */
    override fun sharedCurrent(): SharedSessionState? = shared

    /** The most recent control this channel received (the replay a late collector —
     *  or the probe-less join-or-mint above — attaches to). */
    override fun latestControl(): CoFocusControl? = _controls.replayCache.lastOrNull()

    /** Whether ≥1 presence sync landed since the last rejoin/drop edge — the grace
     *  fallback's "alone" evidence gate (rejoin v2, rule 4). */
    override fun presenceSyncedSinceRejoin(): Boolean = presenceSynced

    /** Leave: untrack + tear the channel down. Idempotent. If ANOTHER instance has
     *  since subscribed this topic (probe / row / VM overlap), the dispatch entry and
     *  presence are THEIRS now — touching removeChannel/untrack here would evict the
     *  live channel (topic-keyed remove), so a superseded instance only cancels its
     *  jobs and lets its dead server-side join expire. */
    override fun close() {
        if (closed) return
        closed = true
        collectJob?.cancel(); collectJob = null
        broadcastJob?.cancel(); broadcastJob = null
        helloJob?.cancel(); helloJob = null
        rejoinJob?.cancel(); rejoinJob = null
        trackJob?.cancel(); trackJob = null
        broadcastTimers.clear()
        shared = null
        _peers.value = emptyList()
        if (!CoFocusTopics.isOwner(topic, this)) return
        val teardown = scope.launch {
            runCatching { channel.untrack() }
            runCatching { channel.realtime.removeChannel(channel) }
            CoFocusTopics.release(topic, this@CoFocusSession)
        }
        // Registered synchronously so an open() issued right after close() awaits it.
        CoFocusTopics.setTeardown(topic, teardown)
    }

    private fun decode(key: String, p: Presence): CoFocusPeer? {
        val st = p.state
        val userId = st["userId"]?.jsonPrimitive?.contentOrNull ?: key
        val name = st["name"]?.jsonPrimitive?.contentOrNull ?: "Someone"
        val state = CoFocusState.fromWire(st["state"]?.jsonPrimitive?.contentOrNull)
        // Epoch-ms via doubleOrNull?.toLong() (not longOrNull) so an iOS peer's
        // fractional-Double wire value (a JSON decimal) still decodes — a strict Long
        // parse returns null and drops the timer. Same tolerance as the broadcast path.
        val since = st["sinceMs"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L
        // A focusing peer carries its live-session timer (sessionStartMs present) so we can
        // render the shared mm:ss. Absent for HERE / observe, or a peer on an older build.
        val startMs = st["sessionStartMs"]?.jsonPrimitive?.doubleOrNull?.toLong()
        val timer = if (state == CoFocusState.FOCUSING && startMs != null) {
            CoFocusTimer(
                sessionStartMs = startMs,
                paused = st["paused"]?.jsonPrimitive?.booleanOrNull ?: false,
                pausedAtMs = st["pausedAtMs"]?.jsonPrimitive?.doubleOrNull?.toLong(),
                estimateMin = st["estimateMin"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 25,
            )
        } else null
        return CoFocusPeer(userId, name, state, since, timer)
    }

    private companion object {
        // Focusing peers first, then by longest-present (ascending join time).
        val PEER_ORDER: Comparator<CoFocusPeer> =
            compareBy({ if (it.state == CoFocusState.FOCUSING) 0 else 1 }, { it.sinceMs })

        // How long a rejoin signal waits for the channel's re-SUBSCRIBED before
        // firing anyway (the re-exchange then rides the SDK's HTTP broadcast
        // fallback, which delivers too). Generous vs the join RTT.
        const val REJOIN_SUBSCRIBE_WAIT_MS = 10_000L
        // How long to wait for the channel status to visibly LEAVE the (possibly
        // stale) SUBSCRIBED after a socket re-CONNECT — rejoinChannels() flips it
        // to SUBSCRIBING within milliseconds of CONNECTED; 2s is generous.
        const val REJOIN_DIP_WAIT_MS = 2_000L
        // When the dip was never observed, how long before the one-shot re-signal
        // (long enough for the SDK's real re-join to have completed).
        const val REJOIN_RETRY_DELAY_MS = 3_000L
    }
}

// ── one-true-shared-session wire codecs (event `timer`) — internal + pure so the
// tolerance rules are unit-testable without a channel. All epoch-ms decode via
// doubleOrNull?.toLong() (NOT longOrNull): iOS's fractional-Double wire values
// serialize as JSON decimals that a strict Long parse would reject, dropping the
// whole message. Missing NEW fields must degrade, never throw (old builds). ──

/** Decode the display timer (the pre-v2 fields). Null when sessionStartMs is absent. */
internal fun decodeCoFocusTimerMessage(obj: JsonObject): CoFocusTimer? {
    val start = obj["sessionStartMs"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: return null
    return CoFocusTimer(
        sessionStartMs = start,
        paused = obj["paused"]?.jsonPrimitive?.booleanOrNull ?: false,
        pausedAtMs = obj["pausedAtMs"]?.jsonPrimitive?.doubleOrNull?.toLong(),
        estimateMin = obj["estimateMin"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 25,
    )
}

/** Decode the FULL shared-session state. Null when the message has no `sessionId`
 *  (an old build → display-only; never adopted or applied as a control). */
internal fun decodeSharedTimerMessage(obj: JsonObject): SharedSessionState? {
    val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull ?: return null
    val start = obj["sessionStartMs"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: return null
    return SharedSessionState(
        sessionId = sessionId,
        sessionStartMs = start,
        paused = obj["paused"]?.jsonPrimitive?.booleanOrNull ?: false,
        pausedAtMs = obj["pausedAtMs"]?.jsonPrimitive?.doubleOrNull?.toLong(),
        estimateMin = obj["estimateMin"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 25,
        rev = obj["rev"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0,
        atMs = obj["atMs"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L,
        ended = obj["ended"]?.jsonPrimitive?.booleanOrNull ?: false,
    )
}

/** The v2 `timer` payload: the pre-v2 display fields PLUS sessionId/rev/atMs/ended.
 *  Hand-built JsonObject (kotlinx default-omission gotcha) with whole-number epoch-ms;
 *  field names match web + iOS byte-for-byte. Old builds ignore the new fields. */
internal fun sharedTimerJson(myId: String, s: SharedSessionState): JsonObject = buildJsonObject {
    put("userId", myId)
    put("sessionStartMs", s.sessionStartMs)
    put("paused", s.paused)
    s.pausedAtMs?.let { put("pausedAtMs", it) }
    put("estimateMin", s.estimateMin)
    put("sessionId", s.sessionId)
    put("rev", s.rev)
    put("atMs", s.atMs)
    put("ended", s.ended)
}

/** A decoded `hello` join-announcement: who joined + whether they are DIVERGED
 *  (spec amendments — a focuser answers a diverged hello even while itself
 *  diverged). Old builds send {userId} only → diverged defaults false. */
internal data class HelloMessage(val userId: String, val diverged: Boolean)

/** The `hello` payload. Hand-built (kotlinx default-omission gotcha): `diverged`
 *  is present ONLY when true, so old builds — which decode a {userId}-shaped
 *  class — never see an unknown field on the common path. Matches web + iOS. */
internal fun helloJson(userId: String, diverged: Boolean): JsonObject = buildJsonObject {
    put("userId", userId)
    if (diverged) put("diverged", true)
}

/** Decode a `hello`. Null when userId is missing (nothing to answer); a missing
 *  or malformed `diverged` degrades to false (old builds / other platforms). */
internal fun decodeHelloMessage(obj: JsonObject): HelloMessage? {
    val userId = obj["userId"]?.jsonPrimitive?.contentOrNull ?: return null
    return HelloMessage(userId, obj["diverged"]?.jsonPrimitive?.booleanOrNull == true)
}
