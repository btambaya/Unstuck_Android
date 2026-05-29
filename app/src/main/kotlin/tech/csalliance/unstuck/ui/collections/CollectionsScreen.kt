package tech.csalliance.unstuck.ui.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.newUuid
import tech.csalliance.unstuck.core.model.CollectionItem
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.design.component.AreaDot
import tech.csalliance.unstuck.design.component.Card
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.components.EmptyState
import tech.csalliance.unstuck.ui.components.ScreenHeader

@Composable
fun CollectionsScreen(vm: AppViewModel) {
    val c = UTheme.colors
    val collections by vm.collections.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    val drafts = remember { mutableStateMapOf<String, String>() }

    Column(Modifier.fillMaxWidth()) {
        ScreenHeader("Lists", subtitle = "${collections.size} collections") {
            IconButton(onClick = { showAdd = true }) { Icon(Icons.Outlined.Add, contentDescription = "New list", tint = c.ink2) }
        }
        if (collections.isEmpty()) {
            EmptyState("No lists yet. Tap + to make one (groceries, ideas, someday…).")
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(collections.sortedBy { it.sortOrder }, key = { it.id }) { col ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AreaDot(col.color)
                                Text(col.name, style = UFont.sans(16, FontWeight.SemiBold), color = c.ink, modifier = Modifier.weight(1f))
                                Text("Delete", style = UFont.sans(12), color = c.coralDeep, modifier = Modifier.clickable { vm.deleteCollection(col.id) })
                            }
                            col.items.sortedByDescending { it.pinned == true }.forEach { item ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    IconButton(onClick = { toggleItem(vm, col, item) }, modifier = Modifier.size(26.dp)) {
                                        Icon(
                                            if (item.done == true) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (item.done == true) c.green else c.ink3,
                                        )
                                    }
                                    Text(item.body, style = UFont.sans(14), color = if (item.done == true) c.ink3 else c.ink, modifier = Modifier.weight(1f))
                                }
                            }
                            val draft = drafts[col.id] ?: ""
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { drafts[col.id] = it },
                                label = { Text("Add item") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    if (draft.isNotBlank()) { addItem(vm, col, draft.trim()); drafts[col.id] = "" }
                                }),
                            )
                        }
                    }
                }
                item { Text("", Modifier.padding(28.dp)) }
            }
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        vm.upsertCollection(ItemCollection(id = newUuid(), name = name.trim(), color = "indigo", subtitle = null, items = emptyList(), sortOrder = collections.size))
                    }
                    showAdd = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
            title = { Text("New list") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
        )
    }
}

private fun addItem(vm: AppViewModel, col: ItemCollection, body: String) {
    val item = CollectionItem(id = newUuid(), body = body, at = vm.isoNow())
    vm.upsertCollection(col.copy(items = col.items + item))
}

private fun toggleItem(vm: AppViewModel, col: ItemCollection, item: CollectionItem) {
    val updated = col.items.map { if (it.id == item.id) it.copy(done = !(it.done ?: false)) else it }
    vm.upsertCollection(col.copy(items = updated))
}
