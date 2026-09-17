package tech.csalliance.unstuck.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.formatTime
import tech.csalliance.unstuck.core.logic.occurrenceBlockFor
import tech.csalliance.unstuck.core.logic.recurrenceLabel
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Time
import tech.csalliance.unstuck.design.component.AppBar
import tech.csalliance.unstuck.design.component.AreaDotColor
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.Card
import tech.csalliance.unstuck.design.component.FilterPill
import tech.csalliance.unstuck.design.component.Leading
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.sharing.ShareMode
import tech.csalliance.unstuck.ui.sharing.ShareScreen
import tech.csalliance.unstuck.ui.sharing.ShareTarget
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import tech.csalliance.unstuck.ui.components.RecurrenceEditor
import tech.csalliance.unstuck.ui.components.TagPicker
import tech.csalliance.unstuck.ui.components.areaColorFor
import tech.csalliance.unstuck.ui.tour.TourAnchorIds
import tech.csalliance.unstuck.ui.tour.tourAnchor
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.design.component.MdToggle
import tech.csalliance.unstuck.sync.CallRequest
import tech.csalliance.unstuck.ui.assistant.CallToolLogic
import tech.csalliance.unstuck.ui.assistant.nextLiveBlock

/** Full-screen task detail — editable (name / first action / estimate / area /
 *  repeat / tags), with session history and capture management. */
