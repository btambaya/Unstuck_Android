package tech.csalliance.unstuck.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.browser.customtabs.CustomTabsIntent
import android.net.Uri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import tech.csalliance.unstuck.sync.CalendarConnectOutcome
import tech.csalliance.unstuck.core.logic.SHARED_BLOCK_ID_PREFIX
import tech.csalliance.unstuck.core.logic.asCalBlock
import tech.csalliance.unstuck.core.logic.busyMinutesByDay
import tech.csalliance.unstuck.core.logic.busyScaleMax
import tech.csalliance.unstuck.core.logic.asSharedWithMe
import tech.csalliance.unstuck.core.logic.isTaskBlock
import tech.csalliance.unstuck.core.logic.liveSharedBlocks
import tech.csalliance.unstuck.core.logic.openedFrom
import tech.csalliance.unstuck.core.logic.monthRange
import tech.csalliance.unstuck.core.logic.sharedBlockLabel
import tech.csalliance.unstuck.core.logic.taskForBlock
import tech.csalliance.unstuck.core.model.SharedWithMe
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.design.component.AppBar
import tech.csalliance.unstuck.design.component.Card
import tech.csalliance.unstuck.design.component.Leading
import tech.csalliance.unstuck.design.component.MdSegment
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel

// Week grid bounds (compact 6am–11pm window in a vertical scroll).
private const val WSTART = 0
private const val WEND = 24
private val WHOUR = 44.dp
private fun hhmmToMin(s: String): Int {
    val p = s.split(":")
    return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
}

// Saves the viewed month as its ISO "yyyy-MM" string so the Month view doesn't snap
// back to the current month on rotation / process death.
private val YearMonthSaver = Saver<java.time.YearMonth, String>(
    save = { it.toString() },
    restore = { runCatching { java.time.YearMonth.parse(it) }.getOrDefault(java.time.YearMonth.now()) },
)

@Composable
fun CalendarScreen(
    vm: AppViewModel, onOpen: (TaskItem) -> Unit, onOpenShared: (SharedWithMe) -> Unit, onSearch: () -> Unit,
    onMenu: () -> Unit, onAvatar: () -> Unit, onNotifications: () -> Unit, notifUnread: Int, avatarInitials: String,
    onCreateAt: (String, String) -> Unit,
    /** One-shot Day/Week/Month request from the assistant's `open_screen` (week | month). */
    requestedView: String? = null,
    onViewApplied: () -> Unit = {},
) {
    val c = UTheme.colors
    // Saveable so the chosen Day/Week/Month tab survives rotation / process death.
    var view by rememberSaveable { mutableStateOf("Day") }
    // Applied then cleared by the caller, so returning to Calendar later keeps
    // whatever the user last picked rather than replaying the old request.
    LaunchedEffect(requestedView) {
        if (requestedView != null) { view = requestedView; onViewApplied() }
    }
    // A day tapped in Month view → switch to Day view focused on it. Saveable so the
    // jump survives rotation; consumed by DayGridScreen via its initialDate.
    var jumpDate by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        AppBar(title = "Calendar", leading = Leading.NONE, onSearch = onSearch, onNotifications = onNotifications, notifUnread = notifUnread, onAvatar = onAvatar, avatarInitials = avatarInitials)
        Box(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
            MdSegment(listOf("Day", "Week", "Month"), view) { view = it }
        }
        CalendarSyncBar(vm)
        when (view) {
            "Day" -> DayGridScreen(vm, onOpen, onOpenShared, onCreateAt, initialDate = jumpDate)
            "Week" -> WeekView(vm, onOpen, onOpenShared, onCreateAt)
            // Month gets the same onOpen / onOpenShared the other two views take: a row
            // in its day peek opens the task (or the read-only shared detail) directly.
            else -> MonthView(vm, onOpen, onOpenShared) { iso -> jumpDate = iso; view = "Day" }
        }
    }
}

