plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.syncler.core.pluginaidl"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        // AIDL stubs are generated for both host and sandbox to depend on.
        aidl = true
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
    testImplementation(libs.junit)
}
