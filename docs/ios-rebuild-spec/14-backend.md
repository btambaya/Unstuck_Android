# Unstuck iOS Rebuild Spec — Area: Backend (Supabase)

**Status:** Reference spec for the SwiftUI rebuild. The Android client is the behavioral reference; the Supabase project is the **shared source of truth** for every client (Web, Android, iOS). This document tells an iOS engineer exactly what the backend exposes, the precise wire shapes to send/receive, the business rules that govern those shapes, the gotchas that have already cost the Android/Web teams bugs, and the Swift equivalents to use.

**Critical framing:** The backend is *not* being rebuilt. It already exists, is deployed, and serves Web + Android in production. The iOS app must speak to it **byte-for-byte identically** to how Android does. Any deviation in JSON key casing, null semantics, enum strings, or dependency ordering is a sync bug. Treat the Android `:sync` and `:core` Kotlin as the canonical client contract and port it 1:1 to Swift (the Kotlin comments repeatedly reference "the iOS X.swift" — those files are what you are recreating from scratch).

---

## 1. What the backend is — architecture & surfaces

The backend is one Supabase project (project ref `uaxfteluwctrlgwmmfzi`). It comprises:

1. **Postgres schema** — 27 migrations (`001`–`027`), normalized one-table-per-entity, every user-owned table RLS-locked to `auth.uid()`.
2. **PostgREST** — the auto-generated REST CRUD API over those tables. This is the client's primary read/write path (`supabase-swift`'s `.from(table)`).
3. **Realtime** — Postgres logical-replication broadcast on a publication (`supabase_realtime`). Clients subscribe per table for cross-device live sync.
4. **Edge Functions** (Deno/TypeScript) — 11 functions for operations that can't be plain CRUD: calendar OAuth + Google API proxy, push-notification senders, account deletion, collection sharing, login tracking, feedback is plain CRUD.
5. **pg_cron + pg_net jobs** — three scheduled jobs (manual SQL, not migrations): wake-window calibration, morning-brief dispatch, collection-late escalation.
6. **Auth** — Supabase GoTrue (email/password, magic link, Google OAuth). A signup trigger (`handle_new_user`) seeds per-user rows.

There is **no app-server**; the iOS client talks directly to PostgREST/Realtime/Edge Functions using the anon key + the user's JWT. RLS is the security boundary.

### The client's job (what iOS must replicate)
The iOS client is an **offline-first, optimistic, last-write-wins replica** of the user's slice of the database:

- **Local store** mirrors every synced table (Android: Room; iOS: GRDB or a JSON blob store — see §5).
- **Write-through**: every mutation writes locally first (instant UI) then enqueues a server **outbox** op.
- **Outbox flusher** drains ops to PostgREST FIFO, honoring **dependency ordering** and **poison-pill dropping**.
- **Hydrator** pulls every table on sign-in and replaces the local cache (server-canonical).
- **Realtime mirror** applies live INSERT/UPDATE/DELETE from other devices.
- **Sync coordinator** observes auth state and drives wipe/flush/hydrate/subscribe.

Port files (Android `sync/` → iOS Swift): `SyncCoordinator`, `SyncDecision`, `WriteThrough`, `OutboxFlusher`, `Hydrator`, `RealtimeMirror`, `SyncGateway`, `DbRowCodec`, plus the typed clients (`CalendarClient`, `PushClient`, `NotificationsClient`, `PreferencesClient`, `CollectionShareClient`, `FeedbackClient`, `LoginTrackerClient`, `AuthService`).

---

## 2. Data model — tables, columns, and the exact wire shape

Every synced row is stored locally as a **camelCase** domain model; the snake_case PostgREST mapping lives in one place (`DbRowCodec`). The rule from the codec header is load-bearing:

- **Top-level columns are `snake_case`** (`estimate_min`, `total_focused`, `created_at`).
- **JSONB blob internals stay `camelCase`** (`recurrence.daysOfWeek`, `objectives[].text`, `comments[].at`, `collection.items[].dueAt`). No global snake_case strategy is applied — the nested types serialize with their own camelCase names.
- **`user_id` is NEVER in the encoded payload** — the gateway injects it on every write (`payload = { ...row, user_id }`).

### 2.1 `tasks`
Columns (migrations 001, 003, 005, 008, 025):

