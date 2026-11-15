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

// Each project's build classpath (the convention plugins and the Gradle plugins they bring) locked as
// its configurations are (#1034), in `buildscript-gradle.lockfile` beside its build file.
gradle.beforeProject {
    buildscript.dependencyLocking.lockAllConfigurations()
}

include(":core")
include(":net")
include(":sources")
include(":ai")
include(":wiring")
include(":store")
include(":cli")
include(":app")
