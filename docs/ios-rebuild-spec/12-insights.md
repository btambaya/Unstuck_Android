# iOS Rebuild Spec — Insights / Analytics

**Area:** Insights (Reflection) screen — Report view + Deep dive view, with charts and a derived-insight engine.
**Android reference files:**
- `app/src/main/kotlin/tech/csalliance/unstuck/ui/insights/InsightsScreen.kt` (the screen + all chart composables)
- `core/src/main/kotlin/tech/csalliance/unstuck/core/logic/Analytics.kt` (pure derivation functions — the source of truth)
- `core/src/test/kotlin/tech/csalliance/unstuck/core/AnalyticsTest.kt` (locked behavior; port these tests verbatim to XCTest)
- `core/src/main/kotlin/tech/csalliance/unstuck/core/time/Time.kt` (date semantics)
- `core/src/main/kotlin/tech/csalliance/unstuck/core/model/Models.kt` + `Enums.kt` (data models)

**Lineage note:** Analytics.kt is itself a 1:1 port of the web's `lib/analytics.ts`, and the test file header says it was ported from `AnalyticsTests.swift`. So a prior iOS implementation of this exact logic existed. The pure functions are platform-agnostic math over the live collections — port them mechanically and the charts fall out. **Do not "improve" any formula.** Every off-looking line below has a code comment explaining a past bug it fixes; reproducing the quirk is the requirement.

---

## 1. What it does — behavior, screens, states, flows, edge cases

The Insights screen is a **read-only reflection surface**. It performs no writes, no network calls of its own, and no navigation beyond Back. It reads five live collections from the app's reactive store and renders derived charts. Its emotional design intent is "observations, not a score" — numbers stay hidden and gentle until enough data exists.

### 1.1 Top-level layout (always present)

A vertical scroll (`LazyColumn` → SwiftUI `ScrollView` + `LazyVStack`) with 18pt horizontal padding, on background `colors.bg`. Above it, an `AppBar` with a BACK leading button (calls `onBack`) and **no** search field.

Header block (first scroll item):
1. **Eyebrow** `SectionLabel`: `"REFLECTION · {RANGE_LABEL}"` in `colors.primaryDeep`. `RANGE_LABEL` = the range uppercased, except `"ALL"` renders as `"ALL TIME"`. So: `"REFLECTION · WEEK"`, `"REFLECTION · MONTH"`, `"REFLECTION · ALL TIME"`.
2. **Title** in serif-italic 28pt, `colors.ink`:
   - Report view: `"Observations, not a score."`
   - Deep dive view: `"Let's look closer. Calmly."`
3. **View segmented control** (`MdSegment`): options `["Report", "Deep dive"]`. Selected = `"Deep dive"` when `deep == true` else `"Report"`. Selecting fires `onToggleDeep(selected == "Deep dive")` — i.e., the `deep` flag is **owned by the parent/navigation**, not local state. The iOS screen takes `deep: Bool` and an `onToggleDeep: (Bool) -> Void` closure (or, in MVVM, a `@Published var deep` on a coordinator). Match the Android contract: this toggle is hoisted.
4. **Range segmented control** (`MdSegment`): options `["Week", "Month", "All"]`. This **is** local state (`@State var range`, default `"Week"`).

### 1.2 Time window selection (range → cutoff)

The selected range computes an epoch-millisecond `cutoff`; all five collections are filtered to events `>= cutoff`. Windows are **calendar-anchored** (web parity), not rolling:

- **"Week"** → since **this week's Monday at local 00:00**. Computed as: `today.minusDays((today.dayOfWeek.value + 6) % 7)` at start of day in the system zone. (Monday-anchored; on Monday the offset is 0.)
- **"Month"** → since **the 1st of the current month** at local 00:00.
- **"All"** → `cutoff = 0` (everything).

The filter predicate `inWin(iso)` parses each row's ISO timestamp to millis (null → `0L`, which is `< cutoff` for Week/Month so it drops; for All it's `>= 0` so it stays) and keeps rows `>= cutoff`. Filtered fields per collection:
- `sessions` filtered by `session.completedAt`
- `captures` filtered by `capture.at`
- `reasons` filtered by `reasonLog.at`
- `tasks` and `lifeAreas` are **NOT** windowed — used whole (slip detection and area legends need full history / current config).

### 1.3 The data-sufficiency gate (`enough`)

