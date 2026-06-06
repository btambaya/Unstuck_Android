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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.csalliance.unstuck.core.logic.formatTime
import tech.csalliance.unstuck.core.logic.isTaskBlock
import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.time.Time
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
fun DayGridScreen(vm: AppViewModel, onOpen: (TaskItem) -> Unit, onCreateAt: (String, String) -> Unit) {
    val c = UTheme.colors
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val areas by vm.lifeAreas.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val hourPx = with(density) { HOUR_HEIGHT.toPx() }

    var date by remember { mutableStateOf(Clock.todayIso()) }
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

    var gridBounds by remember { mutableStateOf(Rect.Zero) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) } // window coords of this screen's top-left
    var dragTask by remember { mutableStateOf<TaskItem?>(null) }
    var dragBlock by remember { mutableStateOf<CalBlock?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) } // window coords
    var editingBlock by remember { mutableStateOf<CalBlock?>(null) }

    val dayBlocks = blocks.filter { it.date == date }
    // Scheduled-anywhere, not just on the viewed day — otherwise a task scheduled on
    // another date reappears in the unscheduled tray and dragging it MOVES its block.
    val scheduledIds = blocks.filter { isTaskBlock(it) }.mapNotNull { it.taskId }.toSet()
    val unscheduled = tasks.filter { !it.done && it.later != true && it.id !in scheduledIds }

    // Map the current drag position (window coords) to a snapped HH:MM on the grid.
    fun dropTimeOrNull(): String? {
        if (!gridBounds.contains(dragPos)) return null
        val yInGrid = (dragPos.y - gridBounds.top) + scroll.value
        val totalMin = START_HOUR * 60 + ((yInGrid / hourPx) * 60).roundToInt()
        val clamped = ((totalMin / 15) * 15).coerceIn(START_HOUR * 60, END_HOUR * 60 - 15)
        return "%02d:%02d".format(clamped / 60, clamped % 60)
    }

    fun drop() {
        val t = dragTask ?: return
        dropTimeOrNull()?.let { vm.scheduleTask(t, date, it) }
        dragTask = null
    }

    // Drop an already-scheduled block onto a new slot → reschedule in place.
    fun dropBlock() {
        val b = dragBlock ?: return
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
                            onCreateAt(date, "%02d:%02d".format(snapped / 60, snapped % 60))
                        }
                    },
                ) {
                    for (h in START_HOUR until END_HOUR) {
                        Row(Modifier.fillMaxWidth().height(HOUR_HEIGHT)) {
                            Text(
                                formatTime("%02d:00".format(h)),
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
                layoutLanes(dayBlocks).forEach { laid ->
                    val b = laid.block
                    val topMin = parseHhmm(b.startTime) - START_HOUR * 60
                    if (topMin >= 0) {
                        val topDp = HOUR_HEIGHT * (topMin / 60f)
                        val hDp = HOUR_HEIGHT * (b.durationMinutes / 60f)
                        // Color by source: external = blue, a task = its life-area swatch,
                        // placeholder = neutral. Mirrors the web bgFor().
                        val bt = if (isTaskBlock(b)) tasks.firstOrNull { it.id == b.taskId } else null
                        val done = bt?.done == true
                        val fill = when {
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
                                .border(1.dp, c.line, RoundedCornerShape(8.dp))
                                // Task blocks: tap to edit + long-press to drag. External/Google
                                // blocks are display-only — they mirror the remote calendar, and
                                // editing them only changes local state that reverts on next sync.
                                .then(
                                    if (isTaskBlock(b)) Modifier.clickable { editingBlock = b }.pointerInput(b.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { local -> dragBlock = b; dragPos = blockOrigin + local },
                                            onDrag = { change, delta -> change.consume(); dragPos += delta },
                                            onDragEnd = { dropBlock() },
                                            onDragCancel = { dragBlock = null },
                                        )
                                        // External/placeholder blocks are display-only — swallow taps so
                                        // they don't fall through to the grid's create-task handler.
                                    } else Modifier.pointerInput(b.id) { detectTapGestures { } },
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                b.taskName, style = UFont.sans(12, FontWeight.Medium),
                                color = if (done) c.ink3 else c.ink, maxLines = 1,
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
                    .background(c.coralDeep)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(label, style = UFont.sans(12, FontWeight.Medium), color = androidx.compose.ui.graphics.Color.White, maxLines = 1)
            }
        }

        editingBlock?.let { blk -> CalBlockEditSheet(vm, blk) { editingBlock = null } }
    }
}

/** Tap a scheduled block → reschedule (free-slot chips), resize (duration chips),
 *  or unschedule. Mirrors the web cal-block-edit-modal. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CalBlockEditSheet(vm: AppViewModel, block: CalBlock, onDismiss: () -> Unit) {
    val c = UTheme.colors
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    // Track the live block so sequential edits compose + the selection follows.
    val live = blocks.firstOrNull { it.id == block.id } ?: block
    // Full-day window (not the default 08:00–18:00) so an early-morning / evening block
    // can be rescheduled within its own time band.
    val slots = tech.csalliance.unstuck.core.logic.findFreeSlotsForDate(blocks, live.durationMinutes, live.date, vm.nowMs(), limit = 5, dayStartMin = 0, dayEndMin = 24 * 60)
    val times = (listOf(live.startTime) + slots.map { it.startTime }).distinct()
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface, scrimColor = tech.csalliance.unstuck.design.component.SheetScrim,
        dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { tech.csalliance.unstuck.design.component.SheetHandle() } },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tech.csalliance.unstuck.design.component.SectionLabel("Edit block")
            Text(live.taskName, style = UFont.sans(18, FontWeight.SemiBold), color = c.ink)

            tech.csalliance.unstuck.design.component.SectionLabel("Start time")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                times.forEach { t -> tech.csalliance.unstuck.ui.tasks.SelectableChip(formatTime(t), selected = live.startTime == t) { vm.moveBlock(live, live.date, t) } }
            }

            tech.csalliance.unstuck.design.component.SectionLabel("Duration")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 25, 45, 60, 90).forEach { m -> tech.csalliance.unstuck.ui.tasks.SelectableChip("${m}m", selected = live.durationMinutes == m) { vm.resizeBlock(live, m) } }
            }

            tech.csalliance.unstuck.design.component.UButton("Unschedule", kind = tech.csalliance.unstuck.design.component.ButtonKind.DANGER, fill = false) { vm.unschedule(live.id); onDismiss() }
        }
    }
}

