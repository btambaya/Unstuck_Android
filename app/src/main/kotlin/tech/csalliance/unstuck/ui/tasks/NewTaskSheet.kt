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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.csalliance.unstuck.core.logic.findConflicts
import tech.csalliance.unstuck.core.logic.findFreeSlotsForDate
import tech.csalliance.unstuck.core.logic.formatTime
import tech.csalliance.unstuck.core.model.CircleStatus
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.model.ShareLevel
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.time.Time
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.component.SheetHandle
import tech.csalliance.unstuck.design.component.SheetScrim
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.sync.InviteResult
import tech.csalliance.unstuck.ui.AppViewModel

// Savers so the draft survives a config change (rotation / dark-mode flip / locale /
// split-screen): Activity recreation must not silently discard a half-typed task.

/** [kind, until, daysOfWeek("|"-joined)] strings; empty list = does not repeat. */
private val RecurrenceSaver = listSaver<Recurrence?, String>(
    save = { r ->
        when (r) {
            null -> emptyList()
            is Recurrence.Daily -> listOf("daily", r.until.orEmpty())
            is Recurrence.Monthly -> listOf("monthly", r.until.orEmpty())
            is Recurrence.Weekly -> listOf("weekly", r.until.orEmpty(), r.daysOfWeek.joinToString("|"))
        }
    },
    restore = { saved ->
        val until = saved.getOrNull(1)?.ifBlank { null }
        when (saved.firstOrNull()) {
            "daily" -> Recurrence.Daily(until)
            "monthly" -> Recurrence.Monthly(until)
            "weekly" -> Recurrence.Weekly(saved.getOrNull(2)?.split("|")?.mapNotNull { it.toIntOrNull() }.orEmpty(), until)
            else -> null
        }
    },
)

private val TagsSaver = listSaver<SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)

