// A plain Kotlin JVM module: Java 17 bytecode, `kotlin.test` on the JUnit 5 runner, the shared
// checks. The pipeline modules apply `sleevesight.pipeline` instead, which adds the
// Multiplatform-readiness rules.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import sleevesight.checks.CheckDependencies
import sleevesight.checks.CheckModuleGraph

plugins {
    kotlin("jvm")
    id("sleevesight.quality")
    id("sleevesight.locking")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    "testImplementation"(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val runtimeRoot = configurations.named("runtimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }

tasks.register<CheckDependencies>("checkDependencies") {
    group = "verification"
    description = "Fails on runtime dependencies outside config/dependency-allowlist.txt."
    allowlist = rootProject.layout.projectDirectory.file("config/dependency-allowlist.txt")
    modules = CheckDependencies.modulesOf(runtimeRoot)
}

tasks.register<CheckModuleGraph>("checkModuleGraph") {
    group = "verification"
    description = "Fails on direct dependencies the module graph does not allow."
    rules = rootProject.layout.projectDirectory.file("config/module-graph.properties")
    modulePath = project.path
    directDependencies = CheckModuleGraph.directOf(runtimeRoot)
}
