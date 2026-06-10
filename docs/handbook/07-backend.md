## Backend (Supabase) & How Android Talks to It

This chapter is your map of the server side of Unstuck: the Supabase project, the Edge Functions, the Postgres schema + Row-Level Security, the notification cron, the secrets, and — crucially — how the **Android client** reaches all of it. The backend is **shared** between web, iOS, and Android. The Next.js web app and the Supabase project live in `/Users/ahmadtambaya/Desktop/projects/unstuck`; the Android app lives in `/Users/ahmadtambaya/Desktop/projects/unstuck_android`. There is **no Android-specific backend** — Android speaks the same Postgres tables and Edge Functions as everyone else, just through the `supabase-kt` client instead of `supabase-js`/Swift.

### Orientation: the project and the trust model

- **Project ref:** `uaxfteluwctrlgwmmfzi` (display name "Unstuck"/"Unstack", org `wgztoodihawnpfiavygh`). Confirmed in `supabase/.temp/linked-project.json` and `supabase/.temp/project-ref`. The CLI in the web repo is already linked + authenticated to this project, so `supabase db push`, `supabase functions deploy`, and `supabase db query` "just work" from `/Users/ahmadtambaya/Desktop/projects/unstuck`.
- **Base URL:** `https://uaxfteluwctrlgwmmfzi.supabase.co`. This is also the Android default — see `app/build.gradle.kts`:
  ```kotlin
  val supabaseUrl = secrets.getProperty("SUPABASE_URL") ?: "https://uaxfteluwctrlgwmmfzi.supabase.co"
  val supabaseAnonKey = secrets.getProperty("SUPABASE_ANON_KEY") ?: ""
  ```
  The anon key is **not** committed; it comes from a gitignored `secrets.properties` and is surfaced via `BuildConfig`. If it's empty, `AppGraph.configured` is false and the app shows a setup screen.
- **The trust boundary** is a layered cake:
  1. **RLS** on every user-owned table: a row is visible/writable only when `user_id = auth.uid()`. This is the real security boundary for direct PostgREST/Realtime access (which is what Android uses for normal CRUD).
  2. **`verify_jwt = true`** on most Edge Functions: Supabase's gateway validates the caller's JWT *before* the function runs. Functions then trust the JWT's `sub` claim as the user id (see below).
  3. **`x-cron-secret`** for the cron-only function (`send-morning-brief`), which runs without a user JWT.

A recurring lesson encoded everywhere (`_shared/jwt.ts`, comments in `account-delete`, `register-push-token`): **functions never call `auth.getUser()`**. Because `verify_jwt = true` already validated the token upstream, they just base64-decode the JWT payload to read `sub`. The comment cites a past project ("Cairn") where the `getUser()` round-trip would wedge under load.

```
Android app  ──JWT (anon key + user session)──▶  Supabase API gateway
                                                   │ verify_jwt?
                                  ┌────────────────┼────────────────┐
                                  ▼                ▼                ▼
                          PostgREST (RLS)     Realtime (RLS)    Edge Functions
                          tasks, sessions…    postgres_changes  calendar-sync, etc.
```

### The database schema & migrations

