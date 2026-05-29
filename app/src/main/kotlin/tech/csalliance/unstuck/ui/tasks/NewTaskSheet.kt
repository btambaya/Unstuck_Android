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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
fun NewTaskSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val c = UTheme.colors
    val areas by vm.lifeAreas.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val now = vm.nowMs()

    var name by remember { mutableStateOf("") }
    var whenSel by remember { mutableStateOf("Today") }       // Today | Tomorrow | Pick date | Later
    var pickedDate by remember { mutableStateOf(tomorrowIso(now)) }
    var pickedTime by remember { mutableStateOf<String?>(null) }
    var estimate by remember { mutableStateOf(settings.focusDefaultMin) }
    var area by remember { mutableStateOf<String?>(null) }
    var firstMove by remember { mutableStateOf("") }
    var recurrence by remember { mutableStateOf<tech.csalliance.unstuck.core.model.Recurrence?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    val tags = remember { mutableStateListOf<String>() }
    val drafts = remember { mutableStateListOf<DraftCapture>() }

    val effectiveDate: String? = when (whenSel) {
        "Later" -> null
        "Today" -> Clock.dateIso(Time.startOfDayMillis(now))
        "Tomorrow" -> tomorrowIso(now)
        else -> pickedDate
    }
    val slots = remember(effectiveDate, estimate, blocks) {
        if (effectiveDate == null) emptyList() else findFreeSlotsForDate(blocks, estimate, effectiveDate, now, limit = 4)
    }
    // Auto-pick the first free slot when the date / estimate changes.
    LaunchedEffect(effectiveDate, estimate, whenSel) {
        pickedTime = if (whenSel == "Later") null else slots.firstOrNull()?.startTime
    }
    val conflicts = if (effectiveDate != null && pickedTime != null) findConflicts(effectiveDate, pickedTime!!, estimate, blocks) else emptyList()
    val canSubmit = name.isNotBlank() && (whenSel == "Later" || pickedTime != null)

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface, scrimColor = SheetScrim,
        dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { SheetHandle() } },
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 22.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("New task")
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("What's the next thing on your mind?") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            SectionLabel("When")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Today", "Tomorrow", "Pick date", "Later").forEach { w ->
                    SelectableChip(if (w == "Pick date" && whenSel == "Pick date") pickedDate.takeLast(5) else w, selected = whenSel == w) {
                        whenSel = w
                        if (w == "Pick date") showDatePicker = true
                    }
                }
            }

            // Free-slot time chips + conflict warning (hidden for Later).
            if (whenSel != "Later") {
                SectionLabel("Time")
                if (slots.isEmpty()) {
                    Text("No free slots that day — it'll still be scheduled.", style = tech.csalliance.unstuck.design.theme.UFont.sans(12), color = c.ink3)
                } else {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        slots.forEach { s -> SelectableChip(formatTime(s.startTime), selected = pickedTime == s.startTime) { pickedTime = s.startTime } }
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
                listOf(15, 25, 45, 60, 90).forEach { m -> SelectableChip("${m}m", selected = estimate == m) { estimate = m } }
            }

            if (areas.isNotEmpty()) {
                SectionLabel("Area")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    areas.forEach { a -> SelectableChip(a.name, selected = area == a.name) { area = if (area == a.name) null else a.name } }
                }
            }

            SectionLabel("First step", color = c.coral)
            OutlinedTextField(value = firstMove, onValueChange = { firstMove = it }, label = { Text("The smallest concrete step…") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            SectionLabel("Tags")
            tech.csalliance.unstuck.ui.components.TagPicker(vm, tags.toList()) { tags.clear(); tags.addAll(it) }

            tech.csalliance.unstuck.ui.components.RecurrenceEditor(recurrence) { recurrence = it }

            // Capture-a-thought drafts (saved against the new task on submit).
            SectionLabel("Capture a thought", color = c.primaryDeep)
            drafts.forEachIndexed { idx, d ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg2).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicTextField(
                        value = d.body, onValueChange = { d.body = it },
                        textStyle = tech.csalliance.unstuck.design.theme.UFont.sans(13).copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.ink),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner -> if (d.body.isEmpty()) Text("Something on your mind…", style = tech.csalliance.unstuck.design.theme.UFont.sans(13), color = c.ink3); inner() },
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CAPTURE_TAGS.forEach { (t, label) -> SelectableChip(label, selected = d.tag == t) { d.tag = t } }
                        Text("Remove", style = tech.csalliance.unstuck.design.theme.UFont.sans(12), color = c.ink3, modifier = Modifier.clickable { drafts.removeAt(idx) }.padding(horizontal = 6.dp, vertical = 5.dp))
                    }
                }
            }
            Box(
                Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, c.line2, RoundedCornerShape(999.dp)).clickable { drafts.add(DraftCapture()) }.padding(horizontal = 12.dp, vertical = 7.dp),
            ) { Text("+ Capture", style = tech.csalliance.unstuck.design.theme.UFont.sans(12, FontWeight.Medium), color = c.ink2) }

            UButton("Add task", kind = ButtonKind.DARK, enabled = canSubmit) {
                val t = vm.addTask(
                    name = name, estimateMin = estimate, lifeArea = area, tags = tags.toList().ifEmpty { null },
                    firstPhysicalAction = firstMove.trim().ifEmpty { null }, recurrence = recurrence,
                    later = whenSel == "Later",
                )
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
}
