package tech.csalliance.unstuck.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.CalBlockSheetActions
import tech.csalliance.unstuck.core.logic.SHARED_BLOCK_ID_PREFIX
import tech.csalliance.unstuck.core.logic.asCalBlock
import tech.csalliance.unstuck.core.logic.asSharedWithMe
import tech.csalliance.unstuck.core.logic.blockIsDone
import tech.csalliance.unstuck.core.logic.calBlockSheetActions
import tech.csalliance.unstuck.core.logic.isSharedBlockId
import tech.csalliance.unstuck.core.logic.isTaskBlock
import tech.csalliance.unstuck.core.logic.liveSharedBlocks
import tech.csalliance.unstuck.core.logic.openedFrom
import tech.csalliance.unstuck.core.logic.sharedBlockLabel
import tech.csalliance.unstuck.core.logic.weekRangeContaining
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.SharedWithMe
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.time.ClockFormat
import tech.csalliance.unstuck.core.time.ClockMode
import tech.csalliance.unstuck.core.time.Time
import tech.csalliance.unstuck.core.time.WireTime
import tech.csalliance.unstuck.design.component.ButtonKind
import tech.csalliance.unstuck.design.component.UButton
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import tech.csalliance.unstuck.ui.components.areaColorFor
import kotlin.math.roundToInt

private const val START_HOUR = 0
private const val END_HOUR = 24
private val HOUR_HEIGHT = 56.dp

private fun parseHhmm(hhmm: String): Int {
    val p = hhmm.split(":")
    return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
}

private fun shiftDate(iso: String, days: Int): String {
    val p = iso.split("-").mapNotNull { it.toIntOrNull() }
    if (p.size != 3) return iso
    return Clock.dateIso(Time.addDaysMillis(Time.civil(p[0], p[1], p[2]), days))
}

/** One block's column placement so time-overlapping blocks render side-by-side. */
internal data class Laid(val block: CalBlock, val startMin: Int, val endMin: Int, var lane: Int = 0, var lanes: Int = 1)

/** Assign each block a (lane, lanes) within its cluster of transitively-overlapping
 *  blocks (greedy interval colouring), so overlaps split the width instead of
 *  stacking. Mirrors the web calendar's layoutLanes. */
internal fun layoutLanes(blocks: List<CalBlock>): List<Laid> {
    val laid = blocks.map { Laid(it, parseHhmm(it.startTime), parseHhmm(it.startTime) + it.durationMinutes.coerceAtLeast(1)) }
        .sortedWith(compareBy({ it.startMin }, { it.endMin }))
    var i = 0
    while (i < laid.size) {
        var clusterEnd = laid[i].endMin
        var j = i + 1
        while (j < laid.size && laid[j].startMin < clusterEnd) { clusterEnd = maxOf(clusterEnd, laid[j].endMin); j++ }
        val cluster = laid.subList(i, j)
        val laneEnd = mutableListOf<Int>()   // end-min of the last block placed in each lane
        for (b in cluster) {
            val lane = laneEnd.indexOfFirst { it <= b.startMin }
            if (lane >= 0) { b.lane = lane; laneEnd[lane] = b.endMin } else { b.lane = laneEnd.size; laneEnd.add(b.endMin) }
        }
        for (b in cluster) b.lanes = laneEnd.size
        i = j
    }
    return laid
}

/** Day grid with drag-to-schedule: long-press an unscheduled task in the tray
 *  and drop it onto an hour slot to create a cal_block at that time. */
