# Unstuck — Android

Native Android client for **Unstuck**, the focus/task app. Full feature parity
with the web app (`github.com/btambaya/Unstuck.git`) and the iOS app
(`Unstuck_IOS.git`), sharing the same Supabase backend (project ref
`uaxfteluwctrlgwmmfzi`).

This app is largely a **Kotlin transcription of the iOS app** — the web `lib/*`
(with Vitest cases) and the Swift `UnstuckCore`/`UnstuckSync` ports are the dual
spec. Every `:core` logic port has a JUnit test mirroring the same web + iOS
cases, so all three clients agree on bucketing, ranking, recurrence, the focus
timer, analytics, and the Google-Calendar mapping.

## Stack

| Concern | Choice |
|---|---|
| Language / UI | Kotlin 2.0 + Jetpack Compose + Material3 |
| Backend SDK | supabase-kt 3.x (Auth/Postgrest/Realtime/Functions, PKCE) |
| Local store | Room (offline-first, `Flow` queries) |
| Serialization | kotlinx-serialization |
| DI | Hilt |
| Async | coroutines + Flow |
| Push | FCM (firebase-messaging) |
| Widgets | Jetpack Glance + DataStore |
| Focus "live" | foreground service + ongoing chronometer notification |
| OAuth | Custom Tabs + `unstuck://` intent-filter |
| Background | WorkManager |
| Build | Gradle (Kotlin DSL) + version catalog; minSdk 26, target/compile 35 |

JDK 17. Gradle wrapper pinned to **8.9** (AGP 8.7.3).

## Modules

```
:core      pure Kotlin/JVM domain + logic ports (no Android/Supabase) — JUnit, headless
:data      Room schema/DAOs (Flow) + outbox + live_session + DbRowCodec
:sync      supabase-kt wiring + offline-first engine
:design    Compose Material3 theme (oklch tokens) + components
:shared    DataStore snapshot shared with the Glance widget + FCM/foreground bits
:app       Compose feature screens + nav + Application/MainActivity + FCM/Timer services + widget
```

## Build & test

```bash
./gradlew :core:test          # headless logic tests (fast, no SDK matrix)
./gradlew test                # all module unit tests
./gradlew assembleDebug       # build the debug APK
```

`:core` tests run with `-Duser.timezone=UTC` for determinism (matches the web CI
+ iOS `TZ=UTC`).

## Local setup (secrets are gitignored)

1. `local.properties` — `sdk.dir=<android sdk path>` (auto-written by the IDE).
2. `secrets.properties` — `SUPABASE_ANON_KEY=…` (surfaced via `BuildConfig`).
   Get it with `supabase projects api-keys --project-ref uaxfteluwctrlgwmmfzi`.
3. `app/google-services.json` — from the Firebase Android app (FCM).

See `handover.md` for the live build state and the phased roadmap.
