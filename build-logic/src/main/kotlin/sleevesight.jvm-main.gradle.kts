// The platform side of a Multiplatform-ready module (architecture, Multiplatform readiness, D2):
// `src/jvmMain/kotlin` is a source set of its own, compiled after `main` against `main`'s classes
// (and its `internal` declarations), while `main` cannot see it. So portable code that refers to
// a JVM implementation does not compile, however it is written.
//
// - Every outgoing variant of the module's code carries both. The jar does, which JVM consumers
//   and the test fixtures take; so do `runtimeElements`' secondary variants `classes` and
//   `resources`, which the Android Gradle plugin takes for a project dependency instead of the jar.
//   Without them a debug APK lacked every `jvmMain` class and the app crashed on start (#177);
//   `:app`'s `checkApkClasses` and the functional tests guard it. Gradle gives `apiElements` no
//   secondary variant; one it gains gets `jvmMain`'s classes as well. (`mainSourceElements`, the
//   source directories for coverage reports, lists `main`'s only; nothing here reads it.)
//   The tests are associated with `main` and `jvmMain`, which gives them the internals of both;
//   the test fixtures see the module through its jar, so they take the jar as a friend path.
// - `jvmMain` shares `main`'s dependencies: `checkJvmMainDependencies` (in `check`) fails when
//   its compile or runtime classpath resolves other artifacts than `main`'s (apart from `main`'s
//   own classes), whether through a declaration, an inherited configuration, a constraint or
//   platform, a project or file dependency, a classifier or a variant. Such a dependency would
//   reach no consumer and escape the dependency allowlist and the module graph check; with equal
//   artifacts, `jvmMain` also gets the versions `main` has locked.
// - The root `gradle.properties` sets `kotlin.build.archivesTaskOutputAsFriendModule=false`:
//   `jvmMain` reads `main`'s internals from its classes, since the jar, which carries `jvmMain`,
//   would be a cycle.
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
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

// `jvmMain`'s class directories, each with the task that writes it, as `main`'s are listed.
val jvmMainClassDirs =
    listOf(
        tasks.named<JavaCompile>(jvmMain.compileJavaTaskName).flatMap { it.destinationDirectory },
        tasks.named<KotlinCompile>("compileJvmMainKotlin").flatMap { it.destinationDirectory },
    )
val processJvmMainResources = tasks.named<ProcessResources>(jvmMain.processResourcesTaskName)

fun ConfigurationVariant.addJvmMainClasses() {
    for (dir in jvmMainClassDirs) {
        artifact(dir) { type = ArtifactTypeDefinition.JVM_CLASS_DIRECTORY }
    }
}

configurations.named("runtimeElements") {
    // Named, not matched: if Gradle renamed them, the build fails here rather than ship without `jvmMain`.
    outgoing.variants.named("classes") { addJvmMainClasses() }
    outgoing.variants.named("resources") {
        artifact(jvmMain.output.resourcesDir!!) {
            type = ArtifactTypeDefinition.JVM_RESOURCES_DIRECTORY
            builtBy(processJvmMainResources)
        }
    }
}
configurations.named("apiElements") {
    outgoing.variants.matching { it.name == "classes" }.configureEach { addJvmMainClasses() }
}

val jarFile = jar.flatMap { it.archiveFile }
pluginManager.withPlugin("java-test-fixtures") {
    tasks.named<KotlinCompile>("compileTestFixturesKotlin") {
        friendPaths.from(jarFile)
    }
}

val checkJvmMainDependencies =
    tasks.register<CheckJvmMainDependencies>("checkJvmMainDependencies") {
        group = "verification"
        description = "Fails when jvmMain's classpaths resolve artifacts that main's do not, or the reverse."
        val names = listOf("compileClasspath", "jvmMainCompileClasspath", "runtimeClasspath", "jvmMainRuntimeClasspath")
        for (name in names) {
            unresolved.addAll(CheckJvmMainDependencies.failuresOf(configurations.named(name)))
        }
        // `main`'s own classes, which the association puts on `jvmMain`'s classpaths.
        val own = sourceSets.getByName("main").output
        val none = files()
        mainCompile = CheckJvmMainDependencies.artifactsOf(configurations.named("compileClasspath"), none, rootDir)
        jvmMainCompile = CheckJvmMainDependencies.artifactsOf(configurations.named("jvmMainCompileClasspath"), own, rootDir)
        mainRuntime = CheckJvmMainDependencies.artifactsOf(configurations.named("runtimeClasspath"), none, rootDir)
        jvmMainRuntime = CheckJvmMainDependencies.artifactsOf(configurations.named("jvmMainRuntimeClasspath"), own, rootDir)
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
