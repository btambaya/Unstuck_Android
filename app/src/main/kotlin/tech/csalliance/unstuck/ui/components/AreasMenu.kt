package tech.csalliance.unstuck.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.SheetHandle
import tech.csalliance.unstuck.design.component.SheetScrim
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

/**
 * Areas filter — the mobile equivalent of the web left rail's AREAS section.
 * Tapping an area filters the Tasks list by it (onPick(name)); "All tasks"
 * clears the filter (onPick(null)); the "Unassigned" bucket surfaces open
 * tasks with no area.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AreasMenu(vm: AppViewModel, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val c = UTheme.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val areas by vm.lifeAreas.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    // recurrence == null: hidden recurring templates don't count toward areas.
    val unassigned = tasks.count { !it.done && it.recurrence == null && it.lifeArea == null }

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface, scrimColor = SheetScrim,
        dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { SheetHandle() } },
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            SectionLabel("Areas", color = c.primaryDeep, modifier = Modifier.padding(start = 20.dp, bottom = 4.dp))
            AreaRow(c.ink4, "All tasks", tasks.count { !it.done && it.recurrence == null }) { onPick(null) }
            areas.sortedBy { it.sortOrder }.forEach { a ->
                val open = tasks.count { it.lifeArea == a.name && !it.done && it.recurrence == null }
                AreaRow(c.areaColor(a.color), a.name, open) { onPick(a.name) }
            }
            if (unassigned > 0) AreaRow(c.ink4, "Unassigned", unassigned) { onPick("Unassigned") }
        }
    }
}

@Composable
private fun AreaRow(dot: androidx.compose.ui.graphics.Color, label: String, count: Int, onClick: () -> Unit) {
    val c = UTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            // Merge so the row reads as one button "<area>, N open tasks" — the bare
            // count "$count" alone was meaningless out of context.
            .semantics(mergeDescendants = true) {
                contentDescription = "$label, $count open ${if (count == 1) "task" else "tasks"}"
            }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Text(label, style = UFont.sans(14, FontWeight.Medium), color = c.ink, modifier = Modifier.weight(1f))
        Text("$count", style = UFont.sans(12), color = c.ink3)
    }
}
