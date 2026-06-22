import java.util.Properties

// :app — the Compose application: feature screens, nav, entry points (Application
// + MainActivity), and the device surfaces. Uses a lightweight manual DI
// container (AppGraph) rather than Hilt — fewer codegen moving parts, adequate
// for one app process.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.firebase.appdistribution)
}

// FCM: the google-services plugin is applied automatically once you drop the
// Firebase config (app/google-services.json) in — until then it's skipped so
// the app still builds without Firebase configured.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// Secrets live in secrets.properties (gitignored). Anon key surfaced via
// BuildConfig; the project URL defaults to the known ref.
val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val supabaseUrl = secrets.getProperty("SUPABASE_URL") ?: "https://uaxfteluwctrlgwmmfzi.supabase.co"
val supabaseAnonKey = secrets.getProperty("SUPABASE_ANON_KEY") ?: ""

// Release signing config from keystore.properties (gitignored). Absent on CI /
// fresh clones → release builds fall back to unsigned (debug builds unaffected).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it).exists() } == true

android {
    namespace = "tech.csalliance.unstuck"
    compileSdk = 35

    defaultConfig {
        // applicationId rebranded to the domain (matches iOS io.unstucknow.app)
        // for the Play Store launch. The Kotlin source package / `namespace`
        // stays tech.csalliance.unstuck — it's internal and invisible, so we
        // don't churn 100+ files. Firebase + App Distribution are re-pointed at
        // the new Android app registered under this id (appId below).
        applicationId = "io.unstucknow.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 64
        versionName = "0.4.50"
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        // In-app feedback bubble — on for beta; flip to false (or repurpose the copy)
        // for a public release.
        buildConfigField("Boolean", "FEEDBACK_ENABLED", "true")
        buildConfigField("Boolean", "ASSISTANT_ENABLED", "true")
        // Cloudflare voice-proxy WSS URL; set after deploying workers/voice-proxy.
        // Empty = voice mode hidden (graceful).
        buildConfigField("String", "VOICE_PROXY_URL", "\"${project.findProperty("voiceProxyUrl") ?: ""}\"")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8: code shrinking/obfuscation + resource shrinking. Keep rules live in
            // app/proguard-rules.pro (kotlinx-serialization is load-bearing — see that
            // file's header). Uses the AGP "optimize" default config.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        // False positive: we use registerForActivityResult on a Compose
        // ComponentActivity (androidx.activity), not a Fragment.
        disable += "InvalidFragmentVersionForActivityResult"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Robolectric needs Android resources on the unit-test classpath (mirrors
    // :data / :sync). Lets the AppViewModel orchestration tests run a real
    // AppGraph (in-memory Room → real LocalStore + WriteThrough) on the JVM.
    testOptions { unitTests.isIncludeAndroidResources = true }
}

// Firebase App Distribution — `./gradlew :app:appDistributionUploadRelease`
// uploads the signed release to testers. Auth uses the gitignored
// firebase-service-account.json.
// DEFAULT (for now): distribute ONLY to the two test accounts below — NOT the
// full `beta` group — so we don't spam every tester with each iteration.
// To push to the whole beta group (when ready): pass -PappDistGroups=beta.
// Override testers with -PappDistTesters="a@x.com,b@y.com".
firebaseAppDistribution {
    // Firebase Android app for io.unstucknow.app (registered 2026-06-12).
    appId = "1:806563895083:android:26d9fa661bf5944a39976e"
    artifactType = "APK"
    val sa = rootProject.file("firebase-service-account.json")
    if (sa.exists()) serviceCredentialsFile = sa.path
    testers = (findProperty("appDistTesters") as String?) ?: "justtesting6363@gmail.com,zyzkazaure@gmail.com"
    // No group by default → only the two testers above are notified. Pass
    // -PappDistGroups=beta to release to the full beta group when told to.
    groups = (findProperty("appDistGroups") as String?) ?: ""
    releaseNotes = "v0.4.50 — NEW: a hands-free focus coach. During a focus session the app now speaks up so you don't lose track of time — a heads-up at five minutes left and when your block is done (\"add five, stop, or keep going?\"). Turn on 'Voice replies' (Settings → Focus) and you can answer out loud — \"add ten\", \"stop\", \"keep going\" — without touching your phone. It's all on-device (nothing leaves your phone, no extra data use), the mic only opens for a moment after it asks, and you can switch the whole thing off in Settings → Focus. Earlier: v0.4.49 — Privacy + reliability hardening. The in-app assistant (chat AND voice) now stays strictly on your tasks, schedule and lists — it politely declines off-topic requests and won't reveal how it's built. Plus a large cross-platform quality pass: cross-device sync is safer (offline edits no longer lost; a task with an unrecognised repeat type no longer disappears), the app is faster and noticeably smaller (R8), and there's a big accessibility round (larger touch targets, TalkBack labels, accessible charts) plus dozens of UX fixes (onboarding keeps what you typed, clearer empty/error states, a real done-button on the Tasks tab, and more). Earlier: v0.4.47 — Three fixes from your testing: (1) Repeating tasks no longer flood the All list — a 'every Friday' task used to show up four or five times there. Repeating tasks now live under the Recurring filter only, and each day's occurrence appears in Today when its day comes (tick or skip just that day without touching the series). (2) Cross-device sync: completing a task on the web now reliably reflects on your phone — a stale offline edit could previously overwrite the web change on the next sync. (3) The Start-Next card on Today is usable again: it shows your next SCHEDULED task for today; if nothing's scheduled, the lowest-friction one (shortest estimate); and if today's empty it points you to your backlog instead of yanking a random task out of it. Plus: Insights now shows your real numbers and charts from your very first focus session (no more empty dashes until session 5). Earlier: v0.4.41 — Fixed phantom reminders: a reminder for a task you'd deleted (or that was removed on another device) could still fire. Reminders now double-check the task still exists, isn't done, and is still scheduled at the moment they fire. Earlier: v0.4.40 — Fixed the sign-in screen flashing for a split second when you open the app already signed in (it now shows a brief loading dot, then goes straight to Today). Earlier: v0.4.39 — Insights now plots an estimate-calibration chart (how your estimates compare to actual time — dots near the line mean you nailed it). Onboarding asks what gets you stuck and lets you jot a first small step for your first task. And the Week calendar is now interactive: tap any empty slot to schedule a task there. Earlier: v0.4.38 — Restores Voice mode (Talk), which was unintentionally hidden in the 0.4.37 build, and bundles all the 0.4.37 reliability + accessibility fixes below. v0.4.37 — Big reliability + accessibility pass (from a full three-platform code review). Voice mode: locking the screen or switching apps now ends the call cleanly instead of leaving audio running in the background; an incoming phone call stops the assistant; the screen stays awake during a call; and the first spoken reply is no longer dropped. Assistant: closing the bubble mid-action no longer abandons a half-finished request (it completes in the background), and your conversation is cleared when you sign out (shared-device privacy). Sync: notes captured during a focus session can no longer be silently lost mid-session, and offline edits survive a refresh more reliably. New-task sheet: everything you typed now survives rotation / dark-mode flips. Focus sounds now pause properly for calls and other apps' audio. Plus a large accessibility round: TalkBack labels on all toolbar + assistant buttons, switches and selectors announce their state, bigger touch targets, clearer disabled buttons, and better text contrast. Earlier: v0.4.36 — Voice mode: much better echo handling. The phone now switches into a proper 'call' audio mode so its built-in echo cancellation actually works, and — best of all — with earphones or a Bluetooth headset you can now just TALK OVER the assistant to interrupt it naturally (no button needed). On the loudspeaker it still waits its turn (use the Interrupt button or tap the circle) since speaker echo is harder; a deeper speaker fix is in the works. v0.4.35 — Voice mode: added an Interrupt button (and you can tap the circle) to cut the assistant off mid-reply and talk right away — it stops speaking, drops the rest, and listens. v0.4.34 — Voice mode: fixed the model cutting itself off mid-sentence and restarting. The phone speaker was leaking its own voice into the mic, which the server mistook for you interrupting. It now stays quiet on the mic while it's speaking (plus echo cancellation), so replies play through cleanly. Speak after it finishes. v0.4.33 — Voice mode now connects (fixed the server relay to Qwen-Omni that was failing the instant you tapped Talk), and if anything does go wrong it now tells you what instead of a generic 'something went wrong'. v0.4.32 — NEW: real voice mode. Open the bubble → Assistant → tap 'Talk' and just speak naturally, like a call with ChatGPT or Claude. It listens, thinks, schedules/creates/updates your tasks and lists while you talk, and talks back — you can interrupt it mid-sentence (barge-in). Live captions show what each of you said. Tap End to stop. (Needs mic permission the first time.) The earlier tap-to-dictate mic + read-aloud speaker are still in the chat too. v0.4.31 — Assistant fixes: it no longer says 'can't reach' on slower replies (longer timeout + an automatic retry), and your conversation now survives closing and reopening the bubble (and the app) — plus a 'New chat' button to start fresh. Earlier (v0.4.30) — NEW: an in-app assistant. Tap the bubble (bottom-right) and switch to Assistant, then just brain-dump — try 'add a dentist appt tomorrow 2pm and put milk on groceries', 'what should I start?', or 'move my report to Friday morning'. It creates and schedules tasks, adds to your lists, asks when it needs a detail, and checks before deleting. You can talk to it too: tap the mic to dictate and the speaker to hear replies (voice runs on-device). Feedback is still here — use the toggle at the top of the bubble. Earlier (v0.4.29) — Web-parity round: Focus now has an overrun check-in when you pass your estimate (Add 10 min / I'm in the zone / Stop here) instead of just turning the timer red; your task's first physical action shows during focus and as the Start-Next headline (with a 'Pick another' button); recurring tasks can now have an end date (Repeat → Ends); the teal area colour finally shows (Health area was gray) and you can pick teal + red; the task detail shows 'In progress' once you've focused on it; dragging a calendar block to a new time now counts as a reschedule; and leaving a running focus session asks first (if Soft exit is on). Earlier (v0.4.28) — Final polish round: ambient focus audio now actually plays when the Ambient setting is on; the 'just now' recap card on Today clears itself after a while; the focus ring timer stays tidy past an hour; you can reschedule early-morning / late-evening calendar blocks to any time; account deletion works even without an email (type DELETE); inbox timestamps stay current; onboarding survives a rotation (keeps your step + typed task); the home-screen widget refreshes in the background; and a couple of crash-safety tidies. Earlier (v0.4.27) — Polish round (continuing the sweep): the home-screen widget now follows dark mode; long task names truncate cleanly instead of clipping mid-word; the New-task date picker opens on the date you'd picked (and Cancel keeps your previous choice); Insights numbers round correctly; Settings export only says 'Exported' when it really did, and the 'Signed in' row no longer looks tappable; the promote 'by-time' picker matches dark mode; removing a shared-list member reconciles if it fails; Collections shows an empty-state and even card heights; background sync only runs with a connection; and the 'set a reminder permission' prompt no longer interrupts a brand-new user before onboarding. Earlier (v0.4.26) — Big reliability pass from a full bug sweep. Dark mode: the sign-in screen is readable again (was white-on-white). Data safety: scheduling a repeating task for today now actually shows it; edits to your own lists, captures during focus, and offline edits no longer get lost (sign-out now syncs first; deleting an unsynced item no longer brings it back). Focus: switching tasks mid-session saves the first session's time; the treatment chips read correctly in light mode. Calendar: all-day Google events no longer pile up as slivers at midnight; tapping a Google event doesn't open 'new task'; a task scheduled on another day stays scheduled; Disconnect asks first. Sign-in: empty email/password is caught before sending; 'Forgot password' now lets you set a new one. Plus: fixed reminders for back-to-back calendar events, settings errors now show red (not green), duplicate area/tag names are blocked, and the feedback box scrolls so Send is always reachable. Earlier (v0.4.25) — the feedback bubble (bottom-right on the main tabs) — tap it any time to send us a bug, idea, or note straight from the app. Pick a tag (Bug / Idea / Praise / Other), type, Send. It quietly attaches your app version, screen, and device so we can act on it fast. Please use it freely during the beta — it's the quickest way to reach us. Earlier (v0.4.24) — Calendar Week view now has ‹ / › arrows to move between weeks (+ a 'Today' jump), like Day view. You can now actually SCHEDULE a task from its detail screen: tap 'Schedule' (or the schedule row) to pick a real date AND time — it was previously estimate-only / auto-slot. And if your phone has notifications turned off for Unstuck, Today now shows a tappable 'Notifications are off' banner that jumps straight to the system toggle (reminders were firing but silently never reaching the phone). Earlier (v0.4.23) — Big stability + polish pass: ~40 bugs fixed from a full sweep of the app. Calendar: overlapping events sit side-by-side instead of hiding behind each other, tap-to-create no longer misfires near the time gutter, and the grid rolls over at midnight. Today: switching to Backlog or an empty area filter now shows a clear note instead of a blank screen; the live-session card shows the correct running total for a resumed task; the date rolls over at midnight without reopening. Sign-in: 'create account', 'magic link' and 'forgot password' now confirm with a 'check your email' message (they used to look like nothing happened), and signing up with an email that already exists tells you so instead of a fake success. Notifications: every item in the bell opens to the right place (shared-list and recap taps were dead), and app updates no longer fire a bogus 'your task is starting' or drop pending reminders. Collections: leaving a shared list now actually completes; a 'by' time set in the past rolls to tomorrow (so it isn't instantly overdue); a rename arriving from another member no longer wipes what you're typing; tapping a revealed item's row dismisses its actions instead of opening the editor. Settings: Backup does a real export now (the old toggles did nothing); duplicate-named areas are prevented; area/tag colours no longer collide after deletes. Plus quieter fixes across focus, insights and the account menu on notched phones. Earlier (v0.4.22): Reopening the app now lands you on Today (not a stale screen). Reminders fix: the app now asks for the 'Alarms & reminders' permission — without it Android silently delayed/dropped your task reminders (so promoted-task reminders never fired). Grant it when prompted, and a reminder fires before a scheduled task's time ('Remind me before tasks' in Settings sets the lead — default 10 min; set it to 5 for a 5-min heads-up). Earlier (v0.4.21): move-to-task fixes: a 'keep everyone in the loop' task now shows on your Calendar at its 'by' time, and you can no longer accidentally promote the same item twice into a duplicate task. Earlier (v0.4.20): archive old/finished lists (archive icon in a list's header; an 'Archived' filter on the overview to view + restore them). Removed the menu (hamburger) from Calendar and Collections. Shared-list notifications (shared / finished / late) now come through loud as a proper heads-up instead of silently. Earlier: hold anywhere on a collection item (not just the text) to reveal its actions — more reliable, with a little buzz when it catches. Everything from v0.4.18: long-press an item to reveal its actions (pin — now a proper pushpin icon, remove, and the new Move to task). Move to task turns an item into a real task; the item stays, struck-through and tagged 'Promoted'. On a SHARED list you can 'keep everyone in the loop' with a 'by' time: the task goes to your list, everyone sees '<you>'s on it · by 6:00', and when you check it off they all see 'done by <you> ✓' and get a heads-up. (If it's not started by 5 min past the time, the others get nudged — pending one server setup.) Earlier: tidier collection header with delete as a small icon next to Share (the big 'Delete collection' button is gone). The title also stays pinned at the top while you scroll its items. Plus everything from v0.4.15: shared collections now actually reach the person you share with (fixed a database-permission bug that silently blocked it), plus you get notified — an in-app card and a push — when someone shares a list with you (honors your notifications-off setting). Tapping the share notification opens Collections. Also fixed: a single overdue task no longer makes Today show an empty 'no tasks' screen (and hide the Backlog toggle). Earlier in v0.4.x — Shared collections! Open any collection → tap the Share icon (now next to the title) → invite a partner by email (even if they don't have Unstuck yet — they get the list the moment they sign up). You both add, check and edit items with live sync. Share as 'Can edit' or 'Can view' (read-only). Edits are conflict-free, so two people adding at once both land. Shared lists show a SHARED tag; non-owners get a Leave button. Fix: on detail/overlay screens the hidden header icons (inbox/bell/profile) no longer catch stray taps; the Today header now lines up with the other tabs. Also: the app now records where it's used (platform + rough city, from sign-in)."
}

dependencies {
    implementation(project(":core"))
    implementation(project(":design"))
    implementation(project(":data"))
    implementation(project(":sync"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp) // realtime voice WebSocket (Qwen-Omni via proxy)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.browser)
    implementation(libs.datastore.preferences)

    // surfaces
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.work.runtime.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // supabase (for handleDeeplinks in MainActivity)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)

    // Unit tests. Robolectric + an in-memory Room DB let the AppViewModel
    // orchestration tests run against a real LocalStore + WriteThrough on the
    // JVM (mirrors how :data / :sync set theirs up). No network / Supabase.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.runtime)
    testImplementation(libs.room.ktx)
}
