plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.syncler.plugin.runtime"
    compileSdk = 35

    defaultConfig {
        // Phase 11: native Kotlin plugins are API 29+ (bindIsolatedService).
        // The SDK runtime can compile against 26 — only the host's binding
        // path is gated on 29 — so plugins targeting JS still build cleanly
        // against this module if they ever depend on it transitively.
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
