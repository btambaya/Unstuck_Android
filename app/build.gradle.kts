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
        applicationId = "tech.csalliance.unstuck"
        minSdk = 26
        targetSdk = 35
        versionCode = 42
        versionName = "0.4.29"
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        // In-app feedback bubble — on for beta; flip to false (or repurpose the copy)
        // for a public release.
        buildConfigField("Boolean", "FEEDBACK_ENABLED", "true")
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
            isMinifyEnabled = false
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
}

// Firebase App Distribution — `./gradlew :app:appDistributionUploadRelease`
// uploads the signed release to testers. Auth uses the gitignored
// firebase-service-account.json.
// DEFAULT (for now): distribute ONLY to the two test accounts below — NOT the
// full `beta` group — so we don't spam every tester with each iteration.
// To push to the whole beta group (when ready): pass -PappDistGroups=beta.
// Override testers with -PappDistTesters="a@x.com,b@y.com".
firebaseAppDistribution {
    appId = "1:806563895083:android:1673707a78b9d39039976e"
    artifactType = "APK"
    val sa = rootProject.file("firebase-service-account.json")
    if (sa.exists()) serviceCredentialsFile = sa.path
    testers = (findProperty("appDistTesters") as String?) ?: "justtesting6363@gmail.com,zyzkazaure@gmail.com"
    // No group by default → only the two testers above are notified. Pass
    // -PappDistGroups=beta to release to the full beta group when told to.
    groups = (findProperty("appDistGroups") as String?) ?: ""
    releaseNotes = "v0.4.29 — Web-parity round: Focus now has an overrun check-in when you pass your estimate (Add 10 min / I'm in the zone / Stop here) instead of just turning the timer red; your task's first physical action shows during focus and as the Start-Next headline (with a 'Pick another' button); recurring tasks can now have an end date (Repeat → Ends); the teal area colour finally shows (Health area was gray) and you can pick teal + red; the task detail shows 'In progress' once you've focused on it; dragging a calendar block to a new time now counts as a reschedule; and leaving a running focus session asks first (if Soft exit is on). Earlier (v0.4.28) — Final polish round: ambient focus audio now actually plays when the Ambient setting is on; the 'just now' recap card on Today clears itself after a while; the focus ring timer stays tidy past an hour; you can reschedule early-morning / late-evening calendar blocks to any time; account deletion works even without an email (type DELETE); inbox timestamps stay current; onboarding survives a rotation (keeps your step + typed task); the home-screen widget refreshes in the background; and a couple of crash-safety tidies. Earlier (v0.4.27) — Polish round (continuing the sweep): the home-screen widget now follows dark mode; long task names truncate cleanly instead of clipping mid-word; the New-task date picker opens on the date you'd picked (and Cancel keeps your previous choice); Insights numbers round correctly; Settings export only says 'Exported' when it really did, and the 'Signed in' row no longer looks tappable; the promote 'by-time' picker matches dark mode; removing a shared-list member reconciles if it fails; Collections shows an empty-state and even card heights; background sync only runs with a connection; and the 'set a reminder permission' prompt no longer interrupts a brand-new user before onboarding. Earlier (v0.4.26) — Big reliability pass from a full bug sweep. Dark mode: the sign-in screen is readable again (was white-on-white). Data safety: scheduling a repeating task for today now actually shows it; edits to your own lists, captures during focus, and offline edits no longer get lost (sign-out now syncs first; deleting an unsynced item no longer brings it back). Focus: switching tasks mid-session saves the first session's time; the treatment chips read correctly in light mode. Calendar: all-day Google events no longer pile up as slivers at midnight; tapping a Google event doesn't open 'new task'; a task scheduled on another day stays scheduled; Disconnect asks first. Sign-in: empty email/password is caught before sending; 'Forgot password' now lets you set a new one. Plus: fixed reminders for back-to-back calendar events, settings errors now show red (not green), duplicate area/tag names are blocked, and the feedback box scrolls so Send is always reachable. Earlier (v0.4.25) — the feedback bubble (bottom-right on the main tabs) — tap it any time to send us a bug, idea, or note straight from the app. Pick a tag (Bug / Idea / Praise / Other), type, Send. It quietly attaches your app version, screen, and device so we can act on it fast. Please use it freely during the beta — it's the quickest way to reach us. Earlier (v0.4.24) — Calendar Week view now has ‹ / › arrows to move between weeks (+ a 'Today' jump), like Day view. You can now actually SCHEDULE a task from its detail screen: tap 'Schedule' (or the schedule row) to pick a real date AND time — it was previously estimate-only / auto-slot. And if your phone has notifications turned off for Unstuck, Today now shows a tappable 'Notifications are off' banner that jumps straight to the system toggle (reminders were firing but silently never reaching the phone). Earlier (v0.4.23) — Big stability + polish pass: ~40 bugs fixed from a full sweep of the app. Calendar: overlapping events sit side-by-side instead of hiding behind each other, tap-to-create no longer misfires near the time gutter, and the grid rolls over at midnight. Today: switching to Backlog or an empty area filter now shows a clear note instead of a blank screen; the live-session card shows the correct running total for a resumed task; the date rolls over at midnight without reopening. Sign-in: 'create account', 'magic link' and 'forgot password' now confirm with a 'check your email' message (they used to look like nothing happened), and signing up with an email that already exists tells you so instead of a fake success. Notifications: every item in the bell opens to the right place (shared-list and recap taps were dead), and app updates no longer fire a bogus 'your task is starting' or drop pending reminders. Collections: leaving a shared list now actually completes; a 'by' time set in the past rolls to tomorrow (so it isn't instantly overdue); a rename arriving from another member no longer wipes what you're typing; tapping a revealed item's row dismisses its actions instead of opening the editor. Settings: Backup does a real export now (the old toggles did nothing); duplicate-named areas are prevented; area/tag colours no longer collide after deletes. Plus quieter fixes across focus, insights and the account menu on notched phones. Earlier (v0.4.22): Reopening the app now lands you on Today (not a stale screen). Reminders fix: the app now asks for the 'Alarms & reminders' permission — without it Android silently delayed/dropped your task reminders (so promoted-task reminders never fired). Grant it when prompted, and a reminder fires before a scheduled task's time ('Remind me before tasks' in Settings sets the lead — default 10 min; set it to 5 for a 5-min heads-up). Earlier (v0.4.21): move-to-task fixes: a 'keep everyone in the loop' task now shows on your Calendar at its 'by' time, and you can no longer accidentally promote the same item twice into a duplicate task. Earlier (v0.4.20): archive old/finished lists (archive icon in a list's header; an 'Archived' filter on the overview to view + restore them). Removed the menu (hamburger) from Calendar and Collections. Shared-list notifications (shared / finished / late) now come through loud as a proper heads-up instead of silently. Earlier: hold anywhere on a collection item (not just the text) to reveal its actions — more reliable, with a little buzz when it catches. Everything from v0.4.18: long-press an item to reveal its actions (pin — now a proper pushpin icon, remove, and the new Move to task). Move to task turns an item into a real task; the item stays, struck-through and tagged 'Promoted'. On a SHARED list you can 'keep everyone in the loop' with a 'by' time: the task goes to your list, everyone sees '<you>'s on it · by 6:00', and when you check it off they all see 'done by <you> ✓' and get a heads-up. (If it's not started by 5 min past the time, the others get nudged — pending one server setup.) Earlier: tidier collection header with delete as a small icon next to Share (the big 'Delete collection' button is gone). The title also stays pinned at the top while you scroll its items. Plus everything from v0.4.15: shared collections now actually reach the person you share with (fixed a database-permission bug that silently blocked it), plus you get notified — an in-app card and a push — when someone shares a list with you (honors your notifications-off setting). Tapping the share notification opens Collections. Also fixed: a single overdue task no longer makes Today show an empty 'no tasks' screen (and hide the Backlog toggle). Earlier in v0.4.x — Shared collections! Open any collection → tap the Share icon (now next to the title) → invite a partner by email (even if they don't have Unstuck yet — they get the list the moment they sign up). You both add, check and edit items with live sync. Share as 'Can edit' or 'Can view' (read-only). Edits are conflict-free, so two people adding at once both land. Shared lists show a SHARED tag; non-owners get a Leave button. Fix: on detail/overlay screens the hidden header icons (inbox/bell/profile) no longer catch stray taps; the Today header now lines up with the other tabs. Also: the app now records where it's used (platform + rough city, from sign-in)."
}

dependencies {
    implementation(project(":core"))
    implementation(project(":design"))
    implementation(project(":data"))
    implementation(project(":sync"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
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

    testImplementation(libs.junit)
}
