pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // WayMap (com.github.ShiKi5473.WayMap:*) is served by JitPack from its git tags.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.ShiKi5473.*") }
        }
    }
}

// Develop against a local WayMap checkout (../WayMap) instead of the JitPack release:
//   ./gradlew -PuseLocalWayMap=true <task>
// (or put useLocalWayMap=true in ~/.gradle/gradle.properties).
val localWayMap = file("../WayMap")
if (providers.gradleProperty("useLocalWayMap").orNull == "true" && localWayMap.isDirectory) {
    includeBuild(localWayMap) {
        dependencySubstitution {
            substitute(module("com.github.ShiKi5473.WayMap:geo")).using(project(":geo"))
            substitute(module("com.github.ShiKi5473.WayMap:waymap")).using(project(":waymap"))
        }
    }
}

rootProject.name = "WayGuard"
// :guard-core — pure Kotlin/JVM: data model, NPA CSV parsing/validation, alert engine.
// :camera-sync — JVM CLI run by GitHub Actions to publish data/published/cameras.json.
// :app — the Android speed-camera guard.
include(":guard-core", ":camera-sync", ":app")
