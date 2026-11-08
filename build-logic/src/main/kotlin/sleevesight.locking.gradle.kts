// Dependency locking for the classpaths that ship. `./gradlew resolveAndLockAll --write-locks`
// rewrites the lockfiles; `checkDependencies` then reads the same resolution. Every other classpath
// (the tools': Android lint, ktlint, Detekt, the tests) gets the catalog's floors instead.
import org.gradle.api.artifacts.VersionCatalogsExtension
import sleevesight.BuildFloors
import sleevesight.Locking

val lockedConfigurations = Locking.configurations

configurations.matching { it.name in lockedConfigurations }.configureEach {
    resolutionStrategy.activateDependencyLocking()
}

// The libraries the build's tools bring in, at no lower than the catalog's `build-floor-*` versions,
// the lowest with no known vulnerability (#1087). Not on the classpaths that ship: what goes into
// the APK and the CLI stays as the lockfiles say, and OSV-Scanner checks it there; nor on the
// module's own code beside `main` (`Locking.isFloored`). A build with no such catalog, such as the
// build logic's functional tests, has no floors.
val buildFloors =
    extensions
        .getByType<VersionCatalogsExtension>()
        .find("libs")
        .map { BuildFloors.from(it) }
        .orElse(emptyMap())
if (buildFloors.isNotEmpty()) {
    configurations.matching { Locking.isFloored(it.name) }.configureEach {
        resolutionStrategy.eachDependency { BuildFloors.raise(this, buildFloors) }
    }
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
