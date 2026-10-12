// An Android application: SDK levels from the architecture (D10), Java 17, and the shared checks,
// plus `checkManifest` on the merged release manifest.
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import sleevesight.checks.CheckDependencies
import sleevesight.checks.CheckManifest
import sleevesight.checks.CheckModuleGraph

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

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val runtimeRoot = variant.runtimeConfiguration.incoming.resolutionResult.rootComponent

        tasks.register<CheckManifest>("checkManifest") {
            group = "verification"
            description = "Fails on permissions outside config/permission-allowlist.txt."
            mergedManifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
            allowlist = rootProject.layout.projectDirectory.file("config/permission-allowlist.txt")
            applicationId = variant.applicationId
        }

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
    }
}
