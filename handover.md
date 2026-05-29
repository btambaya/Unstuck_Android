# Unstuck Android — Handover / Live State

Single source of truth for "where is the Android build?". Update as phases land.

## Status snapshot

- **P0 — Foundation: DONE.** Gradle multi-module scaffold (wrapper 8.9, AGP
  8.7.3, version catalog), repo on `main` → `github.com/btambaya/Unstuck_Android.git`.
  `:core` fully ported from the web `lib/*` + iOS `UnstuckCore`, **157 JUnit
  tests green** (`./gradlew :core:test`). CI runs `:core:test`.

`:core` ports (each mirrors the web Vitest + iOS XCTest cases):
- Models + enums (`TaskItem`, `CalBlock`, `Session`, `Capture`, `ReasonLog`,
  `CalendarConnection`, `ExternalEvent`, `ItemCollection`/`CollectionItem`,
  `TagRow`, `LifeArea`, `LiveSession`, `Recurrence` sealed + custom serializer).
- `Time`/`Clock` (java.time, `ZoneId.systemDefault()` = JS Date LOCAL semantics).
- `Uuid`, `CalBlockKind`, `TaskBucket`, `VisibleTasks` (+slip), `PickStartNext`,
  `Recurrence` (materialise/regen/label), `FreeSlots`, `FocusTimer`, `Analytics`
  (H1–H7 + insights), `AuthErrors`, `GoogleSyncMapping`, `TaskMutations`.

## Roadmap (powering through P0→P7)

1. **P0 — Foundation** ✅
2. **`:design`** ✅ — oklch→sRGB converter (7 tests), brand `UnstuckColors` light/dark,
   `UnstuckTheme` (CompositionLocal + M3), type scale, components (UButton/Chip/Card/
   SectionLabel/AreaDot). Builds to AAR.
2b. **P1 — `:data` + `:sync`** ✅
   - `:data` — Room single `records` table (JSON blob per row) + `outbox` + `live_session`;
     `LocalStore` typed Flows; **6 Robolectric round-trip tests** (incl. JSONB shape +
     external `g_` preservation + outbox FIFO).
   - `:sync` — `DbRowCodec` (PostgREST boundary, **10 tests**: snake_case top-level /
     camelCase JSONB / explicit-null clear / duration_sec omit / uuid-or-null / round-trip),
     `SyncDecision` (**5 tests**), `SupabaseClientProvider` (PKCE, `unstuck://auth-callback`),
     `AuthService`, `SyncGateway`, `Hydrator`, `WriteThrough`, `OutboxFlusher`,
     `RealtimeMirror`, `CalendarClient`, `PushClient` (FCM), `NotificationsClient`,
     `PreferencesClient`, `SyncCoordinator` (sessionStatus → wipe-rule → flush → hydrate →
     subscribe). Compiles against supabase-kt 3.0.3.

   Total tests green so far: **157 (:core) + 7 (:design) + 6 (:data) + 15 (:sync) = 185.**
3. **P1b — backend FCM delta** (in the `unstuck` repo: migration 018 `fcm_token`,
   `_shared/fcm.ts`, platform branches in the senders, deploy)
4. **P2 — Tasks + Today** ✅ — Today (Start Next / Up Next / in-progress resume),
   Tasks (visibleTasks filter views + area + slip toggle + create/edit + schedule-next-free-slot).
5. **P3 — Focus** ✅ (core) — full-screen timer on FocusTimer + 1s ticker, 3 treatments,
   pause reasons → reason_logs, mid-session captures, extend-on-overrun. *Pending: FocusTimerService
   foreground notification + ambient audio (surfaces phase).*
6. **P4 — Calendar** ✅ (agenda) — upcoming blocks grouped by day, unschedule, external g_ blocks,
   schedule via free-slot. *Pending: drag-to-schedule day grid + Google connect UI (Custom Tabs).*
7. **P5 — Collections / Tags & Areas** ✅ — lists with items (add/toggle/delete), areas + tags CRUD in Settings.
8. **P6 — Insights / Settings / Auth** ✅ — Insights (topInsights + stats + pause anatomy + slipping),
   Settings (areas/tags/sync/sign-out), Auth (email/password + sign-up + magic link + Google).
   *Pending: onboarding struggles flow, command palette, recurrence editor UI (model + label done).*

**`:app` builds — `./gradlew :app:assembleDebug` → 20 MB debug APK.** Manual DI
(`AppGraph`) instead of Hilt (fewer codegen moving parts). Central `AppViewModel`
exposes every collection as a StateFlow off the Room store + all write actions
through the sync engine. Bottom nav Today · Tasks · [+] · Calendar · Lists, with
Focus / task-detail / settings as overlays.
9. **Surfaces** ✅ — `StartNextSnapshot` (DataStore) → Glance `StartNextWidget`
   (updates when the recommendation changes), `FocusTimerService` (foreground
   chronometer notification, started/stopped by FocusScreen), `UnstuckMessagingService`
   + `registerFcmToken` (FCM — dormant until google-services.json), `SyncWorker`
   (30-min periodic flush+hydrate via `SyncCoordinator.syncNow()`),
   POST_NOTIFICATIONS runtime request. Widget/snapshot live in :app (no :shared module).
10. **P6/P7 polish** — ✅ recurrence editor (`RecurrenceEditor` in new/edit
    sheets; `scheduleTask` materialises the horizon series), ✅ first-run
    onboarding (struggles → user_preferences + seeds canonical life areas),
    ✅ command palette (search tasks + actions, from the Today header),
    ✅ adaptive launcher icon (Orbit mark), ✅ dark via
    `UnstuckTheme(isSystemInDarkTheme())`. TODO (cosmetic/optional): bundled
    Geist/Instrument-Serif/IBM-Plex-Mono fonts, drag-to-schedule day grid,
    ambient focus audio, Play release signing keystore.

**Validated on-device** (Pixel_Fold emulator, API 35): installs + launches with
no crashes; Supabase client initialises (`SupabaseClient created!`); Room + DI +
Compose theme + oklch brand colours render — the auth screen shows the serif
wordmark + coralDeep CTA. With `secrets.properties` (anon key) it reaches the
live auth screen; without it, the setup screen.

## Critical gotchas (inherited from the iOS build)

- **DbRowCodec:** top-level columns are snake_case but JSONB internals are
  camelCase (`recurrence.daysOfWeek`). Do NOT apply a global SnakeCase naming
  strategy — `@SerialName` per top-level row field, leave nested camelCase.
- **Null clearing:** kotlinx `explicitNulls=true` sends explicit `null` so an
  upsert clears a removed field (matches the web `?? null`); `duration_sec` is
  the one omit-when-nil (default + `encodeDefaults=false`).
- **Stable sort:** Kotlin's `sortedWith` IS stable (unlike Swift) — open-before-
  done partition is still explicit for parity.
- **Dates:** java.time + `ZoneId.systemDefault()` reproduce JS Date LOCAL math;
  ISO strings compare lexicographically; tests run `TZ=UTC`.

## Manual prerequisites (owner — the FCM/Play analogs of the Apple steps)

1. Firebase project → Android app (package `tech.csalliance.unstuck`) →
   `app/google-services.json`; service account → `FCM_SERVICE_ACCOUNT` (JSON) +
   `FCM_PROJECT_ID` Supabase secrets.
2. `SUPABASE_ANON_KEY` → `secrets.properties` (→ `BuildConfig`, gitignored).
3. Google OAuth `/calendar-callback` redirect already registered (shared w/ iOS).
4. Release signing keystore + Play Console (debug builds need none).