`enough = (windowed sessions count) >= 5` (constant `REAL_DATA_THRESHOLD = 5`). This single boolean controls the entire UI:

- When `!enough`: a `ThresholdNote` card shows first ("Patterns appear after a few sessions." + `"{n} of 5 focus sessions so far — numbers stay gentle until then."`), and every headline stat renders **`"—"` instead of a number**. Charts that depend on real data are **omitted entirely** in Report view; in Deep dive the stat values dash out but the structural sections still appear (guarded individually by their own emptiness checks).
- When `enough`: real numbers + all charts.

Edge: `ThresholdNote` always shows the literal count even if 0 ("0 of 5...").

### 1.4 Report view (`deep == false`)

Renders, in order:
1. `ThresholdNote` (only if `!enough`).
2. **Three stacked StatCards** (always shown; values dash when `!enough`):
   - **"Estimates"** — value `"{hit}%"`, badge `"{sessionCount} sessions"`, caption `"landed within 5 min"`, green badge colors (`greenSoft`/`greenInk`). `hit` = calibration hit-rate percent (see §3).
   - **"Focus sessions"** — value `"{sessionCount}"`, badge `"{captureCount} captures"`, caption `"completed this window"`, blue badge colors. (Note the comment in code: this used to be mislabeled "Re-entries"; the headline is just the session count.)
   - **"Gentle friction"** — value `"{slipCount} tasks"`, badge `"All clear."` if no slips else `"Watch these"`, caption `"slipping"`. Badge colors green when empty, amber when non-empty. This stat is **not** dashed by `enough` — it always shows the real slip count (slip detection works at any data volume).
3. **If `enough` only:**
   - **`StackedBars` "When focus happens"** — weekday × life-area stacked horizontal bars (see §1.6).
   - **`Histogram` "When interruptions happen"** — interruption bins, coral.
   - **"Worth noticing" insight cards** — up to 4 cards from `topInsights(...)` (titles + subs). Section omitted if the list is empty. (Engine returns ≤3, screen takes 4 — effectively ≤3.)
4. Bottom spacer (24pt).

### 1.5 Deep dive view (`deep == true`)

Renders, in order:
1. `ThresholdNote` (only if `!enough`).
2. **2×2 grid of small StatCards** (values dash when `!enough`):
   - **"Median"** `"{median}m"`, caption `"across {n} sessions"` — median session length in minutes.
   - **"On estimate"** `"{hit}%"`, caption `"within 5 min"`.
   - **"Re-entry <5m"** `"{reentryPct}%"`, caption `"fast comebacks"` — share of re-entry gaps falling in bin 0 (the `<5min` bucket).
   - **"Captures"** `"{captureCount}"`, caption `"kept this window"`.
3. **"What pauses you"** — labeled horizontal bars from `pauseAnatomy(reasons)`. Section omitted if no reason logs. Each bar: label = reason, fill fraction = `minutes / max(minutes)`, value text = `"{roundedMinutes}m · {count}"`, coral.
4. **`Histogram` "How fast you come back"** — re-entry distribution bins, primary color.
5. **"Captures by kind"** — labeled bars from `captureBreakdown(captures)`. Section omitted **if `captures` is empty** (the breakdown map always has 5 fixed keys, so guard on the captures list, not the map, to avoid 5 zero bars). Label = tag name lowercased with `_`→`-` (e.g. `FOLLOW_UP` → `"follow-up"`). Fill = `count / max(count, 1)`.
6. **"The slip detector"** — up to 8 slip rows, shown only if non-empty. Each row: task name (left, weighted) + `"{moveCount}× · {weeks}w"` (right, mono).
7. **`Heatmap`** "Hour × day" — always rendered (even if all-zero → all cells background color).
8. Bottom spacer.

### 1.6 Chart rendering details (port exactly)

