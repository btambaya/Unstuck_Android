# iOS Rebuild SPEC — Core Business Logic (Pure)

**Area:** `core/.../logic/` + `core/.../time/` + `core/.../model/` — the pure, deterministic engine of Unstuck.
**Reference client:** Android (`unstuck_android`). The web app (`unstuck`) and DB are the data contract.
**Target:** A new SwiftUI app. This module becomes a Swift package `UnstuckCore` with **zero UIKit/SwiftUI/Foundation-UI dependencies** — only `Foundation`. Everything here is a pure function of its inputs plus an injected `now: Int64` (epoch ms) or injected ISO strings, so it is unit-testable exactly like the Android/web code.

> **THE TESTS ARE THE SPEC.** Every JUnit case in `core/src/test/.../*Test.kt` must be ported to XCTest and pass byte-for-byte. The Android code itself notes these were "Ported 1:1 from `*.swift` / `lib/*.test.ts`" — so a prior Swift suite existed; recreate it. Where this document and a test disagree, **the test wins.** Run the Swift suite under `TZ=UTC` (the Kotlin suite runs with `-Duser.timezone=UTC`).

---

## 0. Module layout & dependency ordering

The Kotlin module is `:core`, depended on by `:data` (Room/local cache), `:sync` (supabase-kt), and `:app` (Compose). Mirror this as Swift packages so the dependency graph is identical:

```
UnstuckCore   (pure: this spec)          — no deps but Foundation
UnstuckData   (local JSON/GRDB store)    — depends on UnstuckCore
UnstuckSync   (supabase-swift, DbRowCodec, gateway) — depends on UnstuckCore + UnstuckData
UnstuckApp    (SwiftUI views, view models)— depends on all
```

**Critical ordering rule (gotcha):** `UnstuckCore` must NOT import anything from `Data`/`Sync`/`App`. The Kotlin `:core` has only `kotlinx-serialization` + `java.time`. In Swift that maps to `Foundation` (for `Date`, `Calendar`, `TimeZone`, `Codable`, `UUID`) and nothing else. Keep it that way or the test target won't link cleanly.

Files to produce (1 Swift file ≈ 1 Kotlin file):

| Kotlin | Swift | Purpose |
|---|---|---|
| `model/Models.kt` | `Models.swift` | domain structs (`Codable`) |
| `model/Enums.kt` | `Enums.swift` | string-backed enums |
| `time/Time.kt` | `Time.swift` | `Time` + `Clock` date math |
| `logic/Uuid.kt` | `Uuid.swift` | `newUuid`, `isUuid`, `uuidOrNull` |
| `logic/CalBlockKind.kt` | `CalBlockKind.swift` | `blockKind`, `isTaskBlock`… |
| `logic/TaskBucket.kt` | `TaskBucket.swift` | `isCompletedToday`, `isCreatedToday` |
| `logic/VisibleTasks.kt` | `VisibleTasks.swift` | bucketing, area/tag filter, slip |
| `logic/PickStartNext.kt` | `PickStartNext.swift` | the next-task ranker |
| `logic/Recurrence.kt` | `Recurrence.swift` | materialize/regenerate/label |
| `logic/FocusTimer.kt` | `FocusTimer.swift` | live-session state machine |
| `logic/FreeSlots.kt` | `FreeSlots.swift` | slot finder + conflict detector |
| `logic/Analytics.kt` | `Analytics.swift` | report/deep-dive derivations |
| `logic/AuthErrors.kt` | `AuthErrors.swift` | humanize / nextSafePath / detect |
| `logic/TaskMutations.kt` | `TaskMutations.swift` | completion stamp + move bump |
| `logic/GoogleSyncMapping.kt` | `GoogleSyncMapping.swift` | event↔block transforms |

---

## 1. Time & Clock — the foundation everything else depends on

**Source:** `core/.../time/Time.kt`. This is the single most important file: every date decision in the app routes through it. The header is explicit: *"Reproduce the JS Date semantics the web logic relies on: timestamps are ISO strings, date math is LOCAL, ISO strings compare lexicographically."*

### Constants
- `DAY_MS = 24 * 60 * 60 * 1000` (Int64).

### `Time` (a namespace/`enum Time {}` with static funcs)

All math is in **`ZoneId.systemDefault()`** — the device's *local* zone, mirroring JS `Date`. In Swift use `Calendar.current` with `TimeZone.current` (or inject a `Calendar` for tests). Epoch ms ↔ `Date` via `Date(timeIntervalSince1970: ms/1000)` and `Int64(date.timeIntervalSince1970 * 1000)`.

| Kotlin fn | Behavior | Swift equivalent |
|---|---|---|
| `parseMillis(iso): Long?` | ISO→epoch ms, **tiered parse** (see below). `null` if all tiers fail. | returns `Int64?` |
| `startOfDayMillis(now)` | local-midnight epoch ms of the day containing `now` | `Calendar.startOfDay(for:)` → ms |
| `civil(y, m, d)` | local-midnight epoch ms for a civil date (month **1-based**) | `DateComponents` → `Calendar.date` |
| `addDaysMillis(ms, n)` | local-midnight of the day `n` days after the day of `ms` (collapses to start-of-day!) | `Calendar.date(byAdding:.day)` then `startOfDay` |
| `dayOfWeekJs(ms)` | **JS getDay: 0=Sun … 6=Sat** | `Calendar.component(.weekday)` is 1=Sun…7=Sat → subtract 1 |
| `dayOfMonth(ms)` | day-of-month 1–31 | `.day` |
| `daysInMonth(ms)` | length of that calendar month (28–31) | `Calendar.range(of:.day,in:.month)` count |
| `hourOf(ms)` | local hour 0–23 | `.hour` |
| `minuteOf(ms)` | local minute 0–59 | `.minute` |
| `wholeDaysBetween(a, b)` | `Math.round((startOfDay(a) − startOfDay(b)) / DAY_MS)` | same formula |

**`parseMillis` tiered parsing (port exactly — many tests depend on it):**
1. `Instant.parse` — strict ISO-8601 with `Z` (e.g. `2026-05-21T10:00:00.000Z`).
2. else `OffsetDateTime.parse` — with explicit offset (`+02:00`).
3. else `LocalDateTime.parse` interpreted **in the system zone** (e.g. `2026-06-06T14:00`, no offset).
4. else `LocalDate.parse` → start-of-day in system zone (bare `2026-06-06`).
5. else `null`.

