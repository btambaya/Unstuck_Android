package tech.csalliance.unstuck.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tech.csalliance.unstuck.core.time.ClockFormat
import tech.csalliance.unstuck.core.time.ClockMode
import tech.csalliance.unstuck.design.theme.UFont
import tech.csalliance.unstuck.design.theme.UTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

// The calendar's "now": ONE look for the Day and the Week grids — a thin coral
// rule at the current minute and a coral "NOW" pill in the hour gutter. The Day
// view drew it inline; the Week view had none at all (Ahmad 2026-09-26: "on the
// weekly calendar we don't have that line that indicates the hour of the day").

/** The rule's weight (the Day view's 1.5dp). */
internal val NOW_LINE_THICKNESS = 1.5.dp
/** The dot at the leading edge of today's column in the Week view. */
internal val NOW_DOT_SIZE = 7.dp
internal const val NOW_LINE_TAG = "calendar-now-line"
internal const val NOW_DOT_TAG = "calendar-now-dot"
internal const val NOW_PILL_TAG = "calendar-now-pill"

/** Wall-clock now at [epochMs] in [zone] (the phone's zone in the app). */
internal fun nowIn(epochMs: Long, zone: ZoneId): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone)

/** Minutes from the grid's first hour down to [now], or null when [now] is
 *  outside the grid's [startHour]..[endHour] window. */
internal fun nowGridMinute(now: LocalTime, startHour: Int, endHour: Int): Int? {
    val m = now.hour * 60 + now.minute - startHour * 60
    return m.takeIf { it in 0..((endHour - startHour) * 60) }
}

/** Where the Week view draws "now": today's column + the minute down the grid. */
internal data class WeekNowMark(val column: Int, val minute: Int)

/** Null when today isn't one of the visible week's [days] (another week is
 *  shown) or the time is outside the grid. */
internal fun weekNowMark(now: LocalDateTime, days: List<LocalDate>, startHour: Int, endHour: Int): WeekNowMark? {
    val column = days.indexOf(now.toLocalDate()).takeIf { it >= 0 } ?: return null
    val minute = nowGridMinute(now.toLocalTime(), startHour, endHour) ?: return null
    return WeekNowMark(column, minute)
}

/** How long until the next wall-clock minute begins — so the line steps with the
 *  clock, not up to a tick late. (Zone offsets are whole minutes, so an epoch
 *  minute boundary is a local one in every zone.) */
internal fun millisToNextMinute(epochMs: Long): Long = 60_000L - Math.floorMod(epochMs, 60_000L)

/** Now, re-read as each minute begins while on screen, in the phone's CURRENT
 *  zone (read every tick, so a zone change moves the line on the next minute). */
@Composable
internal fun rememberCalendarNow(): State<LocalDateTime> {
    val now = remember { mutableStateOf(nowIn(System.currentTimeMillis(), ZoneId.systemDefault())) }
    LaunchedEffect(Unit) {
        while (true) {
            now.value = nowIn(System.currentTimeMillis(), ZoneId.systemDefault())
            // A hair past the boundary, so the read lands in the new minute.
            delay(millisToNextMinute(System.currentTimeMillis()) + 20)
        }
    }
    return now
}

/** The rule. The caller places it (offset to the minute) and gives it its width. */
@Composable
internal fun NowRule(modifier: Modifier = Modifier) {
    Box(modifier.height(NOW_LINE_THICKNESS).background(UTheme.colors.coral).testTag(NOW_LINE_TAG))
}

/** The dot that anchors the rule to the leading edge of today's Week column. */
@Composable
internal fun NowDot(modifier: Modifier = Modifier) {
    Box(modifier.size(NOW_DOT_SIZE).clip(CircleShape).background(UTheme.colors.coral).testTag(NOW_DOT_TAG))
}

/** The "NOW" pill in the hour gutter. Spoken as "Now, 2:30 PM" / "Now, 14:30"
 *  (the phone's 12/24-hour setting); [horizontalPadding] narrows it for the
 *  Week view's slimmer gutter. */
@Composable
internal fun NowPill(now: LocalTime, clock: ClockMode, modifier: Modifier = Modifier, horizontalPadding: Dp = 6.dp) {
    val spoken = "Now, " + ClockFormat.time(now.hour, now.minute, clock)
    Box(
        modifier.clip(RoundedCornerShape(999.dp)).background(UTheme.colors.coral)
            .clearAndSetSemantics { contentDescription = spoken; testTag = NOW_PILL_TAG }
            .padding(horizontal = horizontalPadding, vertical = 1.dp),
    ) {
        Text("NOW", style = UFont.mono(8, FontWeight.Bold), color = Color.White, maxLines = 1, softWrap = false)
    }
}
