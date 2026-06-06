# iOS Rebuild SPEC — Sync Engine (offline-first)

Status: implementation spec for the from-scratch SwiftUI rebuild of Unstuck iOS. **Android is the reference client.** The existing iOS sync code is discarded; this spec is the contract. Behavior must match the Android `:sync` + `:data` modules 1:1. Where Android files cite "Port of the iOS X.swift," that previous iOS code is gone — reimplement against *this* spec, not the old Swift.

Android source of truth (read alongside this spec):
- `sync/src/main/kotlin/tech/csalliance/unstuck/sync/` — `WriteThrough.kt`, `OutboxFlusher.kt`, `Hydrator.kt`, `RealtimeMirror.kt`, `SyncCoordinator.kt`, `SyncDecision.kt`, `SyncGateway.kt`, `DbRowCodec.kt`, `AuthService.kt`, `SupabaseClientProvider.kt`
- `data/src/main/kotlin/tech/csalliance/unstuck/data/` — `LocalStore.kt`, `db/Entities.kt`, `db/Daos.kt`
- `core/.../logic/` — `Uuid.kt`, `CalBlockKind.kt`, `GoogleSyncMapping.kt`; `core/.../model/Models.kt`
- Tests (replicate as Swift XCTest): `sync/src/test/.../SyncDecisionTest.kt`, `DbRowCodecTest.kt`; `data/src/test/.../LocalStoreTest.kt`
- `app/.../surface/SyncWorker.kt` (WorkManager periodic job → BGTaskScheduler)

---

## 1. What it does — behavior, states, flows, edge cases

The sync engine is an **offline-first, optimistic, server-canonical** mirror. Every user mutation writes the local store first (UI updates instantly off a reactive read) and enqueues a server op in a durable outbox. A background drainer (`OutboxFlusher`) pushes ops to Supabase in FIFO + dependency order. On (re)connect / sign-in it pulls the server set (`Hydrator`) and subscribes to Postgres realtime (`RealtimeMirror`). An orchestrator (`SyncCoordinator`) wires it all to auth state.

There is **no sync UI / settings screen** for this engine. Its only user-visible surfaces are:
- **Instant optimistic updates** — any create/edit/delete reflects immediately because views observe the local store.
- **Pending-write count** — `LocalStore.pendingCount()` exposes a reactive `Int` of outstanding outbox ops; surfaced as a tiny "syncing N" indicator where the app chooses (optional; keep the publisher).
- **Calendar connect state** flipping to "Synced" (covered by the Calendar area spec, but the flush+hydrate ordering during connect lives here — see §1.7).

### 1.1 Optimistic write (WriteThrough)
For every entity there is an `upsertX(model)` / `deleteX(id)`. Each:
1. Writes/removes the row in the local store (JSON blob keyed by `(table, id)`).
2. Enqueues an `OutboxEntity` (`op`, `recordTable`, `recordId`, `payload`, `dependsOn`, `createdAt`).

`payload` for an upsert is the **encoded PostgREST row JSON** (`DbRowCodec.encodeX`), serialized to a string. For a delete `payload` is `null`.

Entities and their WriteThrough behaviors:
- `upsertTask`, `upsertSession`, `upsertCapture`, `upsertReasonLog`, `upsertCollection`, `upsertTag`, `upsertLifeArea` — plain optimistic write + enqueue.
- `upsertCalBlock` — special (see §1.6 + §1.7): skips external `g_`/EXTERNAL blocks entirely, sets `dependsOn = task.id` when the task id is a UUID, and mirrors to Google.
- `upsertCapture` — `dependsOn = sessionId` (when a UUID): a capture taken *during* a session FK-references a `sessions` row only written at session end.
- Deletes: `deleteTask/Tag/LifeArea/Collection/Session/Capture/ReasonLog` → local delete + **cancel any pending upsert ops for that row** + enqueue a delete. `deleteCalBlock` is special (reads block for Google delete, cancels pending upserts, skips external).

