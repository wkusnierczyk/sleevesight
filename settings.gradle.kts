pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "sleevesight"

include(":core")
include(":net")
include(":sources")
include(":ai")
include(":wiring")
include(":store")
include(":cli")
include(":app")
