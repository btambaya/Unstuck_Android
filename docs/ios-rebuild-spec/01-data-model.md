# iOS Rebuild Spec — Data Model & Local Store

**Area owner module on Android:** `core/.../model/` (Models.kt + Enums.kt), `sync/.../DbRowCodec.kt`, `data/.../` (Room records/outbox/live_session, LocalStore.kt, Daos.kt, Entities.kt, UnstuckDatabase.kt). Supporting logic this area's contract depends on: `WriteThrough.kt`, `OutboxFlusher.kt`, `Hydrator.kt`, `RealtimeMirror.kt`, `SyncGateway.kt`, `SyncDecision.kt`, `core/.../logic/Uuid.kt`.

This is the foundation layer every other module sits on. Android is the reference client; the web app + Supabase DB are the source of truth for shape/semantics. **Do not invent fields or change shapes** — match exactly, byte-for-byte on the wire, or cross-client sync breaks.

The comments in the Kotlin repeatedly say "Port of the iOS X.swift" / "Mirrors X.swift" — an earlier iOS implementation existed and is being discarded. Treat the Kotlin as the canonical reference, **not** any surviving Swift in `unstuck_ios/`. Where the old Swift and the Kotlin disagree, the Kotlin wins.

---

## 1. What this layer does (behavior)

There are **no screens** in this area — it is the persistence + serialization substrate. Its observable behaviors are:

1. **Hold the full local cache** of every synced domain collection as decoded models, exposed as reactive streams (Kotlin `Flow<List<T>>`). The UI subscribes; when a row changes (local write, hydrate replace, or realtime push) the stream re-emits and the UI updates. iOS: each collection becomes an `AsyncStream`/Combine `Publisher` or an `@Observable`/`@Published` array.

2. **Local-first writes**: every mutation writes the model to the local store **immediately** (UI updates from the stream re-emit) and enqueues a server op in the outbox. The network flush happens later/asynchronously. The store layer itself only does the local write + enqueue; the flusher drains.

3. **Offline durability**: the outbox is the *only* copy of an unsynced offline write. It must survive app kills and (critically) **store-schema upgrades** without being wiped. FIFO ordering by sequence, with parent→child dependency ordering.

4. **Server-canonical hydrate**: on sign-in/reconnect, replace each table's local rows with the server set (per-table, error-isolated), preserving two classes of local-only rows (external Google `g_` cal_blocks; unsynced optimistic task blocks).

5. **One device-local live focus session** (single-row store) that is *not* synced to Supabase — it mirrors the web `unstuck-session` localStorage key and drives the focus timer across app restarts.

6. **Cache-wipe on user change**: clear all local data only when the signed-in user actually changes (not on same-user re-auth), so a re-auth can't clobber pending offline edits + the live session.