/** Pending "Share or assign" picks save as "userId|LEVEL" strings (Off = absent —
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
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
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
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var showEstimate by rememberSaveable { mutableStateOf(false) }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    val tags = rememberSaveable(saver = TagsSaver) { mutableStateListOf<String>() }
    val shareLevels = rememberSaveable(saver = ShareLevelsSaver) { mutableStateMapOf<String, ShareLevel>() }

    // Inline circle invite (ported from ShareTaskSheet) — plain remember: an
    // InviteResult can't round-trip a Bundle, and a half-typed invite is fine to drop.
    var inviting by remember { mutableStateOf(false) }
    var inviteEmail by remember { mutableStateOf("") }
    var inviteBusy by remember { mutableStateOf(false) }
    var inviteResult by remember { mutableStateOf<InviteResult?>(null) }
    var inviteErr by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    // Only active connections with a resolved user id can receive a per-task share.
    val active = members.filter { it.status == CircleStatus.ACTIVE && it.memberUserId != null }

    fun copyLink(text: String) {
        clipboard.setText(AnnotatedString(text)); copied = true
        scope.launch { delay(1800); copied = false }
    }

    fun generateInvite() {
        if (inviteBusy) return
        inviteBusy = true; inviteErr = null
        scope.launch {
            val r = runCatching { vm.inviteToCircle(inviteEmail) }.getOrNull()
            inviteBusy = false
            if (r == null || r.error != null) {
                inviteErr = "Could not create invite."
            } else {
                inviteResult = r; inviteEmail = ""
                r.link?.let { copyLink(it) }
            }
        }
    }

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
    LaunchedEffect(effectiveDate, estimate, whenSel, slots) {
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
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("What's the next thing on your mind?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
                        if (pt != null && slots.none { it.startTime == pt }) SelectableChip(formatTime(pt), selected = true) { showTimePicker = true }
                        slots.forEach { s -> SelectableChip(formatTime(s.startTime), selected = pickedTime == s.startTime) { pickedTime = s.startTime; autoTime = false } }
                    }
                    if (slots.isEmpty() && pickedTime == null) {
                        Text("No free slots that day — pick a custom time, or it'll be added without one.", style = tech.csalliance.unstuck.design.theme.UFont.sans(12), color = c.ink3)
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
                    SectionLabel("Share or assign")
                    active.forEach { m ->
                        val userId = m.memberUserId!!
                        SharePickRow(
                            name = m.memberName ?: "Member",
                            relationship = m.relationshipLabel,
                            cur = shareLevels[userId],
                        ) { level -> if (level == null) shareLevels.remove(userId) else shareLevels[userId] = level }
                    }

                    // Invite a new person inline (no separate Connections page).
                    if (inviting) {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg2).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val r = inviteResult
                            if (r != null) {
                                when {
                                    r.added == true -> Text("✓ Added — pick their level above.", style = tech.csalliance.unstuck.design.theme.UFont.sans(13, FontWeight.SemiBold), color = c.greenInk)
                                    r.emailed == true -> Text("✓ Invite sent. Pick their level once they accept.", style = tech.csalliance.unstuck.design.theme.UFont.sans(13, FontWeight.SemiBold), color = c.greenInk)
                                    r.link != null -> {
                                        Text("Invite link ready${if (copied) " · copied!" else ""}", style = tech.csalliance.unstuck.design.theme.UFont.sans(13, FontWeight.SemiBold), color = c.ink)
                                        Text(r.link!!, style = tech.csalliance.unstuck.design.theme.UFont.sans(12), color = c.ink2, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.bg).padding(horizontal = 10.dp, vertical = 8.dp))
                                        Text("Send it to them — it's the only way in.", style = tech.csalliance.unstuck.design.theme.UFont.sans(12), color = c.ink3)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (r.link != null) UButton("Copy link", kind = ButtonKind.DARK, fill = false) { copyLink(r.link!!) }
                                    UButton("Done", kind = ButtonKind.GHOST, fill = false) { inviting = false; inviteResult = null }
                                }
                            } else {
                                OutlinedTextField(
                                    value = inviteEmail, onValueChange = { inviteEmail = it },
                                    label = { Text("name@example.com (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { generateInvite() }),
                                )
                                Text("We'll email them the invite. Or leave it blank for a link you send yourself.", style = tech.csalliance.unstuck.design.theme.UFont.sans(12), color = c.ink3)
                                inviteErr?.let { Text(it, style = tech.csalliance.unstuck.design.theme.UFont.sans(12), color = c.coralDeep) }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    UButton(if (inviteBusy) "…" else if (inviteEmail.isBlank()) "Generate link" else "Send invite", kind = ButtonKind.DARK, fill = false, enabled = !inviteBusy) { generateInvite() }
                                    UButton("Cancel", kind = ButtonKind.GHOST, fill = false) { inviting = false; inviteErr = null }
                                }
                            }
                        }
                    } else {
                        Row(
                            Modifier.clip(RoundedCornerShape(999.dp)).clickable { inviting = true; inviteResult = null; inviteErr = null }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, tint = c.primaryDeep, modifier = Modifier.padding(start = 2.dp))
                            Text("Add someone", style = tech.csalliance.unstuck.design.theme.UFont.sans(13, FontWeight.SemiBold), color = c.primaryDeep)
                        }
                    }

                    if (active.isEmpty() && !inviting) {
                        Text("Share this task with someone in your circle.", style = tech.csalliance.unstuck.design.theme.UFont.sans(12), color = c.ink3)
                    }
                    if (active.isNotEmpty()) {
                        // Explainer — 1:1 with the web share sheet.
                        Text(
                            buildAnnotatedString {
                                fun b(word: String) = withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.ink2)) { append(word) }
                                b("View"); append(" — they see it + get pinged when you start & finish. ")
                                b("Partner"); append(" — either of you can start/complete & focus together. ")
                                b("Assign"); append(" — it becomes their task; you keep view.")
                            },
                            style = tech.csalliance.unstuck.design.theme.UFont.sans(12), color = c.ink3,
                        )
                    }

                    SectionLabel("Tags")
                    tech.csalliance.unstuck.ui.components.TagPicker(vm, tags.toList()) { tags.clear(); tags.addAll(it) }

                    tech.csalliance.unstuck.ui.components.RecurrenceEditor(recurrence) { recurrence = it }
                }
            }

            // Coral accent once the form is valid (a name is entered); muted dark until then.
            UButton("Add task", kind = if (canSubmit) ButtonKind.CORAL else ButtonKind.DARK, enabled = canSubmit) {
                // Pending share picks are handed to addTask, which applies them AFTER the
                // task row lands on the server, IN THE SAME write coroutine as the upsert
                // — so task_share can't race the not-yet-committed insert (not_your_task →
                // silently dropped, the live T2 bug). Failures are logged, not swallowed.
                val t = vm.addTask(
                    name = name, estimateMin = estimate, lifeArea = area, tags = tags.toList().ifEmpty { null },
                    firstPhysicalAction = null, recurrence = recurrence,
                    later = whenSel == "Later",
                    shares = shareLevels.toMap(),
                )
                if (whenSel != "Later" && effectiveDate != null && pickedTime != null) {
                    vm.scheduleTask(t, effectiveDate, pickedTime!!)
                }
                onDismiss()
            }
        }
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
            is24Hour = false,
        )
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickedTime = "%02d:%02d".format(tpState.hour, tpState.minute); autoTime = false; showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = tpState) } },
            containerColor = c.surface,
        )
    }

    if (showEstimate) {
        var v by rememberSaveable { mutableStateOf(estimate.toString()) }
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

/** One circle member + a full-width Off/View/Partner/Assign segmented control —
 *  ShareTaskSheet.MemberLevelRow's pattern, but over PENDING create-form state
 *  (nothing is shared until "Add task"), so the selected segment is coralDeep
 *  (a choice being made) rather than the ink of a live share. */
@Composable
private fun SharePickRow(name: String, relationship: String?, cur: ShareLevel?, onPick: (ShareLevel?) -> Unit) {
    val c = UTheme.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg2).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center) {
                Text((name.trim().firstOrNull() ?: '?').uppercase(), style = tech.csalliance.unstuck.design.theme.UFont.sans(13, FontWeight.SemiBold), color = Color.White)
            }
            Column(Modifier) {
                Text(name, style = tech.csalliance.unstuck.design.theme.UFont.sans(14, FontWeight.SemiBold), color = c.ink, maxLines = 1)
                Text(relationship ?: "connected", style = tech.csalliance.unstuck.design.theme.UFont.sans(11), color = c.ink3, maxLines = 1)
            }
        }
        val opts: List<Pair<ShareLevel?, String>> = listOf(
            null to "Off", ShareLevel.VIEW to "View", ShareLevel.PARTNER to "Partner", ShareLevel.ASSIGN to "Assign",
        )
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp)).background(c.bg).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            opts.forEach { (value, label) ->
                val selected = cur == value
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(if (selected) c.coralDeep else Color.Transparent)
                        .clickable { onPick(value) }.padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = tech.csalliance.unstuck.design.theme.UFont.sans(11, FontWeight.SemiBold), color = if (selected) Color.White else c.ink2) }
            }
        }
    }
}
