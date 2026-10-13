// The platform side of a Multiplatform-ready module (architecture, Multiplatform readiness, D2):
// `src/jvmMain/kotlin` is a source set of its own, compiled after `main` against `main`'s classes
// (and its `internal` declarations), while `main` cannot see it. So portable code that refers to
// a JVM implementation does not compile, however it is written.
//
// - The module's jar carries both, and consumers and the test fixtures get `jvmMain` through it.
//   The tests are associated with `jvmMain` as well as `main`, and both tests and test fixtures
//   take the jar as a friend module, so they see the internals of both.
// - `jvmMain` shares `main`'s dependencies and may declare none of its own: a dependency on a
//   `jvmMain*` configuration would reach no consumer and escape the dependency allowlist, the
//   module graph check and locking, so declaring one fails the build.
// - The root `gradle.properties` sets `kotlin.build.archivesTaskOutputAsFriendModule=false`:
//   `jvmMain` reads `main`'s internals from its classes, since the jar, which carries `jvmMain`,
//   would be a cycle.
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import sleevesight.checks.CheckSourceRoots

plugins {
    kotlin("jvm")
}

val sourceSets = extensions.getByType<SourceSetContainer>()
val jvmMain = sourceSets.create("jvmMain")
val compilations = kotlin.target.compilations

compilations.named("jvmMain") {
    associateWith(compilations.getByName("main"))
}
compilations.named("test") {
    associateWith(compilations.getByName("jvmMain"))
}

val jar = tasks.named<Jar>("jar")
jar {
    from(jvmMain.output)
}

val jarFile = jar.flatMap { it.archiveFile }
tasks.named<KotlinCompile>("compileTestKotlin") {
    friendPaths.from(jarFile)
}
pluginManager.withPlugin("java-test-fixtures") {
    tasks.named<KotlinCompile>("compileTestFixturesKotlin") {
        friendPaths.from(jarFile)
    }
}

val ownDependencies = setOf("jvmMainApi", "jvmMainImplementation", "jvmMainCompileOnly", "jvmMainRuntimeOnly")
configurations.matching { it.name in ownDependencies }.configureEach {
    val configuration = name
    dependencies.whenObjectAdded {
        throw GradleException(
            "$configuration: jvmMain shares main's dependencies; declare $group:$name on main's configurations.",
        )
    }
}

// Kotlin compiles `.kt` files under `src/<set>/java` too; nothing compiles from there.
val checkSourceRoots =
    tasks.register<CheckSourceRoots>("checkSourceRoots") {
        group = "verification"
        description = "Fails when src/main/java or src/jvmMain/java holds a file."
        unexpectedRoots.from(layout.projectDirectory.dir("src/main/java"), layout.projectDirectory.dir("src/jvmMain/java"))
    }

tasks.named("check") {
    dependsOn(checkSourceRoots)
}
