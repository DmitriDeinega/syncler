plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.syncler.feature.pluginsandbox"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        // The sandbox uses the AIDL stubs from :core:plugin-aidl via its
        // generated Java sources; we don't define any new AIDL here.
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

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
