plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.syncler.feature.pluginnativesandbox"
    compileSdk = 35

    defaultConfig {
        // Phase 11: bindIsolatedService(instanceName=...) is API 29+.
        // The whole native plugin pipeline is gated on it, so this
        // module's minSdk floors at 29 even though the rest of the
        // app supports 26+. SandboxRouter rejects native_kotlin
        // publishes at runtime on API 26-28 with `native_only_api_29`.
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        aidl = false
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
    implementation(project(":core:plugin-aidl"))
    implementation(project(":plugin-sdk-runtime"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
