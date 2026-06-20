package tech.csalliance.unstuck.ui.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.FocusTimer
import tech.csalliance.unstuck.core.logic.daysSinceCreated
import tech.csalliance.unstuck.core.logic.formatMMSS
import tech.csalliance.unstuck.core.logic.isCompletedToday
import tech.csalliance.unstuck.core.logic.isTemplate
import tech.csalliance.unstuck.core.logic.pickTodayHero
import tech.csalliance.unstuck.core.logic.projectOccurrences
import tech.csalliance.unstuck.core.logic.visibleTasks
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.model.LiveSession
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.model.TaskListView
import tech.csalliance.unstuck.design.color.oklch
import tech.csalliance.unstuck.design.component.AreaDotColor
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.FilterPill
import tech.csalliance.unstuck.design.component.Orbit
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UnstuckColors
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.components.areaColorFor
import tech.csalliance.unstuck.ui.components.dateEyebrow
import tech.csalliance.unstuck.ui.components.greeting

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TodayScreen(
    vm: AppViewModel,
    onStartFocus: (TaskItem) -> Unit,
    onOpen: (TaskItem) -> Unit,
    onAvatar: () -> Unit,
    onSearch: () -> Unit,
    onInsights: () -> Unit,
    onNotifications: () -> Unit,
    notifUnread: Int,
    onInbox: () -> Unit,
    inboxCount: Int,
) {
    val c = UTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    // Surface a silent failure: if OS notifications are disabled for the app, reminders
    // fire + log in-app but never reach the phone. Re-check on resume (the user may toggle
    // it in system settings and come back).
    var notifsEnabled by remember { mutableStateOf(true) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        notifsEnabled = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val areas by vm.lifeAreas.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val live by vm.liveSession.collectAsStateWithLifecycle()
    val recap by vm.lastRecap.collectAsStateWithLifecycle()
    val nudges by vm.nudges.collectAsStateWithLifecycle()
    // Refresh ~once a minute so the date eyebrow, "today" task filtering and
    // "completed today" roll over at midnight on a screen left open (was captured
    // once at composition → stuck on yesterday until something else recomposed).
    var nowState by remember { mutableLongStateOf(vm.nowMs()) }
    LaunchedEffect(Unit) { while (true) { nowState = vm.nowMs(); kotlinx.coroutines.delay(60_000) } }
    val now = nowState
    val liveId = live?.taskId
    var areaFilter by remember { mutableStateOf<String?>(null) }
    var backlogActive by remember { mutableStateOf(false) }
    val initials = remember(vm.currentName) {
        (vm.currentName ?: "U").split(' ', '.', '@').mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("").ifEmpty { "U" }
    }
    // 1s ticker so the running live-session card timer/ring animate (paused → frozen).
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(live?.id, live?.paused) {
        while (live != null && live?.paused != true) { nowTick = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
    }

    // Start-Next hero — scoped to TODAY (next-scheduled by time → else
    // shortest-estimate → else null so the hero points to the Backlog instead of
    // pulling a backlog task). Excludes the live-focused task + honours the area.
    // Memoized on the bucketing-relevant inputs (coarse 60s `now`, NOT the 1s
    // `nowTick`) so these don't recompute every live-session frame.
    val startNext = remember(tasks, blocks, now, liveId, areaFilter) {
        pickTodayHero(tasks, blocks, now, liveId, areaFilter)
    }
    // Today = open tasks scheduled/intended for today, plus anything completed today
    // (sorted last), matching the web today-list which keeps today's completions visible.
    val todayOpen = remember(tasks, blocks, now) {
        visibleTasks(TaskListView.TODAY, tasks, blocks, now, activeArea = null, slipMode = false)
    }
    // Completed-today = real tasks + today's occurrences (NOT recurring
    // templates), so a ticked-off occurrence stays visible as a win and a done
    // template never leaks in.
    val todayDone = remember(tasks, blocks, now, todayOpen) {
        (tasks.filter { !isTemplate(it) } + projectOccurrences(tasks, blocks, Clock.todayIso()))
            .filter { isCompletedToday(it, now) && todayOpen.none { o -> o.id == it.id } }
    }
    val todayAll = remember(todayOpen, todayDone) { todayOpen + todayDone }
    val rows = remember(todayAll, areaFilter, startNext, liveId) {
        todayAll.filter { (areaFilter == null || it.lifeArea == areaFilter) && it.id != startNext?.id && it.id != liveId }
    }
    // Backlog view (web parity): the unplanned + overdue stack, area-agnostic.
    val backlogAll = remember(tasks, blocks, now) {
        visibleTasks(TaskListView.BACKLOG, tasks, blocks, now, activeArea = null, slipMode = false)
    }
    val backlogRows = remember(backlogAll, startNext, liveId) {
        backlogAll.filter { it.id != startNext?.id && it.id != liveId }
    }
    val displayRows = if (backlogActive) backlogRows else rows
    val liveTask = remember(liveId, tasks) { liveId?.let { id -> tasks.firstOrNull { it.id == id } } }
    // Judge "empty" from the UNFILTERED backlog (+ startNext), not backlogRows:
    // backlogRows has start-next/live subtracted, so a lone overdue task (which
    // becomes the start-next) would otherwise read as empty and hide the Backlog
    // toggle. A genuinely empty account still has no todayAll/backlogAll/startNext.
    val empty = todayAll.isEmpty() && live == null && backlogAll.isEmpty() && startNext == null
    val weekMin = remember(sessions, now) {
        sessions.filter { (now - (it.completedAtMs() ?: 0)) in 0..(7L * 86_400_000) }.sumOf { it.actualSec } / 60
    }

    Column(Modifier.fillMaxWidth()) {
        // ── Pinned header: avatar + bell, greeting, and (when there's content) the
        //    Today/Backlog filters. Only the list below scrolls. ──────────────────
        // top = 8.dp to match the shared AppBar's vertical padding (the other
        // three tabs) so the header icon row sits on the same line across pages.
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Orbit(size = 24)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onInbox), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.MoveToInbox, contentDescription = "Inbox", tint = c.ink2, modifier = Modifier.size(20.dp))
                    if (inboxCount > 0) Box(Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 9.dp).size(7.dp).clip(CircleShape).background(c.coral))
                }
                Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onNotifications), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Notifications, contentDescription = "Notifications", tint = c.ink2, modifier = Modifier.size(20.dp))
                    if (notifUnread > 0) Box(Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 9.dp).size(7.dp).clip(CircleShape).background(c.coral))
                }
                Box(Modifier.size(32.dp).clip(CircleShape).background(c.greenSoft).clickable(onClick = onAvatar), contentAlignment = Alignment.Center) {
                    Text(initials, style = UFont.sans(12, FontWeight.SemiBold), color = c.greenInk)
                }
            }
        }
        Column(Modifier.padding(horizontal = 18.dp)) {
            SectionLabel(dateEyebrow(now), color = c.primaryDeep)
            Text("${greeting(now)}\nUnstuck.", style = UFont.serifItalic(28), color = c.ink, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
            Row(
                Modifier.padding(top = 2.dp, bottom = 4.dp).clip(RoundedCornerShape(999.dp)).background(c.bg2).clickable(onClick = onInsights).padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(c.coral))
                Text("This week · ", style = UFont.sans(12), color = c.ink2)
                Text(if (weekMin >= 60) "${weekMin / 60}h${if (weekMin % 60 != 0) " ${weekMin % 60}m" else ""} focused" else "${weekMin}m focused", style = UFont.sans(12, FontWeight.SemiBold), color = c.ink)
                Text("→", style = UFont.sans(12), color = c.ink3)
            }
        }
        // ── Scrolling content: the Start-Next banner first, then the filter pills
        //    (which stick to the top as you scroll), then the list. ──────────────────
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (!notifsEnabled) {
                item {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(c.amberSoft).clickable {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
                                    )
                                }
                            }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Outlined.Notifications, contentDescription = null, tint = c.amberInk, modifier = Modifier.size(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Notifications are off", style = UFont.sans(13, FontWeight.SemiBold), color = c.amberInk)
                            Text("Reminders won't reach your phone. Tap to turn them on.", style = UFont.sans(12), color = c.amberInk.copy(alpha = 0.85f))
                        }
                        Text("→", style = UFont.sans(14), color = c.amberInk)
                    }
                }
            }
            // Expire the "just now" recap after 6h (web parity) using the existing now ticker.
            recap?.takeIf { now - it.at < 6L * 3600_000 }?.let { r ->
                item {
                    Column(
                        Modifier.padding(horizontal = 18.dp, vertical = 8.dp).clip(RoundedCornerShape(18.dp))
                            .background(c.coralSoft).padding(16.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            SectionLabel("Just now", color = c.coralDeep)
                            Text("✕", style = UFont.sans(13), color = c.ink3, modifier = Modifier.clickable { vm.dismissRecap() })
                        }
                        Text("You did the thing.", style = UFont.serifItalic(22), color = c.ink, modifier = Modifier.padding(top = 4.dp))
                        Text(
                            "${(r.focusedSec / 60).coerceAtLeast(1)} MIN FOCUSED · ${r.taskName}",
                            style = UFont.mono(11), color = c.ink2, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            nudges.firstOrNull()?.let { n ->
                item {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(c.bg2).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(n.title, style = UFont.sans(13), color = c.ink2, maxLines = 2, modifier = Modifier.weight(1f))
                        Text(
                            n.action, style = UFont.sans(12, FontWeight.SemiBold), color = c.primaryDeep,
                            modifier = Modifier.clickable {
                                when (n.kind) {
                                    tech.csalliance.unstuck.ui.NudgeKind.SLIPPING -> tasks.firstOrNull { it.id == n.taskId }?.let(onOpen)
                                    tech.csalliance.unstuck.ui.NudgeKind.CAPTURE -> vm.captures.value.firstOrNull { it.id == n.captureId }?.let { vm.promoteCapture(it) }
                                }
                                vm.dismissNudge(n.id)
                            },
                        )
                        Text("✕", style = UFont.sans(12), color = c.ink3, modifier = Modifier.clickable { vm.dismissNudge(n.id) })
                    }
                }
            }

            if (empty) {
                item { EmptyHero(onAdd = onSearch) }
            } else {
                if (startNext != null) {
                    item { StartNextHero(startNext, onStart = { onStartFocus(startNext) }, onPickAnother = onSearch) }
                } else if (backlogAll.isNotEmpty()) {
                    // Nothing scheduled today — point to the Backlog (don't pull a backlog
                    // task into the hero). Tapping flips the list below to the Backlog.
                    item { BacklogPointerHero(count = backlogAll.size, onOpenBacklog = { backlogActive = true; areaFilter = null }) }
                }
                // Filter pills BELOW the banner; they stick to the top of the list on scroll.
                stickyHeader {
                    Column(Modifier.fillMaxWidth().background(c.bg)) {
                        Text(if (backlogActive) "Backlog" else "Today", style = UFont.sans(15, FontWeight.SemiBold), color = c.ink, modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 8.dp))
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Backlog toggle — amber accent (web parity); entering it clears the area filter.
                            Box(
                                Modifier.clip(RoundedCornerShape(999.dp)).background(if (backlogActive) c.amberSoft else c.bg2).clickable { backlogActive = !backlogActive; if (backlogActive) areaFilter = null }.padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    if (!backlogActive) Box(Modifier.size(6.dp).clip(CircleShape).background(c.amber))
                                    Text("Backlog", style = UFont.sans(12, FontWeight.Medium), color = if (backlogActive) c.amberInk else c.ink2)
                                }
                            }
                            FilterPill("All", !backlogActive && areaFilter == null) { backlogActive = false; areaFilter = null }
                            areas.forEach { a -> FilterPill(a.name, !backlogActive && areaFilter == a.name, dotColor = c.areaColor(a.color)) { backlogActive = false; areaFilter = if (areaFilter == a.name) null else a.name } }
                        }
                    }
                }
                if (liveTask != null && live != null) {
                    item {
                        LiveSessionCard(
                            liveTask, live!!, nowTick,
                            onReturn = { onStartFocus(liveTask) },
                            onPause = { vm.pauseFocus() },
                            onResume = { vm.resumeFocus() },
                        )
                    }
                }
                items(displayRows, key = { it.id }) { t -> TaskRow(t, areaColorFor(t.lifeArea, areas, c), ageDays = if (backlogActive) tech.csalliance.unstuck.ui.components.ageDays(t.createdAt, now) else null) { onOpen(t) } }
                // Per-view empty note: switching to Backlog or an area filter with no
                // matches showed a blank list under the header (looked broken). The live
                // card counts as content, so only show this when nothing else is there.
                if (displayRows.isEmpty() && liveTask == null && (backlogActive || areaFilter != null)) {
                    item {
                        Text(
                            if (backlogActive) "Backlog's clear — nothing waiting." else "Nothing in $areaFilter right now.",
                            style = UFont.sans(13), color = c.ink3,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 28.dp),
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** The Start-Next / empty hero gradient — light lavender→pink in light mode,
 *  a deep indigo→plum in dark mode so the (light) hero text stays legible. */
private fun heroBrush(c: UnstuckColors): Brush =
    if (c.isDark)
        Brush.linearGradient(listOf(oklch(0.34, 0.09, 280.0), oklch(0.30, 0.07, 322.0)))
    else
        Brush.linearGradient(listOf(oklch(0.96, 0.04, 280.0), oklch(0.95, 0.05, 320.0)))

@Composable
private fun StartNextHero(task: TaskItem, onStart: () -> Unit, onPickAnother: () -> Unit) {
    val c = UTheme.colors
    Column(Modifier.padding(horizontal = 18.dp).padding(top = 20.dp)) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(heroBrush(c)).padding(18.dp)) {
            Column {
                Row(Modifier.clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = if (c.isDark) 0.12f else 0.7f)).padding(horizontal = 9.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Bolt, contentDescription = null, tint = c.primaryDeep, modifier = Modifier.size(11.dp))
                    SectionLabel("Start next", color = c.primaryDeep)
                }
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(c.coral))
                    Text("${task.lifeArea ?: "Focus"} · ${task.name}", style = UFont.sans(11, FontWeight.SemiBold), color = c.primaryDeep, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                // Headline = the smallest concrete step (firstPhysicalAction) when set, else
                // the task name — the calming "do this one small thing" framing (web parity).
                Text(task.firstPhysicalAction?.takeIf { it.isNotBlank() } ?: task.name, style = UFont.sans(21, FontWeight.Bold), color = c.ink, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text("${task.estimateMin} min", style = UFont.sans(12), color = c.ink2, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    UButton("Focus", kind = ButtonKind.CORAL, fill = false, leadingIcon = Icons.Filled.PlayArrow, onClick = onStart)
                    Text("Pick another", style = UFont.sans(13, FontWeight.Medium), color = c.primaryDeep, modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable(onClick = onPickAnother).padding(horizontal = 10.dp, vertical = 8.dp))
                }
            }
        }
    }
}