@Composable
fun TaskDetailScreen(vm: AppViewModel, task: TaskItem, onBack: () -> Unit, onStartFocus: () -> Unit) {
    val c = UTheme.colors
    val context = LocalContext.current
    val areas by vm.lifeAreas.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val captures by vm.captures.collectAsStateWithLifecycle()
    var scheduled by remember(task.id) { mutableStateOf<String?>(null) }

    // A recurring OCCURRENCE's id is its cal_block id. Complete/skip route to
    // the block (vm.toggleDone / vm.skipOccurrence already detect it); field
    // edits route to the TEMPLATE (one definition per series).
    val occBlock = occurrenceBlockFor(task.id, tasks, blocks)
    val template = occBlock?.let { b -> tasks.firstOrNull { it.id == b.taskId } }
    val isOcc = occBlock != null && template != null
    val editTarget = if (isOcc) template!! else task

    // A REAL task the owner assigned OUT is view-only here: hide Focus + Mark-done (the
    // recipient owns doing it now) and show a hint. Share controls stay live so the owner
    // can always take it back (downgrade / unshare re-enables Focus + completion).
    // Occurrences are never assigned out, so only gate real tasks.
    val assignedOut by vm.assignedOut.collectAsStateWithLifecycle()
    val assignedOutName = if (!isOcc) assignedOut[task.id] else null
    val isAssignedOut = assignedOutName != null

    // Pick an actual date + time (platform dialogs, local-zone — no Material UTC
    // off-by-one). scheduleTask both creates and reschedules in place; scheduling a
    // concrete time also moves the task out of "Later".
    fun pickSchedule() {
        val d0 = java.time.LocalDate.now()
        val t0 = java.time.LocalTime.now()
        val dlg = android.app.DatePickerDialog(context, { _, y, m, day ->
            android.app.TimePickerDialog(context, { _, h, min ->
                val dateIso = java.time.LocalDate.of(y, m + 1, day).toString()
                val timeIso = "%02d:%02d".format(h, min)
                // vm.scheduleTask clears "Later" itself now (AppViewModel
                // .scheduleTaskNow) — for EVERY scheduling surface, not just this
                // one — so the second whole-row write that used to live here is gone.
                vm.scheduleTask(task, dateIso, timeIso)
                scheduled = "${dateIso.takeLast(5)} ${formatTime(timeIso)}"
            }, t0.hour, t0.minute, false).show()
        }, d0.year, d0.monthValue - 1, d0.dayOfMonth)
        dlg.datePicker.minDate = System.currentTimeMillis() - 60_000   // no past days
        dlg.show()
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var showEstimate by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showHandOver by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    // Current per-task reminder lead override (null = the global default from
    // Settings, 0 = off). Keyed on the TEMPLATE for occurrences — that's the id
    // ReminderScheduler looks up per block.
    var reminderLead by remember(editTarget.id) { mutableStateOf(vm.reminderOverride(editTarget.id)) }
    val taskSessions = sessions.filter { it.taskId == task.id }
    val taskCaptures = captures.filter { it.taskId == task.id }
    val myBlocks = blocks.filter { it.taskId == task.id }.sortedWith(compareBy({ it.date }, { it.startTime }))
    val scheduleLabel = when {
        task.later == true -> "Later"
        myBlocks.isNotEmpty() -> "${myBlocks.first().date.takeLast(5)} ${formatTime(myBlocks.first().startTime)}"
        else -> "Unscheduled"
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppBar(leading = Leading.BACK, trailingSearch = false, onLeading = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 18.dp).padding(bottom = 30.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AreaDotColor(areaColorFor(task.lifeArea, areas, c), size = 6)
                SectionLabel("${(task.lifeArea ?: "Task").uppercase()} · TASK")
                Box(Modifier.weight(1f))
                // A LABELLED "Share" (unified sharing v1 — testers couldn't find the
                // old bare icon) opening the ONE Share screen, + the task-action menu
                // ("Hand over to…"). An occurrence shares its TEMPLATE (one day of a
                // series isn't its own task) and can't be handed over.
                Row(
                    Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, c.line2, RoundedCornerShape(999.dp))
                        .clickable { showShare = true }.padding(horizontal = 12.dp, vertical = 6.dp)
                        .semantics { contentDescription = "Share task" },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = null, tint = c.ink, modifier = Modifier.size(15.dp))
                    Text("Share", style = UFont.sans(13, FontWeight.Medium), color = c.ink)
                }
                if (!isOcc) Box {
                    Icon(
                        Icons.Filled.MoreVert, contentDescription = "More actions", tint = c.ink2,
                        modifier = Modifier.size(28.dp).clip(CircleShape).clickable { moreMenu = true }.padding(4.dp),
                    )
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Hand over to…", style = UFont.sans(14), color = c.ink) },
                            leadingIcon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null, tint = c.ink2, modifier = Modifier.size(18.dp)) },
                            onClick = { moreMenu = false; showHandOver = true },
                        )
                    }
                }
            }

            EditableText(
                value = task.name, placeholder = "Untitled task",
                style = UFont.sans(28, FontWeight.Bold),
                color = if (task.done) c.ink3 else c.ink,
                strike = task.done,
                modifier = Modifier.padding(top = 6.dp),
            ) { if (it.isNotBlank() && it != task.name) vm.updateTask(editTarget.copy(name = it)) }

            Box(Modifier.fillMaxWidth().padding(top = 14.dp).tourAnchor(TourAnchorIds.FIRST_ACTION).clip(RoundedCornerShape(14.dp)).background(c.bg2).padding(horizontal = 16.dp, vertical = 14.dp)) {
                Column {
                    SectionLabel("First physical action", color = c.coral)
                    EditableText(
                        value = task.firstPhysicalAction ?: "", placeholder = "Add one — the smallest concrete step.",
                        style = UFont.sans(14).copy(fontStyle = FontStyle.Italic),
                        color = if (task.firstPhysicalAction == null) c.ink3 else c.ink,
                        modifier = Modifier.padding(top = 6.dp),
                    ) { val v = it.trim().ifEmpty { null }; if (v != task.firstPhysicalAction) vm.updateTask(editTarget.copy(firstPhysicalAction = v)) }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Assigned out → view-only: no live Focus, no Mark-done (guarded in the VM too).
                if (!isAssignedOut) {
                    Box(Modifier.weight(1f)) { UButton("Focus", kind = ButtonKind.CORAL, leadingIcon = Icons.Filled.PlayArrow, onClick = { if (!isAssignedOut) onStartFocus() }) }
                }
                if (!isOcc) UButton("Schedule", kind = ButtonKind.OUTLINED, fill = false) { pickSchedule() }
                if (!isAssignedOut) UButton(if (task.done) "✓ Done" else "Mark done", kind = ButtonKind.TEXT, fill = false) {
                    if (isAssignedOut) return@UButton
                    val wasDone = task.done
                    vm.toggleDone(task)
                    // For a recurring OCCURRENCE, completing it removes that day's row — pop the
                    // sheet (consistent with Skip/Delete) so it doesn't sit on a stale/auto-
                    // bouncing item. Un-completing or a normal task keeps the sheet open.
                    if (isOcc && !wasDone) onBack()
                }
                if (isOcc) UButton("Skip today", kind = ButtonKind.TEXT, fill = false) { vm.skipOccurrence(task.id); onBack() }
            }
            if (isAssignedOut) {
                Text(
                    "You assigned this to ${assignedOutName?.substringBefore('@') ?: "someone"} — view only",
                    style = UFont.sans(12), color = c.ink3, modifier = Modifier.padding(top = 8.dp),
                )
            }
            scheduled?.let { Text("Scheduled $it", style = UFont.sans(12), color = c.green, modifier = Modifier.padding(top = 8.dp)) }
            if (task.later == true) {
                UButton("Move out of Later", kind = ButtonKind.GHOST, fill = false, modifier = Modifier.padding(top = 4.dp)) { vm.setLater(task, false) }
            }

            Card(Modifier.fillMaxWidth().padding(top = 18.dp), radius = 14) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column {
                        SectionLabel("Estimate")
                        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val presets = listOf(15, 25, 45, 60, 90)
                            presets.forEach { m ->
                                SelectableChip("${m}m", selected = task.estimateMin == m) { vm.updateTask(editTarget.copy(estimateMin = m)) }
                            }
                            if (task.estimateMin !in presets) SelectableChip("${task.estimateMin}m", selected = true) { showEstimate = true }
                            SelectableChip("Custom…", selected = false) { showEstimate = true }
                        }
                    }
                    Column {
                        SectionLabel("Area")
                        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterPill("Unassigned", task.lifeArea == null) { vm.updateTask(editTarget.copy(lifeArea = null)) }
                            areas.forEach { a -> FilterPill(a.name, task.lifeArea == a.name, dotColor = c.areaColor(a.color)) { vm.updateTask(editTarget.copy(lifeArea = if (task.lifeArea == a.name) null else a.name)) } }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Tapping the schedule cell opens the same date/time picker.
                        MetaCell("Schedule", scheduleLabel, Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(enabled = !isOcc) { pickSchedule() })
                        MetaCell("Status", when { task.done -> "Completed"; task.totalFocused > 0 -> "In progress"; else -> "Not started" }, Modifier.weight(1f))
                    }
                    // Pre-task reminder — "Default" uses the global lead from Settings;
                    // pick a specific lead (or Off) to override just this task. Only shown
                    // when a reminder can actually fire, i.e. the task is scheduled and not
                    // parked in Later (mirrors the old create-sheet condition). The override
                    // lives in prefs, so re-arm the alarms explicitly (same as Settings).
                    if (task.later != true && (myBlocks.isNotEmpty() || isOcc)) {
                        Column {
                            SectionLabel("Remind me")
                            fun pick(lead: Int?) {
                                reminderLead = lead
                                vm.setReminderOverride(editTarget.id, lead)
                                runCatching { tech.csalliance.unstuck.surface.ReminderScheduler.reschedule(context.applicationContext as tech.csalliance.unstuck.UnstuckApp) }
                            }
                            Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SelectableChip("Default", selected = reminderLead == null) { pick(null) }
                                SelectableChip("Off", selected = reminderLead == 0) { pick(0) }
                                listOf(5, 10, 15).forEach { m -> SelectableChip("${m}m before", selected = reminderLead == m) { pick(m) } }
                            }
                        }
                    }
                }
            }

            // "Call me about this" — books / updates / cancels the call_requests
            // row anchored to this task's next scheduled block (iOS CallMeSection).
            // Occurrences anchor on the TEMPLATE (its blocks carry the series id);
            // a task assigned out is view-only here.
            if (!isAssignedOut && vm.callsAvailable()) {
                CallMeSection(vm, editTarget, blocks.filter { it.taskId == editTarget.id })
            }

            SectionLabel("Repeat", Modifier.padding(top = 18.dp, bottom = 4.dp))
            if (isOcc) {
                // One day of a recurring series — edit the repeat on the series,
                // not this occurrence (which would split it off as its own task).
                Text("One day of “${template!!.name}” (${recurrenceLabel(template!!.recurrence)}).", style = UFont.sans(13), color = c.ink2, modifier = Modifier.padding(bottom = 6.dp))
            } else {
                Text(recurrenceLabel(task.recurrence).ifEmpty { "Does not repeat" }, style = UFont.sans(13), color = c.ink2, modifier = Modifier.padding(bottom = 6.dp))
                RecurrenceEditor(task.recurrence) { vm.setRecurrence(editTarget, it) }
            }

            SectionLabel("Tags", Modifier.padding(top = 18.dp, bottom = 6.dp))
            TagPicker(vm, task.tags ?: emptyList()) { vm.updateTask(editTarget.copy(tags = it.ifEmpty { null })) }

            if (taskSessions.isNotEmpty()) {
                SectionLabel("Sessions", Modifier.padding(top = 18.dp, bottom = 6.dp))
                taskSessions.take(6).forEach { s ->
                    Text("• ${s.actualSec / 60}m focused", style = UFont.sans(13), color = c.ink2, modifier = Modifier.padding(vertical = 2.dp))
                }
            }

            SectionLabel("Captures", Modifier.padding(top = 18.dp, bottom = 6.dp))
            taskCaptures.forEach { cap -> CaptureRow(cap, vm.nowMs(), onPromote = { vm.promoteCapture(cap) }, onDiscard = { vm.deleteCapture(cap.id) }) }
            AddCaptureRow { tag, body -> vm.saveCapture(editTarget.id, null, tag, body) }

            // Occurrences have "Skip today"; deleting the whole series is done
            // from the template (Recurring tab), not from one day.
            if (!isOcc) UButton("Delete", kind = ButtonKind.DANGER, fill = false, modifier = Modifier.padding(top = 22.dp)) { confirmDelete = true }
        }
    }

    if (showEstimate) {
        var v by remember { mutableStateOf(task.estimateMin.toString()) }
        fun saveEstimate() { v.toIntOrNull()?.takeIf { it > 0 }?.let { vm.updateTask(editTarget.copy(estimateMin = it)) }; showEstimate = false }
        AlertDialog(
            onDismissRequest = { showEstimate = false },
            title = { Text("Estimate (minutes)", style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = v, onValueChange = { s -> v = s.filter { it.isDigit() }.take(4) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { saveEstimate() }),
                )
            },
            confirmButton = { TextButton(onClick = { saveEstimate() }) { Text("Save", color = c.primaryDeep) } },
            dismissButton = { TextButton(onClick = { showEstimate = false }) { Text("Cancel", color = c.ink2) } },
            containerColor = c.surface,
        )
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this task?", style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
        text = { Text("Its scheduled blocks and captures are removed too.", style = UFont.sans(13), color = c.ink2) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteTask(task.id); onBack() }) { Text("Delete", color = c.red) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = c.ink2) } },
        containerColor = c.surface,
    )

    // The ONE Share screen (unified sharing v1) + the "Hand over to…" picker —
    // both on the editable target (the template for an occurrence).
    if (showShare) ShareScreen(vm, ShareTarget.Task(editTarget.id, editTarget.name), onDismiss = { showShare = false })
    if (showHandOver) ShareScreen(vm, ShareTarget.Task(editTarget.id, editTarget.name), mode = ShareMode.HAND_OVER, onDismiss = { showHandOver = false })
}

