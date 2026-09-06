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

**Status:** feature-complete **verified beta** at **v0.5.0 (versionCode 84)** —
the **AI gateway port** (memory, 56-tool assistant, gateway card, interview,
call ring) landed 2026-09-06 — distributed via Firebase App Distribution to
2 testers (no beta group by default). All project docs live in
[`docs/`](docs/) — see [`docs/handover.md`](docs/handover.md) for live state,
[`docs/audit-web-parity-gaps.md`](docs/audit-web-parity-gaps.md) for the
web↔Android parity scan, and
[`docs/android-only-features.md`](docs/android-only-features.md) for the reverse.

## Stack

| Concern | Choice |
|---|---|
| Language / UI | Kotlin 2.0 + Jetpack Compose + Material3 |
| Backend SDK | supabase-kt 3.x (Auth/Postgrest/Realtime/Functions, PKCE) |
| Local store | Room (offline-first, `Flow` queries) |
| Serialization | kotlinx-serialization |
| DI | Manual (`AppGraph` — NOT Hilt) |
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

**Ownership rule:** pure logic goes in `:core` with **no Android imports** (it is
plain JVM + JUnit, and every port mirrors the web Vitest + iOS XCTest cases);
wire format + supabase-kt in `:sync`; Compose and Android services in `:app`.
Anything platform-bound that `:core` needs is an interface `:core` defines and
`:app` implements.

## AI gateway (v0.5.0)

The assistant is the app's gateway with memory, 1:1 with web + iOS:

- **Memory** — `profile_facts` synced like any record (hydrate keeps pending
  upserts, realtime `upsertIfNewer`, soft-delete tombstones via the outbox),
  rituals + interview flag in `user_preferences`, per-uid prefs scrubbed on
  sign-out (`core/logic/ProfileFacts.kt`, `sync/ProfileFactsService.kt`).
- **Engine** — all 56 contract tools in `ui/assistant/AssistantTools.kt` behind
  `AssistantApi`; the turn loop (`AssistantHarness`), time cognisance
  (`AssistantTime`), receipts + undo by id, `ReplyPolish` are pure in `:core`.
  `docs/assistant-tool-contract.md` is vendored from the web repo and
  `ContractDiffTest` fails on any drift.
- **Surfaces** — `GatewayCard` on Today (brief + one moment + composer + mic),
  the 7-question interview (`InterviewFlow`), Settings › Memory "What Unstuck
  knows" (`FactsPanel`), a voice integrity guard in Talk mode.
- **Calls** — the server sends a `kind=call` FCM data push and `surface/Push.kt`
  rings a high-priority notification; the full in-app call (full-screen intent,
  voice service, outcomes) is phase C1, pending.

## Build & test

```bash
./gradlew :core:test          # headless logic tests (fast, no SDK matrix)
./gradlew :sync:test :data:test :app:testDebugUnitTest   # the rest
./gradlew test                # all module unit tests
./gradlew assembleDebug       # build the debug APK
```

Unit tests at v0.5.0: **1322 green** — `:core` 693 · `:sync` 250 · `:data` 13 ·
`:app` 366 (Robolectric where a Context is needed).

`:core` tests run with `-Duser.timezone=UTC` for determinism (matches the web CI
+ iOS `TZ=UTC`).

## Local setup (secrets are gitignored)

1. `local.properties` — `sdk.dir=<android sdk path>` (auto-written by the IDE).
2. `secrets.properties` — `SUPABASE_ANON_KEY=…` (surfaced via `BuildConfig`).
   Get it with `supabase projects api-keys --project-ref uaxfteluwctrlgwmmfzi`.
3. `app/google-services.json` — from the Firebase Android app (FCM).

See `docs/handover.md` for the live build state and the phased roadmap.
