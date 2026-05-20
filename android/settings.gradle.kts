pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
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
