// The platform side of a Multiplatform-ready module (architecture, Multiplatform readiness, D2):
// `src/jvmMain/kotlin` is a source set of its own, compiled after `main` against `main`'s classes
// (and its `internal` declarations), while `main` cannot see it. So portable code that refers to
// a JVM implementation does not compile, however it is written.
//
// - The module's jar carries both, and consumers and the test fixtures get `jvmMain` through it.
//   The tests are associated with `main` and `jvmMain`, which gives them the internals of both;
//   the test fixtures see the module through its jar, so they take the jar as a friend path.
// - `jvmMain` shares `main`'s dependencies: `checkJvmMainDependencies` (in `check`) fails when
//   its compile or runtime classpath resolves an external module or version that `main`'s does
//   not, by any route. Such a dependency would reach no consumer and escape the dependency
//   allowlist, the module graph check and locking.
// - The root `gradle.properties` sets `kotlin.build.archivesTaskOutputAsFriendModule=false`:
//   `jvmMain` reads `main`'s internals from its classes, since the jar, which carries `jvmMain`,
//   would be a cycle.
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import sleevesight.checks.CheckDependencies
import sleevesight.checks.CheckJvmMainDependencies
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
pluginManager.withPlugin("java-test-fixtures") {
    tasks.named<KotlinCompile>("compileTestFixturesKotlin") {
        friendPaths.from(jarFile)
    }
}

fun modulesOf(configuration: String) =
    CheckDependencies.modulesOf(configurations.named(configuration).flatMap { it.incoming.resolutionResult.rootComponent })

val checkJvmMainDependencies =
    tasks.register<CheckJvmMainDependencies>("checkJvmMainDependencies") {
        group = "verification"
        description = "Fails when jvmMain resolves external modules or versions that main does not."
        mainCompile = modulesOf("compileClasspath")
        jvmMainCompile = modulesOf("jvmMainCompileClasspath")
        mainRuntime = modulesOf("runtimeClasspath")
        jvmMainRuntime = modulesOf("jvmMainRuntimeClasspath")
    }

// Kotlin compiles `.kt` files under `src/<set>/java` too; nothing compiles from there.
val checkSourceRoots =
    tasks.register<CheckSourceRoots>("checkSourceRoots") {
        group = "verification"
        description = "Fails when src/main/java or src/jvmMain/java holds a file."
        unexpectedRoots.from(layout.projectDirectory.dir("src/main/java"), layout.projectDirectory.dir("src/jvmMain/java"))
    }

tasks.named("check") {
    dependsOn(checkSourceRoots, checkJvmMainDependencies)
}
