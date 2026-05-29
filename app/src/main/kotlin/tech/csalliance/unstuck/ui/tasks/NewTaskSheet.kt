package tech.csalliance.unstuck.ui.tasks

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.model.Priority
import tech.csalliance.unstuck.design.component.SectionLabel
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
    var estimate by remember { mutableStateOf(25) }
    var priority by remember { mutableStateOf<Priority?>(null) }
    var area by remember { mutableStateOf<String?>(null) }
    var firstMove by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.bg) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel("New task")
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("What needs doing?") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            SectionLabel("Estimate")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 25, 45, 60, 90).forEach { m ->
                    SelectableChip("${m}m", selected = estimate == m) { estimate = m }
                }
            }

            SectionLabel("Priority")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Priority.entries.forEach { p ->
                    SelectableChip(p.name.lowercase(), selected = priority == p) { priority = if (priority == p) null else p }
                }
            }

            if (areas.isNotEmpty()) {
                SectionLabel("Area")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    areas.forEach { a -> SelectableChip(a.name, selected = area == a.name) { area = if (area == a.name) null else a.name } }
                }
            }

            OutlinedTextField(value = firstMove, onValueChange = { firstMove = it }, label = { Text("First physical action (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            UButton("Add task", enabled = name.isNotBlank()) {
                vm.addTask(
                    name = name, estimateMin = estimate, priority = priority, lifeArea = area,
                    firstPhysicalAction = firstMove.trim().ifEmpty { null },
                )
                onDismiss()
            }
        }
    }
}
