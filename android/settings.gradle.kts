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
    ":core:storage",
    ":feature:inbox",
    ":feature:settings",
)