@Composable
fun DayGridScreen(vm: AppViewModel, onOpen: (TaskItem) -> Unit, onOpenShared: (SharedWithMe) -> Unit, onCreateAt: (String, String) -> Unit, onStartFocus: (TaskItem) -> Unit, initialDate: String? = null) {
    val c = UTheme.colors
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val blocksRaw by vm.blocks.collectAsStateWithLifecycle()
    // Skipped recurring occurrences are cancelled for that day — drop them.
    val blocks = remember(blocksRaw) { blocksRaw.filter { !it.skipped } }
    // Read-only "shared" blocks (migration 052) at the OWNER's slot. They live in
    // vm.sharedBlocks — never vm.blocks — so the tray / drop / edit paths below, which
    // all derive from `blocks`, can't reach them; the render branch checks the shared
    // map FIRST and attaches only a tap → the shared detail sheet (no edit sheet, no
    // long-press drag, no focus start). Their ids carry the `shared:` prefix, so even
    // an id lookup against own blocks can never match one.
    val sharedRaw by vm.sharedBlocks.collectAsStateWithLifecycle()
    val sharedWithMe by vm.sharedWithMe.collectAsStateWithLifecycle()
    val shared = remember(sharedRaw) { liveSharedBlocks(sharedRaw) }
    val sharedById = remember(shared) { shared.associateBy { SHARED_BLOCK_ID_PREFIX + it.blockId } }
    val areas by vm.lifeAreas.collectAsStateWithLifecycle()
    // Id → task map (built once per task list) so each block's colour/title lookup
    // is O(1) instead of a per-block firstOrNull scan on every drag/ticker frame.
    val tasksById = remember(tasks) { tasks.associateBy { it.id } }
    val density = LocalDensity.current
    val hourPx = with(density) { HOUR_HEIGHT.toPx() }
    val clock = tech.csalliance.unstuck.ui.components.clockMode()

    // Saveable (ISO date string) so the viewed day doesn't snap back to today on rotation.
    var date by rememberSaveable { mutableStateOf(initialDate ?: Clock.todayIso()) }
    // A fresh day tapped in Month view jumps us straight to it (only when it actually
    // changes, so the user can still navigate days afterward without being snapped back).
    LaunchedEffect(initialDate) { if (initialDate != null) date = initialDate }
    // Fetch the whole Monday-anchored week so day-to-day paging (and the Week view)
    // is a cache hit; the ViewModel refetches on the shares-changed signal.
    LaunchedEffect(date) { val r = weekRangeContaining(date); vm.setSharedBlockRange(r.from, r.to) }
    val scroll = rememberScrollState()
    // Open today's grid scrolled to roughly an hour before now.
    LaunchedEffect(date) {
        if (date == Clock.todayIso()) {
            val lt = java.time.LocalTime.now()
            scroll.scrollTo((((lt.hour - 1).coerceAtLeast(0)) * hourPx).toInt())
        }
    }
    // Drive the NOW line from a coarse ticker so it advances while the screen
    // is open (was inline LocalTime.now() with no clock-driven recomposition,
    // so it froze at first composition). Only ticks on today.
    var nowLt by remember { mutableStateOf(java.time.LocalTime.now()) }
    LaunchedEffect(date) {
        if (date == Clock.todayIso()) {
            while (true) {
                nowLt = java.time.LocalTime.now()
                kotlinx.coroutines.delay(30_000)
            }
        }
    }
    // Roll the viewed day forward across midnight if the user is still on "today",
    // so the NOW line + "Today" label don't get stuck on yesterday.
    LaunchedEffect(Unit) {
        var shownToday = Clock.todayIso()
        while (true) {
            kotlinx.coroutines.delay(30_000)
            val t = Clock.todayIso()
            if (t != shownToday) {
                if (date == shownToday) date = t
                shownToday = t
            }
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    var gridBounds by remember { mutableStateOf(Rect.Zero) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) } // window coords of this screen's top-left
    var dragTask by remember { mutableStateOf<TaskItem?>(null) }
    var dragBlock by remember { mutableStateOf<CalBlock?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) } // window coords
    var editingBlock by remember { mutableStateOf<CalBlock?>(null) }

    val dayBlocks = remember(blocks, date) { blocks.filter { it.date == date } }
    // Precompute the lane layout for the day's blocks once per (blocks, shared, date) —
    // was re-running the greedy interval colouring on every drag-position / 30s-ticker
    // frame. Shared blocks join the layout (so an overlap splits the width) but NOT
    // `dayBlocks` / `scheduledIds` — those feed the mutating paths.
    val laidDayBlocks = remember(dayBlocks, shared, date) { layoutLanes(dayBlocks + shared.filter { it.date == date }.map { it.asCalBlock() }) }
    // Scheduled-anywhere, not just on the viewed day — otherwise a task scheduled on
    // another date reappears in the unscheduled tray and dragging it MOVES its block.
    val scheduledIds = remember(blocks) { blocks.filter { isTaskBlock(it) }.mapNotNull { it.taskId }.toSet() }
    // recurrence == null: never offer a recurring TEMPLATE in the schedule tray —
    // it's a hidden definition that generates occurrences, not a schedulable task.
    val unscheduled = remember(tasks, scheduledIds) { tasks.filter { !it.done && it.later != true && it.recurrence == null && it.id !in scheduledIds } }

    // Map the current drag position (window coords) to a snapped HH:MM on the grid.
    fun dropTimeOrNull(): String? {
        if (!gridBounds.contains(dragPos)) return null
        val yInGrid = (dragPos.y - gridBounds.top) + scroll.value
        val totalMin = START_HOUR * 60 + ((yInGrid / hourPx) * 60).roundToInt()
        val clamped = ((totalMin / 15) * 15).coerceIn(START_HOUR * 60, END_HOUR * 60 - 15)
        return WireTime.hm(clamped / 60, clamped % 60)
    }

    fun drop() {
        val t = dragTask ?: return
        dropTimeOrNull()?.let { vm.scheduleTask(t, date, it) }
        dragTask = null
    }

    // Drop an already-scheduled block onto a new slot → reschedule in place.
    fun dropBlock() {
        val b = dragBlock ?: return
        // Belt and braces: a shared block never gets the drag gesture, but if one ever
        // reached here it must not be written into MY cal_blocks.
        if (isSharedBlockId(b.id)) { dragBlock = null; return }
        dropTimeOrNull()?.let { vm.moveBlock(b, date, it) }
        dragBlock = null
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.localToWindow(Offset.Zero) }) {
        Column(Modifier.fillMaxSize()) {
            // Day switcher.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("‹", style = UFont.serifItalic(24), color = c.ink2, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { date = shiftDate(date, -1) }.padding(horizontal = 12.dp, vertical = 4.dp))
                Text(if (date == Clock.todayIso()) "Today" else date, style = UFont.sans(15, FontWeight.Medium), color = c.ink)
                Text("›", style = UFont.serifItalic(24), color = c.ink2, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { date = shiftDate(date, 1) }.padding(horizontal = 12.dp, vertical = 4.dp))
            }

            // Hour grid.
            Box(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)
                    .onGloballyPositioned { gridBounds = it.boundsInWindow() },
            ) {
                // Tap an empty area → create a task prefilled at that snapped time.
                val gutterPx = with(density) { 64.dp.toPx() }
                Column(
                    Modifier.pointerInput(date) {
                        detectTapGestures { off ->
                            if (off.x < gutterPx) return@detectTapGestures   // ignore taps in the hour-label gutter
                            val totalMin = START_HOUR * 60 + ((off.y / hourPx) * 60).roundToInt()
                            val snapped = ((totalMin / 15) * 15).coerceIn(START_HOUR * 60, END_HOUR * 60 - 15)
                            onCreateAt(date, WireTime.hm(snapped / 60, snapped % 60))
                        }
                    },
                ) {
                    for (h in START_HOUR until END_HOUR) {
                        Row(Modifier.fillMaxWidth().height(HOUR_HEIGHT)) {
                            Text(
                                // "14:00" / "2 PM" — the phone's 12/24-hour setting.
                                ClockFormat.hour(h, clock),
                                Modifier.width(64.dp).padding(start = 12.dp, top = 2.dp),
                                style = UFont.mono(10), color = c.ink4,
                            )
                            Box(Modifier.weight(1f).fillMaxSize().border(0.5.dp, c.line))
                        }
                    }
                }
                // Blocks for the day, absolutely positioned by start time. Overlapping
                // blocks split the width into side-by-side lanes (see layoutLanes).
                val gridWidthDp = with(density) { gridBounds.width.toDp() }
                laidDayBlocks.forEach { laid ->
                    val b = laid.block
                    val topMin = parseHhmm(b.startTime) - START_HOUR * 60
                    if (topMin >= 0) {
                        val topDp = HOUR_HEIGHT * (topMin / 60f)
                        val hDp = HOUR_HEIGHT * (b.durationMinutes / 60f)
                        // Color by source: external = blue, a task = its life-area swatch,
                        // placeholder = neutral. Mirrors the web bgFor().
                        // Shared FIRST: an owner's block is display-only here.
                        val sb = sharedById[b.id]
                        val bt = if (sb == null && isTaskBlock(b)) b.taskId?.let { tasksById[it] } else null
                        // A repeating day is done on its own block, a one-off when its task
                        // is (blockIsDone, the Edit-block sheet's rule, so the block it just
                        // ticked or reopened shows it). A shared block keeps the owner's done.
                        val done = if (sb != null) b.done else blockIsDone(b, bt)
                        val fill = when {
                            sb != null -> c.primarySoft.copy(alpha = 0.45f)
                            b.kind == CalBlockKind.EXTERNAL -> c.blueSoft
                            isTaskBlock(b) -> c.areaSwatch(areaColorFor(bt?.lifeArea, areas, c))
                            else -> c.bg2
                        }
                        var blockOrigin by remember(b.id) { mutableStateOf(Offset.Zero) }
                        // Split the block area (grid width − 70 − 12) across the cluster's lanes.
                        val laneWidthDp = if (laid.lanes > 1 && gridWidthDp > 0.dp) (gridWidthDp - 82.dp) / laid.lanes else 0.dp
                        val placement = if (laneWidthDp > 0.dp)
                            Modifier.padding(start = 70.dp).offset(x = laneWidthDp * laid.lane, y = topDp).width((laneWidthDp - 3.dp).coerceAtLeast(20.dp))
                        else
                            Modifier.padding(start = 70.dp, end = 12.dp).offset(y = topDp).fillMaxWidth()
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier = placement
                                .height(hDp.coerceAtLeast(24.dp))
                                .onGloballyPositioned { blockOrigin = it.localToWindow(Offset.Zero) }
                                .clip(RoundedCornerShape(8.dp))
                                .background(fill)
                                // Shared blocks wear a dashed outline instead of the solid border.
                                .then(if (sb != null) Modifier.dashedBorder(c.primaryDeep, 1.dp, 8.dp) else Modifier.border(1.dp, c.line, RoundedCornerShape(8.dp)))
                                // Shared (checked FIRST): tap → the read-only shared detail sheet.
                                // No edit sheet, no long-press drag, no focus start — the owner's
                                // block is theirs; we only look at it. Then task blocks: tap to
                                // edit + long-press to drag. External/Google blocks are display-
                                // only — they mirror the remote calendar, and editing them only
                                // changes local state that reverts on next sync.
                                .then(
                                    if (sb != null) Modifier.clickable {
                                        onOpenShared(sharedWithMe.firstOrNull { it.taskId == sb.taskId }?.openedFrom(sb) ?: sb.asSharedWithMe())
                                    } else if (isTaskBlock(b)) Modifier.clickable { editingBlock = b }.pointerInput(b.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { local -> dragBlock = b; dragPos = blockOrigin + local },
                                            onDrag = { change, delta -> change.consume(); dragPos += delta },
                                            onDragEnd = { dropBlock() },
                                            onDragCancel = { dragBlock = null },
                                        )
                                        // External/placeholder blocks are display-only — swallow taps so
                                        // they don't fall through to the grid's create-task handler, but
                                        // give a brief hint instead of feeling broken/unresponsive.
                                    } else Modifier.pointerInput(b.id) {
                                        detectTapGestures {
                                            android.widget.Toast.makeText(context, viewOnlyBlockHint(b), android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                if (sb != null) sharedBlockLabel(sb) else b.taskName, style = UFont.sans(12, FontWeight.Medium),
                                color = if (done) c.ink3 else if (sb != null) c.primaryDeep else c.ink, maxLines = 1,
                                textDecoration = if (done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                            )
                        }
                    }
                }
                // "NOW" line on today's grid.
                if (date == Clock.todayIso()) {
                    val lt = nowLt
                    val nowMin = lt.hour * 60 + lt.minute - START_HOUR * 60
                    if (nowMin in 0..((END_HOUR - START_HOUR) * 60)) {
                        val topDp = HOUR_HEIGHT * (nowMin / 60f)
                        Box(Modifier.padding(start = 64.dp, end = 12.dp).offset(y = topDp).fillMaxWidth().height(1.5.dp).background(c.coral))
                        Box(Modifier.offset(y = (topDp - 8.dp).coerceAtLeast(0.dp)).padding(start = 8.dp).clip(RoundedCornerShape(999.dp)).background(c.coral).padding(horizontal = 6.dp, vertical = 1.dp)) {
                            Text("NOW", style = UFont.mono(8, FontWeight.Bold), color = Color.White)
                        }
                    }
                }
            }

            // Unscheduled tray.
            Text("Drag onto the grid to schedule", Modifier.padding(start = 20.dp, top = 6.dp), style = UFont.mono(10), color = c.ink3)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                unscheduled.take(20).forEach { t ->
                    var origin by remember(t.id) { mutableStateOf(Offset.Zero) }
                    Box(
                        Modifier
                            .onGloballyPositioned { origin = it.localToWindow(Offset.Zero) }
                            .clip(RoundedCornerShape(10.dp))
                            .background(c.surface)
                            .border(1.dp, c.line, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .pointerInput(t.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { local -> dragTask = t; dragPos = origin + local },
                                    onDrag = { change, delta -> change.consume(); dragPos += delta },
                                    onDragEnd = { drop() },
                                    onDragCancel = { dragTask = null },
                                )
                            },
                    ) {
                        Text("${t.name} · ${t.estimateMin}m", style = UFont.sans(12), color = c.ink)
                    }
                }
            }
        }

        // Drag ghost — follows the finger. dragPos is in window coords, so subtract
        // this Box's window origin to draw it in local space (1:1 with the finger).
        val ghostLabel = dragTask?.name ?: dragBlock?.taskName
        // Density-aware centering offsets (raw px constants were only correct
        // at one screen density → ghost drifted from the finger on hdpi/xxhdpi).
        val ghostDx = with(density) { 70.dp.toPx() }.roundToInt()
        val ghostDy = with(density) { 18.dp.toPx() }.roundToInt()
        ghostLabel?.let { label ->
            Box(
                Modifier
                    .offset { IntOffset((dragPos.x - rootOrigin.x).roundToInt() - ghostDx, (dragPos.y - rootOrigin.y).roundToInt() - ghostDy) }
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.coral)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(label, style = UFont.sans(12, FontWeight.Medium), color = androidx.compose.ui.graphics.Color.White, maxLines = 1)
            }
        }

        // The edit sheet is for MY blocks only — a shared block can never open it (its
        // render branch sets no editingBlock), and this guard keeps that true even if a
        // future path assigns one.
        editingBlock?.takeUnless { isSharedBlockId(it.id) }?.let { blk ->
            CalBlockEditSheet(vm, blk, onOpen = onOpen, onStartFocus = onStartFocus) { editingBlock = null }
        }
    }
}

