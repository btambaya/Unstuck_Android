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
2. **P1 — `:data` (Room + outbox + DbRowCodec) + `:sync` (supabase-kt engine)**
3. **P1b — backend FCM delta** (in the `unstuck` repo: migration 018 `fcm_token`,
   `_shared/fcm.ts`, platform branches in the senders, deploy)
4. **P2 — Tasks + Today** (visibleTasks filters, create/edit, recurrence, slip; Start/Up Next)
5. **P3 — Focus** (timer + 3 treatments + reasons + captures + FocusTimerService + ambient audio)
6. **P4 — Calendar** (day grid + drag-to-schedule + block-time + Google connect + push/pull)
7. **P5 — Collections / Tags & Areas / Captures**
8. **P6 — Insights / Settings / Onboarding / Command palette**
9. **Surfaces** — `:shared` DataStore → FCM register/receive → Glance widget →
   foreground notification → WorkManager paused-check
10. **P7 — Polish** — Material You / dark, dynamic type, empty/error/sync-status,
    accessibility, Play release signing

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
