package tech.csalliance.unstuck.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.tasks.SelectableChip

private enum class Mode { NONE, DAILY, WEEKLY, MONTHLY }

private val DOW = listOf("S", "M", "T", "W", "T", "F", "S") // index 0=Sun … 6=Sat
// Spoken-out names so TalkBack can tell the two "S" (Sun/Sat) and two "T"
// (Tue/Thu) chips apart — the visible single letters stay unchanged.
private val DOW_FULL = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

private fun untilOf(r: Recurrence?): String? = when (r) {
    is Recurrence.Daily -> r.until
    is Recurrence.Weekly -> r.until
    is Recurrence.Monthly -> r.until
    null -> null
}
private fun withUntil(r: Recurrence?, until: String?): Recurrence? = when (r) {
    is Recurrence.Daily -> Recurrence.Daily(until)
    is Recurrence.Weekly -> Recurrence.Weekly(r.daysOfWeek, until)
    is Recurrence.Monthly -> Recurrence.Monthly(until)
    null -> null
}

/** Inline recurrence picker. Emits a [Recurrence]? (null = does not repeat),
 *  including an optional `until` (end date) — web/iOS parity. */
@Composable
fun RecurrenceEditor(value: Recurrence?, modifier: Modifier = Modifier, onChange: (Recurrence?) -> Unit) {
    val c = UTheme.colors
    val context = LocalContext.current
    val mode = when (value) {
        null -> Mode.NONE
        is Recurrence.Daily -> Mode.DAILY
        is Recurrence.Weekly -> Mode.WEEKLY
        is Recurrence.Monthly -> Mode.MONTHLY
    }
    val days = (value as? Recurrence.Weekly)?.daysOfWeek ?: emptyList()
    val until = untilOf(value)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Repeat")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectableChip("Never", selected = mode == Mode.NONE) { onChange(null) }
            SelectableChip("Daily", selected = mode == Mode.DAILY) { onChange(Recurrence.Daily(until)) }
            SelectableChip("Weekly", selected = mode == Mode.WEEKLY) { onChange(Recurrence.Weekly(if (days.isEmpty()) listOf(1) else days, until)) }
            SelectableChip("Monthly", selected = mode == Mode.MONTHLY) { onChange(Recurrence.Monthly(until)) }
        }
        if (mode == Mode.WEEKLY) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DOW.forEachIndexed { idx, label ->
                    SelectableChip(label, selected = idx in days, a11yLabel = DOW_FULL[idx]) {
                        val next = if (idx in days) days - idx else days + idx
                        onChange(Recurrence.Weekly(next.sorted().ifEmpty { listOf(idx) }, until))
                    }
                }
            }
        }
        if (mode != Mode.NONE) {
            // Optional end date (web's "Until:" + "Open-ended" clear).
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ends", style = UFont.sans(12), color = c.ink3)
                SelectableChip(until?.let { "by ${it.takeLast(5)}" } ?: "Open-ended", selected = until != null) {
                    val base = until?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: java.time.LocalDate.now()
                    val dlg = android.app.DatePickerDialog(context, { _, y, m, d ->
                        onChange(withUntil(value, java.time.LocalDate.of(y, m + 1, d).toString()))
                    }, base.year, base.monthValue - 1, base.dayOfMonth)
                    dlg.datePicker.minDate = System.currentTimeMillis() - 60_000
                    dlg.show()
                }
                if (until != null) Text("Clear", style = UFont.sans(12), color = c.primaryDeep, modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Clear end date") { onChange(withUntil(value, null)) }.minimumInteractiveComponentSize())
            }
        }
    }
}
