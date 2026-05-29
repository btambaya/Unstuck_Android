package tech.csalliance.unstuck.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
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
import tech.csalliance.unstuck.core.logic.findFreeSlots
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.design.component.AppBar
import tech.csalliance.unstuck.design.component.AreaDotColor
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.Card
import tech.csalliance.unstuck.design.component.Leading
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.components.areaColorFor

/** Full-screen task detail (mockup 03). */
@Composable
fun TaskDetailScreen(vm: AppViewModel, task: TaskItem, onBack: () -> Unit, onStartFocus: () -> Unit) {
    val c = UTheme.colors
    val areas by vm.lifeAreas.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val captures by vm.captures.collectAsStateWithLifecycle()
    var scheduled by remember(task.id) { mutableStateOf<String?>(null) }
    val taskSessions = sessions.filter { it.taskId == task.id }
    val taskCaptures = captures.filter { it.taskId == task.id }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppBar(leading = Leading.BACK, trailingSearch = false, onLeading = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 30.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AreaDotColor(areaColorFor(task.lifeArea, areas, c), size = 6)
                SectionLabel("${(task.lifeArea ?: "Task").uppercase()} · CREATED")
            }
            Text(task.name, style = UFont.sans(28, FontWeight.Bold), color = if (task.done) c.ink3 else c.ink, modifier = Modifier.padding(top = 6.dp), textDecoration = if (task.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)

            // First physical action card.
            Box(Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(14.dp)).background(c.bg2).padding(horizontal = 16.dp, vertical = 14.dp)) {
                Column {
                    SectionLabel("First physical action")
                    Text(task.firstPhysicalAction ?: "Add one — type a first physical action.", style = UFont.sans(14).copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), color = c.ink3, modifier = Modifier.padding(top = 6.dp))
                }
            }

            // Action row.
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { UButton("Focus", kind = ButtonKind.CORAL, leadingIcon = Icons.Filled.PlayArrow, onClick = onStartFocus) }
                UButton("Schedule", kind = ButtonKind.OUTLINED, fill = false) {
                    val slot = findFreeSlots(blocks, task.estimateMin, vm.nowMs(), limit = 1).firstOrNull()
                    if (slot != null) { vm.scheduleTask(task, slot.date, slot.startTime); scheduled = slot.label }
                }
                UButton(if (task.done) "✓ Done" else "Mark done", kind = ButtonKind.TEXT, fill = false) { vm.toggleDone(task) }
            }
            scheduled?.let { Text("Scheduled $it", style = UFont.sans(12), color = c.green, modifier = Modifier.padding(top = 8.dp)) }

            // Meta grid.
            Card(Modifier.fillMaxWidth().padding(top = 18.dp), radius = 14) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        MetaCell("Estimate", "${task.estimateMin} min", Modifier.weight(1f))
                        MetaCell("Area", task.lifeArea ?: "—", Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        MetaCell("Schedule", if (blocks.any { it.taskId == task.id }) "Scheduled" else "Unscheduled", Modifier.weight(1f))
                        MetaCell("Status", if (task.done) "Completed" else "Not started", Modifier.weight(1f))
                    }
                }
            }

            if (taskSessions.isNotEmpty()) {
                SectionLabel("Sessions", Modifier.padding(top = 18.dp, bottom = 6.dp))
                taskSessions.take(6).forEach { s ->
                    Text("• ${s.actualSec / 60}m focused", style = UFont.sans(13), color = c.ink2, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
            if (taskCaptures.isNotEmpty()) {
                SectionLabel("Captures", Modifier.padding(top = 18.dp, bottom = 6.dp))
                taskCaptures.take(8).forEach { cap ->
                    Text("• ${cap.body}", style = UFont.sans(13), color = c.ink2, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
            UButton("Delete", kind = ButtonKind.DANGER, fill = false, modifier = Modifier.padding(top = 22.dp)) { vm.deleteTask(task.id); onBack() }
        }
    }
}

@Composable
private fun MetaCell(label: String, value: String, modifier: Modifier = Modifier) {
    val c = UTheme.colors
    Column(modifier) {
        SectionLabel(label)
        Text(value, style = UFont.sans(13), color = c.ink, modifier = Modifier.padding(top = 3.dp))
    }
}
