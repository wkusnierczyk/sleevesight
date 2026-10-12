// An Android application: SDK levels from the architecture (D10), Java 17, and the shared checks,
// plus `checkManifest` on the merged release manifest.
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import sleevesight.checks.CheckDependencies
import sleevesight.checks.CheckManifest
import sleevesight.checks.CheckModuleGraph
import sleevesight.checks.networkModules

plugins {
    id("com.android.application")
    id("sleevesight.quality")
    id("sleevesight.locking")
}

extensions.configure<ApplicationExtension> {
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        targetSdk = 36
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val graphRules = rootProject.layout.projectDirectory.file("config/module-graph.properties")

// Aggregates, so `./gradlew checkManifest` runs the check for every variant: release ships, and
// debug builds the APK that CI publishes and phones install.
val checkManifest = tasks.register("checkManifest") { group = "verification" }
val checkDependencies = tasks.register("checkDependencies") { group = "verification" }
val checkModuleGraph = tasks.register("checkModuleGraph") { group = "verification" }

androidComponents {
    onVariants { variant ->
        val suffix = variant.name.replaceFirstChar { it.uppercase() }
        val runtimeRoot = variant.runtimeConfiguration.incoming.resolutionResult.rootComponent
        val compileRoot = variant.compileConfiguration.incoming.resolutionResult.rootComponent

        val manifest =
            tasks.register<CheckManifest>("checkManifest$suffix") {
                group = "verification"
                description = "Fails on permissions outside config/permission-allowlist.txt."
                mergedManifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
                allowlist = rootProject.layout.projectDirectory.file("config/permission-allowlist.txt")
                applicationId = variant.applicationId
            }
        checkManifest.configure { dependsOn(manifest) }

        val dependencies =
            tasks.register<CheckDependencies>("checkDependencies$suffix") {
                group = "verification"
                description = "Fails on runtime dependencies outside config/dependency-allowlist.txt."
                allowlist = rootProject.layout.projectDirectory.file("config/dependency-allowlist.txt")
                modules = CheckDependencies.modulesOf(runtimeRoot)
            }
        checkDependencies.configure { dependsOn(dependencies) }

        val graph =
            tasks.register<CheckModuleGraph>("checkModuleGraph$suffix") {
                group = "verification"
                description = "Fails on dependencies the module graph does not allow."
                rules = graphRules
                modulePath = project.path
                projectDependencies = CheckModuleGraph.projectDependenciesOf(runtimeRoot, compileRoot)
                modulesOutsideNetwork =
                    CheckModuleGraph.modulesOutsideNetworkOf(runtimeRoot, networkModules(graphRules.asFile))
                        .zip(CheckModuleGraph.modulesOutsideNetworkOf(compileRoot, networkModules(graphRules.asFile))) { a, b ->
                            (a + b).distinct().sorted()
                        }
            }
        checkModuleGraph.configure { dependsOn(graph) }
    }
}
