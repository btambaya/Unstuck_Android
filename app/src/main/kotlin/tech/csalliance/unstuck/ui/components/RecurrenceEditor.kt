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
import tech.csalliance.unstuck.core.logic.mondayIso
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
    // An until-only edit keeps the weeks; week one is written as its Monday.
    is Recurrence.EveryNWeeks -> r.copy(anchor = mondayIso(r.anchor), until = until)
    null -> null
}

/**
 * The repeat picker's every-N-weeks half, pure (every-n-weeks spec §5/§6, Ahmad
 * 2026-09-24: chips Every week · 2 weeks · 3 weeks · 4 weeks; the assistant may
 * set up to 8, shown as a fifth chip; the "Starts" chips shown). Unit-tested
 * (RecurrenceEditorModelTest).
 *
 * [stored] is the rule the task has saved (an edit), null in the create sheet;
 * [startIso] is the create sheet's picked day (else today), or an edit's
 * series block day (recurrenceAnchor's: its next live timed block, else its
 * last timed one; [nWeeksBase] only counts it when it is ahead of today).
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

    /** The interval chips: 1–4, plus a fifth for a rhythm past 4 the assistant
     *  (5–8) or another writer set — web's `intervalOptions` and iOS's
     *  weeksRow show it for any stored N above 4. */
    fun intervalChoices(current: Int): List<Int> = listOf(1, 2, 3, 4) + (if (current > 4) listOf(current) else emptyList())

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
     * The create sheet's rule as shown and saved (spec §6: the default is the
     * FIRST "Starts" chip on create): week one is the chip the user tapped
     * ([pick], its anchor) while it is still one of the chips for the current
     * days and [baseIso], else the first chip. The pick is held apart from the
     * rule, as web holds it: a week one computed when the rhythm was chosen went
     * stale when the day was changed afterwards — Today (Thu 24 Sep) → 2 weeks →
     * Tomorrow showed "Thu 8 Oct" as picked and scheduled the series from 8 Oct
     * instead of the day chosen, with 1 Oct left out.
     */
    fun createRule(value: Recurrence?, pick: String?, baseIso: String): Recurrence? {
        if (value !is Recurrence.EveryNWeeks || value.interval < 2) return value
        val chips = startsChips(value.daysOfWeek, value.interval, baseIso)
        val anchor = chips.firstOrNull { it.anchor == pick }?.anchor ?: chips.firstOrNull()?.anchor ?: return value
        return value.copy(anchor = anchor)
    }

    /**
     * The create sheet's save (spec §5, "Create sheet"): the rule with week one
     * set to the "Starts" chip it shows as picked, and the day the first
     * occurrence is scheduled on — ALWAYS the picked day [dateIso], as for
     * weekly (an off-pattern day gets its one-off) and as web's create modal
     * does (web is canonical where the spec is silent; cross-platform
     * verification 2026-09-24). A LATER chip only moves week one: the picked
     * day keeps its slot as a one-off before the chip's weeks (Today + "Starts
     * Thu 1 Oct" → today, then 1 Oct, 15 Oct …). The sheet schedules it with
     * `reanchor = false` (AppViewModel.scheduleTask), or placing the picked day
     * would move week one back to that day's week. Any other rule is returned
     * as it is.
     */
    fun createStart(value: Recurrence?, dateIso: String): Pair<Recurrence?, String> {
        if (value !is Recurrence.EveryNWeeks || value.interval < 2) return value to dateIso
        val chips = startsChips(value.daysOfWeek, value.interval, dateIso)
        val i = chips.indexOfFirst { sameSeriesWeeks(it.anchor, value.anchor, value.interval) }
        if (i < 0) return value to dateIso
        return value.copy(anchor = chips[i].anchor) to dateIso
    }

    /** The "Starts" chips for [value], each with whether it is the rule's weeks.
     *  An edit counts them from [nWeeksBase] on the EDITED days (web review fix
     *  2: a days-only change keeps the stored anchor, so the chip it
     *  pre-selects must name the real first date); the create sheet ([create])
     *  from the picked day itself, as [createRule] does. */
    fun starts(stored: Recurrence?, value: Recurrence.EveryNWeeks, todayIso: String, startIso: String, create: Boolean = false): List<Pair<StartsChip, Boolean>> =
        startsChips(value.daysOfWeek, value.interval, if (create) startIso else nWeeksBase(stored, value.interval, todayIso, startIso, value.daysOfWeek))
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
    /** The create sheet keeps a "Starts" tap apart from the rule
     *  ([RecurrenceEditorModel.createRule]); null (the task sheet) saves it as the
     *  rule's week one. Non-null also marks the create sheet for the chips'
     *  base (the picked day itself). */
    onStartsPick: ((String) -> Unit)? = null,
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
                val chips = RecurrenceEditorModel.starts(stored, value, todayIso, startIso, create = onStartsPick != null)
                if (chips.isNotEmpty()) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Starts", style = UFont.sans(12), color = c.ink3)
                        chips.forEach { (chip, on) ->
                            SelectableChip(shortDayLabel(chip.date), selected = on, a11yLabel = "Starts ${shortDayLabel(chip.date)}") {
                                if (!on) {
                                    if (onStartsPick != null) onStartsPick(chip.anchor)
                                    else onChange(weekly(days, interval, anchor = chip.anchor))
                                }
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
