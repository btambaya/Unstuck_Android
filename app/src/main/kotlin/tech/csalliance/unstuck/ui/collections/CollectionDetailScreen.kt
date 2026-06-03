package tech.csalliance.unstuck.ui.collections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.AddTask
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.core.model.CollectionItem
import tech.csalliance.unstuck.core.model.ItemCollection
import tech.csalliance.unstuck.design.component.AppBar
import tech.csalliance.unstuck.design.component.ColorChip
import tech.csalliance.unstuck.design.component.Leading
import tech.csalliance.unstuck.design.component.SectionLabel
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
    var revealedId by remember { mutableStateOf<String?>(null) }       // item whose actions are shown
    var promoteTarget by remember { mutableStateOf<CollectionItem?>(null) } // item awaiting the share chooser
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(collectionId) { runCatching { focus.requestFocus() } }

    // Navigate out of composition (not as a side effect inside it) when the
    // collection is gone — e.g. deleted locally or via realtime while open.
    LaunchedEffect(col == null) { if (col == null) onBack() }
    if (col == null) return
    val color = c.areaColor(col.color)
    val pinned = col.items.filter { it.pinned == true }
    val rest = col.items.filter { it.pinned != true }
    val owner = vm.isOwner(col)
    val canEdit = vm.canEdit(col)
    val memberCount = col.members.size
    val shared = memberCount > 0 || !owner
    val archived = col.archived == true
    var showShare by remember { mutableStateOf(false) }

    // Move-to-task: solo list → straight to "for me"; shared list → ask via the chooser.
    fun startPromote(item: CollectionItem) {
        revealedId = null
        if (vm.isShared(col)) promoteTarget = item
        else vm.moveItemToTask(col, item, AppViewModel.PromoteMode.SELF)
    }
    // Pick a "by" time (platform dialog), then promote keep-in-loop with that ISO time.
    fun pickByTimeThen(item: CollectionItem) {
        val now = java.time.LocalTime.now()
        android.app.TimePickerDialog(context, { _, h, m ->
            val iso = java.time.LocalDate.now().atTime(h, m).atZone(java.time.ZoneId.systemDefault()).toInstant().toString()
            vm.moveItemToTask(col, item, AppViewModel.PromoteMode.LOOP, iso)
        }, now.hour, now.minute, false).show()
    }

    fun add() {
        val body = draft.trim(); if (body.isEmpty()) return
        vm.addCollectionItem(col, body)
        draft = ""
        runCatching { focus.requestFocus() }   // keep adding
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppBar(leading = Leading.BACK, trailingSearch = false, onLeading = onBack)
        // Pinned header — the title + share/leave + shared-with line stay put while
        // the items below scroll. Opaque bg so scrolled rows don't bleed through.
        Column(Modifier.fillMaxWidth().background(c.bg).padding(horizontal = 18.dp, vertical = 6.dp)) {
            // Title — colored chip + inline rename, with a Share icon (owner) or
            // Leave (member) on the SAME line, right-aligned.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                ColorChip(color, box = 30, dot = 9)
                if (editingTitle && owner) {
                    BasicTextField(value = titleDraft, onValueChange = { titleDraft = it }, textStyle = UFont.serifItalic(26).copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.ink), modifier = Modifier.weight(1f))
                    Text("✓", style = UFont.sans(18), color = c.green, modifier = Modifier.clickable { vm.renameCollection(col, titleDraft); editingTitle = false }.padding(4.dp))
                } else {
                    Text(
                        col.name, style = UFont.serifItalic(26), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).then(if (owner) Modifier.clickable { titleDraft = col.name; editingTitle = true } else Modifier),
                    )
                    if (owner) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(
                                if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                                contentDescription = if (archived) "Unarchive" else "Archive",
                                tint = c.ink3, modifier = Modifier.size(21.dp).clip(CircleShape).clickable { vm.archiveCollection(col.id, !archived); onBack() }.padding(1.dp),
                            )
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete collection", tint = c.ink3, modifier = Modifier.size(21.dp).clip(CircleShape).clickable { confirmDelete = true }.padding(1.dp))
                            Icon(Icons.Filled.Share, contentDescription = "Share", tint = c.ink2, modifier = Modifier.size(22.dp).clip(CircleShape).clickable { showShare = true })
                        }
                    } else {
                        Text("Leave", style = UFont.sans(13, FontWeight.SemiBold), color = c.ink3, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { scope.launch { vm.leaveCollection(col.id) }; onBack() }.padding(horizontal = 6.dp, vertical = 4.dp))
                    }
                }
            }
            // Small shared-with line — only when actually shared.
            if (shared) {
                Text(
                    if (owner) "Shared with $memberCount" else if (canEdit) "Shared with you · you can edit" else "Shared with you · view only",
                    style = UFont.sans(12, FontWeight.SemiBold), color = c.primaryDeep,
                    modifier = Modifier.padding(top = 8.dp, start = 2.dp),
                )
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 18.dp).padding(bottom = 30.dp)) {
            // Recolor swatches — owner only.
            if (owner) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PALETTE.forEach { col2 ->
                        val on = col.color == col2
                        Box(Modifier.size(26.dp).clip(CircleShape).background(c.areaColor(col2)).border(if (on) 2.dp else 0.dp, c.ink, CircleShape).clickable { vm.recolorCollection(col, col2) })
                    }
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
                    pinned.forEach { row ->
                        CollItemRow(col, row, vm, readOnly = !canEdit,
                            revealed = revealedId == row.id,
                            onReveal = { revealedId = if (revealedId == row.id) null else row.id },
                            onMoveToTask = { startPromote(row) })
                    }
                }
                if (rest.isNotEmpty()) {
                    SectionLabel("All", Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp))
                    rest.forEach { row ->
                        CollItemRow(col, row, vm, readOnly = !canEdit,
                            revealed = revealedId == row.id,
                            onReveal = { revealedId = if (revealedId == row.id) null else row.id },
                            onMoveToTask = { startPromote(row) })
                    }
                }
            }

            // Add-item pill field — at the BOTTOM so new items append right above it
            // (the whole add flow is bottom-anchored). Autofocused on open. Hidden
            // for view-only members.
            if (canEdit) {
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
            }

        }
    }

    if (showShare) ShareCollectionSheet(vm, col.id, col.name, onDismiss = { showShare = false })

    // Move-to-task chooser (shared lists only): just me vs keep everyone in the loop.
    promoteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { promoteTarget = null },
            title = { Text("Move to task", style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
            text = { Text("“${target.body}” becomes a task in your list. Keep everyone in the loop and the others can see when it's done — you'll pick a “by” time.", style = UFont.sans(13), color = c.ink2) },
            confirmButton = { TextButton(onClick = { promoteTarget = null; pickByTimeThen(target) }) { Text("Keep everyone in the loop", color = c.primaryDeep) } },
            dismissButton = { TextButton(onClick = { promoteTarget = null; vm.moveItemToTask(col, target, AppViewModel.PromoteMode.SELF) }) { Text("Just me", color = c.ink2) } },
            containerColor = c.surface,
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollItemRow(
    col: ItemCollection, item: CollectionItem, vm: AppViewModel,
    readOnly: Boolean,
    revealed: Boolean,
    onReveal: () -> Unit,
    onMoveToTask: () -> Unit,
) {
    val c = UTheme.colors
    val done = item.done == true
    val isPinned = item.pinned == true
    val promoted = item.promoted == true
    val promotedDone = item.promotedDone == true
    val dueMs = item.dueAt?.let { parseInstantMs(it) }
    val overdue = promoted && !promotedDone && dueMs != null && vm.nowMs() > dueMs
    val struck = done || promoted          // promoted items read as "handled / in flight"
    var editing by remember(item.id) { mutableStateOf(false) }
    var draft by remember(item.id) { mutableStateOf(item.body) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(12.dp))
            // Hold ANYWHERE on the row to reveal the actions; tap = edit. (Gated
            // off while editing so the text field gets the taps; the checkbox +
            // revealed icons keep their own taps.)
            .then(if (readOnly || editing) Modifier else Modifier.combinedClickable(
                onClick = { draft = item.body; editing = true },
                onLongClick = onReveal,
            ))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Done checkbox (always visible).
        Box(
            Modifier.size(18.dp).clip(CircleShape).background(if (done) c.coral else c.surface).border(if (done) 0.dp else 1.5.dp, c.line2, CircleShape)
                .then(if (readOnly) Modifier else Modifier.clickable { vm.toggleCollectionItemDone(col, item.id) }),
            contentAlignment = Alignment.Center,
        ) { if (done) Icon(Icons.Filled.Check, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(12.dp)) }

        // Text + status. (Row handles tap = edit / hold = reveal.)
        Column(Modifier.weight(1f)) {
            if (editing && !readOnly) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(value = draft, onValueChange = { draft = it }, textStyle = UFont.sans(14).copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.ink), modifier = Modifier.weight(1f))
                    Text("✓", style = UFont.sans(16), color = c.green, modifier = Modifier.clickable { vm.updateCollectionItemBody(col, item.id, draft); editing = false }.padding(2.dp))
                }
            } else {
                Text(
                    item.body, style = UFont.sans(14), color = if (struck) c.ink3 else c.ink,
                    textDecoration = if (struck) TextDecoration.LineThrough else null,
                )
            }
            if (promoted) {
                val label = when {
                    promotedDone -> "done by ${item.assignee ?: "someone"} ✓"
                    overdue -> "⚠ overdue · due ${fmtTime(item.dueAt)}"
                    item.assignee != null && item.dueAt != null -> "${item.assignee}'s on it · by ${fmtTime(item.dueAt)}"
                    item.assignee != null -> "${item.assignee}'s on it"
                    else -> "Promoted"
                }
                Text(
                    label, style = UFont.sans(11, FontWeight.Medium),
                    color = if (overdue) c.red else if (promotedDone) c.greenInk else c.primaryDeep,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        // Action bar — hidden by default, revealed on long-press.
        if (!readOnly) {
            AnimatedVisibility(visible = revealed) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.PushPin, contentDescription = "Pin", tint = if (isPinned) c.coral else c.ink4, modifier = Modifier.size(19.dp).clickable { vm.toggleCollectionItemPin(col, item.id) })
                    // Hide Move-to-task while a promotion is in flight (avoids a duplicate task).
                    if (!promoted || promotedDone) {
                        Icon(Icons.Outlined.AddTask, contentDescription = "Move to task", tint = c.ink4, modifier = Modifier.size(19.dp).clickable { onMoveToTask() })
                    }
                    Icon(Icons.Filled.Close, contentDescription = "Remove", tint = c.ink4, modifier = Modifier.size(19.dp).clickable { vm.removeCollectionItem(col, item.id) })
                }
            }
        }
    }
}

private fun parseInstantMs(iso: String): Long? = runCatching { java.time.Instant.parse(iso).toEpochMilli() }
    .recoverCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()

private fun fmtTime(iso: String?): String {
    val ms = iso?.let { parseInstantMs(it) } ?: return ""
    return java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
}
