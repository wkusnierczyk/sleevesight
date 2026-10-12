// Dependency locking for the classpaths that ship. `./gradlew resolveAndLockAll --write-locks`
// rewrites the lockfiles; `checkDependencies` then reads the same resolution.
val lockedConfigurations =
    setOf(
        "runtimeClasspath",
        "compileClasspath",
        "releaseRuntimeClasspath",
        "releaseCompileClasspath",
        // The debug APK is the CI artifact installed on phones.
        "debugRuntimeClasspath",
        "debugCompileClasspath",
    )

configurations.matching { it.name in lockedConfigurations }.configureEach {
    resolutionStrategy.activateDependencyLocking()
}

tasks.register("resolveAndLockAll") {
    notCompatibleWithConfigurationCache("Resolves configurations at execution time to write lockfiles")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) { "Run with --write-locks" }
    }
    doLast {
        configurations.filter { it.name in lockedConfigurations && it.isCanBeResolved }.forEach { it.resolve() }
    }
}
