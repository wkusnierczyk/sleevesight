// Plugins are applied per module through the convention plugins in build-logic/.
plugins {
    base
}

// `./gradlew build` also tests the build logic (the structural checks), which is an included build.
tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}
