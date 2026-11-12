pluginManagement {
    includeBuild("build-logic")
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

rootProject.name = "hatch"

include(
    ":app",
    ":benchmark",
    ":core",
    ":protocol",
    ":ghostty",
    ":engine",
    ":renderer",
    ":design",
    ":feature:sessions",
    ":feature:terminal",
    ":feature:pairing",
    ":feature:settings",
)