- **StackedBars** (horizontal): one row per weekday `["Mon","Tue","Wed","Thu","Fri","Sat","Sun"]` (all 7 always rendered, even empty). `max` = the largest single-day total across all bars, coerced to `>= 0.001` (avoid div-by-zero). Each segment width = `(value / max)` clamped `[0,1]`; segments with `frac == 0` are skipped. Track height 14pt, 4pt corner radius, track bg `colors.bg2`. Segment color = `areaColorFor(areaName, lifeAreas, colors)` (see §3.6). Below the bars: a legend row of swatch (8×8, 2pt radius) + area name, one per area.
- **Histogram** (vertical bars): `max = max(bins, 1)`. Each bar height fraction = `(v / max)` clamped to `[0.02, 1]` (so non-zero-but-tiny still shows a sliver, and even a real value never fully disappears). Bar fill = the passed color if `v > 0` else `colors.bg2`. Container height 80pt, bars equal-width, 4pt spacing, aligned to bottom, top corners rounded 4pt.
- **LabeledBar** (pause anatomy / captures-by-kind): label left + value right (mono), then a pill track (height 8pt, fully rounded 999) with a fill of `frac` clamped `[0.02, 1]`.
- **Heatmap**: 5 rows (Mon–Fri) × 6 columns (2-hour buckets from 07:00). `max = max(flattened grid, 0.001)`. Cell color: if `value <= 0` → `colors.bg2`; else `lerp(colors.bg2, colors.green, 0.2 + 0.7 * (value/max))`. Each cell is a 1:1 aspect square, 6pt radius, weighted equally; left label column (30pt) shows `Mon…Fri`. SwiftUI: use `Color.interpolate`/manual RGB lerp or `Color(...).mix(...)`; replicate the `0.2 + 0.7*t` ramp so even the dimmest active cell is visibly tinted.

### 1.7 Edge cases to preserve

- **Empty everything:** Report with 0 sessions → ThresholdNote ("0 of 5"), three dashed/real stat cards (friction may still be 0 or non-zero from old tasks), no charts. No crash.
- **Sessions with `taskId == null`:** dropped from weekday/heatmap/calibration/re-entry (those `continue` on null taskId). They still **count** toward `sessions.size`, `totalMin`, and the median (those use the raw list).
- **Tasks with `lifeArea == null`:** never coerced to "Work" — they simply don't contribute to StackedBars.
- **A session's task referencing an area not in the user's current `areaNames` list:** `areas.indexOf(area) < 0` → that hour is dropped from the stacked bars (no error, just invisible).
- **`totalMin` is computed (`sum(actualSec)/60`) but is currently unused in the UI** — compute it if you want parity, but it renders nowhere. Don't surface it.

---

## 2. Data — models & Supabase tables/columns

The screen consumes five reactive collections off the app store/ViewModel: `sessions`, `tasks`, `lifeAreas`, `captures`, `reasonLogs`, plus a `nowMs()` clock. On iOS these are published streams from your local store (GRDB or JSON cache) hydrated from Supabase.

### 2.1 Domain models (from `core/model/Models.kt`)

```
Session(
  id: String, taskId: String? = nil, taskName: String,
  tags: [String]? = nil, estimateMin: Int? = nil,
  actualSec: Int, completedAt: String /* ISO */
)

TaskItem( /* relevant fields only */
  id, name, estimateMin: Int, done: Bool = false,
  lifeArea: String? = nil, moveCount: Int? = nil,
  completedAt: String? = nil, createdAt: String /* ISO, required */, ...
)

Capture(
  id, taskId: String? = nil, sessionId: String? = nil,
  tag: CaptureTag, body: String, at: String /* ISO */
)

ReasonLog(
  id, taskId: String? = nil, reason: String,
  action: ReasonAction, at: String /* ISO */, durationSec: Int? = nil
)

LifeArea(id, name: String, color: String, sortOrder: Int)
```

Enums (string-encoded, must match server strings exactly):
- `CaptureTag`: `follow-up`, `idea`, `edit`, `question`, `distraction`
- `ReasonAction`: `pause`, `switch`

### 2.2 Supabase tables (from `unstuck/supabase/migrations`)

All timestamps are `timestamptz` (server stores ISO-8601 with offset, typically `...Z`). All tables are RLS-scoped to `user_id = auth.uid()` and on the realtime publication.

**`sessions`** (001_initial.sql) — completed focus sessions:
| column | type | model field |
|---|---|---|
| `id` | uuid PK | `id` |
| `user_id` | uuid | (RLS only; not in model) |
| `task_id` | uuid, FK→tasks `on delete set null` | `taskId` |
| `task_name` | text not null (denormalized, survives task delete) | `taskName` |
| `tags` | text[] | `tags` |
| `estimate_min` | int (nullable) | `estimateMin` |
| `actual_sec` | int not null, `>= 0` | `actualSec` |
| `completed_at` | timestamptz not null | `completedAt` |