> The comment is load-bearing: tiers 3–4 exist *specifically* so an offset-less timestamp or bare date does **not** read as 00:00 UTC / a 15-minute sliver. In Swift, `ISO8601DateFormatter` alone is insufficient — build a small cascade (`ISO8601DateFormatter` with `.withInternetDateTime` + `.withFractionalSeconds`, then without fractional, then a `DateFormatter` with `yyyy-MM-dd'T'HH:mm` in `TimeZone.current`, then `yyyy-MM-dd` in `TimeZone.current`). Return `nil` on total failure. **Do not** silently default to "now" or epoch 0 — callers rely on `nil` to skip records.

### `Clock`
- `Clock.todayIso(): String` = `dateIso(System.currentTimeMillis())`.
- `Clock.dateIso(ms): String` = local `YYYY-MM-DD`, zero-padded (`"%04d-%02d-%02d"`).

> **Testability gotcha:** `Clock.todayIso()` reads the wall clock directly (not injected). Several `visibleTasks` tests call it live and build relative blocks with `todayPlus(n)`. In Swift, keep `Clock.todayIso()` reading `Date()` so those relative tests stay valid, but allow `dateIso(ms:)` to be called with an injected ms everywhere else. Consider a `ClockProtocol` you can stub for app code, but the test fixtures use the live clock for "today" and an injected `NOW` constant for everything else — replicate both.

### UTC ISO output (for sync / sessions)
The test fixture `iso(ms)` and `GoogleSyncMapping` produce **UTC ISO with milliseconds**: `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` (matches JS `toISOString()`). Provide a helper that formats a `Date` to exactly this shape (Swift `ISO8601DateFormatter` with `.withFractionalSeconds` yields `...SSSZ` with a literal `Z` — verify it emits 3 fractional digits and a `Z`, not `+0000`).

---

## 2. Domain models & enums

**Source:** `model/Models.kt`, `model/Enums.kt`. All structs `Codable`, **camelCase property names** (snake_case is applied only at the DB boundary in `UnstuckSync` — see §13). Embedded JSONB blobs (`Objective`, `Comment`, `CollectionItem`, `Recurrence`) keep camelCase keys *that is the server's JSONB shape*.

### Enums (string-backed; raw values = server strings)

```
Priority: urgent | high | medium | low
FocusState: idle | starting | running | overrun | pause | done | resume
FocusTreatment: ambient | cockpit | monk
CalBlockKind: task | placeholder | external
ReasonAction: pause | switch
CaptureTag: follow-up | idea | edit | question | distraction
CalendarProvider: google | apple | microsoft
ThemePref: system | light | dark
Density: compact | regular | comfy
TaskListView: ALL("All") BACKLOG("Backlog") TODAY("Today") UPCOMING("Upcoming") LATER("Later") COMPLETED("Completed")
```

Swift: `enum Priority: String, Codable, CaseIterable { case urgent, high, medium, low }`. For hyphenated cases (`follow-up`) use explicit raw values: `case followUp = "follow-up"`. `TaskListView` carries a display label.

### Key structs (fields + optionality matter — see §12 gotcha)

**`TaskItem`** (the central model):
`id: String`, `name: String`, `estimateMin: Int`, `totalFocused: Int = 0`, `done: Bool = false`, `priority: Priority? = nil`, `tags: [String]? = nil`, `objectives: [Objective]? = nil`, `comments: [Comment]? = nil`, `intentWhen: String? = nil`, `intentThen: String? = nil`, `lifeArea: String? = nil`, `firstPhysicalAction: String? = nil`, `moveCount: Int? = nil`, `completedAt: String? = nil`, `later: Bool? = nil`, `recurrence: Recurrence? = nil`, `sourceCollectionId: String? = nil`, `sourceItemId: String? = nil`, `dueAt: String? = nil`, `createdAt: String`, `updatedAt: String`.

**`Session`:** `id`, `taskId: String? = nil`, `taskName: String`, `tags: [String]? = nil`, `estimateMin: Int? = nil`, `actualSec: Int`, `completedAt: String`.

**`CalBlock`:** `id`, `taskId: String?`, `taskName: String`, `startTime: String /*HH:MM*/`, `durationMinutes: Int`, `date: String /*YYYY-MM-DD*/`, `externalEventId: String? = nil`, `externalConnectionId: String? = nil`, `kind: CalBlockKind? = nil`.

**`ReasonLog`:** `id`, `taskId: String? = nil`, `reason: String`, `action: ReasonAction`, `at: String`, `durationSec: Int? = nil`.

**`Capture`:** `id`, `taskId: String? = nil`, `sessionId: String? = nil`, `tag: CaptureTag`, `body: String`, `at: String`.

**`Objective`:** `text: String`, `done: Bool? = nil`, `minutes: Int? = nil`.
**`Comment`:** `text: String`, `at: String? = nil`.

**`CalendarConnection`, `ExternalEvent`, `CollectionItem`, `ItemCollection`, `TagRow`, `LifeArea`** — port as in `Models.kt`. (`ItemCollection` has client-only fields `ownerId/members/myRole/archived` that NEVER round-trip to the DB; relevant to Sync, not Core logic, but include them in the model.)

**`LiveSession`** (device-local focus state):
`id: String?`, `taskId: String`, `sessionStart: Int64? = nil /*epoch ms*/`, `paused: Bool = false`, `pausedAt: Int64? = nil`, `sessionEstimateMin: Int`, `nudge80Fired: Bool = false`, `overrunPromptFired: Bool = false`, `treatment: FocusTreatment`, `priorAccumulatedSec: Int? = nil`.

### `Recurrence` — tagged union with a custom serializer

```
sealed class Recurrence { abstract val until: String?
  Daily(until)
  Weekly(daysOfWeek: List<Int>, until)   // 0=Sun…6=Sat
  Monthly(until)
}
```
JSON shape (this **is** the server JSONB): `{"kind":"daily|weekly|monthly", "daysOfWeek":[...]?, "until":"YYYY-MM-DD"?}`. `until` inclusive.