/** What connecting Google Calendar does, said BEFORE Google's consent opens.
 *  Every task block is mirrored to the PRIMARY calendar with the task's name
 *  as the event title (SyncCoordinator.pushBlockUpsert), and nothing on
 *  Android said so — a work account's colleagues could read "therapy prep"
 *  (Android audit 2026-09-23, A19). Web's words (sync-flow.tsx, W14). */
internal const val GOOGLE_CONNECT_DISCLOSURE =
    "Unstuck shows your Google events here, so your plans fit around them.\n\n" +
        "Each task you schedule becomes an event on your main Google Calendar, and moves or disappears " +
        "when you change it here. Anyone who can see that calendar sees the task's name."

/** The disclosure's title for a first connect or a [reconnect]. */
internal fun googleConnectDisclosureTitle(reconnect: Boolean): String =
    if (reconnect) "Reconnect Google Calendar?" else "Connect Google Calendar?"

/** Connect / sync / disconnect Google Calendar. Opens consent in a Custom Tab
 *  — only after [GOOGLE_CONNECT_DISCLOSURE] was shown and accepted; the
 *  `unstuck://calendar-callback` return is handled in MainActivity. */
@Composable
private fun CalendarSyncBar(vm: AppViewModel) {
    val c = UTheme.colors
    val conns by vm.connections.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    // Connect / Reconnect asked for: the disclosure is up (true = a reconnect).
    var disclose by remember { mutableStateOf<Boolean?>(null) }
    fun openConsent() {
        scope.launch {
            busy = true; error = null
            val url = vm.beginGoogleConnect()
            busy = false
            // Surface failures: a null URL means the authorize call failed
            // (no more silent no-ops). Otherwise open the consent tab.
            if (url == null) error = "Couldn't reach Google. Check your connection and try again."
            else runCatching { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url)) }
                .onFailure { error = "No browser available to open Google sign-in." }
        }
    }
    // How the Google consent ended (MainActivity finishes it from the deep link): held
    // until shown here, since the bar is often off screen when the callback lands.
    val connectOutcome by vm.calendarConnectOutcome.collectAsStateWithLifecycle()
    LaunchedEffect(connectOutcome) {
        connectOutcome?.let { error = calendarConnectCaption(it); vm.consumeCalendarConnectOutcome() }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (conns.isEmpty()) {
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(c.bg2).clickable(enabled = !busy) {
                        disclose = false
                    }.padding(horizontal = 12.dp, vertical = 8.dp),
                ) { Text(if (busy) "Connecting…" else "＋ Connect Google Calendar", style = UFont.sans(12, FontWeight.Medium), color = c.ink2) }
            } else {
                // All connected accounts (not just the first); busy feedback on Sync now;
                // Disconnect confirms first (it's destructive — drops all synced events).
                // A connection the server flagged needs_reauth (refresh token revoked /
                // expired — pulls 401) shows "Needs reconnect" + a Reconnect action that
                // re-runs consent; its meetings are kept meanwhile (never reconciled away).
                val needsReauth = conns.any { it.needsReauth }
                Text(
                    if (busy) "Syncing…" else conns.joinToString(", ") { (if (it.needsReauth) "Needs reconnect · " else "Synced · ") + it.accountEmail },
                    style = UFont.sans(12), color = if (needsReauth) c.red else c.ink3, modifier = Modifier.weight(1f),
                )
                if (needsReauth) {
                    Text("Reconnect Google", style = UFont.sans(12, FontWeight.Medium), color = if (busy) c.ink3 else c.primaryDeep, modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable(enabled = !busy) {
                        disclose = true
                    }.padding(horizontal = 8.dp, vertical = 4.dp))
                } else {
                    Text("Sync now", style = UFont.sans(12, FontWeight.Medium), color = if (busy) c.ink3 else c.primaryDeep, modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable(enabled = !busy) {
                        // A failed "Sync now" used to end silently: the pull never threw, so the
                        // old onFailure was dead (parity with iOS build 81, audit 2026-09-22 C18).
                        scope.launch { busy = true; error = null; error = calendarSyncCaption(vm.syncCalendar(), vm.calendarBackedOff); busy = false }
                    }.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                Text("Disconnect", style = UFont.sans(12), color = c.ink3, modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable { confirmDisconnect = true }.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
        error?.let { Text(it, style = UFont.sans(11), color = c.red, modifier = Modifier.padding(horizontal = 18.dp).padding(bottom = 6.dp)) }
    }
    disclose?.let { reconnect ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { disclose = null },
            title = { Text(googleConnectDisclosureTitle(reconnect), style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
            text = { Text(GOOGLE_CONNECT_DISCLOSURE, style = UFont.sans(13), color = c.ink2) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { disclose = null; openConsent() }) { Text("Continue to Google", color = c.primaryDeep) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { disclose = null }) { Text("Not now", color = c.ink2) } },
            containerColor = c.surface,
        )
    }
    if (confirmDisconnect) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmDisconnect = false },
        title = { Text("Disconnect Google Calendar?", style = UFont.sans(16, FontWeight.SemiBold), color = c.ink) },
        text = { Text("Synced events are removed from your calendar. Your tasks are unaffected.", style = UFont.sans(13), color = c.ink2) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmDisconnect = false; conns.forEach { vm.disconnectCalendar(it.id) } }) { Text("Disconnect", color = c.red) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel", color = c.ink2) } },
        containerColor = c.surface,
    )
}