/** Nothing is scheduled today, but the backlog isn't empty — point the user there
 *  instead of pulling a backlog task into the hero. Tapping opens the Backlog list. */
@Composable
private fun BacklogPointerHero(count: Int, onOpenBacklog: () -> Unit) {
    val c = UTheme.colors
    Column(Modifier.padding(horizontal = 18.dp).padding(top = 20.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(heroBrush(c))
                .clickable(onClick = onOpenBacklog).padding(18.dp),
        ) {
            SectionLabel("Nothing scheduled today", color = c.primaryDeep)
            Text("Pick something to start.", style = UFont.sans(21, FontWeight.Bold), color = c.ink, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$count in your backlog", style = UFont.sans(13, FontWeight.SemiBold), color = c.primaryDeep)
                Text("→", style = UFont.sans(13, FontWeight.SemiBold), color = c.primaryDeep)
            }
        }
    }
}

/**
 * The in-progress focus session, surfaced on Today. Branches running vs paused
 * (matching the web LiveTaskRow): running → coral ring + "In focus" + Pause;
 * paused → amber ring + "Paused" + Resume. Tapping the card returns to focus.
 */
@Composable
private fun LiveSessionCard(
    task: TaskItem,
    live: LiveSession,
    now: Long,
    onReturn: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    val c = UTheme.colors
    val paused = live.paused
    // displayedElapsedSec (= this session + priorAccumulatedSec) so a resumed
    // save-for-later session shows the SAME running total as the Focus screen,
    // not just the post-resume slice. Ring uses the session estimate to match.
    val elapsed = FocusTimer.displayedElapsedSec(live, now)
    val estimateSec = ((live.sessionEstimateMin.takeIf { it > 0 } ?: task.estimateMin).coerceAtLeast(1)) * 60
    val progress = (elapsed.toFloat() / estimateSec).coerceIn(0f, 1f)
    val accent = if (paused) c.amber else c.coral
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(c.surface)
            .border(1.dp, if (paused) c.line2 else c.coral.copy(alpha = 0.55f), RoundedCornerShape(14.dp)).clickable(onClick = onReturn).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(30.dp)) {
                val sw = 3.dp.toPx()
                val r = size.minDimension / 2f - sw / 2f
                val cen = Offset(size.width / 2f, size.height / 2f)
                drawArc(c.line, 0f, 360f, false, Offset(cen.x - r, cen.y - r), Size(r * 2, r * 2), style = Stroke(width = sw))
                drawArc(accent, -90f, 360f * progress, false, Offset(cen.x - r, cen.y - r), Size(r * 2, r * 2), style = Stroke(width = sw, cap = StrokeCap.Round))
            }
            // Compact "Hh MM" once past an hour so the label can't overflow the 30dp ring.
            Text(if (elapsed >= 3600) "${elapsed / 3600}h${"%02d".format((elapsed % 3600) / 60)}" else formatMMSS(elapsed), style = UFont.mono(7, FontWeight.Bold), color = c.ink2)
        }
        Column(Modifier.weight(1f)) {
            Text(if (paused) "Paused · ${task.name}" else "In focus · ${task.name}", style = UFont.sans(13, FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(if (paused) "${task.estimateMin}m · paused" else "running for ${formatMMSS(elapsed)}", style = UFont.sans(11), color = c.ink3)
        }
        Box(
            Modifier.clip(RoundedCornerShape(999.dp)).background(if (paused) c.ink else c.bg2)
                .clickable(onClick = if (paused) onResume else onPause).padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(if (paused) "Resume" else "Pause", style = UFont.sans(12, FontWeight.SemiBold), color = if (paused) c.bg else c.ink)
        }
    }
}

