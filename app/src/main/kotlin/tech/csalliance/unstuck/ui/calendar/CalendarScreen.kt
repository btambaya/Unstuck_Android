package tech.csalliance.unstuck.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.browser.customtabs.CustomTabsIntent
import android.net.Uri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.core.logic.isTaskBlock
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.time.Time
import tech.csalliance.unstuck.design.component.AppBar
import tech.csalliance.unstuck.design.component.Card
import tech.csalliance.unstuck.design.component.Leading
import tech.csalliance.unstuck.design.component.MdSegment
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

@Composable
fun CalendarScreen(vm: AppViewModel, onOpen: (TaskItem) -> Unit, onSearch: () -> Unit, onMenu: () -> Unit, onAvatar: () -> Unit, avatarInitials: String) {
    val c = UTheme.colors
    var view by remember { mutableStateOf("Day") }
    Column(Modifier.fillMaxSize()) {
        AppBar(title = "Calendar", leading = Leading.MENU, onLeading = onMenu, onSearch = onSearch, onAvatar = onAvatar, avatarInitials = avatarInitials)
        Box(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
            MdSegment(listOf("Day", "Week", "Month"), view) { view = it }
        }
        CalendarSyncBar(vm)
        when (view) {
            "Day" -> DayGridScreen(vm, onOpen)
            "Week" -> WeekView(vm, onOpen)
            else -> MonthView(vm)
        }
    }
}

/** Connect / sync / disconnect Google Calendar. Opens consent in a Custom Tab;
 *  the `unstuck://calendar-callback` return is handled in MainActivity. */
