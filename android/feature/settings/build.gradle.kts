plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.syncler.feature.settings"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
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
    // Phase 8c — RotationRepository lives in core/auth.
    implementation(project(":core:auth"))
    // V4 #18 — SynclerApi.revokeDevice for the lost-device flow.
    implementation(project(":core:network"))
    // Triad 160 — PairedSenderStore for the sendersToReview list
    // populated on the Done state.
    implementation(project(":core:storage"))
    // V2 closeout triad 142 #1: PluginPermissionsCard surfaces
    // the CapabilityGrantStore.allGrants() + revoke flow.
    implementation(project(":feature:plugin-host"))
    // Retrofit's HttpException is part of the public surface of
    // RotationRepository's failure path (the rewrap call returns a
    // retrofit Response and unsuccessful codes are wrapped). The
    // SettingsViewModel inspects the HTTP status code directly to
    // map 401/409/426/429 onto user-facing copy.
    implementation(libs.retrofit)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
    // V4 #18 — SecurityPrefs uses EncryptedSharedPreferences
    // for the days-based banner marker.
    implementation(libs.androidx.security.crypto)

    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