**Swift port:** an `enum Recurrence: Equatable` with a hand-written `Codable`:
```swift
enum Recurrence: Equatable {
  case daily(until: String?)
  case weekly(daysOfWeek: [Int], until: String?)
  case monthly(until: String?)
  var until: String? { ... }
}
```
- **encode:** emit `kind`; for `.weekly` emit `daysOfWeek`; emit `until` only if non-nil (Kotlin does `value.until?.let { put("until", it) }` — so `until` is **omitted when nil**, not encoded as null).
- **decode:** read `kind`; `daily`/`monthly` read `until`; `weekly` reads `daysOfWeek` (default `[]` if absent) + `until`. Unknown `kind` → **throw** (`CoreModelsTest.unknownKindThrows`).
- Round-trip tests (`CoreModelsTest`): `daily()`, `daily("2026-09-01")`, `weekly([1,3,5])`, `monthly("2026-12-31")` must survive encode→decode equal; decoding the literal `{"kind":"weekly","daysOfWeek":[0,6],"until":null}` yields `.weekly([0,6], until: nil)` (explicit `null` until is accepted on decode).

---

## 3. UUID gate

**Source:** `logic/Uuid.kt`.
- `newUuid() -> String`: lowercase canonical v4. Swift `UUID().uuidString` is **uppercase** — call `.lowercased()`. Test `newUuidIsLowercasedAndValid` asserts `u == u.lowercased()`.
- `isUuid(s) -> Bool`: regex `^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`.
- `uuidOrNull(s: String?) -> String?`: `s` if `isUuid(s)`, else `nil`. **Why it exists:** FK columns (`task_id`, `session_id`, `external_connection_id`) drop to `null` when the local id isn't a real UUID (e.g. demo/`g_…`/`cal-…`/`placeholder` ids). Used at the Sync boundary, but lives in Core.

---

## 4. CalBlockKind derivation

**Source:** `logic/CalBlockKind.kt`. A `CalBlock` may not carry an explicit `kind` (legacy rows); derive it:

```
blockKind(b):
  if b.kind != nil -> return b.kind          // stored kind wins
  if b.externalEventId is non-empty -> .external
  let id = b.taskId ?? ""
  if id == "placeholder" -> .placeholder
  if id.hasPrefix("cal-") -> .external
  return .task
```
- `isTaskBlock(b)` = `blockKind(b) == .task && (b.taskId ?? "") != ""`.
- `isPlaceholderBlock`, `isExternalBlock` analogous.

Tests (`CoreModelsTest`): stored kind wins; an `externalEventId` forces `.external` (and `isTaskBlock == false`); `taskId=="placeholder"` → placeholder; `taskId=="cal-123"` → external; a plain task id → task & `isTaskBlock==true`; a block with `kind=.external` but `taskId=nil` is **not** a task block. **`isTaskBlock` is the gate used throughout `visibleTasks` and `regenerateForTask`** — get it exactly right.

---

## 5. TaskBucket — "is this in today's window?"

**Source:** `logic/TaskBucket.kt`.
- `isCompletedToday(task, now) -> Bool`: parse `task.completedAt` (nil → false); true iff `start ≤ t < start + DAY_MS` where `start = Time.startOfDayMillis(now)`.
- `isCreatedToday(task, now) -> Bool`: same window on `task.createdAt`.
- `daysSinceCreated(task, now) -> Int` (in `VisibleTasks.kt`): `floor(max(0, now − created) / DAY_MS)`; nil created → 0.

Boundary tests (`TaskMutationsTest`, all local-time, run UTC): completed at exactly today-00:00 → true; at today-23:59:59 → true; yesterday-23:59:59 → false; the instant tomorrow-00:00 starts → false. **Half-open interval `[start, start+DAY_MS)`** — replicate precisely.

---

## 6. VisibleTasks — the task-list bucketing engine

**Source:** `logic/VisibleTasks.kt`. Powers the `/tasks` list across all six tabs. **Header rule:** *Today is intentionally area-agnostic* — an active area filter does NOT hide today-scheduled tasks of other areas. The area filter applies only to All/Backlog/Upcoming/Later/Completed.

### Constants
`UNASSIGNED_AREA = "Unassigned"` (sentinel), `SLIP_AGE_MS = 21 days`, `SLIP_MOVE_THRESHOLD = 3`.

### Helpers
- `matchesArea(taskArea, activeArea) -> Bool`: nil/empty active → true (no filter); active == `"Unassigned"` → `taskArea` is nil/empty; else `taskArea == activeArea`. **Single source of truth — `pickStartNext` calls it too.**
- `matchesTag(taskTags, activeTag) -> Bool`: nil/empty active → true; else any tag `lowercased == activeTag.lowercased()`.
- `isSlipping(task, now) -> Bool`: false if done; true if `moveCount ?? 0 >= 3`; else true if `now − created >= 21d` (nil created → false).

### `visibleTasks(view, tasks, blocks, now, activeArea, activeTag = nil, slipMode) -> [TaskItem]`

Precompute from `blocks` (using `Clock.todayIso()` for `today`, and **only task-shaped blocks** via `isTaskBlock`):
- `todayTaskIds` = block.date == today
- `upcomingTaskIds` = block.date > today (lexicographic string compare — valid for `YYYY-MM-DD`)
- `scheduledTaskIds` = any task block
- `pastOnlyTaskIds` = scheduled but neither today nor upcoming (overdue → Backlog)

Per view:
- **TODAY:** `!done && later != true && (id ∈ todayTaskIds || (isCreatedToday(t,now) && id ∉ upcomingTaskIds))`. (Scheduled-today OR created-today, but never a task the user pushed to a future day.)
- **BACKLOG:** `!done && later != true && !isCreatedToday && (id ∉ scheduledTaskIds || id ∈ pastOnlyTaskIds)`. (Open work not actively planned and ≥1 day old: never scheduled, or only ever scheduled in the past.)
- **UPCOMING:** `!done && id ∈ upcomingTaskIds && id ∉ todayTaskIds`.
- **LATER:** `!done && later == true`.
- **COMPLETED:** `done`.
- **ALL:** `!done || isCompletedToday(t, now)`.

