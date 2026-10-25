import sleevesight.release.ReleaseVersion

plugins {
    id("sleevesight.android-app")
}

// A release takes its version from its tag, `v<major>.<minor>.<patch>`, which the release workflow
// passes as `-Psleevesight.version` (`design/release.md`, *Versioning*); other builds are 0.1.0.
val appVersion = ReleaseVersion.parse(providers.gradleProperty("sleevesight.version").getOrElse("0.1.0"))

// Developer mode's default (#451): on in a build for Play's testing tracks, which the release
// workflow makes with `-Psleevesight.developerMode=true` (`design/release.md`, *Test and production
// builds*), so testers reach *Diagnostics* without seven taps; off in every other build. Only the
// default: a choice the user stored wins. Anything but `true` or `false` fails the build.
val developerModeDefault =
    when (val value = providers.gradleProperty("sleevesight.developerMode").getOrElse("false")) {
        "true" -> true
        "false" -> false
        else -> throw GradleException("sleevesight.developerMode is `$value`, not `true` or `false`")
    }

android {
    namespace = "app.sleevesight.app"
    // `BuildConfig.VERSION_NAME`, for the User-Agent the app's one `NetClient` sends (`:wiring`), and
    // `BuildConfig.DEVELOPER_MODE_DEFAULT`, the settings' default for developer mode.
    buildFeatures.buildConfig = true
    defaultConfig {
        applicationId = "app.sleevesight"
        versionCode = appVersion.code
        versionName = appVersion.name
        buildConfigField("boolean", "DEVELOPER_MODE_DEFAULT", developerModeDefault.toString())
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
    // The domain types the session layer maps to (`NowPlaying`), and `Settings`, `Secret` and the
    // `AiProvider` interface the settings screen lists models through.
    implementation(project(":core"))
    // The session history and the record of track changes (#49), on the Android SQLite driver.
    implementation(project(":store"))
    implementation(libs.sqldelight.android.driver)
    // The pipeline's adapters, resolver, AI providers and art fetch, on the process's one
    // `NetClient`, which `:wiring` creates and keeps: `:app` gets `:core` types only, and `:net`
    // stays off its compile classpath (`compile-forbidden.app` in `config/module-graph.properties`).
    implementation(project(":wiring"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)
    // Reads an API key's QR code from the camera's frames, on the phone (#268). Not Google's code
    // scanner, whose ML Kit reports usage to Google; the camera is Android's own API, not CameraX.
    implementation(libs.zxing.core)
    // The now-playing screen's colours from the album art (design, *User interface*).
    implementation(libs.materialkolor.utilities)
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
    // The history's tests run on an in-memory database through the JDBC driver, as `:store`'s do.
    testImplementation(libs.sqldelight.sqlite.driver)
}
