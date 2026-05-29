package tech.csalliance.unstuck.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.ui.tasks.SelectableChip

private enum class Mode { NONE, DAILY, WEEKLY, MONTHLY }

private val DOW = listOf("S", "M", "T", "W", "T", "F", "S") // index 0=Sun … 6=Sat

/** Inline recurrence picker. Emits a [Recurrence]? (null = does not repeat).
 *  `until` is intentionally omitted from the editor (open-ended series); the
 *  model + label still carry it for data synced from web/iOS. */
@Composable
fun RecurrenceEditor(value: Recurrence?, modifier: Modifier = Modifier, onChange: (Recurrence?) -> Unit) {
    val mode = when (value) {
        null -> Mode.NONE
        is Recurrence.Daily -> Mode.DAILY
        is Recurrence.Weekly -> Mode.WEEKLY
        is Recurrence.Monthly -> Mode.MONTHLY
    }
    val days = (value as? Recurrence.Weekly)?.daysOfWeek ?: emptyList()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Repeat")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectableChip("Never", selected = mode == Mode.NONE) { onChange(null) }
            SelectableChip("Daily", selected = mode == Mode.DAILY) { onChange(Recurrence.Daily()) }
            SelectableChip("Weekly", selected = mode == Mode.WEEKLY) { onChange(Recurrence.Weekly(if (days.isEmpty()) listOf(1) else days)) }
            SelectableChip("Monthly", selected = mode == Mode.MONTHLY) { onChange(Recurrence.Monthly()) }
        }
        if (mode == Mode.WEEKLY) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DOW.forEachIndexed { idx, label ->
                    SelectableChip(label, selected = idx in days) {
                        val next = if (idx in days) days - idx else days + idx
                        onChange(Recurrence.Weekly(next.sorted().ifEmpty { listOf(idx) }))
                    }
                }
            }
        }
    }
}
