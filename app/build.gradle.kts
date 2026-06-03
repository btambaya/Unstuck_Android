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
        versionCode = 25
        versionName = "0.4.12"
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
    releaseNotes = "v0.4.11 — New: a capture Inbox (tray icon in the Today header, next to the bell) to triage your captured thoughts — promote to a task, open, archive, or discard. Plus code-review fixes: signing out now unregisters this device so the next person who signs in never gets your notifications; shade actions (Resume/End/Reschedule) are reliable on a cold process; the calendar NOW line advances live; the live-focus notification is restored after the app is killed mid-session; the home-screen 'Start Next' widget now actually updates; drag-to-schedule lines up on all screen densities; and command-palette notes open their task. Removed the redundant 'You noted…' nudge (the Inbox covers it)."
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
