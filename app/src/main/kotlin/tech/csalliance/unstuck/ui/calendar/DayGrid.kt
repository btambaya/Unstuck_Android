package tech.csalliance.unstuck.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.time.Time
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.AppViewModel
import kotlin.math.roundToInt

private const val START_HOUR = 6
private const val END_HOUR = 22
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

/** Day grid with drag-to-schedule: long-press an unscheduled task in the tray
 *  and drop it onto an hour slot to create a cal_block at that time. */
@Composable
fun DayGridScreen(vm: AppViewModel, onOpen: (TaskItem) -> Unit) {
    val c = UTheme.colors
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val hourPx = with(density) { HOUR_HEIGHT.toPx() }

    var date by remember { mutableStateOf(Clock.todayIso()) }
    val scroll = rememberScrollState()

    var gridBounds by remember { mutableStateOf(Rect.Zero) }
    var dragTask by remember { mutableStateOf<TaskItem?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) } // window coords

    val dayBlocks = blocks.filter { it.date == date }
    val scheduledIds = dayBlocks.filter { isTaskBlock(it) }.mapNotNull { it.taskId }.toSet()
    val unscheduled = tasks.filter { !it.done && it.later != true && it.id !in scheduledIds }

    fun drop() {
        val t = dragTask ?: return
        if (gridBounds.contains(dragPos)) {
            val yInGrid = (dragPos.y - gridBounds.top) + scroll.value
            val totalMin = START_HOUR * 60 + ((yInGrid / hourPx) * 60).roundToInt()
            val snapped = (totalMin / 15) * 15
            val clamped = snapped.coerceIn(START_HOUR * 60, END_HOUR * 60 - 15)
            val hh = "%02d:%02d".format(clamped / 60, clamped % 60)
            vm.scheduleTask(t, date, hh)
        }
        dragTask = null
    }

    Box(Modifier.fillMaxSize()) {
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
                Column {
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
                // Blocks for the day, absolutely positioned by start time.
                dayBlocks.forEach { b ->
                    val topMin = parseHhmm(b.startTime) - START_HOUR * 60
                    if (topMin >= 0) {
                        val topDp = HOUR_HEIGHT * (topMin / 60f)
                        val hDp = HOUR_HEIGHT * (b.durationMinutes / 60f)
                        Box(
                            Modifier.padding(start = 70.dp, end = 12.dp).offset(y = topDp).fillMaxWidth()
                                .height(hDp.coerceAtLeast(22.dp))
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isTaskBlock(b)) c.coralSoft else c.bg2)
                                .border(1.dp, c.line, RoundedCornerShape(8.dp))
                                .padding(6.dp),
                        ) {
                            Text(b.taskName, style = UFont.sans(12, FontWeight.Medium), color = c.ink, maxLines = 1)
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

        // Drag ghost.
        dragTask?.let { t ->
            Box(
                Modifier
                    .offset { IntOffset(dragPos.x.roundToInt() - 80, dragPos.y.roundToInt() - 24) }
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.coralDeep)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(t.name, style = UFont.sans(12, FontWeight.Medium), color = androidx.compose.ui.graphics.Color.White, maxLines = 1)
            }
        }
    }
}