| column | type | notes |
|---|---|---|
| `id` | uuid PK | client-generated UUID v4 |
| `user_id` | uuid FK→auth.users | injected by gateway, cascade delete |
| `name` | text not null | |
| `estimate_min` | int default 25, **CHECK 1..1440** | |
| `total_focused` | int default 0, CHECK ≥0 | accumulated focus seconds |
| `done` | bool default false | |
| `priority` | text CHECK in (`urgent`,`high`,`medium`,`low`) default `medium` | nullable in model |
| `tags` | text[] default `{}` | denormalized; canonical list in `tags` table |
| `objectives` | jsonb default `[]` | `[{text, done?, minutes?}]` |
| `comments` | jsonb default `[]` | `[{text, at?}]` |
| `intent_when`, `intent_then` | text | implementation-intention |
| `life_area` | text | matched by name against `life_areas` |
| `first_physical_action` | text | |
| `move_count` | int default 0, CHECK ≥0 | slip detector |
| `completed_at` | timestamptz | set when first marked done |
| `later` | bool default false | deferred flag |
| `recurrence` | jsonb | `{kind, daysOfWeek?, until?}` or null |
| `source_collection_id` | uuid | promote back-link |
| `source_item_id` | text | promote back-link |
| `due_at` | timestamptz | accountability "by" time |
| `late_nudged` | bool default false | **server-owned by the cron — client NEVER writes it** |
| `created_at`, `updated_at` | timestamptz | `updated_at` bumped by `touch_updated_at` trigger |

Swift `TaskItem` mirrors `core/model/Models.kt` exactly. The wire DTO (`TaskRow`) must:
- Serialize `move_count ?? 0`, `later ?? false`, `tags ?? []`, `objectives ?? []`, `comments ?? []` as the Android defaults.
- Serialize `completed_at` as **explicit JSON `null`** when un-completed (so an upsert *clears* the column). See gotcha §4.1.
- Drop `task_id`-style FK fields to null when not a valid UUID (only relevant to other tables — tasks `id` is always a UUID).

### 2.2 `sessions` (completed focus sessions)
`id, user_id, task_id (FK→tasks ON DELETE SET NULL), task_name (denormalized — survives task delete), tags text[], estimate_min int?, actual_sec int CHECK≥0, completed_at timestamptz`. Wire DTO `SessionRow`: `task_id` is `uuidOrNull(taskId)`.

### 2.3 `cal_blocks` (scheduled focus blocks)
`id, user_id, task_id (FK→tasks ON DELETE CASCADE, **nullable** since migration 009), task_name, start_time text 'HH:MM', duration_minutes int CHECK 5..1440, date date 'YYYY-MM-DD', external_event_id text, external_connection_id uuid, kind text CHECK in (task,placeholder,external) default 'task'`.

- `kind` (migration 006) replaces a string-prefix heuristic. Resolve client-side via `blockKind()` fallback: explicit `kind` → else `external_event_id` present → external → else `task_id=="placeholder"` → placeholder → else `task_id` starts `cal-` → external → else task.
- **External Google blocks have ids `g_<googleEventId>`** which are NOT UUIDs and never round-trip to Postgres. They live only on-device. Never enqueue an outbox op for a `g_` block or a `kind==external` block.

### 2.4 `captures`
`id, user_id, task_id (FK SET NULL), session_id (FK→sessions SET NULL), tag text CHECK in (follow-up,idea,edit,question,distraction), body text CHECK length 1..4096, at timestamptz, created_at`. A capture taken during a session references `session_id` whose `sessions` row is only written at session end → **outbox dependsOn = session_id** (see §3.3).

### 2.5 `reason_logs`
`id, user_id, task_id, reason text, action text CHECK in (pause,switch), at timestamptz, duration_sec int? CHECK null-or-≥0`. **Special encode rule:** `duration_sec` is *omitted* (stripped post-encode) when null so an upsert never clobbers a server-set value — this is the lone exception to explicit-nulls (§4.1).

### 2.6 `collections` + items + sharing
`collections`: `id, user_id (owner), name, color default 'indigo', subtitle default '', items jsonb default '[]', sort_order int, archived bool default false (migration 026), created_at, updated_at`.

`items` JSONB element shape (camelCase): `{id, body, pinned?, done?, at, promoted?, assignee?, promotedDone?, dueAt?}`.

Shared-collection machinery:
- `collection_members(collection_id, user_id, role text CHECK in (editor,viewer) default editor, PK(collection_id,user_id))`.
- `collection_invites(collection_id, email, role, invited_by, unique(collection_id,email))` — RLS-on with **no client policies**; only the edge function (service role) and the SECURITY DEFINER claim function touch it.
- Client-only fields on the model (`ownerId`, `members[]`, `myRole`, `archived`) are **populated by the Hydrator** from `collections.user_id` + `collection_members` and **never written back** (the `CollectionRow` codec drops them).

### 2.7 `tags` / `life_areas`
`tags(id, user_id, name, sort_order, color?, unique(user_id,name))`. `life_areas(id, user_id, name, color default 'indigo', sort_order, unique(user_id,name))` — seeded on signup with the 5 canonical areas (Work/Personal/Volunteering/Home/Health).

### 2.8 `calendar_connections` (credentials live here, never client-readable)
`id, user_id, provider CHECK in (google,apple,microsoft), account_email, display_name, credentials bytea (AES-256-GCM encrypted), selected_calendar_ids text[] default {primary}, color_slot int 0..5, last_sync_cursor timestamptz, connected_at, unique(user_id,provider,account_email)`.

