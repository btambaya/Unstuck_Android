package tech.csalliance.unstuck.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
fun CalendarScreen(vm: AppViewModel, onOpen: (TaskItem) -> Unit, onSearch: () -> Unit, onMenu: () -> Unit) {
    val c = UTheme.colors
    var view by remember { mutableStateOf("Day") }
    Column(Modifier.fillMaxSize()) {
        AppBar(title = "Calendar", leading = Leading.MENU, onLeading = onMenu, onSearch = onSearch)
        Box(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
            MdSegment(listOf("Day", "Week", "Month"), view) { view = it }
        }
        when (view) {
            "Day" -> DayGridScreen(vm, onOpen)
            "Week" -> WeekView(vm)
            else -> MonthView(vm)
        }
    }
}

@Composable
private fun WeekView(vm: AppViewModel) {
    val c = UTheme.colors
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val now = vm.nowMs()
    val days = (0..6).map { Clock.dateIso(Time.addDaysMillis(Time.startOfDayMillis(now), it - 3)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        SectionLabel("Week", color = c.primaryDeep, modifier = Modifier.padding(top = 8.dp))
        Text("Next 7 days", style = UFont.serifItalic(26), color = c.ink, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        days.forEach { d ->
            val dayBlocks = blocks.filter { it.date == d }.sortedBy { it.startTime }
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), radius = 14) {
                Column {
                    Text(d, style = UFont.mono(11, FontWeight.Medium), color = c.ink3)
                    if (dayBlocks.isEmpty()) Text("Open", style = UFont.sans(13), color = c.ink4, modifier = Modifier.padding(top = 4.dp))
                    dayBlocks.forEach { b -> Text("${b.startTime} · ${b.taskName}", style = UFont.sans(13), color = c.ink, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
        Box(Modifier.padding(24.dp)) {}
    }
}

@Composable
private fun MonthView(vm: AppViewModel) {
    val c = UTheme.colors
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val now = vm.nowMs()
    // Density: focus seconds per local day for the last 35 days.
    val byDay = HashMap<String, Int>()
    sessions.forEach { s -> Time.parseMillis(s.completedAt)?.let { byDay[Clock.dateIso(it)] = (byDay[Clock.dateIso(it)] ?: 0) + s.actualSec } }
    val start = Time.addDaysMillis(Time.startOfDayMillis(now), -34)
    val cells = (0..34).map { Clock.dateIso(Time.addDaysMillis(start, it)) }
    val max = (byDay.values.maxOrNull() ?: 1).coerceAtLeast(1)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        SectionLabel("Month", color = c.primaryDeep, modifier = Modifier.padding(top = 8.dp))
        Text("Focus density", style = UFont.serifItalic(26), color = c.ink, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        Card(Modifier.fillMaxWidth(), radius = 18) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        week.forEach { d ->
                            val v = byDay[d] ?: 0
                            val t = (v.toFloat() / max).coerceIn(0f, 1f)
                            val today = d == Clock.todayIso()
                            Box(
                                Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(7.dp)).background(if (today) c.coral else if (v == 0) c.bg2 else lerp(c.bg2, c.primary, 0.2f + 0.6f * t)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(d.takeLast(2).trimStart('0'), style = UFont.sans(11, FontWeight.SemiBold), color = if (today || t > 0.5f) c.bg else c.ink2, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.padding(24.dp)) {}
    }
}
