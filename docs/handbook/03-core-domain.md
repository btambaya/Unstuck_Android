## Domain Model & Core Logic (`:core`)

The `:core` module is the brain of the Android app and the place a new engineer should read first. It is a **pure Kotlin/JVM library** — no Android SDK, no Compose, no Supabase, no coroutines. Its `build.gradle.kts` declares only `kotlinx-serialization-json` plus JUnit:

```
:core
├── model/   Models.kt, Enums.kt        ← the domain types (data classes + enums)
├── logic/   FocusTimer, VisibleTasks, PickStartNext, FreeSlots,
│            Recurrence, Analytics, TaskBucket, CalBlockKind,
│            TaskMutations, GoogleSyncMapping, AuthErrors, Uuid
└── time/    Time.kt                     ← Time + Clock (JS-Date semantics)
```

Everything here is a **port of the web app's `lib/*` TypeScript** (and a sibling of the iOS `UnstuckCore`). The package root is `tech.csalliance.unstuck.core`. Because there's no framework dependency, the whole module runs headless via `./gradlew :core:test`, and the tests are configured to run in UTC (`systemProperty("user.timezone", "UTC")` in `core/build.gradle.kts`) so date logic is deterministic and matches web CI + iOS.

### Why this module exists (the design principle)

The product has three clients — web (Next.js), iOS (SwiftUI), Android (Compose) — that must agree byte-for-byte on questions like "which tasks show in Today?", "what should I work on next?", "is this session in overrun?". Rather than re-derive that per platform, the *rules* are extracted into pure functions that take their inputs explicitly (including the current time as an injected `now: Long`) and return values. The Compose/ViewModel layer (in other modules) owns side effects: ticking clocks, persistence, Supabase writes, sound, notifications. `:core` owns the math.

> **Gotcha:** because parity is the whole point, the file headers constantly cite their web source (`Port of lib/...`). If you change behavior in `:core` you are almost certainly creating a cross-platform divergence. Confirm whether the web `lib/` and iOS `UnstuckCore` need the same change.

### The domain models (`model/Models.kt`)

All models are camelCase `@Serializable` data classes. The snake_case ↔ camelCase PostgREST mapping does **not** live here — it lives in `:data`'s `DbRowCodec`. The exception is **embedded JSONB types** (`Objective`, `Comment`, `CollectionItem`, `Recurrence`): their camelCase keys *are* the server JSONB shape, so they serialize directly.

**`TaskItem`** — the central entity. Fields and meaning:

| field | type | meaning |
|---|---|---|
| `id` | `String` | UUID (see `Uuid.kt`) |
| `name` | `String` | task title |
| `estimateMin` | `Int` | planned minutes; also the default duration of materialized cal blocks |
| `totalFocused` | `Int = 0` | accumulated focused seconds across sessions (drives focus resume) |
| `done` | `Boolean = false` | completion flag |
| `priority` | `Priority?` | urgent/high/medium/low; `null` ranks as LOW (see `PickStartNext`) |
| `tags` | `List<String>?` | free-text tags (tag filter is case-insensitive) |
| `objectives` | `List<Objective>?` | checklist sub-items (JSONB) |
| `comments` | `List<Comment>?` | notes (JSONB) |
| `intentWhen` / `intentThen` | `String?` | implementation-intention ("when X, then Y") |
| `lifeArea` | `String?` | area name (matches a `LifeArea.name`); `null` = unassigned |
| `firstPhysicalAction` | `String?` | the concrete first step prompt |
| `moveCount` | `Int?` | reschedule counter — feeds the slip detector |
| `completedAt` | `String?` | ISO timestamp, stamped on first done-flip |
| `later` | `Boolean?` | "saved for later" — excluded from Today/Backlog/StartNext |
| `recurrence` | `Recurrence?` | `null` = does not repeat |
| `createdAt` / `updatedAt` | `String` | ISO timestamps (lexicographically sortable) |

**`CalBlock`** — a scheduled block on the calendar (the unit of the day plan). `taskId` is nullable; `startTime` is `"HH:MM"`, `date` is `"YYYY-MM-DD"`, `durationMinutes` an `Int`. `externalEventId` / `externalConnectionId` link to a synced Google event. `kind` (`CalBlockKind?`) is the *stored* classification — but note it's nullable, which is why `CalBlockKind.kt` exists to derive it (below).