- **`credentials` is never returned to the client** — the calendar-sync `/connections` endpoint strips it server-side, and the table was **removed from Realtime** (migration 013) so the encrypted blob can't leak over the channel.
- The client decodes the *credential-stripped* snake_case row shape (`ConnRow` in `CalendarClient`).

### 2.9 Notification/device tables (client writes only its own subset)
- `device_tokens(id, user_id, device_id, platform default 'ios', apns_token, apns_environment default 'production', fcm_token, live_activity_push_to_start_token, timezone default 'UTC' (IANA), is_primary, last_active_at, created_at, updated_at, unique(user_id,device_id))` — written via the `register-push-token` edge function, **not** PostgREST. NOT in Realtime (tokens are credentials).
- `live_activity_tokens(...)` — per Live Activity update token (iOS Live Activities). One row per running activity, `unique(user_id, activity_id)`. **This is iOS-only and relevant to you** — see §6.
- `notification_preferences` (PK user_id) — seeded on signup. Toggles + wake-window + quiet hours + `daily_push_cap` default 3 + `paused_checkin_muted_until` + `timezone`. Client mirrors a 2-column subset via `PreferencesClient.setNotificationLevel`.
- `notification_ledger` (hard daily cap audit) and `notification_queue` (in-app recap cards + audit). Written server-side; client *reads* the queue for the in-app notification stream.
- `wake_window_history` — first-input-of-day rows the calibration cron medians.

### 2.10 Other
- `feedback` (migration 027) — one-way submissions, owner-RLS, **not** Realtime, immutable. Plain PostgREST insert.
- `login_events` (migration 021) — RLS-on, **no client policies**; only `track-login` (service role) writes.
- Phase-9 collab tables (`trusted_circle`, `task_shares`, `body_double_sessions`, `coach_questions`, `notifications`, `feature_signals`) exist in schema and are in Realtime, but are **not exercised by the current Android client** — do not implement unless a feature spec requires them.

### 2.11 Realtime publication membership (what to subscribe to)
**In** the publication: `user_preferences, tasks, sessions, cal_blocks, reason_logs, captures, life_areas, tags, collections, collection_members`, plus the dormant Phase-9 tables.
**Out** (deliberately): `calendar_connections` (migration 013), `device_tokens`, `live_activity_tokens`, `notification_*`, `login_events`, `feedback`, `collection_invites`. Do not attempt to subscribe to those — you won't get events and the encrypted creds must never broadcast.

---

## 3. Business rules / sync logic (the client contract)

These are pure, unit-tested rules. Port them and port the tests (`DbRowCodecTest`, `SyncDecisionTest` → Swift XCTest).

### 3.1 Cache-wipe rule (`SyncDecision.shouldWipeCache`)
On an auth event, wipe the local cache **only when the user actually changed**:
- `SIGNED_IN` → wipe iff `prevUserId != currentUserId`.
- `INITIAL_SESSION` (session restored from storage) → wipe iff user changed.
- `USER_UPDATED` (metadata change, same user) → **never** wipe.

Rationale (do not skip): a `SIGNED_IN` re-emit for the same user must NOT clobber pending offline edits + the live focus session before the outbox flushes. `prevUserId` is persisted (Android: SharedPreferences; iOS: UserDefaults).

Map Supabase auth session sources to events:
- SignIn / SignUp / External(OAuth) → `SIGNED_IN`
- Storage (restored) → `INITIAL_SESSION`
- UserChanged / UserIdentitiesChanged → `USER_UPDATED`
- Refresh / Unknown → no cache action

### 3.2 Sign-in orchestration order (`SyncCoordinator.handle`)
On Authenticated: resolve event → `if shouldWipeCache: store.clearAll()` → persist prevUserId → **`flusher.flush(uid)` (push offline edits first)** → `hydrator.hydrate(uid)` (pull server-canonical) → `realtime.subscribeAll(uid)` → `pullCalendar()` (best-effort) → `maybeTrackLogin(uid)` (throttled 12h).

On NotAuthenticated && isSignOut: `realtime.unsubscribeAll()` → `store.clearAll()` → clear prevUserId.

**Order matters:** flush before hydrate (hydrate replaces tables; an unflushed local row not yet on the server would otherwise vanish). The flush is **guarded on the live user id** so a sign-out+switch mid-flush can't stamp queued ops with the prior user.

### 3.3 Outbox dependency ordering (`WriteThrough` + `OutboxFlusher`)
Every write: optimistic local upsert + enqueue `OutboxEntity(op, recordTable, recordId, payload(JSON), dependsOn?, createdAt)`.

`dependsOn` wiring:
- `cal_blocks` upsert → `dependsOn = task_id` (if a UUID) so the parent task flushes first (FK).
- `captures` upsert → `dependsOn = session_id` (if a UUID) so the session row exists first.

