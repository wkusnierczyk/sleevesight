import sleevesight.release.ReleaseVersion

plugins {
    id("sleevesight.android-app")
}

// A release takes its version from its tag, `v<major>.<minor>.<patch>`, which the release workflow
// passes as `-Psleevesight.version` (`design/release.md`, *Versioning*); other builds are 0.1.0.
val appVersion = ReleaseVersion.parse(providers.gradleProperty("sleevesight.version").getOrElse("0.1.0"))

android {
    namespace = "app.sleevesight.app"
    defaultConfig {
        applicationId = "app.sleevesight"
        versionCode = appVersion.code
        versionName = appVersion.name
    }
    testOptions {
        // Robolectric reads the app's resources and manifest, and sets a `FileDescriptor`'s
        // descriptor through the JDK's internal `SharedSecrets`, which needs that package exported.
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
    buildTypes {
        // The debug APK (the CI artifact) installs beside a release from Google Play.
        debug {
            applicationIdSuffix = ".debug"
        }
    }
}

dependencies {
    // The domain types the session layer maps to (`NowPlaying`).
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.core)
    // Navigation's typed routes compile against it; declared so compile and runtime agree with
    // the version `:core` brings at runtime.
    implementation(libs.kotlinx.serialization.core)
    // Renders the previews in the IDE; debug only.
    debugImplementation(libs.androidx.compose.ui.tooling)

    // JUnit 4, which Robolectric needs (architecture, *Technology choices*).
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.junit4)
    // Virtual time for the session layer's settle window.
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
