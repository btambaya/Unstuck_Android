package tech.csalliance.unstuck.ui.sharing

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableLongStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.core.logic.formatMMSS
import tech.csalliance.unstuck.core.model.CoFocusPeer
import tech.csalliance.unstuck.core.model.CoFocusState
import tech.csalliance.unstuck.core.model.CoFocusTimer
import tech.csalliance.unstuck.core.model.coFocusElapsedSec
import tech.csalliance.unstuck.core.model.coFocusPeopleLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

// Co-focus / body-doubling UI — the "Partner" promise made live ("focus
// together"). Two surfaces, one presence channel (sync/CoFocusPresence):
//
//   CoFocusBar       — OWNER side, shown while focusing a partner-shared task.
//                      Joins presence as FOCUSING; renders a calm pill only when a
//                      partner is actually there.
//   PartnerPresence  — RECIPIENT side, inline on a partner "shared with you" row.
//                      Observes whether the owner is focusing right now, and offers
//                      "Sit with them" to appear alongside them (track HERE).

/** Subscribe to a co-focus channel for the composition's lifetime and expose the
 *  other peers. (Re)creates the session on a [taskId]/[active] change; flips [track]
 *  IN PLACE (no teardown), mirroring the web's in-place re-track. Closes on dispose
 *  so no channel leaks past the screen. */
@Composable
fun rememberCoFocusPeers(
    vm: AppViewModel,
    taskId: String?,
    active: Boolean,
    track: CoFocusState?,
    timer: CoFocusTimer? = null,
): List<CoFocusPeer> {
    var peers by remember { mutableStateOf<List<CoFocusPeer>>(emptyList()) }
    var session by remember { mutableStateOf<tech.csalliance.unstuck.sync.CoFocusSession?>(null) }
    val scope = rememberCoroutineScope()

    DisposableEffect(taskId, active) {
        if (active && taskId != null) {
            val s = vm.openCoFocus(taskId, track, timer)
            session = s
            val job = s?.let { sess -> scope.launch { sess.peers.collect { peers = it } } }
            onDispose { job?.cancel(); s?.close(); session = null; peers = emptyList() }
        } else {
            peers = emptyList()
            onDispose { }
        }
    }
    // A track change (observe → here) re-tracks on the SAME channel without a rebuild.
    LaunchedEffect(session, track) { session?.setTrack(track) }
    // A timer change (pause / resume / extend / start) re-tracks in place so a partner's
    // shared view updates live. `timer` is a value class → stable while running, so this
    // fires only on an actual state change, not every tick.
    LaunchedEffect(session, timer) { session?.setTimer(timer) }
    return peers
}

/** OWNER: a calm "someone's here with you" pill while focusing a shared task. Styled
 *  light for the dark focus background. Nothing until a partner is actually present. */
@Composable
fun CoFocusBar(peers: List<CoFocusPeer>, modifier: Modifier = Modifier) {
    if (peers.isEmpty()) return
    val anyFocusing = peers.any { it.state == CoFocusState.FOCUSING }
    val suffix = when {
        peers.size == 1 && anyFocusing -> "is focusing with you"
        peers.size == 1 -> "is here with you"
        else -> "are here with you"
    }
    // The shared timer of a focusing partner (if one is broadcasting a live session), so
    // the owner SEES the same running/paused mm:ss — a shared view, not a remote control.
    val focusingPeer = peers.firstOrNull { it.state == CoFocusState.FOCUSING && it.timer != null }
    Row(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        PulseDot(Color(0xFF5FD08A))
        Text(
            "${coFocusPeopleLabel(peers)} $suffix",
            style = UFont.sans(12, FontWeight.SemiBold),
            color = Color.White.copy(alpha = 0.92f),
        )
        focusingPeer?.timer?.let { SharedTimer(it, onDark = true) }
    }
}

/** A partner's live focus timer, rendered from their broadcast session so both people
 *  see the SAME mm:ss + a "Paused" badge. Ticks locally each second; paused elapsed is
 *  frozen (computed from pausedAtMs) so it holds still. Pure elapsed math lives in core. */
