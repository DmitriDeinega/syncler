plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.syncler.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val baseUrl = (project.findProperty("syncler.server.base.url") as? String)
            ?: "http://10.0.2.2:8000/"
        buildConfigField("String", "SERVER_BASE_URL", "\"$baseUrl\"")

        // Environment label + applicationId. Override at build time:
        //   ./gradlew :app:assembleDebug -Psyncler.environment=PROD
        //   ./gradlew :app:assembleDebug -Psyncler.environment=TEST
        // PROD installs as `app.syncler.android` ("Syncler"). Any other
        // env appends a lowercase suffix so multiple variants can live
        // side-by-side on the same device (TEST → `app.syncler.android.test`
        // labelled "Syncler TEST", default DEV → `app.syncler.android.dev`
        // labelled "Syncler DEV").
        val env = (project.findProperty("syncler.environment") as? String)?.uppercase() ?: "DEV"
        applicationId = if (env == "PROD") "app.syncler.android"
                        else "app.syncler.android.${env.lowercase()}"
        val appName = if (env == "PROD") "Syncler" else "Syncler $env"
        resValue("string", "app_name", appName)
        buildConfigField("String", "BUILD_ENVIRONMENT", "\"$env\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // BouncyCastle ships a JPMS manifest that collides with JSpecify
            // and other transitive deps. These META-INF files are not
            // needed at runtime.
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:auth"))
    implementation(project(":core:crypto"))
    implementation(project(":core:network"))
    implementation(project(":core:push"))
    implementation(project(":core:storage"))
    implementation(project(":feature:inbox"))
    implementation(project(":feature:pairing"))
    implementation(project(":feature:plugin-host"))
    // Phase 10b (Codex 117 #1): the :app APK must package the
    // sandbox module so PluginSandboxService is reachable by name
    // when PluginSandboxConnection.bindService() fires.
    implementation(project(":feature:plugin-sandbox"))
    // Phase 11: same reason for the native (isolatedProcess) sandbox
    // — the APK must declare PluginNativeSandboxService for
    // bindIsolatedService to resolve.
    implementation(project(":feature:plugin-native-sandbox"))
    implementation(project(":feature:settings"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    ksp(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