**`captures`** (002_captures.sql):
| `id` uuid PK · `user_id` · `task_id` uuid FK set-null · `session_id` uuid FK→sessions set-null · `tag` text check-in(5 values) · `body` text len 1–4096 · `at` timestamptz · `created_at` timestamptz |
→ model `id, taskId, sessionId, tag, body, at`.

**`reason_logs`** (001 + 006_*_duration.sql):
| `id` · `user_id` · `task_id` uuid (nullable, no FK) · `reason` text not null · `action` text check-in(`pause`,`switch`) · `at` timestamptz default now · `duration_sec` integer (nullable, `>= 0` — **added in migration 006 specifically so pauseAnatomy can show real minutes**) |
→ model `id, taskId, reason, action, at, durationSec`.

**`tasks`** (001 + later migrations): relevant analytics columns `id, name, estimate_min, done, life_area, created_at`, and `move_count` (reschedule counter), `completed_at`. `life_area` is a free-text name (not an FK to life_areas).

**`life_areas`** (003_user_prefs_struggles_lifeareas.sql): `id, name, color, sort_order` per user.

### 2.3 Column-name mapping (critical for the codec)

Android keeps **camelCase** in the model and maps to **snake_case** PostgREST columns in the `:data` layer's row codec (`actualSec`↔`actual_sec`, `completedAt`↔`completed_at`, `sessionId`↔`session_id`, `durationSec`↔`duration_sec`, `taskId`↔`task_id`, `lifeArea`↔`life_area`, `moveCount`↔`move_count`, `createdAt`↔`created_at`). On iOS with supabase-swift use a `keyEncodingStrategy = .convertToSnakeCase` / `.convertFromSnakeCase` on the `JSONDecoder`/`JSONEncoder`, **or** explicit `CodingKeys`. Verify each field round-trips — a silent mismatch here yields all-zero charts.

---

## 3. Business rules / pure logic (port `Analytics.kt` 1:1, lock with `AnalyticsTest.kt`)

These are pure functions. **Put them in a platform-agnostic Swift module (e.g. `UnstuckCore`) and port the XCTest cases verbatim.** `eps = 1e-9` for double comparisons. Constants: `REAL_DATA_THRESHOLD = 5`, `HOUR = 3600.0`.

### 3.1 `dayOfWeekIdx(epochMs) -> Int` — Monday-anchored
`(dayOfWeekJs(epochMs) + 6) % 7` where `dayOfWeekJs` is JS convention (0=Sun…6=Sat). Result: Mon=0…Sun=6.
**Test:** 2026-05-18(Mon)→0, 05-19(Tue)→1, 05-24(Sun)→6.

### 3.2 `weekdayAreaHours(sessions, tasks, areas=DEFAULT_AREAS) -> [StackedBar]`
- `DEFAULT_AREAS = ["Work","Personal","Home","Health","Volunteering"]`.
- Build `taskId → lifeArea` map, **skipping tasks with null lifeArea**.
- Output: 7 `StackedBar(label, [Double] of size areas.count, all 0)` for Mon…Sun.
- For each session: skip null taskId; look up area; `areas.indexOf(area)`, skip if `< 0`; add `actualSec / 3600` to `out[dayIdx].data[areaIdx]`.
- **Test:** Tue Work 3600s → `out[1].data[0] == 1.0`; Wed Personal 1800s → `out[2].data[1] == 0.5`.

### 3.3 Calibration
- `CalibrationDot(e: Int /*estimateMin*/, a: Int /*actualMin*/, t: String /*taskName*/)`.
- `calibrationDots(sessions, tasks, cap=24)`: sort sessions **descending by completedAt** (string compare, see §4.2), take 24, for each skip null taskId / unknown task, `a = round(actualSec / 60.0)` (round-half-up), append.
- `calibrationHitRate(dots, slackMin=5)`: `count(|a - e| <= 5) / dots.count`; `0.0` if empty.
- `hit` percent in UI = `round(rate * 100)` (only computed when dots non-empty; else 0).
- **Tests:** 25-min est, sessions 25m & 27m → both dots, rate 1.0. Est 25, actual 50m → rate 0.0.

### 3.4 `interruptionBins(captures, sessions, binMin=3, binCount=10) -> [Int]`
- For each session compute `start = parseMillis(completedAt) - actualSec*1000` (a *derived* start; sessions store only completion). Keyed by **session id**.
- For each capture: skip null sessionId; look up start; `intoMin = (captureAtMs - startMs)/60000`; skip if `< 0`; `idx = min(9, floor(intoMin / 3))`; `bins[idx]++`.
- **Test:** capture 10 min into a 30-min session → `floor(10/3)=3` → `bins[3] == 1`.