Migrations live in `supabase/migrations/NNN_*.sql` and apply **in numeric order**. Each migration is wrapped by `supabase db push` in its own transaction, so **none of them contain `begin/commit`** — this is called out repeatedly (e.g. migration 007's "lesson"). Adding your own `begin/commit` will break `db push`.

**The "records" tables** (the user's actual data), all from `001_initial.sql` unless noted, all RLS-locked `user_id = auth.uid()`, all `ON DELETE CASCADE` from `auth.users`:

| Table | What it holds | Notable columns |
|---|---|---|
| `user_preferences` | one row/user | `subscription_tier`, `display_name`, `primary_calendar_connection_id`, `adhd_struggles text[]` (003), `usable_minutes_per_day` (007) |
| `tasks` | the to-dos | `estimate_min`, `priority`, `tags text[]`, `objectives/comments jsonb`, `intent_when/then`, `life_area`, `done`, `completed_at` (005), `later` + `recurrence jsonb` (008), `move_count` (003) |
| `sessions` | completed focus sessions | `task_name` denormalized so it survives task delete, `actual_sec`, `completed_at` |
| `cal_blocks` | scheduled blocks on the calendar | `start_time 'HH:MM'`, `duration_minutes`, `date`, `kind` (006: `task`/`placeholder`/`external`), `external_event_id` + `external_connection_id` (Google linkage), `task_id` made nullable in 009, **`done`/`skipped`/`completed_at` (033 — per-occurrence state for a recurring template's blocks; see core-domain "templates + per-day occurrences")** |
| `reason_logs` | why-I-paused/switched | `reason`, `action`, `duration_sec` (006) |
| `captures` | quick thoughts during focus (002) | loosely attached to task + session |
| `tags` (010) + color (011) | curated tag vocabulary | canonical list; `tasks.tags` text[] stays the denormalized reference |
| `collections` (012) | calm "memory containers" | items inline as JSONB on the row |
| `life_areas` (003, backfilled 007) | Work/Personal/etc. | canonical 5 seeded on signup |
| `trusted_circle` etc. (004) | collab/referral/beta | matched by owner or invitee |

**The notification/device tables:**

- **`device_tokens`** (`014`, + `fcm_token` added in `017`): one row per `(user_id, device_id)` — the unique key the upsert targets. Columns: `platform` (`'ios'`/`'android'`), `apns_token`, `apns_environment`, `fcm_token`, `live_activity_push_to_start_token`, `timezone` (IANA — the cron pivot), `last_active_at`. **Deliberately NOT in the realtime publication** (tokens are credentials).
- **`notification_preferences`** (`015`, PK = `user_id`): the per-moment toggles (`session_recap_enabled`, `paused_checkin_enabled`, `morning_brief_enabled`), `daily_push_cap` (default 3), `timezone`, wake-window config (`wake_window_mode auto|fixed`, `wake_window_fixed_start`, `wake_window_computed`), `quiet_hours_*`, `skip_weekends`, `paused_checkin_muted_until`. `handle_new_user()` is extended here to seed this row on signup, and existing users are backfilled.
- **`wake_window_history`** (`015`): rolling `(user_id, local_date)` first-input minute; the cron medians it.
- **`notification_ledger`** (`016`): one row per push *sent*, keyed by `(user_id, local_date, moment)`. This is the hard daily cap's accounting.
- **`notification_queue`** (`016`): audit/retry + the always-on in-app recap card (`status` includes `in_app`).
- **`live_activity_tokens`** (`014`): iOS-only Live Activity update tokens.

**The cap RPC** `try_consume_push_budget(p_user, p_date, p_moment)` (`016`) is the heart of rate-limiting. It's `security definer` (bypasses RLS so service-role and clients alike can count), reads `daily_push_cap`, counts today's ledger rows, and either inserts a ledger row and returns `true` or returns `false` if the cap is hit. **Every push-sending function calls this before sending.** Note the count-then-insert is not transactionally atomic against concurrent calls — fine for a 3/day cap, worth knowing if you ever raise the cap or add high-frequency moments.

**RLS posture summary:** every user table has exactly one `for all using (user_id = auth.uid()) with check (user_id = auth.uid())` policy (named `<table>_own`). Realtime broadcasts changes on user tables (publication `supabase_realtime`, set up at the end of `001`), **except** `calendar_connections` (removed in `013` because the full row — including the encrypted refresh token — was being broadcast on every `last_sync_cursor` bump) and the notification/token tables (never added). `touch_updated_at()` triggers bump `updated_at` on update.

### The Edge Functions

All live under `supabase/functions/`. Per-function auth is set in `supabase/config.toml`. They share four helpers in `_shared/`:

- **`_shared/cors.ts`** — `CORS_HEADERS` (`Allow-Origin: *`), `jsonResponse(body, status)`, `corsPreflight()`. Every handler starts with an `OPTIONS → corsPreflight()` branch.
- **`_shared/jwt.ts`** — `decodeJwt(authHeader)` → `{ sub, email, ... } | null`. Decode-only, never verify (gateway already verified). Returns null if no `sub`.
- **`_shared/apns.ts`** — APNs HTTP/2 client. Mints an **ES256** provider JWT with `crypto.subtle` (cached ~50 min), `sendPush()`, `alertPayload()`, and `pickPrimaryDevice()` (the single most-recently-active iOS device with a token, active in the last 24h).
- **`_shared/fcm.ts`** — FCM HTTP v1 client (the Android counterpart). Mints an **RS256** OAuth2 access token from the service-account JSON, `sendFcmPush()`, and `pickPrimaryFcmDevice()` (same 24h/most-recent rule for Android rows). Key detail: it supports `dataOnly: true`, which folds `title`/`body` into the FCM `data` block and **omits the `notification` block** — so the Android client renders the notification itself (correct channel, deep-link, actions) in every app state. All Unstuck Android pushes use `dataOnly: true`.

Here's each function:

**1. `calendar-sync` (`verify_jwt = true`)** — one function, sub-routed by URL path, handling all calendar operations across providers. `index.ts` dispatches on `(method, subpath)`:

```
POST   /calendar-sync/authorize    → { url, state }   (Google consent URL + HMAC state)
POST   /calendar-sync/connect      → { id, accountEmail, calendars, colorSlot }
POST   /calendar-sync/disconnect   → { disconnected: true }
GET    /calendar-sync/connections  → { connections: [...] }  (credentials stripped)
GET    /calendar-sync/events?from&to&connectionId → { events: [...] }
POST   /calendar-sync/events       → { id }
PATCH  /calendar-sync/events/:id   → { patched: true }
DELETE /calendar-sync/events/:id?connectionId&calendarId → { deleted: true }
```

It uses a **service-role admin client** internally (so it can write `calendar_connections` and clear `cal_blocks` linkage), but always scopes queries by `userId = claims.sub` — never trusting a body param for which user. Provider adapters implement `ProviderAdapter` (`providers/types.ts`): only **`google.ts`** is real; `apple.ts` and `microsoft.ts` are stubs that throw `"not yet implemented"` (Apple needs CalDAV/`tsdav`; Microsoft needs a Graph OAuth client). 

Credential security:
- **`calendar-sync/encrypt.ts`** — AES-256-GCM, key from `CALENDAR_TOKEN_KEY` (32-byte base64). Storage shape `12-byte IV || ciphertext+tag`, fresh IV per encrypt. `credentials` is `bytea`, marshalled to/from PostgREST's `\x…` hex via `bytesToHex`/`hexToBytes`.
- **`calendar-sync/state.ts`** — `signState(sub)`/`verifyState(state, sub)`: HMAC-SHA256 (keyed by the same `CALENDAR_TOKEN_KEY`) over `{sub, nonce, exp}`, 10-min TTL, constant-time-ish compare. This binds the OAuth consent round-trip to the authenticated user so `/connect` can't be CSRF'd into grafting someone else's calendar.

Google OAuth flow (the only live one): the client calls `/authorize` to get a consent `url` + opaque `state`; after consent, Google bounces back with a `?code`; the client posts `code + state` to `/connect`; the function verifies the state, exchanges the code for a `refresh_token` **server-side** with `GOOGLE_CLIENT_SECRET` (`exchangeGoogleCode`), encrypts it, and inserts the connection. `mintAccessToken()` refreshes per-call when listing/inserting events. Note `/connect` defaults `selected_calendar_ids` to **all** of the account's calendars.

**2. `register-push-token` (`verify_jwt = true`)** — the client posts its device + token on launch/refresh. Upserts `device_tokens` on `(user_id, device_id)`, stamping `last_active_at` and `timezone`. Body: `{ deviceId, apnsToken?, fcmToken?, platform, timezone?, apnsEnvironment?, liveActivityPushToStartToken? }`. `platform` is normalized to `'android'` only when explicitly `'android'`, else `'ios'`. Returns `{ ok: true }`. This function holds **no push secrets** — it only records tokens.

**3. `send-session-recap` (`verify_jwt = true`)** — called by the client when a focus session ends. **Always** inserts an in-app recap card into `notification_queue` (no cap). Then, only if `body.away === true` *and* `session_recap_enabled !== false`, it calls `try_consume_push_budget` and — if allowed — sends one push to the most-recently-active device of either platform (FCM for Android, APNs for iOS; ties broken by `last_active_at`). Body `{ taskName?, away? }`; returns `{ inApp, pushed, capped? }`.

**4. `send-paused-checkin` (`verify_jwt = true`)** — **cap coordination only**, no sending. The "paused too long" nudge is a *client-scheduled local notification* (background idle isn't observable server-side), but it routes through here when online so the shared daily cap stays consistent across devices/moments. Checks `paused_checkin_enabled` + `paused_checkin_muted_until`, then `try_consume_push_budget`, and returns `{ allowed }`. The client suppresses its local notification when `allowed` is false.

**5. `send-morning-brief` (`verify_jwt = false`, guarded by `x-cron-secret`)** — invoked **per-user by the cron** (see below), not by clients. It checks the secret header against `CRON_SECRET`, reads `morning_brief_enabled`, computes the top-3 "start here" tasks via a Deno port of `pick-start-next` (kept in lockstep with the Swift + web copies — exclude done/later, rank priority desc → estimate asc → created_at asc), suppresses on empty days, consumes the cap, and sends one push (FCM or APNs) with a `deepLink` of `unstuck://today/brief`.

**6. `account-delete` (`verify_jwt = true`)** — `POST` only. Decodes the JWT for `sub`, then `adminClient.auth.admin.deleteUser(sub)` with the service-role key. All user tables cascade via their `user_id` FK. The user id never comes from the body — there's no override path.

**7. `assistant` (`verify_jwt = true`)** — the in-app AI agent's **text** path. A thin, stateless, provider-agnostic proxy to an OpenAI-compatible `/chat/completions` (owns the system prompt + tool schemas; the client runs the tool calls). Android client: `sync/AssistantClient.kt`; the conversation + tool dispatcher live in `AppViewModel`. The **model/provider is configured entirely server-side** (Supabase secrets `LLM_API_KEY`/`LLM_BASE_URL`/`LLM_MODEL`), so swapping the text model needs **no app change**. The **voice** path is separate — `qwen3.5-omni-flash-realtime` via the `unstuck-voice-proxy` Cloudflare Worker (not a Supabase fn); the only app-side model knob is `AppViewModel.voiceModel`. **Onboarding/swapping either model is documented in the web repo: `unstuck/docs/ASSISTANT-MODELS.md`.**

### The cron (`supabase/manual/notification_cron.sql`)

This file is in `supabase/manual/`, **not** `migrations/`, so `db push` never auto-applies it — you run it **by hand** (psql / SQL editor) after deploying `send-morning-brief`, enabling `pg_cron` + `pg_net`, and wiring `CRON_SECRET`. It defines two `security definer` functions and schedules both **every 15 minutes in UTC**:

- **`calibrate_wake_windows()`** — pure SQL, no network. For each user with `wake_window_mode = 'auto'`, only when their *local* time is near 00:15, it computes the median first-input minute over the last 7 days from `wake_window_history` and writes `wake_window_computed` (HH:MM).
- **`dispatch_morning_briefs()`** — for each user with `morning_brief_enabled`, converts `now()` to their `timezone`, skips weekends if configured, and if local time is within the 15-min tick of their wake-window start (computed/fixed, default `08:00`), calls `net.http_post` to `…/send-morning-brief` with the `x-cron-secret` header and `{ userId }`. It reads `func_url` + `cron_secret` from `current_setting('app.settings.*')` and **no-ops if those aren't set** — so a fresh project is safe until configured.

Because pg_cron only runs in UTC, the per-user timezone pivot (`now() at time zone tz`) is how local-time scheduling stays DST-correct. Both jobs are scheduled idempotently (unschedule-then-schedule by jobname).

### Secrets (set via `supabase secrets set`)

Referenced across the functions:

| Secret | Used by | Purpose |
|---|---|---|
| `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY` | all functions with an admin client | service-role Postgres access |
| `CALENDAR_TOKEN_KEY` | `calendar-sync` (`encrypt.ts`, `state.ts`) | 32-byte base64; AES-GCM creds + HMAC state |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | `calendar-sync/providers/google.ts` | OAuth code exchange + token mint |
| `FCM_SERVICE_ACCOUNT`, `FCM_PROJECT_ID` | `_shared/fcm.ts` | Android push (HTTP v1) |
| `APNS_AUTH_KEY`, `APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_BUNDLE_ID` | `_shared/apns.ts` | iOS push |
| `CRON_SECRET` | `send-morning-brief` + the cron | shared-secret auth for cron→function |

### How to deploy

The CLI in the **web repo** is already authenticated and linked to `uaxfteluwctrlgwmmfzi`. From `/Users/ahmadtambaya/Desktop/projects/unstuck`:

```bash
# Edge Functions (deploy one, or all):
supabase functions deploy calendar-sync --project-ref uaxfteluwctrlgwmmfzi
supabase functions deploy send-morning-brief --project-ref uaxfteluwctrlgwmmfzi

# Migrations (preview first, then apply):
supabase migration list
supabase db push --dry-run
supabase db push --yes </dev/null     # connection/password is cached

# Ad-hoc SQL against the linked project (per the task brief):
supabase db query --linked < some.sql

# Secrets:
supabase secrets set FCM_PROJECT_ID=... FCM_SERVICE_ACCOUNT="$(cat sa.json)"

# The cron is MANUAL — never auto-applied:
supabase db query --linked < supabase/manual/notification_cron.sql
```

Per the project's deploy-workflow convention: after backend changes, apply pending migrations **and** push to `main` (Cloudflare Pages auto-deploys the web app, which is what the user tests against).

### How Android talks to the backend

The Android sync layer (module `:sync`, package `tech.csalliance.unstuck.sync`) is a near-1:1 port of iOS, built on the `io.github.jan.supabase` ("supabase-kt") client.

**Client construction** — `SupabaseClientProvider.kt`:
```kotlin
createSupabaseClient(config.url, config.anonKey) {
    install(Auth) { flowType = FlowType.PKCE; scheme = "unstuck"; host = "auth-callback"
                    autoLoadFromStorage = true; autoSaveToStorage = true }
    install(Postgrest); install(Realtime); install(Functions)
}
```
PKCE is required for the OAuth/magic-link deep-link callback (`unstuck://auth-callback`). The URL + anon key come from `BuildConfig` (which comes from `secrets.properties`).

**The orchestrator** — `SyncCoordinator.kt` (port of the web's `bootstrap-listener.tsx`). It observes `client.auth.sessionStatus` and, on `Authenticated`, runs: apply cache-wipe rule (if the user changed) → **flush the offline outbox** → **hydrate** server-canonical → **subscribe to realtime** → `pullCalendar()`. On sign-out it tears down realtime and wipes the local store. It owns the typed clients:

| Client | File | Talks to |
|---|---|---|
| `AuthService` | `AuthService.kt` | `client.auth` (Email/OTP/Google) + invokes `account-delete` |
| `SyncGateway` | `SyncGateway.kt` | PostgREST CRUD on the records tables |
| `RealtimeMirror` | `RealtimeMirror.kt` | `postgres_changes` per table |
| `CalendarClient` | `CalendarClient.kt` | `calendar-sync` Edge Function |
| `PushClient` | `Clients.kt` | `register-push-token` |
| `NotificationsClient` | `Clients.kt` | `send-session-recap`, `send-paused-checkin` |
| `PreferencesClient` | `Clients.kt` | upsert `user_preferences` directly |

**Normal data CRUD** goes through `SyncGateway` (`from(table).select/upsert/delete`), working in `JsonObject` row shapes (via `DbRowCodec`) so explicit-null semantics survive. On every write it injects `user_id` (`{ ...row, user_id }`) exactly like the web bridge; reads rely on RLS to auto-scope. `RealtimeMirror.subscribeAll(userId)` opens one channel per synced table, each filtered `eq("user_id", userId)` (RLS is the real guard; the client filter is belt-and-braces), mapping `Insert/Update → local upsert` and `Delete → local remove`. **`calendar_connections` is intentionally not subscribed** (creds never broadcast — matches migration 013).

**Edge Function calls** use `client.functions.invoke("<name>", { method = …; setBody(...) })`. The function path is exactly the deployed function name; sub-routed functions append the subpath, e.g. `"calendar-sync/authorize"`. supabase-kt automatically attaches the user's `Authorization: Bearer <jwt>` + the `apikey`, which is why `verify_jwt = true` functions get a valid `sub`.

**Calendar consent flow on Android** (`SyncCoordinator.beginGoogleConnect/completeGoogleConnect` + `CalendarClient`):
1. `calendar.authorize(CAL_REDIRECT)` → server returns `{ url, state }`; the app stores `state` (`pendingCalState`) and opens `url` in a Custom Tab.
2. Google bounces to `CAL_REDIRECT` = `https://unstuck-602.pages.dev/calendar-callback` (an **HTTPS** page — Google rejects custom schemes on a Web OAuth client; this exact URL must be an Authorized redirect URI). That page forwards `?code&state` to `unstuck://calendar-callback`, captured by `MainActivity`.
3. `completeGoogleConnect(code, state)` checks `state == pendingCalState` (CSRF guard), calls `calendar.connectGoogle(...)`, then hydrates + `pullCalendar()` so the UI flips to "Synced" immediately.

**Push registration** — `surface/Push.kt`: `registerFcmToken()` / `UnstuckMessagingService.onNewToken` call `PushClient.register(deviceId, fcmToken)` which posts to `register-push-token` with `platform = "android"`. `deviceId` is `Settings.Secure.ANDROID_ID`. `onMessageReceived` prefers the `data` block (because the server sends `dataOnly: true`), reading `title`/`body`/`kind`/`deepLink`, and renders via `NotificationRenderer`. **Gotcha:** all Firebase calls are guarded and **dormant until `google-services.json` is added + the plugin applied** — the app builds and runs without it (the Android analog of the iOS APNs key being a manual prerequisite).

### How to extend: adding a new client-triggered notification moment

Worked example — add a **"streak nudge"** push.

1. **DB:** No new table needed if you reuse the cap. Add `streak_nudge_enabled boolean not null default true` to `notification_preferences` in a new migration `018_streak_nudge_pref.sql` (no `begin/commit`, use `add column if not exists`). The cap RPC and ledger already handle a new `moment` string — `notification_ledger.moment` is free-form text.

2. **Edge Function:** Create `supabase/functions/send-streak-nudge/index.ts`. Copy `send-session-recap` as the template (it's the closest: `verify_jwt`, optional in-app card, cap, dual-platform send). Add `[functions.send-streak-nudge]\nverify_jwt = true` to `config.toml`. Reuse `try_consume_push_budget(uid, localDate, 'streak_nudge')`, `pickPrimaryFcmDevice`/`pickPrimaryDevice`, and `sendFcmPush({ ..., dataOnly: true, data: { kind: 'streak_nudge', deepLink: 'unstuck://today' } })`.

3. **Android client:** In `Clients.kt`'s `NotificationsClient`, add `suspend fun streakNudge(...)` mirroring `sessionRecap` — `client.functions.invoke("send-streak-nudge") { method = Post; setBody(...) }`. Handle the `kind == "streak_nudge"` branch in `UnstuckMessagingService.onMessageReceived` if it needs a distinct channel/deep-link.

4. **Deploy:** `supabase db push --yes`, `supabase functions deploy send-streak-nudge --project-ref uaxfteluwctrlgwmmfzi`, push web to `main`.

### Pitfalls & gotchas observed in the code

- **No `begin/commit` in migrations.** `db push` wraps each file in its own transaction; adding explicit transaction control breaks it (the migrations call this out repeatedly).
- **kotlinx.serialization drops defaults.** `CalendarClient.AuthorizeBody.provider` has *no* default precisely because `encodeDefaults` is off — a defaulted `provider="google"` was silently omitted from the body and the server rejected it. If you add fields the server requires, don't rely on Kotlin defaults.
- **`calendar_connections` must stay off realtime** (013) — the encrypted refresh token is in the row. `RealtimeMirror` deliberately skips it; don't add it back.
- **The cron is manual.** It lives in `supabase/manual/`, not `migrations/`. After a fresh project setup, morning briefs won't fire until you run `notification_cron.sql` *and* set the `app.settings.functions_url` / `app.settings.cron_secret` Postgres settings — `dispatch_morning_briefs()` silently no-ops otherwise.
- **Apple & Microsoft calendar adapters are stubs** — they throw "not yet implemented". Only Google works end-to-end.
- **`dataOnly` pushes have no `notification` block**, so they only show up if the Android client renders them in `onMessageReceived`. If you send a push without `dataOnly: true` (or the client mishandles `kind`), it may not surface correctly across app states.
- **Cap is count-then-insert, not strictly atomic.** Fine at 3/day; revisit if you add high-frequency moments or concurrent senders.
- **Self-mirroring guard:** when pulling Google events, `SyncCoordinator.pullCalendar()` filters out events whose ids match task blocks Android itself pushed (`externalEventId`), to avoid duplicate `g_` external blocks next to the originating task block. Task blocks are always pushed/patched/deleted on Google's `"primary"` calendar (subscribed calendars 403 on insert), so keep that asymmetry in mind when touching the two-way sync.

**Key files for reference:** backend — `supabase/functions/calendar-sync/index.ts`, `.../providers/google.ts`, `.../encrypt.ts`, `.../state.ts`, `supabase/functions/_shared/{fcm,apns,jwt,cors}.ts`, `supabase/functions/{register-push-token,send-session-recap,send-paused-checkin,send-morning-brief,account-delete}/index.ts`, `supabase/migrations/{001,013,014,015,016,017}_*.sql`, `supabase/manual/notification_cron.sql`, `supabase/config.toml`. Android — `unstuck_android/sync/src/main/kotlin/tech/csalliance/unstuck/sync/{SupabaseClientProvider,SyncCoordinator,SyncGateway,RealtimeMirror,CalendarClient,Clients,AuthService}.kt`, `app/src/main/kotlin/tech/csalliance/unstuck/surface/Push.kt`, `app/build.gradle.kts`.
