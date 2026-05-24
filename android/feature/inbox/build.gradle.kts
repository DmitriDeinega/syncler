plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.syncler.feature.inbox"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation(project(":core:auth"))
    implementation(project(":core:crypto"))
    implementation(project(":core:network"))
    implementation(project(":core:storage"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.moshi.kotlin)
    implementation(libs.okhttp)
    implementation(libs.timber)

    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // TemplateActionRunner test: verify it does NOT send Authorization +
    // confirm release builds refuse cleartext endpoints. Mirrors how
    // :feature:plugin-host tests NetworkBridge.
    testImplementation(libs.okhttp.mockwebserver)
    // Android stubs org.json with NotMocked, breaking host-side parser tests.
    // Pull the real implementation onto the test classpath so the unit tests
    // exercise the same JSON behavior the production runtime sees.
    testImplementation("org.json:json:20240303")
}