@Composable
private fun TaskRow(task: TaskItem, areaColor: Color, ageDays: Int? = null, onOpen: () -> Unit) {
    val c = UTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp)).clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (task.done) {
            Icon(Icons.Filled.CheckCircle, contentDescription = "Done", tint = c.green, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                task.name,
                style = UFont.sans(14, FontWeight.Medium),
                color = if (task.done) c.ink3 else c.ink,
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                AreaDotColor(areaColor, size = 5)
                Text(task.lifeArea ?: "—", style = UFont.sans(12), color = c.ink3)
                // Tags inline on the same line as the area (matches the Tasks list).
                task.tags?.take(3)?.forEach { tn ->
                    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(c.primarySoft).padding(horizontal = 7.dp, vertical = 2.dp)) {
                        Text("#$tn", style = UFont.sans(10, FontWeight.Medium), color = c.primaryDeep)
                    }
                }
            }
        }
        if (ageDays != null) {
            Box(Modifier.clip(RoundedCornerShape(999.dp)).background(c.amberSoft).padding(horizontal = 7.dp, vertical = 2.dp)) {
                Text("${ageDays.coerceAtLeast(1)}d", style = UFont.sans(10, FontWeight.Medium), color = c.amberInk)
            }
        }
        Text("${task.estimateMin}m", style = UFont.mono(11), color = c.ink3)
    }
}

@Composable
private fun EmptyHero(onAdd: () -> Unit) {
    val c = UTheme.colors
    Column(Modifier.padding(horizontal = 18.dp).padding(top = 22.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(heroBrush(c)).padding(vertical = 32.dp, horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Orbit(size = 48)
            SectionLabel("Nothing to start", color = c.primaryDeep)
            Text("You're all clear.", style = UFont.serifItalic(28), color = c.ink)
            Text("Nothing's missing. When something's on your mind, drop it in.", style = UFont.sans(14), color = c.ink2, modifier = Modifier.padding(horizontal = 8.dp))
            Box(Modifier.padding(top = 6.dp)) { UButton("Add one thing", kind = ButtonKind.CORAL, fill = false, onClick = onAdd) }
        }
    }
}

private fun tech.csalliance.unstuck.core.model.Session.completedAtMs(): Long? =
    tech.csalliance.unstuck.core.time.Time.parseMillis(completedAt)
