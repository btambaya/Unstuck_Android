package tech.csalliance.unstuck.ui.tasks

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.newUuid
import tech.csalliance.unstuck.core.model.LifeArea
import tech.csalliance.unstuck.core.model.TagRow
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.ColorChip
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.SheetHandle
import tech.csalliance.unstuck.design.component.SheetScrim
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

// Areas & tags — ONE sheet, opened from the Tasks tab's "Edit" pill (at the
// end of the area filter), from `unstuck://settings?section=areas` and from
// the assistant's `open_screen areas`. It replaces Settings → Areas and
// Settings → Tags (slim settings, 2026-09-24): add, recolour, rename, delete.

internal object AreasTagsCopy {
    const val TITLE = "Areas & tags"
    const val EDIT_PILL = "Edit"
    const val EDIT_PILL_A11Y = "Edit areas and tags"
    const val AREAS = "Areas"
    const val AREAS_SUB = "The parts of your life your tasks belong to."
    const val TAGS = "Tags"
    const val TAGS_SUB = "Labels that cut across areas. A task can have several."
    const val NEW_AREA = "New area"
    const val NEW_TAG = "New tag"
    const val ADD = "Add"
    const val DONE = "Done"
    const val NO_TAGS = "No tags yet."
    fun areaExists(name: String) = "An area named \"$name\" already exists."
    fun tagExists(name: String) = "A tag named \"$name\" already exists."
}

/** The colours an area or tag can take (the area palette tokens). */
internal val AREA_TAG_PALETTE = listOf("indigo", "coral", "green", "amber", "teal", "blue", "violet", "red")

/** A new row's colour: the first one nothing uses yet, else round-robin. */
internal fun nextPaletteColor(used: List<String?>): String =
    AREA_TAG_PALETTE.firstOrNull { col -> used.none { it == col } } ?: AREA_TAG_PALETTE[used.size % AREA_TAG_PALETTE.size]

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AreasTagsSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val c = UTheme.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface, scrimColor = SheetScrim,
        dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { SheetHandle() } },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 22.dp).padding(bottom = 28.dp)
                .testTag("areas-tags-sheet"),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(AreasTagsCopy.TITLE, style = UFont.serifItalic(22), color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
                Text(
                    AreasTagsCopy.DONE, style = UFont.sans(14, FontWeight.Medium), color = c.ink2,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable(role = Role.Button, onClick = onDismiss)
                        .minimumInteractiveComponentSize().padding(horizontal = 8.dp),
                )
            }
            AreasSection(vm)
            TagsSection(vm)
        }
    }
}