Then:
1. **Area filter** — applied to every view **except TODAY** (`view == TODAY ? byView : byView.filter { matchesArea(lifeArea, activeArea) }`).
2. **Tag filter** — applied to **every** view including Today (explicit narrowing), case-insensitive, only when `activeTag` non-empty.
3. **Slip filter** — if `slipMode`, keep only `isSlipping`.
4. **Final ordering:** open tasks first, then completed, **preserving original order within each group**: `afterSlip.filter{!done} + afterSlip.filter{done}`. In Swift, `filter` is order-preserving — do exactly this two-pass partition (the comment says "mirrors iOS").

**Test coverage to port (`VisibleTasksTest`, 23 cases):** area-agnostic Today; All honours area; Upcoming honours area; open-before-completed ordering; Today filters completed out; Completed keeps order; Today = scheduled-today + created-today but not older-unscheduled and not future-scheduled; Today excludes `later`; Later shows only later; Backlog includes unscheduled-older-than-today; Backlog includes past-only overdue; Backlog excludes created-today / today-scheduled / future-scheduled / later / done; Backlog: past+today blocks count as Today not Backlog; tag filter case-insensitive; tag AND area; empty when no task carries tag; nil tag is no-op; isSlipping (≥3 moves, >21d, skips done); daysSinceCreated.

> **UTC-date gotcha:** these tests mix an injected `NOW = 2026-05-21T12:00Z` with the **live** `Clock.todayIso()` and `todayPlus(n)` (live wall clock). The bucketing uses `Clock.todayIso()` for the block-date comparisons but `now` for the created/completed-today checks. Under `TZ=UTC` these coincide on the test machine. **Port the fixtures verbatim** (live `Clock` for blocks, injected `NOW` for task timestamps) or the boundary cases will flake.

---

## 7. PickStartNext — the universal "what next?" ranker

**Source:** `logic/PickStartNext.kt`. Deterministic; powers Start-Next card, Up-Next, the `/tasks` NEXT badge, and the focus-mode UP-NEXT panel — *so every surface agrees.*

**Ranker (a stable sort):**
1. priority desc (`urgent=4 > high=3 > medium=2 > low=1`; **nil priority treated as LOW**).
2. `estimateMin` asc.
3. `createdAt` asc — ISO strings compared **lexicographically == chronologically** (matches web `localeCompare`). Use `String <` / `.compare`.

