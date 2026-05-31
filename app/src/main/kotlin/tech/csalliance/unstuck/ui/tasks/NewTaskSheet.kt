package tech.csalliance.unstuck.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.findConflicts
import tech.csalliance.unstuck.core.logic.findFreeSlotsForDate
import tech.csalliance.unstuck.core.logic.formatTime
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.time.Time
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.SheetHandle
import tech.csalliance.unstuck.design.component.SheetScrim
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

private class DraftCapture {
    var body by mutableStateOf("")
    var tag by mutableStateOf(CaptureTag.FOLLOW_UP)
}

private val CAPTURE_TAGS = listOf(
    CaptureTag.FOLLOW_UP to "follow-up", CaptureTag.IDEA to "idea", CaptureTag.EDIT to "edit",
    CaptureTag.QUESTION to "question", CaptureTag.DISTRACTION to "distraction",
)

private fun tomorrowIso(now: Long): String = Clock.dateIso(Time.addDaysMillis(Time.startOfDayMillis(now), 1))

/**
 * New-task sheet — scheduler-first, mirroring the web task-create-modal:
 * WHEN is mandatory (today / tomorrow / pick a date / later), free-slot time
 * chips with a conflict warning, optional recurrence, optional capture drafts.
 * No priority picker (the web + DB don't surface one).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTaskSheet(vm: AppViewModel, prefillDate: String? = null, prefillTime: String? = null, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val c = UTheme.colors
    val areas by vm.lifeAreas.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val now = vm.nowMs()
    val todayIso = Clock.dateIso(Time.startOfDayMillis(now))
    val tmrwIso = tomorrowIso(now)

    var name by remember { mutableStateOf("") }
    var whenSel by remember { mutableStateOf(when (prefillDate) { null, todayIso -> "Today"; tmrwIso -> "Tomorrow"; else -> "Pick date" }) }
    var pickedDate by remember { mutableStateOf(prefillDate?.takeIf { it != todayIso && it != tmrwIso } ?: tmrwIso) }
    var pickedTime by remember { mutableStateOf(prefillTime) }
    var autoTime by remember { mutableStateOf(prefillTime == null) }  // false once the user/prefill sets a time
    var estimate by remember { mutableStateOf(settings.focusDefaultMin) }
    var area by remember { mutableStateOf<String?>(null) }
    var firstMove by remember { mutableStateOf("") }
    var recurrence by remember { mutableStateOf<tech.csalliance.unstuck.core.model.Recurrence?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showEstimate by remember { mutableStateOf(false) }
    var reminderLead by remember { mutableStateOf<Int?>(null) }   // null = use the global default
    val tags = remember { mutableStateListOf<String>() }
    val drafts = remember { mutableStateListOf<DraftCapture>() }

    val effectiveDate: String? = when (whenSel) {
        "Later" -> null
        "Today" -> todayIso
        "Tomorrow" -> tmrwIso
        else -> pickedDate
    }
    val slots = remember(effectiveDate, estimate, blocks) {
        if (effectiveDate == null) emptyList() else findFreeSlotsForDate(blocks, estimate, effectiveDate, now, limit = 4)
    }
    // Auto-pick the first free slot when the date/estimate changes — unless the
    // user (or a calendar-slot prefill) chose a specific time.
    LaunchedEffect(effectiveDate, estimate, whenSel) {
        if (whenSel == "Later") pickedTime = null
        else if (autoTime) pickedTime = slots.firstOrNull()?.startTime
    }
    val conflicts = if (effectiveDate != null && pickedTime != null) findConflicts(effectiveDate, pickedTime!!, estimate, blocks) else emptyList()
    val canSubmit = name.isNotBlank()

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface, scrimColor = SheetScrim,
        dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { SheetHandle() } },
    ) {
        // Scrollable + keyboard-aware: imePadding lifts the content above the keyboard,
        // verticalScroll lets every field be reached when the IME is open.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 22.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("New task")
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("What's the next thing on your mind?") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            SectionLabel("When")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Today", "Tomorrow", "Pick date", "Later").forEach { w ->
                    SelectableChip(if (w == "Pick date" && whenSel == "Pick date") pickedDate.takeLast(5) else w, selected = whenSel == w) {
                        whenSel = w
                        autoTime = true   // re-auto-pick a slot for the new date
                        if (w == "Pick date") showDatePicker = true
                    }
                }
            }

            // Free-slot time chips + conflict warning (hidden for Later).
            if (whenSel != "Later") {
                SectionLabel("Time")
                if (slots.isEmpty()) {
                    Text("No free slots that day — it'll be added without a set time.", style = tech.csalliance.unstuck.design.theme.UFont.sans(12), color = c.ink3)
                } else {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val pt = pickedTime
                        if (pt != null && slots.none { it.startTime == pt }) SelectableChip(formatTime(pt), selected = true) {}
                        slots.forEach { s -> SelectableChip(formatTime(s.startTime), selected = pickedTime == s.startTime) { pickedTime = s.startTime; autoTime = false } }
                    }
                }
                if (conflicts.isNotEmpty()) {
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(c.amberSoft).padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text("Overlaps ${conflicts.first().block.taskName}", style = tech.csalliance.unstuck.design.theme.UFont.sans(12), color = c.amberInk)
                    }
                }
            }

            SectionLabel("Estimate")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val presets = listOf(15, 25, 45, 60, 90)
                presets.forEach { m -> SelectableChip("${m}m", selected = estimate == m) { estimate = m } }
                if (estimate !in presets) SelectableChip("${estimate}m", selected = true) { showEstimate = true }
                SelectableChip("Custom…", selected = false) { showEstimate = true }
            }

            // Pre-task reminder (only meaningful when it has a time). "Default" uses
            // the global lead from Settings; pick a specific lead to override this task.
            if (whenSel != "Later") {
                SectionLabel("Remind me")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectableChip("Default", selected = reminderLead == null) { reminderLead = null }
                    SelectableChip("Off", selected = reminderLead == 0) { reminderLead = 0 }
                    listOf(5, 10, 15).forEach { m -> SelectableChip("${m}m before", selected = reminderLead == m) { reminderLead = m } }
                }
            }

            if (areas.isNotEmpty()) {
                SectionLabel("Area")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tech.csalliance.unstuck.design.component.FilterPill("Unassigned", area == null) { area = null }
                    areas.forEach { a -> tech.csalliance.unstuck.design.component.FilterPill(a.name, area == a.name, dotColor = c.areaColor(a.color)) { area = if (area == a.name) null else a.name } }
                }
            }

            SectionLabel("First step", color = c.coral)
            OutlinedTextField(value = firstMove, onValueChange = { firstMove = it }, label = { Text("The smallest concrete step…") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            SectionLabel("Tags")
            tech.csalliance.unstuck.ui.components.TagPicker(vm, tags.toList()) { tags.clear(); tags.addAll(it) }

            tech.csalliance.unstuck.ui.components.RecurrenceEditor(recurrence) { recurrence = it }

            // Capture-a-thought drafts — these auto-save against the new task when
            // you hit "Add task", so there's no per-draft Add: just a cancel (✕) to
            // drop one. Tags use the dotted Area-pill aesthetic.
            SectionLabel("Capture a thought", color = c.primaryDeep)
            drafts.forEachIndexed { idx, d ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg2).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BasicTextField(
                            value = d.body, onValueChange = { d.body = it },
                            textStyle = tech.csalliance.unstuck.design.theme.UFont.sans(13).copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.ink),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner -> if (d.body.isEmpty()) Text("Something on your mind…", style = tech.csalliance.unstuck.design.theme.UFont.sans(13), color = c.ink3); inner() },
                        )
                        Icon(Icons.Filled.Close, contentDescription = "Remove", tint = c.ink3, modifier = Modifier.size(18.dp).clickable { drafts.removeAt(idx) })
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CAPTURE_TAGS.forEach { (t, label) -> tech.csalliance.unstuck.design.component.FilterPill(label, selected = d.tag == t, dotColor = captureTagDot(t)) { d.tag = t } }
                    }
                }
            }
            Box(
                Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, c.line2, RoundedCornerShape(999.dp)).clickable { drafts.add(DraftCapture()) }.padding(horizontal = 12.dp, vertical = 7.dp),
            ) { Text("+ Capture", style = tech.csalliance.unstuck.design.theme.UFont.sans(12, FontWeight.Medium), color = c.ink2) }

            // Coral accent once the form is valid (a name is entered); muted dark until then.
            UButton("Add task", kind = if (canSubmit) ButtonKind.CORAL else ButtonKind.DARK, enabled = canSubmit) {
                val t = vm.addTask(
                    name = name, estimateMin = estimate, lifeArea = area, tags = tags.toList().ifEmpty { null },
                    firstPhysicalAction = firstMove.trim().ifEmpty { null }, recurrence = recurrence,
                    later = whenSel == "Later",
                )
                reminderLead?.let { vm.setReminderOverride(t.id, it) }
                if (whenSel != "Later" && effectiveDate != null && pickedTime != null) {
                    vm.scheduleTask(t, effectiveDate, pickedTime!!)
                }
                drafts.filter { it.body.isNotBlank() }.forEach { vm.saveCapture(t.id, null, it.tag, it.body.trim()) }
                onDismiss()
            }
        }
    }

    if (showDatePicker) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = now)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { pickedDate = Clock.dateIso(it) }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = dpState) }
    }

    if (showEstimate) {
        var v by remember { mutableStateOf(estimate.toString()) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showEstimate = false },
            title = { Text("Estimate (minutes)") },
            text = {
                OutlinedTextField(
                    value = v, onValueChange = { s -> v = s.filter { it.isDigit() }.take(4) }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                )
            },
            confirmButton = { TextButton(onClick = { v.toIntOrNull()?.takeIf { it > 0 }?.let { estimate = it }; showEstimate = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showEstimate = false }) { Text("Cancel") } },
            containerColor = c.surface,
        )
    }
}