### 3.5 `timeOfDayHeatmap(sessions) -> [[Double]]` (5×6)
- 5 rows (Mon–Fri), 6 cols (2-hour buckets from 07:00).
- Per session: `dow = dayOfWeekIdx`; skip if `> 4` (weekend); `bucket = floor((hourOf(d) - 7) / 2)`; skip if `< 0 || > 5`; add `actualSec/3600` to `grid[dow][bucket]`.
- **Test:** Sat session skipped; Tue 08:00 1800s → bucket 0 → `grid[1][0] == 0.5`; `grid.count == 5`.

### 3.6 `pauseAnatomy(reasonLogs) -> [PauseBar]`
- `PauseBar(reason, minutes: Double, count: Int)`.
- Iterate reason logs, key = `reason.ifEmpty { "Other" }`. Track **first-seen insertion order** (`order` list). Count every log. Add `durationSec/60` to minutes **only if durationSec != null && > 0**.
- Map in insertion order, **sort by minutes desc, then count desc**, take **6**.
- **Tests:** Bathroom(120s)+Bathroom(240s)+Drink(no dur) → row0 Bathroom count 2 minutes 6.0; row1 Drink count 1 minutes 0.0. 10 distinct reasons → capped at 6.

### 3.7 `reEntryDistribution(sessions, binMin=5, binCount=12) -> [Int]`
- Group sessions by **taskId** (skip null). Within each task sort **ascending by completedAt** (string). For consecutive pairs: `gapMin = (thisEnd - prevEnd)/60000 - thisActualSec/60`; skip if `<= 0`; `idx = min(11, floor(gapMin/5))`; `bins[idx]++`.
- The Deep-dive "Re-entry <5m" % = `bins[0] / sum(bins) * 100`, rounded (0 if sum 0).
- **Test:** two 25-min sessions on same task, second starting 35 min after first's start → gap of 10 min between end and next end minus duration → `floor(10/5)=2` → `bins[2] == 1`.

### 3.8 `slipping(tasks, now=System.currentTimeMillis()) -> [SlipRow]`
- `SlipRow(name, weeks: Int, moveCount: Int)`.
- Per task: skip if `done`. `ageDays = (now - parseMillis(createdAt))/86_400_000` (0.0 if unparseable). `moves = moveCount ?? 0`. Flag if `ageDays >= 21 || moves >= 3`. `weeks = max(0, floor(ageDays/7))`.
- Sort **moveCount desc, then weeks desc**, take 6.
- **Tests:** task created 30 days ago → 1 slip; moveCount 4 → 1 slip; done task with moveCount 5 → 0 slips. (Note: `slipping` in `topInsights` calls with default `now`, but the screen passes `vm.nowMs()` for the displayed list — pass your injected clock.)

### 3.9 `captureBreakdown(captures) -> [CaptureTag: Int]`
- A **linked/ordered map** pre-seeded `FOLLOW_UP, IDEA, EDIT, QUESTION, DISTRACTION` → 0, then `+1` per capture by tag. **Order matters** (the bars render in this fixed order). On Swift use an ordered structure (array of `(tag, count)` or `KeyValuePairs`) — a plain `Dictionary` loses order.
- **Test:** 2 follow-up + 1 distraction → 2/1/0/0/0 with idea=0 present.

### 3.10 `topInsights(sessions, tasks, captures, reasonLogs) -> [Insight]`
- `Insight(title, sub)`. Returns `take(3)`.
- **Only if `sessions.size >= 5`:**
  1. **Best weekday:** sum focus minutes per weekday (`dayOfWeekIdx`); if max > 0, pick **first** index achieving max; emit `"{WEEKDAY_NAMES[idx]} are your strongest day."` / `"{round(maxMin)} focused minutes — more than any other day this window. Stack harder work here."` `WEEKDAY_NAMES = ["Mondays".."Sundays"]`.
  2. **Calibration:** `dots = calibrationDots(...)`; if `dots.size >= 3`, `hit = rate`; phrase: `>=0.75` "you're nailing your estimates", `>=0.5` "your estimates are improving", else "estimates are still settling". Emit `"Estimates within 5 min {round(hit*100)}% of the time."` / `"{dots.size} recent sessions tracked — {phrase}. The calibration card shows where outliers landed."`
