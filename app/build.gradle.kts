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
        versionCode = 75
        versionName = "0.4.61"
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
    releaseNotes = "v0.4.61 — Tour: the highlighted Capture pill in the focus demo is now tappable and opens a demo capture sheet (type freely — nothing is saved), matching what the narration invites. Earlier — v0.4.60: live captions, spotlight-only lockdown, pause confirm + resume chip, demo focus screen."
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
