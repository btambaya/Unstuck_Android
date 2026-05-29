package tech.csalliance.unstuck.core

import tech.csalliance.unstuck.core.model.CalBlock
import tech.csalliance.unstuck.core.model.CalBlockKind
import tech.csalliance.unstuck.core.model.Capture
import tech.csalliance.unstuck.core.model.CaptureTag
import tech.csalliance.unstuck.core.model.Priority
import tech.csalliance.unstuck.core.model.Session
import tech.csalliance.unstuck.core.model.TaskItem
import tech.csalliance.unstuck.core.time.Clock
import tech.csalliance.unstuck.core.time.Time
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Shared test factories mirroring the `task()` / `block()` helpers in
// lib/visible-tasks.test.ts (via the iOS Helpers.swift) so the ported cases
// read 1:1. Tests run with -Duser.timezone=UTC.

/** Fixed "now" used across the ported cases (matches the web's NOW). */
val NOW: Long = Time.parseMillis("2026-05-21T12:00:00.000Z")!!

private val ISO_UTC_MS: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** ISO-8601 (UTC, millis) string for an epoch-ms value — matches JS
 *  `new Date(ms).toISOString()`. */
fun iso(ms: Long): String = ISO_UTC_MS.format(Instant.ofEpochMilli(ms))

/** Local today (+/- N days) as YYYY-MM-DD, consistent with Clock.todayIso(). */
fun todayPlus(days: Int): String =
    Clock.dateIso(Time.addDaysMillis(Time.startOfDayMillis(System.currentTimeMillis()), days))

fun localMillis(y: Int, m: Int, d: Int, hh: Int, mm: Int, ss: Int = 0): Long =
    LocalDateTime.of(y, m, d, hh, mm, ss).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

fun localIso(y: Int, m: Int, d: Int, hh: Int, mm: Int, ss: Int = 0): String =
    iso(localMillis(y, m, d, hh, mm, ss))

fun mkTask(
    id: String = "t",
    name: String = "A task",
    estimateMin: Int = 25,
    totalFocused: Int = 0,
    done: Boolean = false,
    priority: Priority? = null,
    tags: List<String>? = null,
    createdAt: String = "2026-05-21T10:00:00.000Z",
    updatedAt: String = "2026-05-21T10:00:00.000Z",
    lifeArea: String? = null,
    completedAt: String? = null,
    moveCount: Int? = null,
    later: Boolean? = null,
): TaskItem = TaskItem(
    id = id, name = name, estimateMin = estimateMin, totalFocused = totalFocused,
    done = done, priority = priority, tags = tags, lifeArea = lifeArea,
    moveCount = moveCount, completedAt = completedAt, later = later,
    createdAt = createdAt, updatedAt = updatedAt,
)

fun mkBlock(
    id: String = "b",
    taskId: String? = "t",
    taskName: String = "A task",
    startTime: String = "09:00",
    durationMinutes: Int = 25,
    date: String? = null,
    externalEventId: String? = null,
    kind: CalBlockKind? = null,
): CalBlock = CalBlock(
    id = id, taskId = taskId, taskName = taskName, startTime = startTime,
    durationMinutes = durationMinutes, date = date ?: Clock.todayIso(),
    externalEventId = externalEventId, kind = kind,
)

fun sess(id: String, taskId: String?, actualSec: Int, completedAt: String): Session =
    Session(id = id, taskId = taskId, taskName = "task", actualSec = actualSec, completedAt = completedAt)

fun cap(id: String, sessionId: String? = null, tag: CaptureTag, at: String): Capture =
    Capture(id = id, sessionId = sessionId, tag = tag, body = "x", at = at)