- **Always (any data volume):** top slipping task (`slipping(tasks)`, default now); if any, reason = moveCount≥3 → `"rescheduled {n} times"` else `"{weeks}+ weeks on the list"`; emit `"\"{name}\" keeps slipping."` / `"{reason}. Remove it, or break it down differently?"`
- **Tests:** empty → `[]` (no fallbacks). A slipping task with 0 sessions → still surfaces the slip card. 5 Monday sessions + 25-min est → surfaces both "strongest day" and "Estimates within 5 min".

---

## 4. Gotchas (these will silently break parity)

### 4.1 kotlinx default-omission ↔ Swift optionals
Kotlin's serializer **omits fields equal to their default** when encoding and **fills defaults** when a key is absent. So a `Session` JSON may have **no** `task_id`, `estimate_min`, or `tags` keys at all (→ `nil`/empty), and `actualSec`/`completedAt` are always present. On iOS your `Codable` structs **must** make these `Optional` with the same defaults (`taskId: String?`, `estimateMin: Int?`, `tags: [String]?`, `durationSec: Int?`, `sessionId: String?`, `moveCount: Int?`, `lifeArea: String?`, `completedAt` on tasks `String?`). A non-optional decode of an absent key throws and the whole row vanishes → empty charts. Conversely, **don't** require keys that Android omits.

### 4.2 ISO strings compared lexically as a sort key
`calibrationDots` sorts **descending** and `reEntryDistribution` sorts **ascending** directly on the `completedAt` **string**, not on parsed millis. This only matches numeric order because timestamps are normalized ISO-8601 (`YYYY-MM-DDTHH:MM:SS.sssZ`). Replicate with a **string** comparison (`<`/`>` on `completedAt`), not a `Date` comparison, to be byte-identical to Android. (The AnalyticsTest re-entry case even uses fractional-minute offsets that rely on this.) If your data could carry mixed offsets/precision, prefer parsing — but to be a faithful replica, sort the raw strings.

### 4.3 UTC vs local — date math is LOCAL, parsing tolerates many shapes
`Time` deliberately reproduces JS `Date` semantics: **all weekday/hour/day-of-month math uses the system local zone** (`ZoneId.systemDefault()`), while `parseMillis` is an absolute instant. So `dayOfWeekIdx`, `hourOf`, the week/month cutoffs, and the heatmap buckets are **local-time** derived. On iOS, use `Calendar.current` (device zone) for all civil math and `ISO8601DateFormatter` (with fractional-second + Z handling) for parsing. **The Android tests run with `-Duser.timezone=UTC`** — run your XCTest suite with `TZ=UTC` (or inject a fixed `Calendar`/`TimeZone(identifier: "UTC")`) so the ported assertions hold. Don't hardcode UTC in production code; only in tests.

