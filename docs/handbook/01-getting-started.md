## Getting Started & Environment

Welcome to the Unstuck Android codebase. This chapter gets you from a fresh clone to a signed-in build running on an emulator, and explains the moving parts you will touch on day one: prerequisites, the Gradle module layout, the three gitignored config files that "unlock" the app, how to build/run/test, and how releases reach testers via Firebase App Distribution. It ends with a "first 30 minutes" checklist.

Repos you will be working across:
- **Android**: `/Users/ahmadtambaya/Desktop/projects/unstuck_android` (this chapter's focus)
- **Web + Supabase backend**: `/Users/ahmadtambaya/Desktop/projects/unstuck` — the web `lib/*` (with Vitest cases) is the behavioral spec the Android `:core` module is ported from, and it owns the Supabase migrations/schema all three clients share.

The app's package id and namespace are `tech.csalliance.unstuck`, and it talks to the shared Supabase project `uaxfteluwctrlgwmmfzi`.

### Prerequisites

The toolchain is pinned tightly so that all three clients (web, iOS, Android) stay in agreement. Match these versions:

| Tool | Version | Why / where it's pinned |
|---|---|---|
| **JDK** | **17** | `compileOptions` + `kotlinOptions { jvmTarget = "17" }` in every module's `build.gradle.kts`; `jvmToolchain(17)` in `core/build.gradle.kts`. CI uses Temurin 17. |
| **Gradle** | **8.9** | Wrapper-pinned in `gradle/wrapper/gradle-wrapper.properties` (`gradle-8.9-bin.zip`). Always invoke via `./gradlew` — never a globally-installed Gradle. |
| **Android Gradle Plugin (AGP)** | **8.7.3** | `gradle/libs.versions.toml` (`agp = "8.7.3"`). |
| **Kotlin** | **2.0.21** | `libs.versions.toml` (`kotlin`, plus `ksp = "2.0.21-1.0.28"`). |
| **Android SDK** | **compileSdk/targetSdk 35**, **minSdk 26** (Android 8.0) | Declared in `app/build.gradle.kts` `defaultConfig`; every library module repeats `compileSdk = 35` / `minSdk = 26`. Install "Android SDK Platform 35" + matching build-tools. |
| **Android Studio** | Recent stable (Ladybug-era or later, compatible with AGP 8.7.x) | Opens the root as a Gradle project; auto-writes `local.properties`. |
| **supabase CLI** | any current | Only needed to fetch the anon key and (for backend work) apply migrations in the `unstuck` repo. Not required just to build. |

> **Gotcha — don't use a newer JDK by default.** AGP 8.7.3 + Gradle 8.9 will complain on JDK 21+. Point Android Studio's Gradle JDK at a 17 toolchain, and set `JAVA_HOME` to a 17 JDK for command-line builds. The `jvmToolchain(17)` in `:core` will auto-provision a 17 toolchain for that module, but the Android modules rely on your run-JDK being 17.

The Android SDK location is read from `local.properties` (`sdk.dir=...`), which the IDE writes for you and which is gitignored. On this machine it points at `~/Library/Android/sdk`.

### Repo & Gradle module layout

This is a **Kotlin-DSL, version-catalog, multi-module** Gradle build. Modules are declared in `settings.gradle.kts`:

```
rootProject.name = "Unstuck"
include(":core", ":design", ":data", ":sync", ":app")
```

The dependency direction is strictly downward (`:app` depends on everything; `:core` depends on nothing Android):

```
                 :app   (Compose UI, nav, entry points, device surfaces)
                /  |  \
          :design :data :sync
                    \    /  \
                     :core   (also depends on :core)
                       |
                  (pure Kotlin/JVM — no Android, no Supabase)
```

| Module | Plugin set | What lives here |
|---|---|---|
| **`:core`** | `kotlin.jvm` + serialization | Pure Kotlin/JVM domain models + logic ports of the web `lib/*` (bucketing, ranking, recurrence, focus timer, analytics, Google-calendar mapping). **No Android, no Supabase.** Runs headless under JUnit. |
| **`:design`** | `android.library` + `kotlin.compose` | Compose Material3 theme (`UnstuckTheme`), oklch→sRGB brand tokens, shared components (`UButton`, `StatCard`, `CoralFab`, fonts in `res/font`). Builds to an AAR. |
| **`:data`** | `android.library` + serialization + **ksp** | Room offline-first store: a single `records` table (one JSON blob per synced row, keyed by `(table, id)`), plus local-only `outbox` and `live_session` tables; `LocalStore` exposing typed `Flow`s. KSP is for Room's compiler. |
| **`:sync`** | `android.library` + serialization | supabase-kt 3.x wiring + the offline-first engine: `SupabaseClientProvider`, `DbRowCodec` (the PostgREST boundary), `SyncCoordinator`, hydrator/write-through/outbox-flusher/realtime-mirror, calendar + FCM + push clients. Analog of the iOS `UnstuckSync` package. |
| **`:app`** | `android.application` + compose + serialization + firebase-appdistribution | Compose feature screens (`ui/today`, `ui/tasks`, `ui/focus`, `ui/calendar`, `ui/collections`, `ui/insights`, `ui/settings`, `ui/auth`, `ui/onboarding`, `ui/palette`), nav (`MainScaffold`), entry points (`UnstuckApp`, `MainActivity`), and device surfaces in `surface/` (FCM service, foreground focus-timer service, Glance widget, reminder alarms, `SyncWorker`). |

Note the README mentions a `:shared` module, but **there is no `:shared` module** — `settings.gradle.kts` includes only the five above, and the widget/DataStore snapshot lives in `:app` (the handover.md is the accurate source here: "Widget/snapshot live in :app (no :shared module)").

**Plugin declaration pattern.** The root `build.gradle.kts` declares all plugins `apply false` (aliased from the version catalog); each module applies the subset it needs. This is why `:core` only pulls `kotlin.jvm` and stays Android-free. Versions are centralized in `gradle/libs.versions.toml` under `[versions]`/`[libraries]`/`[plugins]` and referenced as `libs.plugins.*` / `libs.*` — add or bump dependencies there, not inline in module files.

**DI note for orientation:** despite Hilt being present in the catalog, `:app` does **not** use Hilt — it uses a hand-rolled container, `AppGraph` (see below). The Hilt aliases are vestigial.

### The three gitignored config files

`.gitignore` excludes `secrets.properties`, `keystore.properties`, `google-services.json`, plus `local.properties`, `*.keystore/*.jks`, and `firebase-service-account.json`. Three of these are the ones that change app behavior. The build is deliberately written so that **each is optional** — missing files degrade gracefully rather than failing the build.

#### 1. `secrets.properties` → Supabase URL + anon key (BuildConfig)

This is the one file you **must** have for a useful build. `app/build.gradle.kts` reads it at configuration time and injects two `BuildConfig` fields:

```kotlin
val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val supabaseUrl = secrets.getProperty("SUPABASE_URL") ?: "https://uaxfteluwctrlgwmmfzi.supabase.co"
val supabaseAnonKey = secrets.getProperty("SUPABASE_ANON_KEY") ?: ""
// ...
buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
```

Copy the template and fill in the key:

```bash
cp secrets.properties.example secrets.properties
# then edit SUPABASE_ANON_KEY=...
supabase projects api-keys --project-ref uaxfteluwctrlgwmmfzi   # prints the anon key
```

`SUPABASE_URL` defaults to the known project ref if omitted, so in practice you only need `SUPABASE_ANON_KEY`.

**What it unlocks / what happens without it.** `AppGraph` gates the entire app on a non-empty key:

```kotlin
// AppGraph.kt
val configured: Boolean = BuildConfig.SUPABASE_ANON_KEY.isNotEmpty()
val provider = if (configured) SupabaseClientProvider(SyncConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)) else null
val coordinator = provider?.let { SyncCoordinator(it, store, ..., scope) }
```

And `AppRoot.kt` branches on it:

```kotlin
when {
    !vm.configured -> SetupScreen()         // "Add SUPABASE_ANON_KEY to secrets.properties, then rebuild."
    else -> when (authed) {
        null  -> LoadingScreen()
        false -> AuthScreen(vm)
        true  -> MainScaffold(vm)
    }
}
```

So **without the key the build still compiles and installs**, but you only ever see the `SetupScreen` (it literally prints the `supabase projects api-keys` command). With the key, you reach the auth screen and can sign in. The Supabase client itself is built in `:sync` `SupabaseClientProvider` with `FlowType.PKCE` and the `unstuck://auth-callback` redirect.

#### 2. `keystore.properties` → release signing

`app/build.gradle.kts` reads it and creates a `release` signing config **only if** the keystore file it points at exists:

```kotlin
val hasReleaseKeystore = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it).exists() } == true
signingConfigs { if (hasReleaseKeystore) create("release") { storeFile = ...; storePassword = ...; keyAlias = ...; keyPassword = ... } }
buildTypes { release { isMinifyEnabled = false; if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release") } }
```

The committed (gitignored) dev values are:

```
storeFile=unstuck-release.keystore
storePassword=unstuck-dev
keyAlias=unstuck
keyPassword=unstuck-dev
```

**What it unlocks / without it.** With it (and `unstuck-release.keystore` present), `:app:assembleRelease` produces a **signed** APK at `app/build/outputs/apk/release/app-release.apk` — which is what App Distribution uploads. Without it, `hasReleaseKeystore` is false, the release variant is built **unsigned**, and debug builds are unaffected (they always use the auto-generated debug keystore). On CI and fresh clones the release build simply falls back to unsigned. The dev keystore is **not** a Play upload key — for Play distribution you swap in your own upload key and re-point `keystore.properties` (see handover.md "Release builds").

#### 3. `app/google-services.json` → Firebase / FCM

The google-services Gradle plugin is **conditionally applied** in `app/build.gradle.kts`:

```kotlin
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}
```

**What it unlocks / without it.** Present → the plugin processes it, FCM is enabled, and `UnstuckMessagingService` + `registerFcmToken(...)` (called from `MainActivity` once a session is `Authenticated`) wire push end-to-end. Absent → the plugin is skipped and the app **still builds and runs**; FCM is simply dormant (no token registration). Per handover.md the file is already configured for the live Firebase project `unstuck-46e8c` / app `tech.csalliance.unstuck`, and the backend FCM senders are deployed — but on a fresh clone you may not have it, and that's fine for everything except push.

> **One-line mental model:** `secrets.properties` decides whether you get past the setup screen; `keystore.properties` decides whether release APKs are signed; `google-services.json` decides whether push works. All three are optional to *build*.

### Building & running on the Pixel_Fold emulator

The canonical verification device is the **Pixel_Fold** AVD on **API 35** (the AVD already exists in `~/.android/avd/Pixel_Fold`). Build/install/run:

```bash
# from the repo root
./gradlew :app:assembleDebug                 # ~20 MB debug APK at app/build/outputs/apk/debug/

# boot the emulator (if not already running)
~/Library/Android/sdk/emulator/emulator -avd Pixel_Fold &
~/Library/Android/sdk/platform-tools/adb wait-for-device

# install + launch
./gradlew :app:installDebug
~/Library/Android/sdk/platform-tools/adb shell am start -n tech.csalliance.unstuck/.MainActivity
```

`adb devices` on this machine shows `emulator-5554 device`, so an emulator is typically already up. In Android Studio, just pick Pixel_Fold and hit Run — same result.

What "good" looks like on launch (validated state, per handover.md): the app installs and launches with no crash, the Supabase client initializes, Room/DI/Compose theme render, and — with the anon key present — you land on the auth screen showing the serif wordmark and coral CTA. Without the key you get the `SetupScreen`.

> **Gotcha — you cannot fully test post-auth on the emulator without a real account.** The Supabase project has `mailer_autoconfirm=false`, so there's no session without a real sign-in (email confirmation). For post-auth screenshots/flows, sign in on a device with real credentials rather than expecting auto-confirmed email signup to work on the emulator.

`MainActivity` does a few environment-relevant things on create worth knowing: `enableEdgeToEdge()`, requests `POST_NOTIFICATIONS` at runtime (Tiramisu+), schedules the periodic `SyncWorker`, registers the FCM token once authenticated, and handles the `unstuck://` deep-link callbacks for OAuth/magic-link (`auth-callback`) and Google Calendar (`calendar-callback`). The `unstuck` scheme is registered via an `intent-filter` in `AndroidManifest.xml`, and the activity is `launchMode="singleTask"` so callbacks re-enter the same task via `onNewIntent`.

### Running unit tests

Tests live in `src/test/` and are split across modules (current counts roughly: `:core` 11 test files / ~157 cases, `:design` 2, `:data` 1, `:sync` 2 — the "185 tests" figure in handover.md is the aggregate). Useful invocations:

```bash
./gradlew :core:test                  # pure JVM logic — fast, no Android SDK matrix, run this constantly
./gradlew :data:testDebugUnitTest      # Room round-trip tests (Robolectric)
./gradlew :sync:testDebugUnitTest      # DbRowCodec + SyncDecision (pure-ish pieces)
./gradlew test                         # all module unit tests
```

`:core` is the workhorse you'll run most. It's configured for determinism in `core/build.gradle.kts`:

```kotlin
tasks.test {
    useJUnit()
    systemProperty("user.timezone", "UTC")   // matches web CI + iOS TZ=UTC
}
```

This UTC pin matters: the date/bucket logic uses `java.time` with `ZoneId.systemDefault()` to reproduce JS `Date` LOCAL semantics, and the ports rely on running under UTC so they match the web Vitest and iOS XCTest cases exactly. Each `:core` test deliberately mirrors a web + iOS case so all three clients agree.

**CI** (`.github/workflows/ci.yml`) runs on push/PR to `main` with Temurin 17 and executes exactly:

```bash
./gradlew :core:test :design:testDebugUnitTest :data:testDebugUnitTest :sync:testDebugUnitTest --console=plain --stacktrace
```

Note CI runs **unit tests only** — it does not assemble or instrument-test the `:app` module. Test reports are uploaded as artifacts under `**/build/reports/tests`. Before pushing, run that same command locally; if it's green, CI will be too.

### Firebase App Distribution flow

Test builds reach testers via the `firebase-appdistribution` plugin (applied in `:app`). The config block in `app/build.gradle.kts`:

```kotlin
firebaseAppDistribution {
    appId = "1:806563895083:android:1673707a78b9d39039976e"
    artifactType = "APK"
    val sa = rootProject.file("firebase-service-account.json")
    if (sa.exists()) serviceCredentialsFile = sa.path
    testers = (findProperty("appDistTesters") as String?) ?: "ahmad@csalliance.tech"
    releaseNotes = "v0.4.1 — ..."
}
```

Auth to Firebase uses the gitignored `firebase-service-account.json` (also `.gitignore`d). The flow is **assemble the signed release first, then upload**:

```bash
./gradlew :app:assembleRelease :app:appDistributionUploadRelease
# override the invite list:
./gradlew :app:assembleRelease :app:appDistributionUploadRelease -PappDistTesters="a@x.com,b@y.com"
```

`appDistributionUploadRelease` uploads the artifact assembled by `assembleRelease` (the signed `app-release.apk`), so it must run after (or in the same invocation as) `assembleRelease`. Release notes default to the string in the build file — bump `versionCode`/`versionName` in `defaultConfig` and update `releaseNotes` for each tester drop. Current testers per handover.md: `ahmad@`, `justtesting6363@`, `zyzkazaure@`.

> **Gotcha — release uploads need both the keystore and the service account.** A fresh clone missing `keystore.properties`/`unstuck-release.keystore` will produce an *unsigned* release (App Distribution rejects unsigned APKs), and missing `firebase-service-account.json` means no upload credentials. Get both before attempting a distribution.

### Worked example: add a new BuildConfig-injected secret

Say the backend team gives you a new key (e.g. a Sentry DSN) you want available at runtime without committing it. Follow the existing `SUPABASE_ANON_KEY` pattern so it stays gitignored and degrades gracefully:

1. Add the line to `secrets.properties` (gitignored) **and** to `secrets.properties.example` (committed, with an empty value as documentation):
   ```
   SENTRY_DSN=
   ```
2. In `app/build.gradle.kts`, read it next to the existing secrets and surface it via `BuildConfig`:
   ```kotlin
   val sentryDsn = secrets.getProperty("SENTRY_DSN") ?: ""
   // inside defaultConfig:
   buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")
   ```
   (`buildConfig = true` is already enabled in `buildFeatures`, so no extra setup.)
3. Consume it in code with the same optional-gate discipline `AppGraph` uses — treat empty as "feature off" rather than crashing:
   ```kotlin
   if (BuildConfig.SENTRY_DSN.isNotEmpty()) { /* init */ }
   ```
4. Rebuild. The default-when-absent pattern keeps CI and fresh clones building.

If instead you're adding a *dependency*, add it to `gradle/libs.versions.toml` (`[versions]` + `[libraries]`/`[plugins]`) and reference `libs.*` in the module — never hardcode a version string in a module's `build.gradle.kts`.

### "First 30 minutes" checklist

A concrete path from clone to a signed-in build on the Pixel_Fold emulator:

1. **Install the toolchain.** JDK 17 (set `JAVA_HOME`/Studio Gradle JDK to 17); Android Studio; via SDK Manager: Android SDK Platform 35 + build-tools; create/keep the **Pixel_Fold API 35** AVD.
2. **Clone & open** `/Users/ahmadtambaya/Desktop/projects/unstuck_android` in Android Studio (Gradle sync writes `local.properties` with `sdk.dir`). Confirm the wrapper resolves Gradle 8.9: `./gradlew --version`.
3. **Create `secrets.properties`** — the one file you can't skip:
   ```bash
   cp secrets.properties.example secrets.properties
   supabase projects api-keys --project-ref uaxfteluwctrlgwmmfzi   # paste into SUPABASE_ANON_KEY
   ```
4. **(Optional, for push)** drop `app/google-services.json`; **(optional, for signed/release)** add `keystore.properties` + `unstuck-release.keystore`. Skip both for a first debug run.
5. **Sanity-check logic builds & tests pass** (fast, no SDK needed):
   ```bash
   ./gradlew :core:test
   ```
6. **Build the debug APK:** `./gradlew :app:assembleDebug` (expect ~20 MB at `app/build/outputs/apk/debug/`).
7. **Boot the emulator & install:**
   ```bash
   ~/Library/Android/sdk/emulator/emulator -avd Pixel_Fold &
   ./gradlew :app:installDebug
   ```
   (or just hit Run in Studio).
8. **Verify the launch state.** With the anon key set you should see the **auth screen** (serif wordmark + coral CTA), not the `SetupScreen`. If you see "Add SUPABASE_ANON_KEY to secrets.properties, then rebuild," your key is empty — fix step 3 and rebuild.
9. **Sign in with a real account** (email already confirmed) — remember `mailer_autoconfirm=false`, so emulator-only signup won't auto-confirm. On success you land in `MainScaffold` (bottom nav: Today · Tasks · [+] · Calendar · Collections).
10. **Before your first push**, run the exact CI command so you don't break `main`:
    ```bash
    ./gradlew :core:test :design:testDebugUnitTest :data:testDebugUnitTest :sync:testDebugUnitTest
    ```

Relevant files to bookmark: `/Users/ahmadtambaya/Desktop/projects/unstuck_android/settings.gradle.kts`, `/Users/ahmadtambaya/Desktop/projects/unstuck_android/build.gradle.kts`, `/Users/ahmadtambaya/Desktop/projects/unstuck_android/app/build.gradle.kts`, `/Users/ahmadtambaya/Desktop/projects/unstuck_android/gradle/libs.versions.toml`, `/Users/ahmadtambaya/Desktop/projects/unstuck_android/secrets.properties.example`, `/Users/ahmadtambaya/Desktop/projects/unstuck_android/.github/workflows/ci.yml`, `/Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/AppGraph.kt`, `/Users/ahmadtambaya/Desktop/projects/unstuck_android/app/src/main/kotlin/tech/csalliance/unstuck/ui/AppRoot.kt`, `/Users/ahmadtambaya/Desktop/projects/unstuck_android/README.md`, and `/Users/ahmadtambaya/Desktop/projects/unstuck_android/handover.md` (the live source of truth for build state).