@Composable
private fun EditableText(
    value: String,
    placeholder: String,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    strike: Boolean = false,
    onCommit: (String) -> Unit,
) {
    val c = UTheme.colors
    var editing by remember(value) { mutableStateOf(false) }
    var draft by remember(value) { mutableStateOf(value) }
    if (editing) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = draft, onValueChange = { draft = it }, textStyle = style.copy(color = c.ink),
                cursorBrush = SolidColor(c.ink), modifier = Modifier.weight(1f),
                // Single-value editor: IME Done = the ✓ commit. Kept multi-line so a long
                // name still wraps; Done (not Enter/newline) is the only way it closes.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCommit(draft.trim()); editing = false }),
            )
            Text("✓", style = UFont.sans(18), color = c.green, modifier = Modifier.clickable { onCommit(draft.trim()); editing = false }.padding(4.dp))
            Text("✕", style = UFont.sans(18), color = c.ink3, modifier = Modifier.clickable { draft = value; editing = false }.padding(4.dp))
        }
    } else {
        Text(
            value.ifEmpty { placeholder },
            style = style, color = color,
            textDecoration = if (strike) TextDecoration.LineThrough else null,
            modifier = modifier.clickable { draft = value; editing = true },
        )
    }
}

@Composable
private fun CaptureRow(cap: Capture, now: Long, onPromote: () -> Unit, onDiscard: () -> Unit) {
    val c = UTheme.colors
    val (bg, fg) = captureTagColors(cap.tag)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text(cap.tag.name.lowercase().replace('_', '-'), style = UFont.sans(10, FontWeight.Medium), color = fg)
            }
            Text(relativeTime(cap.at, now), style = UFont.mono(10), color = c.ink3)
        }
        Text(cap.body, style = UFont.sans(14), color = c.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Promote to task →", style = UFont.sans(12, FontWeight.Medium), color = c.primaryDeep, modifier = Modifier.clickable(onClick = onPromote))
            Text("Discard", style = UFont.sans(12), color = c.ink3, modifier = Modifier.clickable(onClick = onDiscard))
        }
    }
}

