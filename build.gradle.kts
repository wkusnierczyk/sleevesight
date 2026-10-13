// Plugins are applied per module through the convention plugins in build-logic/.

// `./gradlew build` also builds and tests build-logic, the structural checks among it.
tasks.register("build") {
    dependsOn(gradle.includedBuild("build-logic").task(":build"))
}
