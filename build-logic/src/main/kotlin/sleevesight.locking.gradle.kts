// Dependency locking for every configuration (#1034): the classpaths that ship and the tools' (Android
// lint, ktlint, Detekt, the Kotlin compiler, the tests) alike, each in the module's `gradle.lockfile`.
// `./gradlew resolveAndLockAll --write-locks` rewrites the lockfiles; `checkDependencies` then reads
// the same resolution. Lockfiles pin versions; `gradle/verification-metadata.xml` pins what each
// version's files hold (`design/development.md`, *Dependencies*).
import org.gradle.api.artifacts.VersionCatalogsExtension
import sleevesight.BuildFloors
import sleevesight.Locking

dependencyLocking {
    lockAllConfigurations()
}

// The libraries the build's tools bring in, at no lower than the catalog's `build-floor-*` versions,
// the lowest with no known vulnerability (#1087), and locked as raised. Not on the classpaths that
// ship: what goes into the APK and the CLI stays as the lockfiles say, and OSV-Scanner checks it
// there; nor on the module's own code beside `main` (`Locking.isFloored`). A build with no such
// catalog, such as the build logic's functional tests, has no floors.
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

// Some plugins give a configuration its dependencies only when they configure the task that reads it
// (Android lint's `*ForLint` classpaths), so a build that writes the locks configures every task
// first: otherwise such a configuration would be locked empty, and fail the next build that uses it.
if (gradle.startParameter.isWriteDependencyLocks) {
    gradle.projectsEvaluated { tasks.forEach { } }
}

// Every configuration that can be resolved, so each gets its lock state, and its files, so that
// `--write-verification-metadata` records them (`design/development.md`, *Dependencies*). A
// dependency or a file that does not resolve fails the task (`Locking.resolveAll`).
tasks.register("resolveAndLockAll") {
    notCompatibleWithConfigurationCache("Resolves configurations at execution time to write lockfiles")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) { "Run with --write-locks" }
    }
    doLast {
        val failures = Locking.resolveAll(configurations)
        check(failures.isEmpty()) { "Did not resolve:\n" + failures.joinToString("\n") { "  $it" } }
    }
}
