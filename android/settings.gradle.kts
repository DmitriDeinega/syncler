pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Auto-download a matching JDK when gradle's toolchain spec is not
    // satisfied by anything installed locally. AGP needs JDK 17; this
    // resolver fetches one transparently if the user only has 21 etc.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SynclerAndroid"

include(
    ":app",
    ":core:auth",
    ":core:crypto",
    ":core:network",
    ":core:push",
    ":core:storage",
    ":feature:inbox",
    ":feature:pairing",
    ":feature:plugin-host",
    ":feature:settings",
)
