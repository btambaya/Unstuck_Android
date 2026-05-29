package tech.csalliance.unstuck.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.findFreeSlots
import tech.csalliance.unstuck.core.logic.recurrenceLabel
import tech.csalliance.unstuck.core.model.Priority
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailSheet(vm: AppViewModel, task: TaskItem, onDismiss: () -> Unit, onStartFocus: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val c = UTheme.colors
    val areas by vm.lifeAreas.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()

    var name by remember(task.id) { mutableStateOf(task.name) }
    var estimate by remember(task.id) { mutableStateOf(task.estimateMin) }
    var priority by remember(task.id) { mutableStateOf(task.priority) }
    var area by remember(task.id) { mutableStateOf(task.lifeArea) }
    var later by remember(task.id) { mutableStateOf(task.later == true) }
    var scheduled by remember(task.id) { mutableStateOf<String?>(null) }

    fun persist() = vm.updateTask(task.copy(name = name.trim(), estimateMin = estimate, priority = priority, lifeArea = area, later = later))

    ModalBottomSheet(onDismissRequest = { persist(); onDismiss() }, sheetState = sheet, containerColor = c.bg) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel("Edit task")
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            SectionLabel("Estimate")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 25, 45, 60, 90).forEach { m -> SelectableChip("${m}m", selected = estimate == m) { estimate = m } }
            }

            SectionLabel("Priority")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Priority.entries.forEach { p -> SelectableChip(p.name.lowercase(), selected = priority == p) { priority = if (priority == p) null else p } }
            }

            if (areas.isNotEmpty()) {
                SectionLabel("Area")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    areas.forEach { a -> SelectableChip(a.name, selected = area == a.name) { area = if (area == a.name) null else a.name } }
                }
            }

            task.recurrence?.let { Text(recurrenceLabel(it), style = UFont.sans(13), color = c.ink3) }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Save for later", style = UFont.sans(14), color = c.ink)
                Switch(checked = later, onCheckedChange = { later = it })
            }

            scheduled?.let { Text("Scheduled $it", style = UFont.sans(13), color = c.green) }

            UButton("Start focus") { persist(); onStartFocus() }
            UButton("Schedule next free slot", kind = ButtonKind.GHOST) {
                val slot = findFreeSlots(blocks, estimate, vm.nowMs(), limit = 1).firstOrNull()
                if (slot != null) {
                    vm.scheduleTask(task.copy(estimateMin = estimate), slot.date, slot.startTime)
                    scheduled = slot.label
                }
            }
            UButton(if (task.done) "Mark not done" else "Mark done", kind = ButtonKind.GHOST) { vm.toggleDone(task); onDismiss() }
            UButton("Delete", kind = ButtonKind.DANGER) { vm.deleteTask(task.id); onDismiss() }
        }
    }
}
