// The platform side of a Multiplatform-ready module (architecture, Multiplatform readiness, D2):
// `src/jvmMain/kotlin` is a source set of its own, compiled after `main` against `main`'s classes
// (and its `internal` declarations), while `main` cannot see it. So portable code that refers to
// a JVM implementation does not compile, however it is written. The module's jar, its outgoing
// variants, its tests and its test fixtures carry both, so consumers see one module as before.
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import sleevesight.checks.CheckSourceRoots

plugins {
    kotlin("jvm")
}

val sourceSets = extensions.getByType<SourceSetContainer>()
val jvmMain = sourceSets.create("jvmMain")

kotlin.target.compilations.named("jvmMain") {
    associateWith(kotlin.target.compilations.getByName("main"))
}

tasks.named<Jar>("jar") {
    from(jvmMain.output)
}

// Consumers in the same build compile against the `classes` variant, not the jar.
for (elements in listOf("apiElements", "runtimeElements")) {
    configurations.named(elements) {
        outgoing.variants.matching { it.name == "classes" }.configureEach {
            jvmMain.output.classesDirs.files.forEach { dir ->
                artifact(dir) {
                    type = ArtifactTypeDefinition.JVM_CLASS_DIRECTORY
                    builtBy(jvmMain.output)
                }
            }
        }
    }
}

sourceSets.named("test") {
    compileClasspath += jvmMain.output
    runtimeClasspath += jvmMain.output
}

pluginManager.withPlugin("java-test-fixtures") {
    sourceSets.named("testFixtures") {
        compileClasspath += jvmMain.output
        runtimeClasspath += jvmMain.output
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