/** The hint a view-only block (a Google event, reserved time) gives when tapped
 *  in the Day or Week grid. The tap stops there: it never falls through to the
 *  grid's create-a-task-here. */
internal fun viewOnlyBlockHint(b: CalBlock): String =
    if (b.kind == CalBlockKind.EXTERNAL) "From Google Calendar — view only here." else "Reserved time."

/** Tap a scheduled block → finish it, focus on it or open it; reschedule
 *  (free-slot chips), resize (duration chips) or unschedule. Mirrors the web
 *  cal-block-edit-modal (Start now · Mark complete · Open in tasks).
 *
 *  Every task action goes through the path Today and the task screen use, on the
 *  row Today shows ([calBlockSheetActions]): Mark done / Mark not done is
 *  vm.toggleDone (a series' block ticks THAT day's occurrence, never the series),
 *  Start focus is the shell's focus entry, Open task is the task route. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun CalBlockEditSheet(vm: AppViewModel, block: CalBlock, onOpen: (TaskItem) -> Unit, onStartFocus: (TaskItem) -> Unit, onDismiss: () -> Unit) {
    val c = UTheme.colors
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val assignedOut by vm.assignedOut.collectAsStateWithLifecycle()
    // Track the live block so sequential edits compose + the selection follows.
    val live = blocks.firstOrNull { it.id == block.id } ?: block
    // Re-read per change, so a tick made elsewhere (Today, another device) flips
    // the label while the sheet is up.
    val actions = remember(live, tasks, assignedOut) { calBlockSheetActions(live, tasks, assignedOut) }
    // Full-day window (not the default 08:00–18:00) so an early-morning / evening block
    // can be rescheduled within its own time band.
    val clock = tech.csalliance.unstuck.ui.components.clockMode()
    val slots = tech.csalliance.unstuck.core.logic.findFreeSlotsForDate(blocks, live.durationMinutes, live.date, vm.nowMs(), limit = 5, dayStartMin = 0, dayEndMin = 24 * 60, clock = clock)
    val times = (listOf(live.startTime) + slots.map { it.startTime }).distinct()
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface, scrimColor = tech.csalliance.unstuck.design.component.SheetScrim,
        dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { tech.csalliance.unstuck.design.component.SheetHandle() } },
    ) {
        CalBlockEditSheetBody(
            taskName = live.taskName, actions = actions, times = times, startTime = live.startTime,
            durationMinutes = live.durationMinutes, clock = clock,
            // Like the web modal, a tick closes the sheet: the block behind it shows
            // the new state (struck through when done).
            onToggleDone = { actions.row?.let { vm.toggleDone(it) }; onDismiss() },
            onStartFocus = { actions.row?.let { row -> onDismiss(); onStartFocus(row) } },
            onOpenTask = { actions.row?.let { row -> onDismiss(); onOpen(row) } },
            onPickTime = { t -> vm.moveBlock(live, live.date, t) },
            onPickDuration = { m -> vm.resizeBlock(live, m) },
            onUnschedule = { vm.unschedule(live.id); onDismiss() },
        )
    }
}

/** The Edit-block sheet's content, stateless (rendered by the sheet above and by
 *  CalBlockEditSheetTest). The task actions sit first, under the name: Start
 *  focus (coral — Focus is a coral surface) and Mark done / Mark not done, then
 *  Open task; a block with no task behind it ([CalBlockSheetActions.NONE]) shows
 *  none of them. */
