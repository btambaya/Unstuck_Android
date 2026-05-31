## Conventions, Testing, Contribution Workflow & Gotchas

This chapter is your "how we actually write code here, how we test it, how we ship it, and where the bodies are buried" reference. Read it after you've skimmed the module map (`README.md` → Modules) so the names land. Everything below is drawn from the real code in `/Users/ahmadtambaya/Desktop/projects/unstuck_android` (Android) and `/Users/ahmadtambaya/Desktop/projects/unstuck` (web + Supabase backend).

The single most important rule, repeated everywhere: **the web app + Supabase DB are the source of truth for behavior and data; the design mockups are a look cue only.** Every `:core` logic port has a JUnit test mirroring the web's Vitest case and the iOS XCTest case, so all three clients agree. When in doubt, go read `lib/*` in the web repo.

---

### Coding conventions actually used

#### Package layout and module boundaries

Everything lives under `tech.csalliance.unstuck.*`, split across five Gradle modules with strict dependency direction (`:core` ← `:data`/`:design` ← `:sync` ← `:app`):

```
:core    tech.csalliance.unstuck.core.{model,logic,time}   pure Kotlin/JVM, no Android, no Supabase
:data    tech.csalliance.unstuck.data{,.db}                 Room store + outbox + live_session
:design  tech.csalliance.unstuck.design.{theme,color,component}  Compose M3 theme + chrome
:sync    tech.csalliance.unstuck.sync                       supabase-kt engine + DbRowCodec + API clients
:app     tech.csalliance.unstuck.{ui.*,surface}             Compose screens, AppViewModel, AppGraph, OS surfaces
```

Hard rules the codebase enforces:
- `:core` is **pure** — no Android imports, no Supabase. It uses only `java.time` and `kotlinx-serialization`. This is what lets `./gradlew :core:test` run headless and fast.
- The PostgREST/snake_case boundary lives **only** in `:sync/DbRowCodec.kt`. Domain models stay camelCase everywhere else.
- Google-Calendar concerns are kept out of `:core`/`:data` via injectable seams (see `WriteThrough.pushCalBlock` below) so those modules stay backend-agnostic.

#### StateFlow + `collectAsStateWithLifecycle`

There is exactly one app-wide state holder, `AppViewModel` (`app/.../ui/AppViewModel.kt`). It exposes every synced collection as a `StateFlow` derived from the Room `LocalStore` Flows, using a small helper:

```kotlin
private fun <T> sf(flow: Flow<List<T>>): StateFlow<List<T>> =
    flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

val tasks = sf(store.tasks())
val blocks = sf(store.blocks())
// liveSession / pendingCount use stateIn directly with a non-list initial value
```

`SharingStarted.WhileSubscribed(5_000)` is the standard pattern — the upstream Room query stays hot for 5s after the last collector leaves, surviving config changes without re-querying. The one exception is `authed`, which uses `SharingStarted.Eagerly` with a `null` initial value (`null` = "auth not resolved yet", which `AppRoot` renders as a spinner).

In composables, **always** collect with `collectAsStateWithLifecycle()` (from `androidx.lifecycle.compose`), never plain `collectAsState()`:

```kotlin
@Composable
fun TasksScreen(vm: AppViewModel, ...) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    // derive view state in-memory with :core
    val list = visibleTasks(view, tasks, blocks, vm.nowMs(), ...)
}
```

Screens never touch the store or the sync engine directly — they read `vm.<collection>` and call `vm.<action>()`. Derived view state (which tasks are visible, what to "Start Next", analytics) is computed **in memory** by calling `:core` pure functions (`visibleTasks`, `pickStartNext`, `Analytics`) on the collected lists. Same model as web and iOS.

#### The `launchWrite` write pattern

Every mutating action on `AppViewModel` follows one shape. There is a private helper:

```kotlin
private fun launchWrite(block: suspend () -> Unit) { viewModelScope.launch { block() } }
```

and write actions are thin: apply the `:core` mutation rule (e.g. `applyCompletion`, `bumpMoveCount`), then call through the sync engine's `WriteThrough` (`write` is `graph.coordinator?.write`, nullable until the anon key is configured):

```kotlin
fun toggleDone(task: TaskItem) = launchWrite {
    val flipped = task.copy(done = !task.done)
    write?.upsertTask(applyCompletion(flipped, prior = task, nowISO = isoNow()))
}
fun updateTask(task: TaskItem) = launchWrite { write?.upsertTask(task.copy(updatedAt = isoNow())) }
```

