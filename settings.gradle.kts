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
    }
}

rootProject.name = "lugu"

include(":app")
// An application of its own, with an application id of its own, whose instrumented tests
// kill lugu and watch what happens. It is not part of what ships and nothing depends on it.
include(":harness")
include(":core:model")
// Compose parts shared by more than one feature. The feature modules are siblings and
// cannot see each other, so anything two of them draw the same way lives here.
include(":core:ui")
include(":core:api")
include(":core:db")
include(":core:sync")
include(":core:download")
include(":playback")
include(":feature:library")
include(":feature:player")
include(":feature:settings")