@Composable
private fun SharedTimer(timer: CoFocusTimer, onDark: Boolean) {
    val c = UTheme.colors
    val now = rememberSecondTicker()
    val elapsed = coFocusElapsedSec(timer, now)
    val timeColor = if (onDark) Color.White.copy(alpha = 0.85f) else c.ink2
    Text(formatMMSS(elapsed), style = UFont.mono(12, FontWeight.SemiBold), color = timeColor)
    if (timer.paused) {
        val badgeBg = if (onDark) Color.White.copy(alpha = 0.18f) else c.amberSoft
        val badgeFg = if (onDark) Color.White.copy(alpha = 0.9f) else c.amberInk
        Box(Modifier.clip(RoundedCornerShape(999.dp)).background(badgeBg).padding(horizontal = 7.dp, vertical = 2.dp)) {
            Text("Paused", style = UFont.sans(10, FontWeight.Bold), color = badgeFg)
        }
    }
}

/** A local 1-second clock for the calm shared timer. Cheap; paused peers freeze anyway. */
@Composable
private fun rememberSecondTicker(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    return now
}

/** RECIPIENT: inline presence on a partner "shared with you" row. Shows a live
 *  "focusing now" pulse when the owner is present + a "Sit with them" toggle that
 *  joins presence as HERE (body-doubling).
 *
 *  One-true-shared-session guard: when THIS task is the LIVE co-focus session, the
 *  session-lifetime channel (AppViewModel) already holds topic `cofocus:<taskId>`.
 *  supabase-kt keys its dispatch map by topic (last-subscribed instance wins), so a
 *  second channel here would EVICT the VM's — silently killing remote controls.
 *  This row reuses the VM's peers instead and suppresses its own channel; the "Sit
 *  with them" toggle is moot then (you're IN the session, tracked as FOCUSING). */
@Composable
fun PartnerPresence(vm: AppViewModel, taskId: String, modifier: Modifier = Modifier) {
    val c = UTheme.colors
    var sitting by remember(taskId) { mutableStateOf(false) }
    val live by vm.liveSession.collectAsStateWithLifecycle()
    val liveCoFocus = live?.taskId == taskId && live?.sessionStart != null
    val vmPeers by vm.coFocusPeers.collectAsStateWithLifecycle()
    val ownPeers = rememberCoFocusPeers(vm, taskId, active = !liveCoFocus, track = if (sitting) CoFocusState.HERE else null)
    val peers = if (liveCoFocus) vmPeers else ownPeers
    val focusingPeer = peers.firstOrNull { it.state == CoFocusState.FOCUSING }
    val ownerFocusing = focusingPeer != null

    // Nothing to show until the owner is focusing or you've chosen to sit in.
    if (!ownerFocusing && !sitting) return

    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ownerFocusing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                PulseDot(c.green)
                Text("focusing now", style = UFont.sans(11, FontWeight.SemiBold), color = c.greenInk)
                // The owner's live session, so the recipient sees the SAME mm:ss + Paused.
                focusingPeer?.timer?.let { SharedTimer(it, onDark = false) }
            }
        }
        if (!liveCoFocus) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (sitting) c.primary else c.primarySoft)
                    .clickable(role = Role.Button) { sitting = !sitting }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    Icons.Filled.People, contentDescription = null,
                    tint = if (sitting) Color.White else c.primaryDeep, modifier = Modifier.size(12.dp),
                )
                Text(
                    if (sitting) "Sitting with them" else "Sit with them",
                    style = UFont.sans(11, FontWeight.Bold),
                    color = if (sitting) Color.White else c.primaryDeep,
                )
            }
        }
    }
}

/** A gently pulsing presence dot. */
@Composable
private fun PulseDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "cofocus-pulse")
    val a by transition.animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "cofocus-pulse-alpha",
    )
    Box(Modifier.size(8.dp).alpha(a).clip(RoundedCornerShape(999.dp)).background(color))
}
