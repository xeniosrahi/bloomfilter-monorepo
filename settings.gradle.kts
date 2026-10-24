pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}

rootProject.name = "bloomfilter-monorepo"
include(
    "bloomfilter-core",
    "bloomfilter-redis-sdk",
    "bloomfilter-sync-service",
    "bloomfilter-benchmarks"
)
