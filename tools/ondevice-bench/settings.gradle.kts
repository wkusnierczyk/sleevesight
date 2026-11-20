// Spike #272, part 2: a stand-alone measurement app for an on-device model (LiteRT-LM, Gemma 4).
// Its own build, not included in the repository's: the app's dependency allowlist, verification
// metadata and checks do not apply to it, and it never ships.
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

rootProject.name = "ondevice-bench"
include(":app")
