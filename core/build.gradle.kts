// :core — pure Kotlin/JVM domain + logic ports of the web lib/* (no
// Android, no Supabase). Runs headless: `./gradlew :core:test`.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnit()
    // Deterministic date/bucket logic (matches the web CI + iOS TZ=UTC).
    systemProperty("user.timezone", "UTC")
}