@Composable
private fun AreasSection(vm: AppViewModel) {
    val c = UTheme.colors
    val context = LocalContext.current
    val areas by vm.lifeAreas.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    fun add() {
        val name = draft.trim()
        // Skip a duplicate name (areas key tasks by name string → two same-named
        // areas make filtering ambiguous). sortOrder = max+1 and color = first
        // unused both avoid collisions after a delete shrinks `areas.size`.
        if (name.isBlank()) return
        if (areas.any { it.name.equals(name, ignoreCase = true) }) {
            android.widget.Toast.makeText(context, AreasTagsCopy.areaExists(name), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val order = (areas.maxOfOrNull { it.sortOrder } ?: -1) + 1
        vm.upsertLifeArea(LifeArea(newUuid(), name, nextPaletteColor(areas.map { it.color }), order))
        draft = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(AreasTagsCopy.AREAS, color = c.primaryDeep, modifier = Modifier.semantics { heading() })
        Text(AreasTagsCopy.AREAS_SUB, style = UFont.sans(12), color = c.ink3)
        areas.sortedBy { it.sortOrder }.forEach { a ->
            val open = tasks.count { it.lifeArea == a.name && !it.done && it.recurrence == null }
            EditableRow(
                name = a.name, display = a.name, sub = "$open open", color = a.color, noun = "area",
                onRecolor = { vm.recolorLifeArea(a, it) },
                onRename = { nm ->
                    when {
                        areas.any { it.id != a.id && it.name.equals(nm, ignoreCase = true) } -> {
                            android.widget.Toast.makeText(context, AreasTagsCopy.areaExists(nm), android.widget.Toast.LENGTH_SHORT).show(); false
                        }
                        else -> { vm.renameLifeArea(a, nm); true }
                    }
                },
                deleteTitle = "Delete \"${a.name}\"?",
                deleteBody = "Tasks keep their data — they just lose this area label.",
                onDelete = { vm.deleteLifeArea(a.id) },
            )
        }
        AddRow(draft, { draft = it }, AreasTagsCopy.NEW_AREA, "add-area") { add() }
    }
}

@Composable
private fun TagsSection(vm: AppViewModel) {
    val c = UTheme.colors
    val context = LocalContext.current
    val tags by vm.tags.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    fun add() {
        val nm = draft.trim()
        if (nm.isBlank()) return
        if (tags.any { it.name.equals(nm, ignoreCase = true) }) {
            // A duplicate keeps the text, and says why nothing happened.
            android.widget.Toast.makeText(context, AreasTagsCopy.tagExists(nm), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val order = (tags.maxOfOrNull { it.sortOrder } ?: -1) + 1
        vm.upsertTag(TagRow(newUuid(), nm, nextPaletteColor(tags.map { it.color }), order))
        draft = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(AreasTagsCopy.TAGS, color = c.primaryDeep, modifier = Modifier.semantics { heading() })
        Text(AreasTagsCopy.TAGS_SUB, style = UFont.sans(12), color = c.ink3)
        if (tags.isEmpty()) Text(AreasTagsCopy.NO_TAGS, style = UFont.sans(13), color = c.ink3)
        tags.sortedBy { it.sortOrder }.forEach { tag ->
            val uses = tasks.count { it.tags?.contains(tag.name) == true }
            EditableRow(
                name = tag.name, display = "#${tag.name}", sub = "$uses ${if (uses == 1) "task" else "tasks"}", color = tag.color, noun = "tag",
                onRecolor = { vm.recolorTag(tag, it) },
                onRename = { nm ->
                    when {
                        tags.any { it.id != tag.id && it.name.equals(nm, ignoreCase = true) } -> {
                            android.widget.Toast.makeText(context, AreasTagsCopy.tagExists(nm), android.widget.Toast.LENGTH_SHORT).show(); false
                        }
                        else -> { vm.renameTag(tag, nm); true }
                    }
                },
                deleteTitle = "Delete #${tag.name}?",
                deleteBody = "It's removed from every task that uses it. This can't be undone.",
                onDelete = { vm.deleteTag(tag.id) },
            )
        }
        AddRow(draft, { draft = it }, AreasTagsCopy.NEW_TAG, "add-tag") { add() }
    }
}

/** One area or tag: colour (tap to change), name (rename in place), and a ⋮
 *  menu with Rename / Delete (confirmed). [onRename] returns false to keep the
 *  editor open (a duplicate name). */
@Composable
private fun EditableRow(
    name: String, display: String, sub: String, color: String?, noun: String,
    onRecolor: (String) -> Unit, onRename: (String) -> Boolean,
    deleteTitle: String, deleteBody: String, onDelete: () -> Unit,
) {
    val c = UTheme.colors
    var menu by remember(name) { mutableStateOf(false) }
    var confirm by remember(name) { mutableStateOf(false) }
    var editing by remember(name) { mutableStateOf(false) }
    var nameDraft by remember(name) { mutableStateOf(name) }
    var palOpen by remember(name) { mutableStateOf(false) }
    fun commit() {
        val nm = nameDraft.trim()
        when {
            // Blank / unchanged → just close the editor (no-op, no error noise).
            nm.isEmpty() -> { nameDraft = name; editing = false }
            nm == name -> editing = false
            onRename(nm) -> editing = false
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp))
            .padding(start = 6.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            Box(
                Modifier.clickable(role = Role.Button, onClickLabel = "Change colour") { palOpen = true }
                    .minimumInteractiveComponentSize()
                    .semantics { contentDescription = "$display colour: ${color ?: "none"}" },
                contentAlignment = Alignment.Center,
            ) { ColorChip(c.areaColor(color), box = 26, dot = 8) }
            DropdownMenu(expanded = palOpen, onDismissRequest = { palOpen = false }) {
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AREA_TAG_PALETTE.forEach { col ->
                        Box(
                            Modifier.clickable(role = Role.Button) { onRecolor(col); palOpen = false }
                                .minimumInteractiveComponentSize()
                                .semantics { contentDescription = col; selected = (col == color) },
                            contentAlignment = Alignment.Center,
                        ) { ColorChip(c.areaColor(col), box = 24, dot = 7) }
                    }
                }
            }
        }
        if (editing) {
            BasicTextField(
                value = nameDraft, onValueChange = { nameDraft = it },
                textStyle = UFont.sans(14, FontWeight.SemiBold).copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Rename $noun" },
            )
            Text(
                "Save", style = UFont.sans(13, FontWeight.SemiBold), color = c.ink,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { commit() }
                    .minimumInteractiveComponentSize().padding(horizontal = 8.dp),
            )
        } else {
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClickLabel = "Rename $display") { nameDraft = name; editing = true }
                    .padding(vertical = 6.dp),
            ) {
                Text(display, style = UFont.sans(14, FontWeight.SemiBold), color = c.ink)
                Text(sub, style = UFont.sans(11), color = c.ink3)
            }
        }
        Box {
            // The 20dp glyph in a 48dp box: the hit target meets the minimum.
            Box(
                Modifier.clickable(role = Role.Button, onClickLabel = "Options for $display") { menu = true }.minimumInteractiveComponentSize(),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.MoreVert, contentDescription = "Options for $display", tint = c.ink3, modifier = Modifier.size(20.dp)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename", style = UFont.sans(14), color = c.ink) }, onClick = { menu = false; nameDraft = name; editing = true })
                DropdownMenuItem(text = { Text("Delete $noun", style = UFont.sans(14), color = c.red) }, onClick = { menu = false; confirm = true })
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(deleteTitle, style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
        text = { Text(deleteBody, style = UFont.sans(13), color = c.ink2) },
        confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Delete", color = c.red) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", color = c.ink2) } },
        containerColor = c.surface,
    )
}

@Composable
private fun AddRow(draft: String, onDraft: (String) -> Unit, placeholder: String, tag: String, onAdd: () -> Unit) {
    val c = UTheme.colors
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(c.bg2).border(1.dp, c.line2, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 12.dp)) {
            BasicTextField(
                value = draft, onValueChange = onDraft, textStyle = UFont.sans(14).copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { onAdd() }),
                modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = placeholder },
                decorationBox = { inner -> if (draft.isEmpty()) Text(placeholder, style = UFont.sans(14), color = c.ink3); inner() },
            )
        }
        UButton(AreasTagsCopy.ADD, kind = ButtonKind.DARK, fill = false, enabled = draft.isNotBlank()) { onAdd() }
    }
}
