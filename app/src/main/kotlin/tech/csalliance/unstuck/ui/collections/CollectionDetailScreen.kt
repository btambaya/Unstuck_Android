package tech.csalliance.unstuck.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.model.CollectionItem
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.design.component.AppBar
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.ColorChip
import tech.csalliance.unstuck.design.component.Leading
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

private val PALETTE = listOf("indigo", "coral", "green", "amber", "blue", "violet")

@Composable
fun CollectionDetailScreen(vm: AppViewModel, collectionId: String, onBack: () -> Unit) {
    val c = UTheme.colors
    val collections by vm.collections.collectAsStateWithLifecycle()
    val col = collections.firstOrNull { it.id == collectionId }
    var draft by remember { mutableStateOf("") }
    var editingTitle by remember { mutableStateOf(false) }
    var titleDraft by remember(col?.name) { mutableStateOf(col?.name ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(collectionId) { runCatching { focus.requestFocus() } }

    if (col == null) { onBack(); return }
    val color = c.areaColor(col.color)
    val pinned = col.items.filter { it.pinned == true }
    val rest = col.items.filter { it.pinned != true }

    fun add() {
        val body = draft.trim(); if (body.isEmpty()) return
        vm.addCollectionItem(col, body)
        draft = ""
        runCatching { focus.requestFocus() }   // keep adding
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppBar(leading = Leading.BACK, trailingSearch = false, onLeading = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 18.dp).padding(bottom = 30.dp)) {
            // Title — colored chip + rename.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp), modifier = Modifier.padding(top = 6.dp)) {
                ColorChip(color, box = 30, dot = 9)
                if (editingTitle) {
                    BasicTextField(value = titleDraft, onValueChange = { titleDraft = it }, textStyle = UFont.serifItalic(26).copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.ink), modifier = Modifier.weight(1f))
                    Text("✓", style = UFont.sans(18), color = c.green, modifier = Modifier.clickable { vm.renameCollection(col, titleDraft); editingTitle = false }.padding(4.dp))
                } else {
                    Text(col.name, style = UFont.serifItalic(26), color = c.ink, modifier = Modifier.weight(1f).clickable { titleDraft = col.name; editingTitle = true })
                }
            }

            // Recolor swatches.
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PALETTE.forEach { col2 ->
                    val on = col.color == col2
                    Box(Modifier.size(26.dp).clip(CircleShape).background(c.areaColor(col2)).border(if (on) 2.dp else 0.dp, c.ink, CircleShape).clickable { vm.recolorCollection(col, col2) })
                }
            }

            if (col.items.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(top = 24.dp).clip(RoundedCornerShape(18.dp)).background(c.bg2).border(1.dp, c.line2, RoundedCornerShape(18.dp)).padding(vertical = 38.dp, horizontal = 20.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Keep small things here.", style = UFont.serifItalic(19), color = c.ink2)
                        Text("Type below. Hit return. Done.", style = UFont.sans(12), color = c.ink3, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            } else {
                if (pinned.isNotEmpty()) {
                    SectionLabel("Pinned", Modifier.padding(start = 4.dp, top = 20.dp, bottom = 6.dp))
                    pinned.forEach { CollItemRow(col, it, vm) }
                }
                if (rest.isNotEmpty()) {
                    SectionLabel("All", Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp))
                    rest.forEach { CollItemRow(col, it, vm) }
                }
            }

            // Add-item pill field — at the BOTTOM so new items append right above it
            // (the whole add flow is bottom-anchored). Autofocused on open.
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(28.dp)).background(c.surface).border(1.dp, c.line2, RoundedCornerShape(28.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = c.ink3)
                BasicTextField(
                    value = draft, onValueChange = { draft = it },
                    textStyle = UFont.sans(15).copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    decorationBox = { inner -> if (draft.isEmpty()) Text("Add to this collection…", style = UFont.sans(15), color = c.ink3); inner() },
                )
            }

            UButton("Delete collection", kind = ButtonKind.DANGER, fill = false, modifier = Modifier.padding(top = 24.dp)) { confirmDelete = true }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete \"${col.name}\"?", style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
        text = { Text("This collection and its ${col.items.size} item(s) are removed.", style = UFont.sans(13), color = c.ink2) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteCollection(col.id); onBack() }) { Text("Delete", color = c.red) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = c.ink2) } },
        containerColor = c.surface,
    )
}

@Composable
private fun CollItemRow(col: ItemCollection, item: CollectionItem, vm: AppViewModel) {
    val c = UTheme.colors
    val done = item.done == true
    val isPinned = item.pinned == true
    var editing by remember(item.id) { mutableStateOf(false) }
    var draft by remember(item.id) { mutableStateOf(item.body) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Done checkbox.
        Box(
            Modifier.size(18.dp).clip(CircleShape).background(if (done) c.coral else c.surface).border(if (done) 0.dp else 1.5.dp, c.line2, CircleShape).clickable { vm.toggleCollectionItemDone(col, item.id) },
            contentAlignment = Alignment.Center,
        ) { if (done) Icon(Icons.Filled.Check, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(12.dp)) }

        if (editing) {
            BasicTextField(value = draft, onValueChange = { draft = it }, textStyle = UFont.sans(14).copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.ink), modifier = Modifier.weight(1f))
            Text("✓", style = UFont.sans(16), color = c.green, modifier = Modifier.clickable { vm.updateCollectionItemBody(col, item.id, draft); editing = false }.padding(2.dp))
        } else {
            Text(
                item.body, style = UFont.sans(14), color = if (done) c.ink3 else c.ink,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                modifier = Modifier.weight(1f).clickable { draft = item.body; editing = true },
            )
        }
        Icon(Icons.Filled.Star, contentDescription = "Pin", tint = if (isPinned) c.coral else c.ink4, modifier = Modifier.size(18.dp).clickable { vm.toggleCollectionItemPin(col, item.id) })
        Icon(Icons.Filled.Close, contentDescription = "Remove", tint = c.ink4, modifier = Modifier.size(18.dp).clickable { vm.removeCollectionItem(col, item.id) })
    }
}
