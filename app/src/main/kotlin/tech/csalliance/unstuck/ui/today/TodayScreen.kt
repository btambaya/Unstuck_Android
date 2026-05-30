package tech.csalliance.unstuck.ui.today

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import tech.csalliance.unstuck.core.logic.pickStartNext
import tech.csalliance.unstuck.core.logic.visibleTasks
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
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.components.areaColorFor
import tech.csalliance.unstuck.ui.components.dateEyebrow
import tech.csalliance.unstuck.ui.components.greeting

@Composable
fun TodayScreen(
    vm: AppViewModel,
    onStartFocus: (TaskItem) -> Unit,
    onOpen: (TaskItem) -> Unit,
    onAvatar: () -> Unit,
    onSearch: () -> Unit,
    onInsights: () -> Unit,
) {
    val c = UTheme.colors
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val areas by vm.lifeAreas.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val live by vm.liveSession.collectAsStateWithLifecycle()
    val now = vm.nowMs()
    val liveId = live?.taskId
    var areaFilter by remember { mutableStateOf<String?>(null) }
    var backlogActive by remember { mutableStateOf(false) }

    val startNext = pickStartNext(tasks, blocks, liveId, areaFilter)
    // Today = open tasks scheduled/intended for today, plus anything completed today
    // (sorted last), matching the web today-list which keeps today's completions visible.
    val todayOpen = visibleTasks(TaskListView.TODAY, tasks, blocks, now, activeArea = null, slipMode = false)
    val todayDone = tasks.filter { isCompletedToday(it, now) && todayOpen.none { o -> o.id == it.id } }
    val todayAll = todayOpen + todayDone
    val rows = todayAll.filter { (areaFilter == null || it.lifeArea == areaFilter) && it.id != startNext?.id && it.id != liveId }
    // Backlog view (web parity): the unplanned + overdue stack, area-agnostic.
    val backlogRows = visibleTasks(TaskListView.BACKLOG, tasks, blocks, now, activeArea = null, slipMode = false)
        .filter { it.id != startNext?.id && it.id != liveId }
    val displayRows = if (backlogActive) backlogRows else rows
    val liveTask = liveId?.let { id -> tasks.firstOrNull { it.id == id } }
    val empty = todayAll.isEmpty() && live == null && backlogRows.isEmpty()
    val weekMin = sessions.filter { (now - (it.completedAtMs() ?: 0)) in 0..(7L * 86_400_000) }.sumOf { it.actualSec } / 60

    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Orbit(size = 24)
                Box(Modifier.size(32.dp).clip(CircleShape).background(c.greenSoft).clickable(onClick = onAvatar), contentAlignment = Alignment.Center) {
                    Text("UN", style = UFont.sans(12, FontWeight.SemiBold), color = c.greenInk)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 18.dp)) {
                SectionLabel(dateEyebrow(now), color = c.primaryDeep)
                Text("${greeting(now)}\nUnstuck.", style = UFont.serifItalic(28), color = c.ink, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
                Row(
                    Modifier.padding(top = 2.dp).clip(RoundedCornerShape(999.dp)).background(c.bg2).clickable(onClick = onInsights).padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(c.coral))
                    Text("This week · ", style = UFont.sans(12), color = c.ink2)
                    Text(if (weekMin >= 60) "${weekMin / 60}h focused" else "${weekMin}m focused", style = UFont.sans(12, FontWeight.SemiBold), color = c.ink)
                    Text("→", style = UFont.sans(12), color = c.ink3)
                }
            }
        }

        if (empty) {
            item { EmptyHero(onAdd = onSearch) }
        } else {
            if (startNext != null) item { StartNextHero(startNext) { onStartFocus(startNext) } }
            item {
                Column {
                    Text(if (backlogActive) "Backlog" else "Today", style = UFont.sans(15, FontWeight.SemiBold), color = c.ink, modifier = Modifier.padding(start = 18.dp, top = 22.dp, bottom = 8.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        liveTask, live!!, now,
                        onReturn = { onStartFocus(liveTask) },
                        onPause = { vm.pauseFocus() },
                        onResume = { vm.resumeFocus() },
                    )
                }
            }
            items(displayRows, key = { it.id }) { t -> TaskRow(t, areaColorFor(t.lifeArea, areas, c), ageDays = if (backlogActive) tech.csalliance.unstuck.ui.components.ageDays(t.createdAt, now) else null) { onOpen(t) } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun StartNextHero(task: TaskItem, onStart: () -> Unit) {
    val c = UTheme.colors
    Column(Modifier.padding(horizontal = 18.dp).padding(top = 20.dp)) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(oklch(0.96, 0.04, 280.0), oklch(0.95, 0.05, 320.0)))).padding(18.dp)) {
            Column {
                Row(Modifier.clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.7f)).padding(horizontal = 9.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Bolt, contentDescription = null, tint = c.primaryDeep, modifier = Modifier.size(11.dp))
                    SectionLabel("Start next", color = c.primaryDeep)
                }
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(c.coral))
                    Text("${task.lifeArea ?: "Focus"} · ${task.name}", style = UFont.sans(11, FontWeight.SemiBold), color = c.primaryDeep, maxLines = 1)
                }
                Text(task.name, style = UFont.sans(21, FontWeight.Bold), color = c.ink, modifier = Modifier.padding(top = 6.dp))
                Text("${task.estimateMin} min · Low friction", style = UFont.sans(12), color = c.ink2, modifier = Modifier.padding(top = 6.dp))
                Box(Modifier.padding(top = 14.dp)) { UButton("Focus", kind = ButtonKind.CORAL, leadingIcon = Icons.Filled.PlayArrow, onClick = onStart) }
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
    val elapsed = FocusTimer.elapsedSec(live, now)
    val estimateSec = (task.estimateMin * 60).coerceAtLeast(1)
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
            Text(formatMMSS(elapsed), style = UFont.mono(7, FontWeight.Bold), color = c.ink2)
        }
        Column(Modifier.weight(1f)) {
            Text(if (paused) "Paused · ${task.name}" else "In focus · ${task.name}", style = UFont.sans(13, FontWeight.SemiBold), color = c.ink, maxLines = 1)
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
            )
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                AreaDotColor(areaColor, size = 5)
                Text(task.lifeArea ?: "—", style = UFont.sans(12), color = c.ink3)
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
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(oklch(0.96, 0.04, 280.0), oklch(0.95, 0.05, 320.0)))).padding(vertical = 32.dp, horizontal = 22.dp),
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
