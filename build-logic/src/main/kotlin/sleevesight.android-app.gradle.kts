// An Android application: SDK levels from the architecture (D10), Java 17, Compose,
// kotlinx.serialization (Navigation Compose's type-safe routes are serializable), and the shared
// checks, plus `checkManifest` on each variant's merged manifest. The release build is shrunk by
// R8 and signed with the upload key when the environment names one (`design/release.md`).
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import sleevesight.checks.CheckDependencies
import sleevesight.checks.CheckManifest
import sleevesight.checks.CheckModuleGraph
import sleevesight.checks.networkModules
import sleevesight.release.UploadSigning

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("sleevesight.quality")
    id("sleevesight.locking")
}

extensions.configure<ApplicationExtension> {
    // 37 because `okhttp-android` 5.5.0's AAR metadata requires it (`minCompileSdk`).
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        // 36 until Android 17's behaviour changes are checked (#147).
        targetSdk = 36
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Without the key, the release build is unsigned: `./gradlew build` assembles it anywhere, and
    // only the release workflow, which has the key, produces a bundle Google Play takes.
    val upload = UploadSigning.from { providers.environmentVariable(it).orNull }
    val uploadConfig =
        upload?.let {
            signingConfigs.create("upload") {
                storeFile = file(it.storeFile)
                storePassword = it.storePassword
                keyAlias = it.keyAlias
                keyPassword = it.keyPassword
            }
        }
    buildTypes {
        release {
            // R8 shrinks, optimizes and obfuscates the code, and drops unused resources. The
            // libraries bring their own keep rules (kotlinx.serialization, coroutines, Ktor); the
            // module's `proguard-rules.pro` adds what they lack.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = uploadConfig
        }
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
                // The variant's compile classpath; `:net` is on its runtime classpath, since the APK ships it.
                compileClasspathProjects = CheckModuleGraph.projectsReachableFrom(compileRoot)
                modulesOutsideNetwork =
                    CheckModuleGraph.modulesOutsideNetworkOf(runtimeRoot, networkModules(graphRules.asFile))
                        .zip(CheckModuleGraph.modulesOutsideNetworkOf(compileRoot, networkModules(graphRules.asFile))) { a, b ->
                            (a + b).distinct().sorted()
                        }
            }
        checkModuleGraph.configure { dependsOn(graph) }
    }
}