### Key state/edge cases this layer must reproduce
- A row that fails to decode (new server column/enum the client doesn't know, a null in a required field) must be **skipped, not fatal** — `mapNotNull { runCatching { decode }.getOrNull() }`. One bad row never kills the whole collection's stream.
- Hydrate of one table failing leaves that table's local data intact (no partial wipe).
- `cal_blocks` hydrate merges three sources: server rows (canonical, win on id collision), local external `g_` rows (kept), and locally-pending optimistic task blocks not yet flushed (kept until next flush).
- Outbox poison-pill: an op that fails `FAIL_CAP = 5` consecutive times is dropped, and its dependents are dropped too (their FK parent will never exist).
- Per-row last-writer ordering within a flush pass: once a row's op fails this pass, skip that same row's *later* ops so a newer edit isn't applied then clobbered by the older one's retry.

---

## 2. Data — models, enums, and Supabase tables

### 2.1 Domain models (camelCase everywhere — `core/model/Models.kt`)

These mirror `lib/types.ts` (web). **camelCase in memory; the snake_case PostgREST mapping lives only in DbRowCodec.** All timestamp/date fields are **ISO-8601 strings, not Date objects** (they pass through untouched; see §4 UTC).

Swift target: `struct`s conforming to `Codable, Equatable, Identifiable, Sendable`. Use `String` for all date/time fields (do NOT decode to `Date` — keep them opaque strings, exactly as Kotlin keeps `String`).

```
Objective       { text: String, done: Bool?, minutes: Int? }
Comment         { text: String, at: String? }
Recurrence      (sealed/tagged-union — see §3.1)
                  .daily(until: String?)
                  .weekly(daysOfWeek: [Int], until: String?)   // 0=Sun..6=Sat
                  .monthly(until: String?)
TaskItem {
  id: String, name: String, estimateMin: Int,
  totalFocused: Int = 0, done: Bool = false,
  priority: Priority?, tags: [String]?, objectives: [Objective]?,
  comments: [Comment]?, intentWhen: String?, intentThen: String?,
  lifeArea: String?, firstPhysicalAction: String?, moveCount: Int?,
  completedAt: String?, later: Bool?, recurrence: Recurrence?,
  sourceCollectionId: String?, sourceItemId: String?, dueAt: String?,
  createdAt: String, updatedAt: String
}
Session         { id, taskId: String?, taskName, tags: [String]?, estimateMin: Int?, actualSec: Int, completedAt }
CalBlock        { id, taskId: String?, taskName, startTime ("HH:MM"), durationMinutes: Int, date ("YYYY-MM-DD"), externalEventId: String?, externalConnectionId: String?, kind: CalBlockKind? }
ReasonLog       { id, taskId: String?, reason, action: ReasonAction, at, durationSec: Int? }
Capture         { id, taskId: String?, sessionId: String?, tag: CaptureTag, body, at }
CalendarConnection { id, provider: CalendarProvider, accountEmail, displayName, selectedCalendarIds: [String], colorSlot: Int, lastSyncCursor: String?, connectedAt }
ExternalEvent   { id, connectionId, calendarId, summary, start, end }   // not synced as a table; transient
CollectionItem  { id, body, pinned: Bool?, done: Bool?, at, promoted: Bool?, assignee: String?, promotedDone: Bool?, dueAt: String? }
ItemCollection  { id, name, color, subtitle: String?, items: [CollectionItem], sortOrder: Int,
                  ownerId: String?, members: [String] = [], myRole: String?, archived: Bool? }   // last 3 client-only — see §4
TagRow          { id, name, color: String?, sortOrder: Int }
LifeArea        { id, name, color, sortOrder: Int }
LiveSession     { id: String?, taskId, sessionStart: Int64? (epoch ms), paused: Bool = false,
                  pausedAt: Int64? (epoch ms), sessionEstimateMin: Int, nudge80Fired: Bool = false,
                  overrunPromptFired: Bool = false, treatment: FocusTreatment, priorAccumulatedSec: Int? }
```

### 2.2 Enums (`core/model/Enums.kt`) — `@SerialName` = the exact server string

Encode as the lowercase/kebab strings below (Swift: `enum: String, Codable` with these raw values). Decoding an unknown value must throw/skip (so the realtime/hydrate skip-bad-row guard catches it), **not** silently map to a default.

| Enum | Cases → wire string |
|---|---|
| `Priority` | urgent, high, medium, low |
| `FocusState` | idle, starting, running, overrun, pause, done, resume |
| `FocusTreatment` | ambient, cockpit, monk |
| `CalBlockKind` | task, placeholder, external |
| `ReasonAction` | pause, switch |
| `CaptureTag` | follow-up, idea, edit, question, distraction (note the hyphen on `follow-up`) |
| `CalendarProvider` | google, apple, microsoft |
| `ThemePref` | system, light, dark |
| `Density` | compact, regular, comfy |
| `TaskListView` | UI-only (label strings All/Backlog/Today/Upcoming/Later/Completed) — not serialized |

### 2.3 Supabase tables/columns (the wire contract — from `supabase/migrations/`)

DbRowCodec must round-trip these **exact snake_case top-level columns**. JSONB blobs keep camelCase internally.

- **tasks** — `id uuid pk`, `user_id`, `name`, `estimate_min int (1..1440, default 25)`, `total_focused int (>=0, default 0)`, `done bool default false`, `priority text check(urgent|high|medium|low) default 'medium'`, `tags text[] default '{}'`, `objectives jsonb default '[]'`, `comments jsonb default '[]'`, `intent_when`, `intent_then`, `life_area`, `first_physical_action`, `move_count` (present in codec/web), `completed_at timestamptz`, `later bool default false`, `recurrence jsonb`, `source_collection_id uuid`, `source_item_id text`, `due_at timestamptz`, `late_nudged bool default false` **(server-owned by cron — never write from client; not in the model at all)**, `created_at`, `updated_at` (DB trigger `touch_updated_at` bumps on update).
- **sessions** — `id`, `user_id`, `task_id uuid (on delete set null)`, `task_name` (denormalized), `tags text[]`, `estimate_min int?`, `actual_sec int (>=0)`, `completed_at timestamptz not null`.
- **cal_blocks** — `id`, `user_id`, `task_id uuid nullable (on delete cascade when set)`, `task_name`, `start_time text 'HH:MM'`, `duration_minutes int (5..1440)`, `date date`, `external_event_id text`, `external_connection_id uuid`, `kind text check(task|placeholder|external) default 'task'`.
- **reason_logs** — `id`, `user_id`, `task_id uuid`, `reason text`, `action text check(pause|switch)`, `at timestamptz default now()`, `duration_sec integer (null or >=0)` **(omit on upsert when null — see §3.3)**.
- **captures** — `id`, `user_id`, `task_id?`, `session_id?`, `tag`, `body`, `at`.
- **collections** — `id`, `user_id` (= owner), `name`, `color text default 'indigo'`, `subtitle text not null default ''`, `items jsonb default '[]'` (array of CollectionItem), `sort_order int`, `archived bool default false`, timestamps. Item shape camelCase: `{id, body, pinned?, done?, at, promoted?, assignee?, promotedDone?, dueAt?}`.
- **collection_members** (junction, migration 020/022) — `(collection_id, user_id) pk`, `role text check(editor|viewer) default 'editor'`. Used to enrich collections with `members[]`/`myRole`; never mirrored as its own local table.
- **tags** — `id`, `user_id`, `name`, `sort_order int`, `color text?` (migration 011), `unique(user_id,name)`.
- **life_areas** — `id`, `user_id`, `name`, `color text default 'indigo'`, `sort_order int`, `unique(user_id,name)`. Default seed on signup: Work/indigo/0, Personal/coral/1, Volunteering/green/2, Home/amber/3, Health/teal/4.
- **calendar_connections** — has an encrypted `credentials bytea` column the client never reads/writes and which is **deliberately excluded from realtime** (migration 013). The model omits it.

**Logical-table name constants** (Android `Tables`) — these strings double as the local-store table discriminator AND the Supabase table name (the sync layer passes them straight through): `tasks, cal_blocks, sessions, captures, reason_logs, collections, tags, life_areas, calendar_connections`. Keep the identical set of constants on iOS.

---

## 3. Business rules / pure logic (with the tests that pin them)

### 3.1 Recurrence — tagged-union JSON (cited: `RecurrenceSerializer` in Models.kt; `CoreModelsTest`, `DbRowCodecTest.nestedJsonbStaysCamelCase`)

`Recurrence?` optional: `nil` = does not repeat. When present, JSON shape is exactly:
```json
{ "kind": "daily",   "until": "YYYY-MM-DD"? }
{ "kind": "weekly",  "daysOfWeek": [0..6], "until": ...? }   // 0=Sun..6=Sat, inclusive `until`
{ "kind": "monthly", "until": ...? }
```
Rules the Swift `Codable` impl must reproduce:
- `kind` is **always** written. `daysOfWeek` written **only** for weekly. `until` written **only when non-nil** (omitted when nil — note: nested `until` is omitted, unlike top-level columns which write explicit null; this is a custom-coder choice, not the global policy).
- Decoding an **unknown kind throws** (`CoreModelsTest.unknownKindThrows`). Decoding `{"kind":"weekly","daysOfWeek":[0,6],"until":null}` → weekly([0,6], until:nil) (`decodesWebJsonShape`).
- Round-trips for daily/weekly/monthly with and without `until` must be identity (the four round-trip tests).
- iOS: implement custom `init(from:)` / `encode(to:)` on a `Recurrence` enum. **Do not** use Swift's default enum-with-associated-values Codable (it produces a different envelope).

### 3.2 UUID gate / FK-or-null (cited: `core/logic/Uuid.kt`; `DbRowCodecTest.calBlockNonUuidTaskIdDropsToNull` / `...Preserved`)

- `newUuid()` → lowercased UUIDv4. `isUuid(s)` matches the canonical 8-4-4-4-12 hex regex.
- `uuidOrNull(s)` → `s` if it's a valid UUID, else `nil`. Applied to **FK columns only** when encoding rows: `session.task_id`, `cal_block.task_id`, `cal_block.external_connection_id`, `capture.task_id`, `capture.session_id`, `reason_log.task_id`. This drops sentinel ids like `"placeholder"`, `"cal-123"` to null so they don't violate the uuid FK column. Test: `taskId="placeholder"` → `task_id: null`.

### 3.3 DbRowCodec — the PostgREST boundary (cited: `DbRowCodecTest`)

The single most contract-critical file. Encoders produce a JSON object; decoders parse one. Rules:

1. **Top-level columns snake_case** (`estimate_min`, `total_focused`, `created_at`, …). `topLevelSnakeCaseColumns` test asserts `estimate_min` present, `estimateMin` absent.
2. **Nested JSONB stays camelCase** — `recurrence.daysOfWeek`, `objectives[].text`, `comments`, collection `items[]`. There is **no global snake-case strategy**; the nested model types serialize with their own camelCase keys. `nestedJsonbStaysCamelCase` asserts `daysOfWeek` survives.
3. **Explicit nulls clear columns** (`explicitNulls = true` + `encodeDefaults = true`). A nil `completedAt` encodes as `"completed_at": null` (present, JSON null) so an upsert **clears** the column. `explicitNullClearsCompletedAt`.
4. **`reason_logs.duration_sec` exception** — when null it is **stripped from the payload entirely** (not sent as null) so an upsert never clobbers a server-set value. `reasonLogOmitsDurationSecWhenNull`: absent when null, present (120) when set. This is the *only* omit-when-null column.
5. **Defaults match web on encode**: `tags ?? []`, `objectives ?? []`, `comments ?? []`, `move_count ?? 0`, `later ?? false`, `kind ?? task`, `selected_calendar_ids ?? []`. `taskDefaultsMatchWeb`.
6. **`user_id` is NOT in any row DTO** — the gateway injects it on write (`SyncGateway.withUserId`). Encoders return an object the gateway can spread `user_id` into.
7. **Decode ignores unknown server columns** (`ignoreUnknownKeys`/`isLenient`) — server returns `user_id`, server `created_at`, etc. `decodeIgnoresExtraServerColumns`.
8. **Collection subtitle**: nil → encoded as `""` (column is `not null default ''`); `""` → decoded back to nil. `collectionSubtitleEmptyBecomesNull`.
9. **Collection decode**: reads `ownerId` off the **raw row's `user_id`** (not a CollectionRow field, so it never round-trips back into an upsert payload). `members`/`myRole`/`archived` enrichment handled by the Hydrator, not the codec.

iOS: a single `DbRowCodec` with per-entity row structs that use `CodingKeys` for snake_case and custom encode for the duration_sec/subtitle/explicit-null behaviors. **You cannot get all of this from `JSONEncoder.keyEncodingStrategy = .convertToSnakeCase`** because (a) it would also snake-case the nested JSONB keys (breaking `daysOfWeek`), and (b) Swift's `JSONEncoder` omits nil optionals by default rather than emitting explicit `null`. You must hand-roll: per-struct `CodingKeys`, and `encodeNil`/`encode` calls (or `encodeIfPresent` vs forced `encode` of `Optional` as null) to control exactly which keys appear. Verify with the same assertions the Kotlin tests make.

### 3.4 Cache-wipe rule (cited: `SyncDecision.shouldWipeCache`; `SyncDecisionTest`)

Wipe local cache only when the user actually changed:
- `signedIn` / `initialSession`: wipe iff `prevUserId != currentUserId` (so first sign-in with `prev=nil` wipes; same-user re-emit does **not**).
- `userUpdated`: **never** wipe.

This protects pending offline edits + the live session from a SIGNED_IN re-emit. Pure function — port verbatim with the four-case test.

### 3.5 cal_blocks hydrate merge (cited: `SyncDecision.mergeHydratedCalBlocks`; `SyncDecisionTest`)

Build a map keyed by id: insert local **external** blocks first, then remote (remote wins on collision). Local non-external rows are dropped (server canonical). Plus, in `Hydrator.hydrateCalBlocks`, also re-add locally-pending optimistic TASK blocks that have a queued outbox upsert and aren't in the merged set, so they don't flicker off the UI until the next flush. `isExternalBlock` = id starts `g_` OR `externalEventId != null` OR kind `external`.

---

## 4. Gotchas (must-reproduce, easy to get wrong)

1. **No global key-coercion** — snake_case is per-DTO via explicit keys; nested JSONB is camelCase. A blanket `.convertToSnakeCase` corrupts `daysOfWeek`/`promotedDone`/etc. (Swift `JSONEncoder` strategy is the trap here.)

2. **Explicit-null vs default-omission asymmetry**:
   - **Top-level row columns**: emit explicit `null` for nil optionals (upsert clears the column). Swift `encodeIfPresent` would *omit* and silently leave stale server data — wrong. Use forced `encode` (which writes `null`) for clearable columns.
   - **The one exception**: `reason_logs.duration_sec` — omit when nil.
   - **Nested `Recurrence.until`**: omitted when nil (custom coder).
   - The Kotlin "kotlinx default-omission" gotcha is the mirror image of Swift's default: kotlinx omits defaults unless `encodeDefaults=true`; Swift omits nil optionals unless you force-encode. Both clients had to be deliberately configured to emit defaults/nulls. **Match the JSON, not the language default.**

3. **UTC / ISO strings are opaque** — every date/time crosses the wire as the server's ISO-8601 string and is stored/compared as-is. Do **not** parse to `Date` and re-serialize in the store layer (round-trip drift, fractional-second/`Z`-suffix loss). `start_time` is `"HH:MM"`, `date` is `"YYYY-MM-DD"` — local wall-clock strings, not timestamps. `LiveSession.sessionStart`/`pausedAt` are **epoch-ms `Int64`** (device-local), a different convention from the ISO strings — keep that distinction.

4. **LWW (last-writer-wins) is whole-row** — Supabase upsert with `onConflict=id` replaces the entire row; there is **no field-level merge**. Two clients editing the same task → last upsert wins wholesale. The store denormalizes `updatedAt` (for ordering/debug) but the actual conflict resolution is server-side LWW. The **only** finer-grained path is collections' item-level RPCs (migration 022 `collection_add_item` etc.) for concurrent shared-list edits — but the local store still treats collections as whole-row upserts; the RPCs live in the sync/collections layer, out of this area. Within the outbox, per-row ordering is preserved so an older queued edit can't land after a newer one (`blockedRows` skip in OutboxFlusher).

5. **Outbox = the only copy of offline writes** — never destroy it on a store upgrade. Android: `fallbackToDestructiveMigrationOnDowngrade()` only (a forward version bump without a migration **fails loudly** rather than wiping). iOS equivalent: if you use GRDB, register explicit `DatabaseMigrator` migrations and never auto-reset on a missing one; if you use a JSON file store, version the file and migrate forward — **never** delete the outbox file on a schema mismatch. Only an older-binary *downgrade* may reset.

6. **Dependency ordering** (cited: `WriteThrough` + `OutboxEntity.dependsOn` + `OutboxFlusher`):
   - A `cal_blocks` upsert carries `dependsOn = task.id` (only when task_id is a UUID) so the parent task flushes first (FK).
   - A `captures` upsert carries `dependsOn = session_id` (session row is only written at session-end).
   - The flusher holds an op back while its `dependsOn` row still has a pending op.
   - **Poison pill**: `FAIL_CAP = 5` consecutive failures → drop the op AND drop its dependents (their FK parent will never exist). Counts reset on app restart.
   - **Delete cancels queued upserts** (`cancelPendingUpserts`): deleting a row dequeues its pending upserts first, so a held-back upsert can't resurrect the row server-side after the delete flushes.
   - External `g_`/`kind=external` cal_blocks are **never** enqueued to our `cal_blocks` table (not our row shape — would fail forever and stall the outbox).

7. **Skip-bad-row everywhere** — store reads, hydrate decode, and realtime apply all wrap decode in `runCatching{…}.getOrNull()` (or skip). A single un-decodable row (new enum/column) must never throw out of a stream/subscription and kill it. Reproduce with `try?`/`Result` per row.

8. **Client-only collection fields** (`ownerId`, `members`, `myRole`, `archived`-handling) — `members`/`myRole` are populated by the Hydrator from `collection_members` and **must never be written back** (the CollectionRow codec doesn't include them, so they're dropped on encode). `ownerId` is derived from the raw row `user_id` on decode and likewise never round-trips. Realtime upserts for collections **preserve** the existing `members`/`myRole` across an incoming row (which carries neither) — mirror this merge.

9. **calendar_connections excluded from realtime** (its encrypted creds must not broadcast) — and the model has no `credentials` field. Don't subscribe it in the realtime layer.

10. **`late_nudged` is server-owned** — cron writes it; client never reads/writes it; it's absent from `TaskItem`. Don't add it.

11. **Live session is device-local, not a synced table** — single-row store (id=0 in Room). Not in `Tables`. Not hydrated, not in the outbox, not in realtime. `clearAll()` wipes it along with records+outbox.

---

## 5. iOS equivalents / implementation plan

| Android / Kotlin | iOS / Swift |
|---|---|
| `core/model` data classes, kotlinx `@Serializable` | `struct … : Codable, Equatable, Identifiable, Sendable` in a `UnstuckCore` module |
| `@SerialName` enums | `enum: String, Codable` with matching raw values; throw on unknown |
| Custom `RecurrenceSerializer` | Custom `Codable` (`init(from:)`/`encode(to:)`) on a `Recurrence` enum |
| `core/logic/Uuid.kt` (`UUID.randomUUID().toString()`, regex) | `UUID().uuidString.lowercased()`; same regex via `NSRegularExpression`/`Regex` |
| **Room** `records` table (composite key `tableName`,`id`, `data` JSON blob) + DAOs + `Flow` | **GRDB** `records` table + `ValueObservation` → `AsyncValueObservation`/Combine; OR an actor-guarded JSON-file store exposing `AsyncStream`. GRDB is recommended (real WAL durability, transactions, the `replaceTable` semantics). |
| `RecordDao.replaceTable(preserveIdsPrefix)` (`DELETE … NOT LIKE 'g_%'` then insert) | GRDB transaction: delete-where-id-not-like, then upsert |
| `OutboxDao` (autoincrement `seq`, FIFO, `dependsOn`) | GRDB `outbox` table, `INTEGER PRIMARY KEY AUTOINCREMENT seq` |
| `LiveSessionDao` single row id=0 | single-row GRDB table OR a small file; expose observe/get/set/clear |
| `LocalStore` typed facade (`observe(table, serializer)` → `Flow<List<T>>`) | `LocalStore` actor/class with generic `observe<T: Codable>(_ table:) -> AsyncStream<[T]>` |
| `kotlinx.serialization.json.Json{ ignoreUnknownKeys; encodeDefaults; isLenient }` | `JSONDecoder`/`JSONEncoder`; ignore-unknown is default in Swift; you must **hand-control** explicit-null + snake_case (see §3.3) |
| `DbRowCodec` (per-entity Row DTOs) | `DbRowCodec` with per-entity Row structs + `CodingKeys`; custom encode for duration_sec/subtitle/explicit-null |
| `SyncGateway` (supabase-kt postgrest upsert/select/delete, inject user_id) | **supabase-swift** (`supabase-swift` / `PostgREST`): `from(table).upsert(…, onConflict:"id")`, `.select()`, `.delete().eq("id", …)`; inject `user_id` into the dict |
| `RealtimeMirror` (postgrest changes, per-table channel, skip-bad-row) | supabase-swift `RealtimeChannelV2` `postgresChange` streams; same skip-bad-row guard |
| `WorkManager` periodic flush | **BGTaskScheduler** (`BGAppRefreshTask`/`BGProcessingTask`) for background drains; flush also on foreground/reconnect |
| `AlarmManager` exact alarms (focus timers) | **UNUserNotificationCenter** local notifications (interval/calendar triggers). **No exact-alarm permission on iOS** — but note iOS has no guaranteed exact background wake; rely on local notifications + on-launch reconciliation. (Out of this area, but the LiveSession fields `nudge80Fired`/`overrunPromptFired` drive those notifications, so model them faithfully.) |
| `Glance` widget | **WidgetKit** (reads a shared snapshot via App Group container) |
| `FCM` push | **APNs** (the `platform` discriminator on the server already supports per-platform; backend token table exists) |
| Android **foreground service** keeping the timer alive | **iOS has no equivalent** — there is no persistent foreground service. The live timer is reconstructed from `LiveSession` (epoch-ms `sessionStart`/`pausedAt` + `priorAccumulatedSec`) on each foreground, and local notifications cover background nudges. Persist faithfully so the timer is exactly recomputable after suspension/relaunch. |
| Room `fallbackToDestructiveMigrationOnDowngrade()` | GRDB `DatabaseMigrator` (explicit, forward-only); never reset on missing migration; only a downgrade may reset. **Protect the outbox.** |

### Recommended module/file layout (mirror Android's 3-layer split)
- `UnstuckCore` (pure, no I/O): `Models/*.swift`, `Enums.swift`, `Recurrence.swift`, `Uuid.swift`, plus the pure `SyncDecision` functions (cache-wipe, cal_blocks merge) — all unit-testable with no DB/network.
- `UnstuckData`: `LocalStore.swift`, GRDB `Database.swift` + record/outbox/live-session tables, `Tables` constants.
- `UnstuckSync`: `DbRowCodec.swift`, `SyncGateway.swift`, `WriteThrough.swift`, `OutboxFlusher.swift`, `Hydrator.swift`, `RealtimeMirror.swift`.

### Tests to port 1:1 (these are the acceptance criteria)
- **DbRowCodecTests** (from `DbRowCodecTest.kt`): snake_case top-level; camelCase nested `daysOfWeek`/`objectives`; explicit-null clears `completed_at`; defaults (`move_count=0`, `later=false`, `tags=[]`); `duration_sec` omitted-when-null / present-when-set; cal_block FK uuid-or-null both directions; full task round-trip; decode ignores extra columns; collection subtitle `nil↔""`.
- **CoreModelsTests** (from `CoreModelsTest.kt`): Recurrence round-trips (daily/weekly/monthly ±until), decode web JSON shape, unknown-kind throws; UUID new/valid/reject.
- **LocalStoreTests** (from `LocalStoreTest.kt`): replace+observe; JSONB shape survives store→load (recurrence.daysOfWeek + objectives + priority + tags); replace preserves external `g_` blocks; outbox FIFO + dequeue; live-session single-row set/get/clear; `clearAll` wipes records + outbox + live.
- **SyncDecisionTests** (from `SyncDecisionTest.kt`): cache-wipe four cases; cal_blocks merge preserves external + remote-wins-on-clash + drops local non-external.

Hitting all four suites with identical assertions is the definition of "1:1 behavioral replica" for this layer.