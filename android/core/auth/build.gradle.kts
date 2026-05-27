plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.syncler.core.auth"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation(project(":core:crypto"))
    implementation(project(":core:network"))
    implementation(project(":core:storage"))
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.timber)
    // V4 #20 — BiometricPrompt host for SensitiveActionGate.
    // androidx.biometric transitively pulls androidx.fragment so the
    // prompt can be shown over a FragmentActivity-based host.
    implementation(libs.androidx.biometric)

    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp)
    testImplementation(libs.turbine)
}