Swift sort must be **stable** (Swift's `sort` is *not* guaranteed stable across versions — implement a stable comparator by carrying the original index as a final tiebreaker, OR use a manual merge; the Kotlin `sortedWith` is stable and tests rely on it via `createdAt` ties).

- `pickStartNext(tasks, blocks, liveTaskId, areaFilter = nil) -> TaskItem?`: filter `!done && later != true && id != liveTaskId`, then `matchesArea(lifeArea, areaFilter)`, sort by ranker, take first. (`blocks` param is currently unused — keep the signature for parity.)
- `pickUpNext(tasks, blocks, liveTaskId, startNextId, limit = 3) -> [TaskItem]`: skip set = `{liveTaskId, startNextId}` (non-nil); filter `!done && later != true && id ∉ skip`, sort, take `limit`. (Note: Up Next does **not** apply the area filter.)

**Tests (`PickStartNextTest`):** urgent-small beats urgent-big beats low; estimate tie → earlier createdAt; nil priority < medium; excludes done/later/live; area filter picks Work even when Personal is higher priority; returns nil when no candidates; Up Next skips live+startNext and limits to 2 → `[b,c]`; Up Next excludes done+later.

---

## 8. Recurrence — materialize / regenerate / label

**Source:** `logic/Recurrence.kt`. Pure; callers pipe the diff through sync. A task has at most one recurrence; the client materializes cal_blocks `RECURRENCE_HORIZON_DAYS = 56` (8 weeks) ahead at the same start time. Past occurrences preserved; future regenerated on edit.

### `materializeOccurrences(recurrence, startDate, startTime, horizonDays = 56) -> [MaterializedOccurrence(date, startTime)]`
`startDate` is a **local-midnight epoch ms**. Loop `i in 0..<horizonDays`: `day = addDaysMillis(startDate, i)`, `iso = Clock.dateIso(day)`; if `until != nil && iso > until` → **break**; if `matchesRecurrence` → append `(iso, startTime)`.

`matchesRecurrence(r, startDate, candidate)`:
- false if `startOfDay(candidate) < startOfDay(startDate)`.
- `.daily` → true.
- `.weekly` → `Time.dayOfWeekJs(candidate) ∈ daysOfWeek` (0=Sun…6=Sat).
- `.monthly` → `dayOfMonth(candidate) == min(dayOfMonth(startDate), daysInMonth(candidate))`. **Day-clamp:** a 29/30/31 start fires on the last day of shorter months (Feb 28/29) and **recovers** to 31 in long months — it does not drift down.

**Tests (`RecurrenceTest`):** daily = 1/day, 14 days `2026-05-21`→`2026-06-03`; weekly Mon/Wed/Fri exact list; monthly same-day-of-month; **Jan-31 monthly → `[01-31, 02-28, 03-31, 04-30]`** (non-leap), `[01-31, 02-29, 03-31, 04-30]` (2024 leap); default horizon == 56; `until` inclusive stop; nil `until` uses horizon; weekly+until skips out-of-range.

### `regenerateForTask(task, recurrence?, existingBlocks, todayIso, startTime, startDate, horizonDays = 56) -> RegenPlan(toUpsert: [CalBlock], toDelete: [String])`
- `existing` = task-blocks for this task (`isTaskBlock`); `futureExisting` = `date > todayIso`.
- If `recurrence == nil`: delete every `futureExisting.id`, upsert nothing (keep history).
- Else: `desired = materialize(...).filter { date > todayIso }`. Key = `"\(date)|\(startTime)"`.
  - `toDelete` = futureExisting whose key ∉ desiredKeys.
  - `toUpsert` = desired whose key ∉ existingFutureKeys → new `CalBlock(id: newUuid(), taskId: task.id, taskName: task.name, startTime: o.startTime, durationMinutes: task.estimateMin, date: o.date, kind: .task)`.

**Tests:** null recurrence deletes future, keeps past+today; weekly adds missing & deletes mismatched stray; preserves a matching future block (no delete, only the missing one upserted).

### `recurrenceLabel(r?) -> String`
- nil → `""`.
- `.daily` → `"Repeats daily"`; `.monthly` → `"Repeats monthly"`.
- `.weekly`: 7 days → `"Repeats daily"`; else `"Repeats \(formatDays)"`.
- `formatDays(days)`: sort unique; `[1,2,3,4,5]` → `"weekdays"`; `[0,6]` → `"weekends"`; else `Sun/Mon/.../Sat` joined by `/` (labels `["Sun","Mon","Tue","Wed","Thu","Fri","Sat"]`).
- `until` suffix: parse `until` as `Y-M-D`; if valid and `m∈1..12`, append ` until \(MONTH[m-1]) \(d), \(y)` (months `Jan…Dec`).

**Tests:** nil→""; daily-until→`"Repeats daily until Jun 15, 2026"`; Mon/Wed/Fri-until→`"...until Aug 1, 2026"`; weekdays; weekends; all-7→daily; Mon/Wed/Fri.

---

## 9. FocusTimer — the live-session state machine

**Source:** `logic/FocusTimer.kt`. *Pure core* of the focus session. The Kotlin comment is the contract: *"The Compose layer owns the ticking clock, persistence, sound, and the transient forced 'done' state; everything here is a pure function of a `LiveSession` + an injected `now` (epoch ms)."* Port as a `FocusTimer` enum/namespace of static functions.

### Constants / derivations
- `empty`: `LiveSession(id:nil, taskId:"", sessionStart:nil, paused:false, pausedAt:nil, sessionEstimateMin:25, nudge80Fired:false, overrunPromptFired:false, treatment:.ambient, priorAccumulatedSec:0)`.
- `estimateSec(live)` = `(sessionEstimateMin != 0 ? sessionEstimateMin : 25) * 60`.
- `elapsedSec(live, now)`: 0 if `sessionStart == nil`; `elapsedMs = paused && pausedAt != nil ? pausedAt − start : now − start`; return `max(0, elapsedMs/1000)`. **Excludes prior accumulated.**
- `displayedElapsedSec(live, now)` = `elapsedSec + (priorAccumulatedSec ?? 0)`.
- `deriveState(live, now, overrunGraceSec: Double) -> FocusState`: `sessionStart==nil → .idle`; `paused → .pause`; `grace == +∞ → .running`; if `displayedElapsedSec(now) >= estimateSec + grace → .overrun`; else `.running`. **Overrun is computed on DISPLAYED elapsed (incl. prior), not raw.**
- `overrunGraceSeconds(pref: String?) -> Double`: nil/empty → `1.0`; `"Never"` → `+∞`; `"5 min"` → `300`; `"10 min"` → `600`; else `1.0`.

### Transitions (pure: current → next)
- **`start(cur, taskId, estimateMin? , priorAccumulatedSec?, now, newId = newUuid)`** — resume-aware:
  - same task + currently paused → `resume(cur, now)` (shift `sessionStart` by the pause gap; **ignores incoming prior**).
  - same task + running → return `cur` unchanged (no reset on double-Start).
  - otherwise → fresh: `id=newId()`, `sessionStart=now`, `paused=false`, `pausedAt=nil`, `sessionEstimateMin = estimateMin ?? 25`, clear nudge/overrun flags, `priorAccumulatedSec = prior ?? 0`.
- **`pause(cur, now)`**: nil start → cur; else `paused=true, pausedAt=now`.
- **`resume(cur, now)`**: nil start → cur; `pausedDuration = pausedAt != nil ? now − pausedAt : 0`; `paused=false, pausedAt=nil, sessionStart = start + pausedDuration`. (Shifting start forward preserves elapsed across the pause.)
- **`done(cur)`**: `id=nil, sessionStart=nil, paused=false, pausedAt=nil` (elapsed resets to 0; the session-row writeback already happened pre-done; UI forces a transient `done` display).
- **`cancel(cur)`**: `empty.copy(treatment = cur.treatment)` (keeps treatment).
- **`extend(cur, minutes)`**: `sessionEstimateMin += minutes`, `overrunPromptFired=false`.
- **`setTreatment(cur, t)`**: `treatment = t`.

### `formatMMSS(sec: Int) -> String`
`MM:SS`, zero-padded, with leading `-` for negatives: `0→"00:00"`, `65→"01:05"`, `3600→"60:00"`, `-65→"-01:05"`.

**Tests (`FocusTimerTest`, 20+):** format; idle by default; start→running (estimateSec 300); pause→resume; cancel→idle; extend +10 → 15min; setTreatment monk; **start-on-same-paused resumes without resetting elapsed**; start-on-different starts fresh (elapsed ≤1); start-on-same-running is no-op (same `sessionStart`); elapsed frozen while paused & survives resume (then advances +10 over 10s); start with prior seeds displayed (=600) while raw elapsed ≤1; defaults prior to 0; pause/resume preserves prior; **overrun fires on displayed not raw** (prior 24min + 25min estimate, grace 5min → overrun at t0+7min); done clears sessionStart & elapsed→0; start-on-same-paused **ignores** incoming prior (→0); "Never" grace → never overrun even after 60min on a 1-min estimate.

> **iOS platform note (foreground service):** Android backs this with a foreground service so the timer ticks reliably. iOS has **no foreground service** — there is no way to keep a process running a wall-clock timer in the background. The pure engine doesn't care (it's `now`-injected), but the App layer must reconstruct elapsed time from `sessionStart` on every foreground/launch (which `elapsedSec` already supports), and schedule overrun/nudge via local notifications rather than an in-process tick. This is the single biggest behavioral divergence to design around — Core stays pure; the App layer recomputes from persisted `sessionStart`/`pausedAt`.

---

## 10. FreeSlots — slot finder + conflict detector

**Source:** `logic/FreeSlots.kt`. Pure; dates as local-midnight epoch ms.

### Formatting
- `parseHhmm("HH:MM") -> Int` minutes-since-midnight (lenient: missing parts → 0).
- `hhmmFromMin(min) -> "HH:MM"` zero-padded.
- `formatTime("HH:MM") -> "h:MM AM/PM"`: 12-hour, `h12 = ((h+11)%12)+1`, period `PM if h>=12`. Tests: `09:00→"9:00 AM"`, `14:30→"2:30 PM"`, `00:15→"12:15 AM"`, `12:00→"12:00 PM"`.
- `blockTimeRange(b) -> "9:00 AM–10:00 AM"` (en-dash `–`, U+2013).

