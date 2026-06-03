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
        versionCode = 35
        versionName = "0.4.22"
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
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
// firebase-service-account.json. Override the invite list with
// -PappDistTesters="a@x.com,b@y.com" — DEFAULT is the full tester list so a
// plain upload reaches everyone (not just the owner).
firebaseAppDistribution {
    appId = "1:806563895083:android:1673707a78b9d39039976e"
    artifactType = "APK"
    val sa = rootProject.file("firebase-service-account.json")
    if (sa.exists()) serviceCredentialsFile = sa.path
    testers = (findProperty("appDistTesters") as String?) ?: "ahmad@csalliance.tech,justtesting6363@gmail.com,zyzkazaure@gmail.com"
    // Also distribute to the "beta" tester GROUP (alias `beta`) so anyone who
    // self-enrolls via its invite link gets every build — no manual email entry.
    // ⚠ The group must already exist: Firebase Console → App Distribution →
    // Testers & Groups → create a group with alias `beta` + enable its invite link.
    // Override with -PappDistGroups="alias1,alias2".
    groups = (findProperty("appDistGroups") as String?) ?: "beta"
    releaseNotes = "v0.4.22 — Reopening the app now lands you on Today (not a stale screen). Reminders fix: the app now asks for the 'Alarms & reminders' permission — without it Android silently delayed/dropped your task reminders (so promoted-task reminders never fired). Grant it when prompted, and a reminder fires before a scheduled task's time ('Remind me before tasks' in Settings sets the lead — default 10 min; set it to 5 for a 5-min heads-up). Earlier (v0.4.21): move-to-task fixes: a 'keep everyone in the loop' task now shows on your Calendar at its 'by' time, and you can no longer accidentally promote the same item twice into a duplicate task. Earlier (v0.4.20): archive old/finished lists (archive icon in a list's header; an 'Archived' filter on the overview to view + restore them). Removed the menu (hamburger) from Calendar and Collections. Shared-list notifications (shared / finished / late) now come through loud as a proper heads-up instead of silently. Earlier: hold anywhere on a collection item (not just the text) to reveal its actions — more reliable, with a little buzz when it catches. Everything from v0.4.18: long-press an item to reveal its actions (pin — now a proper pushpin icon, remove, and the new Move to task). Move to task turns an item into a real task; the item stays, struck-through and tagged 'Promoted'. On a SHARED list you can 'keep everyone in the loop' with a 'by' time: the task goes to your list, everyone sees '<you>'s on it · by 6:00', and when you check it off they all see 'done by <you> ✓' and get a heads-up. (If it's not started by 5 min past the time, the others get nudged — pending one server setup.) Earlier: tidier collection header with delete as a small icon next to Share (the big 'Delete collection' button is gone). The title also stays pinned at the top while you scroll its items. Plus everything from v0.4.15: shared collections now actually reach the person you share with (fixed a database-permission bug that silently blocked it), plus you get notified — an in-app card and a push — when someone shares a list with you (honors your notifications-off setting). Tapping the share notification opens Collections. Also fixed: a single overdue task no longer makes Today show an empty 'no tasks' screen (and hide the Backlog toggle). Earlier in v0.4.x — Shared collections! Open any collection → tap the Share icon (now next to the title) → invite a partner by email (even if they don't have Unstuck yet — they get the list the moment they sign up). You both add, check and edit items with live sync. Share as 'Can edit' or 'Can view' (read-only). Edits are conflict-free, so two people adding at once both land. Shared lists show a SHARED tag; non-owners get a Leave button. Fix: on detail/overlay screens the hidden header icons (inbox/bell/profile) no longer catch stray taps; the Today header now lines up with the other tabs. Also: the app now records where it's used (platform + rough city, from sign-in)."
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