`parseMillis` accepts, in order: full `Instant` (with Z), `OffsetDateTime`, offset-less `LocalDateTime` (interpreted in system zone), and bare `YYYY-MM-DD` date (start of day, system zone) — returning `nil` only if all fail. Match this fallback ladder; a bare date must **not** become `nil` (it'd otherwise read as a 0/sliver and silently drop). `nil` from parse is treated as `0L` in the window filter (drops for Week/Month, keeps for All).

### 4.4 Round-half semantics
Calibration uses `Math.round(actualSec/60.0)` (round half **up**), and several display values use `roundToInt`. Swift's `.rounded()` defaults to round-half-**to-even**; use `.rounded(.toNearestOrAwayFromZero)` to match `Math.round` exactly. The **median** is computed over raw **seconds** then `(secs[n/2] / 60.0).roundToInt()` — i.e. it's the **upper-middle** element for even counts (`secs[size/2]`), not an averaged median, and it rounds **once at display** (don't truncate per-session minutes first — there's an explicit comment about this skewing the median). Reproduce both quirks.

### 4.5 LWW / realtime / hydration ordering
These collections are server-synced (Supabase realtime + last-write-wins reconciliation in the shared sync layer). The Insights screen just **observes** the already-reconciled local store; it has **no LWW logic of its own**. The iOS dependency order: hydrate the store from Supabase → subscribe to realtime → the Insights view binds to the published streams and recomputes on every emission (Android uses `collectAsStateWithLifecycle`; iOS uses `@Published`/`@ObservedObject` or an `AsyncSequence`). All derivations are cheap/pure — recompute on each change; no caching required. Make sure `tasks`/`lifeAreas` are hydrated **before** first render or the StackedBars legend greys out and slip detection under-reports.

### 4.6 Exact-alarm / foreground-service / WorkManager — N/A here
Insights does **no** scheduling, no notifications, no background work, no foreground service. There is nothing to translate to `BGTaskScheduler`/`UNUserNotificationCenter` for this area. (Those concerns belong to the focus-timer and morning-brief areas.) Do not add any.

### 4.7 `enough` gates value text only, not section structure consistently
In Report view, real charts are entirely **omitted** when `!enough`. In Deep dive, stat **values** dash but the pause/captures/slip/heatmap sections still appear (each guarded by its own emptiness check, not by `enough`). The heatmap renders even when all-zero. Don't unify these into one rule.

### 4.8 StackedBars must use the user's OWN areas
Drive the legend and indices from `lifeAreas.map(name).ifEmpty(DEFAULT_AREAS)` — **not** `DEFAULT_AREAS` directly. There's a fixed bug comment: using DEFAULT_AREAS dropped every custom/renamed area's hours and greyed the legend. The `areaColorFor(name, lifeAreas, colors)` lookup matches by **name** against the LifeArea list and returns `colors.ink4` (a muted grey) when unmatched.

---

## 5. iOS equivalents (translation table)

| Android | iOS |
|---|---|
| Jetpack Compose (`LazyColumn`, `Box`, `Row`) | SwiftUI `ScrollView`+`LazyVStack`, `ZStack`/`GeometryReader`, `HStack`/`VStack` |
| `collectAsStateWithLifecycle()` over `StateFlow` | `@ObservedObject`/`@StateObject` with `@Published`, or `.task`/`AsyncSequence` bindings |
| `MdSegment` (segmented control) | `Picker(.segmented)` or a custom pill segmented control to match the Orbit design |
| `StatCard`/`Card`/`SectionLabel`/`AppBar` (design module) | Rebuild these as SwiftUI design-system views; `StatCard(label, value, badge?, badgeBg?, badgeFg?, caption?)` — note Android call sites pass positionally as `label, value, badge, badgeBg, badgeFg, caption` |
| Compose charts (manual `Box` fills) | Manual SwiftUI shapes (`RoundedRectangle`, `GeometryReader` for fractional widths/heights). **Swift Charts is optional** — the Android charts are hand-drawn fills; reproducing them with plain shapes is the faithful path and avoids Swift Charts styling drift |
| `Color.lerp(a, b, t)` for heatmap | manual RGB interpolation or `Color.mix(_:by:)` (iOS 18+); replicate `0.2 + 0.7*t` ramp |
| Room / local Kotlin store | **GRDB** or a JSON-file store; expose the same five observable collections |
| `kotlinx.serialization` models | `Codable` structs with snake_case key strategy or explicit `CodingKeys`; ordered map for `captureBreakdown` |
| supabase-kt client + realtime | **supabase-swift** (`SupabaseClient`, `.from("sessions").select()`, `RealtimeChannel`) |
| `core/logic/Analytics.kt` pure fns | a pure Swift `Analytics` enum/struct in `UnstuckCore`, identical signatures |
| `AnalyticsTest.kt` (JUnit) | **XCTest**, ported 1:1, run under `TZ=UTC` |
| WorkManager / AlarmManager / Glance / FCM / foreground service | **N/A for Insights** — no background, alarms, widget, push, or service in this area |
| `java.time` (`ZoneId.systemDefault`, `LocalDate`, `Instant`) | `Foundation` `Calendar.current` / `TimeZone.current`, `Date`, `ISO8601DateFormatter` (configure `[.withInternetDateTime, .withFractionalSeconds]`) |

### Acceptance criteria
1. Port `Analytics.kt` to Swift and pass all 16 `AnalyticsTest` cases verbatim (under UTC).
2. Report and Deep dive render identically across Week/Month/All, including the `—` dashing below 5 sessions, the calendar-anchored windows, and the exact card titles/captions/subs quoted above.
3. The three Report stat cards, the four Deep-dive stat cards, the seven-row stacked bars (user's own areas + legend), both histograms (with the `0.02` minimum sliver), pause/captures labeled bars (with their emptiness guards), the slip list (≤8), the 5×6 heatmap (with the `0.2+0.7t` lerp), and the ≤3 "Worth noticing" insight cards all match Android pixel-behavior and copy.
4. No background/notification/widget code is introduced for this area.