### `findFreeSlots(blocks, durationMin, now, startDate? = nil, daysToScan = 4, dayStartMin = 480, dayEndMin = 1080, limit = 9) -> [Slot(date, label, startTime)]`
Scan up to `daysToScan` days from `startDate ?? now`. For each day:
- `dayIso = Clock.dateIso(day)`; collect that day's blocks' `(startMin, startMin+duration)`, sorted by start. **Every block counts as a conflict** (task/placeholder/external alike).
- If the day **is today** (`startOfDay(day)==startOfDay(now)`): `startMin = max(dayStartMin, ceil((nowMin+5)/15)*15)` — snap to the next 15-min mark ≥ now+5. Else `startMin = dayStartMin`.
- `step = max(durationMin, 30)` (back-to-back no closer than 30min). Append a sentinel `(dayEndMin, dayEndMin)` to `scan`. For each block: `gapEnd = min(block.start, dayEndMin)`; while `cursor + durationMin <= gapEnd`: emit slot at `cursor` with label `"\(dayLabel) · \(formatTime)"`, advance `cursor += step`, stop at `limit`. After the gap, `cursor = max(cursor, block.end)`.
- `dayLabelFor(day, now)`: diff 0 → `"Today"`, 1 → `"Tomorrow"`, else `DOW_LABELS[dayOfWeekJs(day)]` (Sun…Sat). (Note: the label uses `·` U+00B7 middle dot.)

### `findFreeSlotsForDate(blocks, durationMin, isoDate, now, limit = 6, dayStartMin, dayEndMin)`
Parse `isoDate` `Y-M-D` (invalid → `[]`); `day = Time.civil(y,m,d)`; delegate to `findFreeSlots(... startDate: day, daysToScan: 1 ...)`.

### `findConflicts(date, startTime, durationMin, blocks, excludeBlockId? = nil) -> [Conflict(block, overlapMin)]`
For blocks on `date` (skip `excludeBlockId`): `overlap = max(0, min(end, bEnd) − max(start, bStart))`; include if `> 0`; sort by block start.

**Tests (`FreeSlotsTest`):** empty day starts 08:00; skips too-short windows (08:15–09:00 block → next 09:00); limits results; today snaps 08:07→08:15; future date independent of now (08:00); conflicts returns overlapping `[a,b]` with `overlapMin=15`; empty when none; excludes edited block; ignores other dates; formatTime; blockTimeRange. **All run under `TZ=UTC` with `localMillis(...)` fixtures.**

---

## 11. Analytics — Report & Deep-Dive derivations

**Source:** `logic/Analytics.kt`. Pure functions over live collections. Charts decide if they have enough data: `REAL_DATA_THRESHOLD = 5` (fewer sessions → demo data in the UI; Core just exposes the threshold).

- `dayOfWeekIdx(ms) -> Int`: **Monday-anchored** `(dayOfWeekJs(ms) + 6) % 7` (Mon=0…Sun=6).
- **H1 `weekdayAreaHours(sessions, tasks, areas = DEFAULT_AREAS)` → `[StackedBar(d, data:[Double])]`** (7 rows Mon…Sun, one Double per area). Map `taskId→lifeArea` (unassigned tasks **drop out**, never coerced to "Work"). For each session: skip if no taskId/area or area ∉ `areas`; add `actualSec/3600` to `row[dayOfWeekIdx(completedAt)][areaIdx]`. `DEFAULT_AREAS = [Work, Personal, Home, Health, Volunteering]`, `DAY_LABELS = [Mon…Sun]`.
- **H2 `calibrationDots(sessions, tasks, cap=24)` → `[CalibrationDot(e: estimateMin, a: round(actualSec/60), t: name)]`**: sort sessions **completedAt desc**, take cap, join to task by id. `calibrationHitRate(dots, slackMin=5) -> Double`: fraction with `abs(a−e) <= slack` (empty → 0).
- **H3 `interruptionBins(captures, sessions, binMin=3, binCount=10) -> [Int]`**: per session, `sessionStart = parse(completedAt) − actualSec*1000`; per capture with a `sessionId`, `intoMin = (parse(at) − start)/60000`; skip if `<0`; bin `min(binCount-1, floor(intoMin/binMin))`.
- **H4 `timeOfDayHeatmap(sessions) -> [[Double]]`** (5 weekdays × 6 two-hour buckets from 7am): `dow = dayOfWeekIdx`; skip if `>4` (weekend); `bucket = floor((hourOf − 7)/2)`; skip if outside 0..5; add `actualSec/3600`.
- **H5 `pauseAnatomy(reasonLogs) -> [PauseBar(reason, minutes, count)]`**: group by `reason` (empty→"Other"), **preserve first-seen order**; sum `durationSec/60` (only when `>0`) and count; sort by `minutes` desc then `count` desc; take 6.
- **H6 `reEntryDistribution(sessions, binMin=5, binCount=12) -> [Int]`**: group by task, sort each by completedAt asc; for consecutive pairs `gapMin = (thisEnd − prevEnd)/60000 − thisActualSec/60`; skip `<=0`; bin `min(binCount-1, floor(gapMin/binMin))`.
- **H7 `slipping(tasks, now = Date.now) -> [SlipRow(name, weeks, moveCount)]`**: skip done; `ageDays = (now − created)/86400000`; include if `ageDays >= 21 || moveCount >= 3`; `weeks = max(0, floor(ageDays/7))`; sort by `moveCount` desc then `weeks` desc; take 6.
- `captureBreakdown(captures) -> [CaptureTag: Int]`: **ordered** keys FOLLOW_UP, IDEA, EDIT, QUESTION, DISTRACTION, each counted (use an ordered structure / `KeyValuePairs` so the map starts populated with all five at 0).
- `topInsights(sessions, tasks, captures, reasonLogs) -> [Insight(title, sub)]` (take 3): when `sessions.count >= 5`: (1) strongest weekday by focus minutes; (2) calibration phrase if `dots >= 3` (`>=0.75 "nailing", >=0.5 "improving", else "settling"`). Always: (3) top slipping task (`moveCount>=3 → "rescheduled N times"` else `"W+ weeks on the list"`). Empty data → `[]` (no fallbacks).

