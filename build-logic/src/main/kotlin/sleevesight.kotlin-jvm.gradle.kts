// A plain Kotlin JVM module: Java 17 bytecode, `kotlin.test` on the JUnit 5 runner, the shared
// checks. The pipeline modules apply `sleevesight.pipeline` instead, which adds the
// Multiplatform-readiness rules.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import sleevesight.checks.CheckDependencies
import sleevesight.checks.CheckModuleGraph
import sleevesight.checks.networkModules

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

// Two test JVMs, as in the app (`sleevesight.android-app`).
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxParallelForks = 2
}

val runtimeRoot = configurations.named("runtimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
val compileRoot = configurations.named("compileClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
// The tests are the module's code too, so a module kept off its compile classpath is kept off theirs,
// and a direct dependency they declare, at compile or at run time, is held to the module's list.
val testCompileRoot = configurations.named("testCompileClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
val testRuntimeRoot = configurations.named("testRuntimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
val graphRules = rootProject.layout.projectDirectory.file("config/module-graph.properties")

tasks.register<CheckDependencies>("checkDependencies") {
    group = "verification"
    description = "Fails on runtime dependencies outside config/dependency-allowlist.txt."
    allowlist = rootProject.layout.projectDirectory.file("config/dependency-allowlist.txt")
    modules = CheckDependencies.modulesOf(runtimeRoot)
}

tasks.register<CheckModuleGraph>("checkModuleGraph") {
    group = "verification"
    description = "Fails on dependencies the module graph does not allow."
    rules = graphRules
    modulePath = project.path
    projectDependencies = CheckModuleGraph.projectDependenciesOf(runtimeRoot, compileRoot, testCompileRoot, testRuntimeRoot)
    compileClasspathProjects = CheckModuleGraph.projectsReachableFrom(compileRoot, testCompileRoot)
    modulesOutsideNetwork =
        CheckModuleGraph.modulesOutsideNetworkOf(runtimeRoot, networkModules(graphRules.asFile))
            .zip(CheckModuleGraph.modulesOutsideNetworkOf(compileRoot, networkModules(graphRules.asFile))) { a, b ->
                (a + b).distinct().sorted()
            }
}