**`Session`** — a *completed* focus session (the historical record analytics read). `actualSec` is the seconds actually focused **in that session** (not including prior accumulated time). `taskId` is nullable (the task may have been deleted); `taskName`, `tags`, `estimateMin` are snapshotted so the row is self-describing.

**`LiveSession`** — the *device-local, in-flight* focus session (mirrors the web `unstuck-session`). This is the state the `FocusTimer` machine operates on:
- `sessionStart: Long?` — epoch ms; `null` means idle.
- `paused` + `pausedAt: Long?` — pause bookkeeping.
- `sessionEstimateMin` — current target minutes (mutated by `extend`).
- `nudge80Fired` / `overrunPromptFired` — one-shot notification gates (UI flips them).
- `treatment: FocusTreatment` — ambient/cockpit/monk visual mode.
- `priorAccumulatedSec: Int?` — seconds already focused on this task before this session (the resume seed).

**`Capture`** — a quick note logged during focus, classified by `CaptureTag`. Linked to `taskId`/`sessionId` (both nullable). `at` is an ISO timestamp.

**`ReasonLog`** — why a session was paused or switched (`ReasonAction`). `durationSec` is how long the pause lasted; feeds `pauseAnatomy`.

**`CalendarConnection`** — a linked external calendar account: `provider` (`CalendarProvider`), `accountEmail`, `selectedCalendarIds`, `colorSlot`, `lastSyncCursor` (incremental-sync token), `connectedAt`. **`ExternalEvent`** is the raw pulled event (`connectionId`, `calendarId`, `summary`, `start`/`end` ISO) before it's mapped to a `CalBlock`.

**`ItemCollection`** + **`CollectionItem`** — generic named lists (e.g. shopping/reading lists). A collection has `name`, `color`, optional `subtitle`, `sortOrder`, and an embedded `items: List<CollectionItem>` (each with `body`, `pinned?`, `done?`, `at`).

**`TagRow`** (`id`, `name`, `color?`, `sortOrder`) and **`LifeArea`** (`id`, `name`, `color`, `sortOrder`) are the user's configurable taxonomies. Note tasks reference a life area by its **name string** (`TaskItem.lifeArea`), not by `LifeArea.id`.

**`Objective`** (`text`, `done?`, `minutes?`), **`Comment`** (`text`, `at?`).

#### Recurrence is a hand-written serialized sealed class

`Recurrence` is a `sealed class` with `Daily`, `Weekly(daysOfWeek: List<Int>, …)`, `Monthly`, all sharing `until: String?` (inclusive `YYYY-MM-DD`, `null` = forever). It uses a **custom `RecurrenceSerializer`** because the server JSONB is a tagged union `{kind, daysOfWeek?, until?}`, not Kotlin's default polymorphic envelope. `daysOfWeek` uses the JS convention **0=Sun…6=Sat**.

> **Gotcha:** the serializer requires a `JsonEncoder`/`JsonDecoder` and throws `SerializationException("Recurrence needs JSON")` otherwise — you cannot use it with a non-JSON format. Unknown `kind` values throw. `CoreModelsTest` round-trips all three variants and asserts the exact web JSON shape (`{"kind":"weekly","daysOfWeek":[0,6],"until":null}`) decodes correctly.

#### Templates + per-day occurrences (reworked 2026-06, migration 033)

A task with `recurrence` set is now a hidden **template** — shown only under the `TaskListView.RECURRING` tab, never in All/Today/Upcoming. Its occurrences are still materialised forward as `CalBlock`s (one per date), but each block now carries its own `done` / `skipped` / `completedAt` (the per-occurrence state). `logic/Occurrences.kt` projects those occurrence blocks into synthetic one-day `TaskItem` rows (`projectOccurrences`, `id = block id`) that `VisibleTasks` composes into Today/All/Upcoming, so each occurrence is an independent completable/skippable task. Helpers: `isTemplate(t) = t.recurrence != null`, `occurrenceBlockFor(rowId, …)` (resolve an occurrence row back to its block), `taskForBlock(block, …)` (what to open on a calendar-block tap). **Routing rule:** complete/skip/focus an occurrence writes the `cal_block`, never the template; never `upsertTask` a row whose id is a block id (would mint a phantom occurrence-as-task — `finishFocus` guards this). Surfaces aggregating "open tasks" (`pickStartNext`, the day-grid schedule tray, area counts, slip nudges) must exclude templates. Tests: `OccurrencesTest` + the `RECURRING` cases in `VisibleTasksTest`.

