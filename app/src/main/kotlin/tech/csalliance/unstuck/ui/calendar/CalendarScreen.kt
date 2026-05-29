package tech.csalliance.unstuck.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.blockTimeRange
import tech.csalliance.unstuck.core.logic.isExternalBlock
import tech.csalliance.unstuck.core.logic.isTaskBlock
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.design.component.AreaDot
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.components.EmptyState
import tech.csalliance.unstuck.ui.components.ScreenHeader

@Composable
fun CalendarScreen(vm: AppViewModel, onOpen: (TaskItem) -> Unit) {
    val c = UTheme.colors
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val today = Clock.todayIso()

    // Today + future, grouped by date and time-ordered.
    val grouped = blocks
        .filter { it.date >= today }
        .sortedWith(compareBy({ it.date }, { it.startTime }))
        .groupBy { it.date }

    var grid by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tech.csalliance.unstuck.ui.tasks.SelectableChip("Agenda", selected = !grid) { grid = false }
            tech.csalliance.unstuck.ui.tasks.SelectableChip("Day grid", selected = grid) { grid = true }
        }
        if (grid) {
            DayGridScreen(vm, onOpen)
            return@Column
        }
        ScreenHeader("Calendar", subtitle = "Upcoming blocks")
        if (grouped.isEmpty()) {
            EmptyState("No blocks scheduled. Schedule a task from its detail sheet.")
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                grouped.forEach { (date, dayBlocks) ->
                    item(key = "h_$date") {
                        Text(
                            dayLabel(date, today),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            style = UFont.mono(12, FontWeight.Medium),
                            color = c.ink3,
                        )
                    }
                    dayBlocks.forEach { b ->
                        item(key = b.id) {
                            val isTask = isTaskBlock(b)
                            BlockRow(
                                block = b,
                                onUnschedule = if (isTask) ({ vm.unschedule(b.id) }) else null,
                                onOpen = if (isTask) ({ tasks.firstOrNull { it.id == b.taskId }?.let(onOpen); Unit }) else null,
                            )
                        }
                    }
                }
                item { Text("", Modifier.padding(28.dp)) }
            }
        }
    }
}

@Composable
private fun BlockRow(block: CalBlock, onUnschedule: (() -> Unit)?, onOpen: (() -> Unit)?) {
    val c = UTheme.colors
    val external = isExternalBlock(block)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (external) c.bg2 else c.surface)
            .border(1.dp, c.line, RoundedCornerShape(12.dp))
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AreaDot(if (external) "blue" else "coral")
        Column(Modifier.weight(1f)) {
            Text(block.taskName, style = UFont.sans(15, FontWeight.Medium), color = c.ink)
            Text(blockTimeRange(block), style = UFont.mono(11), color = c.ink3)
        }
        if (onUnschedule != null) {
            Text("Unschedule", style = UFont.sans(12), color = c.coralDeep, modifier = Modifier.clickable(onClick = onUnschedule).padding(6.dp))
        }
    }
}

private fun dayLabel(date: String, today: String): String = if (date == today) "Today · $date" else date
