package tech.csalliance.unstuck.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.newUuid
import tech.csalliance.unstuck.core.model.CollectionItem
import tech.csalliance.unstuck.design.component.AppBar
import tech.csalliance.unstuck.design.component.ColorChip
import tech.csalliance.unstuck.design.component.ItemRow
import tech.csalliance.unstuck.design.component.Leading
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

@Composable
fun CollectionDetailScreen(vm: AppViewModel, collectionId: String, onBack: () -> Unit) {
    val c = UTheme.colors
    val collections by vm.collections.collectAsStateWithLifecycle()
    val col = collections.firstOrNull { it.id == collectionId }
    var draft by remember { mutableStateOf("") }

    if (col == null) { onBack(); return }
    val color = c.areaColor(col.color)
    val pinned = col.items.filter { it.pinned == true }
    val rest = col.items.filter { it.pinned != true }

    fun toggle(item: CollectionItem) {
        vm.upsertCollection(col.copy(items = col.items.map { if (it.id == item.id) it.copy(done = !(it.done ?: false)) else it }))
    }
    fun add() {
        val body = draft.trim(); if (body.isEmpty()) return
        vm.upsertCollection(col.copy(items = col.items + CollectionItem(id = newUuid(), body = body, at = vm.isoNow())))
        draft = ""
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppBar(leading = Leading.BACK, trailingSearch = false, onLeading = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 30.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp), modifier = Modifier.padding(top = 6.dp)) {
                ColorChip(color, box = 30, dot = 9)
                Text(col.name, style = UFont.serifItalic(26), color = c.ink)
            }

            // Add-item pill field.
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(28.dp)).background(c.surface).border(1.dp, c.line2, RoundedCornerShape(28.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = c.ink3, modifier = Modifier.padding(end = 0.dp))
                BasicTextField(
                    value = draft, onValueChange = { draft = it },
                    textStyle = UFont.sans(15).copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner -> if (draft.isEmpty()) Text("Add to this collection…", style = UFont.sans(15), color = c.ink3); inner() },
                )
            }

            if (col.items.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(top = 24.dp).clip(RoundedCornerShape(18.dp)).background(c.bg2).border(1.dp, c.line2, RoundedCornerShape(18.dp)).padding(vertical = 38.dp, horizontal = 20.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Keep small things here.", style = UFont.serifItalic(19), color = c.ink2)
                        Text("Type above. Hit return. Done.", style = UFont.sans(12), color = c.ink3, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            } else {
                if (pinned.isNotEmpty()) {
                    SectionLabel("Pinned", Modifier.padding(start = 4.dp, top = 20.dp, bottom = 6.dp))
                    pinned.forEach { ItemRow(it.body, it.done == true, pinned = true) { toggle(it) } }
                }
                if (rest.isNotEmpty()) {
                    SectionLabel("All", Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp))
                    rest.forEach { ItemRow(it.body, it.done == true, pinned = false) { toggle(it) } }
                }
            }
        }
    }
}
