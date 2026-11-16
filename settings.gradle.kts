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

rootProject.name = "Anvex"
include(":app")
include(":core:model")
include(":core:engine")
include(":core:ai")
include(":core:android")
// Agent Teams module (feature/): see docs/agent-teams/STATUS.md
include(":feature:agents-core")
include(":feature:agents-fs")
include(":feature:agents-runtime")
include(":feature:agents-ui")