@Composable
private fun AddCaptureRow(onAdd: (CaptureTag, String) -> Unit) {
    val c = UTheme.colors
    val focusManager = LocalFocusManager.current
    var body by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf(CaptureTag.FOLLOW_UP) }
    val tags = listOf(
        CaptureTag.FOLLOW_UP to "follow-up", CaptureTag.IDEA to "idea", CaptureTag.EDIT to "edit",
        CaptureTag.QUESTION to "question", CaptureTag.DISTRACTION to "distraction",
    )
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(c.bg2).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Field + Add on one row — Add sits before the tags so it's always visible
        // and you can add without scrolling past the chips.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = body, onValueChange = { body = it }, textStyle = UFont.sans(14).copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.ink),
                modifier = Modifier.weight(1f),
                // Done = the Add button when there's text; otherwise just drop the keyboard.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (body.isNotBlank()) { onAdd(tag, body.trim()); body = "" } else focusManager.clearFocus() }),
                decorationBox = { inner -> if (body.isEmpty()) Text("Capture a thought…", style = UFont.sans(14), color = c.ink3); inner() },
            )
            if (body.isNotBlank()) {
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(c.ink).clickable { onAdd(tag, body.trim()); body = "" }.padding(horizontal = 16.dp, vertical = 7.dp),
                ) { Text("Add", style = UFont.sans(12, FontWeight.SemiBold), color = c.bg) }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            tags.forEach { (t, label) -> FilterPill(label, selected = tag == t, dotColor = captureTagDot(t)) { tag = t } }
        }
    }
}