/** The bar's caption after "Sync now" (null = it worked): iOS's copy, word for word
 *  (parity with iOS build 81, audit 2026-09-22 C18). */
internal fun calendarSyncCaption(ok: Boolean, backedOff: Boolean): String? = when {
    ok -> null
    backedOff -> "Google is busy right now. Try again in a few minutes."
    else -> "Couldn't sync with Google. Check your connection and try again."
}

/** The bar's caption once the in-app Google connect finished (null = connected). */
internal fun calendarConnectCaption(outcome: CalendarConnectOutcome): String? = when (outcome) {
    CalendarConnectOutcome.CONNECTED -> null
    CalendarConnectOutcome.FIRST_SYNC_FAILED -> "Google is connected, but the first sync didn't finish. Tap Sync now."
    CalendarConnectOutcome.FAILED -> "Couldn't connect. Try again."
}

@Composable
private fun WeekView(vm: AppViewModel, onOpen: (TaskItem) -> Unit, onOpenShared: (SharedWithMe) -> Unit, onCreateAt: (String, String) -> Unit) {
    val c = UTheme.colors
    val blocksRaw by vm.blocks.collectAsStateWithLifecycle()
    // Skipped recurring occurrences are cancelled for that day — drop them.
    val blocks = remember(blocksRaw) { blocksRaw.filter { !it.skipped } }
    // Read-only "shared" blocks (migration 052): tasks others shared with me, at the
    // OWNER's slot. They come from vm.sharedBlocks — never vm.blocks — so the planned
    // roll-up and the tap-to-create path below can't see them; the render branch
    // checks `sharedById` FIRST and attaches only a tap → the shared detail sheet.
    val sharedRaw by vm.sharedBlocks.collectAsStateWithLifecycle()
    val sharedWithMe by vm.sharedWithMe.collectAsStateWithLifecycle()
    val shared = remember(sharedRaw) { liveSharedBlocks(sharedRaw) }
    val sharedById = remember(shared) { shared.associateBy { SHARED_BLOCK_ID_PREFIX + it.blockId } }
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val areas by vm.lifeAreas.collectAsStateWithLifecycle()
    // Monday-anchored week, navigable via the ‹ / › arrows (weekOffset = weeks from
    // the current week; 0 = the week containing today). Saveable so the viewed week
    // doesn't snap back to today on rotation.
    var weekOffset by rememberSaveable { mutableStateOf(0) }
    val today = java.time.LocalDate.now()
    val monday = today.minusDays(((today.dayOfWeek.value + 6) % 7).toLong()).plusWeeks(weekOffset.toLong())
    val days = remember(weekOffset) { (0..6).map { monday.plusDays(it.toLong()) } }
    // The shared-block window follows the visible week (cached per window in the VM).
    LaunchedEffect(days) { vm.setSharedBlockRange(days.first().toString(), days.last().toString()) }
    val dows = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    // Per-day lane layout for all 7 columns — computed once per (blocks, shared, week)
    // instead of re-running layoutLanes for every day on each scroll/recomposition.
    // Own + shared blocks share the lane layout so an overlap splits the column.
    val laidByDay = remember(blocks, shared, days) {
        days.associate { d ->
            val iso = d.toString()
            iso to layoutLanes(blocks.filter { it.date == iso } + shared.filter { it.date == iso }.map { it.asCalBlock() })
        }
    }
    val plannedByDay = remember(blocks, days) { days.map { d -> blocks.filter { it.date == d.toString() && isTaskBlock(it) }.sumOf { it.durationMinutes } } }
    val totalPlanned = plannedByDay.sum()
    val maxPlanned = plannedByDay.maxOrNull() ?: 0
    val minPlanned = plannedByDay.minOrNull() ?: 0
    // Only meaningful when the week isn't flat (empty or uniform → "—" both).
    val busiest = if (maxPlanned == minPlanned) null else days.getOrNull(plannedByDay.indexOf(maxPlanned))
    val lightest = if (maxPlanned == minPlanned) null else days.getOrNull(plannedByDay.indexOf(minPlanned))

    val end = days.last()
    fun mon(d: java.time.LocalDate) = d.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    val rangeLabel = if (monday.month == end.month) "${mon(monday)} ${monday.dayOfMonth}–${end.dayOfMonth}"
        else "${mon(monday)} ${monday.dayOfMonth} – ${mon(end)} ${end.dayOfMonth}"
    // The week title, rollup and weekday row stay PINNED above the grid (like
    // Month and the Day view header) — only the hour grid scrolls, so scrolling
    // to an early or late hour never hides which day a column is (tester,
    // 2026-09-07: "can't see the days").
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionLabel(if (weekOffset == 0) "This week" else "Week", color = c.primaryDeep)
                Text(rangeLabel, style = UFont.serifItalic(24), color = c.ink, modifier = Modifier.padding(top = 4.dp))
            }
            // ‹ prev · (Today, when off the current week) · › next
            Text("‹", style = UFont.serifItalic(28), color = c.ink2, modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable { weekOffset-- }.padding(horizontal = 12.dp, vertical = 2.dp))
            if (weekOffset != 0) Text("Today", style = UFont.sans(12, FontWeight.SemiBold), color = c.primaryDeep, modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable { weekOffset = 0 }.padding(horizontal = 8.dp, vertical = 4.dp))
            Text("›", style = UFont.serifItalic(28), color = c.ink2, modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable { weekOffset++ }.padding(horizontal = 12.dp, vertical = 2.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RollupStat("Focus planned", if (totalPlanned >= 60) "${totalPlanned / 60}h ${totalPlanned % 60}m" else "${totalPlanned}m", c.primarySoft, c.primaryDeep, Modifier.weight(1f))
            RollupStat("Busiest", busiest?.let { dows[((it.dayOfWeek.value + 6) % 7)] } ?: "—", c.amberSoft, c.amberInk, Modifier.weight(1f))
            RollupStat("Lightest", lightest?.let { dows[((it.dayOfWeek.value + 6) % 7)] } ?: "—", c.greenSoft, c.greenInk, Modifier.weight(1f))
        }
        // Weekday header (gutter + 7 day labels).
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(26.dp))
            days.forEachIndexed { i, d ->
                val isToday = d == today
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(dows[i].take(1), style = UFont.mono(9, FontWeight.Medium), color = if (isToday) c.coral else c.ink3)
                    Text("${d.dayOfMonth}", style = UFont.sans(13, FontWeight.SemiBold), color = if (isToday) c.coral else c.ink)
                }
            }
        }
        // The hour grid is the only part that scrolls.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        // Hour grid: time gutter + 7 day columns with positioned blocks.
        Row(Modifier.fillMaxWidth().height(WHOUR * (WEND - WSTART)).padding(top = 6.dp)) {
            Column(Modifier.width(26.dp)) {
                for (h in WSTART until WEND) {
                    Box(Modifier.height(WHOUR)) { Text("%02d".format(h), style = UFont.mono(8), color = c.ink4) }
                }
            }
            val weekDensity = androidx.compose.ui.platform.LocalDensity.current
            val weekHourPx = with(weekDensity) { WHOUR.toPx() }
            // O(1) task lookup for block colour/title instead of a per-block scan.
            val tasksById = remember(tasks) { tasks.associateBy { it.id } }
            days.forEach { d ->
                var colW by remember(d.toString()) { mutableStateOf(0.dp) }
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .onGloballyPositioned { colW = with(weekDensity) { it.size.width.toDp() } }
                        // Tap an empty slot → create a task prefilled at that day + snapped time.
                        // Blocks sit on top with their own tap (open detail), so they win their hits.
                        .pointerInput(d.toString()) {
                            detectTapGestures { off ->
                                val totalMin = WSTART * 60 + ((off.y / weekHourPx) * 60).roundToInt()
                                val snapped = ((totalMin / 15) * 15).coerceIn(WSTART * 60, WEND * 60 - 15)
                                onCreateAt(d.toString(), "%02d:%02d".format(snapped / 60, snapped % 60))
                            }
                        },
                ) {
                    Column(Modifier.fillMaxSize()) {
                        repeat(WEND - WSTART) { Box(Modifier.fillMaxWidth().height(WHOUR).border(0.5.dp, c.line.copy(alpha = 0.6f))) }
                    }
                    // Overlapping blocks split the column into side-by-side lanes.
                    (laidByDay[d.toString()] ?: emptyList()).forEach { laid ->
                        val b = laid.block
                        val top = hhmmToMin(b.startTime) - WSTART * 60
                        if (top in 0..((WEND - WSTART) * 60)) {
                            // Shared FIRST: an owner's block is display-only here.
                            val sb = sharedById[b.id]
                            val bt = if (sb == null && isTaskBlock(b)) b.taskId?.let { tasksById[it] } else null
                            // For a recurring occurrence the completion lives on the block.
                            val done = b.done || bt?.done == true
                            val fill = when {
                                sb != null -> c.primarySoft.copy(alpha = 0.45f)
                                isTaskBlock(b) -> c.areaSwatch(tech.csalliance.unstuck.ui.components.areaColorFor(bt?.lifeArea, areas, c))
                                else -> c.blueSoft
                            }
                            val laneW = if (laid.lanes > 1 && colW > 0.dp) colW / laid.lanes else 0.dp
                            val place = if (laneW > 0.dp)
                                Modifier.width((laneW - 1.dp).coerceAtLeast(5.dp)).offset(x = laneW * laid.lane, y = WHOUR * (top / 60f))
                            else
                                Modifier.fillMaxWidth().padding(horizontal = 1.dp).offset(y = WHOUR * (top / 60f))
                            Box(
                                place.height((WHOUR * (b.durationMinutes / 60f)).coerceAtLeast(13.dp))
                                    .clip(RoundedCornerShape(3.dp)).background(fill)
                                    // Shared: dashed outline + task-led label ("task · owner"); tap → the read-only
                                    // shared detail sheet (never the own-task detail, never create).
                                    .then(
                                        when {
                                            sb != null -> Modifier.dashedBorder(c.primaryDeep, 1.dp, 3.dp).clickable {
                                                onOpenShared(sharedWithMe.firstOrNull { it.taskId == sb.taskId }?.openedFrom(sb) ?: sb.asSharedWithMe())
                                            }
                                            isTaskBlock(b) -> Modifier.clickable { taskForBlock(b, tasks)?.let(onOpen) }
                                            else -> Modifier
                                        },
                                    ),
                            ) { Text(if (sb != null) sharedBlockLabel(sb) else b.taskName, style = UFont.sans(8, FontWeight.Medium), color = if (done) c.ink3 else if (sb != null) c.primaryDeep else c.ink, maxLines = 1, textDecoration = if (done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null) }
                        }
                    }
                }
            }
        }
        Box(Modifier.padding(16.dp)) {}
        }
    }
}