### 1.2 Outbox drain (OutboxFlusher.flush)
Called with `(userId, currentUserId: () -> String?)`. Loops:
1. **User-switch guard**: if `currentUserId() != userId`, return immediately (a mid-drain sign-out + sign-in to a different account must not keep stamping the old user's id; mirrors the web `intendedUserId` guard). RLS would also block it, but this avoids confusing FK/RLS errors + a wedged op.
2. Read all pending ops, **FIFO by `seq`**. Empty → break.
3. **Dependency hold-back**: build `pendingIds = {op.recordId}`. An op is *flushable* only if `dependsOn == nil || dependsOn ∉ pendingIds`. If nothing is flushable, break (a dependent waits for its parent which has no remaining op only after the parent flushed).
4. Iterate flushable ops in order. Track `blockedRows: Set<"table:id">`.
   - If a row already failed this pass, **skip its later ops** (LWW / per-row ordering — see §1.4).
   - Apply the op (`gateway.upsert` or `gateway.delete`). On success: dequeue, clear its fail count, mark progress.
   - On failure: add the row to `blockedRows`, increment a per-`seq` fail tally.
     - When the tally hits **`FAIL_CAP = 5`**: treat as **poison pill** — dequeue and drop it, AND **drop every op whose `dependsOn == this op's recordId`** (orphan-drop: their FK parent will never exist server-side).
5. If no op progressed this pass, break (all errored — retry on next reconnect/sign-in). The `while(true)` otherwise re-reads the queue so newly-unblocked dependents flush in the same call.

`failCounts` is **in-memory, resets on app restart** — so a transient failure (offline) still gets full retries next launch; only a genuinely poison op (e.g. payload the server rejects forever) gets dropped after 5 consecutive same-process failures.

### 1.3 Hydrate (Hydrator.hydrate)
Pulls every synced table and **replaces** the local table (server-canonical). Per-table error isolation: a table whose fetch throws is left intact (the others still replace). Tables, in order:
`tasks → sessions → captures → reason_logs → collections(+members) → tags → life_areas → calendar_connections → cal_blocks`.

Two tables have special hydrate logic:
- **collections** (`hydrateCollections`): fetch `collections` (RLS returns own + shared-with-me), fetch `collection_members`, build `collectionId → [(userId, role)]`. For each collection compute `members = [userIds]` and `myRole = "owner"` if `ownerId == currentUser` else the member's role (or `nil`). Replace.
- **cal_blocks** (`hydrateCalBlocks`): the server set is canonical, but:
  - Preserve **local EXTERNAL `g_` blocks** (Google events; ids aren't UUIDs so they never round-trip to Postgres) via `SyncDecision.mergeHydratedCalBlocks(remote, localExternal)` (remote wins on id collision).
  - Preserve **unsynced optimistic TASK blocks** — those with a pending `cal_blocks` upsert op in the outbox, not present in `remote`, not external. Otherwise the replace would wipe a just-scheduled block off the UI until the next flush.
  - Final set = `merged + localPending`.

### 1.4 LWW (last-writer-wins) — precise semantics
There are **two distinct mechanisms**, do not conflate:

(a) **Local store upsert is unconditional replace** (Room `@Upsert` → just overwrites the `(table,id)` row). `updatedAt` is stored denormalized only for cheap ordering/debug; **the store does NOT compare timestamps on write.** Whoever writes last to the local store wins locally. Realtime + hydrate also just overwrite. So "LWW" at the store level = "newest write applied wins," full stop — replicate exactly: GRDB/JSON upsert is an unconditional `INSERT … ON CONFLICT … DO UPDATE`.

(b) **Outbox per-row ordering** (the comment-cited "preserve per-row order / last-writer-wins" in `OutboxFlusher`): once an op for `table:id` fails in a pass, all *later* queued ops for that same row are skipped this pass. This prevents a newer edit being applied and then clobbered when an older op for the same row retries — it guarantees the server converges to the **last enqueued** state for each row, applied in `seq` order. Implement `blockedRows` exactly.

Server-side, Supabase has a `touch_updated_at` trigger that bumps `updated_at` on UPDATE; the client's encoded `updated_at` is what we send, but the server may overwrite it. Don't rely on client `updated_at` for conflict resolution — there is none beyond "last upsert wins."

### 1.5 Realtime mirror (RealtimeMirror.subscribeAll)
One Postgres-changes channel **per synced table**, channel name `"unstuck_{table}_{userId}"`, schema `public`. INSERT/UPDATE → decode + local upsert; DELETE → local delete by `oldRecord["id"]`.
- Filtered by `user_id == userId` (client safety; RLS is the real guard) **except `collections`** which subscribes **without** the user filter (shared rows are owned by someone else; rely on RLS for delivery to members).
- **`calendar_connections` is intentionally NOT subscribed** — it holds encrypted creds that must never be broadcast (and migration `013` disables realtime on it server-side too).
- **`collections` upsert merges** the incoming row with the existing local one, preserving client-only `members`/`myRole` (the realtime row carries neither). If new and `ownerId == userId`, `myRole = "owner"`, else `nil`.
- **`collection_members` for ME** (filtered `user_id == userId`): any insert/update/delete → call `onMembersChanged` which **re-hydrates collections** (a new share appears / a revoked one drops). It does not mirror member rows itself.
- **Per-event guard**: each event handler is wrapped so one un-decodable row (new column, null in a required field) is skipped and logged — it must NOT throw out of the stream and kill that table's live mirror permanently.
- `subscribeAll` first calls `unsubscribeAll()` (idempotent). `unsubscribeAll` cancels all stream tasks and unsubscribes all channels.

### 1.6 cal_blocks two-way Google mirror (inside WriteThrough/Coordinator)
`upsertCalBlock(b)`:
1. Local upsert always.
2. If `b.kind == EXTERNAL || b.id.startsWith("g_")` → **return** (never push external rows to our `cal_blocks` table — wrong shape/id, would fail forever and wedge the outbox — and never re-push them to Google).
3. `dependsOn = b.taskId` if it's a UUID, else `nil`.
4. Enqueue the `cal_blocks` upsert.
5. Best-effort Google push via injected `pushCalBlock` hook: if it returns a new event id and it differs from `b.externalEventId`, **re-write the block locally with `externalEventId` stamped** and **enqueue a second upsert** (so later edits PATCH the same Google event and a pull won't duplicate it).

`deleteCalBlock(id)`:
1. Read the block first (for the Google delete) unless `g_`.
2. Local delete.
3. If not `g_`: **cancel pending upserts** for this id (a held-back upsert with `dependsOn=task.id` could otherwise flush *after* the delete and resurrect the row server-side), then enqueue a delete.
4. Fire `pushCalBlockDelete` for the read block (best-effort Google event delete).

### 1.7 Orchestrator flows (SyncCoordinator)
Observes auth `sessionStatus`. Map the SDK session source → a `SyncAuthEvent`:
- SignIn / SignUp / External(OAuth) → `SIGNED_IN`
- Storage (restored session on launch) → `INITIAL_SESSION`
- UserChanged / UserIdentitiesChanged → `USER_UPDATED`
- Refresh / Unknown → no cache action (return)

On **Authenticated**:
1. `uid = session.user.id`.
2. `prev = prefs[KEY_PREV_USER]`. If `SyncDecision.shouldWipeCache(event, prev, uid)` → `store.clearAll()`.
3. Persist `prefs[KEY_PREV_USER] = uid`.
4. `flusher.flush(uid) { auth.currentUserId }` → `hydrator.hydrate(uid)` → `realtime.subscribeAll(uid) { hydrateCollections }` → `pullCalendar()` (best-effort) → `maybeTrackLogin(uid)` (throttled analytics).
   **Order is load-bearing: flush before hydrate** (hydrate replaces cal_blocks; an unflushed local TASK block in neither remote-set would vanish until next flush — the `localPending` preservation in §1.3 also backstops this).

On **NotAuthenticated** with `isSignOut == true`: `realtime.unsubscribeAll()` → `store.clearAll()` → remove `KEY_PREV_USER`.
On Initializing / RefreshFailure: no action.

`signOutAndUnregister()`: **drain the outbox first** (bounded 5s timeout, guarded on live user) so un-flushed edits aren't lost when `clearAll()` wipes the outbox; then unregister this device's push token *while the JWT is still valid* (RLS), then `auth.signOut()`.

`syncNow()` (background job entry): no-op if signed out; else `flush → hydrate → pullCalendar` (best-effort).

`refreshCollections()`: re-pull collections+membership (after the owner shares/unshares, since a member's own `collection_members` channel doesn't fire for the owner-initiated row).

### 1.8 Edge cases to replicate exactly
- **Capture during a session**: capture's `session_id` FK points at a `sessions` row written only at session end → `dependsOn = sessionId`. If the session is abandoned, orphan-drop removes the capture op when the session op poisons.
- **Schedule-then-immediately-delete a block offline**: the upsert (held back by `dependsOn`) must not flush after the delete → `cancelPendingUpserts` in delete path.
- **Same-user re-auth (SIGNED_IN re-emit)**: must NOT wipe cache — would drop pending offline edits + the live focus session. `shouldWipeCache` returns false when `prev == current`.
- **User switch mid-flush**: `currentUserId()` guard bails the loop.
- **One bad realtime row**: skipped, stream survives.
- **A table fetch fails during hydrate**: that table keeps its local rows; others replace.
- **External (`g_`) blocks**: never pushed, always preserved across hydrate, never realtime-broadcast to our table.
- **Google all-day events** (date-only start, no `T`): skipped in pull (would collapse to 15-min slivers).
- **Events we pushed ourselves** (own `externalEventId`): filtered out of the pull so we don't get a duplicate `g_` block beside the task block.

---

## 2. Data — models + Supabase tables/columns

### 2.1 Domain models (camelCase; `core/.../model/Models.kt`)
Reimplement as Swift `Codable` structs/enums. Key models for sync (full fields in Models.kt):

- **TaskItem**: `id, name, estimateMin, totalFocused=0, done=false, priority?, tags:[String]?, objectives:[Objective]?, comments:[Comment]?, intentWhen?, intentThen?, lifeArea?, firstPhysicalAction?, moveCount:Int?, completedAt?, later:Bool?, recurrence?, sourceCollectionId?, sourceItemId?, dueAt?, createdAt, updatedAt`.
- **Session**: `id, taskId?, taskName, tags?, estimateMin?, actualSec, completedAt`.
- **CalBlock**: `id, taskId?, taskName, startTime("HH:MM"), durationMinutes, date("YYYY-MM-DD"), externalEventId?, externalConnectionId?, kind:CalBlockKind?`.
- **ReasonLog**: `id, taskId?, reason, action:ReasonAction, at, durationSec?`.
- **Capture**: `id, taskId?, sessionId?, tag:CaptureTag, body, at`.
- **ItemCollection**: `id, name, color, subtitle?, items:[CollectionItem], sortOrder, archived?` + **client-only** `ownerId?, members:[String]=[], myRole?` (never written back).
- **TagRow**: `id, name, color?, sortOrder`. **LifeArea**: `id, name, color, sortOrder`.
- **CalendarConnection**: `id, provider, accountEmail, displayName, selectedCalendarIds:[String], colorSlot, lastSyncCursor?, connectedAt` (note: `credentials` bytea exists server-side but is NEVER fetched/decoded client-side).
- **LiveSession** (device-local, never synced to Postgres): `id?, taskId, sessionStart:Long?, paused, pausedAt:Long?, sessionEstimateMin, nudge80Fired, overrunPromptFired, treatment, priorAccumulatedSec?`.
- **Recurrence** (tagged-union JSONB `{kind, daysOfWeek?, until?}`): Daily / Weekly(daysOfWeek 0=Sun…6=Sat) / Monthly; `until` YYYY-MM-DD inclusive. **Custom Codable** — see §4.

### 2.2 Local persistence shape (`:data`)
Three logical local tables (Android = Room; iOS = GRDB or a JSON store — see §5):
- **records** — composite PK `(tableName, id)`, columns `tableName, id, data (JSON blob of the domain model), updatedAt?`. One row per synced entity.
- **outbox** — `seq` (autoincrement PK, FIFO ordering), `op ("upsert"|"delete"), recordTable, recordId, payload (String?), dependsOn (String?), createdAt (epoch ms)`.
- **live_session** — single row `id=0`, `data` (LiveSession JSON).

`LocalStore` API to reproduce (Swift, `Combine`/`AsyncSequence` for reactive reads):
`tasks()/blocks()/sessions()/captures()/reasonLogs()/collections()/tags()/lifeAreas()/connections()` → reactive `[Model]`; `snapshot(table)`; `upsert(table,model,id,updatedAt?)`; `delete(table,id)`; `replace(table,items,id,updatedAt?,preservePrefix?)`; `clearAll()`; `enqueue/pending/dequeue/pendingCount`; `liveSession()/getLiveSession()/setLiveSession`.

**`Tables` logical names = Supabase table names** (pass-through): `tasks, cal_blocks, sessions, captures, reason_logs, collections, tags, life_areas, calendar_connections`.

### 2.3 Supabase tables/columns (PostgREST row shape — `DbRowCodec`)
Top-level columns are **snake_case**; nested JSONB blobs keep **camelCase**. `user_id` is injected by the gateway, never in the codec payload. Per-table row DTOs (Swift `Codable` with `CodingKeys` for snake_case):

- **tasks**: `id, name, estimate_min, total_focused, done, priority?, tags text[] (default []), objectives jsonb (camelCase), comments jsonb, intent_when?, intent_then?, life_area?, first_physical_action?, move_count (default 0), completed_at?, later (default false), recurrence jsonb?, source_collection_id?, source_item_id?, due_at?, created_at, updated_at`.
- **sessions**: `id, task_id? (uuid-or-null), task_name, tags (default []), estimate_min?, actual_sec, completed_at`.
- **cal_blocks**: `id, task_id? (uuid-or-null), task_name, start_time, duration_minutes, date, external_event_id?, external_connection_id? (uuid-or-null), kind`.
- **captures**: `id, task_id? (uuid-or-null), session_id? (uuid-or-null), tag, body, at`.
- **reason_logs**: `id, task_id? (uuid-or-null), reason, action, at, duration_sec?` — **`duration_sec` omitted from payload when nil** (see §4).
- **collections**: `id, name, color, subtitle ("" when nil), items jsonb (camelCase), sort_order, archived (default false)`. `ownerId` is decoded from the raw row's `user_id` and never re-encoded.
- **tags**: `id, name, color?, sort_order`. **life_areas**: `id, name, color, sort_order`.
- **calendar_connections** (decode-only via hydrate): `id, provider, account_email, display_name, selected_calendar_ids (default []), color_slot, last_sync_cursor?, connected_at`. (`credentials` column never selected/decoded.)

DB constraints worth knowing (from migration 001+): `tasks.estimate_min` 1–1440; `cal_blocks.duration_minutes` 5–1440 (server) though the model floors Google pulls at 15; `priority ∈ {urgent,high,medium,low}`; `reason_logs.action ∈ {pause,switch}`; `cal_blocks.task_id` nullable after migration 009; RLS `user_id = auth.uid()` on every table; `calendar_connections` realtime disabled (migration 013). Shared-collections add `collection_members(collection_id,user_id,role)` and `collections.user_id` = owner (migrations 020/022/024).

---

## 3. Business rules / pure logic (port + tests)

Port these `core/.../logic` pure helpers to Swift and **replicate their unit tests** (cite the Android test names as the Swift XCTest names):

- **`Uuid.kt`**: `isUuid(s)` (regex `^[0-9a-fA-F]{8}-…-{12}$`), `uuidOrNull(s?)`, `newUuid()`. FK columns drop to `null` when not a valid UUID — this is why placeholder/`g_`/`cal-` ids serialize their FK as JSON null.
- **`CalBlockKind.kt`**: `blockKind(b)` (explicit `kind` wins; else EXTERNAL if `externalEventId` set; else PLACEHOLDER if `taskId=="placeholder"`; else EXTERNAL if `taskId` starts `cal-`; else TASK). `isExternalBlock`, `isTaskBlock`, `isPlaceholderBlock`.
- **`GoogleSyncMapping.kt`** (covered mainly by the Calendar spec, but the sync engine calls it): `externalEventToBlock` (id = `g_<googleId>`, local HH:MM + YYYY-MM-DD anchored to device zone, duration floored at 15), `blockToIsoRange` (date+HH:MM → UTC ISO ms). Tested in `core/.../GoogleSyncMappingTest.kt`.
- **`SyncDecision`** (the engine's own pure logic — **port + test verbatim**, `SyncDecisionTest.kt`):
  - `shouldWipeCache(event, prevUserId?, currentUserId)`: SIGNED_IN/INITIAL_SESSION → wipe iff `prev != current`; USER_UPDATED → never. Tests: same-user re-auth does NOT wipe (`prev=="u1",cur=="u1"` → false); first sign-in (`prev==nil`) → true; user switch (`u1`→`u2`) → true; USER_UPDATED always false.
  - `mergeHydratedCalBlocks(remote, localExternal)`: LinkedHashMap by id; insert local externals first (only if `isExternalBlock`), then remote (remote **wins on id clash**); return values. Tests: preserves `g_` block alongside remote; drops local non-external + remote wins on clash.

Also replicate **`DbRowCodecTest.kt`** (the PostgREST boundary contract) and **`LocalStoreTest.kt`** (Room round-trips, replace-preserves-`g_`, outbox FIFO, live-session single row, clearAll). These are the regression net for the whole engine.

---

## 4. Gotchas (replicate exactly — these are the bug sources)

1. **kotlinx default-omission / explicit nulls (CRITICAL).** The row codec uses `explicitNulls = true, encodeDefaults = true`. **Nullable optionals serialize as explicit JSON `null`** so an upsert *clears* a removed column (e.g. un-completing a task sends `completed_at: null` to clear it). In Swift, `JSONEncoder` **omits `nil` optionals by default** — you MUST emit explicit `null`. Build row payloads as a dictionary/`JSONObject` where absent optionals become `NSNull`/`.null`, OR use a custom encoding that writes nulls. Verify with the `explicitNullClearsCompletedAt` test: `completed_at` key present and `== null`.
   - **Lone exception**: `reason_logs.duration_sec` is **omitted (key absent) when nil** so an upsert never clobbers a server-set value. Strip it post-encode. Test `reasonLogOmitsDurationSecWhenNull`.

2. **snake_case top-level, camelCase JSONB.** No global key-conversion strategy. Top-level columns are explicit snake_case (`estimate_min`, `created_at`); nested JSONB blobs (`recurrence.daysOfWeek`, `objectives[].text`, `items[]`) **must stay camelCase** — that IS the server's stored shape. In Swift, do NOT apply `.convertToSnakeCase` globally; use per-key `CodingKeys` at the top level and leave nested types camelCase. Test `nestedJsonbStaysCamelCase` (`daysOfWeek` must survive) + `topLevelSnakeCaseColumns`.

3. **FK uuid-or-null.** Any FK string that isn't a valid UUID (`placeholder`, `g_…`, `cal-…`) must encode as JSON `null`, not the string (else FK violation, poison op). Tests `calBlockNonUuidTaskIdDropsToNull` / `…Preserved`.

4. **UTC dates.** Timestamps round-trip as UTC ISO-8601 with milliseconds (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`, matching JS `toISOString()`). Google `events.list` needs **RFC3339 instants** for `timeMin/timeMax` (a bare `YYYY-MM-DD` is rejected 400 → silent zero events): the coordinator sends full instants but reconciles locally with date-only bounds (`[-7d, +30d]`). Block date+HH:MM → instant is anchored in the **device's local zone** (like `new Date(...).toISOString()`), then formatted UTC. Use a fixed UTC `ISO8601`/`DateFormatter` with milliseconds; do not let the default formatter drop `.SSS`.

5. **LWW** — see §1.4. Two mechanisms: unconditional local-store replace (newest write wins, no timestamp compare) + outbox `blockedRows` per-row ordering (last *enqueued* state converges, applied in `seq` order). Don't add a timestamp guard the Android code doesn't have.

6. **Dependency ordering + poison/orphan drop.** `dependsOn` holds a child op until the parent's op clears the queue. `FAIL_CAP = 5` per-`seq`, **in-memory, resets on restart**. Poison-drop also drops dependents (orphan-drop) keyed by `dependsOn == poison.recordId`. Getting this wrong wedges the whole outbox forever (the historical Android "poison-pill outbox" bug — now fixed; do not regress).

7. **External `g_` blocks are sacred.** Never push (kind==EXTERNAL or id startsWith `g_`), never realtime-mirror to our table, always preserve across hydrate. Conversely, unsynced optimistic TASK blocks with a pending outbox upsert must be preserved across hydrate (`localPending`) or they flicker off the UI.

8. **calendar_connections never broadcast.** Don't subscribe it to realtime (encrypted creds). Hydrate-only.

9. **Cache-wipe only on real user change.** A SIGNED_IN re-emit for the same user must not `clearAll()` — it would drop pending offline edits and the live focus session before the outbox flushes. Persist `prevUserId` across launches.

10. **Drain before sign-out / before clearAll.** `signOutAndUnregister` flushes (bounded 5s) before sign-out because the NotAuthenticated branch wipes the outbox. Push-token unregister must happen while the JWT is still valid.

11. **Exact-alarm denial (notifications area, noted here for sync completeness):** Android schedules exact reminders via `AlarmManager` and degrades gracefully when `SCHEDULE_EXACT_ALARM` is denied. On iOS this maps to `UNUserNotificationCenter` time-interval/calendar triggers, which have no exact-alarm permission concept but are **best-effort delivery** (the system may defer). The sync engine itself doesn't schedule alarms; just don't assume guaranteed-exact background wake.

12. **Per-event realtime guard.** One un-decodable row must not kill the table's stream. Wrap each event in a `do/catch`-equivalent and continue.

13. **collections client-only fields.** `members/myRole/ownerId` are reconstructed on hydrate and merged on realtime; they are NEVER in the upsert payload (the `CollectionRow` codec omits them). `subtitle` encodes `""` for nil and decodes `""` → nil (test `collectionSubtitleEmptyBecomesNull`).

---

## 5. iOS equivalents (mapping)

| Android | iOS |
|---|---|
| Compose / reactive `Flow` reads | SwiftUI + `@Observable`/`ObservableObject`; expose stores as Combine `Publisher`s or `AsyncStream`s feeding `@Published` arrays. Views observe the local store, never the network. |
| **Room** (`records`/`outbox`/`live_session` tables, `@Upsert`, `@Query` Flows, `@Transaction replaceTable`) | **GRDB** is the recommended target: define the three tables, use `INSERT … ON CONFLICT(tableName,id) DO UPDATE` for the unconditional upsert, `ValueObservation` for reactive reads, and a DB transaction for replace-per-table. A pure JSON-file store is acceptable for v1 but GRDB gives you the reactive observation + transactional replace for free. `replaceTable(table, rows, preservePrefix?)` = within a transaction, delete `WHERE tableName=? AND (preservePrefix==nil OR id NOT LIKE 'prefix%')` then upsert rows. |
| **WorkManager** periodic `SyncWorker` (30 min, network-constrained; `syncNow()` + widget refresh) | **BGTaskScheduler**: register a `BGAppRefreshTask` (and optionally `BGProcessingTask` with `requiresNetworkConnectivity = true`). In the handler call `coordinator.syncNow()` then refresh the widget; reschedule the next task. Note iOS gives no guaranteed 30-min cadence — best-effort. Also trigger `syncNow()` on `scenePhase` → `.active`. |
| **Glance** Start-Next app widget update in the worker | **WidgetKit**: write the Start-Next snapshot to a shared App Group container, then `WidgetCenter.shared.reloadTimelines(ofKind:)`. (`pickStartNext` is a `:core` pure fn — port + reuse.) |
| **FCM** (push tokens, `push.unregister(deviceId)`) | **APNs** via `UNUserNotificationCenter` registration; device token → the same `push_device_tokens` table with `platform='ios'`. Unregister this device's token before sign-out (RLS, JWT still valid), same as Android. `thisDeviceId()` (ANDROID_ID) → `identifierForVendor` (UUID, stored once; or a stable keychain id). |
| **Foreground service** (Android focus-session keep-alive) | **iOS has no equivalent.** A focus session can't hold an indefinite foreground service. Persist the live session to the local `live_session` row and recompute elapsed time from `sessionStart`/`pausedAt`/`priorAccumulatedSec` on resume; use `UNUserNotificationCenter` for the 80%/overrun nudges and (optionally) background audio / `beginBackgroundTask` for short tails. The sync engine is unaffected — `LiveSession` is device-local and never synced. |
| **AlarmManager** exact alarms | **UNUserNotificationCenter** calendar/time-interval triggers (best-effort; no exact-alarm permission). |
| **supabase-kt** (`SupabaseClient`, Auth PKCE + `unstuck://auth-callback`, Postgrest `from().upsert{onConflict="id"}` / `.select().decodeList<JsonObject>()` / `.delete{filter}`, Realtime `postgresChangeFlow`, Functions `.invoke`) | **supabase-swift**: `SupabaseClient` with `auth.flowType = .pkce` and the `unstuck://` redirect scheme; `from(table).upsert(payload, onConflict: "id")`, `from(table).select().execute()` decoded to `[ [String: AnyJSON] ]`, `from(table).delete().eq("id", id)`; `channel(...).postgresChange(...)` async stream for realtime; `functions.invoke("account-delete")`. Keep payloads as `AnyJSON`/dictionary so explicit-null semantics survive (mirror `SyncGateway` injecting `user_id`). |
| kotlinx-serialization `Json{explicitNulls,encodeDefaults,ignoreUnknownKeys,isLenient}` | A custom JSON layer. `ignoreUnknownKeys` → tolerate extra server columns (`user_id`, server-only fields) on decode (don't use a strict decoder that throws on unknown keys; or decode through `AnyJSON`/dictionary). `explicitNulls`/`encodeDefaults` → emit nulls and defaults (see §4.1). `RecurrenceSerializer` → a custom `Codable` for the `{kind,daysOfWeek?,until?}` tagged union. |
| `SyncCoordinator` observing `client.auth.sessionStatus` | Observe supabase-swift `auth.authStateChanges` / `AuthChangeEvent`. Map `.signedIn`/`.initialSession`/`.userUpdated`/`.signedOut` to `SyncAuthEvent` + the wipe/flush/hydrate/subscribe pipeline. Persist `prevUserId` in `UserDefaults` (Android `SharedPreferences`). |
| `println("[outbox]…")` diagnostics | `os.Logger` (subsystem `tech.csalliance.unstuck`, categories `outbox`/`hydrate`/`realtime`). Keep the same log points (poison drop, orphan drop, per-table hydrate failure, skipped realtime event) — they're the field-debug breadcrumbs. |

### 5.1 Concurrency
Android uses structured coroutines on an injected `CoroutineScope`. iOS: a single actor (e.g. `actor SyncEngine`) or `@MainActor` view models + detached `Task`s for flush/hydrate. The flush loop must be **serialized** (no two concurrent drains) — guard with an actor or an in-flight flag. Realtime streams run as long-lived `Task`s stored for cancellation in `unsubscribeAll()`.

### 5.2 Suggested Swift module layout (mirror Android)
`SyncEngine/` → `WriteThrough.swift`, `OutboxFlusher.swift`, `Hydrator.swift`, `RealtimeMirror.swift`, `SyncCoordinator.swift`, `SyncDecision.swift`, `SyncGateway.swift`, `DbRowCodec.swift`, `AuthService.swift`, `SupabaseClientProvider.swift`. `LocalStore/` → `LocalStore.swift` + GRDB schema. `Core/` → `Models.swift`, `Uuid.swift`, `CalBlockKind.swift`, `GoogleSyncMapping.swift`. Tests: `SyncDecisionTests`, `DbRowCodecTests`, `LocalStoreTests` (ported 1:1).

### 5.3 Acceptance / done criteria
- All three ported test files pass (SyncDecision, DbRowCodec, LocalStore round-trips incl. `g_` preservation, outbox FIFO).
- Offline: create/edit/delete tasks, schedule blocks, run a session with a capture → all reflect instantly; on reconnect the outbox drains in FIFO+dependency order with zero FK errors; a forced server-reject op poisons after 5 tries and orphan-drops its dependents without wedging the queue.
- Sign out → sign in *same user* keeps pending edits + live session (no wipe). Sign in *different user* wipes and hydrates the new account.
- cal_blocks: a `g_` external block survives hydrate; an unflushed task block survives hydrate; deleting a just-scheduled offline block does not resurrect it on flush.
- Realtime: a peer device edit appears live; a malformed row doesn't kill the stream; `calendar_connections` is never subscribed.
- `explicit-null` clears columns server-side; `reason_logs.duration_sec` is never clobbered when nil; nested `recurrence.daysOfWeek` stays camelCase.