private fun captureTagKey(tag: CaptureTag): Int = when (tag) {
    CaptureTag.IDEA -> 1
    CaptureTag.EDIT -> 2
    CaptureTag.QUESTION -> 3
    CaptureTag.DISTRACTION -> 4
    else -> 0
}

@Composable
private fun captureTagColors(tag: CaptureTag): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    val c = UTheme.colors
    return when (captureTagKey(tag)) {
        1 -> c.amberSoft to c.amberInk
        2 -> c.blueSoft to c.blueInk
        3 -> c.greenSoft to c.greenInk
        4 -> c.coralSoft to c.coralDeep
        else -> c.primarySoft to c.primaryDeep
    }
}

private fun relativeTime(iso: String, now: Long): String {
    val ms = Time.parseMillis(iso) ?: return ""
    val diff = (now - ms).coerceAtLeast(0)
    val min = diff / 60000
    return when {
        min < 1 -> "just now"
        min < 60 -> "${min}m ago"
        min < 1440 -> "${min / 60}h ago"
        else -> "${min / 1440}d ago"
    }
}

@Composable
private fun MetaCell(label: String, value: String, modifier: Modifier = Modifier) {
    val c = UTheme.colors
    Column(modifier) {
        SectionLabel(label)
        Text(value, style = UFont.sans(13), color = c.ink, modifier = Modifier.padding(top = 3.dp))
    }
}

// ── "Call me about this" (iOS App/Calls/CallMeSection.swift, copy verbatim) ──
// Book / update / cancel the call_requests row anchored to this task's next
// scheduled block. Lead-relative (`lead_min` + `block_id`, so the server follows
// the block if it moves); disabled with a hint until the task has a scheduled
// time. Notes are one per line (a note may contain ";"), 20 × 300 chars like
// the web — they're read back verbatim when the phone rings. The writes run
// through the executor's request_call / update_call / cancel_call
// (AppViewModel.bookTaskCall / updateTaskCall / cancelCallRequest) so the editor
// gets the assistant's guards + duplicate rule; a compare-and-set miss reloads
// instead of showing stale state.

