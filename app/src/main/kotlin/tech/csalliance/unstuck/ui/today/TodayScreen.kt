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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.BuildConfig
import tech.csalliance.unstuck.core.logic.InsightsSpan
import tech.csalliance.unstuck.core.logic.PeriodData
import tech.csalliance.unstuck.core.logic.PeriodWindow
import tech.csalliance.unstuck.core.logic.collectWindow
import tech.csalliance.unstuck.core.logic.insightsRange
import tech.csalliance.unstuck.core.logic.localToday
import tech.csalliance.unstuck.core.logic.periodDur
import tech.csalliance.unstuck.core.logic.periodMinutes
import tech.csalliance.unstuck.core.logic.thisWeekFacts
import tech.csalliance.unstuck.core.logic.FocusTimer
import tech.csalliance.unstuck.core.logic.areaFilterFollowing
import tech.csalliance.unstuck.core.logic.daysSinceCreated
import tech.csalliance.unstuck.core.logic.formatMMSS
import tech.csalliance.unstuck.core.logic.isCompletedToday
import tech.csalliance.unstuck.core.logic.ShareViewMode
import tech.csalliance.unstuck.core.logic.isTemplate
import tech.csalliance.unstuck.core.logic.projectOccurrences
import tech.csalliance.unstuck.core.logic.visibleShares
import tech.csalliance.unstuck.core.logic.visibleTasks
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.model.LiveSession
import tech.csalliance.unstuck.core.model.ShareBadge
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.core.model.SharedWithMe
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.model.TaskListView
import tech.csalliance.unstuck.design.component.AreaDotColor
import tech.csalliance.unstuck.design.component.FilterPill
import tech.csalliance.unstuck.design.component.Orbit
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.assistant.VoiceModeScreen
import tech.csalliance.unstuck.ui.sharing.SharedWithYouSection
import tech.csalliance.unstuck.ui.tour.TourAnchorIds
import tech.csalliance.unstuck.ui.tour.tourAnchor
import tech.csalliance.unstuck.ui.components.areaColorFor
import tech.csalliance.unstuck.ui.components.dateEyebrow
import tech.csalliance.unstuck.ui.components.greetingLine

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TodayScreen(
    vm: AppViewModel,
    onStartFocus: (TaskItem) -> Unit,
    onOpen: (TaskItem) -> Unit,
    onAvatar: () -> Unit,
    onInsights: () -> Unit,
    onNotifications: () -> Unit,
    notifUnread: Int,
    onInbox: () -> Unit,
    inboxCount: Int,
    onOpenShared: (SharedWithMe) -> Unit,
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
    // Talk mode from the assistant input pill's mic — presented here exactly as
    // the Assistant sheet presents it (saveable so a rotation keeps the live
    // session's screen).
    var voiceOpen by rememberSaveable { mutableStateOf(false) }
    if (voiceOpen) VoiceModeScreen(vm) { voiceOpen = false }
    // Privacy §21 kill-switch (Settings → Interface → AI Assistant): with AI off
    // the input pill draws nothing at all (web / iOS parity).
    val settings by vm.settings.collectAsStateWithLifecycle()
    val assistantOn = BuildConfig.ASSISTANT_ENABLED && settings.assistantEnabled
    // Sharing (M2/M3): tasks OTHERS shared with me, badges on MY outgoing shares, and
    // the taskId→assignee map for tasks I assigned away (they leave the active list).
    val sharedWithMe by vm.sharedWithMe.collectAsStateWithLifecycle()
    val shareBadges by vm.shareBadges.collectAsStateWithLifecycle()
    val assignedOut by vm.assignedOut.collectAsStateWithLifecycle()
    // Display name (Settings → Account source) — reactive so the greeting fills in
    // the moment auth hydration lands rather than staying on the fallback.
    val displayName by vm.currentNameState.collectAsStateWithLifecycle()
    // Refresh ~once a minute so the date eyebrow, "today" task filtering and
    // "completed today" roll over at midnight on a screen left open (was captured
    // once at composition → stuck on yesterday until something else recomposed).
    var nowState by remember { mutableLongStateOf(vm.nowMs()) }
    LaunchedEffect(Unit) { while (true) { nowState = vm.nowMs(); kotlinx.coroutines.delay(60_000) } }
    val now = nowState
    val liveId = live?.taskId
    var areaFilter by remember { mutableStateOf<String?>(null) }
    // The pill holds the area NAME: follow a rename (Settings, the assistant, another
    // device) and fall back to All on a delete. After the cascade moved the tasks, a
    // filter left on the old name matched nothing — no pill lit and "Nothing in
    // Personal right now." (parity with iOS build 81, audit 2026-09-22 C19).
    var prevAreas by remember { mutableStateOf(areas) }
    LaunchedEffect(areas) {
        areaFilter = areaFilterFollowing(areaFilter, prevAreas, areas)
        prevAreas = areas
    }
    var backlogActive by remember { mutableStateOf(false) }
    val initials = remember(vm.currentName) {
        (vm.currentName ?: "U").split(' ', '.', '@').mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("").ifEmpty { "U" }
    }
    // 1s ticker so the running live-session card timer/ring animate (paused → frozen).
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(live?.id, live?.paused) {
        while (live != null && live?.paused != true) { nowTick = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
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
    // Tasks I assigned away leave my active list (they collect in Delegated instead).
    val rows = remember(todayAll, areaFilter, liveId, assignedOut) {
        todayAll.filter { (areaFilter == null || it.lifeArea == areaFilter) && it.id != liveId && it.id !in assignedOut }
    }
    // Backlog view (web parity): the unplanned + overdue stack, area-agnostic.
    val backlogAll = remember(tasks, blocks, now) {
        visibleTasks(TaskListView.BACKLOG, tasks, blocks, now, activeArea = null, slipMode = false)
    }
    val backlogRows = remember(backlogAll, liveId, assignedOut) {
        backlogAll.filter { it.id != liveId && it.id !in assignedOut }
    }
    // "Shared with you" follows the SAME rules as my own tasks, placed by the OWNER's
    // next block (migration 052): Today holds shares whose next block is today or that
    // have no plan yet; the Backlog view holds the overdue ones; a completed share
    // leaves immediately (it lives under Tasks → Completed from then on). The group
    // respects the area filter like Delegated does (an area-less share always shows).
    val todayIso = remember(now) { Clock.dateIso(now) }
    val shareMode = if (backlogActive) ShareViewMode.BACKLOG else ShareViewMode.TODAY
    val sharedVisible = remember(sharedWithMe, now, shareMode, areaFilter) {
        visibleShares(sharedWithMe, shareMode, now, todayIso, activeArea = if (backlogActive) null else areaFilter)
    }
    // Delegated group: MY tasks handed off at 'assign'. A completed hand-off lingers
    // today (a quiet "done ✓"), then ages out — mirrors delegated-group.tsx.
    val delegatedRows = remember(tasks, assignedOut, areaFilter, now) {
        tasks.filter { t ->
            assignedOut.containsKey(t.id) &&
                (areaFilter == null || t.lifeArea == areaFilter) &&
                !(t.done && !isCompletedToday(t, now))
        }
    }
    val displayRows = if (backlogActive) backlogRows else rows
    // A live OWN task resolves from the store; a live SHARED-task focus (T3) isn't in
    // my store, so synthesize a minimal display task from the session's shared marker
    // so the running/paused card stays visible + resumable (tapping returns to it).
    val liveTask = remember(liveId, tasks, live) {
        liveId?.let { id -> tasks.firstOrNull { it.id == id } }
            ?: live?.let { l -> l.sharedTitle?.let { title -> TaskItem(id = l.taskId, name = title, estimateMin = l.sessionEstimateMin, createdAt = "", updatedAt = "") } }
    }
    // The week pill — the way into Insights from home, so it ALWAYS shows
    // (Ahmad, 2026-09-24, his Today with no pill: "Where is the insight
    // button??"). THIS week (Monday-anchored, so far) from the shared periodFacts
    // engine over the same rows the Insights page reads — its focus and done
    // are the page's This week Focused and Done (analytics D3). See [weekPill].
    val pillData = remember(tasks, blocks, sessions) { PeriodData(tasks, blocks, sessions, emptyList(), emptyList()) }
    val pill = remember(pillData, now) { weekPill(pillData, now) }

    Column(Modifier.fillMaxWidth()) {
        // ── Pinned header: avatar + bell, greeting, and (when there's content) the
        //    Today/Backlog filters. Only the list below scrolls. ──────────────────
        // top = 8.dp to match the shared AppBar's vertical padding (the other
        // three tabs) so the header icon row sits on the same line across pages.
        // The logo's ring fills the same 32-dp box as the avatar (Orbit draws its
        // ring at ~72 % of its size, so 44 → a 32-dp ring, left to overflow the
        // box's transparent margin), and both sit on the page's 18-dp margins —
        // logo and avatar mirror each other (iOS build 96 parity).
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                Orbit(modifier = Modifier.wrapContentSize(unbounded = true), size = 44)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onInbox), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.MoveToInbox, contentDescription = "Captures", tint = c.ink2, modifier = Modifier.size(20.dp))
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
            // Greet by first name on ONE line ("Good evening Maya.") — display name
            // from the same source Settings → Account reads (reactive, so it fills
            // in once auth hydrates); "Unstuck." when unset (iOS GreetingName.line).
            GreetingLine(greetingLine(now, displayName), modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
            Row(
                Modifier.padding(top = 2.dp, bottom = 4.dp).clip(RoundedCornerShape(999.dp)).background(c.bg2)
                    // Opens Insights on the week the pill names: last week for
                    // "Last week · …", this week otherwise.
                    .clickable(onClickLabel = "Open Insights", role = Role.Button) {
                        vm.openInsightsAt(InsightsSpan.WEEK, if (pill.lastWeek) -1 else 0); onInsights()
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(c.coral))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = c.ink2)) { append(pill.lead) }
                        withStyle(SpanStyle(color = c.ink, fontWeight = FontWeight.SemiBold)) { append(pill.value) }
                        withStyle(SpanStyle(color = c.ink2)) { append(pill.tail) }
                    },
                    style = UFont.sans(12),
                )
                Text("→", style = UFont.sans(12), color = c.ink3, modifier = Modifier.clearAndSetSemantics {})
            }
            // The way into the assistant + Talk: ONE input pill directly under the
            // week pill (it replaced the gateway card — brief / moment / chips /
            // "Personalise your assistant" left the home, 2026-09-17).
            if (assistantOn) AssistantInputPill(vm, onTalk = { voiceOpen = true }, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
        }
        // ── Scrolling content: the filter pills (which stick to the top as you
        //    scroll), then the list. ─────────────────────────────────────────────
        // tourAnchor: the guided tour's today/finish steps ring the list area.
        LazyColumn(Modifier.fillMaxWidth().weight(1f).tourAnchor(TourAnchorIds.TODAY_LIST)) {
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
                            SectionLabel("Just now", color = c.coral)
                            Text("✕", style = UFont.sans(13), color = c.ink3, modifier = Modifier.clickable { vm.dismissRecap() })
                        }
                        // A remote-ended shared session attributes calmly; own finishes celebrate.
                        Text(
                            r.endedBy?.let { "$it ended the session." } ?: "You did the thing.",
                            style = UFont.serifItalic(22), color = c.ink, modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            "${(r.focusedSec / 60).coerceAtLeast(1)} MIN FOCUSED · ${r.taskName}",
                            style = UFont.mono(11), color = c.ink2, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            // The Start-Next hero and its all-clear twin are gone (2026-09-18): the
            // list IS the home. Filter pills first; they stick to the top on scroll.
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
            // Sharing groups sit at the top of the list (web parity). "Shared with
            // you" follows the view — today's + unplanned shares here, the OVERDUE
            // ones in the Backlog view (placed by the owner's next block) — then, on
            // Today only, the tasks I delegated.
            if (sharedVisible.isNotEmpty()) item(key = "shared-with-you") {
                SharedWithYouSection(vm, sharedVisible, shareMode, onToggle = { taskId, done -> vm.completeSharedTask(taskId, done) }, onOpen = onOpenShared, modifier = Modifier.padding(horizontal = 18.dp))
            }
            if (!backlogActive && delegatedRows.isNotEmpty()) item(key = "delegated") {
                DelegatedSection(delegatedRows, assignedOut, onOpen)
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
            items(displayRows, key = { it.id }) { t -> TaskRow(t, areaColorFor(t.lifeArea, areas, c), ageDays = if (backlogActive) tech.csalliance.unstuck.ui.components.ageDays(t.createdAt, now) else null, shareBadges = shareBadges[t.id].orEmpty()) { onOpen(t) } }
            // Per-view empty note: switching to Backlog or an area filter with no
            // matches showed a blank list under the header (looked broken). The live
            // card counts as content, so only show this when nothing else is there.
            if (displayRows.isEmpty() && liveTask == null && sharedVisible.isEmpty() && (backlogActive || areaFilter != null)) {
                item {
                    Text(
                        if (backlogActive) "Backlog's clear — nothing waiting." else "Nothing in $areaFilter right now.",
                        style = UFont.sans(13), color = c.ink3,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 28.dp),
                    )
                }
            } else if (displayRows.isEmpty() && liveTask == null && sharedVisible.isEmpty()) {
                // Plain Today with nothing scheduled (no live card): a quiet prompt
                // rather than a blank list — the all-clear hero is gone (iOS parity).
                item {
                    Text(
                        "Nothing scheduled. Tap + to add.",
                        style = UFont.sans(13), color = c.ink3,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 28.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** The ONE-line greeting ("Good evening Maya."): clamped to a line; a long name
 *  scales the type down (to 70%) before it ellipsises — iOS lineLimit(1) +
 *  minimumScaleFactor(0.7). */
@Composable
private fun GreetingLine(text: String, modifier: Modifier = Modifier) {
    val c = UTheme.colors
    val base = UFont.serifItalic(28)
    var fontSize by remember(text) { mutableStateOf(base.fontSize) }
    Text(
        text, style = base.copy(fontSize = fontSize), color = c.ink, maxLines = 1, softWrap = false,
        overflow = TextOverflow.Ellipsis, modifier = modifier,
        onTextLayout = { r -> if (r.hasVisualOverflow && fontSize.value > 28f * 0.7f) fontSize = (fontSize.value * 0.92f).sp },
    )
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
private fun TaskRow(task: TaskItem, areaColor: Color, ageDays: Int? = null, shareBadges: List<ShareBadge> = emptyList(), onOpen: () -> Unit) {
    val c = UTheme.colors
    // Assign-level shares move a task to the Delegated group, so an active row only
    // ever carries view/partner badges — a quiet "shared with N" chip.
    val visibleBadges = shareBadges.filter { it.level != ShareLevel.ASSIGN }
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
        if (visibleBadges.isNotEmpty()) {
            val label = if (visibleBadges.size == 1) visibleBadges.first().recipientName.substringBefore('@') else "${visibleBadges.size} people"
            Row(
                Modifier.clip(RoundedCornerShape(999.dp)).background(c.primarySoft).padding(horizontal = 7.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(Icons.Filled.Person, contentDescription = null, tint = c.primaryDeep, modifier = Modifier.size(10.dp))
                Text(label, style = UFont.sans(10, FontWeight.Medium), color = c.primaryDeep, maxLines = 1)
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

/** "Delegated" — MY tasks handed off at 'assign'. Once assigned they become the
 *  recipient's to do, so they leave my active list and collect here (I keep view +
 *  their done state; a tap opens the task to change the level or take it back).
 *  Port of delegated-group.tsx. */
@Composable
private fun DelegatedSection(rows: List<TaskItem>, assignedOut: Map<String, String>, onOpen: (TaskItem) -> Unit) {
    val c = UTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 2.dp)) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = c.ink3, modifier = Modifier.size(12.dp))
            SectionLabel("Delegated")
        }
        rows.forEach { t ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).border(1.dp, c.primarySoft, RoundedCornerShape(12.dp)).clickable { onOpen(t) }.padding(horizontal = 13.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        t.name, style = UFont.sans(14, FontWeight.Medium),
                        color = if (t.done) c.ink3 else c.ink,
                        textDecoration = if (t.done) TextDecoration.LineThrough else null,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text("assigned to ${assignedOut[t.id]?.substringBefore('@') ?: "someone"}", style = UFont.sans(12), color = c.ink3, modifier = Modifier.padding(top = 2.dp))
                }
                Box(Modifier.clip(RoundedCornerShape(999.dp)).background(c.primarySoft).padding(horizontal = 9.dp, vertical = 2.dp)) {
                    Text(if (t.done) "done" else "assigned", style = UFont.sans(10, FontWeight.Bold), color = c.primaryDeep)
                }
            }
        }
    }
}

/**
 * What the Today week pill shows. It ALWAYS shows: it is the way into Insights
 * from home, and hidden at zero it left a week with no focus yet no way there
 * (Ahmad, 2026-09-24, his Today with no pill: "Where is the insight button??").
 *  • [Kind.FOCUSED]: focus this week → "This week · 2h 5m focused →";
 *  • [Kind.DONE]: no focus, but tasks done this week → "3 done this week →"
 *    ("1 done this week →") — the Insights page's This week Done, from the
 *    same periodFacts window, so the numbers match;
 *  • [Kind.LAST_WEEK]: nothing yet this week, on a Monday or Tuesday, and last
 *    week had focus → "Last week · 1h 35m focused →", opening Insights on last
 *    week;
 *  • [Kind.EMPTY]: anything else → "Your week →".
 * The words are split where the look changes: [lead] and [tail] in ink2,
 * [value] in ink, semibold.
 */
internal data class WeekPill(val kind: Kind, val minutes: Int = 0, val done: Int = 0) {
    enum class Kind { FOCUSED, DONE, LAST_WEEK, EMPTY }

    val lastWeek: Boolean get() = kind == Kind.LAST_WEEK

    val lead: String get() = when (kind) {
        Kind.FOCUSED -> "This week · "
        Kind.LAST_WEEK -> "Last week · "
        Kind.DONE, Kind.EMPTY -> ""
    }
    val value: String get() = when (kind) {
        Kind.FOCUSED, Kind.LAST_WEEK -> "${periodDur(minutes)} focused"
        Kind.DONE -> "$done done"
        Kind.EMPTY -> "Your week"
    }
    val tail: String get() = if (kind == Kind.DONE) " this week" else ""

    /** The pill's words without the arrow (web's weekPillText; the shared
     *  period-review vectors' weekPill `text`). */
    val words: String get() = "$lead$value$tail"

    /** The whole pill as read: "This week · 2h 5m focused →". */
    val text: String get() = "$words →"
}

internal fun weekPill(data: PeriodData, now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): WeekPill {
    val week = thisWeekFacts(data, now, zone)
    val focused = periodMinutes(week.focusSec)
    if (focused > 0) return WeekPill(WeekPill.Kind.FOCUSED, minutes = focused)
    if (week.doneCount > 0) return WeekPill(WeekPill.Kind.DONE, done = week.doneCount)
    val dow = java.time.Instant.ofEpochMilli(now).atZone(zone).dayOfWeek.value   // 1 = Monday
    if (dow <= 2) {
        val last = insightsRange(InsightsSpan.WEEK, -1, localToday(now, zone), null)
        val sec = collectWindow(data, PeriodWindow(last.from, last.end, null), zone).focusSec
        periodMinutes(sec).takeIf { it > 0 }?.let { return WeekPill(WeekPill.Kind.LAST_WEEK, minutes = it) }
    }
    return WeekPill(WeekPill.Kind.EMPTY)
}