**Tests (`AnalyticsTest`):** dayOfWeekIdx anchoring; weekdayAreaHours groups (Tue Work 1.0, Wed Personal 0.5); calibration hit-rate 1.0 within slack / 0.0 when miss by 10; interruption capture lands in bin 3; pause anatomy aggregates minutes & falls back to count, caps at 6; re-entry 10-min gap → bin 2; slipping flags >21d / ≥3 moves / ignores done; capture breakdown counts; heatmap skips weekends & clamps (grid 5 rows, Tue-8am=0.5); topInsights empty→[], surfaces slipping, rich data surfaces weekday + calibration.

> **Rounding note:** Kotlin uses `Math.round(Double)` (half-up to nearest, ties→+∞). Swift `Double.rounded()` is `.toNearestOrAwayFromZero` — for non-negative values this matches `Math.round` for `.5` ties; use `(x).rounded()` and cast to Int. For `Math.round(x).toInt()` on durations/percentages, verify the `27*60/60 = 27` and `50*60/60 = 50` test values; ties are unlikely but prefer `.rounded(.toNearestOrAwayFromZero)` to match.

---

## 12. AuthErrors — humanize / open-redirect guard / anti-enumeration

**Source:** `logic/AuthErrors.kt`. Pure; the networked sign-in/up/out lives in Sync.