/** The section's pure half — unit-tested (TaskDetailCallMeLogicTest). */
object CallMeLogic {
    const val SECTION_TITLE = "Call me about this"
    const val SCHEDULE_FIRST = "Schedule it first — the call rings a few minutes before the task starts."
    const val OFF_HINT = "Your phone rings before it starts and reads your notes back."
    const val NOTES_LABEL = "Notes to read back — one per line"
    const val BOOK = "Book call"
    const val UPDATE = "Update call"
    const val CANCEL_FAILED = "Couldn't cancel the call — try again."
    const val BOOK_FAILED = "Couldn't book the call — check your connection and try again."
    const val CHANGED_UNDERNEATH = "That call changed underneath you — reloaded."

    fun chip(minutes: Int): String = "${minutes}m before"
    fun ringsLine(callAtMs: Long): String = "Rings ${CallToolLogic.fmt(callAtMs)}"

    /** A call needs a scheduled start and a task that isn't parked in Later. */
    fun canBook(blockStartMs: Long?, later: Boolean?): Boolean = blockStartMs != null && later != true

    fun callAtMs(blockStartMs: Long?, leadMin: Int): Long? = blockStartMs?.let { it - leadMin * 60_000L }

    /** One note per line — trimmed, blanks dropped, 300 chars × 20 (web / CallToolLogic.notes). */
    fun notes(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        .map { it.take(CallToolLogic.MAX_NOTE_LENGTH) }
        .take(CallToolLogic.MAX_NOTES)

    /** Anything to save? No row yet ⇒ always; else notes / lead / anchor differ. */
    fun dirty(row: CallRequest?, notes: List<String>, leadMin: Int, nextBlockId: String?): Boolean {
        if (row == null) return true
        return row.notes != notes || row.leadMin != leadMin || row.blockId != nextBlockId
    }

    fun isOk(result: String): Boolean = result.startsWith("ok")

    /** cancel_call / update_call on a row that already rang / was cancelled
     *  elsewhere ("error: that call is already <status>…") — gone either way. */
    fun isAlreadyGone(result: String): Boolean = result.startsWith("error: that call is already ")

    /** The tool's contract string → the copy the section shows (iOS: the error
     *  minus "error: ", first letter capitalised; the two iOS-local strings map
     *  to their sentences). */
    fun userMessage(result: String): String = when {
        isOk(result) -> ""
        result == CallToolLogic.NETWORK -> BOOK_FAILED
        result == CallToolLogic.CHANGED_UNDERNEATH -> CHANGED_UNDERNEATH
        else -> result.removePrefix("error: ").replaceFirstChar { it.uppercase() }
    }

    /** The row a successful request_call describes when the read-back fails
     *  (offline right after): `ok: call booked <date> <time> "<label>" (<n> notes) id=<id>`. */
    fun rowFromResult(result: String, userId: String?, taskId: String, blockId: String?, leadMin: Int, notes: List<String>, callAtMs: Long): CallRequest? {
        val m = Regex("^ok: call booked \\S+ \\S+ \"(.*)\" \\(\\d+ notes?\\) id=(\\S+)$").find(result) ?: return null
        return CallRequest(
            id = m.groupValues[2], userId = userId, taskId = taskId, blockId = blockId,
            callAt = tech.csalliance.unstuck.sync.CallsClient.iso(callAtMs), leadMin = leadMin, label = m.groupValues[1], notes = notes,
        )
    }
}

@Composable
internal fun CallMeSection(vm: AppViewModel, task: TaskItem, taskBlocks: List<CalBlock>) {
    val c = UTheme.colors
    val scope = rememberCoroutineScope()
    val callSettings by vm.callSettings.collectAsStateWithLifecycle()
    var loaded by remember(task.id) { mutableStateOf(false) }
    var row by remember(task.id) { mutableStateOf<CallRequest?>(null) }
    var enabled by remember(task.id) { mutableStateOf(false) }
    var lead by remember(task.id) { mutableStateOf(callSettings.defaultLeadMin) }
    var notesText by remember(task.id) { mutableStateOf("") }
    var busy by remember(task.id) { mutableStateOf(false) }
    var error by remember(task.id) { mutableStateOf<String?>(null) }

    // The same anchor request_call uses: the task's NEXT live block.
    val nextBlock = nextLiveBlock(taskBlocks, Clock.todayIso(), task.id)
    val blockStart = nextBlock?.let { CallToolLogic.blockStartMs(it) }
    val canBook = CallMeLogic.canBook(blockStart, task.later)
    val notes = CallMeLogic.notes(notesText)
    val callAt = CallMeLogic.callAtMs(blockStart, lead)

    suspend fun load() {
        val existing = vm.callForTask(task.id)
        row = existing
        if (existing != null) {
            enabled = true
            lead = existing.leadMin ?: callSettings.defaultLeadMin
            notesText = existing.notes.joinToString("\n")
        }
        loaded = true
    }
    LaunchedEffect(task.id) { load() }

    fun toggle(on: Boolean) {
        error = null
        if (on) { enabled = true; return }
        enabled = false
        val r = row ?: return
        busy = true
        scope.launch {
            val res = vm.cancelCallRequest(r.id)
            // "already <status>" ⇒ it rang / was cancelled elsewhere — gone either way.
            if (CallMeLogic.isOk(res) || CallMeLogic.isAlreadyGone(res)) row = null
            else { error = CallMeLogic.CANCEL_FAILED; enabled = true }
            busy = false
        }
    }

    fun save() {
        val at = callAt ?: return
        val block = nextBlock ?: return
        error = null
        busy = true
        val leadNow = lead
        val notesNow = notes
        scope.launch {
            val existing = row
            val res = if (existing != null) vm.updateTaskCall(existing.id, leadNow, notesNow) else vm.bookTaskCall(task.id, leadNow, notesNow)
            when {
                CallMeLogic.isOk(res) -> row = vm.callForTask(task.id)
                    ?: CallMeLogic.rowFromResult(res, existing?.userId, task.id, block.id, leadNow, notesNow, at) ?: existing
                // Zero rows: the call rang / was cancelled underneath us.
                res == CallToolLogic.CHANGED_UNDERNEATH || CallMeLogic.isAlreadyGone(res) -> {
                    error = CallMeLogic.CHANGED_UNDERNEATH
                    enabled = false
                    load()
                }
                else -> error = CallMeLogic.userMessage(res)
            }
            busy = false
        }
    }

    val toggleLocked = !canBook || busy || !loaded
    Card(Modifier.fillMaxWidth().padding(top = 18.dp), radius = 14) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(CallMeLogic.SECTION_TITLE, Modifier.weight(1f))
                MdToggle(
                    enabled, { if (!toggleLocked) toggle(it) },
                    Modifier.alpha(if (toggleLocked) 0.5f else 1f)
                        .semantics { contentDescription = CallMeLogic.SECTION_TITLE },
                )
            }
            when {
                !canBook -> Text(CallMeLogic.SCHEDULE_FIRST, style = UFont.sans(12), color = c.ink3)
                enabled -> Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.bg2).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tech.csalliance.unstuck.calls.CallSettingsStore.LEAD_OPTIONS.forEach { m ->
                            SelectableChip(CallMeLogic.chip(m), selected = lead == m) { lead = m }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(CallMeLogic.NOTES_LABEL, style = UFont.sans(11, FontWeight.Medium), color = c.ink3)
                        BasicTextField(
                            value = notesText, onValueChange = { notesText = it },
                            textStyle = UFont.sans(14).copy(color = c.ink), cursorBrush = SolidColor(c.ink),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
                                .clip(RoundedCornerShape(10.dp)).background(c.surface).padding(8.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        callAt?.let { Text(CallMeLogic.ringsLine(it), style = UFont.sans(12), color = c.ink2) }
                        Box(Modifier.weight(1f))
                        UButton(
                            if (row == null) CallMeLogic.BOOK else CallMeLogic.UPDATE, kind = ButtonKind.DARK, fill = false,
                            enabled = !busy && CallMeLogic.dirty(row, notes, lead, nextBlock?.id),
                        ) { save() }
                    }
                }
                loaded -> Text(CallMeLogic.OFF_HINT, style = UFont.sans(12), color = c.ink3)
            }
            error?.let { Text(it, style = UFont.sans(12), color = c.red) }
        }
    }
}
