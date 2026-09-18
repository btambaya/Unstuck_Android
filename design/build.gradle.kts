// :design — Compose Material3 theme (oklch brand tokens) + shared components.
// Analog of the iOS UnstuckDesign package.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "tech.csalliance.unstuck.design"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    // Robolectric needs Android resources (the bundled brand fonts live in
    // design/src/main/res/font) on the unit-test classpath so the Compose
    // component tests below can actually lay a screen out on the JVM.
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    // Compose component tests on the JVM (Robolectric): the chrome's behaviour —
    // e.g. what the bottom bar's + announces and who it calls — is only
    // observable by composing it, so the token/maths tests alone left it bare.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // ui-test-manifest declares the host ComponentActivity the compose rule
    // launches. `testImplementation`, NOT `debugImplementation`: these tests are
    // compiled into BOTH unit-test variants, and as a debug-only dependency the
    // activity was missing from the release unit-test manifest —
    // `:design:testReleaseUnitTest` failed all 5 BottomNavBarTest cases with
    // "Unable to resolve activity" (RoboMonitoringInstrumentation:102). A library
    // module merges its unit-test variant's own manifest, so this covers both.
    // (:app can't use the AAR at all — see the note there.)
    testImplementation(libs.compose.ui.test.manifest)
}