Flusher algorithm (FIFO by seq):
1. Bail if `currentUserId() != userId` (mid-drain user switch).
2. Hold an op while its `dependsOn` rowId still has a pending op.
3. Once an op for a row fails this pass, **skip that row's later ops** (preserve per-row order / LWW).
4. **Poison-pill cap = 5**: after 5 consecutive failures, drop the op AND drop any ops that `dependsOn` it (their FK parent will never exist). `failCounts` resets on app restart (transient failures still retry).
5. If no op progressed this pass, stop and retry on next reconnect/sign-in.

Delete safety: deleting a row **cancels any still-queued upsert** for it first (`cancelPendingUpserts`) — a held-back `cal_block` upsert (dependsOn=task) could otherwise flush *after* the no-dependency delete and resurrect the row server-side.

### 3.4 Gateway write shape (`SyncGateway`)
- Read: `from(table).select(*).decodeList()` — RLS auto-scopes.
- Upsert: `from(table).upsert(row + {user_id}) { onConflict = "id" }`.
- Delete: `from(table).delete { eq("id", id) }`.

### 3.5 Hydrator (`Hydrator`)
Per-table error isolation: a table whose fetch fails is left intact locally (`if (res.ok) replace(...)`). One bad table never wipes another.

`cal_blocks` hydrate merge (`SyncDecision.mergeHydratedCalBlocks`): server set is canonical, but preserve locally-cached external (`g_`) blocks (id-collision → remote wins) AND preserve unsynced optimistic TASK blocks that have a pending outbox upsert (in neither remote nor localExternal — would otherwise flicker off until next flush).

`collections` hydrate: fetch `collections` (RLS returns own + shared-with-me) + `collection_members`, then enrich each with `members[]` and `myRole` (`"owner"` if `ownerId==me` else the member row's role). Call this standalone when a `collection_members` realtime event fires (the owner's share doesn't fire the member's own `collections` channel reliably).

### 3.6 Realtime mirror (`RealtimeMirror`)
One channel per table, filtered `user_id = eq(uid)` (RLS already enforces; the filter is client safety). On INSERT/UPDATE → decode + local upsert; on DELETE → remove by `oldRecord.id`.

