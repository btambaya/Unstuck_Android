package tech.csalliance.unstuck.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.clampEstimateMin
import tech.csalliance.unstuck.core.logic.findConflicts
import tech.csalliance.unstuck.core.logic.findFreeSlotsForDate
import tech.csalliance.unstuck.core.logic.newTaskNeedsTime
import tech.csalliance.unstuck.core.logic.sharePicksInRosterOrder
import tech.csalliance.unstuck.core.logic.shareWithSummary
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.time.ClockFormat
import tech.csalliance.unstuck.core.time.ClockMode
import tech.csalliance.unstuck.core.time.Time
import tech.csalliance.unstuck.core.time.WireTime
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.SheetHandle
import tech.csalliance.unstuck.design.component.SheetScrim
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.sharing.ShareScreen
import tech.csalliance.unstuck.ui.sharing.ShareTarget
import tech.csalliance.unstuck.ui.sharing.ShareWithRow

// Savers so the draft survives a config change (rotation / dark-mode flip / locale /
// split-screen): Activity recreation must not silently discard a half-typed task.

/** [kind, until, daysOfWeek("|"-joined), interval, anchor] strings; empty list =
 *  does not repeat (interval + anchor for every N weeks only). */
internal val RecurrenceSaver = listSaver<Recurrence?, String>(
    save = { r ->
        when (r) {
            null -> emptyList()
            is Recurrence.Daily -> listOf("daily", r.until.orEmpty())
            is Recurrence.Monthly -> listOf("monthly", r.until.orEmpty())
            is Recurrence.Weekly -> listOf("weekly", r.until.orEmpty(), r.daysOfWeek.joinToString("|"))
            is Recurrence.EveryNWeeks -> listOf("everyNWeeks", r.until.orEmpty(), r.daysOfWeek.joinToString("|"), r.interval.toString(), r.anchor)
        }
    },
    restore = { saved ->
        val until = saved.getOrNull(1)?.ifBlank { null }
        val days = saved.getOrNull(2)?.split("|")?.mapNotNull { it.toIntOrNull() }.orEmpty()
        when (saved.firstOrNull()) {
            "daily" -> Recurrence.Daily(until)
            "monthly" -> Recurrence.Monthly(until)
            "weekly" -> Recurrence.Weekly(days, until)
            "everyNWeeks" -> {
                val n = saved.getOrNull(3)?.toIntOrNull()
                val anchor = saved.getOrNull(4)
                if (n != null && n >= 2 && anchor != null) Recurrence.EveryNWeeks(n, days, anchor, until) else Recurrence.Weekly(days, until)
            }
            else -> null
        }
    },
)

private val TagsSaver = listSaver<SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)

/** Pending "Share with…" picks save as "userId|LEVEL" strings (not picked = absent —
 *  nothing is shared until "Add task", so there's no unshare to remember). */
private val ShareLevelsSaver = listSaver<SnapshotStateMap<String, ShareLevel>, String>(
    save = { m -> m.map { (id, level) -> "$id|${level.name}" } },
    restore = { saved ->
        val m = mutableStateMapOf<String, ShareLevel>()
        saved.forEach { s ->
            runCatching { ShareLevel.valueOf(s.substringAfterLast("|")) }.getOrNull()?.let { m[s.substringBeforeLast("|")] = it }
        }
        m
    },
)

private fun tomorrowIso(now: Long): String = Clock.dateIso(Time.addDaysMillis(Time.startOfDayMillis(now), 1))

