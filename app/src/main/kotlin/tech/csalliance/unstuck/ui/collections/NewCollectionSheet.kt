package tech.csalliance.unstuck.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.newUuid
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.SheetHandle
import tech.csalliance.unstuck.design.component.SheetScrim
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

private val PALETTE = listOf("indigo", "coral", "green", "amber", "blue", "violet")

/** Create a collection — name + a color swatch. Mirrors the web new-collection-sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewCollectionSheet(vm: AppViewModel, onCreated: (String) -> Unit, onDismiss: () -> Unit) {
    val c = UTheme.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val collections by vm.collections.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("indigo") }

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface, scrimColor = SheetScrim,
        dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { SheetHandle() } },
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 22.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel("New collection")
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("What would you like to remember?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SectionLabel("Color")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PALETTE.forEach { col ->
                    val on = color == col
                    Box(Modifier.size(30.dp).clip(CircleShape).background(c.areaColor(col)).border(if (on) 2.dp else 0.dp, c.ink, CircleShape).clickable { color = col })
                }
            }
            UButton("Create", kind = ButtonKind.DARK, enabled = name.isNotBlank()) {
                val col = ItemCollection(id = newUuid(), name = name.trim(), color = color, items = emptyList(), sortOrder = (collections.maxOfOrNull { it.sortOrder } ?: -1) + 1)
                vm.upsertCollection(col)
                onCreated(col.id)
            }
        }
    }
}
