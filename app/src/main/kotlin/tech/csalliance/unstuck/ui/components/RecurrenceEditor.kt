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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import tech.csalliance.unstuck.core.logic.StartsChip
import tech.csalliance.unstuck.core.logic.everyNWeeksDays
import tech.csalliance.unstuck.core.logic.nWeeksAnchor
import tech.csalliance.unstuck.core.logic.nWeeksBase
import tech.csalliance.unstuck.core.logic.sameSeriesWeeks
import tech.csalliance.unstuck.core.logic.shortDayLabel
import tech.csalliance.unstuck.core.logic.startsChips
import tech.csalliance.unstuck.core.model.Recurrence
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.design.component.SectionLabel
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import tech.csalliance.unstuck.ui.tasks.SelectableChip

private enum class Mode { NONE, DAILY, WEEKLY, MONTHLY }

private val DOW = listOf("S", "M", "T", "W", "T", "F", "S") // index 0=Sun … 6=Sat
// Spoken-out names so TalkBack can tell the two "S" (Sun/Sat) and two "T"
// (Tue/Thu) chips apart — the visible single letters stay unchanged.
private val DOW_FULL = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

private fun untilOf(r: Recurrence?): String? = r?.until
private fun withUntil(r: Recurrence?, until: String?): Recurrence? = when (r) {
    is Recurrence.Daily -> Recurrence.Daily(until)
    is Recurrence.Weekly -> Recurrence.Weekly(r.daysOfWeek, until)
    is Recurrence.Monthly -> Recurrence.Monthly(until)
    is Recurrence.EveryNWeeks -> r.copy(until = until)
    null -> null
}

/**
 * The repeat picker's every-N-weeks half, pure (every-n-weeks spec §5/§6, Ahmad
 * 2026-09-24: chips Every week · 2 weeks · 3 weeks · 4 weeks; the assistant may
 * set up to 8, shown as a fifth chip; the "Starts" chips shown). Unit-tested
 * (RecurrenceEditorModelTest).
 *
 * [stored] is the rule the task has saved (an edit), null in the create sheet;
 * [startIso] is the create sheet's picked day (else today), or an edit's own
 * start (its next timed block's day, else today).
 */
internal object RecurrenceEditorModel {
    /** Weeks between on-weeks: 1 for plain weekly (or anything else). */
    fun intervalOf(r: Recurrence?): Int = (r as? Recurrence.EveryNWeeks)?.interval ?: 1

    /** The weekday toggles' days. */
    fun daysOf(r: Recurrence?): List<Int> = when (r) {
        is Recurrence.Weekly -> r.daysOfWeek
        is Recurrence.EveryNWeeks -> everyNWeeksDays(r)
        else -> emptyList()
    }

    /** The interval chips: 1–4, plus a 5–8 rhythm the assistant set. */
    fun intervalChoices(current: Int): List<Int> = listOf(1, 2, 3, 4) + (if (current in 5..8) listOf(current) else emptyList())

    fun intervalLabel(n: Int): String = when (n) {
        1 -> "Every week"
        in 2..4 -> "$n weeks"
        else -> "Every $n weeks"
    }

    /** What picking [days] every [n] weeks writes: plain weekly for 1; else every
     *  N weeks with [anchor] (a "Starts" pick) or the §5 default — the STORED
     *  weeks when an edit keeps N, else the week of the first series day from the
     *  base. */
    fun rule(stored: Recurrence?, days: List<Int>, n: Int, until: String?, todayIso: String, startIso: String, anchor: String? = null): Recurrence =
        if (n <= 1) Recurrence.Weekly(days, until)
        else Recurrence.EveryNWeeks(n, days, anchor ?: nWeeksAnchor(stored, days, n, todayIso, startIso), until)

    /**
     * The create sheet's save (spec §5, "Create sheet"): the rule with week one
     * set to the "Starts" chip it shows as picked, and the day the first
     * occurrence is scheduled on — the picked day for the first chip (as for
     * weekly: an off-pattern day gets its one-off), else that chip's day, so the
     * Schedule step's re-anchor (AppViewModel.scheduleTaskNow) lands on the same
     * weeks. Any other rule is returned as it is, on [dateIso].
     */
    fun createStart(value: Recurrence?, dateIso: String): Pair<Recurrence?, String> {
        if (value !is Recurrence.EveryNWeeks || value.interval < 2) return value to dateIso
        val chips = startsChips(value.daysOfWeek, value.interval, dateIso)
        val i = chips.indexOfFirst { sameSeriesWeeks(it.anchor, value.anchor, value.interval) }
        if (i < 0) return value to dateIso
        return value.copy(anchor = chips[i].anchor) to (if (i == 0) dateIso else chips[i].date)
    }

