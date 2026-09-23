package tech.csalliance.unstuck.core.logic

import tech.csalliance.unstuck.core.time.WireTime
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * One wake-window calibration sample (`wake_window_history`, migration 015): the
 * local date, the day's FIRST app input as local HH:MM, and the weekday
 * (0 = Sunday … 6 = Saturday, the server's convention). The server medians these
 * per weekday (calibrate_wake_windows) to time the morning brief — until clients
 * wrote them the feature was dead and every brief pinned to 08:00.
 */
data class WakeWindowSample(val localDate: String, val firstInputLocal: String, val weekday: Int)

fun wakeWindowSample(nowMs: Long, zone: ZoneId): WakeWindowSample {
    val local = Instant.ofEpochMilli(nowMs).atZone(zone)
    val weekday = if (local.dayOfWeek == DayOfWeek.SUNDAY) 0 else local.dayOfWeek.value   // MON=1 … SAT=6
    return WakeWindowSample(
        localDate = local.toLocalDate().toString(),
        // ASCII digits: record_wake_window refuses the phone's own digits (Android audit 2026-09-23, A12).
        firstInputLocal = WireTime.hm(local.hour, local.minute),
        weekday = weekday,
    )
}