@Composable
private fun RollupStat(label: String, value: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(label, style = UFont.mono(9, FontWeight.Medium), color = fg)
        Text(value, style = UFont.sans(14, FontWeight.SemiBold), color = fg, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun MonthView(vm: AppViewModel, onOpen: (TaskItem) -> Unit, onOpenShared: (SharedWithMe) -> Unit, onPickDay: (String) -> Unit) {
    val c = UTheme.colors
    var ym by rememberSaveable(stateSaver = YearMonthSaver) { mutableStateOf(java.time.YearMonth.now()) }
    // Planned indicators: the dots under a day say what's PLANNED — own task blocks
    // (solid) + blocks of tasks shared with me (hollow, at the owner's slot;
    // migration 052). The shared window follows the viewed month.
    val blocksRaw by vm.blocks.collectAsStateWithLifecycle()
    val sharedRaw by vm.sharedBlocks.collectAsStateWithLifecycle()
    LaunchedEffect(ym) { val r = monthRange(ym.year, ym.monthValue); vm.setSharedBlockRange(r.from, r.to) }
    val liveShared = remember(sharedRaw) { liveSharedBlocks(sharedRaw) }
    val ownPlannedDays = remember(blocksRaw) { blocksRaw.filter { isTaskBlock(it) && !it.skipped }.map { it.date }.toSet() }
    val sharedPlannedDays = remember(liveShared) { liveShared.map { it.date }.toSet() }
    // Heat = how BUSY the day is: scheduled minutes (my blocks + shared), which is
    // readable for days still ahead. It used to be focus density (minutes actually
    // focused from sessions), so every future day rendered empty.
    val byDay = remember(blocksRaw, liveShared) { busyMinutesByDay(blocksRaw, liveShared) }
    // Tap ANY day → everything on it, in a peek sheet (tester, 2026-09-08: a shared
    // day opened something and a planned day didn't). Saveable so the peek survives
    // rotation like the viewed month does.
    var peekIso by rememberSaveable { mutableStateOf<String?>(null) }
    val first = ym.atDay(1)
    val lead = (first.dayOfWeek.value + 6) % 7
    val cells: List<java.time.LocalDate?> = List(lead) { null } + (1..ym.lengthOfMonth()).map { ym.atDay(it) }
    // A floor (3h) so one 8-hour day doesn't flatten a normal week to nothing.
    val max = remember(byDay) { busyScaleMax(byDay) }
    val dows = listOf("M", "T", "W", "T", "F", "S", "S")
    val todayIso = Clock.todayIso()

    // The month title + paging, the legend and the weekday row stay PINNED above the
    // grid (as the Day view pins its date header) — only the grid scrolls, so paging
    // months or reading a weekday column never needs a scroll back up. (Was one
    // scrolling Column around everything: the header scrolled away with the grid.)
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ym.month.name.lowercase().replaceFirstChar { it.uppercase() } + " " + ym.year, style = UFont.serifItalic(24), color = c.ink, modifier = Modifier.weight(1f))
            Text("‹", style = UFont.serifItalic(24), color = c.ink2, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { ym = ym.minusMonths(1) }.padding(horizontal = 10.dp, vertical = 2.dp))
            Text("Today", style = UFont.sans(12, FontWeight.Medium), color = c.primaryDeep, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { ym = java.time.YearMonth.now() }.padding(horizontal = 8.dp, vertical = 4.dp))
            Text("›", style = UFont.serifItalic(24), color = c.ink2, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { ym = ym.plusMonths(1) }.padding(horizontal = 10.dp, vertical = 2.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("How busy", style = UFont.mono(10, FontWeight.Medium), color = c.ink3, modifier = Modifier.weight(1f))
            // Legend for the per-day planned dots.
            Box(Modifier.size(5.dp).clip(CircleShape).background(c.primaryDeep))
            Text(" planned   ", style = UFont.mono(9), color = c.ink3)
            Box(Modifier.size(5.dp).clip(CircleShape).border(1.dp, c.primaryDeep, CircleShape))
            Text(" shared", style = UFont.mono(9), color = c.ink3)
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            dows.forEach { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(it, style = UFont.mono(10), color = c.ink4) } }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Card(Modifier.fillMaxWidth(), radius = 18) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    cells.chunked(7).forEach { week ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            week.forEach { d ->
                                if (d == null) {
                                    Box(Modifier.weight(1f).aspectRatio(1f))
                                } else {
                                    val iso = d.toString()
                                    val v = byDay[iso] ?: 0
                                    val t = (v.toFloat() / max).coerceIn(0f, 1f)
                                    val isToday = iso == todayIso
                                    val ownHere = iso in ownPlannedDays
                                    val sharedHere = iso in sharedPlannedDays
                                    Box(
                                        Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(7.dp))
                                            .background(if (isToday) c.coral else if (v == 0) c.bg2 else lerp(c.bg2, c.primary, 0.2f + 0.6f * t))
                                            // EVERY day opens the same peek — what is on it, and a way into
                                            // each item. (Shared days used to open a sheet and planned days
                                            // only jumped to Day view.)
                                            .clickable(role = androidx.compose.ui.semantics.Role.Button) { peekIso = iso }
                                            .semantics(mergeDescendants = true) {
                                                contentDescription = monthCellLabel(d.dayOfMonth, isToday, ownHere, sharedHere, v)
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        val onDark = isToday || t > 0.5f
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("${d.dayOfMonth}", style = UFont.sans(11, FontWeight.SemiBold), color = if (onDark) c.bg else c.ink2, textAlign = TextAlign.Center)
                                            // ● own blocks planned · ○ shared blocks (the owner's slot).
                                            if (ownHere || sharedHere) {
                                                val dot = if (onDark) c.bg else c.primaryDeep
                                                Row(Modifier.padding(top = 1.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    if (ownHere) Box(Modifier.size(4.dp).clip(CircleShape).background(dot))
                                                    if (sharedHere) Box(Modifier.size(4.dp).clip(CircleShape).border(1.dp, dot, CircleShape))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            repeat(7 - week.size) { Box(Modifier.weight(1f).aspectRatio(1f)) }
                        }
                    }
                }
            }
            // Room for the floating assistant bubble, so the last week's Sat/Sun cells
            // can scroll fully out from under it.
            Box(Modifier.padding(36.dp)) {}
        }
    }
    // Tap a day → everything on it; a row then opens the task (mine) or the
    // read-only shared detail, and "Open in Day view" keeps the old jump.
    peekIso?.let { iso ->
        MonthDayPeekSheet(
            vm = vm, iso = iso,
            onOpen = { peekIso = null; onOpen(it) },
            onOpenShared = { peekIso = null; onOpenShared(it) },
            onOpenDay = { peekIso = null; onPickDay(iso) },
            onDismiss = { peekIso = null },
        )
    }
}

/** A month cell's spoken label: "Today, 8, 2 planned, 1 shared, 180 minutes scheduled".
 *  (Was "minutes focused" — the fill measures scheduled load now.) */
private fun monthCellLabel(day: Int, isToday: Boolean, own: Boolean, shared: Boolean, busyMin: Int): String {
    val parts = mutableListOf(if (isToday) "Today, $day" else "$day")
    if (own) parts += "planned"
    if (shared) parts += "shared"
    if (busyMin > 0) parts += "$busyMin minutes scheduled"
    return parts.joinToString(", ")
}