    /** The "Starts" chips for [value], each with whether it is the rule's weeks. */
    fun starts(stored: Recurrence?, value: Recurrence.EveryNWeeks, todayIso: String, startIso: String): List<Pair<StartsChip, Boolean>> =
        startsChips(value.daysOfWeek, value.interval, nWeeksBase(stored, value.interval, todayIso, startIso))
            .map { it to sameSeriesWeeks(it.anchor, value.anchor, value.interval) }
}

/** Inline recurrence picker. Emits a [Recurrence]? (null = does not repeat),
 *  including an optional `until` (end date) — web/iOS parity.
 *
 *  [showHeading] owns the "REPEAT" [SectionLabel]. It defaults to true so a
 *  bare call site (NewTaskSheet) is labelled on its own; a sheet that already
 *  prints its own "Repeat" heading — plus a summary line above the picker, as
 *  TaskDetailSheet does — MUST pass false, or the heading stacks twice. */
@Composable
fun RecurrenceEditor(
    value: Recurrence?,
    modifier: Modifier = Modifier,
    showHeading: Boolean = true,
    /** The rule the task has saved (the task sheet); null in the create sheet. */
    stored: Recurrence? = null,
    todayIso: String = Clock.todayIso(),
    /** Create: the picked day; edit: the edit's own start (see [RecurrenceEditorModel]). */
    startIso: String = todayIso,
    onChange: (Recurrence?) -> Unit,
) {
    val c = UTheme.colors
    val context = LocalContext.current
    val mode = when (value) {
        null -> Mode.NONE
        is Recurrence.Daily -> Mode.DAILY
        is Recurrence.Weekly, is Recurrence.EveryNWeeks -> Mode.WEEKLY
        is Recurrence.Monthly -> Mode.MONTHLY
    }
    val days = RecurrenceEditorModel.daysOf(value)
    val interval = RecurrenceEditorModel.intervalOf(value)
    val until = untilOf(value)
    fun weekly(nextDays: List<Int>, n: Int, anchor: String? = null) =
        RecurrenceEditorModel.rule(stored, nextDays, n, until, todayIso, startIso, anchor)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showHeading) SectionLabel("Repeat")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectableChip("Never", selected = mode == Mode.NONE) { onChange(null) }
            SelectableChip("Daily", selected = mode == Mode.DAILY) { onChange(Recurrence.Daily(until)) }
            // Re-tapping Weekly keeps the rule: on an every-N-weeks series it would
            // otherwise quietly make it every week.
            SelectableChip("Weekly", selected = mode == Mode.WEEKLY) { if (mode != Mode.WEEKLY) onChange(Recurrence.Weekly(if (days.isEmpty()) listOf(1) else days, until)) }
            SelectableChip("Monthly", selected = mode == Mode.MONTHLY) { onChange(Recurrence.Monthly(until)) }
        }
        if (mode == Mode.WEEKLY) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DOW.forEachIndexed { idx, label ->
                    SelectableChip(label, selected = idx in days, a11yLabel = DOW_FULL[idx]) {
                        // A weekly recurrence needs at least one day (an empty set is treated as
                        // corrupt downstream + would erase the series). Rather than silently
                        // snapping the last day back on, tell the user it must stay selected.
                        if (idx in days && days.size <= 1) {
                            android.widget.Toast.makeText(context, "Pick at least one day for a weekly repeat.", android.widget.Toast.LENGTH_SHORT).show()
                            return@SelectableChip
                        }
                        val next = if (idx in days) days - idx else days + idx
                        onChange(weekly(next.sorted(), interval))
                    }
                }
            }
            // Every N weeks (Ahmad 2026-09-24). Every week is plain weekly.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RecurrenceEditorModel.intervalChoices(interval).forEach { n ->
                    SelectableChip(RecurrenceEditorModel.intervalLabel(n), selected = n == interval) {
                        if (n != interval) onChange(weekly(days.ifEmpty { listOf(1) }, n))
                    }
                }
            }
            // "Starts": which weeks count, made explicit (Zubair's call showed it matters).
            if (value is Recurrence.EveryNWeeks && interval >= 2) {
                val chips = RecurrenceEditorModel.starts(stored, value, todayIso, startIso)
                if (chips.isNotEmpty()) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Starts", style = UFont.sans(12), color = c.ink3)
                        chips.forEach { (chip, on) ->
                            SelectableChip(shortDayLabel(chip.date), selected = on, a11yLabel = "Starts ${shortDayLabel(chip.date)}") {
                                if (!on) onChange(weekly(days, interval, anchor = chip.anchor))
                            }
                        }
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