### The enums (`model/Enums.kt`)

Each enum constant carries a `@SerialName` matching the **string stored server-side** (e.g. `URGENT` ↔ `"urgent"`, `FOLLOW_UP` ↔ `"follow-up"`). Don't rename the serial names — that breaks the wire format.

- `Priority`: urgent/high/medium/low.
- `FocusState`: idle/starting/running/overrun/pause/done/resume. *Note:* `FocusTimer.deriveState` only ever returns IDLE/PAUSE/RUNNING/OVERRUN — `STARTING`, `DONE`, `RESUME` are transient display states the UI layer forces, not engine outputs.
- `FocusTreatment`: ambient/cockpit/monk.
- `CalBlockKind`: task/placeholder/external.
- `ReasonAction`: pause/switch. `CaptureTag`: follow-up/idea/edit/question/distraction.
- `CalendarProvider`: google/apple/microsoft.
- `ThemePref`, `Density`: user prefs.
- **`TaskListView`** (`ALL`, `BACKLOG`, `TODAY`, `UPCOMING`, `LATER`, `COMPLETED`) — *not* serializable; it's a UI concept with a human `label`. It's the input that selects the `visibleTasks` bucket.

### `time/Time.kt` — JS-Date semantics on the JVM

This is the most important file to internalize, because every date bug traces back to it. The web logic relies on JS `Date`: timestamps are ISO strings, **date math is local** (`ZoneId.systemDefault()`), and ISO strings compare lexicographically. `Time` reproduces that:

- `Time.parseMillis(iso): Long?` — tries `Instant.parse`, falls back to `OffsetDateTime.parse`, returns `null` on failure (mirrors `Date.parse → NaN`).
- `Time.startOfDayMillis(now)` — local midnight epoch ms.
- `Time.civil(y, m, d)` — local midnight for a civil date (month is 1-based).
- `Time.addDaysMillis`, `dayOfWeekJs` (**0=Sun…6=Sat**, JS `getDay`), `dayOfMonth`, `hourOf`, `minuteOf`.
- `Time.wholeDaysBetween(a, b)` — `Math.round((midnightA − midnightB)/DAY_MS)`, matching the web exactly.
- `Clock.todayIso()` / `Clock.dateIso(epochMs)` — the local `YYYY-MM-DD` string.

> **Gotcha:** `Clock.todayIso()` reads the real wall clock (`System.currentTimeMillis()`), so it is *not* injected. `visibleTasks` calls it directly even though it also takes a `now` parameter. In production both agree; in tests the fixtures pin `now` and arrange dates relative to `Clock.todayIso()` (see `Fixtures.todayPlus`). Be aware of this split if you write a test that fakes `now` far from the system date.

### `FocusTimer` — the focus state machine (`logic/FocusTimer.kt`)

`FocusTimer` is an `object` of pure functions over a `LiveSession` + injected `now: Long`. The Compose layer owns the ticking clock and persistence; the engine is deterministic.

**Derivations:**
- `estimateSec(live)` = `sessionEstimateMin * 60` (with a 25-min fallback if 0).
- `elapsedSec(live, now)` = seconds of *this* session only. While paused, it freezes at `pausedAt − sessionStart`; otherwise `now − sessionStart`. Clamped at 0. This is what's written to `Session.actualSec`.
- `displayedElapsedSec(live, now)` = `elapsedSec + priorAccumulatedSec` — what the UI shows (so a resumed task continues counting from where it left off).
- `deriveState(live, now, overrunGraceSec)`:
  ```
  sessionStart == null            → IDLE
  paused                          → PAUSE
  graceSec == +Infinity           → RUNNING        ("Never" overrun)
  displayedElapsed ≥ estimate+grace → OVERRUN
  else                            → RUNNING
  ```
  Note overrun is judged on **displayed** (incl. prior), not raw elapsed — `FocusTimerTest.overrunFiresOnDisplayedNotRawElapsed` locks this in.
- `overrunGraceSeconds(pref)` maps the settings string: `null`/empty → `1.0`s, `"Never"` → `+∞`, `"5 min"` → 300, `"10 min"` → 600, anything else → 1.0.