`WriteThrough` (`sync/.../WriteThrough.kt`) is the optimistic-write engine. Each `upsertX`:
1. writes the model JSON to Room immediately (`store.upsert(...)`) — the UI updates instantly because the Room `Flow` re-emits;
2. enqueues a server op in the **outbox** (`enqueue("tasks", id, "upsert", DbRowCodec.encodeTask(t).toString())`).

The `OutboxFlusher` later drains the queue to Supabase FIFO and **dependency-ordered** — a `cal_blocks` upsert carries `dependsOn = task.id` so the parent task flushes first. Two conventions worth internalizing:
- **Cascades happen in `AppViewModel`, not the store.** E.g. `deleteTask` first deletes the task's cal_blocks + captures, then the task, mirroring the web `deleteTask`. `deleteTag`/`deleteLifeArea`/`renameTag` cascade the name change across every affected task.
- **Whole-row JSONB mutations are read-modify-write under a `Mutex`.** Collections carry their items inline in one JSONB row, so `mutateCollection` re-resolves the latest row from Room inside `collectionMutex.withLock { ... store.collections().first() ... }` before writing. This prevents a rapid fast-add burst from persisting a stale snapshot and dropping items (matches the web's functional-update guard).

Injectable seams for determinism: `WriteThrough.nowMillis` is an overridable `() -> Long` (default `System.currentTimeMillis()`) so tests aren't at the mercy of the wall clock.

#### Compose idioms

- **Theme tokens via `UTheme`, not `MaterialTheme`.** Inside any composable, `val c = UTheme.colors` then `c.coral`, `c.ink`, `c.coralDeep`, etc. `UTheme` (`design/.../theme/Theme.kt`) is a thin accessor over a `staticCompositionLocalOf` (`LocalUnstuckColors`). The full brand palette is the `UnstuckColors` immutable data class (light/dark companions). Color tokens are named, not raw hex — neutrals are exact mockup hex (`#1A1C26` ink), accents are oklch rendered through the oklab→sRGB converter in `design/.../color/Oklch.kt`.
- **Typography via `UFont`**, e.g. `UFont.serifItalic(26)`, `UFont.sans(15)`, `UFont.mono(12)` (Instrument Serif / Geist / IBM Plex Mono, bundled in `:design/res/font`).
- **Shared chrome lives in `:design/component`** — `AppBar`, `BottomNavBar`, `CoralFab`, `MdField`/`MdToggle`/`MdSegment`, `FilterPill`, `StatCard`, `ItemRow`, `ColorChip`, the `Orbit` mark. Reuse these; don't hand-roll a button.
- **State hoisting + callback props.** Screens take `vm: AppViewModel` plus lambdas (`onOpen`, `onStartFocus`, `onSearch`). Navigation is a callback up to `MainScaffold`, never a `NavController` push from inside a leaf.
- **Settings/theme reactivity is centralized.** `AppRoot` owns the `UnstuckTheme` wrapper and feeds it `dark` / `accent` / `fontScale` from `vm.settings` (a `StateFlow` over `SettingsStore`). A settings change re-themes the whole app live. Note: identity getters like `vm.currentEmail` are **non-reactive** plain getters — don't expect them to recompose.
- **System back** is handled with stacked `BackHandler`s in `MainScaffold`, top layer wins (focus overlay → route stack → non-Today-tab fallback). Modal bottom sheets handle their own back.

#### Serialization conventions

- All models are `@Serializable` with **camelCase** field names. Optional fields are nullable with defaults (`val tags: List<String>? = null`).
- The `LocalStore` JSON instance uses `ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true` — forward-compatible with new fields.
- `Recurrence` is a sealed class with a hand-written `RecurrenceSerializer` emitting the tagged-union shape `{kind, daysOfWeek?, until?}` to match the web JSONB exactly.

---

### Worked example: adding a field to `TaskItem` end-to-end

Suppose product wants tasks to carry an optional `energyLevel` (a string like `"low"`/`"high"`) that the user picks in the New-task sheet and that round-trips to Supabase. **Confirm with the web app first** — if the web/DB doesn't have it, you don't add it (the "web wins over mockup" rule). Assuming the web `tasks` table and `lib/types.ts` already have `energy_level` / `energyLevel`, here's the full Android pass.

**1. `:core` model — `core/.../model/Models.kt`.** Add the field to `TaskItem` as a nullable with a default so old rows still decode:

```kotlin
@Serializable
data class TaskItem(
    val id: String,
    val name: String,
    // ...
    val energyLevel: String? = null,   // NEW
    val createdAt: String,
    val updatedAt: String,
)
```

**2. `:sync` DbRowCodec — `sync/.../DbRowCodec.kt`.** Add it to `TaskRow` (the server DTO) with `@SerialName` for the snake_case column, then wire both the `constructor(t: TaskItem)` and `toModel()`:

```kotlin
@Serializable
internal data class TaskRow(
    // ...
    @SerialName("energy_level") val energyLevel: String?,   // NEW
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
) {
    constructor(t: TaskItem) : this(/* ...existing... */, t.energyLevel, t.createdAt, t.updatedAt)
    fun toModel() = TaskItem(/* ...existing... */, energyLevel = energyLevel, createdAt = createdAt, updatedAt = updatedAt)
}
```

Because `explicitNulls = true` in `rowJson`, a `null` `energyLevel` serializes as explicit JSON `null`, so an upsert **clears** the column when the user removes it — matching the web `?? null` writer. If (and only if) this field must never be clobbered by an upsert, follow the `reason_logs.duration_sec` pattern: strip the key post-encode in `encodeTask`. For a normal user-editable field, leave it.

**3. Store/WriteThrough — no change needed.** `WriteThrough.upsertTask` already serializes the whole `TaskItem` to Room and calls `DbRowCodec.encodeTask`. The single JSON-blob `records` table (`:data`) is schemaless, so there's no Room migration — the new field just appears in the blob.

**4. Backend — the `unstuck` repo.** Add a Supabase migration adding `energy_level` to the `tasks` table, and update `lib/use-tasks.ts` (`taskToDbRow` / row→model). Apply with `supabase db push --yes </dev/null` against project `uaxfteluwctrlgwmmfzi` (see the deploy section). RLS already scopes `tasks` by `user_id`.

**5. UI control — `app/.../ui/tasks/NewTaskSheet.kt` (+ `TaskDetailSheet.kt` for editing).** Add a `MdSegment`/chip group bound to a `remember { mutableStateOf<String?>(null) }`, then pass it into `vm.addTask(...)`. Extend `AppViewModel.addTask` to accept and forward the new parameter:

```kotlin
fun addTask(name: String, /* ... */, energyLevel: String? = null): TaskItem {
    val now = isoNow()
    val t = TaskItem(id = newUuid(), name = name.trim(), /* ... */, energyLevel = energyLevel,
        createdAt = now, updatedAt = now)
    launchWrite { write?.upsertTask(t) }
    return t
}
```

**6. Test — `sync/.../DbRowCodecTest.kt`.** Add a round-trip assertion (this is the contract test that must mirror the web/iOS case):

```kotlin
@Test fun energyLevelRoundTrips() {
    val t = task().copy(energyLevel = "low")
    val o = DbRowCodec.encodeTask(t)
    assertTrue(o.containsKey("energy_level"))         // snake_case column present
    assertFalse(o.containsKey("energyLevel"))         // not the camelCase name
    assertEquals("low", DbRowCodec.decodeTask(o).energyLevel)
}
@Test fun energyLevelNullClearsColumn() {
    assertEquals(JsonNull, DbRowCodec.encodeTask(task().copy(energyLevel = null))["energy_level"])
}
```

Run `./gradlew :core:test :sync:testDebugUnitTest` and confirm the matching web Vitest case in `lib/use-tasks.test.ts` agrees.

---

### Worked example: adding a new screen / route

Navigation is **not** Jetpack Navigation graphs for overlays — it's a hand-rolled stack in `MainScaffold` (`app/.../ui/MainScaffold.kt`). There are two kinds of destination:

- **Tabs** — the four bottom-nav entries in the `NAV` list (`today`, `tasks`, `calendar`, `lists`), selected by a `var tab by rememberSaveable { mutableStateOf("today") }`.
- **Overlay routes** — a `sealed interface Route` rendered from a `mutableStateListOf<Route>()` stack on top of the tab content. Existing routes: `Detail(taskId)`, `Collection(id)`, `Insights(deep)`, `Settings`, `SettingsSub(section)`, `Palette`.

To add, say, an **Archive** overlay screen:

1. Create `app/.../ui/archive/ArchiveScreen.kt`, a `@Composable fun ArchiveScreen(vm: AppViewModel, onBack: () -> Unit)` that reads `vm` flows with `collectAsStateWithLifecycle` and uses `:design` chrome (`AppBar(... leading = Leading.BACK, onLeading = onBack)`).
2. In `MainScaffold`, add to the sealed interface: `data object Archive : Route`.
3. Render it in the `stack.lastOrNull()?.let { route -> when (route) { ... } }` block:
   ```kotlin
   Route.Archive -> ArchiveScreen(vm, onBack = ::pop)
   ```
4. Push it from wherever it's triggered (e.g. an `AvatarMenu` item): `onArchive = { sheet = null; push(Route.Archive) }`.

`BackHandler` already pops the stack for you (no extra wiring). If your screen needs to live in the bottom nav instead, add a `NavSpec` to `NAV` and a branch in the `when (tab)` block. Note `onSelect`/tab changes call `stack.clear()`, so overlays don't survive a tab switch.

---

### Testing strategy

#### What's tested and where

The test suite is overwhelmingly **JVM unit tests in `:core`** plus targeted tests in the other modules — **185 total**, all green:

| Module | Kind | Count | Covers |
|---|---|---|---|
| `:core` | JUnit (headless JVM) | 157 | bucketing (`TaskBucket`/`VisibleTasks` + slip), `PickStartNext`, `Recurrence` (materialise/regen/label), `FreeSlots`, `FocusTimer`, `Analytics` (H1–H7 + insights), `AuthErrors`, `GoogleSyncMapping`, `TaskMutations`, model serialization round-trips |
| `:design` | JUnit | 7 | oklch→sRGB converter + token assertions |
| `:data` | Robolectric | 6 | Room round-trips, JSONB shape, external `g_` preservation, outbox FIFO |
| `:sync` | JUnit | 15 | `DbRowCodec` (10 — the PostgREST contract) + `SyncDecision` (5) |

The philosophy: **all the real logic lives in `:core` as pure functions, so it's tested without an emulator or the Supabase SDK.** Each `:core` test file mirrors, case-for-case, the web Vitest spec and the iOS XCTest spec — this is how the three clients stay in lockstep on tricky rules (recurrence, slip detection, calibration).

Key test conventions:
- **Determinism via UTC.** `:core/build.gradle.kts` sets `systemProperty("user.timezone", "UTC")` in `tasks.test`, matching web CI and iOS `TZ=UTC`. Date logic uses `java.time` + `ZoneId.systemDefault()` to reproduce JS `Date` LOCAL semantics; ISO strings compare lexicographically.
- **Shared fixtures.** `core/src/test/.../Fixtures.kt` provides `mkTask()`, `mkBlock()`, `sess()`, `cap()`, a fixed `NOW`, and `iso(ms)` helpers that read 1:1 with the web `task()`/`block()` helpers.
- **`:data` uses Robolectric** (`testImplementation(libs.robolectric)`, `testOptions { unitTests.isIncludeAndroidResources = true }`) so Room runs in a plain JVM test, no device.
- **`DbRowCodecTest` is the contract test** — it asserts snake_case top-level columns, camelCase JSONB internals (`recurrence.daysOfWeek` survives), explicit-null clearing, the `duration_sec` omit exception, FK uuid-or-null, and full round-trips. Add to it whenever you touch the row shape.

#### How to run

```bash
./gradlew :core:test                                          # fast headless logic (no SDK matrix)
./gradlew :core:test :design:testDebugUnitTest \
          :data:testDebugUnitTest :sync:testDebugUnitTest      # the full 185
./gradlew test                                                # all module unit tests
./gradlew :app:assembleDebug                                  # build the debug APK
```

JDK 17, Gradle wrapper pinned to **8.9** (AGP 8.7.3). Use the wrapper (`./gradlew`), not a system Gradle.

#### The on-device emulator screenshot pass

There are no instrumented UI tests in the suite; visual verification is a **manual emulator screenshot pass** on the `Pixel_Fold` AVD (API 35). The Android SDK is installed locally (`~/Library/Android/sdk`). Gotchas captured from doing this (see the GOTCHAS section): the headless swiftshader emulator freezes the framebuffer — launch with `-gpu host` and a visible window (or toggle recents) to capture a live frame; the screenshot is downscaled (map tap coordinates back to the real 1080-wide device); and a connected hardware keyboard suppresses the soft IME. A **full post-auth** screenshot pass is blocked on the emulator because Supabase `mailer_autoconfirm = false` (no session without a real sign-in) — verify post-auth screens on a signed-in physical device instead.

---

### Release / deploy workflow

#### Android release → Firebase App Distribution

1. **Version bump** in `app/build.gradle.kts` `defaultConfig`: increment `versionCode` (integer, currently 14) and set `versionName` (currently `"0.4.1"`).
2. **Release notes** in the `firebaseAppDistribution { releaseNotes = "…" }` block — keep it a single clean string; internal escaped quotes have broken builds.
3. **Run tests** (`./gradlew :core:test :sync:testDebugUnitTest` at minimum, ideally the full 185).
4. **Build the signed release:** `./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`. Signing reads the gitignored `keystore.properties` + `unstuck-release.keystore` (dev passphrase `unstuck-dev`); on a fresh clone without it, release builds fall back to unsigned. For Play, swap in your own upload key.
5. **Upload to testers:** `./gradlew :app:appDistributionUploadRelease` (uploads the on-disk APK from step 4 — build first). Override the invite list with `-PappDistTesters="a@x.com,b@y.com"` (defaults to the owner). Auth uses the gitignored `firebase-service-account.json`; app id `1:806563895083:android:…`, Firebase project `unstuck-46e8c`. Current testers: ahmad@csalliance.tech / justtesting6363@gmail.com / zyzkazaure@gmail.com.
6. **Push to git** — remote `github.com/btambaya/Unstuck_Android.git`, branch `main`.

There is no CI screenshot/instrumented stage; CI runs `:core:test`.

#### Backend deploy (the `unstuck` repo)

The Supabase CLI on this machine is authenticated and linked to project `uaxfteluwctrlgwmmfzi` ("Unstuck"). Per the team workflow, **after backend changes apply migrations and push to `main`** (Cloudflare Pages auto-deploys the web app from `main`; the deployed clients run against the linked Supabase project, so a stale schema breaks them).

- **Migrations:** `supabase migration list` / `--dry-run` first, then `supabase db push --yes </dev/null` (connection/password cached). Confirm `.env*`/secrets stay gitignored before `git add -A`.
- **Edge functions:** `supabase functions deploy <fn> --project-ref uaxfteluwctrlgwmmfzi`. The notification stack lives in `supabase/functions/` with `_shared/{fcm,apns}.ts`; `_shared/fcm.ts` sends **data-only** FCM payloads (RS256 service-account JWT, FCM HTTP v1) so Android renders in every app state. `register-push-token` accepts `{fcmToken, platform}` and `send-session-recap`/`send-morning-brief` branch FCM-for-android / APNs-for-ios.
- **Ad-hoc SQL:** `supabase db query --linked` (Management API, no DB password).

---

### Consolidated GOTCHAS (high-signal)

These are real bugs that bit this codebase. Internalize them.

1. **ktor `Content-Type` on `functions.invoke`.** Every `client.functions.invoke("…") { setBody(...) }` **must** set `contentType(ContentType.Application.Json)`, or ktor throws "Fail to prepare request body" and the call silently fails. Every body call in `CalendarClient.kt` and `Clients.kt` does this — copy the pattern.

2. **kotlinx omits default values in request bodies.** The row JSON uses `encodeDefaults`, but request-body DTOs in `CalendarClient` do **not** — so a defaulted `provider = "google"` got dropped from the body and the server rejected it ("Only google supports authorize"). Server-**required** fields must have **no default** (see `AuthorizeBody(val redirectUri: String, val provider: String)` — `provider` is passed explicitly, not defaulted).

3. **snake_case server rows need a DTO.** Most endpoints return camelCase, but some return raw DB rows in snake_case. `calendar-sync/connections` is the example: `CalendarClient.ConnRow` decodes the snake_case shape with `@SerialName`, then maps to the domain model. Don't try to decode snake_case JSON straight into a camelCase model — it silently yields defaults/empties.

4. **DbRowCodec dual-casing.** Top-level columns are snake_case (`@SerialName` per field on the `…Row` DTO); JSONB internals stay camelCase (`recurrence.daysOfWeek`, `objectives`). **Never** apply a global `SnakeCase` naming strategy — it would mangle the nested JSONB. `rowJson` has `explicitNulls = true` so a null clears the column on upsert (web parity). The lone exception: `reason_logs.duration_sec` is stripped post-encode in `encodeReasonLog` so an upsert never clobbers a server-set value.

5. **Google `events.list` needs RFC3339, and custom-scheme redirects are rejected.** When pulling (`pullCalendar`, window [−7d, +30d]) you **must** send RFC3339 timestamps, not bare `YYYY-MM-DD` dates, or Google returns 0 events (`GoogleSyncMapping.kt` builds the proper ISO via `blockToIsoRange`). And Google blocks custom-scheme redirects for Web OAuth clients, so the OAuth redirect is the **HTTPS bounce page** `https://unstuck-602.pages.dev/calendar-callback`, which forwards `?code&state` to `unstuck://calendar-callback` → `MainActivity.handleAuthOrCalendar` → `completeGoogleConnect`. (Separately, Supabase Auth Redirect URLs must list `unstuck://auth-callback` for email/magic-link/Google sign-in to round-trip.)

6. **Notification-channel importance is immutable after first creation.** Changing a channel's importance has no effect once it exists — you must use a **new, stable channel id**. That's why `NotificationChannels` uses fresh ids (`unstuck_reminders`, `unstuck_recap`, …) and never reuses the old `unstuck_push`. Channels are created once at app start (`ensureAll`); re-issuing the same `NotifIds` value updates a notification in place rather than stacking.

7. **Exact-alarm permissions are best-effort.** Pre-task reminders use `AlarmManager` (not WorkManager) for punctuality. The manifest declares `SCHEDULE_EXACT_ALARM` + `USE_EXACT_ALARM` + `RECEIVE_BOOT_COMPLETED`, but `ReminderScheduler.setAlarm` still checks `am.canScheduleExactAlarms()` (API 31+) and **falls back to an inexact `am.set`** when the permission isn't granted. Alarms are rescheduled on boot (`ReminderReceiver` listens for `BOOT_COMPLETED`).

8. **Emulator screenshot downscaling + hardware-keyboard IME.** The emulator screenshot is downscaled — map tap coordinates back to the real 1080-wide device. A connected **hardware keyboard suppresses the soft IME**, so text-entry screens look wrong. And the headless swiftshader emulator freezes the framebuffer — use `-gpu host` with a visible window (or a recents toggle) to capture live frames.

9. **Outbox stall from external `g_` blocks.** External Google events (ids starting `g_`, `kind = EXTERNAL`) are mirrored read-only and must **never** be enqueued to our `cal_blocks` table — the row id/shape isn't ours, the upsert fails forever, and it **stalls the entire outbox** (`OutboxFlusher` stops when no op makes progress). `WriteThrough.upsertCalBlock` early-returns for them; `deleteCalBlock` skips the enqueue for `g_` ids.

10. **`scheduleTask` slip-detector honesty.** Re-scheduling must update a block **in place** (don't mint a new one each tap → duplicate blocks), and bump `moveCount` **only on a real date/time change** — otherwise the slip detector inflates. See `AppViewModel.scheduleTask` (the `persistOrMove` port). Recurring tasks diff via `regenerateForTask` rather than re-inserting a whole horizon.

11. **Whole-row JSONB upserts need read-modify-write.** Collections carry items inline; rapid fast-add can persist a stale snapshot and drop items. Always go through `AppViewModel.mutateCollection` (Mutex + `store.collections().first()` re-resolve).

12. **`exported=false` receivers can't be triggered with `adb am broadcast`.** `NotificationActionReceiver`/`ReminderReceiver` are correctly `exported=false`, so test their actions via the UI or the `PendingIntent`, not adb broadcasts.

13. **`firebaseAppDistribution { releaseNotes }` is fragile.** Keep it a single clean string — internal escaped quotes have broken the build.

---

### Where to start when you join

- Read `:core/model/Models.kt` (the data shapes), then `:core/logic/VisibleTasks.kt` + `PickStartNext.kt` (the rules), then run `./gradlew :core:test`.
- Read `AppViewModel.kt` top to bottom — it's the map of every user action.
- Trace one write: `vm.toggleDone` → `applyCompletion` (`:core`) → `WriteThrough.upsertTask` (`:sync`) → `store.upsert` + outbox enqueue (`:data`) → `OutboxFlusher` → `SyncGateway.upsert` (PostgREST). That round trip is the whole architecture in miniature.
- Keep the web repo open. When you're unsure how something should behave, the answer is in `lib/*` (and its Vitest cases), and your job is to make the Android `:core` test mirror it.

Reference docs to keep handy: `README.md` (stack + build), `handover.md` (live phase state), `docs/APP_GUIDE.md` (full feature walkthrough), and the audit logs `audit-web-parity-gaps.md` / `audit-sweep2.md` (known gaps and the next batch).