@Composable
internal fun CalBlockEditSheetBody(
    taskName: String,
    actions: CalBlockSheetActions,
    times: List<String>,
    startTime: String,
    durationMinutes: Int,
    clock: ClockMode,
    onToggleDone: () -> Unit,
    onStartFocus: () -> Unit,
    onOpenTask: () -> Unit,
    onPickTime: (String) -> Unit,
    onPickDuration: (Int) -> Unit,
    onUnschedule: () -> Unit,
) {
    val c = UTheme.colors
    // Scrolls when it outgrows the sheet, like the Month peek and New task sheets.
    // With the task actions the body is ~410 dp at normal text and ~575 dp at 200 %,
    // more than a 640 dp phone's sheet holds, so Unschedule was cut off there.
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tech.csalliance.unstuck.design.component.SectionLabel("Edit block")
        // Struck through once done, like the block on the grid.
        Text(
            taskName, style = UFont.sans(18, FontWeight.SemiBold), color = if (actions.done) c.ink3 else c.ink,
            textDecoration = if (actions.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
        )

        if (actions.canFocus || actions.canComplete) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (actions.canFocus) {
                    UButton(
                        CalBlockSheetActions.START_FOCUS, kind = ButtonKind.CORAL, leadingIcon = Icons.Filled.PlayArrow,
                        modifier = Modifier.weight(1f).actionSemantics("Start focus on $taskName"),
                        onClick = onStartFocus,
                    )
                }
                if (actions.canComplete) {
                    UButton(
                        actions.completeLabel, kind = ButtonKind.OUTLINED,
                        leadingIcon = if (actions.done) Icons.Outlined.RadioButtonUnchecked else Icons.Filled.Check,
                        modifier = Modifier.weight(1f).actionSemantics(if (actions.done) "Mark $taskName not done" else "Mark $taskName done"),
                        onClick = onToggleDone,
                    )
                }
            }
        }
        actions.assignedTo?.let { who ->
            // In place of Start focus / Mark done, the task screen's words: its
            // recipient does it now.
            Text("You assigned this to ${who.substringBefore('@')} — view only", style = UFont.sans(12), color = c.ink3)
        }
        if (actions.canOpen) {
            UButton(
                CalBlockSheetActions.OPEN_TASK, kind = ButtonKind.TEXT,
                modifier = Modifier.actionSemantics("Open $taskName"),
                onClick = onOpenTask,
            )
        }

        tech.csalliance.unstuck.design.component.SectionLabel("Start time")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            times.forEach { t -> tech.csalliance.unstuck.ui.tasks.SelectableChip(ClockFormat.time(t, clock), selected = startTime == t) { onPickTime(t) } }
        }

        tech.csalliance.unstuck.design.component.SectionLabel("Duration")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 25, 45, 60, 90).forEach { m -> tech.csalliance.unstuck.ui.tasks.SelectableChip("${m}m", selected = durationMinutes == m) { onPickDuration(m) } }
        }

        UButton("Unschedule", kind = ButtonKind.DANGER, fill = false, onClick = onUnschedule)
    }
}

/** A sheet action's spoken name (with the task, so TalkBack says what it acts on)
 *  and its button role. */
private fun Modifier.actionSemantics(label: String): Modifier =
    semantics { contentDescription = label; role = Role.Button }
