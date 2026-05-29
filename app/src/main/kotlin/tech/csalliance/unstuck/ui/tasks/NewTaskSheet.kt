package tech.csalliance.unstuck.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import tech.csalliance.unstuck.core.model.Priority
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.SheetHandle
import tech.csalliance.unstuck.design.component.SheetScrim
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTaskSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val c = UTheme.colors
    val areas by vm.lifeAreas.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf("") }
    var whenSel by remember { mutableStateOf("Today") }
    var estimate by remember { mutableStateOf(25) }
    var priority by remember { mutableStateOf<Priority?>(null) }
    var area by remember { mutableStateOf<String?>(null) }
    var firstMove by remember { mutableStateOf("") }
    var recurrence by remember { mutableStateOf<tech.csalliance.unstuck.core.model.Recurrence?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface, scrimColor = SheetScrim,
        dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { SheetHandle() } },
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 22.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("New task")
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("What's the next thing on your mind?") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            SectionLabel("When")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Today", "Tomorrow", "Later").forEach { w -> SelectableChip(w, selected = whenSel == w) { whenSel = w } }
            }

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

            SectionLabel("First step", color = c.coral)
            OutlinedTextField(value = firstMove, onValueChange = { firstMove = it }, label = { Text("The smallest concrete step…") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            tech.csalliance.unstuck.ui.components.RecurrenceEditor(recurrence) { recurrence = it }

            UButton("Add task", kind = ButtonKind.DARK, enabled = name.isNotBlank()) {
                val t = vm.addTask(
                    name = name, estimateMin = estimate, priority = priority, lifeArea = area,
                    firstPhysicalAction = firstMove.trim().ifEmpty { null }, recurrence = recurrence,
                    later = whenSel == "Later",
                )
                if (whenSel == "Today" || whenSel == "Tomorrow") {
                    val days = if (whenSel == "Tomorrow") 1 else 0
                    val date = tech.csalliance.unstuck.core.time.Clock.dateIso(
                        tech.csalliance.unstuck.core.time.Time.addDaysMillis(tech.csalliance.unstuck.core.time.Time.startOfDayMillis(vm.nowMs()), days),
                    )
                    val slot = tech.csalliance.unstuck.core.logic.findFreeSlotsForDate(emptyList(), estimate, date, vm.nowMs(), limit = 1).firstOrNull()
                    if (slot != null) vm.scheduleTask(t, date, slot.startTime)
                }
                onDismiss()
            }
        }
    }
}