/**
 * New-task sheet — four serif "conversational" questions (what / when / how
 * long / which area) + a collapsed "More options" disclosure (share, tags,
 * repeat), replacing the old 11-section stack that users found overwhelming.
 * WHEN stays mandatory (today / tomorrow / pick a date / later) with free-slot
 * time chips and a conflict warning. First step, capture drafts and the
 * reminder override moved to the edit screen; new tasks use the global default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTaskSheet(vm: AppViewModel, prefillDate: String? = null, prefillTime: String? = null, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val c = UTheme.colors
    val areas by vm.lifeAreas.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val members by vm.circle.collectAsStateWithLifecycle()
    val now = vm.nowMs()
    val todayIso = Clock.dateIso(Time.startOfDayMillis(now))
    val tmrwIso = tomorrowIso(now)

    // rememberSaveable (not remember): a config change recreates the Activity and
    // must restore — not discard — everything typed in the primary creation flow.
    var name by rememberSaveable { mutableStateOf("") }
    var whenSel by rememberSaveable { mutableStateOf(when (prefillDate) { null, todayIso -> "Today"; tmrwIso -> "Tomorrow"; else -> "Pick date" }) }
    var pickedDate by rememberSaveable { mutableStateOf(prefillDate?.takeIf { it != todayIso && it != tmrwIso } ?: tmrwIso) }
    var pickedTime by rememberSaveable { mutableStateOf(prefillTime) }
    var autoTime by rememberSaveable { mutableStateOf(prefillTime == null) }  // false once the user/prefill sets a time
    var estimate by rememberSaveable { mutableStateOf(settings.focusDefaultMin) }
    var area by rememberSaveable { mutableStateOf<String?>(null) }
    var recurrence by rememberSaveable(stateSaver = RecurrenceSaver) { mutableStateOf<Recurrence?>(null) }
    // The "Starts" chip tapped (its week's Monday); null = the first chip. Kept
    // apart from the rule so a later change of day re-derives week one
    // (RecurrenceEditorModel.createRule).
    var startsPick by rememberSaveable { mutableStateOf<String?>(null) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var showEstimate by rememberSaveable { mutableStateOf(false) }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    val tags = rememberSaveable(saver = TagsSaver) { mutableStateListOf<String>() }
    val shareLevels = rememberSaveable(saver = ShareLevelsSaver) { mutableStateMapOf<String, ShareLevel>() }
    // The Share screen in its pre-create mode, over this sheet (the "Share with…" row).
    var showShare by rememberSaveable { mutableStateOf(false) }

    val effectiveDate: String? = when (whenSel) {
        "Later" -> null
        "Today" -> todayIso
        "Tomorrow" -> tmrwIso
        else -> pickedDate
    }
    // The phone's 12/24-hour setting — the time chips and the picker follow it.
    val clock = tech.csalliance.unstuck.ui.components.clockMode()
    val slots = remember(effectiveDate, estimate, blocks, clock) {
        if (effectiveDate == null) emptyList() else findFreeSlotsForDate(blocks, estimate, effectiveDate, now, limit = 4, clock = clock)
    }
    // Auto-pick the first free slot when the date/estimate changes — unless the
    // user (or a calendar-slot prefill) chose a specific time.
    LaunchedEffect(effectiveDate, estimate, whenSel, slots) {
        if (whenSel == "Later") pickedTime = null
        else if (autoTime) pickedTime = slots.firstOrNull()?.startTime
    }
    val conflicts = if (effectiveDate != null && pickedTime != null) findConflicts(effectiveDate, pickedTime!!, estimate, blocks) else emptyList()
    // A repeating task needs a day and a time, and a one-off for a later day a
    // time: every occurrence is a timed block and nothing can invent the time
    // later, so an evening (the free-slot finder stops at 18:00) or Later repeat
    // was saved with no occurrences at all (parity with iOS build 81, audit
    // 2026-09-22 C7).
    val needsTime = newTaskNeedsTime(repeats = recurrence != null, date = effectiveDate, todayIso = todayIso, pickedTime = pickedTime)
    val canSubmit = name.isNotBlank() && !needsTime
    val focusManager = LocalFocusManager.current

    // Shared by the "Add task" button AND the name field's IME Done.
    // Pending share picks are handed to addTask, which applies them AFTER the
    // task row lands on the server, IN THE SAME write coroutine as the upsert
    // — so task_share can't race the not-yet-committed insert (not_your_task →
    // silently dropped, the live T2 bug). Failures are logged, not swallowed.
    fun submit() {
        if (!canSubmit) return
        // Every N weeks: week one is the "Starts" chip shown as picked (the first
        // unless one was tapped); the series is scheduled from the picked day,
        // never re-anchored, so a later chip keeps that day as a one-off before
        // its weeks (spec §5; web's create modal, canonical).
        val model = tech.csalliance.unstuck.ui.components.RecurrenceEditorModel
        val (rule, firstDate) = if (effectiveDate != null) {
            model.createStart(model.createRule(recurrence, startsPick, effectiveDate), effectiveDate)
        } else model.createRule(recurrence, startsPick, todayIso) to null
        val t = vm.addTask(
            name = name, estimateMin = estimate, lifeArea = area, tags = tags.toList().ifEmpty { null },
            firstPhysicalAction = null, recurrence = rule,
            later = whenSel == "Later",
            shares = shareLevels.toMap(),
        )
        if (whenSel != "Later" && firstDate != null && pickedTime != null) {
            vm.scheduleTask(t, firstDate, pickedTime!!, reanchor = false)
        }
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface, scrimColor = SheetScrim,
        dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { SheetHandle() } },
    ) {
        // Scrollable + keyboard-aware: imePadding lifts the content above the keyboard,
        // verticalScroll lets every field be reached when the IME is open.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 22.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Close", style = tech.csalliance.unstuck.design.theme.UFont.sans(14, FontWeight.Medium), color = c.ink2,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable(onClick = onDismiss).padding(horizontal = 6.dp, vertical = 4.dp),
                )
                Text("New task", style = tech.csalliance.unstuck.design.theme.UFont.serif(22, italic = true), color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                // Invisible twin of "Close" so the weighted title stays optically centered.
                Text("Close", style = tech.csalliance.unstuck.design.theme.UFont.sans(14, FontWeight.Medium), color = Color.Transparent, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("What's on your mind?", style = tech.csalliance.unstuck.design.theme.UFont.serif(22), color = c.ink)
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("What's the next thing on your mind?") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    // The only free-text field in the main flow → Done = the form's
                    // primary action once valid; otherwise just drop the keyboard.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canSubmit) submit() else focusManager.clearFocus() }),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("When?", style = tech.csalliance.unstuck.design.theme.UFont.serif(22), color = c.ink)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Today", "Tomorrow", "Pick date", "Later").forEach { w ->
                        SelectableChip(if (w == "Pick date" && whenSel == "Pick date") pickedDate.takeLast(5) else w, selected = whenSel == w) {
                            // Re-auto-pick a slot for the new date ONLY if the user hasn't set an
                            // explicit time — changing the date must not silently overwrite a
                            // custom/prefilled time (it's a date-independent HH:MM that should ride along).
                            if (pickedTime == null) autoTime = true
                            // "Pick date" only commits AFTER the dialog's OK — so cancelling
                            // leaves the previous selection instead of a stale "Pick date".
                            if (w == "Pick date") showDatePicker = true else whenSel = w
                        }
                    }
                }

                // Free-slot chips + a custom time picker + conflict warning (hidden for Later).
                if (whenSel != "Later") {
                    SectionLabel("Time")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val pt = pickedTime
                        SelectableChip("Custom…", selected = false) { showTimePicker = true }
                        // The chosen time (a prefilled/custom one not in the suggestions) — tap to change.
                        if (pt != null && slots.none { it.startTime == pt }) SelectableChip(ClockFormat.time(pt, clock), selected = true) { showTimePicker = true }
                        slots.forEach { s -> SelectableChip(ClockFormat.time(s.startTime, clock), selected = pickedTime == s.startTime) { pickedTime = s.startTime; autoTime = false } }
                    }
                    if (slots.isEmpty() && pickedTime == null) {
                        // "…added without one" is only true for a one-off today (C7).
                        Text(
                            if (needsTime) "No free slots that day — pick a custom time."
                            else "No free slots that day — pick a custom time, or it'll be added without one.",
                            style = tech.csalliance.unstuck.design.theme.UFont.sans(12), color = c.ink3,
                        )
                    }
                    if (conflicts.isNotEmpty()) {
                        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(c.amberSoft).padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text("Overlaps ${conflicts.first().block.taskName}", style = tech.csalliance.unstuck.design.theme.UFont.sans(12), color = c.amberInk)
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("How long?", style = tech.csalliance.unstuck.design.theme.UFont.serif(22), color = c.ink)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val presets = listOf(15, 25, 45, 90)
                    SelectableChip("Custom…", selected = false) { showEstimate = true }
                    presets.forEach { m -> SelectableChip("${m}m", selected = estimate == m) { estimate = m } }
                    if (estimate !in presets) SelectableChip("${estimate}m", selected = true) { showEstimate = true }
                }
            }

            if (areas.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Which area?", style = tech.csalliance.unstuck.design.theme.UFont.serif(22), color = c.ink)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tech.csalliance.unstuck.design.component.FilterPill("Unassigned", area == null) { area = null }
                        areas.forEach { a -> tech.csalliance.unstuck.design.component.FilterPill(a.name, area == a.name, dotColor = c.areaColor(a.color)) { area = if (area == a.name) null else a.name } }
                    }
                }
            }

            // "More options" disclosure — share / tags / repeat stay one tap away so
            // the four questions above are all a first-time user has to answer.
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { moreOpen = !moreOpen }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (moreOpen) "More options ▴" else "More options ▾", style = tech.csalliance.unstuck.design.theme.UFont.sans(14, FontWeight.SemiBold), color = c.ink2)
                    Box(Modifier.weight(1f))
                    Text("Share · Tags · Repeat", style = tech.csalliance.unstuck.design.theme.UFont.sans(12), color = c.ink3)
                }

                if (moreOpen) {
                    // ONE row — who's picked, in a line — opening the Share screen
                    // in its pre-create mode (the per-person Off/View/Partner/Assign
                    // cards were "terrible", Ahmad 2026-09-24).
                    SectionLabel("Share")
                    ShareWithRow(shareWithSummary(sharePicksInRosterOrder(members, shareLevels))) { showShare = true }

                    SectionLabel("Tags")
                    tech.csalliance.unstuck.ui.components.TagPicker(vm, tags.toList()) { tags.clear(); tags.addAll(it) }

                    val repeatModel = tech.csalliance.unstuck.ui.components.RecurrenceEditorModel
                    val repeatBase = effectiveDate ?: todayIso
                    tech.csalliance.unstuck.ui.components.RecurrenceEditor(
                        repeatModel.createRule(recurrence, startsPick, repeatBase), todayIso = todayIso, startIso = repeatBase,
                        onStartsPick = { startsPick = it },
                    ) { r ->
                        // A new rhythm starts from its first chip again (web parity);
                        // a day toggle keeps the pick while it is still a chip.
                        if (repeatModel.intervalOf(r) != repeatModel.intervalOf(recurrence)) startsPick = null
                        recurrence = r
                    }
                }
            }

            if (needsTime) {
                Text(
                    if (whenSel == "Later" && recurrence != null) "A repeating task needs a day and a time — pick Today, Tomorrow or a date."
                    else "Pick a time to add this task.",
                    style = tech.csalliance.unstuck.design.theme.UFont.sans(12), color = c.ink3,
                )
            }
            // Coral accent once the form is valid (a name, and a time where one is
            // needed); muted dark until then.
            UButton("Add task", kind = if (canSubmit) ButtonKind.CORAL else ButtonKind.DARK, enabled = canSubmit) { submit() }
        }
    }

    if (showShare) {
        // Picks come back live, so the sheet holds them however the screen closes;
        // submit() hands them to addTask exactly as before.
        ShareScreen(
            vm, ShareTarget.NewTask(name), picks = shareLevels.toMap(),
            onPicks = { p -> shareLevels.clear(); shareLevels.putAll(p) },
            onDismiss = { showShare = false },
        )
    }

    if (showDatePicker) {
        // Material3's selectedDateMillis is UTC-midnight — read/seed it in UTC, NOT
        // the local zone, or west-of-UTC users land one calendar day early.
        val dpState = rememberDatePickerState(
            // Seed from the already-picked date so re-opening shows it (not always today).
            initialSelectedDateMillis = (runCatching { java.time.LocalDate.parse(pickedDate) }.getOrNull() ?: java.time.LocalDate.now())
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { pickedDate = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString() }
                    whenSel = "Pick date"   // commit only on OK
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = dpState) }
    }

    if (showTimePicker) {
        val tpState = rememberTimePickerState(
            initialHour = pickedTime?.substringBefore(":")?.toIntOrNull() ?: 9,
            initialMinute = pickedTime?.substringAfter(":")?.toIntOrNull() ?: 0,
            is24Hour = clock == ClockMode.H24,
        )
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickedTime = WireTime.hm(tpState.hour, tpState.minute); autoTime = false; showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = tpState) } },
            containerColor = c.surface,
        )
    }

    if (showEstimate) {
        var v by rememberSaveable { mutableStateOf(estimate.toString()) }
        // Held to the server's 1…1440 (audit 2026-09-22, C4) so the chip shows what is stored.
        fun saveEstimate() { v.toIntOrNull()?.takeIf { it > 0 }?.let { estimate = clampEstimateMin(it) }; showEstimate = false }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showEstimate = false },
            title = { Text("Estimate (minutes)") },
            text = {
                OutlinedTextField(
                    value = v, onValueChange = { s -> v = s.filter { it.isDigit() }.take(4) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { saveEstimate() }),
                )
            },
            confirmButton = { TextButton(onClick = { saveEstimate() }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showEstimate = false }) { Text("Cancel") } },
            containerColor = c.surface,
        )
    }
}