**Transitions (each returns the next `LiveSession`):**
- **`start(cur, taskId, estimateMin?, priorAccumulatedSec?, now, newId)`** — *resume-aware*, this is the subtle one:
  - same task **and** paused → `resume(cur, now)` (continue, don't reset).
  - same task **and** running → return `cur` unchanged (a double-Start is a no-op).
  - otherwise → a fresh session with a new id, `sessionStart = now`, and the provided `priorAccumulatedSec` (defaults 0).
- `pause(cur, now)` — sets `paused`, stamps `pausedAt` (no-op if idle).
- `resume(cur, now)` — clears pause and **shifts `sessionStart` forward by the pause gap** (`now − pausedAt`), so elapsed survives the pause.
- `done(cur)` — clears `id`/`sessionStart`/pause (elapsed → 0). The `Session` writeback already happened with the pre-done elapsed; the UI separately forces a transient `done` display state.
- `cancel(cur)` — back to `empty`, preserving only the chosen `treatment`.
- `extend(cur, minutes)` — adds to `sessionEstimateMin` and clears `overrunPromptFired` (so the prompt can re-fire).
- `setTreatment(cur, t)`.

`formatMMSS(sec)` renders `MM:SS` (or `-MM:SS`) for the timer display.

> **Pitfall:** the resume-aware `start` is exactly the "Start on a saved-for-later task continues, doesn't reset" behavior from a recent commit. If you ever simplify `start` to always create a fresh session, you'll silently reset elapsed time on the second tap. Tests `startOnSamePausedTaskResumesWithoutResettingElapsed`, `startOnSameRunningTaskIsNoOp`, and `startOnSamePausedTaskIgnoresPrior` (resume must ignore an incoming `priorAccumulatedSec`) guard this.

### `VisibleTasks` + `TaskListView` (`logic/VisibleTasks.kt`)

`visibleTasks(view, tasks, blocks, now, activeArea, activeTag?, slipMode)` is the single source of truth for the task lists. Pipeline:

1. Precompute task-id sets from **task-shaped** cal blocks (via `isTaskBlock`): `todayTaskIds`, `upcomingTaskIds` (date > today), `scheduledTaskIds`, and `pastOnlyTaskIds` (scheduled only in the past → "overdue", routed to Backlog).
2. Pick the bucket by `view`:
   - **TODAY** — not done, not `later`, and (scheduled today **or** created today and not scheduled for a future day).
   - **BACKLOG** — open, not `later`, not created today, and (never scheduled **or** only-in-past).
   - **UPCOMING** — open, scheduled in the future, not also today.
   - **LATER** — open and `later == true`.
   - **COMPLETED** — `done`.
   - **ALL** — open, plus tasks completed *today* (so they linger one day).
3. **Area filter** applies to every view *except* Today (Today is intentionally area-agnostic — `matchesArea` via the `UNASSIGNED_AREA = "Unassigned"` sentinel).
4. **Tag filter** applies to *every* view including Today (case-insensitive `matchesTag`).
5. **Slip filter** (if `slipMode`) keeps only `isSlipping` tasks.
6. Finally re-orders: **open tasks first, then completed**, preserving order within each group (hand-partitioned to mirror iOS).

Helpers: `isSlipping(task, now)` (done → false; `moveCount ≥ 3` → true; else age ≥ 21 days), `daysSinceCreated`. The slip thresholds are `SLIP_AGE_MS` (21 days) and `SLIP_MOVE_THRESHOLD` (3).

> **Gotcha:** the area-agnostic Today behavior is deliberate and documented in the file header — don't "fix" it to honor the area filter. `VisibleTasksTest` (24 tests) covers the bucket boundaries, the sentinel, and the open-before-completed ordering.

### `PickStartNext` (`logic/PickStartNext.kt`)

`pickStartNext(tasks, blocks, liveTaskId, areaFilter?)` answers "what should I work on next?" and powers Start Next, Up Next, the `/tasks` NEXT badge, and the focus-mode UP NEXT panel — so every surface agrees. It excludes done/`later`/currently-focused tasks, honors the area filter, and ranks with a **stable** comparator: **priority desc → `estimateMin` asc → `createdAt` asc** (ISO strings sort chronologically, matching the web `localeCompare` tiebreak). `null` priority ranks as LOW.

`pickUpNext(..., startNextId, limit=3)` returns the next N, skipping both the live task and the already-shown start-next pick.

> **Note:** `blocks` is in the signature for parity but unused here — don't be surprised it isn't referenced.

### `FreeSlots` (`logic/FreeSlots.kt`)

Pure scheduling helpers; dates flow as local-midnight epoch ms.
- `findFreeSlots(blocks, durationMin, now, startDate?, daysToScan=4, dayStartMin=8h, dayEndMin=18h, limit=9)` scans upcoming days for windows ≥ `durationMin`. For today it starts at the later of `dayStartMin` and the next 15-min boundary `≥ now+5min`. The step between consecutive slots is `max(durationMin, 30)` (no closer than 30 min). **Every** block on a day is treated as a conflict (task/placeholder/external alike). Returns `Slot(date, label, startTime)` with labels like `"Tomorrow · 9:00 AM"`.
- `findFreeSlotsForDate(blocks, durationMin, isoDate, now, …)` — one specific day (delegates to `findFreeSlots` with `daysToScan=1`).
- `findConflicts(date, startTime, durationMin, blocks, excludeBlockId?)` — overlapping blocks on a date, sorted by start; `excludeBlockId` skips the block being edited.
- Formatters: `formatTime("14:30") → "2:30 PM"`, `blockTimeRange(b) → "9:00 AM–10:00 AM"`.

### `Recurrence` logic (`logic/Recurrence.kt`)

Materializes/regenerates cal blocks for repeating tasks. `RECURRENCE_HORIZON_DAYS = 56` (8 weeks ahead).
- `materializeOccurrences(recurrence, startDate, startTime, horizonDays)` walks day-by-day from `startDate`, emitting `MaterializedOccurrence(date, startTime)` where the day matches the rule (Daily = every day, Weekly = `dayOfWeekJs ∈ daysOfWeek`, Monthly = same `dayOfMonth` as start). Stops at `until` (inclusive).
- `regenerateForTask(task, recurrence, existingBlocks, todayIso, startTime, startDate, …) → RegenPlan(toUpsert, toDelete)`. **Past occurrences are preserved** (it only diffs `date > todayIso`). Clearing recurrence (`null`) deletes all future occurrences, keeps history. Keys are `"date|startTime"`; missing desired ones become new `CalBlock`s with `kind = TASK` and a fresh `newUuid()`.
- `recurrenceLabel(r)` → human chips ("Repeats weekdays", "Repeats Mon/Wed/Fri until Sep 1, 2026"). Special-cases weekdays (1–5), weekends (0,6), and 7-day weekly → "daily".

> **Pitfall:** the diff is keyed on `date|startTime`. If you change a recurring task's *time*, every future occurrence at the old time is deleted and recreated at the new time — that's intended, but it means stable block ids are *not* preserved across a time edit.

### `Analytics` (`logic/Analytics.kt`)

Pure derivations over the live collections; the Compose Report/DeepDive charts consume these. `REAL_DATA_THRESHOLD = 5` — below this many sessions, charts show demo data. Weekday index is **Monday-anchored** (`dayOfWeekIdx`: Mon=0…Sun=6). The charts (H1–H7):
- `weekdayAreaHours` — stacked focus hours by weekday × life area (unassigned tasks drop out, never coerced to "Work").
- `calibrationDots` / `calibrationHitRate` — estimate-vs-actual scatter; hit = within `slackMin` (default 5).
- `interruptionBins` — captures binned by minutes into their session.
- `timeOfDayHeatmap` — 5 weekdays × 6 two-hour buckets from 7am.
- `pauseAnatomy` — minutes/count per pause reason, top 6 (from `ReasonLog`).
- `reEntryDistribution` — gaps between consecutive sessions on the same task.
- `slipping` → `SlipRow` and `captureBreakdown` → `Map<CaptureTag,Int>`.
- `topInsights` — the "WORTH NOTICING" cards (best weekday, calibration phrase, top slipping task), max 3.

`DEFAULT_AREAS = ["Work","Personal","Home","Health","Volunteering"]`.

### Remaining helpers

- **`CalBlockKind.kt`** — `blockKind(b)` derives the kind even when `CalBlock.kind` is null: stored kind wins; else `externalEventId` ⇒ EXTERNAL; else `taskId == "placeholder"` ⇒ PLACEHOLDER; `taskId` starting `"cal-"` ⇒ EXTERNAL; else TASK. `isTaskBlock(b)` = TASK kind **and** non-empty `taskId` (so a placeholder/external with a null task never counts as a task block). This is the predicate `visibleTasks`/`regenerateForTask` use to ignore external/placeholder blocks.
- **`Uuid.kt`** — `newUuid()` (lowercase v4), `isUuid(s)` (regex), `uuidOrNull(s)` (FK columns drop to `null` for non-UUIDs — mirrors web `uuidOrNull`).
- **`TaskBucket.kt`** — `isCompletedToday`/`isCreatedToday` (within today's local-midnight window). Used by `visibleTasks`.
- **`TaskMutations.kt`** — pure mutation rules extracted from the web `use-tasks.ts`: `stampCompletion` (first done-flip captures `completedAt`, preserves the original on re-toggle, **clears** it on un-complete), `applyCompletion`, `bumpMoveCount` (feeds the slip detector). All take `nowISO` for determinism.
- **`GoogleSyncMapping.kt`** — pure value transforms between Google events and `CalBlock` (the orchestration lives in `:sync`). `externalEventToBlock` ids the block `"g_<eventId>"` so re-pulls overwrite the same row; `blockToIsoRange` converts date+`HH:MM` to UTC ISO (with `.SSSZ` to match JS `toISOString()`); `diffMinutes` floors at 15 min so zero-length events stay visible.
- **`AuthErrors.kt`** — `humanizeAuthError` (maps Supabase error codes/messages to friendly copy), `nextSafePath` (open-redirect guard: only same-origin `/…`, rejects `//`), `detectSignupAlreadyExists` (anti-enumeration tells).

### Unit-test coverage (`core/src/test`)

The module is thoroughly tested — **165 `@Test` methods** across 11 files, all ported 1:1 from the web `*.test.ts` and iOS `*Tests.swift`:

| test file | @Tests | covers |
|---|---:|---|
| `VisibleTasksTest` | 24 | bucket boundaries, area/tag/slip filters, ordering |
| `AuthErrorsTest` | 24 | error mapping table, redirect guard, signup detection |
| `FocusTimerTest` | 19 | full state machine + resume/overrun/prior edge cases |
| `RecurrenceTest` | 18 | materialize/regenerate, labels, until-boundary |
| `CoreModelsTest` | 17 | bucket helpers, block-kind, Recurrence JSON round-trips, UUID |
| `AnalyticsTest` | 16 | all H1–H7 charts + insights |
| `TaskMutationsTest` | 12 | completion stamping, move-count bump |
| `FreeSlotsTest` | 11 | slot finding, conflicts, formatting |
| `GoogleSyncMappingTest` | 8 | event↔block transforms |
| `PickStartNextTest` | 8 | ranking + filters |

Shared factories live in `Fixtures.kt` — `mkTask`, `mkBlock`, `sess`, `cap`, `iso(ms)`, `localIso`, `todayPlus(n)`, and a pinned `NOW` (`2026-05-21T12:00:00Z`). These mirror the `task()`/`block()` helpers in `lib/visible-tasks.test.ts`, so a web test and its Kotlin port read line-for-line.

> **How to add a new pure rule (worked example):** suppose you want a "stale capture" detector. (1) Add `fun isStaleCapture(c: Capture, now: Long): Boolean` to a logic file (or new file) under `logic/`, taking `now` explicitly and using `Time.parseMillis(c.at)`. (2) Keep it pure — no `System.currentTimeMillis()` inside, no I/O. (3) Add a test using `Fixtures.cap(...)` and the pinned `NOW`. (4) **Check parity** — if the web has the same concept, mirror its threshold/behavior exactly (and update iOS `UnstuckCore`). (5) Consumers (ViewModels in other modules) call it; the side effects stay out of `:core`.

### Files referenced
- Models/enums: `/Users/ahmadtambaya/Desktop/projects/unstuck_android/core/src/main/kotlin/tech/csalliance/unstuck/core/model/Models.kt`, `.../model/Enums.kt`
- Logic: `.../logic/FocusTimer.kt`, `VisibleTasks.kt`, `PickStartNext.kt`, `FreeSlots.kt`, `Recurrence.kt`, `Analytics.kt`, `TaskBucket.kt`, `CalBlockKind.kt`, `TaskMutations.kt`, `GoogleSyncMapping.kt`, `AuthErrors.kt`, `Uuid.kt`
- Time: `.../time/Time.kt`
- Tests + fixtures: `/Users/ahmadtambaya/Desktop/projects/unstuck_android/core/src/test/kotlin/tech/csalliance/unstuck/core/` (`Fixtures.kt` + the 11 `*Test.kt` files)
- Build: `/Users/ahmadtambaya/Desktop/projects/unstuck_android/core/build.gradle.kts`
