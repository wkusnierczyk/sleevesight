// An Android application: SDK levels from the architecture (D10), Java 17, Compose,
// kotlinx.serialization (Navigation Compose's type-safe routes are serializable), and the shared
// checks, plus `checkManifest` on each variant's merged manifest, `checkApkClasses` and
// `checkApkNotices` on each variant's APK (in `check`), and `checkDependencyPage` on the release
// variant's classpath. Each variant's assets get the list of the libraries it ships, with their
// licences (`listLibraryLicences`, #488). The release build is shrunk by R8 and signed with the
// upload key when the environment names one (`design/release.md`).
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.HasAndroidTest
import com.android.build.api.variant.HasUnitTest
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import sleevesight.checks.CheckApkClasses
import sleevesight.checks.CheckApkNotices
import sleevesight.checks.CheckDependencies
import sleevesight.checks.CheckDependencyPage
import sleevesight.checks.CheckManifest
import sleevesight.checks.CheckModuleGraph
import sleevesight.checks.ListLibraryLicences
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
        // 37 since #147, which checked Android 17's behaviour changes for apps targeting it.
        targetSdk = 37
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
// In `check`, so CI's `build` packages and reads the APKs. It matters most for debug, the APK phones
// install: R8 renames most of the release build's classes, and it fails on a missing class itself.
val checkApkClasses = tasks.register("checkApkClasses") { group = "verification" }
tasks.named("check") { dependsOn(checkApkClasses) }
val checkApkNotices = tasks.register("checkApkNotices") { group = "verification" }
tasks.named("check") { dependsOn(checkApkNotices) }
// Android lint on debug, the variant phones install, with the pre-push hook's other static checks.
tasks.named("staticChecks") { dependsOn("lintDebug") }

androidComponents {
    onVariants { variant ->
        val suffix = variant.name.replaceFirstChar { it.uppercase() }
        val runtimeRoot = variant.runtimeConfiguration.incoming.resolutionResult.rootComponent
        val compileRoot = variant.compileConfiguration.incoming.resolutionResult.rootComponent
        // The variant's tests, unit and instrumented: they are the module's code too, so a module kept
        // off the variant's compile classpath (`compile-forbidden`) is kept off theirs, and a direct
        // dependency they declare, at compile or at run time, is held to the module's list. The
        // HTTP-client rule (`modulesOutsideNetwork`) reads the variant's own classpaths only.
        val tests =
            listOfNotNull((variant as? HasUnitTest)?.unitTest, (variant as? HasAndroidTest)?.androidTest)
        val compileRoots =
            (listOf(variant.compileConfiguration) + tests.map { it.compileConfiguration })
                .map { it.incoming.resolutionResult.rootComponent }
                .toTypedArray()
        val testRuntimeRoots =
            tests.map { it.runtimeConfiguration.incoming.resolutionResult.rootComponent }.toTypedArray()

        val manifest =
            tasks.register<CheckManifest>("checkManifest$suffix") {
                group = "verification"
                description = "Fails on permissions outside config/permission-allowlist.txt, or on cleartext allowed."
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

        // The page lists what ships, so it is held to the release variant's classpath alone; the
        // debug APK's extras (Compose's tooling) are not on it.
        if (variant.name == "release") {
            tasks.register<CheckDependencyPage>("checkDependencyPage") {
                group = "verification"
                description = "Fails when docs/dependencies/index.md and the shipped runtime classpath disagree."
                page = rootProject.layout.projectDirectory.file("docs/dependencies/index.md")
                modules = CheckDependencies.modulesOf(runtimeRoot)
            }
        }

        val graph =
            tasks.register<CheckModuleGraph>("checkModuleGraph$suffix") {
                group = "verification"
                description = "Fails on dependencies the module graph does not allow."
                rules = graphRules
                modulePath = project.path
                projectDependencies = CheckModuleGraph.projectDependenciesOf(runtimeRoot, *compileRoots, *testRuntimeRoots)
                // The compile classpaths only: `:net` is on the runtime classpath, since the APK ships it.
                compileClasspathProjects = CheckModuleGraph.projectsReachableFrom(*compileRoots)
                modulesOutsideNetwork =
                    CheckModuleGraph.modulesOutsideNetworkOf(runtimeRoot, networkModules(graphRules.asFile))
                        .zip(CheckModuleGraph.modulesOutsideNetworkOf(compileRoot, networkModules(graphRules.asFile))) { a, b ->
                            (a + b).distinct().sorted()
                        }
            }
        checkModuleGraph.configure { dependsOn(graph) }

        val apkClasses =
            tasks.register<CheckApkClasses>("checkApkClasses$suffix") {
                group = "verification"
                description = "Fails when the APK's code refers to an app.sleevesight class no dex file of it defines."
                apkDirectory = variant.artifacts.get(SingleArtifact.APK)
                prefix = "Lapp/sleevesight/"
            }
        checkApkClasses.configure { dependsOn(apkClasses) }

        // The licence POMs: the shipped modules' POMs, and level by level their parents', since a
        // POM may leave its licence to its parent. Each level is one configuration, so a parent
        // two modules name at different versions resolves to one of them, and the other module's
        // licence is then not found: the list fails on it rather than guessing.
        val pomLevels =
            (0 until ListLibraryLicences.POM_LEVELS).fold(listOf<NamedDomainObjectProvider<out Configuration>>()) { levels, level ->
                val coordinates =
                    levels.lastOrNull()
                        ?.flatMap { it.incoming.artifacts.resolvedArtifacts }
                        ?.map { poms -> ListLibraryLicences.parentsOf(poms.map { it.file.readText() }) }
                        ?: CheckDependencies.modulesOf(runtimeRoot)
                levels +
                    configurations.resolvable("${variant.name}LicencePoms$level") {
                        isTransitive = false
                        val poms = coordinates.map { all -> all.map { project.dependencies.create("$it@pom") } }
                        (this as Configuration).dependencies.addAllLater(poms)
                    }
            }
        // The modules' own jars and AARs, unprocessed, for their LICENSE and NOTICE files.
        val shippedArtifacts =
            variant.runtimeConfiguration.incoming.artifactView {
                componentFilter { it is ModuleComponentIdentifier }
            }.artifacts.resolvedArtifacts
        val libraryLicences =
            tasks.register<ListLibraryLicences>("listLibraryLicences$suffix") {
                group = "verification"
                description = "Lists the shipped libraries with their licences for the credits; fails on a missing notice."
                modules = CheckDependencies.modulesOf(runtimeRoot)
                poms.from(pomLevels)
                artifactModules =
                    shippedArtifacts.map { all ->
                        all.map { artifact ->
                            val id = artifact.id.componentIdentifier as ModuleComponentIdentifier
                            "${id.group}:${id.module}:${id.version}\t${artifact.file.path}"
                        }
                    }
                artifactFiles.from(shippedArtifacts.map { all -> all.map { it.file } })
                noticeTable = rootProject.layout.projectDirectory.file("config/licence-notices.txt")
                noticeDirectory = layout.projectDirectory.dir("src/main/assets/licences")
                outputDirectory = layout.buildDirectory.dir("generated/licences/${variant.name}")
            }
        variant.sources.assets?.addGeneratedSourceDirectory(libraryLicences, ListLibraryLicences::outputDirectory)

        val apkNotices =
            tasks.register<CheckApkNotices>("checkApkNotices$suffix") {
                group = "verification"
                description = "Fails when the APK lacks its list of libraries or a notice the list names."
                apkDirectory = variant.artifacts.get(SingleArtifact.APK)
            }
        checkApkNotices.configure { dependsOn(apkNotices) }
    }
}