@Composable
private fun CalendarSyncBar(vm: AppViewModel) {
    val c = UTheme.colors
    val conns by vm.connections.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (conns.isEmpty()) {
            Box(
                Modifier.clip(RoundedCornerShape(999.dp)).background(c.bg2).clickable(enabled = !busy) {
                    scope.launch {
                        busy = true
                        val url = vm.beginGoogleConnect()
                        busy = false
                        if (url != null) runCatching { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url)) }
                    }
                }.padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text(if (busy) "Connecting…" else "＋ Connect Google Calendar", style = UFont.sans(12, FontWeight.Medium), color = c.ink2) }
        } else {
            Text("Synced · ${conns.first().accountEmail}", style = UFont.sans(12), color = c.ink3, modifier = Modifier.weight(1f))
            Text("Sync now", style = UFont.sans(12, FontWeight.Medium), color = c.primaryDeep, modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable { scope.launch { vm.syncCalendar() } }.padding(horizontal = 8.dp, vertical = 4.dp))
            Text("Disconnect", style = UFont.sans(12), color = c.ink3, modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable { conns.forEach { vm.disconnectCalendar(it.id) } }.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun WeekView(vm: AppViewModel, onOpen: (TaskItem) -> Unit) {
    val c = UTheme.colors
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val areas by vm.lifeAreas.collectAsStateWithLifecycle()
    // Monday-anchored week containing today.
    val today = java.time.LocalDate.now()
    val monday = today.minusDays(((today.dayOfWeek.value + 6) % 7).toLong())
    val days = (0..6).map { monday.plusDays(it.toLong()) }
    val dows = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val plannedByDay = days.map { d -> blocks.filter { it.date == d.toString() && isTaskBlock(it) }.sumOf { it.durationMinutes } }
    val totalPlanned = plannedByDay.sum()
    val busiest = days.getOrNull(plannedByDay.indexOf(plannedByDay.maxOrNull() ?: 0))
    val lightest = days.getOrNull(plannedByDay.indexOf(plannedByDay.minOrNull() ?: 0))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        SectionLabel("This week", color = c.primaryDeep, modifier = Modifier.padding(top = 8.dp))
        Text("${monday.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${monday.dayOfMonth}–${days.last().dayOfMonth}", style = UFont.serifItalic(24), color = c.ink, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RollupStat("Focus planned", if (totalPlanned >= 60) "${totalPlanned / 60}h ${totalPlanned % 60}m" else "${totalPlanned}m", c.primarySoft, c.primaryDeep, Modifier.weight(1f))
            RollupStat("Busiest", busiest?.let { dows[((it.dayOfWeek.value + 6) % 7)] } ?: "—", c.amberSoft, c.amberInk, Modifier.weight(1f))
            RollupStat("Lightest", lightest?.let { dows[((it.dayOfWeek.value + 6) % 7)] } ?: "—", c.greenSoft, c.greenInk, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            days.forEachIndexed { i, d ->
                val isToday = d == today
                val dayBlocks = blocks.filter { it.date == d.toString() }.sortedBy { it.startTime }
                Column(Modifier.width(116.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(dows[i], style = UFont.mono(10, FontWeight.Medium), color = if (isToday) c.coral else c.ink3)
                        Text("${d.dayOfMonth}", style = UFont.sans(15, FontWeight.SemiBold), color = if (isToday) c.coral else c.ink)
                    }
                    if (dayBlocks.isEmpty()) Text("Open", style = UFont.sans(11), color = c.ink4, modifier = Modifier.padding(top = 2.dp))
                    dayBlocks.forEach { b ->
                        val area = if (isTaskBlock(b)) tasks.firstOrNull { it.id == b.taskId }?.lifeArea else null
                        val fill = if (isTaskBlock(b)) c.areaSwatch(tech.csalliance.unstuck.ui.components.areaColorFor(area, areas, c)) else c.bg2
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(fill)
                                .clickable { tasks.firstOrNull { it.id == b.taskId }?.let(onOpen) }.padding(7.dp),
                        ) {
                            Text(b.startTime, style = UFont.mono(9), color = c.ink3)
                            Text(b.taskName, style = UFont.sans(11, FontWeight.Medium), color = c.ink, maxLines = 2)
                        }
                    }
                }
            }
        }
        Box(Modifier.padding(16.dp)) {}
    }
}

@Composable
private fun RollupStat(label: String, value: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(label, style = UFont.mono(9, FontWeight.Medium), color = fg)
        Text(value, style = UFont.sans(14, FontWeight.SemiBold), color = fg, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun MonthView(vm: AppViewModel) {
    val c = UTheme.colors
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    var ym by remember { mutableStateOf(java.time.YearMonth.now()) }
    val byDay = remember(sessions) {
        HashMap<String, Int>().apply {
            sessions.forEach { s -> Time.parseMillis(s.completedAt)?.let { val k = Clock.dateIso(it); put(k, (get(k) ?: 0) + s.actualSec) } }
        }
    }
    val first = ym.atDay(1)
    val lead = (first.dayOfWeek.value + 6) % 7
    val cells: List<java.time.LocalDate?> = List(lead) { null } + (1..ym.lengthOfMonth()).map { ym.atDay(it) }
    val max = (byDay.values.maxOrNull() ?: 1).coerceAtLeast(1)
    val dows = listOf("M", "T", "W", "T", "F", "S", "S")
    val todayIso = Clock.todayIso()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ym.month.name.lowercase().replaceFirstChar { it.uppercase() } + " " + ym.year, style = UFont.serifItalic(24), color = c.ink, modifier = Modifier.weight(1f))
            Text("‹", style = UFont.serifItalic(24), color = c.ink2, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { ym = ym.minusMonths(1) }.padding(horizontal = 10.dp, vertical = 2.dp))
            Text("Today", style = UFont.sans(12, FontWeight.Medium), color = c.primaryDeep, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { ym = java.time.YearMonth.now() }.padding(horizontal = 8.dp, vertical = 4.dp))
            Text("›", style = UFont.serifItalic(24), color = c.ink2, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { ym = ym.plusMonths(1) }.padding(horizontal = 10.dp, vertical = 2.dp))
        }
        Text("Focus density", style = UFont.mono(10, FontWeight.Medium), color = c.ink3, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            dows.forEach { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(it, style = UFont.mono(10), color = c.ink4) } }
        }
        Card(Modifier.fillMaxWidth(), radius = 18) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        week.forEach { d ->
                            if (d == null) {
                                Box(Modifier.weight(1f).aspectRatio(1f))
                            } else {
                                val iso = d.toString()
                                val v = byDay[iso] ?: 0
                                val t = (v.toFloat() / max).coerceIn(0f, 1f)
                                val isToday = iso == todayIso
                                Box(
                                    Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(7.dp)).background(if (isToday) c.coral else if (v == 0) c.bg2 else lerp(c.bg2, c.primary, 0.2f + 0.6f * t)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("${d.dayOfMonth}", style = UFont.sans(11, FontWeight.SemiBold), color = if (isToday || t > 0.5f) c.bg else c.ink2, textAlign = TextAlign.Center)
                                }
                            }
                        }
                        repeat(7 - week.size) { Box(Modifier.weight(1f).aspectRatio(1f)) }
                    }
                }
            }
        }
        Box(Modifier.padding(24.dp)) {}
    }
}