- `AuthErrorInfo(code: String? = nil, message: String? = nil, status: Int? = nil)`.
- `humanizeAuthError(err?) -> String`: nil → `"Something went wrong. Try again in a moment."`; match in **this order** on `code` exact OR `message.lowercased().contains(...)`: rate-limit (`over_email_send_rate_limit` / "rate limit") → "We can only send a few sign-up emails per hour…"; invalid creds; user-already-exists; email-not-confirmed; weak-password; `over_request_rate_limit` or `status==429`; network ("network"/"failed to fetch"/"timed out"); invalid email / `validation_failed`; **fallback** = capitalize first char of `message` (empty message → return it as-is). Port the exact copy strings (tests assert substrings).
- `nextSafePath(raw?, fallback = "/dashboard") -> String`: nil/empty → fallback; **URL-decode** (decode failure → fallback); must start with single `/` (not `//`) else fallback. Tests: accepts `/tasks`, preserves query, rejects `//evil`, rejects absolute URLs & `javascript:`, rejects `tasks`/`../tasks`, decodes `%2Ftasks`, fallback on bad encoding `%E0%A4%A`, honours custom fallback. (Swift: `removingPercentEncoding`, returns nil on malformed → fallback.)
- `detectSignupAlreadyExists(identitiesCount?, emailConfirmedAt?, lastSignInAt?, hasSession) -> Bool`: true if `identitiesCount == 0` OR (`emailConfirmedAt != nil && !hasSession`) OR (`lastSignInAt != nil && !hasSession`). (Supabase's anti-enumeration sign-up "succeeds" for an existing email; these are the tells.)

---

## 13. TaskMutations & GoogleSyncMapping

### TaskMutations (`logic/TaskMutations.kt`)
- `stampCompletion(isDone, incomingCompletedAt?, priorCompletedAt?, nowISO) -> String?`: if `isDone` → `incoming ?? prior ?? nowISO`; else `nil`. (First done-flip stamps now; re-toggle preserves original; un-complete clears.)
- `applyCompletion(item, prior?, nowISO) -> TaskItem`: copy with `completedAt = stampCompletion(item.done, item.completedAt, prior?.completedAt, nowISO)`, `updatedAt = nowISO`.
- `bumpMoveCount(task, nowISO) -> TaskItem`: `moveCount = (moveCount ?? 0) + 1`, `updatedAt = nowISO`.

Tests (`TaskMutationsTest`): first flip stamps now; keeps incoming; preserves prior on retoggle; uncomplete clears; bump from nil→1, 2→3, sets updatedAt.

> **LWW gotcha:** these stamp `updatedAt = nowISO`. The sync layer uses last-write-wins on `updatedAt`. Keep the timestamp injected (don't read the clock inside Core) so writes are deterministic; the App/Sync layer passes a single canonical `nowISO` per mutation.

### GoogleSyncMapping (`logic/GoogleSyncMapping.kt`)
Value transforms only (orchestration in Sync). Uses the **UTC-ms ISO formatter** `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`.
- `isoToLocalYmd(iso) -> "YYYY-MM-DD"` (local zone; parse-fail → `iso.prefix(10)`).
- `isoToLocalHHMM(iso) -> "HH:MM"` (local; parse-fail → `"00:00"`).
- `diffMinutes(startIso, endIso) -> Int`: `max(15, round((end−start)/60000))` (floored at 15; parse-fail → 15).
- `externalEventToBlock(ev, calendarId) -> CalBlock`: `id = "g_\(ev.id)"` (stable, so re-pulls overwrite the same row), `taskId = nil`, `taskName = ev.summary.isEmpty ? "(untitled)" : ev.summary`, `startTime = isoToLocalHHMM(ev.start)`, `durationMinutes = diffMinutes(start,end)`, `date = isoToLocalYmd(ev.start)`, `externalEventId = ev.id`, `externalConnectionId = ev.connectionId`, `kind = .external`.
- `blockToIsoRange(b) -> (String, String)`: parse `b.date` + `b.startTime` as a **local** datetime → UTC ISO start; end = start + `durationMinutes*60`s. Invalid date → `(b.date, b.date)`.

Tests (`GoogleSyncMappingTest`): ymd anchors; HHMM zero-pads; diffMinutes clamps to 15 / computes 90; external marks `.external` + carries ids + nil taskId; stable `g_`-prefixed id; untitled fallback; zero-length → 15min; `blockToIsoRange("09:00",90,"2026-05-21")` → `("2026-05-21T09:00:00.000Z","2026-05-21T10:30:00.000Z")` (under `TZ=UTC`).

---

## 14. The Supabase data contract (for the Sync layer that consumes Core)

Core stays pure, but its models map 1:1 to these tables (snake_case at the boundary). Reference: `001_initial.sql` + migrations 005/006/008/009/025; the row codec is `sync/DbRowCodec.kt`.

**`tasks`** (camel→snake): `estimate_min`, `total_focused`, `intent_when`, `intent_then`, `life_area`, `first_physical_action`, `move_count`, `completed_at` (migration 005), `later` + `recurrence` jsonb (008), `source_collection_id`/`source_item_id`/`due_at` (025), `created_at`, `updated_at`. `tags text[]`, `objectives`/`comments` jsonb. `priority` check `urgent|high|medium|low`.
**`sessions`:** `task_id` (FK `on delete set null`, so denormalized `task_name` survives), `estimate_min`, `actual_sec`, `completed_at`.
**`cal_blocks`:** `task_id` (nullable since 009), `task_name`, `start_time` (`HH:MM` text), `duration_minutes`, `date` (date), `external_event_id`, `external_connection_id`, `kind` (task|placeholder|external, migration 006).
**`reason_logs`:** `task_id`, `reason`, `action` (pause|switch), `at`, `duration_sec` (006).
**`captures`** (002), **`tags`/`tags.color`** (010/011), **`life_areas`** (003/007), **`calendar_connections`** (encrypted credentials), **`collections`/`collection_members`/`collection_items`** (012/020/022/025/026).

### Sync-layer gotchas (port from `DbRowCodec.kt` into `UnstuckSync`, not Core — listed here so they aren't lost)
1. **JSONB blobs keep camelCase.** Apply snake_case **only** to top-level columns (per-property `CodingKeys`), never globally — `recurrence.daysOfWeek`, `objectives[].minutes`, etc. must stay camelCase. **Do NOT set a global `keyEncodingStrategy = .convertToSnakeCase`** on the encoder or you'll corrupt JSONB keys.
2. **explicit-null vs omission (kotlinx default-omission analog).** Encode nullable optionals as explicit JSON `null` so an upsert **clears** a removed field (Swift `JSONEncoder` already emits `null` for `Optional.none` *only if you encode it explicitly* — by default Swift **omits** nil optionals; you must override `encode(to:)` per row DTO to `encodeNil` for the clearable columns, mirroring Kotlin's `explicitNulls=true`). **The one exception:** `reason_logs.duration_sec` is **omitted when nil** (never clobber the server value) — strip it post-encode.
3. **Defaults on encode** (`encodeDefaults` analog): `tags ?? []`, `move_count ?? 0`, `later ?? false`, `kind ?? .task`.
4. **FK columns use `uuidOrNull`** — drop non-UUID `task_id`/`session_id`/`external_connection_id` to null.
5. **`user_id` is injected by the gateway**, never in the row DTO.
6. **Collection client-only fields** (`ownerId/members/myRole/archived`-derived) never round-trip into an upsert payload; `ownerId` is read off the raw row's `user_id`.

---

## 15. iOS platform translation cheatsheet

| Android / Kotlin | iOS / Swift | Note for this module |
|---|---|---|
| Pure `:core` logic | `UnstuckCore` Swift package | Foundation only |
| `kotlinx.serialization` `@Serializable` | `Codable` + `CodingKeys` | custom `Codable` for `Recurrence` union |
| `kotlinx` `explicitNulls`/`encodeDefaults` | manual `encode(to:)` overrides | needed at Sync boundary, not Core |
| `java.time` (`Instant`/`ZoneId.systemDefault()`) | `Date` + `Calendar.current`/`TimeZone.current` | inject `Calendar` for tests |
| `System.currentTimeMillis()` | `Int64(Date().timeIntervalSince1970*1000)` | only in `Clock.todayIso()`/`slipping` default |
| JUnit `*Test.kt` | XCTest, run `TZ=UTC` | **the behavioral spec** |
| Compose (UI) | SwiftUI | not in this module |
| Room | GRDB or a Codable JSON store | `UnstuckData`, consumes Core |
| WorkManager | BGTaskScheduler | sync scheduling (Sync layer) |
| AlarmManager **exact alarms** | `UNUserNotificationCenter` (`UNTimeIntervalNotificationTrigger`) | iOS has no exact-alarm permission gate; but **no foreground service** — see §9 |
| Glance widget | WidgetKit | reads same Core models |
| FCM | APNs | push (Sync layer) |
| Foreground service (focus timer) | **no equivalent** | reconstruct elapsed from `sessionStart` on foreground; schedule overrun via local notification |
| supabase-kt | supabase-swift | Sync layer |

**Exact-alarm / foreground-service denial gotcha (carry into App layer):** Android requests exact-alarm permission and runs a foreground service to keep the focus timer alive and fire the 80%/overrun nudges on time. iOS grants none of this. The pure `FocusTimer` is unaffected (it's `now`-injected), but the App must: persist `LiveSession` (incl. `sessionStart`, `pausedAt`, `nudge80Fired`, `overrunPromptFired`); on every foreground/launch recompute state via `deriveState`/`elapsedSec`; and pre-schedule `UNUserNotificationCenter` notifications for the 80%-nudge and overrun moments computed from `sessionStart + estimateSec`, cancelling/rescheduling on pause/resume/extend. Treat missed-while-backgrounded as "recompute on return," never "lost tick."

---

## 16. Acceptance criteria

1. `UnstuckCore` compiles with only `Foundation`; no UI / Data / Sync imports.
2. Every JUnit case across the 11 `*Test.kt` files is ported to XCTest and **passes under `TZ=UTC`**. The fixture helpers (`mkTask`, `mkBlock`, `sess`, `cap`, `iso`, `localMillis`, `localIso`, `todayPlus`, `NOW = 2026-05-21T12:00:00.000Z`) are ported as-is.
3. `Recurrence` JSON round-trips match the web JSONB shape byte-for-byte (`until` omitted when nil on encode, accepted as null on decode, unknown `kind` throws).
4. `parseMillis` honors all four parse tiers (UTC-Z, offset, offset-less-local, bare-date) and returns `nil` only on total failure.
5. ISO string comparisons use lexicographic `<` (no date parsing) wherever Kotlin does (`date > today`, `createdAt.compareTo`).
6. Sort stability holds in `pickStartNext`/`pickUpNext` (carry original index as final tiebreaker).
7. `formatMMSS`, `formatTime`, `blockTimeRange`, `recurrenceLabel` produce the exact strings the tests assert (including `·` U+00B7 and `–` U+2013).