- **`collections` channel: NO user_id filter** (shared rows are owned by someone else; rely on RLS for delivery). On upsert, **preserve the client-only `members`/`myRole`** from the existing local row (the incoming row carries neither).
- **`collection_members` channel (filtered user_id=me):** any event → re-hydrate collections (don't mirror the row directly).
- **Per-event guard:** wrap each event handler in a try/catch and *skip* an undecodable row — one bad row (new column/enum, null in a required field) must NOT throw out and permanently kill that table's live stream.
- **Do NOT subscribe `calendar_connections`** (encrypted creds).

### 3.7 `pickStartNext` / morning-brief ranking (must agree across clients)
Exclude `done` + `later` + currently-focused, honor area filter, then rank **priority desc → estimate_min asc → created_at asc** (ISO strings sort lexicographically = chronologically). The morning-brief edge function reimplements this exact ranker in Deno (`topTasks`) — the iOS `pickStartNext` must produce the same order so the brief's "Start with X" matches the in-app NEXT badge. Rank map: urgent=4, high=3, medium=2, low=1; null priority → low.

### 3.8 Recurrence materialization
Client materializes `cal_blocks` `RECURRENCE_HORIZON_DAYS = 56` (8 weeks) ahead. `{kind:'daily'}` / `{kind:'weekly', daysOfWeek:[0=Sun..6=Sat]}` / `{kind:'monthly'}` (+ optional `until` YYYY-MM-DD inclusive). Monthly clamps the start day to each month's length (29/30/31 → last day of Feb etc.). Past occurrences preserved; future regenerated on edit.

---

## 4. Gotchas (already-bitten landmines — replicate the fixes)

### 4.1 kotlinx default-omission → Swift `Codable` must send required fields
The single most recurring Android bug. `kotlinx.serialization` with `encodeDefaults = false` **omits default-valued fields from JSON**, so a defaulted `platform = "android"` would be dropped and the server falls through to its `'ios'` branch — mislabeling the device row so FCM never routes. The Android fix: every wire field that the server reads is **required and set explicitly** (no defaults) in `RegisterBody`, `TrackBody`, `AuthorizeBody`, `FeedbackClient.Row`, etc.

**Swift equivalent:** Swift `Codable` does the *opposite* by default (it always encodes non-nil fields), so you're mostly safe — BUT:
- For **optional** fields, decide explicit-null vs omit per the rules below. Swift omits `nil` optionals by default; where the server contract needs an explicit `null` (to clear a column), you must use a wrapper or a custom `encode(to:)` that writes `encodeNil`.
- For the **row DTOs** (`TaskRow` et al.), the contract is **`explicitNulls = true, encodeDefaults = true`** (rowJson). i.e. nullable optionals MUST serialize as explicit `null` so an upsert clears a removed field. Implement `DbRowCodec` row encoders with custom `encode(to:)` that always `encodeNil(forKey:)` for nil values — except `reason_logs.duration_sec`, which is **stripped when null** (post-encode) to avoid clobbering a server value.
- For **function-call bodies** (register-push-token, track-login, calendar authorize), every server-read field must appear. With Swift `Codable` and non-optional Swift properties this is automatic, but never make them optional-with-default.

### 4.2 UTC / timezone correctness
- `cal_blocks` store **local** `date` (YYYY-MM-DD) + `start_time` (HH:MM) with no zone. When pushing a block to Google or computing ranges, anchor in the device's local zone then convert to **UTC ISO with milliseconds** (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`, matching web `toISOString()`).
- Google `events.list` **requires RFC3339 instants** for timeMin/timeMax — a bare `YYYY-MM-DD` is rejected (400) and silently yields zero events. Send full instants; reconcile locally against date-only bounds. Pull window is `[today-7d, today+30d]`.
- All notification crons run in **UTC** (pg_cron) but pivot on each user's **IANA `timezone`** via `now() at time zone tz` (DST-correct). The iOS client must send its real IANA timezone (`TimeZone.current.identifier`) on `register-push-token` — it's the pivot for morning-brief timing.
- The edge functions compute "today" as `new Date().toLocaleDateString('en-CA', {timeZone: tz})` (yields `YYYY-MM-DD`) for the daily push cap. The client never computes this; just send the right tz.

### 4.3 Last-write-wins (LWW)
There is **no vector clock or merge** — the system is LWW per row. The server `updated_at` trigger bumps on every UPDATE; the *last upsert to land wins*. The flusher preserves per-row op order (skip a row's later ops after a failure) so an older edit can't retry-clobber a newer one. The atomic collection-item RPCs (§4.7) are the one exception that avoids whole-row LWW clobber for concurrent shared-list edits.

### 4.4 Dependency ordering & poison pills
Covered in §3.3 — the FK ordering (`cal_block`→task, `capture`→session) and the 5-failure poison-pill drop (with orphaned-dependent cleanup) are mandatory. Without them a poison op wedges its dependents forever.

### 4.5 `g_` external blocks must never be pushed
External Google blocks (`g_<id>`, `kind==external`) are mirrored read-only. Never enqueue them to `cal_blocks` (their id/shape isn't ours; would fail forever and stall the outbox) and never re-push to Google. Skip them in WriteThrough.

### 4.6 RLS recursion (already fixed server-side — don't re-trigger)
Migrations 020/022 created infinite-recursion RLS (collections policy queried collection_members and vice-versa → Postgres 42P17 for non-owners). Migration 024 fixed it with SECURITY DEFINER helper functions (`is_collection_owner/member/editor`). **You inherit the fix** — just don't write client code that assumes a member can't read a shared collection; they can.

### 4.7 Concurrent shared-list edits use atomic RPCs, not whole-row upsert
For a **shared** collection, item add/update/remove/flag/promote must go through the server-side JSONB RPCs (`collection_add_item`, `collection_update_item`, `collection_remove_item`, `collection_set_item_flag`, `collection_set_item_promotion`) — each mutates the `items` array in **one SQL statement**, RLS-gated (a viewer's call no-ops). Whole-row upserts would clobber a co-editor's concurrent change. Own/unshared lists keep the whole-row outbox path. Also: updating a shared collection's *metadata* (name/color/subtitle/archived) uses a **partial PostgREST UPDATE** (not whole-row upsert) so `items` isn't shipped and can't clobber.

### 4.8 Exact-alarm denial (Android) → iOS notification constraints
Android schedules exact-alarm local notifications (paused-checkin) and must handle exact-alarm permission denial. **On iOS this maps to `UNUserNotificationCenter` + the iOS background-execution constraint:** there is no reliable background timer to observe "paused too long" while backgrounded. The paused-checkin is therefore a **client-scheduled local notification** (`UNTimeIntervalNotificationTrigger`) that, when online, **routes through the `send-paused-checkin` edge function first** purely for cap coordination — the function returns `{allowed}` and the client **suppresses its local notification when `allowed==false`** (cap hit, muted, or disabled). Replicate: schedule the local notif, but gate it on the edge function's allow when network is available.

### 4.9 supabase-kt → supabase-swift parity gotchas
- **ktor Content-Type:** Android had to set `contentType(ContentType.Application.Json)` explicitly on every function `invoke`. In `supabase-swift`, set the body as `Codable` via `FunctionsClient.invoke(_:options:)` with `body:` — ensure the request content-type is JSON.
- **Function path routing:** calendar-sync is sub-routed by URL path (`calendar-sync/authorize`, `/connect`, `/connections`, `/events`, `/events/:id`). Android invokes `"calendar-sync/authorize"` etc. as the function "name." In supabase-swift, invoke with the path appended (the SDK appends to the functions base URL). Query params (`?from=&to=&connectionId=`) for GET `/events` and DELETE `/events/:id`.
- **JWT decode, not getUser:** the edge functions base64-decode the JWT `sub` (lesson from Cairn: `auth.getUser()` round-trips can wedge). You don't control this server-side; just know that `verify_jwt=true` functions need the `Authorization: Bearer <jwt>` header (the SDK attaches it automatically when signed in).

### 4.10 Shared-device push-token leak (single-owner)
`register-push-token` pre-deletes any other user's row holding the same apns/fcm token before upserting yours. The client side: on **sign-out**, delete this device's `device_tokens` row **while the JWT is still valid** (RLS `user_id=auth.uid()`) so the previous user's morning brief can't reach whoever signs in next. Replicate in the iOS sign-out path (`PushClient.unregister` → `from("device_tokens").delete { eq("device_id", thisDeviceId) }`, then `auth.signOut()`). Also drain the outbox (bounded ~5s) before sign-out, since `clearAll()` wipes the outbox.

---

## 5. iOS equivalents — mapping table

| Android / backend concern | iOS equivalent |
|---|---|
| supabase-kt (`io.github.jan.supabase`) | **supabase-swift** (`Supabase` SPM): `PostgrestClient`, `RealtimeV2`, `FunctionsClient`, `AuthClient` |
| Room `records` table (JSON-blob-per-row) + Flows | **GRDB** with a `records` table (tableName, id, json, updatedAt) and `ValueObservation` → Combine/`AsyncStream`; or a Codable JSON store. Keep the blob-per-row shape so `DbRowCodec` decode/encode is identical. |
| `kotlinx.serialization` row DTOs | **`Codable`** structs with `CodingKeys` for snake_case + **custom `encode(to:)`** for explicit-null and the `duration_sec` omit |
| Kotlin coroutines / Flow | Swift **async/await** + `AsyncStream`/Combine |
| WorkManager periodic `syncNow` | **BGTaskScheduler** (`BGAppRefreshTask`) calling flush→hydrate→pullCalendar |
| AlarmManager exact alarms (paused-checkin) | **`UNUserNotificationCenter`** local notifications (`UNTimeIntervalNotificationTrigger`), gated by `send-paused-checkin` allow (§4.8) |
| FCM token + FCM HTTP v1 (Android push) | **APNs** device token → `register-push-token` with `platform:"ios"`, `apnsToken`, `apnsEnvironment` (production/sandbox). Server routes iOS via `_shared/apns.ts` (ES256 provider JWT, time-sensitive `interruption-level`). |
| Glance widgets | **WidgetKit** (read from the shared local store / App Group) |
| Foreground service (live focus session keep-alive) | **No iOS equivalent.** Use **Live Activities (ActivityKit)** + the `live_activity_tokens` table (§2.9): register the per-activity push-to-update token via `register-push-token` (`liveActivityPushToStartToken`) / a `live_activity_tokens` upsert. Background timer accuracy is not guaranteed — the live session is device-local (`LiveSession` model) and recomputed from `sessionStart` epoch on foreground. |
| ANDROID_ID device id | **`UIDevice.identifierForVendor`** (stable per vendor) as `device_id` |
| `TimeZone.getDefault().id` | **`TimeZone.current.identifier`** (IANA) |
| platform string `"android"` | **`"ios"`** everywhere (register-push-token, track-login, feedback) — and remember it must be explicitly serialized |

### Platform-string send values (iOS)
- `register-push-token`: `{ deviceId, apnsToken, platform:"ios", apnsEnvironment:"production"|"sandbox", liveActivityPushToStartToken?, timezone }`. Send `apnsToken` (not `fcmToken`); the server's `pickPrimaryDevice` (APNs) routes iOS rows.
- `track-login`: `{ platform:"ios", device:"<model> · iOS <version>" }`.
- `feedback`: `{ ..., platform:"ios", device, screen, app_version }`.

---

## 6. Edge Functions — exact request/response contracts

All functions: CORS `*`; `OPTIONS` → preflight; non-POST (where applicable) → 405. `verify_jwt` per `config.toml`. JWT-authed functions read `sub` from the token (client just needs to be signed in). Cron functions need `x-cron-secret` (server-only; client never calls them).

### 6.1 `register-push-token` (verify_jwt=true)
POST `{ deviceId (required), apnsToken?, fcmToken?, platform?, liveActivityPushToStartToken?, timezone?, apnsEnvironment? }` → upserts `device_tokens` (onConflict user_id,device_id), stamps `last_active_at`. Pre-deletes other users' rows with the same token. `400 bad_request` if no deviceId. Call on launch + token refresh + foreground (to keep `last_active_at` fresh — it's the device-routing pivot).

### 6.2 `send-session-recap` (verify_jwt=true)
POST `{ taskName?, away? }`. Always inserts an in-app `notification_queue` row (`status:'in_app'`, no cap). If `session_recap_enabled != false` **and** `away==true`: claim a push slot via `try_consume_push_budget(uid, localDate, 'session_recap')`; if allowed, send one push to the most-recently-active device (iOS→APNs). Returns `{ inApp, pushed, capped? }`. **Call this on every session end**, passing `away` = whether the app is backgrounded (so a foreground user gets only the in-app card).

### 6.3 `send-paused-checkin` (verify_jwt=true)
POST (empty body ok) → returns `{ allowed: bool }`. Returns `allowed:false` if `paused_checkin_enabled==false` or `paused_checkin_muted_until > now`, else claims a push slot. **Client suppresses its local paused notification when `allowed==false`.** (§4.8). Defaults to `false` if unreachable.

### 6.4 `send-morning-brief` (verify_jwt=false, cron-only)
Client does **not** call this. Cron POSTs `{ userId }` with `x-cron-secret`. Picks top-3 via the shared ranker, caps via budget, pushes to primary device. Listed here so you understand the morning push your app receives (deepLink `unstuck://today/brief`).

### 6.5 `calendar-sync` (verify_jwt=true, path-routed)
- POST `/authorize` `{ provider:"google", redirectUri }` → `{ url, state }`. **Open `url` in `ASWebAuthenticationSession`/SFSafariViewController.** Store `state` for the CSRF check.
- POST `/connect` `{ provider:"google", code, redirectUri, state }` → `{ id, accountEmail, calendars[], colorSlot }`. Verify the returned `state` matches what you stored before calling.
- POST `/disconnect` `{ connectionId }` → `{ disconnected:true }` (also nulls `external_event_id`/`external_connection_id` on affected blocks).
- GET `/connections` → `{ connections[] }` (snake_case, **credentials stripped**).
- GET `/events?from=&to=&connectionId=` → `{ events: [{id, connectionId, calendarId, summary, start, end}] }`.
- POST `/events` `{ connectionId, calendarId, summary, start, end }` → `{ id }`.
- PATCH `/events/:id` `{ connectionId, calendarId, summary?, start?, end? }`; `404 {error:'event_gone'}` if the event was deleted in Google.
- DELETE `/events/:id?connectionId=&calendarId=`.

**Redirect URI is the HTTPS bounce page** `https://unstuck-602.pages.dev/calendar-callback` (Google rejects custom schemes on a Web OAuth client). That page forwards `?code&state` to `unstuck://calendar-callback`, which your app captures via a URL scheme / Universal Link handler. **This exact URL must already be registered on the Google Cloud Console Web OAuth client** — see the team memo on the OAuth redirect (mobile calendar connect is blocked until it's registered).

Push of local TASK blocks to Google: always insert on the `"primary"` calendar (selected ids can include read-only/subscribed calendars that 403 on insert). PATCH if the block already has an `external_event_id`, else INSERT and persist the returned id on the block so later edits PATCH the same event and a pull won't duplicate it. Skip all-day events (date-only start, no `T`) on pull — they'd collapse to 00:00 slivers.

### 6.6 `account-delete` (verify_jwt=true)
POST (no body) → `adminClient.auth.admin.deleteUser(sub)`; everything cascades via FK. Returns `{ deleted:true }`. Client then signs out.

### 6.7 `share-collection` (verify_jwt=true)
POST `{ action: "add"|"remove"|"leave"|"list", collectionId, email?, userId?, role? }`.
- `add` (owner-only): existing account → member (`{ok, userId, role}`); no account → pending invite + best-effort email (`{ok, invited:true, email, role, emailSent}`). `{error:'self'}` if sharing with yourself.
- `remove` (owner-only): by `userId` (member) or `email` (cancel invite).
- `leave`: caller removes own membership.
- `list`: `{ ok, members:[{user_id,email,role}], pending:[{email,role}], isOwner }`.

Map outcomes to `ShareOutcome` (OK/INVITED/NOT_FOUND/SELF/ERROR) exactly as `CollectionShareClient.share`.

### 6.8 `collection-task-done` (verify_jwt=true)
POST `{ collectionId, itemId, taskName?, by? }`. Flips the shared item's `promotedDone` (visible to all via Realtime on `collections`) + pushes the OTHER members. Call when the assignee completes a promoted shared task.

### 6.9 `check-collection-late` (verify_jwt=false, cron) and `track-login` (verify_jwt=true)
`check-collection-late` is cron-only (you don't call it; it nudges other members when your promoted task is overdue+unstarted, firing once via `late_nudged`). `track-login` POST `{ platform:"ios", device }` → best-effort `{ok}`; throttle to ≤1/12h per user client-side (`maybeTrackLogin`).

### 6.10 Atomic collection RPCs (PostgREST `rpc`)
`collection_add_item(p_collection_id,p_id,p_body,p_at)`, `collection_update_item(...,p_item_id,p_body)`, `collection_remove_item(...,p_item_id)`, `collection_set_item_flag(...,p_item_id,p_flag in (pinned,done),p_value bool)`, `collection_set_item_promotion(...,p_item_id,p_assignee,p_done?,p_due_at?)`. Use snake_case param names (`@SerialName("p_collection_id")` → Swift `CodingKeys`). These are RLS-gated (caller privilege), so a viewer's call is a silent no-op.

---

## 7. Auth contract (`AuthService` port)

`supabase-swift` `AuthClient`: email/password sign-in/up, magic link (OTP), Google OAuth, password reset, `updateUser` (password + metadata `display_name`/`full_name`), `signOut`.

Critical edge cases to replicate:
- **Signup anti-enumeration:** Supabase returns a "successful" obfuscated user for an already-registered email (no session, empty identities). Detect it (`detectSignupAlreadyExists`: identitiesCount, emailConfirmedAt, lastSignInAt, hasSession) and surface "user already exists" instead of the misleading "check your email."
- **`hasPassword`:** true if the user has an `email` identity (vs Google-only) — gates the change-password UI.
- **`currentName`:** `display_name` → `full_name` metadata → email local part fallback.
- **Signup trigger side-effects (server-side, automatic):** `handle_new_user` seeds `user_preferences`, `notification_preferences`, the 5 canonical `life_areas`, and **claims pending collection invites** matching the new email. Your client doesn't do these — but expect a brand-new user to already have 5 life areas and possibly shared collections on first hydrate.

---

## 8. Secrets / config the iOS app needs (and doesn't)

The app ships only the **Supabase URL + anon key** (public). It needs **none** of the server secrets (`SUPABASE_SERVICE_ROLE_KEY`, `APNS_*`, `FCM_*`, `GOOGLE_CLIENT_ID/SECRET`, `CALENDAR_TOKEN_KEY`, `CRON_SECRET`) — those live only in Edge Function env. The APNs auth key, team id, bundle id (`tech.csalliance.unstuck`) are configured server-side; the iOS app only obtains and registers its **APNs device token**. The bundle id must match `APNS_BUNDLE_ID`.

---

## 9. Acceptance / test parity

Port these Android tests to Swift XCTest and make them pass against the same shapes:

- **`DbRowCodecTest`** — top-level snake_case; nested JSONB stays camelCase (`recurrence.daysOfWeek == [1,3,5]`); explicit-null clears `completed_at`; task defaults (`move_count:0, later:false, tags:[]`); `reason_logs.duration_sec` omitted when null, present when set; non-UUID `task_id` → null, UUID preserved; full round-trip; ignore extra server columns; collection `subtitle` "" ↔ null.
- **`SyncDecisionTest`** — wipe only on user change for SIGNED_IN/INITIAL_SESSION; never on USER_UPDATED; cal_blocks merge preserves local `g_` external + remote-wins-on-clash + drops local non-external.
- **Ranker parity** — `pickStartNext` output order matches the morning-brief `topTasks` for the same input.

Manual integration checks: offline-edit → reconnect drains in order; cal_block-before-task FK ordering; capture-during-session ordering; poison op drops after 5 fails without wedging dependents; shared-list concurrent add lands both items (RPC path); sign-out deletes the device token row; same-user re-auth does NOT wipe a live session.

---

## 10. Source-of-truth file map (for the engineer)

- Schema/RLS/triggers/functions: `/Users/ahmadtambaya/Desktop/projects/unstuck/supabase/migrations/001…027_*.sql`
- Cron jobs (manual): `/Users/ahmadtambaya/Desktop/projects/unstuck/supabase/manual/notification_cron.sql`
- Edge functions: `/Users/ahmadtambaya/Desktop/projects/unstuck/supabase/functions/<name>/index.ts` + `_shared/{apns,fcm,jwt,cors,notify,email}.ts` + `calendar-sync/{encrypt,state,providers/*}.ts`
- Function auth config: `/Users/ahmadtambaya/Desktop/projects/unstuck/supabase/config.toml`
- **Client contract to port (Android → Swift):** `/Users/ahmadtambaya/Desktop/projects/unstuck_android/sync/src/main/kotlin/tech/csalliance/unstuck/sync/{SyncCoordinator,SyncDecision,WriteThrough,OutboxFlusher,Hydrator,RealtimeMirror,SyncGateway,DbRowCodec,Clients,CalendarClient,CollectionShareClient,AuthService,FeedbackClient}.kt`
- Domain models/enums + pure logic: `/Users/ahmadtambaya/Desktop/projects/unstuck_android/core/src/main/kotlin/tech/csalliance/unstuck/core/{model/Models.kt,model/Enums.kt,logic/{Uuid,CalBlockKind,GoogleSyncMapping,PickStartNext,Recurrence}.kt}`
- Tests to port: `/Users/ahmadtambaya/Desktop/projects/unstuck_android/sync/src/test/kotlin/tech/csalliance/unstuck/sync/{DbRowCodecTest,SyncDecisionTest}.kt`
- Local store shape: `/Users/ahmadtambaya/Desktop/projects/unstuck_android/data/src/main/kotlin/tech/csalliance/unstuck/data/LocalStore.kt` + `db/Daos.kt`

The Kotlin files carry "// Port of the iOS X.swift" comments throughout — those Swift files are exactly what you are rebuilding. The behavior in this spec is authoritative; where the Android source and this document agree, the Android source wins on byte-level detail.